package kr.mom.probe.reminder

internal class PendingUnlockAction {
    var pending: Boolean = false
        private set

    fun begin() {
        pending = true
    }

    fun cancel() {
        pending = false
    }

    fun runIfUnlocked(isUnlocked: () -> Boolean, action: () -> Unit): Boolean {
        if (!pending || !isUnlocked()) return false
        pending = false
        action()
        return true
    }
}
