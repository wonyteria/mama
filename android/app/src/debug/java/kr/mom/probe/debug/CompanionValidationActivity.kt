package kr.mom.probe.debug

import android.os.Bundle
import android.webkit.CookieManager
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kr.mom.probe.data.ProbeRepository
import kr.mom.probe.data.SchoolLevel
import kr.mom.probe.task.AssistantTaskStore
import kr.mom.probe.connector.WebsiteSessionManager

/** Synthetic checks only in the separate QA application; never in the distributable build. */
class CompanionValidationActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        check(packageName.endsWith(".qa"))
        val output = TextView(this).apply { textSize = 20f; text = "QA running"; setPadding(40, 120, 40, 40) }
        setContentView(output)
        lifecycleScope.launch {
            try {
                val repo = ProbeRepository.get(this@CompanionValidationActivity)
                check(repo.acceptConsent() && repo.saveChild("QA", "TestElementary", 2, SchoolLevel.ELEMENTARY) && repo.deferSetup())
                if (intent.getBooleanExtra("alarm", false)) {
                    withContext(Dispatchers.IO) {
                        AssistantTaskStore.reset(this@CompanionValidationActivity)
                        val store = AssistantTaskStore.get(this@CompanionValidationActivity)
                        store.load()
                        val now = System.currentTimeMillis()
                        check(store.add("QA_PRIVATE_REMINDER", dueAt = now + 60_000, remindAt = now + 3_000))
                    }
                    output.text = "PASS: task reminder scheduled"
                    return@launch
                }
                withContext(Dispatchers.IO) {
                    AssistantTaskStore.reset(this@CompanionValidationActivity)
                    val store = AssistantTaskStore.get(this@CompanionValidationActivity)
                    store.load()
                    store.add("QA_PRIVATE_TASK_913")
                    val id = store.tasks.value.single().id
                    check(!getSharedPreferences("assistant_tasks", MODE_PRIVATE).getString("encrypted", "")!!.contains("QA_PRIVATE_TASK_913"))
                    store.setCompleted(id, true); check(store.tasks.value.single().completed)
                    store.load(); check(store.tasks.value.single().completed)
                    store.setCompleted(id, false); check(!store.tasks.value.single().completed)
                    store.delete(id); check(store.tasks.value.isEmpty())
                }
                val cookies = CookieManager.getInstance()
                suspendCancellableCoroutine<Unit> { continuation ->
                    cookies.setCookie("https://www.ealimi.com/private/", "QA_COOKIE=yes; Path=/private; Secure") {
                        continuation.resume(Unit)
                    }
                }
                check(cookies.getCookie("https://www.ealimi.com/private/").orEmpty().contains("QA_COOKIE"))
                check(WebsiteSessionManager.clearAll())
                check(!cookies.getCookie("https://www.ealimi.com/private/").orEmpty().contains("QA_COOKIE"))
                check(repo.beginReset())
                withContext(Dispatchers.IO) {
                    check(runCatching { AssistantTaskStore.get(this@CompanionValidationActivity).add("blocked") }.isFailure)
                    AssistantTaskStore.reset(this@CompanionValidationActivity)
                }
                check(repo.deleteAll())
                output.text = "PASS: add / complete / reload / reopen / delete\nPASS: encrypted payload\nPASS: path cookie deletion\nPASS: revoked consent blocks writes"
            } catch (error: Exception) {
                output.text = "FAIL: ${error.javaClass.simpleName}: ${error.message}"
            }
        }
    }
}
