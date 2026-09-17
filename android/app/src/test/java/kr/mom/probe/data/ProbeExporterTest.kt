package kr.mom.probe.data

import org.junit.Assert.*
import org.junit.Test

class ProbeExporterTest {
    private fun record(text: String) = ProbeRecord("raw-id", "school.app", "학교앱", 123, 456, "안내", text, "", emptyList(), null, null, null, null, 7, "private-key", false, false, "private-hash")

    @Test fun `known child names phones emails and long account numbers are masked`() {
        val output = ProbeExporter.redact("김지우 보호자: 김철수 010-1234-5678 test@example.com 계좌 123-456789-0123", "김지우")
        listOf("김지우", "김철수", "010-1234-5678", "test@example.com", "123-456789-0123").forEach { assertFalse(output.contains(it)) }
        assertTrue(output.contains("[아이]"))
    }

    @Test fun `csv quotes commas quotes and embedded newline`() {
        assertEquals("\"가방, \"\"물통\"\"\n준비\"", ProbeExporter.csvCell("가방, \"물통\"\n준비"))
    }

    @Test fun `csv neutralizes formula prefixes including whitespace tricks`() {
        listOf("=SUM(1,2)", "+1", "-1", "@SUM(A1)", "  =HYPERLINK()", "\t=1", "\r=1", "\n=1").forEach {
            assertTrue(ProbeExporter.csvCell(it).startsWith("\"'"))
        }
        assertEquals("\"준비물\"", ProbeExporter.csvCell("준비물"))
    }

    @Test fun `exports omit raw identifiers and escape JSON controls`() {
        val output = ProbeExporter.exportJson(listOf(record("김지우\n\"준비\"\\가방")), "김지우")
        assertFalse(output.contains("raw-id"))
        assertFalse(output.contains("private-key"))
        assertFalse(output.contains("private-hash"))
        assertFalse(output.contains("김지우"))
        assertTrue(output.contains("[아이]\\n\\\"준비\\\"\\\\가방"))
        assertTrue(output.contains("pseudonymized-research-data"))
    }

    @Test fun `empty export remains a usable file`() {
        assertTrue(ProbeExporter.exportJson(emptyList(), "").contains("\"recordCount\": 0"))
        assertTrue(ProbeExporter.exportCsv(emptyList(), "").startsWith("\uFEFF\"recordAlias\""))
    }

    @Test fun `export retains truncation flag and collection timestamps`() {
        val output = ProbeExporter.exportJson(listOf(record("긴 알림").copy(receivedAt = 1_800_000_000_000L, truncated = true)), "")
        assertTrue(output.contains("\"truncated\": \"true\""))
        assertTrue(output.contains("1800000000000"))
    }
}
