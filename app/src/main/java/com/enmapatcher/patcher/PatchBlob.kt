package com.enmapatcher.patcher

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

sealed interface PatchBlob {
    fun size(): Long
    fun bytes(): ByteArray
    fun openStream(): InputStream

    data class Mem(val data: ByteArray) : PatchBlob {
        override fun size(): Long = data.size.toLong()
        override fun bytes(): ByteArray = data
        override fun openStream(): InputStream = data.inputStream()
    }

    data class Disk(val file: File) : PatchBlob {
        override fun size(): Long = file.length()
        override fun bytes(): ByteArray = file.readBytes()
        override fun openStream(): InputStream = file.inputStream()
    }

    companion object {
        const val SPILL_OVER_BYTES = 4L * 1024L * 1024L
        const val MAP_BUDGET_BYTES = 256L * 1024L * 1024L

        fun ofBytes(data: ByteArray, spillDir: File?): PatchBlob {
            if (spillDir == null || data.size <= SPILL_OVER_BYTES) return Mem(data)
            spillDir.mkdirs()
            val file = File(spillDir, "blob_" + System.nanoTime() + "_" + data.size)
            file.writeBytes(data)
            return Disk(file)
        }

        fun readStream(
            ins: InputStream,
            maxBytes: Long,
            label: String,
            spillDir: File?,
        ): PatchBlob {
            val mem = ByteArrayOutputStream()
            var fileOut: OutputStream? = null
            var file: File? = null
            var total = 0L
            val buf = ByteArray(65536)
            try {
                while (true) {
                    val n = ins.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > maxBytes) throw IOException("EntryTooLarge:$label")
                    val current = fileOut
                    if (current != null) {
                        current.write(buf, 0, n)
                    } else if (spillDir != null && total > SPILL_OVER_BYTES) {
                        spillDir.mkdirs()
                        val fresh = File(spillDir, "blob_" + System.nanoTime() + "_" + label.hashCode())
                        val out = fresh.outputStream().buffered(65536)
                        out.write(mem.toByteArray())
                        mem.reset()
                        out.write(buf, 0, n)
                        file = fresh
                        fileOut = out
                    } else {
                        mem.write(buf, 0, n)
                    }
                }
            } catch (e: Exception) {
                runCatching { fileOut?.close() }
                if (file != null) runCatching { file.delete() }
                throw e
            }
            runCatching { fileOut?.flush() }
            runCatching { fileOut?.close() }
            val done = file
            return if (done != null) Disk(done) else Mem(mem.toByteArray())
        }

        fun spillDown(map: MutableMap<String, PatchBlob>, budget: Long, spillDir: File?) {
            if (spillDir == null) return
            var total = 0L
            for ((_, blob) in map) {
                if (blob is Mem) total += blob.data.size
            }
            if (total <= budget) return
            val big = map.entries
                .filter { it.value is Mem }
                .sortedByDescending { (it.value as Mem).data.size }
            for ((key, blob) in big) {
                if (total <= budget) break
                blob as Mem
                try {
                    val file = File(spillDir, "blob_evict_${System.nanoTime()}")
                    file.writeBytes(blob.data)
                    map[key] = Disk(file)
                    total -= blob.data.size
                } catch (_: Exception) {
                    break
                }
            }
        }
    }
}
