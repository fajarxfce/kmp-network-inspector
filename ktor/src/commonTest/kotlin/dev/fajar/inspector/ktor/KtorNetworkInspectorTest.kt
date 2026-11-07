package dev.fajar.inspector.ktor

import dev.fajar.inspector.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import kotlinx.io.readString

class KtorNetworkInspectorTest {
    @Test
    fun pendingAttemptBecomesTheActualResponseWithoutRetainingCredentials() = runTest {
        val store = NetworkTraceStore()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val client =
            HttpClient(
                MockEngine {
                    entered.complete(Unit)
                    release.await()
                    respond(
                        "original body",
                        HttpStatusCode.Created,
                        headersOf(
                            "Content-Type" to listOf("text/plain"),
                            "Set-Cookie" to listOf("private-cookie"),
                        ),
                    )
                }
            ) {
                install(KtorNetworkInspector) { recorder = store }
            }
        try {
            val request = async {
                client
                    .get("https://user:private-password@example.test/items?token=private-query") {
                        header("Authorization", "Bearer private-token")
                    }
                    .bodyAsText()
            }
            entered.await()
            val pending = store.snapshots.value.traces.single()
            assertNull(pending.outcome)
            assertEquals("example.test", pending.request.endpoint.host)
            assertEquals(443, pending.request.endpoint.port)
            assertEquals(listOf("token"), pending.request.endpoint.queryNames)
            assertEquals(
                "[redacted]",
                pending.request.headers.single { it.name == "Authorization" }.value,
            )
            release.complete(Unit)
            assertEquals("original body", request.await())
            val completed = store.snapshots.value.traces.single()
            assertEquals(pending.id, completed.id)
            assertEquals(NetworkOutcome.Response(201), completed.outcome)
            assertNotNull(completed.durationMillis)
            assertNull(completed.responseBytes, "Header length is not measured body consumption")
            assertEquals(
                "[redacted]",
                completed.responseHeaders.single { it.name == "Set-Cookie" }.value,
            )
        } finally {
            release.complete(Unit)
            client.close()
            store.close()
        }
    }

    @Test
    fun everyRedirectGetsItsOwnAttemptAndOriginalResponseStatus() = runTest {
        val store = NetworkTraceStore()
        var requests = 0
        val client =
            HttpClient(
                MockEngine { request ->
                    requests++
                    if (request.url.encodedPath == "/start")
                        respond("", HttpStatusCode.Found, headersOf("Location", "/end"))
                    else respond("final")
                }
            ) {
                install(KtorNetworkInspector) { recorder = store }
            }
        try {
            assertEquals("final", client.get("https://example.test/start").bodyAsText())
            assertEquals(2, requests)
            val rows = store.snapshots.value.traces.reversed()
            assertEquals(listOf("/start", "/end"), rows.map { it.request.endpoint.path })
            assertEquals(
                listOf(NetworkOutcome.Response(302), NetworkOutcome.Response(200)),
                rows.map { it.outcome },
            )
            assertEquals(2, rows.map { it.id }.distinct().size)
        } finally {
            client.close()
            store.close()
        }
    }

    @Test
    fun retryAttemptsAreSeparateRegardlessOfPluginInstallationOrder() = runTest {
        for (inspectorFirst in listOf(false, true)) {
            val store = NetworkTraceStore()
            var requests = 0
            val client =
                HttpClient(
                    MockEngine {
                        requests++
                        respond(
                            "response",
                            if (requests == 1) HttpStatusCode.ServiceUnavailable
                            else HttpStatusCode.OK,
                        )
                    }
                ) {
                    if (inspectorFirst) install(KtorNetworkInspector) { recorder = store }
                    install(HttpRequestRetry) {
                        retryOnServerErrors(maxRetries = 1)
                        delay {}
                    }
                    if (!inspectorFirst) install(KtorNetworkInspector) { recorder = store }
                }
            try {
                assertEquals(200, client.get("https://example.test/retry").status.value)
                assertEquals(2, requests)
                assertEquals(
                    listOf(NetworkOutcome.Response(503), NetworkOutcome.Response(200)),
                    store.snapshots.value.traces.reversed().map { it.outcome },
                )
            } finally {
                client.close()
                store.close()
            }
        }
    }

    @Test
    fun validationFailureDoesNotReplaceTheObservedHttpStatus() = runTest {
        val store = NetworkTraceStore()
        val client =
            HttpClient(MockEngine { respond("private response", HttpStatusCode.Unauthorized) }) {
                expectSuccess = true
                install(KtorNetworkInspector) { recorder = store }
            }
        try {
            assertFailsWith<ClientRequestException> { client.get("https://example.test/denied") }
            assertEquals(
                NetworkOutcome.Response(401),
                store.snapshots.value.traces.single().outcome,
            )
        } finally {
            client.close()
            store.close()
        }
    }

