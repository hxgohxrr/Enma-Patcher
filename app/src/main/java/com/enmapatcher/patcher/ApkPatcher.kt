package com.enmapatcher.patcher

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FilterOutputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.Inflater
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class ApkPatcher(private val workDir: File) {

    suspend fun applyFileReplacements(
        apkFile: File,
        patchMap: Map<String, ByteArray>,
        appName: String? = null,
        currentLabel: String? = null,
        mergeApks: List<File> = emptyList(),
        bypassZip: File? = null,
        bypassSplitNames: Set<String> = emptySet(),
        useFastPath: Boolean = true,
    ): File = withContext(Dispatchers.IO) {
        workDir.mkdirs()
        val output = File(workDir, "patched_unsigned.apk")
        if (useFastPath) {
            try {
                applyFast(
                    apkFile, patchMap, appName, currentLabel,
                    mergeApks, bypassZip, bypassSplitNames, output,
                )
            } catch (_: Exception) {
                applyLegacy(
                    apkFile, patchMap, appName, currentLabel,
                    mergeApks, bypassZip, bypassSplitNames, output,
                )
            }
        } else {
            applyLegacy(
                apkFile, patchMap, appName, currentLabel,
                mergeApks, bypassZip, bypassSplitNames, output,
            )
        }
        output
    }

    private fun applyFast(
        apkFile: File,
        patchMap: Map<String, ByteArray>,
        appName: String?,
        currentLabel: String?,
        mergeApks: List<File>,
        bypassZip: File?,
        bypassSplitNames: Set<String>,
        output: File,
    ) {
        val base = FastZipReader(apkFile)
        try {
            val splits = mergeApks.map { FastZipReader(it) }
            val bypass = bypassZip?.let { FastZipReader(it) }
            try {
                val counting = CountingOutputStream(output.outputStream().buffered(BUFFER))
                val central = ArrayList<FastCentralEntry>()
                val written = mutableSetOf<String>()
                for (entry in base.entries) {
                    val name = entry.name
                    val patchBytes = patchMap[name]
                    if (patchBytes == null && name in bypassSplitNames) continue
                    written += name
                    when {
                        name == "AndroidManifest.xml" && mergeApks.isNotEmpty() -> {
                            val raw = patchBytes ?: base.readData(entry)
                            writeFreshDeflated(counting, central, name, removeSplitRequirements(raw), entry.dosTime, entry.dosDate)
                        }
                        patchBytes != null -> {
                            writeFreshDeflated(counting, central, name, patchBytes, entry.dosTime, entry.dosDate)
                        }
                        name == "resources.arsc" && appName != null && currentLabel != null -> {
                            val patched = AppNamePatcher.patch(base.readData(entry), currentLabel, appName)
                            writeFreshStored(counting, central, name, patched, entry.dosTime, entry.dosDate)
                        }
                        name == "resources.arsc" -> {
                            writeFreshStored(counting, central, name, base.readData(entry), entry.dosTime, entry.dosDate)
                        }
                        entry.hasDescriptor -> {
                            writeFreshDeflated(counting, central, name, base.readData(entry), entry.dosTime, entry.dosDate)
                        }
                        else -> {
                            copyVerbatim(base, entry, counting, central)
                        }
                    }
                }
                for ((path, bytes) in patchMap) {
                    if (path !in written) {
                        writeFreshDeflated(counting, central, path, bytes, dosNow(), dosNowDate())
                        written += path
                    }
                }
                if (bypass != null) {
                    for (entry in bypass.entries) {
                        val raw = entry.name
                        if (!raw.startsWith("split/") || raw.endsWith("/")) continue
                        val name = raw.removePrefix("split/")
                        if (name == "AndroidManifest.xml") continue
                        if (name.startsWith("META-INF/")) continue
                        if (name in written) continue
                        val patchBytes = patchMap[name]
                        if (patchBytes != null) {
                            writeFreshDeflated(counting, central, name, patchBytes, entry.dosTime, entry.dosDate)
                        } else if (entry.hasDescriptor) {
                            writeFreshDeflated(counting, central, name, bypass.readData(entry), entry.dosTime, entry.dosDate)
                        } else {
                            copyVerbatim(bypass, entry, counting, central, name)
                        }
                        written += name
                    }
                }
                for (splitApk in splits) {
                    for (entry in splitApk.entries) {
                        val name = entry.name
                        if (name == "AndroidManifest.xml") continue
                        if (name.startsWith("META-INF/")) continue
                        if (name in written) continue
                        val patchBytes = patchMap[name]
                        if (patchBytes != null) {
                            writeFreshDeflated(counting, central, name, patchBytes, entry.dosTime, entry.dosDate)
                        } else if (entry.method == ZipEntry.STORED && !entry.hasDescriptor) {
                            copyVerbatim(splitApk, entry, counting, central, name)
                        } else if (entry.hasDescriptor) {
                            writeFreshDeflated(counting, central, name, splitApk.readData(entry), entry.dosTime, entry.dosDate)
                        } else {
                            copyVerbatim(splitApk, entry, counting, central, name)
                        }
                        written += name
                    }
                }
                counting.flush()
                writeCentralDirectory(counting, central, base.comment)
                counting.flush()
                counting.close()
            } finally {
                splits.forEach { runCatching { it.close() } }
                bypass?.let { runCatching { it.close() } }
            }
        } finally {
            runCatching { base.close() }
        }
    }

    private fun copyVerbatim(
        reader: FastZipReader,
        entry: FastZipReader.ZipEntryInfo,
        out: CountingOutputStream,
        central: MutableList<FastCentralEntry>,
        outName: String = entry.name,
    ) {
        val start = out.count
        reader.copyRaw(entry, out)
        central += FastCentralEntry(
            name = outName,
            method = entry.method,
            crc = entry.crc,
            compSize = entry.compSize,
            size = entry.size,
            localOffset = start,
            extra = entry.extra,
            externalAttrs = entry.externalAttrs,
            dosTime = entry.dosTime,
            dosDate = entry.dosDate,
            hasDescriptor = false,
        )
    }

    private fun writeFreshDeflated(
        out: CountingOutputStream,
        central: MutableList<FastCentralEntry>,
        name: String,
        bytes: ByteArray,
        dosTime: Int,
        dosDate: Int,
    ) {
        val nameBytes = name.toByteArray(Charsets.UTF_8)
        val utf8 = nameBytes.size != name.length || name.any { it.code > 127 }
        var flags = 0x08
        if (utf8) flags = flags or 0x0800
        val start = out.count
        writeLocalHeader(out, nameBytes, ByteArray(0), ZipEntry.DEFLATED, flags, dosTime, dosDate, 0, 0, 0)
        val crc = CRC32()
        val deflater = Deflater(Deflater.BEST_SPEED, true)
        var compSize = 0L
        val buf = ByteArray(65536)
        val outBuf = ByteArray(65536)
        try {
            var off = 0
            while (off < bytes.size) {
                val chunk = minOf(65536, bytes.size - off)
                crc.update(bytes, off, chunk)
                deflater.setInput(bytes, off, chunk)
                while (!deflater.needsInput()) {
                    val n = deflater.deflate(outBuf)
                    if (n <= 0) break
                    out.write(outBuf, 0, n)
                    compSize += n
                }
                off += chunk
            }
            deflater.finish()
            while (!deflater.finished()) {
                val n = deflater.deflate(outBuf)
                if (n <= 0) break
                out.write(outBuf, 0, n)
                compSize += n
            }
        } finally {
            deflater.end()
        }
        writeDataDescriptor(out, crc.value, compSize, bytes.size.toLong())
        central += FastCentralEntry(
            name = name,
            method = ZipEntry.DEFLATED,
            crc = crc.value,
            compSize = compSize,
            size = bytes.size.toLong(),
            localOffset = start,
            extra = ByteArray(0),
            externalAttrs = 0,
            dosTime = dosTime,
            dosDate = dosDate,
            hasDescriptor = true,
        )
    }

    private fun writeFreshStored(
        out: CountingOutputStream,
        central: MutableList<FastCentralEntry>,
        name: String,
        bytes: ByteArray,
        dosTime: Int,
        dosDate: Int,
    ) {
        val crc = CRC32().apply { update(bytes) }.value
        val aligned = storedAligned(name, bytes.size.toLong(), crc, out)
        val extra = aligned.extra ?: ByteArray(0)
        val start = out.count
        writeLocalHeader(out, name.toByteArray(Charsets.UTF_8), extra, ZipEntry.STORED, 0, dosTime, dosDate, crc, bytes.size.toLong(), bytes.size.toLong())
        out.write(bytes)
        central += FastCentralEntry(
            name = name,
            method = ZipEntry.STORED,
            crc = crc,
            compSize = bytes.size.toLong(),
            size = bytes.size.toLong(),
            localOffset = start,
            extra = extra,
            externalAttrs = 0,
            dosTime = dosTime,
            dosDate = dosDate,
            hasDescriptor = false,
        )
    }

    private fun writeLocalHeader(
        out: OutputStream,
        nameBytes: ByteArray,
        extra: ByteArray,
        method: Int,
        flags: Int,
        dosTime: Int,
        dosDate: Int,
        crc: Long,
        compSize: Long,
        size: Long,
    ) {
        val buf = ByteBuffer.allocate(30).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(0x04034b50)
        buf.putShort(20)
        buf.putShort(flags.toShort())
        buf.putShort(method.toShort())
        buf.putShort(dosTime.toShort())
        buf.putShort(dosDate.toShort())
        buf.putInt(crc.toInt())
        buf.putInt(compSize.toInt())
        buf.putInt(size.toInt())
        buf.putShort(nameBytes.size.toShort())
        buf.putShort(extra.size.toShort())
        out.write(buf.array())
        out.write(nameBytes)
        out.write(extra)
    }

    private fun writeDataDescriptor(out: OutputStream, crc: Long, compSize: Long, size: Long) {
        val buf = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(0x08074b50)
        buf.putInt(crc.toInt())
        buf.putInt(compSize.toInt())
        buf.putInt(size.toInt())
        out.write(buf.array())
    }

    private fun writeCentralDirectory(
        out: OutputStream,
        central: List<FastCentralEntry>,
        comment: ByteArray,
    ) {
        val cdStart = (out as CountingOutputStream).count
        for (e in central) {
            val nameBytes = e.name.toByteArray(Charsets.UTF_8)
            val utf8 = nameBytes.size != e.name.length || e.name.any { it.code > 127 }
            var flags = 0
            if (e.hasDescriptor) flags = flags or 0x08
            if (utf8) flags = flags or 0x0800
            val buf = ByteBuffer.allocate(46).order(ByteOrder.LITTLE_ENDIAN)
            buf.putInt(0x02014b50)
            buf.putShort(20)
            buf.putShort(20)
            buf.putShort(flags.toShort())
            buf.putShort(e.method.toShort())
            buf.putShort(e.dosTime.toShort())
            buf.putShort(e.dosDate.toShort())
            buf.putInt(e.crc.toInt())
            buf.putInt(e.compSize.toInt())
            buf.putInt(e.size.toInt())
            buf.putShort(nameBytes.size.toShort())
            buf.putShort(e.extra.size.toShort())
            buf.putShort(0)
            buf.putShort(0)
            buf.putShort(0)
            buf.putInt(e.externalAttrs.toInt())
            buf.putInt(e.localOffset.toInt())
            out.write(buf.array())
            out.write(nameBytes)
            out.write(e.extra)
        }
        val cdEnd = (out as CountingOutputStream).count
        val end = ByteBuffer.allocate(22).order(ByteOrder.LITTLE_ENDIAN)
        end.putInt(0x06054b50)
        end.putShort(0)
        end.putShort(0)
        end.putShort(central.size.toShort())
        end.putShort(central.size.toShort())
        end.putInt((cdEnd - cdStart).toInt())
        end.putInt(cdStart.toInt())
        end.putShort(comment.size.toShort())
        out.write(end.array())
        out.write(comment)
    }

    private fun dosNow(): Int {
        val cal = java.util.Calendar.getInstance()
        return (cal.get(java.util.Calendar.HOUR_OF_DAY) shl 11) or
            (cal.get(java.util.Calendar.MINUTE) shl 5) or
            (cal.get(java.util.Calendar.SECOND) / 2)
    }

    private fun dosNowDate(): Int {
        val cal = java.util.Calendar.getInstance()
        return ((cal.get(java.util.Calendar.YEAR) - 1980) shl 9) or
            ((cal.get(java.util.Calendar.MONTH) + 1) shl 5) or
            cal.get(java.util.Calendar.DAY_OF_MONTH)
    }

    private data class FastCentralEntry(
        val name: String,
        val method: Int,
        val crc: Long,
        val compSize: Long,
        val size: Long,
        val localOffset: Long,
        val extra: ByteArray,
        val externalAttrs: Long,
        val dosTime: Int,
        val dosDate: Int,
        val hasDescriptor: Boolean,
    )

    private class FastZipReader(val file: File) {
        data class ZipEntryInfo(
            val name: String,
            val method: Int,
            val crc: Long,
            val compSize: Long,
            val size: Long,
            val localOffset: Long,
            val extra: ByteArray,
            val externalAttrs: Long,
            val dosTime: Int,
            val dosDate: Int,
            val hasDescriptor: Boolean,
        )

        val entries: List<ZipEntryInfo>
        val comment: ByteArray
        private val raf = RandomAccessFile(file, "r")

        init {
            val len = raf.length()
            val tailSize = minOf(len, 70000L).toInt()
            val tail = ByteArray(tailSize)
            raf.seek(len - tailSize)
            raf.readFully(tail)
            var endPos = -1
            var i = tailSize - 22
            while (i >= 0) {
                if (tail[i] == 0x50.toByte() && tail[i + 1] == 0x4b.toByte() &&
                    tail[i + 2] == 0x05.toByte() && tail[i + 3] == 0x06.toByte()
                ) {
                    endPos = i
                    break
                }
                i--
            }
            if (endPos < 0) throw IllegalStateException("EndNotFound")
            val end = ByteBuffer.wrap(tail, endPos, 22).order(ByteOrder.LITTLE_ENDIAN)
            end.getInt()
            val disk = end.getShort().toInt() and 0xFFFF
            val cdDisk = end.getShort().toInt() and 0xFFFF
            val cdCountDisk = end.getShort().toInt() and 0xFFFF
            val cdCount = end.getShort().toInt() and 0xFFFF
            end.getInt()
            val cdOffsetRaw = end.getInt().toLong() and 0xFFFFFFFFL
            val commentLen = end.getShort().toInt() and 0xFFFF
            if (disk != 0 || cdDisk != 0 || cdCountDisk == 0xFFFF || cdCount == 0xFFFF ||
                cdOffsetRaw == 0xFFFFFFFFL
            ) {
                throw IllegalStateException("UnsupportedZip")
            }
            comment = if (commentLen > 0) {
                tail.copyOfRange(endPos + 22, endPos + 22 + commentLen)
            } else {
                ByteArray(0)
            }
            val list = ArrayList<ZipEntryInfo>(cdCount)
            raf.seek(cdOffsetRaw)
            repeat(cdCount) {
                val head = ByteArray(46)
                raf.readFully(head)
                val b = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN)
                if (b.getInt() != 0x02014b50) throw IllegalStateException("CentralCorrupt")
                b.getShort()
                b.getShort()
                val flags = b.getShort().toInt() and 0xFFFF
                val method = b.getShort().toInt() and 0xFFFF
                val dosTime = b.getShort().toInt() and 0xFFFF
                val dosDate = b.getShort().toInt() and 0xFFFF
                val crc = b.getInt().toLong() and 0xFFFFFFFFL
                val compSize = b.getInt().toLong() and 0xFFFFFFFFL
                val size = b.getInt().toLong() and 0xFFFFFFFFL
                val nameLen = b.getShort().toInt() and 0xFFFF
                val extraLen = b.getShort().toInt() and 0xFFFF
                val commentLenEntry = b.getShort().toInt() and 0xFFFF
                b.getShort()
                b.getShort()
                val extAttrs = b.getInt().toLong() and 0xFFFFFFFFL
                val localOffRaw = b.getInt().toLong() and 0xFFFFFFFFL
                if (localOffRaw == 0xFFFFFFFFL) throw IllegalStateException("UnsupportedZip")
                val nameBytes = ByteArray(nameLen)
                raf.readFully(nameBytes)
                val extra = ByteArray(extraLen)
                if (extraLen > 0) raf.readFully(extra)
                if (commentLenEntry > 0) raf.skipBytes(commentLenEntry)
                val name = if (flags and 0x0800 != 0) {
                    nameBytes.toString(Charsets.UTF_8)
                } else {
                    nameBytes.toString(Charsets.ISO_8859_1)
                }
                list += ZipEntryInfo(
                    name = name.replace('\\', '/'),
                    method = method,
                    crc = crc,
                    compSize = compSize,
                    size = size,
                    localOffset = localOffRaw,
                    extra = extra,
                    externalAttrs = extAttrs,
                    dosTime = dosTime,
                    dosDate = dosDate,
                    hasDescriptor = flags and 0x08 != 0,
                )
            }
            entries = list
        }

        fun readData(entry: ZipEntryInfo): ByteArray {
            raf.seek(entry.localOffset)
            val head = ByteArray(30)
            raf.readFully(head)
            val b = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN)
            if (b.getInt() != 0x04034b50) throw IllegalStateException("LocalCorrupt")
            b.position(26)
            val nameLen = b.getShort().toInt() and 0xFFFF
            val extraLen = b.getShort().toInt() and 0xFFFF
            raf.skipBytes(nameLen + extraLen)
            if (entry.method == ZipEntry.STORED) {
                if (entry.size > Int.MAX_VALUE) throw IllegalStateException("TooLarge")
                val out = ByteArray(entry.size.toInt())
                raf.readFully(out)
                return out
            }
            val inflater = Inflater(true)
            try {
                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(65536)
                val inBuf = ByteArray(65536)
                var remaining = entry.compSize
                val small = ByteArray(1)
                while (remaining > 0 || !inflater.finished()) {
                    if (inflater.needsInput() && remaining > 0) {
                        val n = raf.read(inBuf, 0, minOf(inBuf.size.toLong(), remaining).toInt())
                        if (n <= 0) break
                        inflater.setInput(inBuf, 0, n)
                        remaining -= n
                    }
                    val n = inflater.inflate(buf)
                    if (n > 0) {
                        out.write(buf, 0, n)
                    } else if (inflater.finished() || inflater.needsDictionary()) {
                        break
                    } else if (remaining <= 0) {
                        val extra = inflater.inflate(small)
                        if (extra <= 0) break
                        out.write(small, 0, extra)
                    }
                }
                return out.toByteArray()
            } finally {
                inflater.end()
            }
        }

        fun copyRaw(entry: ZipEntryInfo, out: OutputStream) {
            raf.seek(entry.localOffset)
            val head = ByteArray(30)
            raf.readFully(head)
            val b = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN)
            if (b.getInt() != 0x04034b50) throw IllegalStateException("LocalCorrupt")
            b.position(26)
            val nameLen = b.getShort().toInt() and 0xFFFF
            val extraLen = b.getShort().toInt() and 0xFFFF
            val headerLen = 30L + nameLen + extraLen
            raf.seek(entry.localOffset)
            var remaining = headerLen + entry.compSize
            val buf = ByteArray(65536)
            val crc = CRC32()
            while (remaining > 0) {
                val n = raf.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                if (n <= 0) throw IllegalStateException("Truncated")
                val dataStart = remaining - entry.compSize
                if (dataStart < n) {
                    val from = maxOf(0L, dataStart).toInt()
                    crc.update(buf, from, n - from)
                }
                out.write(buf, 0, n)
                remaining -= n
            }
            if (entry.method != ZipEntry.STORED || entry.compSize > 0) {
                if (crc.value != entry.crc) throw IllegalStateException("CrcMismatch:" + entry.name)
            }
        }

        fun close() {
            runCatching { raf.close() }
        }
    }

    private fun applyLegacy(
        apkFile: File,
        patchMap: Map<String, ByteArray>,
        appName: String?,
        currentLabel: String?,
        mergeApks: List<File>,
        bypassZip: File?,
        bypassSplitNames: Set<String>,
        output: File,
    ) {
        val counting = CountingOutputStream(output.outputStream().buffered(BUFFER))
        ZipOutputStream(counting).use { zos ->
            zos.setLevel(Deflater.BEST_SPEED)

            ZipFile(apkFile).use { source ->
                val originalNames = mutableSetOf<String>()

                for (entry in source.entries()) {
                    val name = entry.name.replace('\\', '/')
                    val patchBytes = patchMap[name]

                    if (patchBytes == null && name in bypassSplitNames) continue

                    originalNames += name
                    when {
                        name == "AndroidManifest.xml" && mergeApks.isNotEmpty() -> {
                            val raw = patchBytes ?: source.getInputStream(entry).readBytes()
                            zos.putNextEntry(ZipEntry(name))
                            zos.write(removeSplitRequirements(raw))
                        }
                        patchBytes != null -> {
                            zos.putNextEntry(ZipEntry(name))
                            zos.write(patchBytes)
                        }
                        name == "resources.arsc" && appName != null && currentLabel != null -> {
                            val original = source.getInputStream(entry).readBytes()
                            val patched = AppNamePatcher.patch(original, currentLabel, appName)
                            val crc = CRC32().apply { update(patched) }.value
                            zos.putNextEntry(storedAligned(name, patched.size.toLong(), crc, counting))
                            zos.write(patched)
                        }
                        name == "resources.arsc" -> {
                            val bytes = source.getInputStream(entry).readBytes()
                            val crc = if (entry.method == ZipEntry.STORED) entry.crc
                                      else CRC32().apply { update(bytes) }.value
                            zos.putNextEntry(storedAligned(name, bytes.size.toLong(), crc, counting))
                            zos.write(bytes)
                        }
                        entry.method == ZipEntry.STORED -> {
                            zos.putNextEntry(storedAligned(name, entry.size, entry.crc, counting))
                            source.getInputStream(entry).use { it.copyTo(zos, bufferSize = BUFFER) }
                        }
                        else -> {
                            zos.putNextEntry(ZipEntry(name))
                            source.getInputStream(entry).use { it.copyTo(zos, bufferSize = BUFFER) }
                        }
                    }
                    zos.closeEntry()
                }

                patchMap.forEach { (path, bytes) ->
                    if (path !in originalNames) {
                        zos.putNextEntry(ZipEntry(path))
                        zos.write(bytes)
                        zos.closeEntry()
                        originalNames += path
                    }
                }

                if (bypassZip != null) {
                    ZipFile(bypassZip).use { bzip ->
                        for (entry in bzip.entries()) {
                            val raw = entry.name.replace('\\', '/')
                            if (!raw.startsWith("split/") || entry.isDirectory) continue
                            val name = raw.removePrefix("split/")
                            if (name == "AndroidManifest.xml") continue
                            if (name.startsWith("META-INF/")) continue
                            if (name in originalNames) continue
                            val patchBytes = patchMap[name]
                            if (patchBytes != null) {
                                zos.putNextEntry(ZipEntry(name))
                                zos.write(patchBytes)
                            } else {
                                zos.putNextEntry(storedAligned(name, entry.size, entry.crc, counting))
                                bzip.getInputStream(entry).use { it.copyTo(zos, bufferSize = BUFFER) }
                            }
                            zos.closeEntry()
                            originalNames += name
                        }
                    }
                }

                for (splitApk in mergeApks) {
                    ZipFile(splitApk).use { splitSrc ->
                        for (entry in splitSrc.entries()) {
                            val name = entry.name.replace('\\', '/')
                            if (name == "AndroidManifest.xml") continue
                            if (name.startsWith("META-INF/")) continue
                            if (name in originalNames) continue
                            val patchBytes = patchMap[name]
                            if (patchBytes != null) {
                                zos.putNextEntry(ZipEntry(name))
                                zos.write(patchBytes)
                            } else if (entry.method == ZipEntry.STORED) {
                                zos.putNextEntry(storedAligned(name, entry.size, entry.crc, counting))
                                splitSrc.getInputStream(entry).use { it.copyTo(zos, bufferSize = BUFFER) }
                            } else {
                                zos.putNextEntry(ZipEntry(name))
                                splitSrc.getInputStream(entry).use { it.copyTo(zos, bufferSize = BUFFER) }
                            }
                            zos.closeEntry()
                            originalNames += name
                        }
                    }
                }
            }
        }
    }


    suspend fun zipAlign(apk: File): Unit = withContext(Dispatchers.IO) {
        val tmp = File(apk.parent, "${apk.name}.align_tmp")
        try {
            val counting = CountingOutputStream(tmp.outputStream().buffered(BUFFER))
            ZipOutputStream(counting).use { zos ->
                zos.setLevel(Deflater.BEST_SPEED)
                ZipFile(apk).use { source ->
                    for (entry in source.entries()) {
                        val name = entry.name




                        when {
                            name == "resources.arsc" || entry.method == ZipEntry.STORED -> {
                                val bytes = source.getInputStream(entry).use { it.readBytes() }
                                val crc = CRC32().apply { update(bytes) }.value
                                zos.putNextEntry(storedAligned(name, bytes.size.toLong(), crc, counting))
                                zos.write(bytes)
                            }
                            else -> {
                                zos.putNextEntry(ZipEntry(name))
                                source.getInputStream(entry).use { it.copyTo(zos, bufferSize = BUFFER) }
                            }
                        }
                        zos.closeEntry()
                    }
                }
            }
            if (!tmp.renameTo(apk)) {
                tmp.copyTo(apk, overwrite = true)
                tmp.delete()
            }
        } catch (e: Exception) {
            tmp.delete()
            throw e
        }
    }

    private fun storedAligned(
        name: String,
        size: Long,
        crc: Long,
        counting: CountingOutputStream,
    ): ZipEntry {


        val nameLen = name.toByteArray(Charsets.UTF_8).size
        val basePos = (counting.count + 30L + nameLen) % 4




        val extraLen = if (basePos == 0L) 0 else (8 - basePos).toInt()
        return ZipEntry(name).apply {
            method = ZipEntry.STORED
            this.size = size
            compressedSize = size
            this.crc = crc
            if (extraLen > 0) {
                val dataLen = extraLen - 4
                extra = ByteArray(extraLen).apply {

                    this[0] = 0; this[1] = 0

                    this[2] = (dataLen and 0xFF).toByte()
                    this[3] = (dataLen ushr 8 and 0xFF).toByte()

                }
            }
        }
    }











    private fun removeSplitRequirements(manifest: ByteArray): ByteArray {
        val result = manifest.copyOf()
        val targets = listOf(
            "base__abi,base__density",
            "base__abi,base__density,base__language",
            "base__density,base__abi",
            "base__abi",
            "base__density",
            "base__language",
            "com.android.vending.splits.required",
        )
        for (target in targets) {

            nullStringEntry(result, target.toByteArray(Charsets.UTF_16LE), prefixBytes = 2)

            nullStringEntry(result, target.toByteArray(Charsets.UTF_8), prefixBytes = 2)
        }
        return result
    }


    private fun nullStringEntry(buf: ByteArray, needle: ByteArray, prefixBytes: Int) {
        var i = prefixBytes
        while (i <= buf.size - needle.size) {
            var match = true
            for (j in needle.indices) {
                if (buf[i + j] != needle[j]) { match = false; break }
            }
            if (match) {
                for (k in 1..prefixBytes) buf[i - k] = 0
                for (j in needle.indices) buf[i + j] = 0
                i += needle.size
            } else {
                i++
            }
        }
    }

    private class CountingOutputStream(out: OutputStream) : FilterOutputStream(out) {
        var count = 0L
        override fun write(b: Int) { out.write(b); count++ }
        override fun write(b: ByteArray, off: Int, len: Int) { out.write(b, off, len); count += len }
    }

    companion object {
        private const val BUFFER = 1_048_576
    }
}
