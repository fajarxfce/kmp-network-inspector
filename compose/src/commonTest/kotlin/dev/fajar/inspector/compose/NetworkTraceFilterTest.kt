package dev.fajar.inspector.compose

import dev.fajar.inspector.*
import kotlin.test.*

class NetworkTraceFilterTest {
    @Test
    fun statusFiltersDistinguishPendingHttpErrorsAndTransportFailures() {
        val pending = trace()
        val redirect = trace(NetworkOutcome.Response(302))
        val denied = trace(NetworkOutcome.Response(403))
        val failure = trace(NetworkOutcome.Failed(NetworkFailure.CONNECTION))
        val values = listOf(pending, redirect, denied, failure)
        assertEquals(values, values.filter(NetworkTraceFilter()::includes))
        assertEquals(
            listOf(pending),
            values.filter(NetworkTraceFilter(status = NetworkTraceStatus.PENDING)::includes),
        )
        assertEquals(
            listOf(denied),
            values.filter(NetworkTraceFilter(status = NetworkTraceStatus.HTTP_ERROR)::includes),
        )
        assertEquals(
            listOf(failure),
            values.filter(
                NetworkTraceFilter(status = NetworkTraceStatus.TRANSPORT_FAILURE)::includes
            ),
        )
    }

    @Test
    fun bodyFailureAndMissingHeadersAreNotClassifiedAsPendingOrFabricatedHttpErrors() {
        val bodyFailure =
            trace(NetworkOutcome.Response(200))
                .copy(transfer = NetworkTransfer.Complete(10, NetworkFailure.CONNECTION))
        val missingHeaders =
            trace().copy(transfer = NetworkTransfer.Complete(20), incomplete = true)
        val pending = NetworkTraceFilter(status = NetworkTraceStatus.PENDING)
        val transport = NetworkTraceFilter(status = NetworkTraceStatus.TRANSPORT_FAILURE)
        val httpError = NetworkTraceFilter(status = NetworkTraceStatus.HTTP_ERROR)
        assertTrue(transport.includes(bodyFailure))
        assertFalse(transport.includes(missingHeaders))
        listOf(bodyFailure, missingHeaders).forEach {
            assertFalse(pending.includes(it))
            assertFalse(httpError.includes(it))
        }
    }

    @Test
    fun sourceQueryAndStatusAreCombinedWithoutChangingTheOriginalRecords() {
        val original = trace(NetworkOutcome.Response(503))
        val selected =
            NetworkTraceFilter("  CLIENT  ", NetworkSource.MATRIX, NetworkTraceStatus.HTTP_ERROR)
        assertTrue(selected.includes(original))
        assertFalse(selected.copy(source = NetworkSource.KTOR).includes(original))
        assertFalse(selected.copy(query = "/login").includes(original))
        assertFalse(selected.copy(status = NetworkTraceStatus.PENDING).includes(original))
        assertEquals("/_matrix/client/versions", original.request.endpoint.path)
        assertEquals(NetworkOutcome.Response(503), original.outcome)
    }

    @Test
    fun searchUsesMethodHostAndPathWithoutSearchingHeadersOrQueryNames() {
        val original = trace()
        assertTrue(NetworkTraceFilter("example.test").includes(original))
        assertTrue(NetworkTraceFilter("get").includes(original))
        assertTrue(NetworkTraceFilter("/versions").includes(original))
        assertFalse(NetworkTraceFilter("authorization").includes(original))
        assertFalse(NetworkTraceFilter("access_token").includes(original))
        assertFalse(NetworkTraceFilter("[redacted]").includes(original))
    }

    @Test
    fun oversizedSearchIsBoundedAndItsDebugStringDoesNotRevealInput() {
        val input = "a".repeat(256)
        val original =
            trace().let {
                it.copy(
                    request = it.request.copy(endpoint = it.request.endpoint.copy(path = "/$input"))
                )
            }
        val filter = NetworkTraceFilter(input + "never-searched".repeat(1_000))
        assertTrue(filter.includes(original))
        assertEquals("NetworkTraceFilter([redacted])", filter.toString())
    }

    private fun trace(outcome: NetworkOutcome? = null) =
        NetworkTrace(
            1,
            1_000,
            NetworkRequest(
                NetworkSource.MATRIX,
                "GET",
                NetworkEndpoint(
                    "https",
                    "example.test",
                    path = "/_matrix/client/versions",
                    queryNames = listOf("access_token"),
                ),
                listOf(NetworkHeader("authorization", "[redacted]")),
            ),
            outcome,
        )
}
