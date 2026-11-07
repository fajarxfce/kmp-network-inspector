package dev.fajar.inspector.matrix

import dev.fajar.inspector.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.runTest

class MatrixHttpCaptureTest {
    private val request =
        NetworkRequest(
            NetworkSource.CUSTOM,
            "GET",
            NetworkEndpoint("https", "matrix.example.test", path = "/sync"),
        )

    private fun start(id: Long, attempt: Long = 1) =
        MatrixHttpEvent.Started(id, attempt, 1_000 + id, request)

    private fun batch(vararg events: MatrixHttpEvent) = MatrixHttpBatch(events.toList())

    private val closed = MatrixHttpBatch(emptyList(), closed = true)

    @Test
    fun nativeTimesRetriesAndResponseBodyFailureRemainSeparateTransportFacts() = runTest {
        val source =
            ScriptedSubscription(
                listOf(
                    batch(
                        start(0),
                        MatrixHttpEvent.Response(0, 429, 15),
                        MatrixHttpEvent.Finished(0, 17, 25),
                    ),
                    batch(
                        start(1, 2),
                        MatrixHttpEvent.Response(1, 200, 30),
                        MatrixHttpEvent.Finished(1, 45, failure = NetworkFailure.CONNECTION),
                    ),
                    closed,
                )
            )
        val store = NetworkTraceStore()
        captureMatrixHttp(source, store)
        val traces = store.snapshots.value.traces.reversed()
        assertEquals(listOf(1_000L, 1_001L), traces.map { it.startedAtEpochMillis })
        assertEquals(listOf(1L, 2L), traces.map { it.request.attemptNumber })
        assertTrue(traces.all { it.request.source == NetworkSource.MATRIX })
        assertEquals(
            listOf(NetworkOutcome.Response(429), NetworkOutcome.Response(200)),
            traces.map { it.outcome },
        )
        assertEquals(listOf(15L, 30L), traces.map { it.durationMillis })
        assertEquals(
            NetworkTransfer.Complete(45, NetworkFailure.CONNECTION),
            traces.last().transfer,
        )
        assertEquals(1, source.closes)
    }

    @Test
    fun concurrentStartAndCompletionOrderCannotBorrowOrDuplicateAnotherAttempt() = runTest {
        val store = NetworkTraceStore()
        captureMatrixHttp(
            ScriptedSubscription(
                listOf(
                    batch(start(11), start(10), start(11)),
                    batch(
                        MatrixHttpEvent.Response(10, 201, 4),
                        MatrixHttpEvent.Finished(10, 5),
                        start(10),
                    ),
                    batch(
                        MatrixHttpEvent.Response(11, 401, 9),
                        MatrixHttpEvent.Finished(11, 10),
                        MatrixHttpEvent.Response(10, 500, 50),
                    ),
                    closed,
                )
            ),
            store,
        )
        val traces = store.snapshots.value.traces
        assertEquals(2, traces.size)
        assertEquals(
            listOf(NetworkOutcome.Response(201), NetworkOutcome.Response(401)),
            traces.map { it.outcome },
        )
        assertEquals(3L, store.snapshots.value.droppedObservations)
    }

    @Test
    fun cancellationClosesTheSuspendedNativeReaderWithoutFabricatingTransportFailure() = runTest {
        val source = WaitingSubscription(batch(start(0), MatrixHttpEvent.Response(0, 200, 10)))
        val store = NetworkTraceStore()
        val reader = launch { captureMatrixHttp(source, store) }
        source.waiting.await()
        reader.cancelAndJoin()
        assertEquals(1, source.closes)
        val trace = store.snapshots.value.traces.single()
        assertEquals(NetworkOutcome.Response(200), trace.outcome)
        assertEquals(10L, trace.durationMillis)
        assertEquals(NetworkTransfer.Unavailable, trace.transfer)
        assertTrue(trace.incomplete)
    }

    @Test
    fun alreadyCancelledOwnerStillClosesTheSubscriptionWithoutReadingIt() = runTest {
        val source = ScriptedSubscription(listOf(batch(start(0))))
        val store = NetworkTraceStore()
        launch(start = CoroutineStart.UNDISPATCHED) {
                currentCoroutineContext().cancel()
                assertFailsWith<CancellationException> { captureMatrixHttp(source, store) }
            }
            .join()
        assertEquals(0, source.reads)
        assertEquals(1, source.closes)
        assertTrue(store.snapshots.value.traces.isEmpty())
    }

