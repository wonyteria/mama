package kr.mom.probe.task

import android.content.Context
import android.content.SharedPreferences
import java.io.IOException

/**
 * Durable journal for the record-commit to task-reconcile gap. The pending
 * marker is written BEFORE the record row commits, so a failed commit leaves
 * neither record nor marker behind and a crash between the mark and the row
 * insert leaves only a ghost entry that startup replay drops because its
 * record is missing. If the process dies or the task save fails after the
 * record commits, the pending source id survives and is replayed until
 * reconciliation completes once, logically. Keys explicitly retired by the
 * user are remembered so replay never resurrects a deleted automatic task.
 * Entries are opaque group ids only, so this store needs no encryption.
 *
 * Every write uses [SharedPreferences.Editor.commit] rather than apply(): the
 * journal's promise is durability before the caller proceeds, so an async
 * write could still lose the entry it exists to preserve. A failed commit
 * throws and propagates to the caller's error path instead of being swallowed.
 *
 * Each read-modify-write runs under [lock]: capture, ingest, replay and user
 * delete can all touch the same sets from different threads without the
 * repository's action mutex, and an unguarded get-then-put would drop
 * concurrent entries.
 */
object ReconcileJournal {
    private const val PREFS = "mama_task_reconcile"
    private const val PENDING = "pending"
    private const val RETIRED = "retired"

    private val lock = Any()

    /** Test seam: injects commit failures for durability regressions. */
    @Volatile
    internal var committer: (SharedPreferences.Editor) -> Boolean = { it.commit() }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun write(context: Context, mutate: SharedPreferences.(SharedPreferences.Editor) -> Unit) {
        synchronized(lock) {
            val prefs = prefs(context)
            val editor = prefs.edit()
            prefs.mutate(editor)
            if (!committer(editor)) throw IOException("재처리 기록을 저장하지 못했어요.")
        }
    }

    fun pending(context: Context): Set<String> =
        prefs(context).getStringSet(PENDING, emptySet()).orEmpty().toSet()

    fun markPending(context: Context, sourceNotificationId: String) {
        write(context) { editor ->
            editor.putStringSet(PENDING, getStringSet(PENDING, emptySet()).orEmpty() + sourceNotificationId)
        }
    }

    fun clearPending(context: Context, sourceNotificationId: String) {
        write(context) { editor ->
            editor.putStringSet(PENDING, getStringSet(PENDING, emptySet()).orEmpty() - sourceNotificationId)
        }
    }

    fun retiredKeys(context: Context): Set<String> =
        prefs(context).getStringSet(RETIRED, emptySet()).orEmpty().toSet()

    fun retire(context: Context, keys: Set<String>) {
        if (keys.isEmpty()) return
        write(context) { editor ->
            editor.putStringSet(RETIRED, getStringSet(RETIRED, emptySet()).orEmpty() + keys)
        }
    }

    fun reset(context: Context) {
        write(context) { editor -> editor.clear() }
    }
}
