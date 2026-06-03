package com.enmapatcher.patcher

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FilterOutputStream
import java.io.OutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater
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
    ): File = withContext(Dispatchers.IO) {
        workDir.mkdirs()
        val output = File(workDir, "patched_unsigned.apk")

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

        output
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
