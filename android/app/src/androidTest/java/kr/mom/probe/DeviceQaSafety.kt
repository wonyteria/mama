package kr.mom.probe

import android.content.Context
import android.os.Build
import kotlinx.coroutines.flow.first
import kr.mom.probe.data.ProbeRepository
import kr.mom.probe.task.AssistantTaskStore
import org.junit.Assume.assumeTrue

/**
 * Gate for destructive QA setup (deleteAll / store reset). A wipe is safe on an
 * emulator or on a QA install that still holds no captured data; on a physical
 * device that already has QA records, tasks, or completed onboarding the wipe
 * would destroy evidence we are not allowed to touch, so the caller must skip
 * honestly instead.
 */
object DeviceQaSafety {
    fun isEmulator(): Boolean = Build.FINGERPRINT.startsWith("generic") ||
        Build.FINGERPRINT.startsWith("unknown") ||
        Build.MODEL.contains("google_sdk") ||
        Build.MODEL.contains("Emulator", ignoreCase = true) ||
        Build.MODEL.contains("sdk_gphone") ||
        Build.HARDWARE.contains("ranchu") ||
        Build.HARDWARE.contains("goldfish") ||
        Build.PRODUCT.contains("sdk")

    /**
     * True only when a full repository reset cannot destroy existing QA data.
     * Reads loaded state, so callers must have awaited repository readiness.
     */
    suspend fun hasDestructibleState(context: Context): Boolean {
        if (isEmulator()) return true
        val repository = ProbeRepository.get(context)
        repository.isReady.first { it }
        val store = AssistantTaskStore.get(context)
        store.load()
        return repository.records.value.isEmpty() &&
            store.tasks.value.isEmpty() &&
            !repository.settings.value.onboardingDone
    }

    /**
     * Skips the test on a physical device that still holds QA data, with the
     * reason recorded in the test XML instead of silently wiping it.
     */
    suspend fun requireDestructibleState(context: Context, testName: String) {
        assumeTrue(
            "$testName needs a destructive repository reset; skipping because this " +
                "physical device already holds QA records/tasks/onboarding state. " +
                "Run on an emulator or a fresh QA install.",
            hasDestructibleState(context),
        )
    }
}
