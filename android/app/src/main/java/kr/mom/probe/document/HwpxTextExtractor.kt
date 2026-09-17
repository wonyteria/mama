package kr.mom.probe.document

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.StringReader
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.zip.ZipException
import java.util.zip.ZipInputStream
import javax.xml.parsers.SAXParserFactory
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.helpers.DefaultHandler

internal object HwpxTextExtractor {
    fun extract(bytes: ByteArray): DocumentTextResult {
        val issues = mutableListOf<DocumentTextIssue>()
        val paragraphs = mutableListOf<String>()
        val entries = mutableListOf<String>()
        val zipBudget = ZipInflateBudget()
        val textBudget = DocumentTextBudget()
        try {
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                var entry = zip.nextEntry
                var count = 0
                while (entry != null) {
                    count++
                    if (count > DocumentTextLimits.MAX_ZIP_ENTRIES) {
                        issues += DocumentTextIssue(DocumentIssueCode.CONTENT_LIMIT_EXCEEDED, "HWPX ZIP entry가 안전 한도를 넘어 일부만 읽었어요.", recoverable = true)
                        break
                    }
                    val name = entry.name.orEmpty()
                    val collectXml = !entry.isDirectory && name.endsWith(".xml", ignoreCase = true) && isTextXml(name)
                    val entryBytes = zip.readEntryCapped(collectXml, zipBudget)
                    if (!entry.isDirectory && name.startsWith("BinData/", ignoreCase = true)) {
                        issues += DocumentTextIssue(
                            DocumentIssueCode.EMBEDDED_BINARY_SKIPPED,
                            "HWPX 안의 그림/바이너리 자료는 OCR 없이 해석하지 않았어요.",
                            recoverable = true,
                        )
                    }
                    if (collectXml) {
                        paragraphs += parseXmlText(entryBytes, textBudget, issues)
                        entries += name
                        if (textBudget.exceeded) break
                    }
                    entry = zip.nextEntry
                }
            }
        } catch (_: ZipBudgetExceededException) {
            issues += DocumentTextIssue(DocumentIssueCode.CONTENT_LIMIT_EXCEEDED, "HWPX 압축 해제 데이터가 안전 한도를 넘어 일부만 읽었어요.", recoverable = true)
        } catch (_: ZipException) {
            issues += DocumentTextIssue(DocumentIssueCode.INVALID_CONTAINER, "HWPX ZIP 구조가 깨져 본문을 끝까지 읽지 못했어요.", recoverable = true)
        } catch (_: IOException) {
            issues += DocumentTextIssue(DocumentIssueCode.INVALID_CONTAINER, "HWPX ZIP 데이터를 읽는 중 오류가 발생해 본문을 끝까지 읽지 못했어요.", recoverable = true)
        }
        textBudget.issueIfExceeded("문서 본문이 안전 출력 한도를 넘어 일부만 읽었어요.")?.let { issues += it }
        return DocumentTextExtractor.complete(
            format = DocumentFormat.HWPX,
            paragraphs = paragraphs,
            issues = issues,
            evidence = entries.take(16).map { "HWPX XML: $it" },
        )
    }

    private fun isTextXml(name: String): Boolean {
        val normalized = name.replace('\\', '/')
        return normalized.matches(Regex("""Contents/(section|header|footer)\d*\.xml""", RegexOption.IGNORE_CASE))
    }

    private fun parseXmlText(
        bytes: ByteArray,
        textBudget: DocumentTextBudget,
        issues: MutableList<DocumentTextIssue>,
    ): List<String> {
        val xml = try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (_: Exception) {
            issues += DocumentTextIssue(DocumentIssueCode.XML_PARSE_FAILED, "HWPX XML이 검증된 UTF-8 본문이 아니어서 읽지 않았어요.", recoverable = true)
            return emptyList()
        }
        if ('\u0000' in xml || Regex("""<!\s*(DOCTYPE|ENTITY)""", setOf(RegexOption.IGNORE_CASE)).containsMatchIn(xml)) {
            issues += DocumentTextIssue(DocumentIssueCode.XML_PARSE_FAILED, "HWPX XML DTD/entity 선언은 안전상 파싱하지 않았어요.", recoverable = true)
            return emptyList()
        }
        val handler = TextHandler(textBudget)
        try {
            val factory = SAXParserFactory.newInstance()
            factory.isNamespaceAware = true
            // The strict UTF-8 pre-scan above rejects every DTD/entity declaration before
            // parser creation. These flags add defense in depth where the Android SAX
            // implementation supports them; unsupported optional flags do not make a
            // declaration-free document unsafe.
            setFeatureIfSupported(factory, "http://xml.org/sax/features/external-general-entities", false)
            setFeatureIfSupported(factory, "http://xml.org/sax/features/external-parameter-entities", false)
            setFeatureIfSupported(factory, "http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
            setFeatureIfSupported(factory, "http://apache.org/xml/features/disallow-doctype-decl", true)
            val parser = factory.newSAXParser()
            val reader = parser.xmlReader
            reader.entityResolver = org.xml.sax.EntityResolver { _, _ -> InputSource(ByteArrayInputStream(ByteArray(0))) }
            reader.contentHandler = handler
            // Parse the exact decoded text. This prevents SAX from auto-detecting a
            // second byte encoding after the UTF-8 security scan above.
            reader.parse(InputSource(StringReader(xml)))
        } catch (_: Exception) {
            issues += DocumentTextIssue(DocumentIssueCode.XML_PARSE_FAILED, "HWPX XML 본문을 파싱하지 못했어요.", recoverable = true)
        }
        if (!handler.validSectionRoot) {
            issues += DocumentTextIssue(DocumentIssueCode.XML_PARSE_FAILED, "HWPX 본문 section namespace를 확인하지 못했어요.", recoverable = true)
            return emptyList()
        }
        return handler.paragraphs()
    }

    private fun setFeatureIfSupported(factory: SAXParserFactory, feature: String, value: Boolean) {
        runCatching { factory.setFeature(feature, value) }
    }

    private fun ZipInputStream.readEntryCapped(collect: Boolean, budget: ZipInflateBudget): ByteArray {
        val out = if (collect) ByteArrayOutputStream() else null
        val buffer = ByteArray(8192)
        while (true) {
            val read = read(buffer)
            if (read <= 0) break
            budget.add(read)
            if (out != null && out.size() + read > DocumentTextLimits.MAX_STREAM_BYTES) {
                throw ZipBudgetExceededException()
            }
            out?.write(buffer, 0, read)
        }
        return out?.toByteArray() ?: ByteArray(0)
    }

    private class TextHandler(private val textBudget: DocumentTextBudget) : DefaultHandler() {
        private val paragraphs = mutableListOf<String>()
        private val current = StringBuilder()
        private var textDepth = 0
        private var elementDepth = 0
        var validSectionRoot: Boolean = false
            private set

        override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes?) {
            elementDepth++
            if (elementDepth == 1) {
                validSectionRoot = name(localName, qName) == "sec" && isHancomSectionNamespace(uri)
            }
            if (validSectionRoot && name(localName, qName) == "t" && isHancomParagraphNamespace(uri)) textDepth++
        }

        override fun characters(ch: CharArray, start: Int, length: Int) {
            if (textDepth > 0) textBudget.append(ch, start, length, current)
        }

        override fun endElement(uri: String?, localName: String?, qName: String?) {
            if (validSectionRoot && isHancomParagraphNamespace(uri)) {
                when (name(localName, qName)) {
                    "p", "tr" -> flushParagraph()
                    "tc" -> current.append('\t')
                    "br" -> current.append('\n')
                    "t" -> if (textDepth > 0) textDepth--
                }
            }
            elementDepth--
        }

        fun paragraphs(): List<String> {
            flushParagraph()
            return paragraphs
        }

        private fun flushParagraph() {
            val text = current.toString().replace(Regex("""[ \t\r\n]+"""), " ").trim()
            if (text.isNotBlank()) paragraphs += text
            current.clear()
        }

        private fun name(localName: String?, qName: String?): String =
            (localName?.takeIf { it.isNotBlank() } ?: qName.orEmpty().substringAfter(':')).lowercase()

        private fun isHancomParagraphNamespace(uri: String?): Boolean {
            return uri == HANCOM_PARAGRAPH_2011 || uri == HANCOM_PARAGRAPH_2016
        }

        private fun isHancomSectionNamespace(uri: String?): Boolean {
            return uri == HANCOM_SECTION_2011
        }
    }

    private class ZipInflateBudget {
        private var inflatedBytes = 0L

        fun add(count: Int) {
            inflatedBytes += count
            if (inflatedBytes > DocumentTextLimits.MAX_DECOMPRESSED_BYTES) {
                throw ZipBudgetExceededException()
            }
        }
    }

    private class ZipBudgetExceededException : RuntimeException()

    private const val HANCOM_SECTION_2011 = "http://www.hancom.co.kr/hwpml/2011/section"
    private const val HANCOM_PARAGRAPH_2011 = "http://www.hancom.co.kr/hwpml/2011/paragraph"
    private const val HANCOM_PARAGRAPH_2016 = "http://www.hancom.co.kr/hwpml/2016/paragraph"
}
