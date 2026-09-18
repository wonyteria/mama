package kr.mom.probe

import android.app.Application
import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kr.mom.probe.data.ProbeRepository
import kr.mom.probe.connector.EalimiNoticeClient
import kr.mom.probe.connector.PublicSourceFetchers
import kr.mom.probe.connector.WebsiteHealthWorker
import kr.mom.probe.sync.SourceFetcherRegistry
import kr.mom.probe.sync.SourceIds
import kr.mom.probe.sync.SourceSyncScheduler

class ProbeApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        val migrations = getSharedPreferences("mom-migrations", MODE_PRIVATE)
        if (!migrations.getBoolean("removed-candidate-feedback-v1", false)) {
            val removed = getSharedPreferences("candidate_feedback", MODE_PRIVATE).edit().clear().commit()
            if (removed) migrations.edit().putBoolean("removed-candidate-feedback-v1", true).commit()
        }
        if (BuildConfig.DEBUG) android.webkit.WebView.setWebContentsDebuggingEnabled(true)
        PublicSourceFetchers.registerDefaults()
        SourceFetcherRegistry.register(SourceIds.EALIMI_WEB, EalimiNoticeClient(this))
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "expire-probe-records", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<ExpiryWorker>(1, TimeUnit.DAYS).build()
        )
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "website-session-health", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<WebsiteHealthWorker>(12, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
        )
        SourceSyncScheduler.schedulePeriodic(this)
        // Learned posting windows need loaded settings; schedulePeriodic runs
        // before the repository is ready, so re-run once startup completes.
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            ProbeRepository.get(this@ProbeApplication).isReady.first { it }
            SourceSyncScheduler.scheduleLearnedWindows(this@ProbeApplication)
        }
    }
}

class ExpiryWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = try {
        if (ProbeRepository.get(applicationContext).cleanupExpired()) Result.success() else Result.retry()
    } catch (_: Exception) { Result.retry() }
}