    @Test
    fun originalTransportFailurePropagatesWithoutItsMessageBeingRecorded() = runTest {
        val store = NetworkTraceStore()
        val original = FixtureTransportFailure(Any())
        val client =
            HttpClient(MockEngine { throw original }) {
                install(KtorNetworkInspector) { recorder = store }
            }
        try {
            assertSame(
                original,
                assertFailsWith<FixtureTransportFailure> {
                    client.get("https://example.test/failure")
                },
            )
            assertEquals(
                NetworkOutcome.Failed(NetworkFailure.UNKNOWN),
                store.snapshots.value.traces.single().outcome,
            )
        } finally {
            client.close()
            store.close()
        }
    }

    @Test
    fun cancellationFinishesTheAttemptAndStillCancelsTheEngine() = runTest {
        val store = NetworkTraceStore()
        val entered = CompletableDeferred<Unit>()
        val stopped = CompletableDeferred<Unit>()
        val client =
            HttpClient(
                MockEngine {
                    entered.complete(Unit)
                    try {
                        awaitCancellation()
                    } finally {
                        stopped.complete(Unit)
                    }
                }
            ) {
                install(KtorNetworkInspector) { recorder = store }
            }
        try {
            val request = async { client.get("https://example.test/wait") }
            entered.await()
            request.cancel()
            request.join()
            assertFailsWith<CancellationException> { request.await() }
            stopped.await()
            assertEquals(
                NetworkOutcome.Failed(NetworkFailure.CANCELLED),
                store.snapshots.value.traces.single().outcome,
            )
        } finally {
            client.close()
            store.close()
        }
    }

    @Test
    fun responseIsObservableBeforeStreamingBodyArrivesAndTheConsumerKeepsItsBytes() = runTest {
        val store = NetworkTraceStore()
        val channel = ByteChannel(autoFlush = true)
        val consumerReady = CompletableDeferred<Unit>()
        val client =
            HttpClient(MockEngine { respond(channel, HttpStatusCode.OK) }) {
                install(KtorNetworkInspector) { recorder = store }
            }
        try {
            val request = async {
                client.prepareGet("https://example.test/stream").execute { response ->
                    assertEquals(
                        NetworkOutcome.Response(200),
                        store.snapshots.value.traces.single().outcome,
                    )
                    consumerReady.complete(Unit)
                    response.bodyAsChannel().readRemaining().readString()
                }
            }
            consumerReady.await()
            channel.writeStringUtf8("first")
            channel.writeStringUtf8(" second")
            channel.close()
            assertEquals("first second", request.await())
            assertNull(store.snapshots.value.traces.single().responseBytes)
        } finally {
            channel.cancel()
            client.close()
            store.close()
        }
    }

    @Test
    fun clearAndCloseFenceInflightCaptureWithoutInterruptingTheRequest() = runTest {
        for (close in listOf(false, true)) {
            val store = NetworkTraceStore()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val client =
                HttpClient(
                    MockEngine {
                        entered.complete(Unit)
                        release.await()
                        respond("still delivered")
                    }
                ) {
                    install(KtorNetworkInspector) { recorder = store }
                }
            try {
                val request = async { client.get("https://example.test/pending").bodyAsText() }
                entered.await()
                if (close) store.close() else store.clear()
                release.complete(Unit)
                assertEquals("still delivered", request.await())
                assertTrue(store.snapshots.value.traces.isEmpty())
                assertEquals(close, store.snapshots.value.closed)
            } finally {
                release.complete(Unit)
                client.close()
                store.close()
            }
        }
    }

    @Test
    fun aBrokenOrExcludingPathFilterCannotChangeTheTransport() = runTest {
        for (exclude in listOf(false, true)) {
            val store = NetworkTraceStore()
            var requests = 0
            val client =
                HttpClient(
                    MockEngine {
                        requests++
                        respond("ok")
                    }
                ) {
                    install(KtorNetworkInspector) {
                        recorder = store
                        sanitizePath = { if (exclude) null else error("private filter failure") }
                    }
                }
            try {
                assertEquals("ok", client.get("https://example.test/excluded").bodyAsText())
                assertEquals(1, requests)
                assertTrue(store.snapshots.value.traces.isEmpty())
            } finally {
                client.close()
                store.close()
            }
        }
    }

