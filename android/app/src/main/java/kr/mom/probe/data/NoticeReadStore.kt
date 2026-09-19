package kr.mom.probe.data

import android.content.Context

/**
 * Tracks which stored notices the parent has opened. Read state is deliberately
 * separate from task completion: reading a notice, opening the source app, or
 * muting an alert never finishes a Todo.
 */
object NoticeReadStore {
    private const val PREFS = "notice-read"

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isRead(context: Context, recordId: String): Boolean = prefs(context).getBoolean(recordId, false)

    fun readIds(context: Context): Set<String> =
        prefs(context).all.filterValues { it == true }.keys

    fun markRead(context: Context, recordId: String): Boolean =
        prefs(context).edit().putBoolean(recordId, true).commit()

    fun reset(context: Context): Boolean = prefs(context).edit().clear().commit()
}
