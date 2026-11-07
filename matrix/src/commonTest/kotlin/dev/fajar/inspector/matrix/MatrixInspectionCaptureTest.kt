@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.inspector.matrix

import dev.fajar.inspector.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.*

class MatrixInspectionCaptureTest {
    private val request =
        NetworkRequest(
            NetworkSource.MATRIX,
            "GET",
            NetworkEndpoint("https", "example.test", path = "/sync"),
        )

    @Test
    fun resetDisposesTheOldReaderBeforeSubscribingAndCannotRefeedItsBuffer() = runTest {
        val inspection = NetworkInspection()
        val first = Source()
        val second = Source()
        val old = assertNotNull(inspection.recorders.value)
        var starts = 0
        val job = launch {
            captureMatrixHttp(inspection) {
                starts++
                if (starts == 1) first
                else {
                    assertEquals(1, first.closes)
                    second
                }
            }
        }
        try {
            runCurrent()
            first.events.send(
                MatrixHttpBatch(listOf(MatrixHttpEvent.Started(1, 1, 1_000, request)))
            )
            runCurrent()
            assertEquals(1, old.snapshots.value.traces.size)
            inspection.reset()
            first.events.trySend(
                MatrixHttpBatch(listOf(MatrixHttpEvent.Started(2, 1, 2_000, request)))
            )
            runCurrent()
            assertEquals(2, starts)
            assertEquals(1, first.closes)
            assertTrue(old.snapshots.value.closed)
            assertTrue(old.snapshots.value.traces.isEmpty())
            val current = assertNotNull(inspection.recorders.value)
            assertTrue(current.snapshots.value.traces.isEmpty())
            second.events.send(
                MatrixHttpBatch(listOf(MatrixHttpEvent.Started(1, 1, 3_000, request)))
            )
            runCurrent()
            assertEquals(3_000L, current.snapshots.value.traces.single().startedAtEpochMillis)
            inspection.close()
            job.join()
            assertEquals(1, second.closes)
        } finally {
            job.cancelAndJoin()
            inspection.close()
        }
    }

    @Test
    fun closeWakesPendingReadAndCompletesTheOwnedCollector() = runTest {
        val inspection = NetworkInspection()
        val source = Source()
        val job = launch { captureMatrixHttp(inspection) { source } }
        runCurrent()
        inspection.close()
        job.join()
        assertEquals(1, source.closes)
        assertFalse(inspection.reset())
    }

    @Test
    fun cancellingOneClientLeavesOtherClientAndInspectionOwned() = runTest {
        val inspection = NetworkInspection()
        val first = Source()
        val second = Source()
        val one = launch { captureMatrixHttp(inspection) { first } }
        val two = launch { captureMatrixHttp(inspection) { second } }
        try {
            runCurrent()
            one.cancelAndJoin()
            assertEquals(1, first.closes)
            assertEquals(0, second.closes)
            assertTrue(two.isActive)
            assertNotNull(inspection.recorders.value)
        } finally {
            one.cancelAndJoin()
            two.cancelAndJoin()
            inspection.close()
        }
    }

    @Test
    fun sourceFailureWaitsForResetWithoutResubscribingInALoop() = runTest {
        val inspection = NetworkInspection()
        val source = Source()
        var starts = 0
        val job = launch {
            captureMatrixHttp(inspection) {
                starts++
                if (starts == 1) error("fixture subscription") else source
            }
        }
        try {
            runCurrent()
            assertEquals(1, starts)
            assertTrue(job.isActive)
            inspection.reset()
            runCurrent()
            assertEquals(2, starts)
        } finally {
            job.cancelAndJoin()
            inspection.close()
        }
        assertEquals(1, source.closes)
    }

    @Test
    fun alreadyClosedInspectionNeverOpensASubscription() = runTest {
        val inspection = NetworkInspection()
        inspection.close()
        captureMatrixHttp(inspection) { error("must not open") }
    }

    @Test
    fun pendingOldCleanupCannotOpenANewSourceEarly() = runTest {
        val inspection = NetworkInspection()
        val release = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        var starts = 0
        val source =
            object : MatrixHttpSubscription {
                override suspend fun nextBatch(): MatrixHttpBatch {
                    try {
                        awaitCancellation()
                    } finally {
                        withContext(NonCancellable) {
                            entered.complete(Unit)
                            release.await()
                        }
                    }
                }

                override fun close() = Unit
            }
        val job = launch {
            captureMatrixHttp(inspection) {
                starts++
                source
            }
        }
        try {
            runCurrent()
            inspection.reset()
            runCurrent()
            assertTrue(entered.isCompleted)
            assertEquals(1, starts)
            release.complete(Unit)
            runCurrent()
            assertEquals(2, starts)
        } finally {
            release.complete(Unit)
            job.cancelAndJoin()
            inspection.close()
        }
    }

    private class Source : MatrixHttpSubscription {
        val events = Channel<MatrixHttpBatch>(8)
        var closes = 0

        override suspend fun nextBatch() = events.receive()

        override fun close() {
            closes++
            events.close()
        }
    }
}
