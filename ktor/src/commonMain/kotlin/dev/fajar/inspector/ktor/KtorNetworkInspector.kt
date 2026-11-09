package dev.fajar.inspector.ktor

import dev.fajar.inspector.*
import io.ktor.client.call.HttpClientCall
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.request.HttpSendPipeline
import io.ktor.util.AttributeKey
import io.ktor.util.pipeline.PipelinePhase
import kotlinx.coroutines.*

class KtorNetworkInspectorConfig {
    /** The host owns and closes this store; it can be shared by several transports. */
    var recorder: NetworkTraceStore? = null

    /** Optional nonblocking lookup, evaluated once per engine attempt. Null disables capture. */
    var recorderProvider: (() -> NetworkTraceStore?)? = null

    /** Receives an encoded path of at most 2,049 characters. Null excludes the attempt. */
    var sanitizePath: (String) -> String? = { it }
}

/**
 * Observes each Ktor engine attempt, including retries and redirects, without reading bodies.
 * Response duration ends at response headers, before validation or body consumption. A later
 * application/body failure does not overwrite an observed HTTP status. No worker or listener is
 * allocated; pipeline registrations live with this client. The host closes the recorder separately.
 */
val KtorNetworkInspector =
    createClientPlugin("KtorNetworkInspector", ::KtorNetworkInspectorConfig) {
        require(pluginConfig.recorder == null || pluginConfig.recorderProvider == null) {
            "Choose one host-owned recorder or provider"
        }
        val fixedRecorder = pluginConfig.recorder
        val recorderProvider =
            pluginConfig.recorderProvider
                ?: {
                    requireNotNull(fixedRecorder) { "A host-owned recorder is required" }
                }
        require(fixedRecorder != null || pluginConfig.recorderProvider != null) {
            "A host-owned recorder or provider is required"
        }
        val metadata = KtorTraceMetadata(pluginConfig.sanitizePath)
        val captureKey = AttributeKey<KtorCapture>("KmpInspectorEngineAttempt")
        val beforeEngine = PipelinePhase("KmpInspectorBeforeEngine")
        val afterEngine = PipelinePhase("KmpInspectorAfterEngine")
        client.sendPipeline.insertPhaseBefore(HttpSendPipeline.Engine, beforeEngine)
        client.sendPipeline.insertPhaseAfter(HttpSendPipeline.Engine, afterEngine)

        client.sendPipeline.intercept(beforeEngine) {
            // A retry/redirect may copy attributes. Never borrow an earlier attempt's handle.
            context.attributes.remove(captureKey)
            val capture =
                try {
                    recorderProvider()?.let { recorder ->
                        metadata.request(context)?.let { request ->
                            recorder.begin(request)?.let { KtorCapture(recorder, it) }
                        }
                    }
                } catch (failure: CancellationException) {
                    currentCoroutineContext().ensureActive()
                    null
                } catch (_: Exception) {
                    // A host's metadata filter must not change the original request.
                    null
                }
            if (capture == null) {
                proceed()
                return@intercept
            }
            context.attributes.put(captureKey, capture)
            try {
                proceed()
            } catch (failure: Throwable) {
                val category = ktorFailureCategory(failure)
                // Only a finite in-memory write survives cancellation; no I/O or child scope.
                withContext(NonCancellable) {
                    try {
                        capture.recorder.finish(capture.handle, NetworkOutcome.Failed(category))
                    } catch (_: Exception) {
                        // Preserve the original transport failure, including cancellation.
                    }
                }
                throw failure
            } finally {
                context.attributes.remove(captureKey)
            }
        }
        client.sendPipeline.intercept(afterEngine) {
            val capture = context.attributes.getOrNull(captureKey) ?: return@intercept
            val response = (subject as? HttpClientCall)?.response ?: return@intercept
            withContext(NonCancellable) {
                try {
                    capture.recorder.finish(
                        capture.handle,
                        NetworkOutcome.Response(response.status.value),
                        metadata.headers(response.headers),
                    )
                } catch (_: Exception) {
                    // Observation never changes the response or forces the body to be consumed.
                }
            }
        }
    }

/** Retains the exact store across reset, redirects, retries and body/transport failures. */
private data class KtorCapture(val recorder: NetworkTraceStore, val handle: NetworkCapture)

private fun ktorFailureCategory(failure: Throwable): NetworkFailure {
    // Ktor's request timeout cancels execution with HttpRequestTimeoutException as its cause.
    // Inspect a bounded chain of types only; exception messages and URLs never enter the store.
    var current: Throwable? = failure
    repeat(8) {
        when (current) {
            is HttpRequestTimeoutException,
            is ConnectTimeoutException,
            is SocketTimeoutException,
            is TimeoutCancellationException -> return NetworkFailure.TIMEOUT
        }
        current = current?.cause
    }
    return if (failure is CancellationException) NetworkFailure.CANCELLED
    else NetworkFailure.UNKNOWN
}
