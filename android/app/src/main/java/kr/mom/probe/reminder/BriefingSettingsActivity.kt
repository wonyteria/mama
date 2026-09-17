package kr.mom.probe.reminder

import android.Manifest
import android.app.TimePickerDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kr.mom.probe.ui.MomTheme
import kr.mom.probe.ui.Clay
import kr.mom.probe.ui.ClayCard
import kr.mom.probe.ui.flatSurface
import kr.mom.probe.data.ProbeRepository
import kr.mom.probe.data.ProbeRules

class BriefingSettingsActivity : ComponentActivity() {
    private var lifecycleRevision by mutableIntStateOf(0)
    private var pendingEnableSlot: Int? = null
    override fun onResume() { super.onResume(); lifecycleRevision++ }
    private fun save(slot: Int, value: BriefingTime): Boolean {
        val saved = BriefingReminders.save(this, slot, value)
        if (!saved) Toast.makeText(this, "저장하지 못했어요. 다시 시도해 주세요.", Toast.LENGTH_LONG).show()
        return saved
    }
    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
        val slot = pendingEnableSlot
        pendingEnableSlot = null
        if (allowed && slot != null) {
            save(slot, BriefingReminders.read(this, slot).copy(enabled = true))
            lifecycleRevision++
        }
        Toast.makeText(this, if (allowed) "알림을 사용할 수 있어요" else "휴대폰 설정에서 알림을 허용해 주세요", Toast.LENGTH_SHORT).show()
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MomTheme {
                val repository = remember { ProbeRepository.get(this) }
                val ready by repository.isReady.collectAsState()
                val settings by repository.settings.collectAsState()
                var revision by remember { mutableIntStateOf(0) }
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.safeDrawingPadding().padding(24.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        TextButton(onClick = { finish() }) { Text("뒤로") }
                        Text("비서 알림", style = MaterialTheme.typography.headlineMedium)
                        Text("남은 부탁이나 새로 모인 알림이 있을 때 알려드려요. 소리로 읽기는 브리핑에서 직접 눌러주세요.")
                        if (!ready || !settings.consent || settings.consentVersion != ProbeRules.CONSENT_VERSION || !settings.onboardingDone) Text("먼저 앱의 첫 설정을 마쳐주세요.")
                        else {
                            val alarmReady = remember(lifecycleRevision, revision) { BriefingReminders.alarmPermissions(this@BriefingSettingsActivity) }
                            val alarmEnabled = remember(revision) { BriefingReminders.alarmMode(this@BriefingSettingsActivity) }
                            ClayCard(Modifier.fillMaxWidth(), tint = Clay.Peach) {
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        Text("전화처럼 알려주기", style = MaterialTheme.typography.titleMedium)
                                        Switch(checked = alarmEnabled, enabled = alarmReady || alarmEnabled, onCheckedChange = {
                                            if (BriefingReminders.saveAlarmMode(this@BriefingSettingsActivity, it)) revision++
                                            else Toast.makeText(this@BriefingSettingsActivity, "권한을 확인하고 다시 시도해 주세요", Toast.LENGTH_LONG).show()
                                        })
                                    }
                                    Text("정한 시각에 모모가 화면과 알람 소리로 찾아와요. 챙길 일이 없으면 울리지 않고 30초 뒤 멈춰요.")
                                    if (!alarmReady) Text("처음 한 번, 아래 두 설정을 허용해주세요. 휴대폰에 따라 상단 알림으로 보일 수 있어요.", style = MaterialTheme.typography.bodySmall)
                                    OutlinedButton(onClick = {
                                        startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName")))
                                    }) { Text("시간 맞춰 울리기 허용") }
                                    if (Build.VERSION.SDK_INT >= 34) OutlinedButton(onClick = {
                                        startActivity(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:$packageName")))
                                    }) { Text("잠금화면에 띄우기 허용") }
                            }
                            listOf(0, 2, 1).forEach { slot ->
                                val value = remember(revision, lifecycleRevision) { BriefingReminders.read(this@BriefingSettingsActivity, slot) }
                                Column(Modifier.fillMaxWidth().flatSurface().padding(16.dp)) {
                                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                            Text(when (slot) { 0 -> "아침 브리핑"; 1 -> "저녁 브리핑"; else -> "점심 브리핑" })
                                            Switch(checked = value.enabled, onCheckedChange = {
                                                if (it && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                                                    pendingEnableSlot = slot
                                                    permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                                                } else if (save(slot, value.copy(enabled = it))) revision++
                                            })
                                        }
                                        TextButton(onClick = {
                                            TimePickerDialog(this@BriefingSettingsActivity, { _, hour, minute ->
                                                if (save(slot, value.copy(hour = hour, minute = minute))) revision++
                                            }, value.hour, value.minute, true).show()
                                        }) { Text("%02d:%02d · 시간 바꾸기".format(value.hour, value.minute)) }
                                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                            Text("평일만 알려주기")
                                            Switch(checked = value.weekdaysOnly, onCheckedChange = { if (save(slot, value.copy(weekdaysOnly = it))) revision++ })
                                        }
                                }
                            }
                            Text("휴대폰의 절전 상태에 따라 늦게 도착할 수 있어요. 소리·진동은 휴대폰 알림 설정을 따라요.", style = MaterialTheme.typography.bodySmall)
                            OutlinedButton(onClick = {
                                if (!BriefingReminders.notify(this@BriefingSettingsActivity, 0, 0, demo = true)) {
                                    permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                                    Toast.makeText(this@BriefingSettingsActivity, "휴대폰 설정에서 비서 브리핑 알림을 켠 뒤 다시 눌러주세요", Toast.LENGTH_LONG).show()
                                }
                            }, modifier = Modifier.fillMaxWidth()) { Text("시험 알림 보내기") }
                        }
                    }
                }
            }
        }
    }
}
