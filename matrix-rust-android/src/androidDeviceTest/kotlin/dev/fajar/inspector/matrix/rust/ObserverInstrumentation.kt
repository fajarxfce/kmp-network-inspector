package dev.fajar.inspector.matrix.rust

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import dev.fajar.inspector.NetworkInspection
import dev.fajar.inspector.NetworkOutcome
import dev.fajar.inspector.NetworkTraceStore
import dev.fajar.inspector.NetworkTransfer
import dev.fajar.inspector.matrix.captureMatrixHttp
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.matrix.rustcomponents.sdk.Client
import org.matrix.rustcomponents.sdk.ClientBuilder
import org.matrix.rustcomponents.sdk.RequestConfig
import org.matrix.rustcomponents.sdk.SlidingSyncVersionBuilder

/** Real JNI/HTTP fixture. It never signs in, opens HRIS data, or contacts a remote server. */
class ObserverInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val result = JSONObject()
        var success = false
        try {
            runBlocking {
                withTimeout(60_000) {
                    LoopbackFixture().use { fixture ->
                        val client = buildClient(fixture.url)
                        try {
                            result.put("activeScenario", "original-response")
                            originalResponse(client, fixture)
                            result.put("originalResponseAndCapture", true)
                            result.put("activeScenario", "pending-close")
                            pendingClose(client)
                            result.put("pendingReadStoppedAndDisposed", true)
                            result.put("activeScenario", "replacement")
                            replacement(client)
                            result.put("replacementClosesExactOldHandle", true)
                            result.put("activeScenario", "cancellation")
                            cancellationDuringRequest(client, fixture)
                            result.put("captureCancellationPreservesRequest", true)
                            result.put("activeScenario", "bounded-capture")
                            boundedUnconsumedCapture(client, fixture)
                            result.put("boundedCapturePreservesAllRequests", true)
                            result.put("activeScenario", "inspection-reset")
                            inspectionReset(client, fixture)
                            result.put("resetReplacesCaptureWithoutChangingRequest", true)
                        } finally {
                            client.close()
                        }
                    }
                }
            }
            result.remove("activeScenario")
            success = true
        } catch (failure: Throwable) {
            // Do not emit native errors, request data or stack traces through instrumentation.
            result.put("failed", true)
            result.put("failureType", failure.javaClass.simpleName.take(80))
        } finally {
            result.put("passed", success)
            finish(
                if (success) Activity.RESULT_OK else Activity.RESULT_CANCELED,
                Bundle().apply { putString("observerResult", result.toString()) },
            )
        }
    }

    private suspend fun originalResponse(client: Client, fixture: LoopbackFixture) =
        coroutineScope {
            check(client.getProfile("@before:fixture.invalid").displayName == "Original profile")
            val store = NetworkTraceStore()
            val native = client.observeHttpRequests()
            val collector =
                launch(start = CoroutineStart.UNDISPATCHED) {
                    captureMatrixHttp(MatrixRustHttpSubscription(native), store)
                }
            try {
                check(
                    client.getProfile("@observed:fixture.invalid").displayName == "Original profile"
                )
                val snapshot =
                    withTimeout(5_000) {
                        store.snapshots.first { value ->
                            value.traces.any {
                                "/profile/" in it.request.endpoint.path &&
                                    it.transfer is NetworkTransfer.Complete
                            }
                        }
                    }
                val trace = snapshot.traces.single { "/profile/" in it.request.endpoint.path }
                check(trace.outcome == NetworkOutcome.Response(200))
                check(trace.responseBytes == LoopbackFixture.BODY.size.toLong())
                check(trace.startedAtEpochMillis > 0 && trace.request.attemptNumber == 1L)
                check(trace.responseHeaders.none { it.value.contains("Original profile") })
                check(fixture.count("before") == 1 && fixture.count("observed") == 1)
            } finally {
                collector.cancelAndJoin()
                check(native.uniffiIsDestroyed)
                store.close()
            }
            check(client.getProfile("@after:fixture.invalid").displayName == "Original profile")
            check(fixture.count("after") == 1)
        }

    private suspend fun pendingClose(client: Client) = coroutineScope {
        val native = client.observeHttpRequests()
        val adapter = MatrixRustHttpSubscription(native)
        val pending = async(start = CoroutineStart.UNDISPATCHED) { adapter.nextBatch() }
        try {
            check(!pending.isCompleted)
            adapter.close()
            check(withTimeout(5_000) { pending.await() }.closed)
            check(native.uniffiIsDestroyed)
            adapter.close()
        } finally {
            pending.cancelAndJoin()
            adapter.close()
        }
    }

    private suspend fun replacement(client: Client) = coroutineScope {
        val old = client.observeHttpRequests()
        val store = NetworkTraceStore()
        val collector =
            launch(start = CoroutineStart.UNDISPATCHED) {
                captureMatrixHttp(MatrixRustHttpSubscription(old), store)
            }
        val next = client.observeHttpRequests()
        try {
            withTimeout(5_000) { collector.join() }
            check(old.uniffiIsDestroyed)
            check(!next.uniffiIsDestroyed)
        } finally {
            collector.cancelAndJoin()
            next.stop()
            next.close()
            store.close()
        }
    }

    private suspend fun cancellationDuringRequest(client: Client, fixture: LoopbackFixture) =
        coroutineScope {
            val native = client.observeHttpRequests()
            val store = NetworkTraceStore()
            val collector =
                launch(start = CoroutineStart.UNDISPATCHED) {
                    captureMatrixHttp(MatrixRustHttpSubscription(native), store)
                }
            val request = async { client.getProfile("@delayed:fixture.invalid") }
            try {
                withTimeout(5_000) { store.snapshots.first { it.traces.isNotEmpty() } }
                collector.cancelAndJoin()
                check(native.uniffiIsDestroyed)
                check(request.await().displayName == "Original profile")
                check(fixture.count("delayed") == 1)
            } finally {
                collector.cancelAndJoin()
                request.cancelAndJoin()
                store.close()
            }
        }

    private suspend fun boundedUnconsumedCapture(client: Client, fixture: LoopbackFixture) {
        val native = client.observeHttpRequests()
        try {
            repeat(100) {
                check(
                    client.getProfile("@bounded:fixture.invalid").displayName == "Original profile"
                )
            }
            val batch = withTimeout(5_000) { native.nextBatch() }
            check(batch.events.size <= 32)
            check(batch.droppedEvents > 0u)
            check(fixture.count("bounded") == 100)
        } finally {
            native.stop()
            native.close()
        }
    }

    private suspend fun inspectionReset(client: Client, fixture: LoopbackFixture) = coroutineScope {
        val inspection = NetworkInspection()
        val opened = Channel<Unit>(2)
        val handles = mutableListOf<org.matrix.rustcomponents.sdk.HttpObservationSubscription>()
        val collector = launch {
            captureMatrixHttp(inspection) {
                check(handles.all { it.uniffiIsDestroyed })
                val native = client.observeHttpRequests()
                handles += native
                check(opened.trySend(Unit).isSuccess)
                MatrixRustHttpSubscription(native)
            }
        }
        try {
            opened.receive()
            val previous = checkNotNull(inspection.recorders.value)
            val pending = async { client.getProfile("@reset-delayed:fixture.invalid") }
            withTimeout(5_000) { previous.snapshots.first { it.traces.isNotEmpty() } }
            inspection.reset()
            opened.receive()
            check(pending.await().displayName == "Original profile")
            check(previous.snapshots.value.closed && previous.snapshots.value.traces.isEmpty())
            val current = checkNotNull(inspection.recorders.value)
            check(current.snapshots.value.traces.isEmpty())
            check(client.getProfile("@reset-new:fixture.invalid").displayName == "Original profile")
            val captured =
                withTimeout(5_000) {
                    current.snapshots.first { value ->
                        value.traces.any { it.transfer is NetworkTransfer.Complete }
                    }
                }
            check(captured.traces.single().outcome == NetworkOutcome.Response(200))
            check("reset-new" in captured.traces.single().request.endpoint.path)
            check(fixture.count("reset-delayed") == 1 && fixture.count("reset-new") == 1)
            inspection.close()
            collector.join()
            check(handles.size == 2 && handles.all { it.uniffiIsDestroyed })
        } finally {
            collector.cancelAndJoin()
            inspection.close()
            opened.close()
        }
    }

    private suspend fun buildClient(url: String): Client {
        var builder = ClientBuilder()
        fun replace(change: (ClientBuilder) -> ClientBuilder) {
            val old = builder
            builder = change(old)
            old.close()
        }
        try {
            replace { it.homeserverUrl(url) }
            replace { it.disableWellKnownLookup(true) }
            replace { it.slidingSyncVersionBuilder(SlidingSyncVersionBuilder.NATIVE) }
            replace { it.requestConfig(RequestConfig(0u, 2_000u, 2u, 2_000u)) }
            return builder.build()
        } finally {
            builder.close()
        }
    }
}

