package com.enmapatcher.patcher

import java.nio.ByteBuffer
import java.nio.ByteOrder

object AppNamePatcher {

    fun patch(arscBytes: ByteArray, oldName: String, newName: String): ByteArray {
        if (oldName == newName || newName.isBlank()) return arscBytes
        return try {
            var result = arscBytes.copyOf()

            val utf8Old = encUtf8(oldName)
            val utf8New = encUtf8Padded(newName, oldName)
            if (utf8Old.size == utf8New.size) result = replaceAll(result, utf8Old, utf8New)

            val utf16Old = encUtf16(oldName)
            val utf16New = encUtf16Padded(newName, oldName)
            if (utf16Old.size == utf16New.size) result = replaceAll(result, utf16Old, utf16New)

            result
        } catch (_: Exception) {
            arscBytes
        }
    }

    private fun encUtf8(s: String): ByteArray {
        val utf8 = s.toByteArray(Charsets.UTF_8)
        val out = mutableListOf<Byte>()
        val u16 = s.length
        if (u16 > 0x7F) { out.add(((u16 shr 8) or 0x80).toByte()); out.add((u16 and 0xFF).toByte()) }
        else out.add(u16.toByte())
        val u8 = utf8.size
        if (u8 > 0x7F) { out.add(((u8 shr 8) or 0x80).toByte()); out.add((u8 and 0xFF).toByte()) }
        else out.add(u8.toByte())
        out.addAll(utf8.toList())
        out.add(0)
        return out.toByteArray()
    }

    private fun encUtf16(s: String): ByteArray {
        val buf = ByteBuffer.allocate(2 + s.length * 2 + 2).order(ByteOrder.LITTLE_ENDIAN)
        buf.putShort(s.length.toShort())
        s.forEach { buf.putChar(it) }
        buf.putShort(0)
        return buf.array()
    }

    private fun encUtf8Padded(newName: String, oldName: String): ByteArray {
        val targetBytes = oldName.toByteArray(Charsets.UTF_8).size
        var s = newName
        while (s.toByteArray(Charsets.UTF_8).size < targetBytes) s += " "
        while (s.toByteArray(Charsets.UTF_8).size > targetBytes) s = s.dropLast(1)
        return encUtf8(s)
    }

    private fun encUtf16Padded(newName: String, oldName: String): ByteArray {
        var s = newName
        while (s.length < oldName.length) s += " "
        if (s.length > oldName.length) s = s.substring(0, oldName.length)
        return encUtf16(s)
    }

    private fun replaceAll(data: ByteArray, pattern: ByteArray, replacement: ByteArray): ByteArray {
        if (pattern.isEmpty()) return data
        val result = data.copyOf()
        var i = 0
        while (i <= result.size - pattern.size) {
            var match = true
            for (j in pattern.indices) {
                if (result[i + j] != pattern[j]) { match = false; break }
            }
            if (match) { replacement.copyInto(result, i); i += pattern.size }
            else i++
        }
        return result
    }
}