    @Test
    fun clearFencesABatchAlreadyAwaitedByTheOldReaderAndKeepsReplacementCaptureIndependent() =
        runTest {
            val source = WaitingSubscription(batch(start(0)))
            val store = NetworkTraceStore()
            val reader = launch { captureMatrixHttp(source, store) }
            source.waiting.await()
            store.clear()
            source.batches.send(batch(start(1), MatrixHttpEvent.Finished(0, 12)))
            reader.join()
            assertTrue(store.snapshots.value.traces.isEmpty())
            assertEquals(1, source.closes)
            captureMatrixHttp(
                ScriptedSubscription(
                    listOf(
                        batch(
                            start(1),
                            MatrixHttpEvent.Response(1, 204, 2),
                            MatrixHttpEvent.Finished(1, 3),
                        ),
                        closed,
                    )
                ),
                store,
            )
            assertEquals(
                NetworkOutcome.Response(204),
                store.snapshots.value.traces.single().outcome,
            )
            assertEquals(0L, store.snapshots.value.droppedObservations)
        }

    @Test
    fun sourceFailureIsPreservedEvenIfClosingAlsoFails() = runTest {
        val original = FixtureFailure(Any())
        val closeFailure = FixtureFailure(Any())
        val source = ScriptedSubscription(listOf(batch(start(0))), original, closeFailure)
        val store = NetworkTraceStore()
        assertSame(original, assertFailsWith<FixtureFailure> { captureMatrixHttp(source, store) })
        assertTrue(original.suppressedExceptions.any { it === closeFailure })
        assertEquals(NetworkTransfer.Unavailable, store.snapshots.value.traces.single().transfer)
        assertNull(store.snapshots.value.traces.single().outcome)
        assertEquals(1, source.closes)
    }

    @Test
    fun malformedOrNonProgressingBatchesTerminateWithoutAnUnboundedReadLoop() = runTest {
        for (malformed in
            listOf(
                MatrixHttpBatch(List(33) { start(it.toLong()) }),
                MatrixHttpBatch(emptyList(), droppedEvents = -1),
                MatrixHttpBatch(emptyList(), missedAttemptStarts = -1),
                MatrixHttpBatch(emptyList()),
                MatrixHttpBatch(listOf(start(3)), closed = true),
            )) {
            val source = ScriptedSubscription(listOf(malformed))
            val store = NetworkTraceStore()
            captureMatrixHttp(source, store)
            assertEquals(1, source.reads)
            assertEquals(1, source.closes)
            assertTrue(store.snapshots.value.traces.isEmpty())
            assertTrue(store.snapshots.value.droppedObservations > 0)
        }
    }

    @Test
    fun cumulativeLossCountsOnlyDeltasAndNeverChangesTheActualHttpStatus() = runTest {
        val store = NetworkTraceStore()
        captureMatrixHttp(
            ScriptedSubscription(
                listOf(
                    batch(start(0), MatrixHttpEvent.Response(0, 200, 4)).copy(droppedEvents = 3),
                    batch(MatrixHttpEvent.Finished(0, 7))
                        .copy(droppedEvents = 3, missedAttemptStarts = 2),
                    closed.copy(droppedEvents = 4, missedAttemptStarts = 3),
                )
            ),
            store,
        )
        assertEquals(7L, store.snapshots.value.droppedObservations)
        assertEquals(NetworkOutcome.Response(200), store.snapshots.value.traces.single().outcome)
        assertEquals(NetworkTransfer.Complete(7), store.snapshots.value.traces.single().transfer)
        assertTrue(store.snapshots.value.traces.single().incomplete)
    }

    @Test
    fun correlationPressureRetiresOnlyItsOriginalHandleAndLateCompletionCannotRestoreIt() =
        runTest {
            val store = NetworkTraceStore()
            captureMatrixHttp(
                ScriptedSubscription(
                    listOf(
                        batch(start(0), start(1), start(2)),
                        batch(
                            MatrixHttpEvent.Response(1, 201, 3),
                            MatrixHttpEvent.Finished(1, 4),
                            MatrixHttpEvent.Response(2, 202, 5),
                            MatrixHttpEvent.Finished(2, 6),
                            MatrixHttpEvent.Finished(0, 50),
                        ),
                        closed,
                    )
                ),
                store,
                correlationCapacity = 2,
            )
            val traces = store.snapshots.value.traces.reversed()
            assertEquals(NetworkTransfer.Unavailable, traces.first().transfer)
            assertNull(traces.first().outcome)
            assertEquals(
                listOf(NetworkOutcome.Response(201), NetworkOutcome.Response(202)),
                traces.drop(1).map { it.outcome },
            )
            assertEquals(2L, store.snapshots.value.droppedObservations)
        }

