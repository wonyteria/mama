package kr.mom.probe.reminder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingUnlockActionTest {
    @Test
    fun keepsPendingWhenDismissSucceedsBeforeKeyguardReportsUnlocked() {
        val action = PendingUnlockAction()
        var opens = 0

        action.begin()

        assertFalse(action.runIfUnlocked(isUnlocked = { false }) { opens++ })
        assertTrue(action.pending)
        assertEquals(0, opens)

        assertTrue(action.runIfUnlocked(isUnlocked = { true }) { opens++ })
        assertFalse(action.pending)
        assertEquals(1, opens)
    }

    @Test
    fun cancelClearsPendingUnlockAction() {
        val action = PendingUnlockAction()
        var opens = 0

        action.begin()
        action.cancel()

        assertFalse(action.runIfUnlocked(isUnlocked = { true }) { opens++ })
        assertFalse(action.pending)
        assertEquals(0, opens)
    }
}
