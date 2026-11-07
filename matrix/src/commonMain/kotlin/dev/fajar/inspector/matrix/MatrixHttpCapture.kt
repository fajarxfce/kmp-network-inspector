package dev.fajar.inspector.matrix

import dev.fajar.inspector.*
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Collect in a host-owned scope. Cancellation closes the exact native subscription; no child scope,
 * timer or unbounded queue is allocated. On Clear, cancel/join this call, clear the store and
 * subscribe again. Account replacement additionally closes the old store.
 *
 * Correlation is bounded independently of retained UI rows. Source loss and discarded events are
 * visible; incomplete observation never synthesizes request failure or message delivery.
 */
suspend fun captureMatrixHttp(
    subscription: MatrixHttpSubscription,
    recorder: NetworkTraceStore,
    correlationCapacity: Int = 256,
) {
    MatrixHttpCollector(subscription, recorder, correlationCapacity).collect()
}

private class MatrixHttpCollector(
    private val subscription: MatrixHttpSubscription,
    private val recorder: NetworkTraceStore,
    private val capacity: Int,
) {
    private val active = linkedMapOf<Long, NetworkCapture>()
    // Bounded replay window admits out-of-order concurrent starts but rejects stale duplicates.
    private val seen = mutableSetOf<Long>()
    private var highestId = 0L
    private var dropped = 0L
    private var missed = 0L

    suspend fun collect() {
        var cause: Throwable? = null
        try {
            require(capacity in 1..500)
            val session = recorder.openObservation() ?: return
            while (recorder.isObservationOpen(session)) {
                currentCoroutineContext().ensureActive()
                val batch = subscription.nextBatch()
                if (!recorder.isObservationOpen(session)) return
                if (
                    batch.events.size > 32 ||
                        batch.droppedEvents < dropped ||
                        batch.missedAttemptStarts < missed
                ) {
                    recorder.observedLoss(session, batch.events.size.toLong().coerceAtLeast(1))
                    return
                }
                val dropDelta = batch.droppedEvents - dropped
                val missedDelta = batch.missedAttemptStarts - missed
                dropped = batch.droppedEvents
                missed = batch.missedAttemptStarts
                if (batch.closed) {
                    if (dropDelta > 0) recorder.observedLoss(session, dropDelta)
                    if (missedDelta > 0) recorder.observedLoss(session, missedDelta)
                    if (batch.events.isNotEmpty())
                        recorder.observedLoss(session, batch.events.size.toLong())
                    return
                }
                if (batch.events.isEmpty() && dropDelta == 0L && missedDelta == 0L) {
                    // A conforming native reader suspends. Do not spin on a broken source.
                    recorder.observedLoss(session, 1)
                    return
                }
                var discarded = 0L
                for (event in batch.events) {
                    currentCoroutineContext().ensureActive()
                    if (!record(session, event)) discarded++
                }
                // Process loss after the batch so affected starts in that batch are marked too.
                if (dropDelta > 0) recorder.observedLoss(session, dropDelta)
                if (missedDelta > 0) recorder.observedLoss(session, missedDelta)
                if (discarded > 0) recorder.observedLoss(session, discarded)
            }
        } catch (failure: Throwable) {
            cause = failure
            throw failure
        } finally {
            var closeFailure: Throwable? = null
            try {
                subscription.close()
            } catch (failure: Throwable) {
                closeFailure = failure
            }
            // Finite in-memory cleanup only. Native close never waits for HTTP or a UI callback.
            withContext(NonCancellable) {
                for (capture in active.values) recorder.observedIncomplete(capture)
                active.clear()
                seen.clear()
            }
            if (closeFailure != null) {
                if (cause == null) throw closeFailure
                cause.addSuppressed(closeFailure)
            }
        }
    }

    private suspend fun record(
        session: NetworkObservationSession,
        event: MatrixHttpEvent,
    ): Boolean {
        if (event.id < 0) return false
        return when (event) {
            is MatrixHttpEvent.Started -> start(session, event)
            is MatrixHttpEvent.Response -> {
                val capture = active[event.id] ?: return false
                recorder.observedResponse(
                    capture,
                    event.status,
                    event.elapsedMillis,
                    event.headers,
                    event.truncated,
                )
            }
            is MatrixHttpEvent.Finished -> {
                val capture = active.remove(event.id) ?: return false
                val accepted =
                    recorder.observedComplete(
                        capture,
                        event.elapsedMillis,
                        event.responseBytes,
                        event.failure,
                    )
                if (!accepted) recorder.observedIncomplete(capture)
                accepted
            }
        }
    }

    private suspend fun start(
        session: NetworkObservationSession,
        event: MatrixHttpEvent.Started,
    ): Boolean {
        if (event.attempt < 1 || event.id in active) return false
        val window = capacity * 2L
        highestId = maxOf(highestId, event.id)
        val floor = (highestId - window + 1).coerceAtLeast(0)
        seen.removeAll { it < floor }
        if (event.id < floor || !seen.add(event.id)) return false
        val request =
            event.request.copy(source = NetworkSource.MATRIX, attemptNumber = event.attempt)
        val capture =
            recorder.beginObserved(session, request, event.startedAtEpochMillis) ?: return false
        if (active.size == capacity) {
            val old = active.remove(active.keys.first())!!
            recorder.observedIncomplete(old)
            recorder.observedLoss(session, 1)
        }
        active[event.id] = capture
        return true
    }
}
