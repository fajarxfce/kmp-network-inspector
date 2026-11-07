package dev.fajar.inspector

enum class NetworkSource {
    KTOR,
    MATRIX,
    CUSTOM,
}

/** Structured parts prevent credentials or query values from becoming the displayed URL. */
data class NetworkEndpoint(
    val scheme: String,
    val host: String,
    val port: Int? = null,
    val path: String = "/",
    val queryNames: List<String> = emptyList(),
) {
    override fun toString() = "NetworkEndpoint([redacted])"
}

data class NetworkHeader(val name: String, val value: String) {
    override fun toString() = "NetworkHeader([redacted])"
}

data class NetworkRequest(
    val source: NetworkSource,
    val method: String,
    val endpoint: NetworkEndpoint,
    val headers: List<NetworkHeader> = emptyList(),
    val metadataTruncated: Boolean = false,
) {
    override fun toString() = "NetworkRequest([redacted])"
}

/** Closed categories deliberately exclude exception messages and native debug strings. */
enum class NetworkFailure {
    DNS,
    CONNECTION,
    TLS,
    TIMEOUT,
    CANCELLED,
    UNKNOWN,
}

sealed interface NetworkOutcome {
    data class Response(val status: Int) : NetworkOutcome

    data class Failed(val category: NetworkFailure) : NetworkOutcome
}

data class NetworkTrace(
    val id: Long,
    val startedAtEpochMillis: Long,
    val request: NetworkRequest,
    /** Null until an actual response or terminal transport failure was observed. */
    val outcome: NetworkOutcome? = null,
    val durationMillis: Long? = null,
    val responseHeaders: List<NetworkHeader> = emptyList(),
    val responseBytes: Long? = null,
    val responseHeadersTruncated: Boolean = false,
) {
    override fun toString() = "NetworkTrace([redacted])"
}

data class NetworkTraceSnapshot(
    /** Newest starts first. Completion changes the existing row without moving it. */
    val traces: List<NetworkTrace> = emptyList(),
    val evictedRequests: Long = 0,
    val closed: Boolean = false,
) {
    override fun toString() = "NetworkTraceSnapshot([redacted])"
}
