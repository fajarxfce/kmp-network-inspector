package dev.fajar.inspector.matrix

import dev.fajar.inspector.NetworkFailure
import dev.fajar.inspector.NetworkHeader
import dev.fajar.inspector.NetworkRequest

/**
 * Binding boundary for the actual Matrix Rust HTTP observer, never operation-level events. Native
 * producers must bound/redact before crossing FFI. Implementations return at most32 events, wait
 * for progress, preserve cancellation and close the exact native subscription. The caller must not
 * read this subscription concurrently or reuse it after collection ends.
 */
interface MatrixHttpSubscription {
    suspend fun nextBatch(): MatrixHttpBatch

    fun close()
}

data class MatrixHttpBatch(
    val events: List<MatrixHttpEvent>,
    /** Cumulative nonnegative counters for this exact subscription. */
    val droppedEvents: Long = 0,
    val missedAttemptStarts: Long = 0,
    val closed: Boolean = false,
) {
    override fun toString() = "MatrixHttpBatch([redacted])"
}

sealed interface MatrixHttpEvent {
    /** Native diagnostic identity, unrelated to message transactions and event IDs. */
    val id: Long

    data class Started(
        override val id: Long,
        val attempt: Long,
        val startedAtEpochMillis: Long,
        val request: NetworkRequest,
    ) : MatrixHttpEvent {
        override fun toString() = "MatrixHttpEvent.Started([redacted])"
    }

    data class Response(
        override val id: Long,
        val status: Int,
        val elapsedMillis: Long,
        val headers: List<NetworkHeader> = emptyList(),
        val truncated: Boolean = false,
    ) : MatrixHttpEvent {
        override fun toString() = "MatrixHttpEvent.Response([redacted])"
    }

    data class Finished(
        override val id: Long,
        val elapsedMillis: Long,
        val responseBytes: Long? = null,
        val failure: NetworkFailure? = null,
    ) : MatrixHttpEvent {
        override fun toString() = "MatrixHttpEvent.Finished([redacted])"
    }
}
