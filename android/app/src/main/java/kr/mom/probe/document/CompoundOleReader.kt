package kr.mom.probe.document

import java.io.ByteArrayOutputStream
import java.nio.charset.Charset
import kotlin.math.min

internal class CompoundOleReader private constructor(
    private val bytes: ByteArray,
    private val sectorSize: Int,
    private val miniSectorSize: Int,
    private val miniStreamCutoff: Int,
    private val firstDirectorySector: Int,
    private val firstMiniFatSector: Int,
    private val miniFatSectorCount: Int,
    private val fat: IntArray,
) {
    private val utf16le = Charset.forName("UTF-16LE")
    private val entries: List<DirectoryEntry> by lazy { readDirectoryEntries() }
    private val rootEntry: DirectoryEntry? by lazy { entries.firstOrNull { it.type == OBJECT_ROOT } }
    private val miniFat: IntArray by lazy { readMiniFat() }
    private val miniStream: ByteArray by lazy {
        rootEntry?.let { readStandardStream(it.startSector, min(it.size, DocumentTextLimits.MAX_STREAM_BYTES.toLong())) } ?: ByteArray(0)
    }

    fun listStreams(): List<OleStream> {
        val root = entries.firstOrNull() ?: return emptyList()
        val out = mutableListOf<OleStream>()
        val visited = mutableSetOf<Int>()
        walkSiblings(root.childId, emptyList(), visited, out, depth = 0)
        if (out.isEmpty()) {
            entries.filter { it.type == OBJECT_STREAM }.forEach {
                out += OleStream(it.id, it.name, it.name, it.size)
            }
        }
        return out
    }

    fun readStream(path: String): ByteArray? {
        val stream = listStreams().firstOrNull { it.path.equals(path, ignoreCase = true) || it.name.equals(path, ignoreCase = true) } ?: return null
        val entry = entries.firstOrNull { it.id == stream.id && it.type == OBJECT_STREAM } ?: return null
        return if (entry.size < miniStreamCutoff && entry.startSector >= 0) {
            readMiniStream(entry.startSector, entry.size)
        } else {
            readStandardStream(entry.startSector, entry.size)
        }
    }

    private fun walkSiblings(
        id: Int,
        parentPath: List<String>,
        visited: MutableSet<Int>,
        out: MutableList<OleStream>,
        depth: Int,
    ) {
        if (id == NO_STREAM) return
        if (id !in entries.indices) throw OleParseException(DocumentIssueCode.INVALID_CONTAINER, "OLE directory tree references an unknown entry.")
        if (depth > DocumentTextLimits.MAX_DIRECTORY_DEPTH) throw OleParseException(DocumentIssueCode.CONTENT_LIMIT_EXCEEDED, "OLE directory tree exceeded the safety depth.")
        if (!visited.add(id)) throw OleParseException(DocumentIssueCode.CYCLE_DETECTED, "OLE directory tree contains a cycle.")
        if (visited.size > DocumentTextLimits.MAX_DIRECTORY_ENTRIES) throw OleParseException(DocumentIssueCode.CONTENT_LIMIT_EXCEEDED, "OLE directory tree exceeded the entry limit.")
        val entry = entries[id]
        walkSiblings(entry.leftSiblingId, parentPath, visited, out, depth + 1)
        when (entry.type) {
            OBJECT_STORAGE -> {
                val path = parentPath + entry.name
                walkSiblings(entry.childId, path, visited, out, depth + 1)
            }
            OBJECT_STREAM -> {
                val path = (parentPath + entry.name).joinToString("/")
                out += OleStream(entry.id, path, entry.name, entry.size)
            }
        }
        walkSiblings(entry.rightSiblingId, parentPath, visited, out, depth + 1)
    }

    private fun readDirectoryEntries(): List<DirectoryEntry> {
        val data = readStandardChain(
            startSector = firstDirectorySector,
            expectedSectors = DocumentTextLimits.MAX_DIRECTORY_ENTRIES * 128 / sectorSize,
            allocator = fat,
            unitSize = sectorSize,
            allowShortChain = true,
        ) { sectorOffset(it) }
        return data.asList().chunked(128).mapIndexedNotNull { index, chunk ->
            if (chunk.size < 128) return@mapIndexedNotNull null
            val entryBytes = chunk.toByteArray()
            val nameLength = entryBytes.u16(64).coerceIn(0, 64)
            val name = if (nameLength >= 2) {
                String(entryBytes, 0, nameLength - 2, utf16le).trimEnd('\u0000')
            } else {
                ""
            }
            val type = entryBytes[66].toInt() and 0xFF
            if (type == OBJECT_EMPTY) return@mapIndexedNotNull DirectoryEntry(index, name, type, NO_STREAM, NO_STREAM, NO_STREAM, -1, 0)
            DirectoryEntry(
                id = index,
                name = name,
                type = type,
                leftSiblingId = entryBytes.sid(68),
                rightSiblingId = entryBytes.sid(72),
                childId = entryBytes.sid(76),
                startSector = entryBytes.i32(116),
                size = entryBytes.u32(120),
            )
        }
    }

    private fun readMiniFat(): IntArray {
        if (firstMiniFatSector < 0 || miniFatSectorCount <= 0) return IntArray(0)
        val bytes = readStandardChain(firstMiniFatSector, miniFatSectorCount, fat, sectorSize) { sectorOffset(it) }
        return IntArray(bytes.size / 4) { bytes.i32(it * 4) }
    }

    private fun readStandardStream(startSector: Int, size: Long): ByteArray {
        rejectOversizedStream(size)
        return readStandardChain(startSector, sectorsFor(size, sectorSize), fat, sectorSize) { sectorOffset(it) }
            .trimToDeclaredSize(size)
    }

    private fun readMiniStream(startSector: Int, size: Long): ByteArray {
        rejectOversizedStream(size)
        if (miniFat.isEmpty() || miniStream.isEmpty()) return ByteArray(0)
        return readStandardChain(startSector, sectorsFor(size, miniSectorSize), miniFat, miniSectorSize) { sector ->
            val offset = sector.toLong() * miniSectorSize
            if (offset < 0 || offset + miniSectorSize > miniStream.size) -1 else offset.toInt()
        }.trimToDeclaredSize(size)
    }

    private fun readStandardChain(
        startSector: Int,
        expectedSectors: Int,
        allocator: IntArray,
        unitSize: Int,
        allowShortChain: Boolean = false,
        offsetForSector: (Int) -> Int,
    ): ByteArray {
        if (startSector < 0) return ByteArray(0)
        if (expectedSectors <= 0 || expectedSectors > DocumentTextLimits.MAX_CHAIN_SECTORS) {
            throw OleParseException(DocumentIssueCode.CONTENT_LIMIT_EXCEEDED, "OLE sector chain size exceeded the safety limit.")
        }
        val maxBytes = expectedSectors.toLong() * unitSize
        if (maxBytes > DocumentTextLimits.MAX_STREAM_BYTES.toLong() && unitSize != sectorSize) {
            throw OleParseException(DocumentIssueCode.CONTENT_LIMIT_EXCEEDED, "OLE mini stream exceeded the safety limit.")
        }
        val out = ByteArrayOutputStream(min(maxBytes, DocumentTextLimits.MAX_STREAM_BYTES.toLong()).toInt())
        val seen = mutableSetOf<Int>()
        var sector = startSector
        var count = 0
        while (sector >= 0 && sector != END_OF_CHAIN) {
            if (sector >= allocator.size) {
                throw OleParseException(DocumentIssueCode.INVALID_CONTAINER, "OLE sector chain references an unknown sector.")
            }
            if (!seen.add(sector)) throw OleParseException(DocumentIssueCode.CYCLE_DETECTED, "OLE sector chain contains a cycle.")
            if (count >= expectedSectors) throw OleParseException(DocumentIssueCode.CONTENT_LIMIT_EXCEEDED, "OLE sector chain exceeded the safety limit.")
            val offset = offsetForSector(sector)
            if (offset < 0 || (unitSize == sectorSize && offset + unitSize > bytes.size)) {
                throw OleParseException(DocumentIssueCode.INVALID_CONTAINER, "OLE sector points outside the file.")
            }
            val source = if (unitSize == sectorSize) bytes else miniStream
            if (offset < 0 || offset >= source.size) throw OleParseException(DocumentIssueCode.INVALID_CONTAINER, "OLE sector points outside the stream.")
            val end = min(offset + unitSize, source.size)
            out.write(source, offset, end - offset)
            sector = allocator[sector]
            count++
        }
        if (!allowShortChain && count < expectedSectors) {
            throw OleParseException(DocumentIssueCode.INVALID_CONTAINER, "OLE sector chain ended before the declared stream size.")
        }
        if (sector != END_OF_CHAIN) {
            throw OleParseException(DocumentIssueCode.INVALID_CONTAINER, "OLE sector chain ended with an invalid marker.")
        }
        return out.toByteArray()
    }

    private fun sectorOffset(sector: Int): Int {
        val offset = 512L + sector.toLong() * sectorSize
        if (offset < 0 || offset > Int.MAX_VALUE) return -1
        return offset.toInt()
    }

    private fun sectorsFor(size: Long, unitSize: Int): Int {
        return ((size + unitSize - 1) / unitSize).toInt().coerceAtLeast(1)
    }

    private fun ByteArray.trimToDeclaredSize(size: Long): ByteArray {
        val declared = size.toInt()
        return if (this.size <= declared) this else copyOf(declared)
    }

    private fun rejectOversizedStream(size: Long) {
        if (size > DocumentTextLimits.MAX_STREAM_BYTES) {
            throw OleParseException(DocumentIssueCode.CONTENT_LIMIT_EXCEEDED, "OLE stream declares more bytes than the extractor limit.")
        }
    }

    data class OleStream(
        val id: Int,
        val path: String,
        val name: String,
        val size: Long,
    )

    private data class DirectoryEntry(
        val id: Int,
        val name: String,
        val type: Int,
        val leftSiblingId: Int,
        val rightSiblingId: Int,
        val childId: Int,
        val startSector: Int,
        val size: Long,
    )

    companion object {
        private val MAGIC = byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(), 0xA1.toByte(), 0xB1.toByte(), 0x1A, 0xE1.toByte())
        private const val FREE_SECTOR = -1
        private const val END_OF_CHAIN = -2
        private const val DIFAT_SECTOR = -4
        private const val NO_STREAM = -1
        private const val OBJECT_EMPTY = 0
        private const val OBJECT_STORAGE = 1
        private const val OBJECT_STREAM = 2
        private const val OBJECT_ROOT = 5

        fun parse(bytes: ByteArray): CompoundOleReader {
            if (bytes.size < 1536 || !bytes.startsWith(MAGIC)) {
                throw OleParseException(DocumentIssueCode.INVALID_CONTAINER, "OLE header signature is missing.")
            }
            val majorVersion = bytes.u16(26)
            val byteOrder = bytes.u16(28)
            val sectorShift = bytes.u16(30)
            val miniSectorShift = bytes.u16(32)
            if (byteOrder != 0xFFFE) throw OleParseException(DocumentIssueCode.INVALID_CONTAINER, "OLE byte order is not little-endian.")
            if (majorVersion == 4) {
                throw OleParseException(DocumentIssueCode.UNSUPPORTED_VERSION, "CFB version 4 uses 4096-byte header padding and is not supported by this extractor.")
            }
            val sectorSize = when {
                majorVersion == 3 && sectorShift == 9 -> 512
                else -> throw OleParseException(DocumentIssueCode.UNSUPPORTED_VERSION, "Unsupported OLE sector size or version.")
            }
            val physicalSectorCount = ((bytes.size - 512L) / sectorSize).toInt()
            if (physicalSectorCount < 2) throw OleParseException(DocumentIssueCode.INVALID_CONTAINER, "OLE file is too small for its sector size.")
            if (miniSectorShift != 6) throw OleParseException(DocumentIssueCode.UNSUPPORTED_VERSION, "Unsupported OLE mini sector size.")
            val numberOfFatSectors = bytes.i32(44)
            val firstDirectorySector = bytes.i32(48)
            val miniStreamCutoff = bytes.i32(56)
            val firstMiniFatSector = bytes.i32(60)
            val miniFatSectorCount = bytes.i32(64)
            val firstDifatSector = bytes.i32(68)
            val difatSectorCount = bytes.i32(72)
            if (numberOfFatSectors < 1 || numberOfFatSectors > physicalSectorCount) {
                throw OleParseException(DocumentIssueCode.INVALID_CONTAINER, "OLE FAT sector count is outside the physical file bounds.")
            }
            if (firstDirectorySector !in 0 until physicalSectorCount) {
                throw OleParseException(DocumentIssueCode.INVALID_CONTAINER, "OLE directory sector is outside the physical file bounds.")
            }
            if (miniStreamCutoff != 4096) {
                throw OleParseException(DocumentIssueCode.UNSUPPORTED_VERSION, "Unsupported OLE mini stream cutoff.")
            }
            if (miniFatSectorCount < 0 || miniFatSectorCount > physicalSectorCount) {
                throw OleParseException(DocumentIssueCode.INVALID_CONTAINER, "OLE mini FAT count is outside the physical file bounds.")
            }
            if (miniFatSectorCount > 0 && firstMiniFatSector !in 0 until physicalSectorCount) {
                throw OleParseException(DocumentIssueCode.INVALID_CONTAINER, "OLE mini FAT sector is outside the physical file bounds.")
            }
            if (difatSectorCount < 0 || difatSectorCount > physicalSectorCount) {
                throw OleParseException(DocumentIssueCode.INVALID_CONTAINER, "OLE DIFAT count is outside the physical file bounds.")
            }
            if (difatSectorCount == 0 && firstDifatSector != END_OF_CHAIN && firstDifatSector != FREE_SECTOR) {
                throw OleParseException(DocumentIssueCode.INVALID_CONTAINER, "OLE DIFAT start is set despite a zero DIFAT count.")
            }
            if (difatSectorCount > 0 && firstDifatSector !in 0 until physicalSectorCount) {
                throw OleParseException(DocumentIssueCode.INVALID_CONTAINER, "OLE DIFAT sector is outside the physical file bounds.")
            }
            val difat = mutableListOf<Int>()
            repeat(109) { index ->
                val sector = bytes.i32(76 + index * 4)
                if (sector >= 0) {
                    if (sector >= physicalSectorCount) throw OleParseException(DocumentIssueCode.INVALID_CONTAINER, "OLE DIFAT references a FAT sector outside the file.")
                    difat += sector
                }
            }
            var difatSector = firstDifatSector
            var difatRead = 0
            val visitedDifat = mutableSetOf<Int>()
            while (difatSector >= 0 && difatSector != END_OF_CHAIN && difatRead < difatSectorCount && difatRead < DocumentTextLimits.MAX_CHAIN_SECTORS) {
                if (!visitedDifat.add(difatSector)) throw OleParseException(DocumentIssueCode.CYCLE_DETECTED, "OLE DIFAT chain contains a cycle.")
                if (difatSector >= physicalSectorCount) throw OleParseException(DocumentIssueCode.INVALID_CONTAINER, "DIFAT sector points outside the file.")
                val offset = 512 + difatSector * sectorSize
                if (offset < 0 || offset + sectorSize > bytes.size) throw OleParseException(DocumentIssueCode.INVALID_CONTAINER, "DIFAT sector points outside the file.")
                val entriesPerDifatSector = sectorSize / 4 - 1
                repeat(entriesPerDifatSector) { index ->
                    val sector = bytes.i32(offset + index * 4)
                    if (sector >= 0) {
                        if (sector >= physicalSectorCount) throw OleParseException(DocumentIssueCode.INVALID_CONTAINER, "OLE DIFAT references a FAT sector outside the file.")
                        difat += sector
                    }
                }
                difatSector = bytes.i32(offset + entriesPerDifatSector * 4)
                difatRead++
            }
            if (difatRead != difatSectorCount) {
                throw OleParseException(DocumentIssueCode.INVALID_CONTAINER, "OLE DIFAT chain length does not match the header count.")
            }
            if (difatSector != END_OF_CHAIN && difatSector != FREE_SECTOR) {
                throw OleParseException(DocumentIssueCode.INVALID_CONTAINER, "OLE DIFAT chain did not terminate cleanly.")
            }
            if (difat.size < numberOfFatSectors) {
                throw OleParseException(DocumentIssueCode.INVALID_CONTAINER, "OLE header does not list enough FAT sectors.")
            }
            val fatSectorIds = difat.take(numberOfFatSectors.coerceAtLeast(0))
            if (fatSectorIds.toSet().size != fatSectorIds.size) {
                throw OleParseException(DocumentIssueCode.INVALID_CONTAINER, "OLE FAT sector list contains duplicates.")
            }
            val fatBytes = ByteArrayOutputStream(fatSectorIds.size * sectorSize)
            fatSectorIds.forEach { sector ->
                val offset = 512L + sector.toLong() * sectorSize
                if (sector < 0 || offset < 0 || offset + sectorSize > bytes.size) {
                    throw OleParseException(DocumentIssueCode.INVALID_CONTAINER, "FAT sector points outside the file.")
                }
                val start = offset.toInt()
                fatBytes.write(bytes, start, sectorSize)
            }
            val fatRaw = fatBytes.toByteArray()
            val fat = IntArray(fatRaw.size / 4) { fatRaw.i32(it * 4) }
            if (fat.isEmpty() || fat.all { it == FREE_SECTOR || it == DIFAT_SECTOR }) {
                throw OleParseException(DocumentIssueCode.INVALID_CONTAINER, "OLE FAT is empty.")
            }
            return CompoundOleReader(
                bytes = bytes,
                sectorSize = sectorSize,
                miniSectorSize = 1 shl miniSectorShift,
                miniStreamCutoff = miniStreamCutoff,
                firstDirectorySector = firstDirectorySector,
                firstMiniFatSector = firstMiniFatSector,
                miniFatSectorCount = miniFatSectorCount,
                fat = fat,
            )
        }

        private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
            size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }
    }
}

internal class OleParseException(
    val code: DocumentIssueCode,
    override val message: String,
) : RuntimeException(message)

internal fun ByteArray.u16(offset: Int): Int =
    (this[offset].toInt() and 0xFF) or ((this[offset + 1].toInt() and 0xFF) shl 8)

internal fun ByteArray.i32(offset: Int): Int =
    (this[offset].toInt() and 0xFF) or
        ((this[offset + 1].toInt() and 0xFF) shl 8) or
        ((this[offset + 2].toInt() and 0xFF) shl 16) or
        ((this[offset + 3].toInt() and 0xFF) shl 24)

internal fun ByteArray.u32(offset: Int): Long =
    i32(offset).toLong() and 0xFFFF_FFFFL

internal fun ByteArray.sid(offset: Int): Int {
    val value = i32(offset)
    return if (value == -1) -1 else value
}
