package kr.mom.probe.task

import android.content.Context

/**
 * Durable journal for the record-commit to task-reconcile gap. A captured
 * notice is persisted before its automatic task is saved; if the process dies
 * or the save fails in between, the pending source id survives and is replayed
 * until reconciliation completes once, logically. Keys explicitly retired by
 * the user are remembered so replay never resurrects a deleted automatic task.
 * Entries are opaque group ids only, so this store needs no encryption.
 */
object ReconcileJournal {
    private const val PREFS = "mama_task_reconcile"
    private const val PENDING = "pending"
    private const val RETIRED = "retired"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun pending(context: Context): Set<String> =
        prefs(context).getStringSet(PENDING, emptySet()).orEmpty().toSet()

    fun markPending(context: Context, sourceNotificationId: String) {
        prefs(context).edit().putStringSet(PENDING, pending(context) + sourceNotificationId).apply()
    }

    fun clearPending(context: Context, sourceNotificationId: String) {
        prefs(context).edit().putStringSet(PENDING, pending(context) - sourceNotificationId).apply()
    }

    fun retiredKeys(context: Context): Set<String> =
        prefs(context).getStringSet(RETIRED, emptySet()).orEmpty().toSet()

    fun retire(context: Context, keys: Set<String>) {
        if (keys.isEmpty()) return
        prefs(context).edit().putStringSet(RETIRED, retiredKeys(context) + keys).apply()
    }

    fun reset(context: Context) {
        prefs(context).edit().clear().apply()
    }
}
