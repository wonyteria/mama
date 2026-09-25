package kr.mom.probe.connector

import android.content.Context
import android.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kr.mom.probe.data.ProbeCrypto
import org.json.JSONArray
import org.json.JSONObject

class ConnectorRepository private constructor(context: Context) {
    private val app = context.applicationContext
    private val preferences = app.getSharedPreferences("mom-connectors", Context.MODE_PRIVATE)
    private val crypto = ProbeCrypto()
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow(load())
    val state: StateFlow<ConnectorState> = mutableState.asStateFlow()

    suspend fun saveChild(name: String, schoolName: String, grade: Int?): Boolean = mutex.withLock {
        val cleanName = name.trim()
        val cleanSchool = schoolName.trim()
        if (cleanName.isEmpty() || cleanName.length > 20 || cleanName.any { it.isISOControl() }) return false
        if (cleanSchool.length > 60 || cleanSchool.any { it.isISOControl() }) return false
        if (grade != null && grade !in 1..6) return false
        persist(mutableState.value.copy(child = ChildProfile(name = cleanName, schoolName = cleanSchool, grade = grade)))
    }

    suspend fun markConnecting(siteId: String): Boolean = updateSite(siteId) { current ->
        current.copy(status = ConnectionStatus.CONNECTING, lastCheckedAt = System.currentTimeMillis())
    }

    suspend fun markObserved(siteId: String, observedUrl: String, likelySignedIn: Boolean): Boolean = updateSite(siteId) { current ->
        current.copy(
            status = if (likelySignedIn) ConnectionStatus.SESSION_READY else current.status,
            connectedAt = if (likelySignedIn) current.connectedAt ?: System.currentTimeMillis() else current.connectedAt,
            lastCheckedAt = System.currentTimeMillis(),
            lastObservedUrl = observedUrl,
        )
    }

    suspend fun markConnected(siteId: String, metadata: Map<String, String>): Boolean = updateSite(siteId) { current ->
        current.copy(
            status = ConnectionStatus.CONNECTED,
            connectedAt = current.connectedAt ?: System.currentTimeMillis(),
            lastCheckedAt = System.currentTimeMillis(),
            metadata = metadata,
        )
    }

    suspend fun requireReauth(siteId: String): Boolean = updateSite(siteId) { it.copy(status = ConnectionStatus.REAUTH_REQUIRED, lastCheckedAt = System.currentTimeMillis()) }

    suspend fun markSessionChecked(siteId: String): Boolean = updateSite(siteId) { it.copy(lastCheckedAt = System.currentTimeMillis()) }

    suspend fun disconnect(siteId: String): Boolean = mutex.withLock {
        val updated = mutableState.value.copy(sites = mutableState.value.sites - siteId)
        persist(updated)
    }

    suspend fun deleteAll(): Boolean = mutex.withLock {
        val cleared = preferences.edit().clear().commit()
        mutableState.value = ConnectorState()
        cleared
    }

    suspend fun disconnectWebsites(): Boolean = mutex.withLock {
        persist(mutableState.value.copy(sites = mutableState.value.sites.filterKeys { it == "neis-public" }))
    }

    private suspend fun updateSite(siteId: String, transform: (SiteConnection) -> SiteConnection): Boolean = mutex.withLock {
        val definition = ConnectorCatalog.site(siteId) ?: return false
        if (!definition.available) return false
        val current = mutableState.value.sites[siteId] ?: SiteConnection(siteId, mutableState.value.child.id, ConnectionStatus.DISCONNECTED)
        persist(mutableState.value.copy(sites = mutableState.value.sites + (siteId to transform(current))))
    }

    private fun persist(value: ConnectorState): Boolean = runCatching {
        val json = JSONObject().apply {
            put("version", 1)
            put("child", JSONObject().apply {
                put("id", value.child.id); put("name", value.child.name); put("schoolName", value.child.schoolName)
                put("grade", value.child.grade ?: JSONObject.NULL)
            })
            put("sites", JSONArray(value.sites.values.map { site -> JSONObject().apply {
                put("id", site.id); put("childId", site.childId); put("status", site.status.name)
                put("connectedAt", site.connectedAt ?: JSONObject.NULL); put("lastCheckedAt", site.lastCheckedAt ?: JSONObject.NULL)
                put("lastObservedUrl", site.lastObservedUrl ?: JSONObject.NULL)
                put("metadata", JSONObject(site.metadata))
            } }))
        }
        val encrypted = Base64.encodeToString(crypto.encrypt(json.toString(), "connectors"), Base64.NO_WRAP)
        check(preferences.edit().putString("state", encrypted).commit())
        mutableState.value = value
        true
    }.getOrDefault(false)

    private fun load(): ConnectorState = runCatching {
        val encoded = preferences.getString("state", null) ?: return ConnectorState()
        val json = JSONObject(crypto.decrypt(Base64.decode(encoded, Base64.NO_WRAP), "connectors"))
        val childJson = json.getJSONObject("child")
        val child = ChildProfile(
            id = childJson.optString("id", "primary-child"),
            name = childJson.optString("name"),
            schoolName = childJson.optString("schoolName"),
            grade = if (childJson.isNull("grade")) null else childJson.optInt("grade"),
        )
        val siteArray = json.optJSONArray("sites") ?: JSONArray()
        val sites = buildMap {
            for (index in 0 until siteArray.length()) {
                val item = siteArray.getJSONObject(index)
                val id = item.getString("id")
                put(id, SiteConnection(
                    id = id, childId = item.optString("childId", child.id),
                    status = runCatching { ConnectionStatus.valueOf(item.optString("status")) }.getOrDefault(ConnectionStatus.DISCONNECTED),
                    connectedAt = item.optLongOrNull("connectedAt"), lastCheckedAt = item.optLongOrNull("lastCheckedAt"),
                    lastObservedUrl = if (item.isNull("lastObservedUrl")) null else item.optString("lastObservedUrl"),
                    metadata = item.optJSONObject("metadata")?.let { objectJson ->
                        objectJson.keys().asSequence().associateWith { objectJson.optString(it) }
                    }.orEmpty(),
                ))
            }
        }
        ConnectorState(child, sites)
    }.getOrDefault(ConnectorState())

    companion object {
        @Volatile private var instance: ConnectorRepository? = null
        fun get(context: Context): ConnectorRepository = instance ?: synchronized(this) {
            instance ?: ConnectorRepository(context).also { instance = it }
        }
    }
}

private fun JSONObject.optLongOrNull(key: String): Long? = if (isNull(key) || !has(key)) null else optLong(key)
