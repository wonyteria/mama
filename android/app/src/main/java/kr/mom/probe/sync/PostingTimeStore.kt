package kr.mom.probe.sync

import android.content.Context
import java.time.Instant
import java.time.ZoneId
import org.json.JSONObject

/**
 * Learns the hour-of-day in which new notices from a web source were first
 * observed. The school board only exposes posting *dates*, so the earliest
 * honest signal is the discovery hour of newly stored items. With the 6-hour
 * baseline poll, that discovery window trails the real posting time; the
 * learned check is scheduled a few hours earlier to catch fresh posts sooner.
 */
object PostingTimeStore {
    private const val PREFS = "posting-patterns"
    private const val MIN_OBSERVATIONS = 4
    private const val MIN_DAYS = 2
    private const val LOOKAHEAD_HOURS = 3
    private val seoul: ZoneId = ZoneId.of("Asia/Seoul")

    fun recordDiscoveries(context: Context, sourceId: String, observedAtMillis: List<Long>) {
        if (observedAtMillis.isEmpty()) return
        val prefs = prefs(context)
        val root = read(prefs, sourceId)
        val hours = root.optJSONObject("hours") ?: JSONObject()
        val days = mutableSetOf<String>()
        root.optJSONArray("days")?.let { array ->
            for (i in 0 until array.length()) days += array.getString(i)
        }
        observedAtMillis.forEach { at ->
            val zoned = Instant.ofEpochMilli(at).atZone(seoul)
            val hour = zoned.hour.toString()
            hours.put(hour, hours.optInt(hour, 0) + 1)
            days += zoned.toLocalDate().toString()
        }
        root.put("hours", hours)
        root.put("days", org.json.JSONArray(days.toList()))
        root.put("updatedAt", System.currentTimeMillis())
        prefs.edit().putString(key(sourceId), root.toString()).apply()
    }

    /**
     * The hour at which an extra daily check should run, or null while the
     * pattern is not yet learned. Derived from the modal discovery hour minus
     * a lookahead that approximates the baseline polling lag.
     */
    fun learnedCheckHour(context: Context, sourceId: String): Int? {
        val root = read(prefs(context), sourceId)
        val hours = root.optJSONObject("hours") ?: return null
        val days = root.optJSONArray("days") ?: return null
        if (days.length() < MIN_DAYS) return null
        var bestHour = -1
        var bestCount = 0
        var total = 0
        val keys = hours.keys()
        while (keys.hasNext()) {
            val hourKey = keys.next()
            val count = hours.optInt(hourKey, 0)
            total += count
            if (count > bestCount) {
                bestCount = count
                bestHour = hourKey.toIntOrNull() ?: -1
            }
        }
        if (total < MIN_OBSERVATIONS || bestHour !in 0..23) return null
        return (bestHour - LOOKAHEAD_HOURS + 24) % 24
    }

    /** Short Korean hint for UI, e.g. "주로 오후 3시쯤 새 소식이 발견돼요". */
    fun describeLearnedWindow(context: Context, sourceId: String): String? {
        val checkHour = learnedCheckHour(context, sourceId) ?: return null
        val discoveredHour = (checkHour + LOOKAHEAD_HOURS) % 24
        val period = if (discoveredHour < 12) "오전" else "오후"
        val display = discoveredHour % 12
        return "주로 $period ${display}시쯤 새 소식이 발견돼요"
    }

    fun reset(context: Context, sourceId: String) {
        prefs(context).edit().remove(key(sourceId)).apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun key(sourceId: String) = "source:$sourceId"

    private fun read(prefs: android.content.SharedPreferences, sourceId: String): JSONObject =
        runCatching { JSONObject(prefs.getString(key(sourceId), null) ?: "{}") }.getOrDefault(JSONObject())
}
