package com.enmapatcher

import com.enmapatcher.patcher.PatchBlob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PatchBlobTest {

    @Test
    fun smallStaysInMemory() {
        val blob = PatchBlob.readStream(
            "hello".repeat(100).byteInputStream(),
            100L * 1024L * 1024L,
            "small.txt",
            File(System.getProperty("java.io.tmpdir")),
        )
        assertTrue(blob is PatchBlob.Mem)
        assertEquals("hello".repeat(100), blob.bytes().toString(Charsets.UTF_8))
    }

    @Test
    fun largeSpillsToDisk() {
        val dir = File(System.getProperty("java.io.tmpdir"), "blobtest_${System.nanoTime()}")
        dir.mkdirs()
        try {
            val chunk = ByteArray(65536) { (it % 251).toByte() }
            val source = object : java.io.InputStream() {
                var left = 33L * 1024L * 1024L
                override fun read(): Int = throw UnsupportedOperationException()
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    if (left <= 0) return -1
                    val n = minOf(len.toLong(), chunk.size.toLong(), left).toInt()
                    chunk.copyInto(b, off, 0, n)
                    left -= n
                    return n
                }
            }
            val blob = PatchBlob.readStream(source, 100L * 1024L * 1024L, "big.bin", dir)
            assertTrue(blob is PatchBlob.Disk)
            assertEquals(33L * 1024L * 1024L, blob.size())
            val head = ByteArray(8)
            blob.openStream().use { it.read(head) }
            for (i in head.indices) assertEquals(chunk[i], head[i])
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun capThrowsAndCleans() {
        val dir = File(System.getProperty("java.io.tmpdir"), "blobcap_${System.nanoTime()}")
        dir.mkdirs()
        try {
            val source = ByteArray(1024).inputStream()
            runCatching {
                PatchBlob.readStream(source, 100L, "cap.bin", dir)
            }.onSuccess { throw AssertionError("cap not enforced") }
            assertEquals(0, dir.listFiles()?.size ?: 0)
        } finally {
            dir.deleteRecursively()
        }
    }
}
