package kr.mom.probe.connector

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kr.mom.probe.data.ProbeRepository

/** Checks cookie presence only; it never treats cookies as data-read success. */
class WebsiteHealthWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val probe = ProbeRepository.get(applicationContext)
        val ready = withTimeoutOrNull(5_000) { probe.isReady.first { it } } ?: return Result.retry()
        if (!ready || probe.lastError.value != null) return Result.retry()
        if (!probe.settings.value.consent) return Result.success()
        val connectors = ConnectorRepository.get(applicationContext)
        var saved = true
        connectors.state.value.sites.values
            .filter { it.id == "ealimi-web" || it.id == "hiclass-web" }
            .filter { it.status == ConnectionStatus.SESSION_READY || it.status == ConnectionStatus.CONNECTED }
            .forEach { connection ->
                val definition = ConnectorCatalog.site(connection.id) ?: return@forEach
                val updated = if (!WebsiteSessionManager.hasStoredSession(definition)) connectors.requireReauth(connection.id)
                    else connectors.markSessionChecked(connection.id)
                if (!updated) saved = false
            }
        return if (saved) Result.success() else Result.retry()
    }
}
