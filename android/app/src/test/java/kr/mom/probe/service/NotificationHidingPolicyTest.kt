package kr.mom.probe.service

import org.junit.Assert.assertFalse
import org.junit.Test

class NotificationHidingPolicyTest {
    @Test fun `original notification hiding stays disabled until safety evidence exists`() {
        assertFalse(NotificationHidingPolicy.mayHideOriginal())
    }
}
