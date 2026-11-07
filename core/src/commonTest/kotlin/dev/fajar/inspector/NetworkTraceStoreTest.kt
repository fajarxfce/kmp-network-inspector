package dev.fajar.inspector

import kotlin.test.*
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant
import kotlin.time.TestTimeSource
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest

class NetworkTraceStoreTest {
    private val request =
        NetworkRequest(
            NetworkSource.MATRIX,
            "PUT",
            NetworkEndpoint("https", "matrix.example.test", path = "/_matrix/client/v3/sync"),
        )

    @Test
    fun startAndResponsePreserveOneRowAndUseMonotonicDuration() = runTest {
        val time = TestTimeSource()
        var wall = Instant.fromEpochMilliseconds(1_000)
        val store =
            NetworkTraceStore(
                clock =
                    object : Clock {
                        override fun now() = wall
                    },
                timeSource = time,
            )
        val handle = assertNotNull(store.begin(request))
        val original = store.snapshots.value.traces.single()
        assertNull(original.outcome)
        assertNull(original.durationMillis)
        wall = Instant.fromEpochMilliseconds(-10_000)
        time += 125.milliseconds
        assertTrue(store.finish(handle, NetworkOutcome.Response(200), responseBytes = 25))
        val finished = store.snapshots.value.traces.single()
        assertEquals(original.id, finished.id)
        assertEquals(1_000L, finished.startedAtEpochMillis)
        assertEquals(125L, finished.durationMillis)
        assertEquals(25L, finished.responseBytes)
        assertEquals(NetworkOutcome.Response(200), finished.outcome)
        assertNull(original.outcome)
    }

    @Test
    fun completionOrderNeverReordersRequestStartsOrOverwritesATerminalOutcome() = runTest {
        val store = NetworkTraceStore()
        val first = assertNotNull(store.begin(request))
        val second = assertNotNull(store.begin(request.copy(source = NetworkSource.KTOR)))
        val ids = store.snapshots.value.traces.map { it.id }
        assertTrue(store.finish(second, NetworkOutcome.Response(503)))
        assertTrue(store.finish(first, NetworkOutcome.Failed(NetworkFailure.TIMEOUT)))
        assertFalse(store.finish(first, NetworkOutcome.Response(200)))
        assertEquals(ids, store.snapshots.value.traces.map { it.id })
        assertEquals(
            NetworkOutcome.Failed(NetworkFailure.TIMEOUT),
            store.snapshots.value.traces.last().outcome,
        )
    }

    @Test
    fun clearFencesLateResponsesAndOtherStoresCannotReuseAnAttemptHandle() = runTest {
        val store = NetworkTraceStore()
        val original = assertNotNull(store.begin(request))
        val other = NetworkTraceStore()
        assertNotNull(other.begin(request))
        assertFalse(other.finish(original, NetworkOutcome.Response(200)))
        store.clear()
        val replacement = assertNotNull(store.begin(request))
        assertFalse(store.finish(original, NetworkOutcome.Response(200)))
        assertEquals(1, store.snapshots.value.traces.size)
        assertTrue(store.finish(replacement, NetworkOutcome.Response(201)))
    }

    @Test
    fun closingErasesRetainedMetadataAndCannotBeReopenedByClearOrLateCallbacks() = runTest {
        val store = NetworkTraceStore()
        val handle = assertNotNull(store.begin(request))
        store.close()
        assertTrue(store.snapshots.value.closed)
        assertTrue(store.snapshots.value.traces.isEmpty())
        assertNull(store.begin(request))
        assertFalse(store.finish(handle, NetworkOutcome.Response(200)))
        store.clear()
        assertTrue(store.snapshots.value.closed)
        assertNull(store.begin(request))
    }

    @Test
    fun boundedRetentionPrefersCompletedRowsAndNeverRestoresEvictedInflightRequests() = runTest {
        val store = NetworkTraceStore(capacity = 2)
        val pending = assertNotNull(store.begin(request))
        val completed = assertNotNull(store.begin(request))
        store.finish(completed, NetworkOutcome.Response(200))
        assertNotNull(store.begin(request))
        assertEquals(pending.id, store.snapshots.value.traces.last().id)
        assertEquals(1L, store.snapshots.value.evictedRequests)
        assertNotNull(store.begin(request))
        assertFalse(store.finish(pending, NetworkOutcome.Response(200)))
        assertEquals(2, store.snapshots.value.traces.size)
        assertEquals(2L, store.snapshots.value.evictedRequests)
    }

    @Test
    fun requestAndResponseSecretsAreRedactedBeforePublicationAndInputsAreDetached() = runTest {
        val requestHeaders =
            mutableListOf(
                NetworkHeader("Authorization", "Bearer private-token"),
                NetworkHeader("Content-Type", "application/json"),
            )
        val query = mutableListOf("access_token")
        val store = NetworkTraceStore()
        val handle =
            assertNotNull(
                store.begin(
                    request.copy(
                        headers = requestHeaders,
                        endpoint =
                            request.endpoint.copy(
                                path = "/sync?token=private#fragment",
                                queryNames = query,
                            ),
                    )
                )
            )
        requestHeaders.clear()
        query.clear()
        assertTrue(
            store.finish(
                handle,
                NetworkOutcome.Response(200),
                listOf(NetworkHeader("Set-Cookie", "session=private")),
            )
        )
        val trace = store.snapshots.value.traces.single()
        assertEquals("/sync", trace.request.endpoint.path)
        assertEquals(listOf("access_token"), trace.request.endpoint.queryNames)
        assertEquals(
            listOf("[redacted]", "application/json"),
            trace.request.headers.map { it.value },
        )
        assertEquals("[redacted]", trace.responseHeaders.single().value)
        assertFalse(trace.toString().contains("private"))
    }

    @Test
    fun malformedTransportMetadataDoesNotCreateOrCompleteARequest() = runTest {
        val store = NetworkTraceStore()
        assertNull(store.begin(request.copy(method = "GET\r\nAuthorization")))
        assertNull(
            store.begin(request.copy(endpoint = request.endpoint.copy(host = "user:password@host")))
        )
        val handle = assertNotNull(store.begin(request))
        assertFalse(store.finish(handle, NetworkOutcome.Response(99)))
        assertFalse(store.finish(handle, NetworkOutcome.Response(200), responseBytes = -1))
        assertNull(store.snapshots.value.traces.single().outcome)
    }

    @Test
    fun concurrentStartsFinishesAndCloseLeaveNoLateMetadataOrOverCapacitySnapshot() = runTest {
        val store = NetworkTraceStore(capacity = 8)
        val jobs =
            List(64) { index ->
                async(Dispatchers.Default) {
                    val handle = store.begin(request)
                    yield()
                    if (index == 32) store.close()
                    if (handle != null) store.finish(handle, NetworkOutcome.Response(200))
                    assertTrue(store.snapshots.value.traces.size <= 8)
                }
            }
        jobs.awaitAll()
        assertTrue(store.snapshots.value.closed)
        assertTrue(store.snapshots.value.traces.isEmpty())
    }

    @Test
    fun aCancelledCallerCannotPublishACaptureEvenWhenTheStoreLockIsUncontended() = runTest {
        val store = NetworkTraceStore()
        val job =
            launch(start = CoroutineStart.UNDISPATCHED) {
                currentCoroutineContext().cancel()
                assertFailsWith<CancellationException> { store.begin(request) }
            }
        job.join()
        assertTrue(store.snapshots.value.traces.isEmpty())
    }
}
