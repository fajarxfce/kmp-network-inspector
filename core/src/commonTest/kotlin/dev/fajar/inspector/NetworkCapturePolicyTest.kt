package dev.fajar.inspector

import kotlin.test.*

class NetworkCapturePolicyTest {
    private val policy = NetworkCapturePolicy()
    private val request =
        NetworkRequest(NetworkSource.KTOR, "get", NetworkEndpoint("https", "example.test"))

    @Test
    fun retentionBoundsApplyToEveryVariableLengthCollectionAndField() {
        val largeValue = "secret".repeat(10_000)
        val result =
            assertNotNull(
                policy.request(
                    request.copy(
                        endpoint =
                            request.endpoint.copy(
                                path = "/" + "x".repeat(50_000),
                                queryNames = (0..999).map { "key$it" },
                            ),
                        headers = (0..999).map { NetworkHeader("X-Header$it", largeValue) },
                    )
                )
            )
        assertEquals(2_048, result.endpoint.path.length)
        assertEquals(32, result.endpoint.queryNames.size)
        assertEquals(64, result.headers.size)
        assertTrue(result.metadataTruncated)
        assertTrue(result.headers.all { it.value == "[redacted]" })
        assertEquals("GET", result.method)
    }

    @Test
    fun unknownAndSensitiveHeadersAreAlwaysRedactedIncludingAlternateCase() {
        val names =
            listOf(
                "Authorization",
                "COOKIE",
                "Set-Cookie",
                "X-Api-Key",
                "Proxy-Authorization",
                "X-Private-Key",
                "Unknown-Header",
            )
        assertTrue(
            policy.headers(names.map { NetworkHeader(it, "secret") }).all {
                it.value == "[redacted]"
            }
        )
    }

    @Test
    fun safeHeaderValuesStillRequireTheExpectedWireShape() {
        val result =
            policy.headers(
                listOf(
                    NetworkHeader("Content-Length", "123"),
                    NetworkHeader("Content-Length", "secret"),
                    NetworkHeader("Content-Type", "application/json; charset=utf-8"),
                    NetworkHeader("Content-Type", "application/json\r\nsecret"),
                    NetworkHeader("Retry-After", "2"),
                )
            )
        assertEquals(
            listOf("123", "[redacted]", "application/json; charset=utf-8", "[redacted]", "2"),
            result.map { it.value },
        )
    }

    @Test
    fun invalidUrlPartsAndHeaderNamesAreNotRetained() {
        for (endpoint in
            listOf(
                request.endpoint.copy(scheme = "file"),
                request.endpoint.copy(host = "private@host"),
                request.endpoint.copy(host = "host/path"),
                request.endpoint.copy(host = "host:secret"),
                request.endpoint.copy(host = "host%40private"),
                request.endpoint.copy(port = 0),
                request.endpoint.copy(path = "https://foreign"),
                request.endpoint.copy(path = "/bad\npath"),
            )) assertNull(policy.request(request.copy(endpoint = endpoint)))
        assertTrue(
            policy
                .headers(
                    listOf(
                        NetworkHeader("Bad\nName", "value"),
                        NetworkHeader("x".repeat(81), "value"),
                    )
                )
                .isEmpty()
        )
    }

    @Test
    fun parsedIpv6HostsAreSupportedAndDroppedQueryNamesAreReported() {
        for (host in listOf("::1", "[2001:db8::1]", "::ffff:127.0.0.1")) {
            assertNotNull(
                policy.request(request.copy(endpoint = request.endpoint.copy(host = host)))
            )
        }
        val captured =
            assertNotNull(
                policy.request(
                    request.copy(
                        endpoint =
                            request.endpoint.copy(
                                queryNames = listOf("valid", "invalid=private", "valid")
                            )
                    )
                )
            )
        assertEquals(listOf("valid"), captured.endpoint.queryNames)
        assertTrue(captured.metadataTruncated)
    }
}
