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
    /** One-based transport attempt, when the adapter can observe it. */
    val attemptNumber: Long? = null,
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

/** Optional body-transfer observation. Null on a trace means this stage is not captured. */
sealed interface NetworkTransfer {
    data object InProgress : NetworkTransfer

    /** Observation ended without a terminal transport event; no failure/duration is inferred. */
    data object Unavailable : NetworkTransfer

    data class Complete(val durationMillis: Long, val failure: NetworkFailure? = null) :
        NetworkTransfer
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
    val transfer: NetworkTransfer? = null,
    /** Producer loss, a missing response, or observation ending before the terminal event. */
    val incomplete: Boolean = false,
) {
    override fun toString() = "NetworkTrace([redacted])"
}

data class NetworkTraceSnapshot(
    /** Most recently received starts first. Completion changes the row without moving it. */
    val traces: List<NetworkTrace> = emptyList(),
    val evictedRequests: Long = 0,
    val closed: Boolean = false,
    val droppedObservations: Long = 0,
) {
    override fun toString() = "NetworkTraceSnapshot([redacted])"
}
