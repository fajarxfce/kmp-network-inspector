package dev.fajar.inspector

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Host-owned capture lifetime. Reset closes the old recorder before publishing a new one, so
 * buffered native starts and late HTTP completions cannot enter the replacement capture. No
 * coroutine or transport is owned here; each adapter collects in its host's scope.
 */
class NetworkInspection(private val capacity: Int = 200) {
    private val mutex = Mutex()
    private val state = MutableStateFlow<NetworkTraceStore?>(NetworkTraceStore(capacity))
    val recorders: StateFlow<NetworkTraceStore?> = state.asStateFlow()

    /** Clear or account replacement; never reopens a closed inspection. */
    suspend fun reset(): Boolean =
        mutex.withLock {
            currentCoroutineContext().ensureActive()
            val previous = state.value ?: return@withLock false
            previous.close()
            state.value = NetworkTraceStore(capacity)
            true
        }

    suspend fun close() =
        mutex.withLock {
            currentCoroutineContext().ensureActive()
            state.value?.close()
            state.value = null
        }
}
