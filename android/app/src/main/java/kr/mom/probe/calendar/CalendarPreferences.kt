package kr.mom.probe.calendar

import android.content.Context
import android.util.Base64
import java.io.Serializable
import java.io.IOException
import kr.mom.probe.agent.ScheduleCommandParser
import kr.mom.probe.data.ProbeCrypto
import org.json.JSONObject

data class CalendarDestination(
    val calendarId: Long,
    val displayName: String,
    val accountName: String,
    val accountType: String,
    val ownerAccount: String,
) : Serializable

data class CalendarAppHandler(
    val packageName: String,
    val activityName: String,
    val label: String,
) : Serializable

interface CalendarPreferencesPort {
    fun selectedCalendar(): CalendarDestination?
    fun saveSelectedCalendar(destination: CalendarDestination): Boolean
    fun defaultDurationMinutes(): Int
    fun saveDefaultDurationMinutes(minutes: Int): Boolean
}

class CalendarPreferences private constructor(context: Context) : CalendarPreferencesPort {
    private val app = context.applicationContext
    private val preferences = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized
    override fun selectedCalendar(): CalendarDestination? {
        val encoded = preferences.getString(KEY_CALENDAR, null) ?: return null
        return runCatching {
            decodeDestination(ProbeCrypto().decrypt(Base64.decode(encoded, Base64.NO_WRAP), KEY_CALENDAR))
        }.getOrNull()
    }

    @Synchronized
    override fun saveSelectedCalendar(destination: CalendarDestination): Boolean {
        require(destination.calendarId > 0L) { "저장할 캘린더를 다시 선택해주세요." }
        val raw = encodeDestination(destination)
        val encoded = Base64.encodeToString(ProbeCrypto().encrypt(raw, KEY_CALENDAR), Base64.NO_WRAP)
        return preferences.edit().putString(KEY_CALENDAR, encoded).commit()
    }

    @Synchronized
    override fun defaultDurationMinutes(): Int =
        preferences.getInt(KEY_DURATION, ScheduleCommandParser.DEFAULT_DURATION_MINUTES).coerceIn(15, 24 * 60)

    @Synchronized
    override fun saveDefaultDurationMinutes(minutes: Int): Boolean {
        require(minutes in 15..(24 * 60)) { "기본 일정 길이는 15분~24시간으로 정해주세요." }
        return preferences.edit().putInt(KEY_DURATION, minutes).commit()
    }

    @Synchronized
    fun clear(): Boolean = preferences.edit().clear().commit()

    companion object {
        private const val PREFS = "calendar-preferences"
        private const val KEY_CALENDAR = "selected-calendar"
        private const val KEY_DURATION = "default-duration"
        @Volatile private var instance: CalendarPreferences? = null
        fun get(context: Context): CalendarPreferences = instance ?: synchronized(this) {
            instance ?: CalendarPreferences(context).also { instance = it }
        }
        fun reset(context: Context): Boolean = get(context).clear()
    }
}

internal fun encodeDestination(value: CalendarDestination): String = JSONObject()
    .put("calendarId", value.calendarId)
    .put("displayName", value.displayName)
    .put("accountName", value.accountName)
    .put("accountType", value.accountType)
    .put("ownerAccount", value.ownerAccount)
    .toString()

internal fun decodeDestination(raw: String): CalendarDestination {
    val json = JSONObject(raw)
    return CalendarDestination(
        calendarId = json.getLong("calendarId"),
        displayName = json.getString("displayName"),
        accountName = json.getString("accountName"),
        accountType = json.getString("accountType"),
        ownerAccount = json.optString("ownerAccount"),
    )
}

internal fun requireSaved(saved: Boolean) {
    if (!saved) throw IOException("캘린더 설정을 저장하지 못했어요.")
}

class CalendarAppPreferences private constructor(context: Context) {
    private val app = context.applicationContext
    private val preferences = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized
    fun selectedHandler(): CalendarAppHandler? {
        val encoded = preferences.getString(KEY_HANDLER, null) ?: return null
        return runCatching {
            val json = JSONObject(ProbeCrypto().decrypt(Base64.decode(encoded, Base64.NO_WRAP), KEY_HANDLER))
            CalendarAppHandler(json.getString("packageName"), json.getString("activityName"), json.getString("label"))
        }.getOrNull()
    }

    @Synchronized
    fun saveSelectedHandler(handler: CalendarAppHandler): Boolean {
        val raw = JSONObject()
            .put("packageName", handler.packageName)
            .put("activityName", handler.activityName)
            .put("label", handler.label)
            .toString()
        val encoded = Base64.encodeToString(ProbeCrypto().encrypt(raw, KEY_HANDLER), Base64.NO_WRAP)
        return preferences.edit().putString(KEY_HANDLER, encoded).commit()
    }

    @Synchronized
    fun clear(): Boolean = preferences.edit().clear().commit()

    companion object {
        private const val PREFS = "calendar-app-preferences"
        private const val KEY_HANDLER = "selected-insert-handler"
        @Volatile private var instance: CalendarAppPreferences? = null
        fun get(context: Context): CalendarAppPreferences = instance ?: synchronized(this) {
            instance ?: CalendarAppPreferences(context).also { instance = it }
        }
        fun reset(context: Context): Boolean = get(context).clear()
    }
}
