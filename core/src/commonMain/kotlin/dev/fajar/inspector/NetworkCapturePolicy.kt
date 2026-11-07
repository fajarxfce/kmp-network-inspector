package dev.fajar.inspector

/** Metadata-only initial capture. Bodies and query values have no retention field. */
class NetworkCapturePolicy {
    fun request(value: NetworkRequest): NetworkRequest? {
        val endpoint = value.endpoint
        if (value.method.length !in 1..16 || !value.method.all { it in 'A'..'Z' || it in 'a'..'z' })
            return null
        if (endpoint.scheme !in setOf("http", "https", "ws", "wss")) return null
        if (
            endpoint.host.length !in 1..255 ||
                !(endpoint.host.matches(dnsHost) || endpoint.host.matches(ipv6Host))
        )
            return null
        if (endpoint.port != null && endpoint.port !in 1..65535) return null
        val path = endpoint.path.take(2_048).substringBefore('?').substringBefore('#')
        if (!path.startsWith('/') || path.any(Char::isISOControl)) return null
        val capturedHeaders = headers(value.headers)
        val queryNames =
            endpoint.queryNames
                .take(32)
                .mapNotNull { name ->
                    name.takeIf {
                        it.length in 1..80 && it.all { c -> c.isLetterOrDigit() || c in "_.-[]" }
                    }
                }
                .distinct()
        return value.copy(
            method = value.method.uppercase(),
            endpoint = endpoint.copy(path = path, queryNames = queryNames),
            headers = capturedHeaders,
            metadataTruncated =
                value.metadataTruncated ||
                    endpoint.path.length > 2_048 ||
                    queryNames.size != endpoint.queryNames.size ||
                    capturedHeaders.size != value.headers.size,
        )
    }

    /** Preserve useful wire metadata while treating every other header value as sensitive. */
    fun headers(values: List<NetworkHeader>): List<NetworkHeader> =
        values.take(64).mapNotNull { header ->
            val name = header.name
            if (name.length !in 1..80 || !name.all { it.isLetterOrDigit() || it == '-' })
                return@mapNotNull null
            val key = name.lowercase()
            val value = header.value
            val safe =
                value.length <= 256 &&
                    value.none(Char::isISOControl) &&
                    when (key) {
                        "content-length",
                        "retry-after" -> value.toLongOrNull()?.let { it >= 0 } == true
                        "content-type" -> value.matches(contentType)
                        else -> false
                    }
            NetworkHeader(name, if (safe) value else "[redacted]")
        }

    private val contentType =
        Regex("[A-Za-z0-9!#$&^_.+-]+/[A-Za-z0-9!#$&^_.+-]+(?:;[ ]*charset=[A-Za-z0-9_-]+)?")
    private val dnsHost = Regex("[A-Za-z0-9.-]+")
    private val ipv6Host =
        Regex("(?:[A-Fa-f0-9.]*:[A-Fa-f0-9.:]+|\\[[A-Fa-f0-9.]*:[A-Fa-f0-9.:]+\\])")
}
