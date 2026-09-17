package kr.mom.probe.document

enum class DocumentFormat {
    HWP5,
    HWPX,
    UNKNOWN,
}

enum class DocumentCompleteness {
    COMPLETE,
    PARTIAL,
    UNSUPPORTED,
}

enum class DocumentIssueCode {
    UNSUPPORTED_FORMAT,
    INPUT_TOO_LARGE,
    INVALID_CONTAINER,
    UNSUPPORTED_VERSION,
    PROTECTED_DOCUMENT,
    DISTRIBUTION_PROTECTED,
    CONTENT_LIMIT_EXCEEDED,
    DECOMPRESSION_FAILED,
    XML_PARSE_FAILED,
    EMBEDDED_BINARY_SKIPPED,
    NO_TEXT,
    CYCLE_DETECTED,
}

data class DocumentTextIssue(
    val code: DocumentIssueCode,
    val message: String,
    val recoverable: Boolean = false,
)

data class DocumentTypedText(
    val paragraphs: List<String>,
) {
    val plainText: String = paragraphs.joinToString("\n")
}

data class DocumentTextResult(
    val format: DocumentFormat,
    val typedText: DocumentTypedText = DocumentTypedText(emptyList()),
    val completeness: DocumentCompleteness,
    val issues: List<DocumentTextIssue> = emptyList(),
    val evidence: List<String> = emptyList(),
) {
    val text: String = typedText.plainText
}

object DocumentTextExtractor {
    private val hwpOleMagic = byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(), 0xA1.toByte(), 0xB1.toByte(), 0x1A, 0xE1.toByte())

    fun extract(bytes: ByteArray, filename: String? = null, mimeType: String? = null): DocumentTextResult {
        if (bytes.size > DocumentTextLimits.MAX_INPUT_BYTES) {
            return unsupported(
                DocumentFormat.UNKNOWN,
                DocumentIssueCode.INPUT_TOO_LARGE,
                "첨부파일이 안전 처리 한도보다 커서 본문을 읽지 않았어요.",
            )
        }
        val format = detectFormat(bytes, filename, mimeType)
        return when (format) {
            DocumentFormat.HWP5 -> Hwp5TextExtractor.extract(bytes)
            DocumentFormat.HWPX -> HwpxTextExtractor.extract(bytes)
            DocumentFormat.UNKNOWN -> unsupported(
                DocumentFormat.UNKNOWN,
                DocumentIssueCode.UNSUPPORTED_FORMAT,
                "지원하는 HWP5 또는 HWPX 첨부가 아니어서 본문을 읽지 않았어요.",
            )
        }
    }

    private fun detectFormat(bytes: ByteArray, filename: String?, mimeType: String?): DocumentFormat {
        val lowerName = filename.orEmpty().lowercase()
        val lowerMime = mimeType.orEmpty().lowercase()
        val namesHwp = lowerName.endsWith(".hwp") || lowerMime.contains("hwp") && !lowerMime.contains("hwpx")
        val namesHwpx = lowerName.endsWith(".hwpx") || lowerMime.contains("hwpx")
        return when {
            bytes.startsWith(hwpOleMagic) || namesHwp -> DocumentFormat.HWP5
            namesHwpx -> DocumentFormat.HWPX
            else -> DocumentFormat.UNKNOWN
        }
    }

    internal fun complete(format: DocumentFormat, paragraphs: List<String>, issues: List<DocumentTextIssue>, evidence: List<String>): DocumentTextResult {
        val cleaned = paragraphs.map { it.trim() }.filter { it.isNotBlank() }
        val completeness = when {
            cleaned.isEmpty() -> DocumentCompleteness.UNSUPPORTED
            issues.any {
                    it.code == DocumentIssueCode.CONTENT_LIMIT_EXCEEDED ||
                    it.code == DocumentIssueCode.CYCLE_DETECTED ||
                    it.code == DocumentIssueCode.EMBEDDED_BINARY_SKIPPED ||
                    it.code == DocumentIssueCode.DECOMPRESSION_FAILED ||
                    it.code == DocumentIssueCode.XML_PARSE_FAILED ||
                    it.code == DocumentIssueCode.INVALID_CONTAINER
            } -> DocumentCompleteness.PARTIAL
            else -> DocumentCompleteness.COMPLETE
        }
        val finalIssues = if (cleaned.isEmpty()) {
            issues + DocumentTextIssue(DocumentIssueCode.NO_TEXT, "문서에서 기계가 읽을 수 있는 본문 텍스트를 찾지 못했어요.", recoverable = true)
        } else {
            issues
        }
        return DocumentTextResult(format, DocumentTypedText(cleaned), completeness, finalIssues, evidence)
    }

    internal fun unsupported(format: DocumentFormat, code: DocumentIssueCode, message: String): DocumentTextResult =
        DocumentTextResult(
            format = format,
            completeness = DocumentCompleteness.UNSUPPORTED,
            issues = listOf(DocumentTextIssue(code, message, recoverable = true)),
        )

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
        size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }
}

internal object DocumentTextLimits {
    const val MAX_INPUT_BYTES = 5 * 1024 * 1024
    const val MAX_STREAM_BYTES = 2 * 1024 * 1024
    const val MAX_DECOMPRESSED_BYTES = 4 * 1024 * 1024
    const val MAX_TEXT_CHARS = 100_000
    const val MAX_CHAIN_SECTORS = 16_384
    const val MAX_DIRECTORY_ENTRIES = 4096
    const val MAX_DIRECTORY_DEPTH = 128
    const val MAX_ZIP_ENTRIES = 256
}

internal class DocumentTextBudget {
    private var emittedChars = 0
    var exceeded: Boolean = false
        private set

    fun remaining(): Int = (DocumentTextLimits.MAX_TEXT_CHARS - emittedChars).coerceAtLeast(0)

    fun accept(text: String): String {
        if (text.isEmpty()) return text
        val remaining = remaining()
        if (remaining <= 0) {
            exceeded = true
            return ""
        }
        if (text.length > remaining) {
            exceeded = true
            emittedChars = DocumentTextLimits.MAX_TEXT_CHARS
            return text.take(remaining)
        }
        emittedChars += text.length
        return text
    }

    fun append(chars: CharArray, start: Int, length: Int, target: StringBuilder) {
        val remaining = remaining()
        if (remaining <= 0) {
            exceeded = true
            return
        }
        val take = minOf(length, remaining)
        target.append(chars, start, take)
        emittedChars += take
        if (take < length) exceeded = true
    }

    fun issueIfExceeded(message: String): DocumentTextIssue? =
        if (exceeded) DocumentTextIssue(DocumentIssueCode.CONTENT_LIMIT_EXCEEDED, message, recoverable = true) else null
}
