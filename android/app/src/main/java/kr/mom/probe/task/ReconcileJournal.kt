package kr.mom.probe.task

import android.content.Context
import android.content.SharedPreferences
import java.io.IOException

/**
 * Durable journal for the record-commit to task-reconcile gap. A captured
 * notice is persisted before its automatic task is saved; if the process dies
 * or the save fails in between, the pending source id survives and is replayed
 * until reconciliation completes once, logically. Keys explicitly retired by
 * the user are remembered so replay never resurrects a deleted automatic task.
 * Entries are opaque group ids only, so this store needs no encryption.
 *
 * Every write uses [SharedPreferences.Editor.commit] rather than apply(): the
 * journal's promise is durability before the caller proceeds, so an async
 * write could still lose the entry it exists to preserve. A failed commit
 * throws and propagates to the caller's error path instead of being swallowed.
 */
object ReconcileJournal {
    private const val PREFS = "mama_task_reconcile"
    private const val PENDING = "pending"
    private const val RETIRED = "retired"

    /** Test seam: injects commit failures for durability regressions. */
    @Volatile
    internal var committer: (SharedPreferences.Editor) -> Boolean = { it.commit() }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun write(context: Context, mutate: SharedPreferences.Editor.() -> Unit) {
        val editor = prefs(context).edit().apply(mutate)
        if (!committer(editor)) throw IOException("재처리 기록을 저장하지 못했어요.")
    }

    fun pending(context: Context): Set<String> =
        prefs(context).getStringSet(PENDING, emptySet()).orEmpty().toSet()

    fun markPending(context: Context, sourceNotificationId: String) {
        write(context) { putStringSet(PENDING, pending(context) + sourceNotificationId) }
    }

    fun clearPending(context: Context, sourceNotificationId: String) {
        write(context) { putStringSet(PENDING, pending(context) - sourceNotificationId) }
    }

    fun retiredKeys(context: Context): Set<String> =
        prefs(context).getStringSet(RETIRED, emptySet()).orEmpty().toSet()

    fun retire(context: Context, keys: Set<String>) {
        if (keys.isEmpty()) return
        write(context) { putStringSet(RETIRED, retiredKeys(context) + keys) }
    }

    fun reset(context: Context) {
        write(context) { clear() }
    }
}
