package dev.fajar.inspector

import kotlin.test.*
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant
import kotlin.time.TestTimeSource
import kotlinx.coroutines.test.runTest

class NetworkObservedCaptureTest {
    @Test
    fun stoppedObservationPreservesHeadersAndCannotBeMistakenForTransportCancellation() = runTest {
        val store = NetworkTraceStore()
        val other = NetworkTraceStore()
        val session = assertNotNull(store.openObservation())
        assertTrue(store.isObservationOpen(session))
        assertFalse(other.isObservationOpen(session))
        val capture =
            assertNotNull(store.beginObserved(session, request.copy(attemptNumber = 2), 1))
        assertTrue(store.observedResponse(capture, 200, 4))
        assertFalse(other.observedIncomplete(capture))
        assertTrue(store.observedIncomplete(capture))
        assertFalse(store.observedComplete(capture, 5, failure = NetworkFailure.CANCELLED))
        assertFalse(store.observedIncomplete(capture))
        val trace = store.snapshots.value.traces.single()
        assertEquals(NetworkOutcome.Response(200), trace.outcome)
        assertEquals(NetworkTransfer.Unavailable, trace.transfer)
        assertEquals(4L, trace.durationMillis)
        assertEquals(2L, trace.request.attemptNumber)
        assertTrue(trace.incomplete)
        val local = assertNotNull(store.begin(request))
        assertFalse(store.observedIncomplete(local))
        store.clear()
        assertFalse(store.isObservationOpen(session))
        assertFalse(store.observedIncomplete(capture))
    }

    private val request =
        NetworkRequest(
            NetworkSource.MATRIX,
            "GET",
            NetworkEndpoint("https", "matrix.example.test", path = "/sync"),
        )

    @Test
    fun bufferedEventsKeepNativeStartAndSeparateHeaderAndTransferDurations() = runTest {
        val time = TestTimeSource()
        val store =
            NetworkTraceStore(
                clock =
                    object : Clock {
                        override fun now() = Instant.fromEpochMilliseconds(900_000)
                    },
                timeSource = time,
            )
        val session = assertNotNull(store.openObservation())
        val capture = assertNotNull(store.beginObserved(session, request, 1_000))
        time += 2.days
        assertTrue(store.observedResponse(capture, 200, 125))
        assertTrue(store.observedComplete(capture, 300, responseBytes = 12))
        val trace = store.snapshots.value.traces.single()
        assertEquals(1_000L, trace.startedAtEpochMillis)
        assertEquals(125L, trace.durationMillis)
        assertEquals(NetworkTransfer.Complete(300), trace.transfer)
        assertEquals(12L, trace.responseBytes)
        assertFalse(trace.incomplete)
    }

    @Test
    fun failedBodyKeepsActualHttpResponseAndSanitizedHeaders() = runTest {
        val store = NetworkTraceStore()
        val capture =
            assertNotNull(
                store.beginObserved(assertNotNull(store.openObservation()), request, 1_000)
            )
        assertTrue(
            store.observedResponse(
                capture,
                401,
                15,
                listOf(NetworkHeader("set-cookie", "private")),
                metadataTruncated = true,
            )
        )
        assertTrue(store.observedComplete(capture, 28, failure = NetworkFailure.CONNECTION))
        val trace = store.snapshots.value.traces.single()
        assertEquals(NetworkOutcome.Response(401), trace.outcome)
        assertEquals(NetworkTransfer.Complete(28, NetworkFailure.CONNECTION), trace.transfer)
        assertTrue(trace.responseHeadersTruncated)
        assertNotEquals("private", trace.responseHeaders.single().value)
        assertFalse(store.observedComplete(capture, 40))
        assertFalse(store.observedResponse(capture, 200, 20))
    }

    @Test
    fun preHeaderFailureAndMissingResponseAreNotFabricatedHttpStatuses() = runTest {
        val store = NetworkTraceStore()
        val session = assertNotNull(store.openObservation())
        val failed = assertNotNull(store.beginObserved(session, request, 1))
        assertTrue(store.observedComplete(failed, 5, failure = NetworkFailure.CANCELLED))
        val failure = store.snapshots.value.traces.single()
        assertEquals(NetworkOutcome.Failed(NetworkFailure.CANCELLED), failure.outcome)
        assertFalse(failure.incomplete)
        val incomplete = assertNotNull(store.beginObserved(session, request, 2))
        assertTrue(store.observedComplete(incomplete, 9, responseBytes = 12))
        val trace = store.snapshots.value.traces.first()
        assertNull(trace.outcome)
        assertTrue(trace.incomplete)
        assertEquals(NetworkTransfer.Complete(9), trace.transfer)
    }

