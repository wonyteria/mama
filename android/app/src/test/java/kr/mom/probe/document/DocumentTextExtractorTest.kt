package kr.mom.probe.document

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.Charset
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class DocumentTextExtractorTest {
    private val utf16le = Charset.forName("UTF-16LE")

    @Test fun extractsParagraphTextFromSyntheticCompressedHwp5() {
        val bytes = syntheticHwp5(
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

    @Test fun rejectsPasswordProtectedHwp5WithoutAttemptingBypass() {
        val bytes = syntheticHwp5(
            text = "읽으면 안 되는 보호 문서",
            flags = 1 shl 1,
        )

        val result = DocumentTextExtractor.extract(bytes, filename = "locked.hwp")

        assertEquals(DocumentCompleteness.UNSUPPORTED, result.completeness)
        assertTrue(result.issues.any { it.code == DocumentIssueCode.PROTECTED_DOCUMENT })
        assertTrue(result.text.isBlank())
    }

    @Test fun rejectsDuplicateFatSectorsBeforeAllocatingFatBytes() {
        val bytes = syntheticHwp5(
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
        val bytes = syntheticHwp5(
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
        val bytes = syntheticHwp5(
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
        val bytes = syntheticHwp5(
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
        val bytes = syntheticHwp5(
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
        val bytes = syntheticHwp5(
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
        val bytes = syntheticHwp5CompressedStandardSections(
            sectionBodies = listOf(budgetFiller, laterRecord),
        )

        val result = DocumentTextExtractor.extract(bytes, filename = "many-sections.hwp")

        assertFalse(result.completeness == DocumentCompleteness.COMPLETE)
        assertFalse(result.text.contains("나중 섹션은 처리되면 안 돼요"))
        assertTrue(result.issues.any { it.code == DocumentIssueCode.CONTENT_LIMIT_EXCEEDED })
    }

    @Test fun extractsTextFromHwpxXmlWithExternalEntityBlocked() {
        val bytes = hwpx(
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
        val bytes = hwpxBytes(
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
        val bytes = hwpxBytes(
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
        val bytes = hwpx(
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
            hwpxBytes("Contents/section0.xml" to disguised),
            filename = "utf16-doctype.hwpx",
        )

        assertEquals(DocumentCompleteness.UNSUPPORTED, result.completeness)
        assertFalse(result.text.contains("root:"))
        assertTrue(result.issues.any { it.code == DocumentIssueCode.XML_PARSE_FAILED })
    }

    @Test fun hwpxRejectsLookalikeHancomNamespaces() {
        val bytes = hwpx(
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
        val bytes = hwpx(
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
        val bytes = hwpx(
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

    private fun syntheticHwp5(text: String, flags: Int): ByteArray {
        val fileHeader = ByteArray(64)
        "HWP Document File".toByteArray(Charsets.US_ASCII).copyInto(fileHeader)
        fileHeader.putIntLe(32, 0x05000000)
        fileHeader.putIntLe(36, flags)
        val paraText = text.toByteArray(utf16le)
        val record = ByteArrayOutputStream().apply {
            writeRecordHeader(67, paraText.size)
            write(paraText)
        }.toByteArray()
        val section = if ((flags and 1) != 0) deflateRaw(record) else record
        val miniStream = ByteArray(512)
        fileHeader.copyInto(miniStream, destinationOffset = 0)
        section.copyInto(miniStream, destinationOffset = 64)
        val sectionMiniSectors = (section.size + 63) / 64
        val sectors = listOf(
            fatSector(),
            directorySector(section.size.toLong()),
            miniStream,
            miniFatSector(sectionMiniSectors),
        )
        return header() + sectors.reduce(ByteArray::plus)
    }

    private fun syntheticHwp5CompressedStandardSections(sectionBodies: List<ByteArray>): ByteArray {
        val fileHeader = ByteArray(64)
        "HWP Document File".toByteArray(Charsets.US_ASCII).copyInto(fileHeader)
        fileHeader.putIntLe(32, 0x05000000)
        fileHeader.putIntLe(36, 1)
        val sectionStreams = sectionBodies.map { body ->
            deflateRaw(body).let { compressed ->
                if (compressed.size >= 4096) compressed else compressed + ByteArray(4096 - compressed.size)
            }
        }
        val sectorPayloads = mutableListOf<ByteArray>()
        sectorPayloads += fatSector()
        sectorPayloads += directorySector(sectionStreams.map { it.size.toLong() })
        sectorPayloads += miniStream(fileHeader)
        sectorPayloads += miniFatSector(sectionMiniSectors = 0)
        sectionStreams.forEach { stream ->
            stream.asIterable().chunked(512).forEach { chunk ->
                sectorPayloads += chunk.toByteArray().copyOf(512)
            }
        }
        val bytes = header().apply {
            putIntLe(44, 1)
            putIntLe(60, 4)
        } + sectorPayloads.reduce(ByteArray::plus)
        val fatOffset = 512
        bytes.putIntLe(fatOffset + 1 * 4, 2)
        bytes.putIntLe(fatOffset + 2 * 4, -2)
        bytes.putIntLe(fatOffset + 3 * 4, -2)
        bytes.putIntLe(fatOffset + 4 * 4, -2)
        var sector = 5
        sectionStreams.forEach { stream ->
            val sectorCount = (stream.size + 511) / 512
            repeat(sectorCount) { index ->
                val next = if (index == sectorCount - 1) -2 else sector + 1
                bytes.putIntLe(fatOffset + sector * 4, next)
                sector++
            }
        }
        return bytes
    }

    private fun header(): ByteArray = ByteArray(512) { 0xFF.toByte() }.apply {
        byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(), 0xA1.toByte(), 0xB1.toByte(), 0x1A, 0xE1.toByte()).copyInto(this)
        for (i in 8 until 24) this[i] = 0
        putShortLe(24, 0x003E)
        putShortLe(26, 0x0003)
        putShortLe(28, 0xFFFE)
        putShortLe(30, 0x0009)
        putShortLe(32, 0x0006)
        for (i in 34 until 44) this[i] = 0
        putIntLe(44, 1)
        putIntLe(48, 1)
        putIntLe(52, 0)
        putIntLe(56, 4096)
        putIntLe(60, 3)
        putIntLe(64, 1)
        putIntLe(68, -2)
        putIntLe(72, 0)
        putIntLe(76, 0)
    }

    private fun fatSector(): ByteArray = ByteArray(512) { 0xFF.toByte() }.apply {
        putIntLe(0, -3)
        putIntLe(4, -2)
        putIntLe(8, -2)
        putIntLe(12, -2)
    }

    private fun directorySector(sectionSize: Long): ByteArray = ByteArray(512).apply {
        directoryEntry(offset = 0, name = "Root Entry", type = 5, child = 2, startSector = 2, size = 512)
        directoryEntry(offset = 128, name = "FileHeader", type = 2, right = -1, startSector = 0, size = 64)
        directoryEntry(offset = 256, name = "BodyText", type = 1, left = 1, child = 3, startSector = -1, size = 0)
        directoryEntry(offset = 384, name = "Section0", type = 2, startSector = 1, size = sectionSize)
    }

    private fun directorySector(sectionSizes: List<Long>): ByteArray {
        require(sectionSizes.size == 2)
        val first = ByteArray(512)
        first.directoryEntry(offset = 0, name = "Root Entry", type = 5, child = 2, startSector = 3, size = 512)
        first.directoryEntry(offset = 128, name = "FileHeader", type = 2, right = -1, startSector = 0, size = 64)
        first.directoryEntry(offset = 256, name = "BodyText", type = 1, left = 1, child = 3, startSector = -1, size = 0)
        first.directoryEntry(offset = 384, name = "Section0", type = 2, right = 4, startSector = 5, size = sectionSizes[0])
        val second = ByteArray(512)
        val section1Start = 5 + ((sectionSizes[0] + 511) / 512).toInt()
        second.directoryEntry(offset = 0, name = "Section1", type = 2, startSector = section1Start, size = sectionSizes[1])
        return first + second
    }

    private fun miniStream(fileHeader: ByteArray): ByteArray = ByteArray(512).apply {
        fileHeader.copyInto(this, destinationOffset = 0)
    }

    private fun ByteArray.directoryEntry(
        offset: Int,
        name: String,
        type: Int,
        left: Int = -1,
        right: Int = -1,
        child: Int = -1,
        startSector: Int,
        size: Long,
    ) {
        val encodedName = (name + "\u0000").toByteArray(utf16le)
        encodedName.copyInto(this, offset)
        putShortLe(offset + 64, encodedName.size)
        this[offset + 66] = type.toByte()
        this[offset + 67] = 1
        putIntLe(offset + 68, left)
        putIntLe(offset + 72, right)
        putIntLe(offset + 76, child)
        putIntLe(offset + 116, startSector)
        putIntLe(offset + 120, size.toInt())
        putIntLe(offset + 124, 0)
    }

    private fun miniFatSector(sectionMiniSectors: Int): ByteArray = ByteArray(512) { 0xFF.toByte() }.apply {
        putIntLe(0, -2)
        repeat(sectionMiniSectors) { index ->
            val next = if (index == sectionMiniSectors - 1) -2 else index + 2
            putIntLe((index + 1) * 4, next)
        }
    }

    private fun hwpx(vararg entries: Pair<String, String>): ByteArray {
        return hwpxBytes(*entries.map { it.first to it.second.toByteArray(Charsets.UTF_8) }.toTypedArray())
    }

    private fun hwpxBytes(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            entries.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun deflateRaw(bytes: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.DEFAULT_COMPRESSION, true)
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(256)
        deflater.setInput(bytes)
        deflater.finish()
        while (!deflater.finished()) {
            out.write(buffer, 0, deflater.deflate(buffer))
        }
        deflater.end()
        return out.toByteArray()
    }

    private fun ByteArrayOutputStream.writeIntLe(value: Int) {
        write(value and 0xFF)
        write((value ushr 8) and 0xFF)
        write((value ushr 16) and 0xFF)
        write((value ushr 24) and 0xFF)
    }

    private fun ByteArrayOutputStream.writeRecordHeader(tagId: Int, size: Int) {
        if (size >= 0xFFF) {
            writeIntLe((0xFFF shl 20) or tagId)
            writeIntLe(size)
        } else {
            writeIntLe((size shl 20) or tagId)
        }
    }

    private fun ByteArray.putShortLe(offset: Int, value: Int) {
        this[offset] = (value and 0xFF).toByte()
        this[offset + 1] = ((value ushr 8) and 0xFF).toByte()
    }

    private fun ByteArray.putIntLe(offset: Int, value: Int) {
        this[offset] = (value and 0xFF).toByte()
        this[offset + 1] = ((value ushr 8) and 0xFF).toByte()
        this[offset + 2] = ((value ushr 16) and 0xFF).toByte()
        this[offset + 3] = ((value ushr 24) and 0xFF).toByte()
    }

    private fun ByteArray.intLe(offset: Int): Int =
        (this[offset].toInt() and 0xFF) or
            ((this[offset + 1].toInt() and 0xFF) shl 8) or
            ((this[offset + 2].toInt() and 0xFF) shl 16) or
            ((this[offset + 3].toInt() and 0xFF) shl 24)
}
