package kr.mom.probe.document

import java.io.ByteArrayOutputStream
import java.nio.charset.Charset
import java.util.zip.DataFormatException
import java.util.zip.Inflater

internal object Hwp5TextExtractor {
    private val utf16le = Charset.forName("UTF-16LE")
    private const val HWP_TAG_PARA_TEXT = 67
    private const val FLAG_COMPRESSED = 1
    private const val FLAG_PASSWORD_PROTECTED = 1 shl 1
    private const val FLAG_DISTRIBUTION_PROTECTED = 1 shl 2

    fun extract(bytes: ByteArray): DocumentTextResult {
        val ole = try {
            CompoundOleReader.parse(bytes)
        } catch (error: OleParseException) {
            return DocumentTextExtractor.unsupported(DocumentFormat.HWP5, error.code, error.message)
        }
        val fileHeader = try {
            ole.readStream("FileHeader")
        } catch (error: OleParseException) {
            return DocumentTextExtractor.unsupported(DocumentFormat.HWP5, error.code, error.message)
        } ?: return DocumentTextExtractor.unsupported(DocumentFormat.HWP5, DocumentIssueCode.INVALID_CONTAINER, "HWP FileHeader stream을 찾지 못했어요.")
        val headerText = String(fileHeader.copyOfRange(0, minOf(fileHeader.size, 32)), Charsets.US_ASCII)
        if (!headerText.startsWith("HWP Document File")) {
            return DocumentTextExtractor.unsupported(DocumentFormat.HWP5, DocumentIssueCode.INVALID_CONTAINER, "HWP5 FileHeader signature가 맞지 않아요.")
        }
        if (fileHeader.size < 40) {
            return DocumentTextExtractor.unsupported(DocumentFormat.HWP5, DocumentIssueCode.INVALID_CONTAINER, "HWP5 FileHeader가 너무 짧아요.")
        }
        val version = fileHeader.i32(32)
        if ((version ushr 24) != 5) {
            return DocumentTextExtractor.unsupported(DocumentFormat.HWP5, DocumentIssueCode.UNSUPPORTED_VERSION, "HWP 5.x 문서만 지원해요.")
        }
        val flags = fileHeader.i32(36)
        if ((flags and FLAG_PASSWORD_PROTECTED) != 0) {
            return DocumentTextExtractor.unsupported(DocumentFormat.HWP5, DocumentIssueCode.PROTECTED_DOCUMENT, "암호화된 HWP 문서는 읽지 않았어요.")
        }
        if ((flags and FLAG_DISTRIBUTION_PROTECTED) != 0) {
            return DocumentTextExtractor.unsupported(DocumentFormat.HWP5, DocumentIssueCode.DISTRIBUTION_PROTECTED, "배포용으로 보호된 HWP 문서는 읽지 않았어요.")
        }
        val compressed = (flags and FLAG_COMPRESSED) != 0
        val allStreams = try {
            ole.listStreams()
        } catch (error: OleParseException) {
            return DocumentTextExtractor.unsupported(DocumentFormat.HWP5, error.code, error.message)
        }
        val streams = allStreams
            .filter { it.path.replace('\\', '/').startsWith("BodyText/Section", ignoreCase = true) }
            .sortedWith(compareBy { sectionNumber(it.path) ?: Int.MAX_VALUE })
        if (streams.isEmpty()) {
            return DocumentTextExtractor.unsupported(DocumentFormat.HWP5, DocumentIssueCode.NO_TEXT, "HWP BodyText/Section stream을 찾지 못했어요.")
        }
        val issues = mutableListOf<DocumentTextIssue>()
        val textBudget = DocumentTextBudget()
        if (allStreams.any { it.path.replace('\\', '/').startsWith("BinData/", ignoreCase = true) }) {
            issues += DocumentTextIssue(
                DocumentIssueCode.EMBEDDED_BINARY_SKIPPED,
                "HWP 안의 그림/바이너리 자료는 OCR 없이 해석하지 않았어요.",
                recoverable = true,
            )
        }
        val paragraphs = mutableListOf<String>()
        var decompressedBytes = 0
        for (stream in streams) {
            if (textBudget.exceeded) break
            val remainingDecompressedBytes = DocumentTextLimits.MAX_DECOMPRESSED_BYTES - decompressedBytes
            if (remainingDecompressedBytes <= 0) {
                issues += DocumentTextIssue(DocumentIssueCode.CONTENT_LIMIT_EXCEEDED, "HWP 본문 압축 해제 데이터가 안전 한도를 넘어 일부만 읽었어요.", recoverable = true)
                break
            }
            val raw = try {
                ole.readStream(stream.path)
            } catch (error: OleParseException) {
                issues += DocumentTextIssue(error.code, error.message, recoverable = true)
                continue
            } ?: continue
            val body = if (compressed) {
                try {
                    val inflated = inflateRaw(raw, remainingDecompressedBytes)
                    if (inflated.trailingBytes > 0) {
                        issues += DocumentTextIssue(
                            DocumentIssueCode.INVALID_CONTAINER,
                            "HWP 압축 본문 뒤에 추가 바이트가 있어 본문만 보수적으로 읽었어요.",
                            recoverable = true,
                        )
                    }
                    inflated.bytes
                } catch (error: DataFormatException) {
                    issues += DocumentTextIssue(DocumentIssueCode.DECOMPRESSION_FAILED, "HWP 본문 압축을 해제하지 못했어요.", recoverable = true)
                    continue
                } catch (error: OleParseException) {
                    issues += DocumentTextIssue(error.code, error.message, recoverable = true)
                    break
                }
            } else {
                raw
            }
            decompressedBytes += body.size
            if (decompressedBytes > DocumentTextLimits.MAX_DECOMPRESSED_BYTES) {
                issues += DocumentTextIssue(DocumentIssueCode.CONTENT_LIMIT_EXCEEDED, "HWP 본문 압축 해제 데이터가 안전 한도를 넘어 일부만 읽었어요.", recoverable = true)
                break
            }
            paragraphs += readParagraphTextRecords(body, textBudget, issues)
            if (textBudget.exceeded) break
        }
        textBudget.issueIfExceeded("문서 본문이 안전 출력 한도를 넘어 일부만 읽었어요.")?.let { issues += it }
        return DocumentTextExtractor.complete(
            format = DocumentFormat.HWP5,
            paragraphs = paragraphs,
            issues = issues,
            evidence = listOf("HWP5 FileHeader", "BodyText/Section streams: ${streams.size}"),
        )
    }

