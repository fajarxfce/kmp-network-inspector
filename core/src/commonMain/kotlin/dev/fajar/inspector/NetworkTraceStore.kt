package dev.fajar.inspector

import kotlin.time.Clock
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Opaque correlation for one observed attempt. It retains no client, response or recorder. */
class NetworkCapture
internal constructor(
    internal val owner: Any,
    internal val generation: Long,
    internal val id: Long,
    /** Null only for externally timed observations. Never use ingestion time for them. */
    internal val started: TimeMark?,
) {
    override fun toString() = "NetworkCapture([redacted])"
}

/** Generation token for a buffered producer. Clear/close fences even its delayed start events. */
class NetworkObservationSession
internal constructor(internal val owner: Any, internal val generation: Long) {
    override fun toString() = "NetworkObservationSession([redacted])"
}

/**
 * Host-owned bounded metadata storage. No timers, background scope, I/O or delivery policy. Account
 * replacement closes the old store and installs a new one. Clear fences late finishes.
 */
class NetworkTraceStore(
    private val capacity: Int = 200,
    private val policy: NetworkCapturePolicy = NetworkCapturePolicy(),
    private val clock: Clock = Clock.System,
    private val timeSource: TimeSource = TimeSource.Monotonic,
) {
    init {
        require(capacity in 1..500)
    }

    private val owner = Any()
    private val mutex = Mutex()
    private val entries = linkedMapOf<Long, NetworkTrace>()
    private val state = MutableStateFlow(NetworkTraceSnapshot())
    private var generation = 0L
    private var sequence = 0L
    val snapshots: StateFlow<NetworkTraceSnapshot> = state.asStateFlow()

    suspend fun begin(request: NetworkRequest): NetworkCapture? = beginAt(request, null, null)

    /**
     * The host must obtain a new session after clear and discard the old producer's buffered data.
     */
    suspend fun openObservation(): NetworkObservationSession? =
        mutex.withLock {
            currentCoroutineContext().ensureActive()
            if (state.value.closed) null else NetworkObservationSession(owner, generation)
        }

    suspend fun beginObserved(
        session: NetworkObservationSession,
        request: NetworkRequest,
        startedAtEpochMillis: Long,
    ): NetworkCapture? {
        // Keep timestamps renderable through year9999. Native clock skew is not acquisition delay.
        if (startedAtEpochMillis !in 0..253_402_300_799_999L) return null
        return beginAt(request, session, startedAtEpochMillis)
    }

    /** A buffered reader checks this after each read. Its host still cancels a suspended read. */
    suspend fun isObservationOpen(session: NetworkObservationSession): Boolean =
        mutex.withLock {
            currentCoroutineContext().ensureActive()
            session.owner === owner && session.generation == generation && !state.value.closed
        }

    private suspend fun beginAt(
        request: NetworkRequest,
        session: NetworkObservationSession?,
        observedStart: Long?,
    ): NetworkCapture? {
        if (session != null && session.owner !== owner) return null
        val sanitized = policy.request(request) ?: return null
        return mutex.withLock {
            currentCoroutineContext().ensureActive()
            if (state.value.closed || sequence == Long.MAX_VALUE) return@withLock null
            if (session != null && session.generation != generation) return@withLock null
            val id = ++sequence
            val capture =
                NetworkCapture(
                    owner,
                    generation,
                    id,
                    if (session == null) timeSource.markNow() else null,
                )
            var evicted = state.value.evictedRequests
            if (entries.size == capacity) {
                // Completed rows leave first. If every row is pending, evict the oldest start;
                // its late completion cannot recreate the removed request.
                val victim =
                    entries.values
                        .firstOrNull {
                            it.transfer is NetworkTransfer.Complete ||
                                it.transfer == NetworkTransfer.Unavailable ||
                                (it.transfer == null && it.outcome != null)
                        }
                        ?.id ?: entries.keys.first()
                entries.remove(victim)
                if (evicted != Long.MAX_VALUE) evicted++
            }
            entries[id] =
                NetworkTrace(
                    id,
                    observedStart ?: clock.now().toEpochMilliseconds(),
                    sanitized,
                    transfer = if (session == null) null else NetworkTransfer.InProgress,
                )
            state.value =
                state.value.copy(
                    traces = entries.values.toList().asReversed(),
                    evictedRequests = evicted,
                )
            capture
        }
    }

    suspend fun finish(
        capture: NetworkCapture,
        outcome: NetworkOutcome,
        responseHeaders: List<NetworkHeader> = emptyList(),
        responseBytes: Long? = null,
    ): Boolean {
        if (
            capture.owner !== owner ||
                capture.started == null ||
                (outcome is NetworkOutcome.Response && outcome.status !in 100..599) ||
                (responseBytes != null && responseBytes < 0)
        )
            return false
        val headers = policy.headers(responseHeaders)
        return mutex.withLock {
            currentCoroutineContext().ensureActive()
            if (state.value.closed || capture.generation != generation) return@withLock false
            val existing = entries[capture.id] ?: return@withLock false
            if (existing.outcome != null) return@withLock false
            entries[capture.id] =
                existing.copy(
                    outcome = outcome,
                    durationMillis =
                        capture.started.elapsedNow().inWholeMilliseconds.coerceAtLeast(0),
                    responseHeaders = headers,
                    responseBytes = responseBytes,
                    responseHeadersTruncated = headers.size != responseHeaders.size,
                )
            state.value = state.value.copy(traces = entries.values.toList().asReversed())
            true
        }
    }

    /** Response-header timing from the transport clock, independent of callback/batch delay. */
    suspend fun observedResponse(
        capture: NetworkCapture,
        status: Int,
        elapsedMillis: Long,
        responseHeaders: List<NetworkHeader> = emptyList(),
        metadataTruncated: Boolean = false,
    ): Boolean {
        if (
            capture.owner !== owner ||
                capture.started != null ||
                status !in 100..599 ||
                elapsedMillis < 0
        )
            return false
        val headers = policy.headers(responseHeaders)
        return mutex.withLock {
            currentCoroutineContext().ensureActive()
            if (state.value.closed || capture.generation != generation) return@withLock false
            val existing = entries[capture.id] ?: return@withLock false
            if (existing.transfer != NetworkTransfer.InProgress || existing.outcome != null)
                return@withLock false
            entries[capture.id] =
                existing.copy(
                    outcome = NetworkOutcome.Response(status),
                    durationMillis = elapsedMillis,
                    responseHeaders = headers,
                    responseHeadersTruncated =
                        metadataTruncated || headers.size != responseHeaders.size,
                )
            state.value = state.value.copy(traces = entries.values.toList().asReversed())
            true
        }
    }

    /** A body failure retains real response headers/status. Missing status is never fabricated. */
    suspend fun observedComplete(
        capture: NetworkCapture,
        elapsedMillis: Long,
        responseBytes: Long? = null,
        failure: NetworkFailure? = null,
    ): Boolean {
        if (
            capture.owner !== owner ||
                capture.started != null ||
                elapsedMillis < 0 ||
                (responseBytes != null && responseBytes < 0)
        )
            return false
        return mutex.withLock {
            currentCoroutineContext().ensureActive()
            if (state.value.closed || capture.generation != generation) return@withLock false
            val existing = entries[capture.id] ?: return@withLock false
            if (
                existing.transfer != NetworkTransfer.InProgress ||
                    elapsedMillis < (existing.durationMillis ?: 0)
            )
                return@withLock false
            entries[capture.id] =
                existing.copy(
                    outcome = existing.outcome ?: failure?.let { NetworkOutcome.Failed(it) },
                    durationMillis = existing.durationMillis ?: elapsedMillis,
                    responseBytes = responseBytes,
                    transfer = NetworkTransfer.Complete(elapsedMillis, failure),
                    incomplete =
                        existing.incomplete || (existing.outcome == null && failure == null),
                )
            state.value = state.value.copy(traces = entries.values.toList().asReversed())
            true
        }
    }

    /** Report producer loss once per delta. The session fences late reports after clear/close. */
    suspend fun observedLoss(session: NetworkObservationSession, count: Long): Boolean {
        if (session.owner !== owner || count <= 0) return false
        return mutex.withLock {
            currentCoroutineContext().ensureActive()
            if (state.value.closed || session.generation != generation) return@withLock false
            entries.entries.forEach { entry ->
                if (entry.value.transfer == NetworkTransfer.InProgress)
                    entry.setValue(entry.value.copy(incomplete = true))
            }
            val previous = state.value.droppedObservations
            state.value =
                state.value.copy(
                    traces = entries.values.toList().asReversed(),
                    droppedObservations = previous + count.coerceAtMost(Long.MAX_VALUE - previous),
                )
            true
        }
    }

    /** Capture ended or its correlation was discarded. This is not transport cancellation. */
    suspend fun observedIncomplete(capture: NetworkCapture): Boolean {
        if (capture.owner !== owner || capture.started != null) return false
        return mutex.withLock {
            currentCoroutineContext().ensureActive()
            if (state.value.closed || capture.generation != generation) return@withLock false
            val existing = entries[capture.id] ?: return@withLock false
            if (existing.transfer != NetworkTransfer.InProgress) return@withLock false
            entries[capture.id] =
                existing.copy(transfer = NetworkTransfer.Unavailable, incomplete = true)
            state.value = state.value.copy(traces = entries.values.toList().asReversed())
            true
        }
    }

    suspend fun clear() =
        mutex.withLock {
            entries.clear()
            val closed = state.value.closed || generation == Long.MAX_VALUE
            if (!closed) generation++
            state.value = NetworkTraceSnapshot(closed = closed)
        }

    suspend fun close() =
        mutex.withLock {
            entries.clear()
            state.value = NetworkTraceSnapshot(closed = true)
        }
}
