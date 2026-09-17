package kr.mom.probe.data

import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/** Holds only the callback reference while local consent is being restored, never its extras. */
internal suspend fun awaitReadyCaptureEpoch(
    callbackEpoch: Long,
    ready: StateFlow<Boolean>,
    currentEpoch: () -> Long,
    timeoutMillis: Long = 5_000,
): Long? {
    val initialized = withTimeoutOrNull(timeoutMillis) { ready.first { it } } ?: return null
    if (!initialized) return null
    val restoredEpoch = currentEpoch()
    // Reject callbacks spanning pause, deletion, changed sources or consent changes.
    return restoredEpoch.takeIf { it == callbackEpoch }
}
