package kr.mom.probe.data

import android.content.Context

/**
 * Plaintext in-progress flag for the full delete-all reset. The shared
 * encryption key is destroyed mid-reset, so this marker lives outside every
 * encrypted store and survives a crash between key destruction and the last
 * preference cleanup. While it is set, startup replays the cleanup chain and
 * re-enrollment must refuse to proceed — an encrypted task or connector
 * remnant must never meet a fresh key under a half-wiped state.
 */
object ResetMarker {
    private const val PREFS = "mama_reset_marker"
    private const val PENDING = "pending"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isPending(context: Context): Boolean =
        prefs(context).getBoolean(PENDING, false)

    fun begin(context: Context) {
        prefs(context).edit().putBoolean(PENDING, true).commit()
    }

    fun finish(context: Context) {
        prefs(context).edit().putBoolean(PENDING, false).commit()
    }
}
