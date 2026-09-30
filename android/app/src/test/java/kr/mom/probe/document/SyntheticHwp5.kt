package kr.mom.probe.document

import java.io.ByteArrayOutputStream
import java.nio.charset.Charset
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Deterministic synthetic HWP5/HWPX builders for parser tests. They emit the
 * minimum compound-file structure CompoundOleReader and Hwp5TextExtractor
 * accept, with no real document content, licensing, or personal data.
 */
internal object SyntheticHwp5 {
    private val utf16le = Charset.forName("UTF-16LE")

    /** 공개 학교 HWP에서 관찰된 제어 블록: 제어 문자 + 6 WCHAR 데이터 + 제어 문자. */
    fun controlBlock(code: Char, id: String): String =
        "$code${id.padEnd(6, '\u0000')}$code"

    fun hwp5(text: String, flags: Int): ByteArray {
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

    /**
     * Same container as [hwp5] plus a `BinData` storage with a zero-size
     * `BIN0001.png` stream, so extraction must report PARTIAL with
     * EMBEDDED_BINARY_SKIPPED instead of depending on a real public document.
     */
    fun hwp5WithBinData(text: String, flags: Int): ByteArray {
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
        val directory = ByteArray(512).apply {
            directoryEntry(offset = 0, name = "Root Entry", type = 5, child = 2, startSector = 3, size = 512)
            directoryEntry(offset = 128, name = "FileHeader", type = 2, right = -1, startSector = 0, size = 64)
            directoryEntry(offset = 256, name = "BodyText", type = 1, left = 1, right = 4, child = 3, startSector = -1, size = 0)
            directoryEntry(offset = 384, name = "Section0", type = 2, startSector = 1, size = section.size.toLong())
        } + ByteArray(512).apply {
            directoryEntry(offset = 0, name = "BinData", type = 1, child = 5, startSector = -1, size = 0)
            directoryEntry(offset = 128, name = "BIN0001.png", type = 2, startSector = -2, size = 0)
        }
        val sectors = listOf(
            fatSectorWithDirectoryChain(),
            directory,
            miniStream,
            miniFatSector(sectionMiniSectors),
        )
        return header().apply { putIntLe(60, 4) } + sectors.reduce(ByteArray::plus)
    }

    fun compressedStandardSections(sectionBodies: List<ByteArray>): ByteArray {
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

    fun hwpx(vararg entries: Pair<String, String>): ByteArray =
        hwpxBytes(*entries.map { it.first to it.second.toByteArray(Charsets.UTF_8) }.toTypedArray())

    fun hwpxBytes(vararg entries: Pair<String, ByteArray>): ByteArray {
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

    /** FAT for the [hwp5WithBinData] layout: dir chain sector 1 -> 2. */
    private fun fatSectorWithDirectoryChain(): ByteArray = ByteArray(512) { 0xFF.toByte() }.apply {
        putIntLe(0, -3)
        putIntLe(4, 2)
        putIntLe(8, -2)
        putIntLe(12, -2)
        putIntLe(16, -2)
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
        val encodedName = (name + " ").toByteArray(utf16le)
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
}

internal fun ByteArrayOutputStream.writeIntLe(value: Int) {
    write(value and 0xFF)
    write((value ushr 8) and 0xFF)
    write((value ushr 16) and 0xFF)
    write((value ushr 24) and 0xFF)
}

internal fun ByteArrayOutputStream.writeRecordHeader(tagId: Int, size: Int) {
    if (size >= 0xFFF) {
        writeIntLe((0xFFF shl 20) or tagId)
        writeIntLe(size)
    } else {
        writeIntLe((size shl 20) or tagId)
    }
}

internal fun ByteArray.putShortLe(offset: Int, value: Int) {
    this[offset] = (value and 0xFF).toByte()
    this[offset + 1] = ((value ushr 8) and 0xFF).toByte()
}

internal fun ByteArray.putIntLe(offset: Int, value: Int) {
    this[offset] = (value and 0xFF).toByte()
    this[offset + 1] = ((value ushr 8) and 0xFF).toByte()
    this[offset + 2] = ((value ushr 16) and 0xFF).toByte()
    this[offset + 3] = ((value ushr 24) and 0xFF).toByte()
}

internal fun ByteArray.intLe(offset: Int): Int =
    (this[offset].toInt() and 0xFF) or
        ((this[offset + 1].toInt() and 0xFF) shl 8) or
        ((this[offset + 2].toInt() and 0xFF) shl 16) or
        ((this[offset + 3].toInt() and 0xFF) shl 24)