    @Test
    fun simultaneousAttemptsFinishingOutOfOrderKeepTheirOwnEndpointAndStatus() = runTest {
        val store = NetworkTraceStore()
        val firstEntered = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val client =
            HttpClient(
                MockEngine { request ->
                    if (request.url.encodedPath == "/first") {
                        firstEntered.complete(Unit)
                        releaseFirst.await()
                        respond("first", HttpStatusCode.Created)
                    } else respond("second", HttpStatusCode.Accepted)
                }
            ) {
                install(KtorNetworkInspector) { recorder = store }
            }
        try {
            val first = async { client.get("https://example.test/first").bodyAsText() }
            firstEntered.await()
            assertEquals("second", client.get("https://example.test/second").bodyAsText())
            releaseFirst.complete(Unit)
            assertEquals("first", first.await())
            assertEquals(
                mapOf(
                    "/first" to NetworkOutcome.Response(201),
                    "/second" to NetworkOutcome.Response(202),
                ),
                store.snapshots.value.traces.associate { it.request.endpoint.path to it.outcome },
            )
        } finally {
            releaseFirst.complete(Unit)
            client.close()
            store.close()
        }
    }

    @Test
    fun requestTimeoutIsDistinctFromUserCancellation() = runTest {
        val store = NetworkTraceStore()
        val client =
            HttpClient(MockEngine { awaitCancellation() }) {
                install(HttpTimeout) { requestTimeoutMillis = 100 }
                install(KtorNetworkInspector) { recorder = store }
            }
        try {
            assertFailsWith<HttpRequestTimeoutException> {
                client.get("https://example.test/timeout")
            }
            assertEquals(
                NetworkOutcome.Failed(NetworkFailure.TIMEOUT),
                store.snapshots.value.traces.single().outcome,
            )
        } finally {
            client.close()
            store.close()
        }
    }

    @Test
    fun failedBodyRemainsAConsumerFailureAfterTheResponseHeadersWereObserved() = runTest {
        for (capture in listOf(false, true)) {
            val store = NetworkTraceStore()
            val channel = ByteChannel(autoFlush = true)
            val ready = CompletableDeferred<Unit>()
            val client =
                HttpClient(MockEngine { respond(channel, HttpStatusCode.OK) }) {
                    if (capture) install(KtorNetworkInspector) { recorder = store }
                }
            try {
                supervisorScope {
                    val request = async {
                        client.prepareGet("https://example.test/body-failure").execute { response ->
                            val body = response.bodyAsChannel()
                            assertEquals(7.toByte(), body.readByte())
                            ready.complete(Unit)
                            // A real read observes the failure even when it arrives before this
                            // suspension; readRemaining on an already closed empty stream may not.
                            body.readByte()
                        }
                    }
                    channel.writeByte(7)
                    ready.await()
                    channel.close(IOException("private streaming diagnostic"))
                    assertFailsWith<IOException> { request.await() }
                    if (capture)
                        assertEquals(
                            NetworkOutcome.Response(200),
                            store.snapshots.value.traces.single().outcome,
                        )
                    else assertTrue(store.snapshots.value.traces.isEmpty())
                }
            } finally {
                channel.cancel()
                client.close()
                store.close()
            }
        }
    }

    @Test
    fun oversizedMetadataIsBoundedBeforeCallingTheHostFilterOrRetainingFields() = runTest {
        val store = NetworkTraceStore()
        var filterInput = 0
        val client =
            HttpClient(MockEngine { respond("ok") }) {
                install(KtorNetworkInspector) {
                    recorder = store
                    sanitizePath = {
                        filterInput = it.length
                        it
                    }
                }
            }
        try {
            assertEquals(
                "ok",
                client
                    .get("https://example.test/" + "a".repeat(100_000)) {
                        repeat(100) {
                            parameter("field$it", "private-query-value")
                            header("X-Field-$it", "private-header-value")
                        }
                    }
                    .bodyAsText(),
            )
            val request = store.snapshots.value.traces.single().request
            assertEquals(2_049, filterInput)
            assertEquals(2_048, request.endpoint.path.length)
            assertEquals(32, request.endpoint.queryNames.size)
            assertEquals(64, request.headers.size)
            assertTrue(request.metadataTruncated)
            assertTrue(request.headers.all { it.value == "[redacted]" })
        } finally {
            client.close()
            store.close()
        }
    }
}

/** Additional immutable data prevents coroutine stacktrace recovery from copying this sentinel. */
private class FixtureTransportFailure(val marker: Any) : Exception("private transport diagnostic")
