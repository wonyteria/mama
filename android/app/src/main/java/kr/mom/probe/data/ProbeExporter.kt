package kr.mom.probe.data

/** Local pseudonymization only. The user must review and explicitly consent before sharing. */
object ProbeExporter {
    const val WARNING = "가명 처리된 연구자료입니다. 자동 가림이 놓친 이름·기관·주소 등을 확인해 주세요. 익명화를 보장하지 않습니다."
    private val email = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")
    private val phone = Regex("(?<![0-9])(?:\\+82[ -]?|0)(?:1[016789]|2|[3-6][1-5]|70)[ -]?[0-9]{3,4}[ -]?[0-9]{4}(?![0-9])")
    private val longNumber = Regex("(?<![0-9])(?:[0-9][ -]?){8,}[0-9](?![0-9])")
    private val namedPerson = Regex("((?:이름|성명|학생|아동|원아|보호자|엄마|아빠)\\s*[:：]\\s*)[가-힣]{2,5}")

    fun redact(value: String, childName: String): String {
        var result = value
        if (childName.isNotBlank()) result = result.replace(childName.trim(), "[아이]")
        result = namedPerson.replace(result) { "${it.groupValues[1]}[이름]" }
        result = email.replace(result, "[이메일]")
        result = phone.replace(result, "[전화번호]")
        return longNumber.replace(result, "[계좌·긴번호]")
    }

    fun preview(records: List<ProbeRecord>, childName: String): String = exportJson(records, childName)

    fun exportJson(records: List<ProbeRecord>, childName: String): String {
        val rows = records.mapIndexed { index, record ->
            fields(record, index, childName).entries.joinToString(",\n", "    {\n", "\n    }") { (key, value) ->
                "      ${jsonString(key)}: ${jsonString(value)}"
            }
        }.joinToString(",\n")
        return """
            |{
            |  "schemaVersion": "mom-probe-export-1",
            |  "kind": "pseudonymized-research-data",
            |  "warning": ${jsonString(WARNING)},
            |  "recordCount": ${records.size},
            |  "records": [
            |$rows
            |  ]
            |}
        """.trimMargin()
    }

    fun exportCsv(records: List<ProbeRecord>, childName: String): String {
        val header = fields(emptyRecord(), 0, childName).keys.joinToString(",") { csvCell(it) }
        val rows = records.mapIndexed { index, record -> fields(record, index, childName).values.joinToString(",") { csvCell(it) } }
        // UTF-8 BOM makes Korean text readable in common spreadsheet import flows.
        return "\uFEFF" + (listOf(header) + rows).joinToString("\r\n") + "\r\n"
    }

    fun csvCell(value: String): String {
        val first = value.trimStart { it.isWhitespace() || it == '\uFEFF' }.firstOrNull()
        val safe = if (first in listOf('=', '+', '-', '@') || value.startsWith('\t') || value.startsWith('\r') || value.startsWith('\n')) "'$value" else value
        return "\"${safe.replace("\"", "\"\"")}\""
    }

    private fun fields(record: ProbeRecord, index: Int, childName: String): LinkedHashMap<String, String> = with(record) {
        linkedMapOf(
            "recordAlias" to "record_${index + 1}",
            "packageName" to packageName,
            "appLabel" to appLabel,
            "postedAt" to postedAt.toString(),
            "receivedAt" to receivedAt.toString(),
            "title" to title,
            "text" to text,
            "bigText" to bigText,
            "textLines" to textLines.joinToString("\n"),
            "subText" to subText.orEmpty(),
            "summaryText" to summaryText.orEmpty(),
            "category" to category.orEmpty(),
            "channelId" to channelId.orEmpty(),
            "truncated" to truncated.toString(),
        ).mapValuesTo(linkedMapOf()) { (key, value) ->
            if (key == "postedAt" || key == "receivedAt" || key == "recordAlias") value else redact(value, childName)
        }
    }

    private fun jsonString(value: String): String = buildString {
        append('"')
        value.forEach { character ->
            when (character) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                else -> if (character < ' ') append("\\u%04x".format(character.code)) else append(character)
            }
        }
        append('"')
    }

    private fun emptyRecord() = ProbeRecord("", "", "", 0, 0, "", "", "", emptyList(), null, null, null, null, 0, "", false, false, "")
}
