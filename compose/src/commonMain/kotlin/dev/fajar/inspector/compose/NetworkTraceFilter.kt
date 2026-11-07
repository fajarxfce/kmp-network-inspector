package dev.fajar.inspector.compose

import dev.fajar.inspector.NetworkOutcome
import dev.fajar.inspector.NetworkSource
import dev.fajar.inspector.NetworkTrace

/** Presentation filtering only; never starts capture or changes transport. */
data class NetworkTraceFilter(
    val query: String = "",
    val source: NetworkSource? = null,
    val status: NetworkTraceStatus = NetworkTraceStatus.ALL,
) {
    fun includes(trace: NetworkTrace): Boolean {
        if (source != null && trace.request.source != source) return false
        val outcome = trace.outcome
        val statusMatches =
            when (status) {
                NetworkTraceStatus.ALL -> true
                NetworkTraceStatus.PENDING -> outcome == null
                NetworkTraceStatus.HTTP_ERROR ->
                    outcome is NetworkOutcome.Response && outcome.status >= 400
                NetworkTraceStatus.TRANSPORT_FAILURE -> outcome is NetworkOutcome.Failed
            }
        if (!statusMatches) return false
        val search = query.take(256).trim()
        if (search.isEmpty()) return true
        val request = trace.request
        return request.method.contains(search, ignoreCase = true) ||
            request.endpoint.host.contains(search, ignoreCase = true) ||
            request.endpoint.path.contains(search, ignoreCase = true)
    }

    override fun toString() = "NetworkTraceFilter([redacted])"
}

enum class NetworkTraceStatus {
    ALL,
    PENDING,
    HTTP_ERROR,
    TRANSPORT_FAILURE,
}