    private fun readParagraphTextRecords(
        bytes: ByteArray,
        textBudget: DocumentTextBudget,
        issues: MutableList<DocumentTextIssue>,
    ): List<String> {
        val paragraphs = mutableListOf<String>()
        var offset = 0
        while (offset + 4 <= bytes.size && !textBudget.exceeded) {
            val header = bytes.i32(offset)
            offset += 4
            val tagId = header and 0x3FF
            var size = (header ushr 20) and 0xFFF
            if (size == 0xFFF) {
                if (bytes.size - offset < 4) {
                    issues += DocumentTextIssue(DocumentIssueCode.INVALID_CONTAINER, "HWP record extended header가 완전하지 않아요.", recoverable = true)
                    break
                }
                size = bytes.i32(offset)
                offset += 4
            }
            if (size < 0 || size > bytes.size - offset) {
                issues += DocumentTextIssue(DocumentIssueCode.INVALID_CONTAINER, "HWP record 크기가 본문 범위를 벗어났어요.", recoverable = true)
                break
            }
            if (tagId == HWP_TAG_PARA_TEXT) {
                val text = visibleHwpText(bytes, offset, size)
                val accepted = textBudget.accept(text)
                if (accepted.isNotBlank()) {
                    paragraphs += accepted
                }
            }
            offset += size
        }
        if (offset < bytes.size && !textBudget.exceeded) {
            issues += DocumentTextIssue(DocumentIssueCode.INVALID_CONTAINER, "HWP record stream 끝에 불완전한 record 바이트가 있어요.", recoverable = true)
        }
        return paragraphs
    }

    private fun visibleHwpText(bytes: ByteArray, offset: Int, size: Int): String {
        val safeSize = size - (size % 2)
        val decoded = String(bytes, offset, safeSize, utf16le)
        val builder = StringBuilder(decoded.length)
        decoded.forEach { ch ->
            when {
                ch == '\t' -> builder.append('\t')
                ch == '\n' || ch == '\r' -> builder.append('\n')
                ch.code < 32 -> Unit
                Character.isISOControl(ch) -> Unit
                else -> builder.append(ch)
            }
        }
        return builder.toString().replace(Regex("""[ \t]+\n"""), "\n").replace(Regex("""\n{3,}"""), "\n\n").trim()
    }

    private fun inflateRaw(bytes: ByteArray, maxBytes: Int): InflateResult {
        val inflater = Inflater(true)
        val out = ByteArrayOutputStream(minOf(bytes.size * 3, 64 * 1024, maxBytes))
        val buffer = ByteArray(8192)
        inflater.setInput(bytes)
        try {
            while (!inflater.finished()) {
                val count = inflater.inflate(buffer)
                if (count == 0) {
                    if (inflater.needsInput()) throw DataFormatException("Raw deflate stream ended before the final block.")
                    if (inflater.needsDictionary()) throw DataFormatException("Raw deflate stream requires a dictionary.")
                    throw DataFormatException("Raw deflate stream made no progress.")
                } else {
                    if (out.size() + count > maxBytes) {
                        throw OleParseException(DocumentIssueCode.CONTENT_LIMIT_EXCEEDED, "Inflated HWP stream exceeded the safety limit.")
                    }
                    out.write(buffer, 0, count)
                }
            }
            if (!inflater.finished()) {
                throw DataFormatException("Raw deflate stream did not finish.")
            }
            return InflateResult(out.toByteArray(), inflater.remaining)
        } finally {
            inflater.end()
        }
    }

    private fun sectionNumber(path: String): Int? =
        Regex("""Section(\d+)""", RegexOption.IGNORE_CASE).find(path)?.groupValues?.get(1)?.toIntOrNull()

    private data class InflateResult(
        val bytes: ByteArray,
        val trailingBytes: Int,
    )
}
