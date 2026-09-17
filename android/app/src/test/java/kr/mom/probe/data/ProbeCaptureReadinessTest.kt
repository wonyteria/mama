package kr.mom.probe.data

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ProbeCaptureReadinessTest {
    @Test fun `first callback waits for persisted settings instead of using initial false consent`() = runBlocking {
        val ready = MutableStateFlow(false)
        val capture = async(start = CoroutineStart.UNDISPATCHED) { awaitReadyCaptureEpoch(0, ready, { 0 }) }
        assertFalse(capture.isCompleted)
        ready.value = true
        assertEquals(0L, capture.await())
    }

    @Test fun `policy changes during startup invalidate pending callbacks`() = runBlocking {
        val ready = MutableStateFlow(false)
        var epoch = 0L
        val capture = async(start = CoroutineStart.UNDISPATCHED) { awaitReadyCaptureEpoch(0, ready, { epoch }) }
        epoch = 1L
        ready.value = true
        assertNull(capture.await())
    }

    @Test fun `startup callback wait is bounded`() = runBlocking {
        assertNull(awaitReadyCaptureEpoch(0, MutableStateFlow(false), { 0 }, timeoutMillis = 1))
    }
}
