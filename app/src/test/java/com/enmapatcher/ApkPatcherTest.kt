package com.enmapatcher

import com.enmapatcher.patcher.ApkPatcher
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Random
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class ApkPatcherTest {

    private fun buildFixture(dir: File): File {
        val apk = File(dir, "base.apk")
        val random = Random(1234)
        ZipOutputStream(apk.outputStream().buffered()).use { zos ->
            zos.setLevel(Deflater.BEST_SPEED)
            fun add(name: String, bytes: ByteArray, stored: Boolean = false) {
                if (stored) {
                    val crc = java.util.zip.CRC32().apply { update(bytes) }
                    zos.putNextEntry(ZipEntry(name).apply {
                        method = ZipEntry.STORED
                        size = bytes.size.toLong()
                        compressedSize = bytes.size.toLong()
                        this.crc = crc.value
                    })
                } else {
                    zos.putNextEntry(ZipEntry(name))
                }
                zos.write(bytes)
                zos.closeEntry()
            }
            val textBlock = ("The quick brown fox jumps over the lazy dog. ".repeat(200)).toByteArray()
            repeat(24) { i ->
                add("assets/data/blob_$i.bin", textBlock + " #$i".toByteArray())
            }
            val noise = ByteArray(3 * 1024 * 1024)
            random.nextBytes(noise)
            add("assets/data/noise.bin", noise)
            add("assets/stored/stored_$ Encuentro.dat", textBlock, stored = true)
            add("resources.arsc", textBlock + ByteArray(512 * 1024).also { random.nextBytes(it) })
            add("AndroidManifest.xml", "<manifest package=\"x\"/>".toByteArray())
            add("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\r\n".toByteArray())
            add("emptydir/", ByteArray(0))
            add("lib/arm64-v8a/libx.so", noise.copyOf(1024 * 1024), stored = true)
        }
        return apk
    }

    private fun buildSplit(dir: File): File {
        val split = File(dir, "split.apk")
        ZipOutputStream(split.outputStream().buffered()).use { zos ->
            zos.putNextEntry(ZipEntry("assets/split/extra.bin"))
            zos.write("split-content".repeat(5000).toByteArray())
            zos.closeEntry()
            zos.putNextEntry(ZipEntry("AndroidManifest.xml"))
            zos.write("<manifest/>".toByteArray())
            zos.closeEntry()
        }
        return split
    }

    private fun snapshot(apk: File): Map<String, ByteArray> {
        val out = LinkedHashMap<String, ByteArray>()
        ZipFile(apk).use { zf ->
            for (entry in zf.entries()) {
                if (entry.isDirectory) continue
                out[entry.name] = zf.getInputStream(entry).readBytes()
            }
        }
        return out
    }

    @Test
    fun fastPathMatchesLegacy() {
        val dir = File(System.getProperty("java.io.tmpdir"), "apkpatcher_test_${System.nanoTime()}")
        dir.mkdirs()
        try {
            val apk = buildFixture(dir)
            val split = buildSplit(dir)
            val patchMap = mapOf(
                "assets/data/blob_3.bin" to "PATCHED-CONTENT".repeat(1000).toByteArray(),
                "assets/brand_new.bin" to "new-file".toByteArray(),
            )
            val fastWork = File(dir, "fast").also { it.mkdirs() }
            val legacyWork = File(dir, "legacy").also { it.mkdirs() }
            val tFast = System.nanoTime()
            val fastOut = runBlocking {
                ApkPatcher(fastWork).applyFileReplacements(
                    apk, patchMap,
                    appName = "New Name",
                    currentLabel = "Old Name",
                    mergeApks = listOf(split),
                    useFastPath = true,
                )
            }
            val fastMs = (System.nanoTime() - tFast) / 1_000_000
            val tLegacy = System.nanoTime()
            val legacyOut = runBlocking {
                ApkPatcher(legacyWork).applyFileReplacements(
                    apk, patchMap,
                    appName = "New Name",
                    currentLabel = "Old Name",
                    mergeApks = listOf(split),
                    useFastPath = false,
                )
            }
            val legacyMs = (System.nanoTime() - tLegacy) / 1_000_000
            println("fastMs=$fastMs legacyMs=$legacyMs")
            val fast = snapshot(fastOut)
            val legacy = snapshot(legacyOut)
            assertEquals(legacy.keys, fast.keys)
            for (name in legacy.keys) {
                assertArrayEquals("content of $name", legacy[name], fast[name])
            }
            ZipFile(fastOut).use { zf ->
                for (entry in zf.entries()) {
                    if (entry.isDirectory) continue
                    zf.getInputStream(entry).use { ins ->
                        val crc = java.util.zip.CRC32()
                        val buf = ByteArray(65536)
                        var n = ins.read(buf)
                        while (n > 0) {
                            crc.update(buf, 0, n)
                            n = ins.read(buf)
                        }
                        assertEquals("crc of ${entry.name}", entry.crc, crc.value)
                    }
                }
            }
            assertTrue(fastOut.length() > 0)
        } finally {
            dir.deleteRecursively()
        }
    }
}
