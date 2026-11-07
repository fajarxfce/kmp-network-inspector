package dev.fajar.inspector.ktor

import dev.fajar.inspector.*
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.http.Headers

/** Bounded transport mapping; raw authorization/query/body values never enter retained records. */
internal class KtorTraceMetadata(private val sanitizePath: (String) -> String?) {
    fun request(builder: HttpRequestBuilder): NetworkRequest? {
        val url = builder.url
        val encodedPath = boundedPath(url.encodedPathSegments)
        val path = sanitizePath(encodedPath) ?: return null
        val names = url.encodedParameters.names().asSequence().take(33).map { it.take(81) }.toList()
        val requestHeaders = headers(builder.headers.names()) { builder.headers[it] }
        return NetworkRequest(
            NetworkSource.KTOR,
            builder.method.value,
            NetworkEndpoint(
                url.protocol.name,
                url.host,
                url.port.takeIf { it != 0 } ?: url.protocol.defaultPort,
                path.take(2_049),
                names,
            ),
            requestHeaders,
            metadataTruncated = encodedPath.length > 2_048 || path.length > 2_048,
        )
    }

    fun headers(headers: Headers): List<NetworkHeader> = headers(headers.names()) { headers[it] }

    private fun headers(names: Set<String>, firstValue: (String) -> String?): List<NetworkHeader> =
        names
            .asSequence()
            .take(65)
            .map { name ->
                // Never concatenate a potentially large list of header values. A 65th bounded
                // entry lets the core report the dropped metadata instead of hiding truncation.
                val boundedName = name.take(81)
                val value =
                    when (boundedName.lowercase()) {
                        "content-type",
                        "content-length",
                        "retry-after" -> firstValue(name)?.take(257).orEmpty()
                        else -> "[redacted]"
                    }
                NetworkHeader(boundedName, value)
            }
            .toList()

    private fun boundedPath(segments: List<String>): String = buildString {
        if (segments.isEmpty() || segments.first().isNotEmpty()) append('/')
        for ((index, segment) in segments.withIndex()) {
            if (length == 2_049) break
            if (index > 0) append('/')
            append(segment, 0, minOf(segment.length, 2_049 - length))
        }
        if (isEmpty()) append('/')
    }
}