    @Test
    fun clearAndCloseFenceBufferedStartsResponsesCompletionsAndLoss() = runTest {
        val store = NetworkTraceStore()
        val session = assertNotNull(store.openObservation())
        val old = assertNotNull(store.beginObserved(session, request, 1))
        store.clear()
        assertNull(store.beginObserved(session, request, 2))
        assertFalse(store.observedResponse(old, 200, 3))
        assertFalse(store.observedComplete(old, 4))
        assertFalse(store.observedLoss(session, 10))
        val replacement = assertNotNull(store.openObservation())
        assertNotNull(store.beginObserved(replacement, request, 3))
        assertEquals(1, store.snapshots.value.traces.size)
        store.close()
        assertNull(store.openObservation())
        assertNull(store.beginObserved(replacement, request, 4))
        assertFalse(store.observedLoss(replacement, 1))
        assertTrue(store.snapshots.value.traces.isEmpty())
    }

    @Test
    fun foreignSessionsAndWrongTimingModesAreRejectedWithoutMutation() = runTest {
        val store = NetworkTraceStore()
        val other = NetworkTraceStore()
        val session = assertNotNull(store.openObservation())
        assertNull(other.beginObserved(session, request, 1))
        assertFalse(other.observedLoss(session, 1))
        val observed = assertNotNull(store.beginObserved(session, request, 1))
        assertFalse(store.finish(observed, NetworkOutcome.Response(200)))
        assertFalse(other.observedResponse(observed, 200, 1))
        assertFalse(other.observedComplete(observed, 2))
        val local = assertNotNull(store.begin(request))
        assertFalse(store.observedResponse(local, 200, 1))
        assertFalse(store.observedComplete(local, 2))
        assertTrue(store.finish(local, NetworkOutcome.Response(200)))
        assertTrue(store.observedResponse(observed, 200, 3))
    }

    @Test
    fun invalidOrReversedNativeTimingCannotOverwriteAnObservedStage() = runTest {
        val store = NetworkTraceStore()
        val session = assertNotNull(store.openObservation())
        assertNull(store.beginObserved(session, request, -1))
        assertNull(store.beginObserved(session, request, Long.MAX_VALUE))
        val capture = assertNotNull(store.beginObserved(session, request, 1))
        assertFalse(store.observedResponse(capture, 600, 3))
        assertFalse(store.observedResponse(capture, 200, -1))
        assertFalse(store.observedComplete(capture, 1, responseBytes = -1))
        assertTrue(store.observedResponse(capture, 200, 10))
        assertFalse(store.observedComplete(capture, 9))
        assertEquals(NetworkTransfer.InProgress, store.snapshots.value.traces.single().transfer)
        assertTrue(store.observedComplete(capture, 10, responseBytes = 0))
    }

    @Test
    fun aResponseWithBodyStillInProgressSurvivesCompletedRowEviction() = runTest {
        val store = NetworkTraceStore(capacity = 2)
        val session = assertNotNull(store.openObservation())
        val observed = assertNotNull(store.beginObserved(session, request, 1))
        assertTrue(store.observedResponse(observed, 200, 5))
        val completed = assertNotNull(store.begin(request))
        assertTrue(store.finish(completed, NetworkOutcome.Response(201)))
        assertNotNull(store.begin(request))
        assertTrue(store.observedComplete(observed, 10))
        assertEquals(1L, store.snapshots.value.evictedRequests)
        assertEquals(2, store.snapshots.value.traces.size)
        assertFalse(store.snapshots.value.traces.any { it.outcome == NetworkOutcome.Response(201) })
    }

    @Test
    fun lossIsBoundedVisibleAndNeverRewritesTransportOutcome() = runTest {
        val store = NetworkTraceStore()
        val session = assertNotNull(store.openObservation())
        val observed = assertNotNull(store.beginObserved(session, request, 1))
        assertTrue(store.observedResponse(observed, 200, 3))
        val local = assertNotNull(store.begin(request))
        assertTrue(store.observedLoss(session, Long.MAX_VALUE))
        assertTrue(store.observedLoss(session, 10))
        assertEquals(Long.MAX_VALUE, store.snapshots.value.droppedObservations)
        assertTrue(store.observedComplete(observed, 9))
        assertTrue(store.finish(local, NetworkOutcome.Response(201)))
        assertTrue(store.snapshots.value.traces.last().incomplete)
        assertFalse(store.snapshots.value.traces.first().incomplete)
        assertEquals(NetworkOutcome.Response(200), store.snapshots.value.traces.last().outcome)
        store.clear()
        assertEquals(0L, store.snapshots.value.droppedObservations)
    }
}
