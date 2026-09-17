package kr.mom.probe.task

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kr.mom.probe.MainActivity
import kr.mom.probe.BuildConfig
import kr.mom.probe.R
import kr.mom.probe.data.ProbeRepository
import kr.mom.probe.data.ProbeRules
import kr.mom.probe.ui.MomTheme
import kr.mom.probe.ui.Clay
import kr.mom.probe.ui.ClayCard
import kr.mom.probe.ui.displayTime

class AssistantTasksActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (!BuildConfig.DEBUG) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        setContent { MomTheme { AssistantTasks() } }
    }

    @Composable
    private fun AssistantTasks() {
        val repository = remember { ProbeRepository.get(this) }
        val ready by repository.isReady.collectAsStateWithLifecycle()
        val settings by repository.settings.collectAsStateWithLifecycle()
        val repositoryError by repository.lastError.collectAsStateWithLifecycle()
        val allowed = ready && repositoryError == null && settings.consent && settings.onboardingDone &&
            settings.consentVersion == ProbeRules.CONSENT_VERSION
        val store = remember { AssistantTaskStore.get(this) }
        val tasks by store.tasks.collectAsStateWithLifecycle()
        val scope = rememberCoroutineScope()
        var draft by rememberSaveable { mutableStateOf("") }
        var busy by remember { mutableStateOf(false) }
        var loaded by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf<String?>(null) }
        var deleteTarget by remember { mutableStateOf<AssistantTask?>(null) }

        suspend fun operation(action: () -> Unit): Boolean {
            busy = true
            return try {
                withContext(Dispatchers.IO) { action() }
                error = null
                true
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                error = if (failure is IllegalArgumentException || failure is IllegalStateException || failure is java.io.IOException)
                    failure.message else "부탁을 불러오거나 저장하지 못했어요. 다시 시도해주세요."
                false
            } finally { busy = false }
        }

        LaunchedEffect(allowed) {
            loaded = false
            if (allowed) loaded = operation { store.load() }
            else { draft = ""; deleteTarget = null }
        }

        Column(Modifier.fillMaxSize().background(Clay.Background).safeDrawingPadding().imePadding().padding(horizontal = 20.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = { finish() }) { Text("닫기") }
                TextButton(onClick = {
                    startActivity(Intent(this@AssistantTasksActivity, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
                    finish()
                }) { Text("앱 열기") }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Image(painterResource(R.drawable.assistant_widget_momo), contentDescription = null,
                    modifier = Modifier.size(width = 46.dp, height = 62.dp))
                Spacer(Modifier.width(12.dp))
                Text("모모에게 부탁하기", style = MaterialTheme.typography.headlineSmall)
            }
            Spacer(Modifier.height(8.dp))
            if (!ready) {
                CircularProgressIndicator(Modifier.padding(24.dp))
            } else if (!allowed) {
                Text("앱에서 처음 설정을 마치면 부탁을 적어둘 수 있어요.")
            } else {
                Text("챙길 일을 적고, 끝내면 체크해주세요.", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(value = draft, onValueChange = { if (it.length <= AssistantTaskStore.MAX_TEXT) draft = it },
                    modifier = Modifier.fillMaxWidth(), label = { Text("예: 내일 물티슈 준비") }, minLines = 1, maxLines = 3,
                    enabled = !busy, supportingText = { Text("${draft.length}/${AssistantTaskStore.MAX_TEXT} · 시간 자동 설정은 하지 않아요") })
                Button(onClick = { scope.launch { if (operation { store.add(draft) }) draft = "" } },
                    enabled = !busy && loaded && draft.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("부탁 적어두기") }
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error)
                    if (!loaded) TextButton(onClick = { scope.launch { loaded = operation { store.load() } } }, enabled = !busy) { Text("다시 불러오기") }
                }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                val visibleTasks = tasks.filterNot { it.suspended }
                if (loaded && visibleTasks.isEmpty()) Text("아직 적어둔 부탁이 없어요.", Modifier.padding(vertical = 24.dp))
                LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(visibleTasks.sortedBy { it.completed }, key = { it.id }) { task ->
                        ClayCard(Modifier.fillMaxWidth()) {
                                Text(task.text, style = MaterialTheme.typography.bodyLarge)
                                task.dueAt?.let { Text("기한 ${displayTime(it)}", color = Clay.Green, style = MaterialTheme.typography.bodySmall) }
                                task.remindAt?.let { Text("다시 알림 예정 ${displayTime(it)} 무렵", color = Clay.Muted, style = MaterialTheme.typography.bodySmall) }
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    TextButton(onClick = { scope.launch { operation { store.setCompleted(task.id, !task.completed) } } }, enabled = !busy && loaded) {
                                        Text(if (task.completed) "완료됨 · 다시 챙기기" else "준비 완료")
                                    }
                                    TextButton(onClick = { deleteTarget = task }, enabled = !busy) { Text("삭제") }
                                }
                        }
                    }
                }
            }
        }
        deleteTarget?.let { task ->
            AlertDialog(onDismissRequest = { if (!busy) deleteTarget = null }, title = { Text("부탁을 삭제할까요?") },
                text = { Text(task.text) },
                confirmButton = { TextButton(onClick = { scope.launch { if (operation { store.delete(task.id) }) deleteTarget = null } }, enabled = !busy) { Text("삭제") } },
                dismissButton = { TextButton(onClick = { deleteTarget = null }, enabled = !busy) { Text("취소") } })
        }
    }
}
