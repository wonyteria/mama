package kr.mom.probe.document

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DocumentTextExtractorDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun requireQaPackage() {
        check(context.packageName.endsWith(".qa")) {
            "DocumentTextExtractorDeviceTest must target the debug QA application id, not release user data."
        }
    }

    @Test
    fun syntheticHwpxExtractsKoreanAndChineseTextOnAndroidSax() {
        val bytes = hwpx(
            "Contents/section0.xml" to """
                <?xml version="1.0" encoding="UTF-8"?>
                <hs:sec xmlns:hs="http://www.hancom.co.kr/hwpml/2011/section"
                    xmlns:hp="http://www.hancom.co.kr/hwpml/2011/paragraph">
                    <hp:p><hp:run><hp:t>학부모 수업 참여 안내</hp:t></hp:run></hp:p>
                    <hp:p><hp:run><hp:t>學校 공개수업 자료</hp:t></hp:run></hp:p>
                </hs:sec>
            """.trimIndent(),
        )

        val result = DocumentTextExtractor.extract(bytes, filename = "android-sax.hwpx")

        assertEquals(DocumentFormat.HWPX, result.format)
        assertEquals(DocumentCompleteness.COMPLETE, result.completeness)
        assertTrue(result.text.contains("학부모 수업 참여 안내"))
        assertTrue(result.text.contains("學校 공개수업 자료"))
    }

    @Test
    fun optInPublicHwpFixtureExtractsSecondGradeRowsAndReportsBinDataPartial() {
        val fixture = publicFixture()
        assumeTrue("Push fixture to externalFilesDir/qa-input/parent-class-notice-1951993.hwp for this opt-in QA test.", fixture.isFile)
        assertEquals(PUBLIC_FIXTURE_SHA256, sha256(fixture))

        val result = DocumentTextExtractor.extract(fixture.readBytes(), filename = fixture.name)

        assertEquals(DocumentFormat.HWP5, result.format)
        assertEquals(DocumentCompleteness.PARTIAL, result.completeness)
        assertTrue(result.text.contains("2학년 전체 학급"))
        assertTrue(result.issues.any { it.code == DocumentIssueCode.EMBEDDED_BINARY_SKIPPED })
    }

    @Test
    fun optInPublicHwpxFixtureUsesTheOfficialSectionAndParagraphNamespaces() {
        val root = requireNotNull(context.getExternalFilesDir(null))
        val fixture = File(File(root, "qa-input"), "grade6-notice-1953062.hwpx")
        assumeTrue("Push the public HWPX fixture to the QA input directory.", fixture.isFile)
        assertEquals(PUBLIC_HWPX_SHA256, sha256(fixture))

        val result = DocumentTextExtractor.extract(fixture.readBytes(), filename = fixture.name)

        assertEquals(DocumentFormat.HWPX, result.format)
        assertTrue(result.text.contains("6학년"))
        assertTrue(result.text.contains("개인 도시락"))
        assertEquals(DocumentCompleteness.PARTIAL, result.completeness)
        assertTrue(result.issues.any { it.code == DocumentIssueCode.EMBEDDED_BINARY_SKIPPED })
    }

    private fun publicFixture(): File {
        val root = requireNotNull(context.getExternalFilesDir(null)) {
            "External files directory is unavailable."
        }
        val qaInput = File(root, "qa-input")
        return File(qaInput, "parent-class-notice-1951993.hwp")
    }

    private fun hwpx(vararg entries: Pair<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            entries.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02X".format(it) }
    }

    private companion object {
        const val PUBLIC_FIXTURE_SHA256 = "0CAAADECFCB2FAF9439EB8EE4E158C01497540C7948673BD42093EC629F2C77B"
        const val PUBLIC_HWPX_SHA256 = "D4BB3CB6A7FE9301EBB38093BC6911B9809415048CEAD0E8CB54ECABF27350B8"
    }
}