    @Test
    fun boundedReplayWindowRejectsStaleStartsAfterTheirCompletedHandlesHaveLeft() = runTest {
        val store = NetworkTraceStore()
        captureMatrixHttp(
            ScriptedSubscription(
                listOf(
                    batch(
                        start(0),
                        MatrixHttpEvent.Response(0, 200, 1),
                        MatrixHttpEvent.Finished(0, 2),
                    ),
                    batch(start(20), start(19), start(0)),
                    closed,
                )
            ),
            store,
            correlationCapacity = 2,
        )
        assertEquals(3, store.snapshots.value.traces.size)
        assertEquals(1, store.snapshots.value.traces.count { it.startedAtEpochMillis == 1_000L })
        assertEquals(1L, store.snapshots.value.droppedObservations)
    }

    @Test
    fun metadataAndInvalidStagesAreSanitizedBeforePublicationWithoutInventingAResponse() = runTest {
        val store = NetworkTraceStore()
        val dirty =
            start(0)
                .copy(
                    request =
                        request.copy(headers = listOf(NetworkHeader("Authorization", "private")))
                )
        captureMatrixHttp(
            ScriptedSubscription(
                listOf(
                    batch(
                        start(-1),
                        start(1, 0),
                        start(2).copy(startedAtEpochMillis = Long.MAX_VALUE),
                        dirty,
                    ),
                    batch(
                        MatrixHttpEvent.Response(0, 600, 1),
                        MatrixHttpEvent.Finished(0, 2),
                        MatrixHttpEvent.Finished(90, 9),
                    ),
                    closed,
                )
            ),
            store,
        )
        val trace = store.snapshots.value.traces.single()
        assertEquals("[redacted]", trace.request.headers.single().value)
        assertNull(trace.outcome)
        assertTrue(trace.incomplete)
        assertEquals(5L, store.snapshots.value.droppedObservations)
    }

    @Test
    fun closedStoreAndInvalidCapacityCloseWithoutAcquiringNativeEvents() = runTest {
        val store = NetworkTraceStore()
        store.close()
        val source = ScriptedSubscription(listOf(batch(start(0))))
        captureMatrixHttp(source, store)
        assertEquals(0, source.reads)
        assertEquals(1, source.closes)
        val invalid = ScriptedSubscription(listOf(batch(start(0))))
        assertFailsWith<IllegalArgumentException> {
            captureMatrixHttp(invalid, NetworkTraceStore(), 501)
        }
        assertEquals(0, invalid.reads)
        assertEquals(1, invalid.closes)
    }

    @Test
    fun regressingCountersAndSaturatedTotalsCannotWrapOrAcceptAnotherGeneration() = runTest {
        val store = NetworkTraceStore()
        val source =
            ScriptedSubscription(
                listOf(
                    batch(start(0))
                        .copy(droppedEvents = Long.MAX_VALUE, missedAttemptStarts = Long.MAX_VALUE),
                    batch(start(1)).copy(droppedEvents = 0),
                )
            )
        captureMatrixHttp(source, store)
        assertEquals(Long.MAX_VALUE, store.snapshots.value.droppedObservations)
        assertEquals(1, store.snapshots.value.traces.size)
        assertEquals(NetworkTransfer.Unavailable, store.snapshots.value.traces.single().transfer)
        assertEquals(2, source.reads)
        assertEquals(1, source.closes)
    }
}

private class ScriptedSubscription(
    private val batches: List<MatrixHttpBatch>,
    private val failure: Throwable? = null,
    private val closeFailure: Throwable? = null,
) : MatrixHttpSubscription {
    var reads = 0
    var closes = 0

    override suspend fun nextBatch(): MatrixHttpBatch {
        currentCoroutineContext().ensureActive()
        if (reads == batches.size) throw failure ?: error("Fixture exhausted")
        return batches[reads++]
    }

    override fun close() {
        closes++
        if (closeFailure != null) throw closeFailure
    }
}

private class WaitingSubscription(private val first: MatrixHttpBatch) : MatrixHttpSubscription {
    private var initial = true
    val waiting = CompletableDeferred<Unit>()
    val batches = Channel<MatrixHttpBatch>(1)
    var closes = 0

    override suspend fun nextBatch(): MatrixHttpBatch {
        if (initial) {
            initial = false
            return first
        }
        waiting.complete(Unit)
        return batches.receive()
    }

    override fun close() {
        closes++
        batches.cancel()
    }
}

private class FixtureFailure(val marker: Any) : Exception("fixture diagnostic")
