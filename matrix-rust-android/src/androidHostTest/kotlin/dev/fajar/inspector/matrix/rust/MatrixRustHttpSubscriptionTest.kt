package dev.fajar.inspector.matrix.rust

import dev.fajar.inspector.NetworkFailure
import dev.fajar.inspector.NetworkSource
import dev.fajar.inspector.NetworkTraceStore
import dev.fajar.inspector.matrix.MatrixHttpEvent
import dev.fajar.inspector.matrix.captureMatrixHttp
import kotlin.test.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.matrix.rustcomponents.sdk.*

class MatrixRustHttpSubscriptionTest {
    @Test
    fun realBindingRecordsKeepNativeTimesBytesAndAttempts() = runTest {
        val native = NativeFixture()
        val adapter = MatrixRustHttpSubscription(native)
        native.result.complete(
            batch(
                listOf(
                    start(),
                    HttpObservationEvent.Response(7u, 401u, 12u, listOf("set-cookie"), true),
                    HttpObservationEvent.Finished(7u, 19u, 42u, HttpObservedFailure.CONNECTION),
                )
            )
        )
        val events = adapter.nextBatch().events
        val started = assertIs<MatrixHttpEvent.Started>(events[0])
        assertEquals(7L, started.id)
        assertEquals(2L, started.attempt)
        assertEquals(1234L, started.startedAtEpochMillis)
        assertEquals(NetworkSource.MATRIX, started.request.source)
        assertEquals(8448, started.request.endpoint.port)
        assertEquals(listOf("since"), started.request.endpoint.queryNames)
        assertEquals("[not captured]", started.request.headers.single().value)
        val response = assertIs<MatrixHttpEvent.Response>(events[1])
        assertEquals(401, response.status)
        assertEquals(12L, response.elapsedMillis)
        assertTrue(response.truncated)
        assertEquals("[not captured]", response.headers.single().value)
        val finished = assertIs<MatrixHttpEvent.Finished>(events[2])
        assertEquals(19L, finished.elapsedMillis)
        assertEquals(42L, finished.responseBytes)
        assertEquals(NetworkFailure.CONNECTION, finished.failure)
        adapter.close()
        assertEquals(listOf("read", "stop", "dispose"), native.calls)
    }

    @Test
    fun clientExtensionOwnsOnlyItsExactSubscription() = runTest {
        val native = NativeFixture()
        var clientDisposed = false
        val client =
            object : Client(NoHandle) {
                override fun observeHttpRequests() = native

                override fun destroy() {
                    clientDisposed = true
                }
            }
        val adapter = client.observeNetworkRequests()
        adapter.close()
        adapter.close()
        assertFalse(clientDisposed)
        assertEquals(listOf("stop", "dispose"), native.calls)
        assertTrue(adapter.nextBatch().closed)
    }

    @Test
    fun closeWakesReaderBeforeDisposingAndDiscardsLateBufferedEvents() = runTest {
        val native = NativeFixture()
        val adapter = MatrixRustHttpSubscription(native)
        val reader = async(start = CoroutineStart.UNDISPATCHED) { adapter.nextBatch() }
        assertFalse(reader.isCompleted)
        adapter.close()
        assertEquals(listOf("read", "stop"), native.calls)
        native.result.complete(batch(listOf(start())))
        assertTrue(reader.await().closed)
        assertEquals(listOf("read", "stop", "dispose"), native.calls)
        adapter.close()
        assertEquals(1, native.calls.count { it == "dispose" })
    }

    @Test
    fun concurrentReadersAreRejectedWithoutClosingFirstReader() = runTest {
        val native = NativeFixture()
        val adapter = MatrixRustHttpSubscription(native)
        val first = async(start = CoroutineStart.UNDISPATCHED) { adapter.nextBatch() }
        assertFailsWith<IllegalStateException> { adapter.nextBatch() }
        assertEquals(listOf("read"), native.calls)
        native.result.complete(batch(listOf(start())))
        assertEquals(1, first.await().events.size)
        adapter.close()
    }

    @Test
    fun collectorCancellationStopsAndDisposesItsNativeHandle() = runTest {
        val native = NativeFixture()
        val store = NetworkTraceStore()
        val collector =
            launch(start = CoroutineStart.UNDISPATCHED) {
                captureMatrixHttp(MatrixRustHttpSubscription(native), store)
            }
        collector.cancelAndJoin()
        assertEquals(listOf("read", "stop", "dispose"), native.calls)
        assertTrue(store.snapshots.value.traces.isEmpty())
        assertFalse(store.snapshots.value.closed)
    }

    @Test
    fun alreadyCancelledCollectorStillClosesHandleWithoutNativeRead() = runTest {
        val native = NativeFixture()
        val collector =
            launch(start = CoroutineStart.UNDISPATCHED) {
                currentCoroutineContext().cancel()
                captureMatrixHttp(MatrixRustHttpSubscription(native), NetworkTraceStore())
            }
        collector.join()
        assertEquals(listOf("stop", "dispose"), native.calls)
    }

    @Test
    fun sourceFailureIsPreservedAndCollectorClosesHandle() = runTest {
        val native = NativeFixture()
        val original = FixtureFailure()
        native.result.completeExceptionally(original)
        val failure =
            assertFailsWith<FixtureFailure> {
                captureMatrixHttp(MatrixRustHttpSubscription(native), NetworkTraceStore())
            }
        assertSame(original, failure)
        assertEquals(listOf("read", "stop", "dispose"), native.calls)
    }