/** One owned local server thread with bounded reads; no retained bodies or external connection. */
private class LoopbackFixture : AutoCloseable {
    private val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
    private val counts = ConcurrentHashMap<String, AtomicInteger>()
    @Volatile private var active: Socket? = null
    @Volatile private var closed = false
    val url = "http://127.0.0.1:${server.localPort}"
    private val worker =
        thread(name = "matrix-observer-fixture", isDaemon = true) {
            while (!closed) {
                try {
                    server.accept().use { socket ->
                        active = socket
                        socket.soTimeout = 2_000
                        val input = socket.getInputStream()
                        val header = StringBuilder()
                        while (header.length < 16_384 && !header.endsWith("\r\n\r\n")) {
                            val next = input.read()
                            if (next < 0) break
                            header.append(next.toChar())
                        }
                        check(header.endsWith("\r\n\r\n"))
                        val path =
                            URLDecoder.decode(
                                header.toString().substringBefore("\r\n").split(' ')[1],
                                "UTF-8",
                            )
                        counts.computeIfAbsent(path) { AtomicInteger() }.incrementAndGet()
                        if (path.contains("@delayed:") || path.contains("@reset-delayed:"))
                            Thread.sleep(150)
                        val body = if (path.endsWith("/versions")) VERSIONS else BODY
                        val response =
                            "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n" +
                                "Content-Length: ${body.size}\r\nConnection: close\r\n\r\n"
                        socket.getOutputStream().apply {
                            write(response.toByteArray())
                            write(body)
                            flush()
                        }
                    }
                } catch (_: Exception) {
                    if (!closed)
                        counts
                            .computeIfAbsent("fixture-failure") { AtomicInteger() }
                            .incrementAndGet()
                } finally {
                    active = null
                }
            }
        }

    fun count(user: String): Int =
        counts.entries
            .filter { it.key.endsWith("/@$user:fixture.invalid") }
            .sumOf { it.value.get() }

    override fun close() {
        closed = true
        active?.close()
        server.close()
        worker.join(3_000)
        check(!worker.isAlive)
        check((counts["fixture-failure"]?.get() ?: 0) == 0)
    }

    companion object {
        val BODY = "{\"displayname\":\"Original profile\"}".toByteArray()
        val VERSIONS = "{\"versions\":[\"v1.11\"]}".toByteArray()
    }
}
