package dev.fajar.inspector.ktor

import dev.fajar.inspector.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest

class KtorInspectionLifetimeTest {
    @Test
    fun resetDuringRequestPreservesBytesButCannotPopulateTheNewCapture() = runTest {
        val inspection = NetworkInspection()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var lookups = 0
        val client =
            HttpClient(
                MockEngine {
                    entered.complete(Unit)
                    release.await()
                    respond("original")
                }
            ) {
                install(KtorNetworkInspector) {
                    recorderProvider = {
                        lookups++
                        inspection.recorders.value
                    }
                }
            }
        try {
            val old = assertNotNull(inspection.recorders.value)
            val request = async { client.get("https://example.test/original").bodyAsText() }
            entered.await()
            assertEquals(1, old.snapshots.value.traces.size)
            inspection.reset()
            val current = assertNotNull(inspection.recorders.value)
            release.complete(Unit)
            assertEquals("original", request.await())
            assertEquals(1, lookups)
            assertTrue(old.snapshots.value.traces.isEmpty())
            assertTrue(current.snapshots.value.traces.isEmpty())
            assertEquals("original", client.get("https://example.test/new").bodyAsText())
            assertEquals("/new", current.snapshots.value.traces.single().request.endpoint.path)
        } finally {
            release.complete(Unit)
            client.close()
            inspection.close()
        }
    }

    @Test
    fun retryAfterResetUsesANewRecorderAndPreservesBothHttpOutcomes() = runTest {
        val inspection = NetworkInspection()
        val first = assertNotNull(inspection.recorders.value)
        var calls = 0
        var oldOutcome: NetworkOutcome? = null
        val client =
            HttpClient(
                MockEngine {
                    calls++
                    respond(
                        "body",
                        if (calls == 1) HttpStatusCode.ServiceUnavailable else HttpStatusCode.OK,
                    )
                }
            ) {
                install(KtorNetworkInspector) { recorderProvider = { inspection.recorders.value } }
                install(HttpRequestRetry) {
                    retryOnServerErrors(maxRetries = 1)
                    delay {
                        oldOutcome = first.snapshots.value.traces.single().outcome
                        inspection.reset()
                    }
                }
            }
        try {
            assertEquals("body", client.get("https://example.test/retry").bodyAsText())
            assertEquals(2, calls)
            assertEquals(NetworkOutcome.Response(503), oldOutcome)
            assertEquals(
                NetworkOutcome.Response(200),
                inspection.recorders.value!!.snapshots.value.traces.single().outcome,
            )
            assertTrue(first.snapshots.value.traces.isEmpty())
        } finally {
            client.close()
            inspection.close()
        }
    }

    @Test
    fun closedOrFailedProviderCannotCancelOrChangeRequests() = runTest {
        for (failure in
            listOf(null, IllegalStateException("fixture"), CancellationException("fixture"))) {
            var paths = 0
            val client =
                HttpClient(MockEngine { respond("unchanged") }) {
                    install(KtorNetworkInspector) {
                        recorderProvider = { if (failure != null) throw failure else null }
                        sanitizePath = {
                            paths++
                            it
                        }
                    }
                }
            try {
                assertEquals("unchanged", client.get("https://example.test/item").bodyAsText())
                assertEquals(0, paths)
            } finally {
                client.close()
            }
        }
    }

    @Test
    fun closeDuringBodyTransferDoesNotInterruptTheOriginalResponse() = runTest {
        val inspection = NetworkInspection()
        val client =
            HttpClient(MockEngine { respond("unchanged body") }) {
                install(KtorNetworkInspector) { recorderProvider = { inspection.recorders.value } }
            }
        try {
            client.prepareGet("https://example.test/item").execute { response ->
                inspection.close()
                assertEquals("unchanged body", response.bodyAsText())
            }
            assertEquals("unchanged body", client.get("https://example.test/after").bodyAsText())
            assertNull(inspection.recorders.value)
        } finally {
            client.close()
            inspection.close()
        }
    }
}