    @Test
    fun stopFailurePreservesDisposalFailureAndBothAreAttemptedOnce() {
        val native = NativeFixture()
        val stopping = FixtureFailure()
        val disposing = FixtureFailure()
        native.stopFailure = stopping
        native.disposeFailure = disposing
        val adapter = MatrixRustHttpSubscription(native)
        assertSame(stopping, assertFailsWith<FixtureFailure> { adapter.close() })
        assertEquals(listOf(disposing), stopping.suppressed.toList())
        adapter.close()
        assertEquals(listOf("stop", "dispose"), native.calls)
    }

    @Test
    fun unsignedLossCountersSaturateWithoutBecomingNegative() = runTest {
        val native = NativeFixture()
        native.result.complete(
            HttpObservationBatch(emptyList(), ULong.MAX_VALUE, ULong.MAX_VALUE, true)
        )
        val adapter = MatrixRustHttpSubscription(native)
        val mapped = adapter.nextBatch()
        assertEquals(Long.MAX_VALUE, mapped.droppedEvents)
        assertEquals(Long.MAX_VALUE, mapped.missedAttemptStarts)
        assertTrue(mapped.closed)
        adapter.close()
    }

    @Test
    fun overflowingIdsAndTimesRejectObservationInsteadOfMergingRows() = runTest {
        for (invalid in
            listOf(
                start().copy(id = ULong.MAX_VALUE),
                start().copy(attempt = ULong.MAX_VALUE),
                start().copy(startedAtEpochMillis = ULong.MAX_VALUE),
                HttpObservationEvent.Response(7u, 200u, ULong.MAX_VALUE, emptyList(), false),
                HttpObservationEvent.Finished(7u, 0u, ULong.MAX_VALUE, null),
            )) {
            val native = NativeFixture()
            native.result.complete(batch(listOf(invalid)))
            assertFailsWith<IllegalStateException> {
                captureMatrixHttp(MatrixRustHttpSubscription(native), NetworkTraceStore())
            }
            assertEquals(listOf("read", "stop", "dispose"), native.calls)
        }
    }

    @Test
    fun incompatibleBoundsAreRejectedBeforeAdditionalRetention() = runTest {
        val invalid =
            listOf(
                batch(List(33) { start() }),
                batch(listOf(start().copy(request = request().copy(path = "x".repeat(1025))))),
                batch(
                    listOf(start().copy(request = request().copy(queryNames = List(33) { "q" })))
                ),
                batch(
                    listOf(
                        HttpObservationEvent.Response(7u, 200u, 1u, listOf("h".repeat(65)), false)
                    )
                ),
            )
        for (value in invalid) {
            val native = NativeFixture()
            native.result.complete(value)
            assertFailsWith<IllegalStateException> {
                captureMatrixHttp(MatrixRustHttpSubscription(native), NetworkTraceStore())
            }
            assertEquals("dispose", native.calls.last())
        }
    }

    @Test
    fun terminalFailureCategoriesNeverBecomeHttpResponses() = runTest {
        val failures =
            listOf(
                HttpObservedFailure.TIMEOUT to NetworkFailure.TIMEOUT,
                HttpObservedFailure.CONNECTION to NetworkFailure.CONNECTION,
                HttpObservedFailure.CANCELLED to NetworkFailure.CANCELLED,
                HttpObservedFailure.OTHER to NetworkFailure.UNKNOWN,
                null to null,
            )
        val native = NativeFixture()
        native.result.complete(
            batch(
                failures.mapIndexed { i, (failure, _) ->
                    HttpObservationEvent.Finished(i.toULong(), 2u, null, failure)
                }
            )
        )
        val adapter = MatrixRustHttpSubscription(native)
        val result = adapter.nextBatch()
        assertEquals(
            failures.map { it.second },
            result.events.map { assertIs<MatrixHttpEvent.Finished>(it).failure },
        )
        adapter.close()
    }

    @Test
    fun generatedMutableRecordCollectionsAreCopied() = runTest {
        val names = mutableListOf("since")
        val native = NativeFixture()
        native.result.complete(
            batch(listOf(start().copy(request = request().copy(queryNames = names))))
        )
        val adapter = MatrixRustHttpSubscription(native)
        val event = assertIs<MatrixHttpEvent.Started>(adapter.nextBatch().events.single())
        names.clear()
        assertEquals(listOf("since"), event.request.endpoint.queryNames)
        adapter.close()
    }

    private class NativeFixture : HttpObservationSubscription(NoHandle) {
        val calls = mutableListOf<String>()
        val result = CompletableDeferred<HttpObservationBatch>()
        var stopFailure: Throwable? = null
        var disposeFailure: Throwable? = null

        override suspend fun nextBatch(): HttpObservationBatch {
            calls += "read"
            return result.await()
        }

        override fun stop() {
            calls += "stop"
            stopFailure?.let { throw it }
        }

        override fun destroy() {
            calls += "dispose"
            disposeFailure?.let { throw it }
        }
    }

    // Additional state prevents kotlinx.coroutines debug stack recovery from copying this
    // exception, allowing the test to verify the original technical failure by identity.
    private class FixtureFailure : IllegalStateException("fixture") {
        val marker = Any()
    }

    private fun request() =
        HttpObservedRequest(
            "GET",
            "https",
            "example.test",
            8448u,
            "/_matrix/client/v3/sync",
            listOf("since"),
            listOf("authorization"),
            false,
        )

    private fun start() = HttpObservationEvent.Started(7u, 2u, 1234u, request())

    private fun batch(events: List<HttpObservationEvent>) =
        HttpObservationBatch(events, 0u, 0u, false)
}
