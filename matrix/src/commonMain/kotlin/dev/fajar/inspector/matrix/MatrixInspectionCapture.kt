package dev.fajar.inspector.matrix

import dev.fajar.inspector.NetworkInspection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.transformWhile

/**
 * Collect for one native client in its existing host-owned lifetime. Reset cancels and disposes the
 * old source before subscribing again; close ends collection. Each source must be fresh and bound
 * to this exact client. A diagnostic source failure waits for a reset, never retries HTTP.
 */
suspend fun captureMatrixHttp(
    inspection: NetworkInspection,
    subscribe: () -> MatrixHttpSubscription,
) {
    inspection.recorders
        .transformWhile { recorder ->
            emit(recorder)
            recorder != null
        }
        .collectLatest { recorder ->
            if (recorder != null) {
                try {
                    captureMatrixHttp(subscribe(), recorder)
                } catch (_: CancellationException) {
                    currentCoroutineContext().ensureActive()
                } catch (_: Exception) {
                    // The owning collector already closed the exact source and marked partial rows.
                    // Raw SDK exception messages must not be retained or affect the client's work.
                }
            }
        }
}
