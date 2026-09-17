package kr.mom.probe.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kr.mom.probe.data.ProbeExporter
import kr.mom.probe.data.ProbeRecord
import kr.mom.probe.data.NotificationCandidateParser
import org.json.JSONObject

/** Research-only review. The ordinary onboarding never asks the parent to edit JSON. */
@Composable
fun ExportScreen(records: List<ProbeRecord>, childName: String, busy: Boolean, onBack: () -> Unit,
                 onSave: (String, String, Set<String>) -> Unit) {
    var excluded by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var terms by rememberSaveable { mutableStateOf("") }
    var consent by rememberSaveable { mutableStateOf(false) }
    var format by rememberSaveable { mutableStateOf("json") }
    var days by rememberSaveable { mutableIntStateOf(14) }
    var appFilter by rememberSaveable { mutableStateOf("") }
    val selected = records.filter { it.id !in excluded && (appFilter.isEmpty() || it.packageName == appFilter) && System.currentTimeMillis() - it.receivedAt <= days * 86_400_000L }
    val phrases = terms.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.distinct().toList()
    val reviewed = remember(selected, childName, phrases) {
        val json = JSONObject(ProbeExporter.exportJson(selected, childName))
        val rows = json.getJSONArray("records")
        for (i in 0 until rows.length()) {
            val row = rows.getJSONObject(i)
            val record = selected[i]
            val candidate = NotificationCandidateParser.parse(record)
            row.put("candidateDetected", candidate != null)
            row.put("candidateKind", candidate?.kind?.name ?: "")
            row.put("candidateDueAt", candidate?.dueAt ?: JSONObject.NULL)
            row.keys().asSequence().toList().forEach { key ->
                if (key !in setOf("recordAlias", "postedAt", "receivedAt", "truncated")) {
                    var value = row.optString(key)
                    phrases.forEach { value = value.replace(it, "[추가 가림]") }
                    row.put(key, value)
                }
            }
        }
        json
    }
    Page {
        BackHeading("연구자료 검토", onBack)
        Text("보내기 전에,\n한 번 더 확인해요.", style = MaterialTheme.typography.headlineMedium)
        Text("이름·전화번호 등을 먼저 가렸어요. 자동 가림이 놓친 다른 사람 이름·학교·주소가 없는지 확인해주세요.", color = Clay.Muted)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(days == 1, { days = 1; consent = false }, label = { Text("최근 24시간") })
            FilterChip(days == 7, { days = 7; consent = false }, label = { Text("7일") })
            FilterChip(days == 14, { days = 14; consent = false }, label = { Text("전체") })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(appFilter.isEmpty(), { appFilter = ""; consent = false }, label = { Text("모든 앱") })
            records.map { it.packageName }.distinct().forEach { pkg ->
                FilterChip(appFilter == pkg, { appFilter = pkg; consent = false }, label = { Text(SourceCatalog.label(pkg).take(7)) })
            }
        }
        OutlinedTextField(terms, { terms = it; consent = false }, Modifier.fillMaxWidth(),
            label = { Text("더 가릴 이름이나 문구") }, placeholder = { Text("한 줄에 하나씩 입력해주세요") }, minLines = 2,
            supportingText = { Text("입력한 문구는 아래 자료의 모든 본문에서 가려요.") })
        Text("검토할 알림 ${selected.size}개", style = MaterialTheme.typography.titleMedium)
        if (selected.isEmpty()) EmptyCard("선택된 알림이 없어요", "기간을 바꾸거나 제외한 알림을 다시 포함해주세요.")
        val filtered = records.filter { (appFilter.isEmpty() || it.packageName == appFilter) && System.currentTimeMillis() - it.receivedAt <= days * 86_400_000L }
        filtered.forEach { record ->
            val included = record.id !in excluded
            val index = selected.indexOfFirst { it.id == record.id }
            var expanded by remember(record.id) { mutableStateOf(false) }
            ClayCard(tint = if (included) Clay.Sage else Clay.Background) {
                ConsentRow(included, { include -> excluded = if (include) excluded - record.id else (excluded + record.id).distinct(); consent = false }, "${record.appLabel} · ${displayTime(record.receivedAt)}")
                if (index >= 0) {
                    val row = reviewed.getJSONArray("records").getJSONObject(index)
                    SelectionContainer { Text(row.optString("title").ifBlank { "제목 없음" }, style = MaterialTheme.typography.titleMedium) }
                    SelectionContainer { Text(row.optString("bigText").ifBlank { row.optString("text") }.ifBlank { row.optString("textLines") }.ifBlank { "본문 없음" }, color = Clay.Muted) }
                    TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "전체 필드 접기" else "저장될 모든 내용 확인") }
                    if (expanded) SelectionContainer { Text(row.toString(2), style = MaterialTheme.typography.bodySmall) }
                } else Text("저장할 자료에서 제외했어요.", color = Clay.Muted)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FilterChip(format == "json", { format = "json"; consent = false }, label = { Text("JSON") })
            FilterChip(format == "csv", { format = "csv"; consent = false }, label = { Text("CSV") })
        }
        Text("가명 처리된 연구자료이며 익명화를 보장하지 않아요. 외부에 저장한 사본은 이 앱에서 삭제할 수 없어요.", style = MaterialTheme.typography.bodySmall, color = Clay.Muted)
        ConsentRow(consent, { consent = it }, "자료를 검토했고, 선택한 위치에 파일로 저장하는 데 동의해요.")
        ClayButton("검토한 자료 저장", enabled = consent && selected.isNotEmpty() && !busy) {
            val payload = if (format == "json") reviewed.toString(2) else reviewedCsv(reviewed)
            onSave(payload, format, selected.map { it.id }.toSet())
        }
    }
}

internal fun reviewedCsv(json: JSONObject): String {
    val rows = json.getJSONArray("records")
    if (rows.length() == 0) return ""
    val keys = rows.getJSONObject(0).keys().asSequence().toList().sorted()
    return "\uFEFF" + (listOf(keys.joinToString(",") { ProbeExporter.csvCell(it) }) + (0 until rows.length()).map { index ->
        keys.joinToString(",") { key -> ProbeExporter.csvCell(rows.getJSONObject(index).optString(key)) }
    }).joinToString("\r\n") + "\r\n"
}
