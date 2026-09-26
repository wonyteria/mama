package kr.mom.probe.document

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.Charset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class DocumentTextExtractorTest {
    private val utf16le = Charset.forName("UTF-16LE")

    @Test fun extractsParagraphTextFromSyntheticCompressedHwp5() {
        val bytes = SyntheticHwp5.hwp5(
            text = "2026 학부모 수업 참여의 날\n1교시 공개수업",
            flags = 1,
        )

        val result = DocumentTextExtractor.extract(bytes, filename = "parent-day.hwp")

        assertEquals(DocumentFormat.HWP5, result.format)
        assertEquals(DocumentCompleteness.COMPLETE, result.completeness)
        assertTrue(result.text.contains("2026 학부모 수업 참여의 날"))
        assertTrue(result.text.contains("1교시 공개수업"))
        assertFalse(result.issues.any { it.code == DocumentIssueCode.NO_TEXT })
    }

    @Test fun skipsHwp5ExtendedControlBlocksSoFieldDataDoesNotLeakAsText() {
        // 공개 학교 HWP에서 관찰된 구조: 인라인/확장 제어 = 제어 문자 + 6 WCHAR 데이터 + 제어 문자(총 8 WCHAR).
        // CTRL ID는 4바이트 역순 저장이라 UTF-16으로 디코딩하면 깨진 CJK 문자가 된다.
        val hyperlinkStart = SyntheticHwp5.controlBlock('\u0003', "\u6C6B\u2568") // %hlk
        val hyperlinkEnd = SyntheticHwp5.controlBlock('\u0004', "\u6C6B\u0068")
        val sectionDef = SyntheticHwp5.controlBlock('\u0002', "\u6364\u7365") // secd
        val inlineObject = SyntheticHwp5.controlBlock('\u000B', "\u6F20\u6773") // gso
        val wideTab = SyntheticHwp5.controlBlock('\u0009', "\u0916\u0100")
        val text = "홈페이지: $hyperlinkStart" + "https://example.kr" + hyperlinkEnd +
            "\n$sectionDef$inlineObject" + "학부모 안내\n" + wideTab + "1. 준비물 안내\r"
        val bytes = SyntheticHwp5.hwp5(text = text, flags = 1)

        val result = DocumentTextExtractor.extract(bytes, filename = "controls.hwp")

        assertTrue(result.text.contains("홈페이지: https://example.kr"))
        assertTrue(result.text.contains("학부모 안내"))
        assertTrue(result.text.contains("\t1. 준비물 안내"))
        assertFalse(result.text.contains('汫'))
        assertFalse(result.text.contains('漠'))
        assertFalse(result.text.contains('╨'))
        assertFalse(result.text.contains('ख'))
    }

    @Test fun rejectsPasswordProtectedHwp5WithoutAttemptingBypass() {
        val bytes = SyntheticHwp5.hwp5(
            text = "읽으면 안 되는 보호 문서",
            flags = 1 shl 1,
        )

        val result = DocumentTextExtractor.extract(bytes, filename = "locked.hwp")

        assertEquals(DocumentCompleteness.UNSUPPORTED, result.completeness)
        assertTrue(result.issues.any { it.code == DocumentIssueCode.PROTECTED_DOCUMENT })
        assertTrue(result.text.isBlank())
    }

    @Test fun rejectsDuplicateFatSectorsBeforeAllocatingFatBytes() {
        val bytes = SyntheticHwp5.hwp5(
            text = "정상 본문",
            flags = 1,
        ).copyOf().apply {
            putIntLe(44, 2)
            putIntLe(80, 0)
        }

        val result = DocumentTextExtractor.extract(bytes, filename = "duplicate-fat.hwp")

        assertEquals(DocumentCompleteness.UNSUPPORTED, result.completeness)
        assertTrue(result.issues.any { it.code == DocumentIssueCode.INVALID_CONTAINER })
    }

    @Test fun rejectsShortMiniFatChainBeforeReturningCompleteText() {
        val bytes = SyntheticHwp5.hwp5(
            text = "미니 FAT 체인이 짧으면 본문을 완료로 보지 않아야 해요".repeat(6),
            flags = 0,
        ).copyOf().apply {
            val miniFatSectorOffset = 512 + 512 + 512 + 512
            putIntLe(miniFatSectorOffset + 4, -2)
        }

        val result = DocumentTextExtractor.extract(bytes, filename = "short-minifat.hwp")

        assertFalse(result.completeness == DocumentCompleteness.COMPLETE)
        assertTrue(result.issues.any { it.code == DocumentIssueCode.INVALID_CONTAINER })
    }

    @Test fun rejectsCfbVersion4RatherThanUsingVersion3Offsets() {
        val bytes = SyntheticHwp5.hwp5(
            text = "v4는 현재 범위가 아니에요",
            flags = 1,
        ).copyOf().apply {
            putShortLe(26, 4)
            putShortLe(30, 12)
        }

        val result = DocumentTextExtractor.extract(bytes, filename = "v4.hwp")

        assertEquals(DocumentCompleteness.UNSUPPORTED, result.completeness)
        assertTrue(result.issues.any { it.code == DocumentIssueCode.UNSUPPORTED_VERSION })
    }

    @Test fun rejectsOversizedDeclaredOleStreamInsteadOfSilentlyTrimming() {
        val bytes = SyntheticHwp5.hwp5(
            text = "stream size clamp는 완료로 보이면 안 돼요",
            flags = 1,
        ).copyOf().apply {
            val fileHeaderSizeOffset = 512 + 512 + 128 + 120
            putIntLe(fileHeaderSizeOffset, DocumentTextLimits.MAX_STREAM_BYTES + 1)
        }

        val result = DocumentTextExtractor.extract(bytes, filename = "oversized-stream.hwp")

        assertEquals(DocumentCompleteness.UNSUPPORTED, result.completeness)
        assertTrue(result.issues.any { it.code == DocumentIssueCode.CONTENT_LIMIT_EXCEEDED })
    }

    @Test fun truncatedCompressedHwp5StreamDoesNotReturnCompleteText() {
        val bytes = SyntheticHwp5.hwp5(
            text = "압축 본문이 잘리면 완료가 아니어야 해요",
            flags = 1,
        ).copyOf()
        val sectionSizeOffset = 512 + 512 + 384 + 120
        bytes.putIntLe(sectionSizeOffset, bytes.intLe(sectionSizeOffset) - 1)

        val result = DocumentTextExtractor.extract(bytes, filename = "truncated.hwp")

        assertFalse(result.completeness == DocumentCompleteness.COMPLETE)
        assertTrue(result.issues.any { it.code == DocumentIssueCode.DECOMPRESSION_FAILED })
    }

    @Test fun hwp5TextLimitIsGlobalAndVisible() {
        val bytes = SyntheticHwp5.hwp5(
            text = "가".repeat(DocumentTextLimits.MAX_TEXT_CHARS + 50),
            flags = 1,
        )

        val result = DocumentTextExtractor.extract(bytes, filename = "large-text.hwp")

        assertEquals(DocumentCompleteness.PARTIAL, result.completeness)
        assertEquals(DocumentTextLimits.MAX_TEXT_CHARS, result.text.length)
        assertTrue(result.issues.any { it.code == DocumentIssueCode.CONTENT_LIMIT_EXCEEDED })
    }

    @Test fun hwp5InflateBudgetStopsBeforeLaterSections() {
        val budgetFiller = ByteArray(DocumentTextLimits.MAX_DECOMPRESSED_BYTES) { 0x41 }
        val laterRecord = ByteArrayOutputStream().apply {
            val laterText = "나중 섹션은 처리되면 안 돼요".toByteArray(utf16le)
            writeRecordHeader(67, laterText.size)
            write(laterText)
        }.toByteArray()
        val bytes = SyntheticHwp5.compressedStandardSections(
            sectionBodies = listOf(budgetFiller, laterRecord),
        )

        val result = DocumentTextExtractor.extract(bytes, filename = "many-sections.hwp")

        assertFalse(result.completeness == DocumentCompleteness.COMPLETE)
        assertFalse(result.text.contains("나중 섹션은 처리되면 안 돼요"))
        assertTrue(result.issues.any { it.code == DocumentIssueCode.CONTENT_LIMIT_EXCEEDED })
    }

    @Test fun extractsTextFromHwpxXmlWithExternalEntityBlocked() {
        val bytes = SyntheticHwp5.hwpx(
            "Contents/section0.xml" to """
                <?xml version="1.0" encoding="UTF-8"?>
                <hs:sec xmlns:hs="http://www.hancom.co.kr/hwpml/2011/section" xmlns:hp="http://www.hancom.co.kr/hwpml/2011/paragraph">
                    <hp:p><hp:run><hp:t>학부모 수업 참여 안내</hp:t></hp:run></hp:p>
                    <hp:p><hp:run><hp:t>참관 일정은 본문 기준으로만 판단</hp:t></hp:run></hp:p>
                </hs:sec>
            """.trimIndent(),
            "Contents/section1.xml" to """
                <!DOCTYPE sec [ <!ENTITY xxe SYSTEM "file:///etc/passwd"> ]>
                <hs:sec xmlns:hs="http://www.hancom.co.kr/hwpml/2011/section" xmlns:hp="http://www.hancom.co.kr/hwpml/2011/paragraph">
                    <hp:p><hp:run><hp:t>&xxe;</hp:t></hp:run></hp:p>
                </hs:sec>
            """.trimIndent(),
        )

        val result = DocumentTextExtractor.extract(bytes, filename = "notice.hwpx")

        assertEquals(DocumentFormat.HWPX, result.format)
        assertTrue(result.text.contains("학부모 수업 참여 안내"))
        assertTrue(result.text.contains("참관 일정은 본문 기준으로만 판단"))
        assertFalse(result.text.contains("root:"))
        assertTrue(result.issues.any { it.code == DocumentIssueCode.XML_PARSE_FAILED })
    }

    @Test fun hwpxNonTextInflationBudgetStopsBeforeLaterEntries() {
        val bytes = SyntheticHwp5.hwpxBytes(
            "BinData/huge.bin" to ByteArray(DocumentTextLimits.MAX_DECOMPRESSED_BYTES + 1),
            "Contents/section0.xml" to """
                <hs:sec xmlns:hs="http://www.hancom.co.kr/hwpml/2011/section" xmlns:hp="http://www.hancom.co.kr/hwpml/2011/paragraph">
                    <hp:p><hp:run><hp:t>예산 초과 뒤 읽히면 안 되는 본문</hp:t></hp:run></hp:p>
                </hs:sec>
            """.trimIndent().toByteArray(Charsets.UTF_8),
        )

        val result = DocumentTextExtractor.extract(bytes, filename = "zip-budget.hwpx")

        assertFalse(result.completeness == DocumentCompleteness.COMPLETE)
        assertFalse(result.text.contains("예산 초과 뒤 읽히면 안 되는 본문"))
        assertTrue(result.issues.any { it.code == DocumentIssueCode.CONTENT_LIMIT_EXCEEDED })
    }

    @Test fun hwpxDirectoryEntryPayloadCountsAgainstInflationBudget() {
        val bytes = SyntheticHwp5.hwpxBytes(
            "Contents/fake-dir/" to ByteArray(DocumentTextLimits.MAX_DECOMPRESSED_BYTES + 1),
            "Contents/section0.xml" to """
                <hs:sec xmlns:hs="http://www.hancom.co.kr/hwpml/2011/section" xmlns:hp="http://www.hancom.co.kr/hwpml/2011/paragraph">
                    <hp:p><hp:run><hp:t>디렉터리 폭탄 뒤 본문은 읽히면 안 돼요</hp:t></hp:run></hp:p>
                </hs:sec>
            """.trimIndent().toByteArray(Charsets.UTF_8),
        )

        val result = DocumentTextExtractor.extract(bytes, filename = "dir-budget.hwpx")

        assertFalse(result.completeness == DocumentCompleteness.COMPLETE)
        assertFalse(result.text.contains("디렉터리 폭탄 뒤 본문은 읽히면 안 돼요"))
        assertTrue(result.issues.any { it.code == DocumentIssueCode.CONTENT_LIMIT_EXCEEDED })
    }

    @Test fun hwpxRejectsFakeSectionXmlWithoutHancomParagraphNamespace() {
        val bytes = SyntheticHwp5.hwpx(
            "Contents/section0.xml" to """
                <fake><t>가짜 section 텍스트는 본문으로 승격하지 않아요</t></fake>
            """.trimIndent(),
        )

        val result = DocumentTextExtractor.extract(bytes, filename = "fake-section.hwpx")

        assertEquals(DocumentCompleteness.UNSUPPORTED, result.completeness)
        assertFalse(result.text.contains("가짜 section 텍스트"))
        assertTrue(result.issues.any { it.code == DocumentIssueCode.XML_PARSE_FAILED })
    }

    @Test fun hwpxRejectsUtf16BytesThatCouldHideADoctypeFromAnAsciiScan() {
        val disguised = """
            <?xml version="1.0" encoding="UTF-16"?>
            <!DOCTYPE sec [ <!ENTITY xxe SYSTEM "file:///etc/passwd"> ]>
            <hs:sec xmlns:hs="http://www.hancom.co.kr/hwpml/2011/section" xmlns:hp="http://www.hancom.co.kr/hwpml/2011/paragraph">
                <hp:p><hp:run><hp:t>&xxe;</hp:t></hp:run></hp:p>
            </hs:sec>
        """.trimIndent().toByteArray(Charsets.UTF_16LE)
        val result = DocumentTextExtractor.extract(
            SyntheticHwp5.hwpxBytes("Contents/section0.xml" to disguised),
            filename = "utf16-doctype.hwpx",
        )

        assertEquals(DocumentCompleteness.UNSUPPORTED, result.completeness)
        assertFalse(result.text.contains("root:"))
        assertTrue(result.issues.any { it.code == DocumentIssueCode.XML_PARSE_FAILED })
    }

    @Test fun hwpxRejectsLookalikeHancomNamespaces() {
        val bytes = SyntheticHwp5.hwpx(
            "Contents/section0.xml" to """
                <hs:sec xmlns:hs="urn:fake-hancom-section" xmlns:hp="urn:fake-hancom-paragraph">
                    <hp:p><hp:run><hp:t>가짜 namespace 본문</hp:t></hp:run></hp:p>
                </hs:sec>
            """.trimIndent(),
        )

        val result = DocumentTextExtractor.extract(bytes, filename = "fake-namespace.hwpx")
        assertEquals(DocumentCompleteness.UNSUPPORTED, result.completeness)
        assertFalse(result.text.contains("가짜 namespace 본문"))
    }

    @Test fun hwpxTextLimitIsGlobalAndVisible() {
        val largeText = "나".repeat(DocumentTextLimits.MAX_TEXT_CHARS + 50)
        val bytes = SyntheticHwp5.hwpx(
            "Contents/section0.xml" to """
                <hs:sec xmlns:hs="http://www.hancom.co.kr/hwpml/2011/section" xmlns:hp="http://www.hancom.co.kr/hwpml/2011/paragraph">
                    <hp:p><hp:run><hp:t>$largeText</hp:t></hp:run></hp:p>
                </hs:sec>
            """.trimIndent(),
        )

        val result = DocumentTextExtractor.extract(bytes, filename = "large-text.hwpx")

        assertEquals(DocumentCompleteness.PARTIAL, result.completeness)
        assertEquals(DocumentTextLimits.MAX_TEXT_CHARS, result.text.length)
        assertTrue(result.issues.any { it.code == DocumentIssueCode.CONTENT_LIMIT_EXCEEDED })
    }

    @Test fun bareZipMagicIsNotEnoughToClaimHwpx() {
        val bytes = SyntheticHwp5.hwpx(
            "Contents/section0.xml" to """
                <hs:sec xmlns:hs="http://www.hancom.co.kr/hwpml/2011/section" xmlns:hp="http://www.hancom.co.kr/hwpml/2011/paragraph">
                    <hp:p><hp:run><hp:t>파일명이 없으면 HWPX로 승격하지 않아요</hp:t></hp:run></hp:p>
                </hs:sec>
            """.trimIndent(),
        )

        val result = DocumentTextExtractor.extract(bytes)

        assertEquals(DocumentFormat.UNKNOWN, result.format)
        assertEquals(DocumentCompleteness.UNSUPPORTED, result.completeness)
    }

    @Test fun reportsUnsupportedForMalformedHwpInput() {
        val result = DocumentTextExtractor.extract("not an ole file".toByteArray(), filename = "bad.hwp")

        assertEquals(DocumentFormat.HWP5, result.format)
        assertEquals(DocumentCompleteness.UNSUPPORTED, result.completeness)
        assertTrue(result.issues.any { it.code == DocumentIssueCode.INVALID_CONTAINER })
    }

    @Test fun syntheticHwp5EmbeddedBinaryReportsPartialAndSkipsBinData() {
        val bytes = SyntheticHwp5.hwp5WithBinData(
            text = "2학년 전체 학급 대상 안내",
            flags = 1,
        )

        val result = DocumentTextExtractor.extract(bytes, filename = "bin-data.hwp")

        assertEquals(DocumentFormat.HWP5, result.format)
        assertEquals(DocumentCompleteness.PARTIAL, result.completeness)
        assertTrue(result.text.contains("2학년 전체 학급"))
        assertTrue(result.issues.any { it.code == DocumentIssueCode.EMBEDDED_BINARY_SKIPPED })
    }

    @Test fun validatesRealPublicParentClassFixtureWhenPresent() {
        val fixture = File("device-qa/public-fixtures/parent-class-notice-1951993.hwp")
        assumeTrue("public device fixture is intentionally not bundled in test resources", fixture.isFile)

        val result = DocumentTextExtractor.extract(fixture.readBytes(), fixture.name)

        assertEquals(DocumentFormat.HWP5, result.format)
        assertEquals(DocumentCompleteness.PARTIAL, result.completeness)
        assertTrue(result.text.contains("학부모 수업 참여의 날"))
        assertTrue(result.text.contains("학부모"))
        assertTrue(result.issues.any { it.code == DocumentIssueCode.EMBEDDED_BINARY_SKIPPED })
    }

}