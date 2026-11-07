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
    internal val started: TimeMark,
) {
    override fun toString() = "NetworkCapture([redacted])"
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

    suspend fun begin(request: NetworkRequest): NetworkCapture? {
        val sanitized = policy.request(request) ?: return null
        return mutex.withLock {
            currentCoroutineContext().ensureActive()
            if (state.value.closed || sequence == Long.MAX_VALUE) return@withLock null
            val id = ++sequence
            val capture = NetworkCapture(owner, generation, id, timeSource.markNow())
            var evicted = state.value.evictedRequests
            if (entries.size == capacity) {
                // Completed rows leave first. If every row is pending, evict the oldest start;
                // its late completion cannot recreate the removed request.
                val victim =
                    entries.values.firstOrNull { it.outcome != null }?.id ?: entries.keys.first()
                entries.remove(victim)
                if (evicted != Long.MAX_VALUE) evicted++
            }
            entries[id] = NetworkTrace(id, clock.now().toEpochMilliseconds(), sanitized)
            state.value = NetworkTraceSnapshot(entries.values.toList().asReversed(), evicted)
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
