package dev.fajar.inspector.matrix.rust

import dev.fajar.inspector.NetworkEndpoint
import dev.fajar.inspector.NetworkFailure
import dev.fajar.inspector.NetworkHeader
import dev.fajar.inspector.NetworkRequest
import dev.fajar.inspector.NetworkSource
import dev.fajar.inspector.matrix.MatrixHttpBatch
import dev.fajar.inspector.matrix.MatrixHttpEvent
import dev.fajar.inspector.matrix.MatrixHttpSubscription
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.matrix.rustcomponents.sdk.Client
import org.matrix.rustcomponents.sdk.HttpObservationEvent
import org.matrix.rustcomponents.sdk.HttpObservationSubscription
import org.matrix.rustcomponents.sdk.HttpObservedFailure

/**
 * Open one real Rust HTTP subscription. The host retains the client and owns collection in a
 * supervised diagnostics job. Closing this subscription never closes the client or its requests.
 * Requires the SDK observer binding; an application-level event stream is not a substitute.
 */
fun Client.observeNetworkRequests(): MatrixHttpSubscription =
    MatrixRustHttpSubscription(observeHttpRequests())

/** Owns the generated subscription handle, including native stop before handle disposal. */
internal class MatrixRustHttpSubscription(private val native: HttpObservationSubscription) :
    MatrixHttpSubscription {
    private var reading = false
    private val lifecycle = Any()
    private var closed = false
    private var disposed = false

    override suspend fun nextBatch(): MatrixHttpBatch {
        currentCoroutineContext().ensureActive()
        synchronized(lifecycle) {
            if (closed) return MatrixHttpBatch(emptyList(), closed = true)
            check(!reading) { "Only one observation reader is allowed" }
            reading = true
        }
        var failure: Throwable? = null
        try {
            val batch = native.nextBatch()
            currentCoroutineContext().ensureActive()
            synchronized(lifecycle) {
                if (closed) return MatrixHttpBatch(emptyList(), closed = true)
            }
            // The pinned native producer bounds before FFI allocation. Reject an incompatible
            // producer before creating any additional collection or retaining its fields.
            check(batch.events.size <= 32) { "Incompatible observer batch size" }
            val mapped =
                MatrixHttpBatch(
                    events = batch.events.map { it.toInspectorEvent() },
                    droppedEvents = batch.droppedEvents.saturatedLong(),
                    missedAttemptStarts = batch.missedAttemptStarts.saturatedLong(),
                    closed = batch.closed,
                )
            return synchronized(lifecycle) {
                if (closed) MatrixHttpBatch(emptyList(), closed = true) else mapped
            }
        } catch (caught: Throwable) {
            failure = caught
            throw caught
        } finally {
            synchronized(lifecycle) {
                reading = false
                if (closed) {
                    try {
                        dispose()
                    } catch (closing: Throwable) {
                        if (failure == null) throw closing
                        failure.addSuppressed(closing)
                    }
                }
            }
        }
    }

    override fun close() {
        synchronized(lifecycle) {
            if (closed) return
            closed = true
            var failure: Throwable? = null
            try {
                // AutoCloseable.close alone only drops this handle. Native stop erases queued
                // metadata and wakes a pending read; defer disposal until that read has returned.
                native.stop()
            } catch (caught: Throwable) {
                failure = caught
                throw caught
            } finally {
                if (!reading) {
                    try {
                        dispose()
                    } catch (closing: Throwable) {
                        if (failure == null) throw closing
                        failure.addSuppressed(closing)
                    }
                }
            }
        }
    }

    private fun dispose() {
        if (disposed) return
        disposed = true
        native.close()
    }
}

private fun HttpObservationEvent.toInspectorEvent(): MatrixHttpEvent =
    when (this) {
        is HttpObservationEvent.Started -> {
            check(request.queryNames.size <= 32 && request.headerNames.size <= 32) {
                "Incompatible observer metadata bounds"
            }
            check(
                request.method.length <= 16 &&
                    request.scheme.length <= 16 &&
                    request.host.length <= 253 &&
                    request.path.length <= 1024 &&
                    request.queryNames.all { it.length <= 64 } &&
                    request.headerNames.all { it.length <= 64 }
            ) {
                "Incompatible observer field bounds"
            }
            MatrixHttpEvent.Started(
                id = id.exactLong(),
                attempt = attempt.exactLong(),
                startedAtEpochMillis = startedAtEpochMillis.exactLong(),
                request =
                    NetworkRequest(
                        source = NetworkSource.MATRIX,
                        method = request.method,
                        endpoint =
                            NetworkEndpoint(
                                scheme = request.scheme,
                                host = request.host,
                                port = request.port?.toInt(),
                                path = request.path,
                                queryNames = request.queryNames.toList(),
                            ),
                        headers = request.headerNames.map { NetworkHeader(it, "[not captured]") },
                        metadataTruncated = request.truncated,
                    ),
            )
        }
        is HttpObservationEvent.Response -> {
            check(headerNames.size <= 32 && headerNames.all { it.length <= 64 }) {
                "Incompatible observer metadata bounds"
            }
            MatrixHttpEvent.Response(
                id = id.exactLong(),
                status = status.toInt(),
                elapsedMillis = elapsedMillis.exactLong(),
                headers = headerNames.map { NetworkHeader(it, "[not captured]") },
                truncated = truncated,
            )
        }
        is HttpObservationEvent.Finished ->
            MatrixHttpEvent.Finished(
                id = id.exactLong(),
                elapsedMillis = elapsedMillis.exactLong(),
                responseBytes = responseBytes?.exactLong(),
                failure =
                    when (failure) {
                        HttpObservedFailure.TIMEOUT -> NetworkFailure.TIMEOUT
                        HttpObservedFailure.CONNECTION -> NetworkFailure.CONNECTION
                        HttpObservedFailure.CANCELLED -> NetworkFailure.CANCELLED
                        HttpObservedFailure.OTHER -> NetworkFailure.UNKNOWN
                        null -> null
                    },
            )
    }

// IDs and measurements cannot be clamped without confusing correlation or timing. Counters may
// saturate, since their contract is monotone loss accounting and the core also saturates at MAX.
private fun ULong.exactLong(): Long {
    check(this <= Long.MAX_VALUE.toULong()) { "Observer value exceeds the supported range" }
    return toLong()
}

private fun ULong.saturatedLong(): Long = minOf(this, Long.MAX_VALUE.toULong()).toLong()
