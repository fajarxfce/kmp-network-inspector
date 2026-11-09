package dev.fajar.inspector

import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest

class NetworkInspectionTest {
    private val request =
        NetworkRequest(
            NetworkSource.CUSTOM,
            "GET",
            NetworkEndpoint("https", "example.test", path = "/items"),
        )

    @Test
    fun resetErasesOldMetadataAndRejectsLateLocalAndBufferedEvents() = runTest {
        val inspection = NetworkInspection()
        val previous = assertNotNull(inspection.recorders.value)
        val handle = assertNotNull(previous.begin(request))
        val buffered = assertNotNull(previous.openObservation())
        assertTrue(inspection.reset())
        val replacement = assertNotNull(inspection.recorders.value)
        assertNotSame(previous, replacement)
        assertTrue(previous.snapshots.value.closed)
        assertTrue(previous.snapshots.value.traces.isEmpty())
        assertFalse(previous.finish(handle, NetworkOutcome.Response(200)))
        assertNull(previous.beginObserved(buffered, request, 1_000))
        assertTrue(replacement.snapshots.value.traces.isEmpty())
        assertNotNull(replacement.begin(request))
        inspection.close()
    }

    @Test
    fun closeErasesAndCannotBeReopenedByALateReset() = runTest {
        val inspection = NetworkInspection()
        val old = assertNotNull(inspection.recorders.value)
        old.begin(request)
        inspection.close()
        inspection.close()
        assertNull(inspection.recorders.value)
        assertFalse(inspection.reset())
        assertTrue(old.snapshots.value.closed)
        assertTrue(old.snapshots.value.traces.isEmpty())
    }

    @Test
    fun cancelledResetCannotEraseTheCurrentCapture() = runTest {
        val inspection = NetworkInspection()
        val original = assertNotNull(inspection.recorders.value)
        original.begin(request)
        val attempt =
            launch(start = CoroutineStart.UNDISPATCHED) {
                currentCoroutineContext().cancel()
                inspection.reset()
            }
        attempt.join()
        assertSame(original, inspection.recorders.value)
        assertFalse(original.snapshots.value.closed)
        assertEquals(1, original.snapshots.value.traces.size)
        inspection.close()
    }

    @Test
    fun concurrentResetAndCloseLeaveNoWritableRecorder() = runTest {
        val inspection = NetworkInspection()
        val seen = mutableListOf(assertNotNull(inspection.recorders.value))
        coroutineScope {
            repeat(20) {
                launch {
                    inspection.reset()
                    inspection.recorders.value?.let(seen::add)
                }
            }
            launch { inspection.close() }
        }
        assertNull(inspection.recorders.value)
        assertTrue(seen.all { it.snapshots.value.closed })
    }

    @Test
    fun replacementKeepsTheConfiguredCapacity() = runTest {
        val inspection = NetworkInspection(1)
        inspection.reset()
        val store = assertNotNull(inspection.recorders.value)
        repeat(3) { store.begin(request) }
        assertEquals(1, store.snapshots.value.traces.size)
        assertEquals(2L, store.snapshots.value.evictedRequests)
        inspection.close()
    }
}
