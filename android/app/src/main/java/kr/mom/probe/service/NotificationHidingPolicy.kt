package kr.mom.probe.service

/**
 * Boundary for hiding the source app's own notification after capture.
 *
 * Suppressing the original notification can make a family miss a school notice entirely if
 * MAMA's copy is wrong, delayed, or filtered out, so it stays disabled until instrumentation
 * coverage and physical-device evidence show the replacement surfacing path is reliable.
 * All suppression must pass through [mayHideOriginal] so the gate cannot be bypassed
 * accidentally.
 */
object NotificationHidingPolicy {
    private const val ENABLED = false

    fun mayHideOriginal(): Boolean = ENABLED
}
