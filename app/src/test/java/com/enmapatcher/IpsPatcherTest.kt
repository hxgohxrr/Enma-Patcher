package com.enmapatcher

import com.enmapatcher.patcher.IpsPatcher
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IpsPatcherTest {

    private fun buildPatch(vararg ops: ByteArray): ByteArray {
        var out = "PATCH".toByteArray(Charsets.US_ASCII)
        for (op in ops) out += op
        out += "EOF".toByteArray(Charsets.US_ASCII)
        return out
    }

    private fun record(offset: Int, data: ByteArray): ByteArray {
        val head = byteArrayOf(
            ((offset shr 16) and 0xFF).toByte(),
            ((offset shr 8) and 0xFF).toByte(),
            (offset and 0xFF).toByte(),
            ((data.size shr 8) and 0xFF).toByte(),
            (data.size and 0xFF).toByte(),
        )
        return head + data
    }

    private fun rle(offset: Int, length: Int, value: Byte): ByteArray {
        return byteArrayOf(
            ((offset shr 16) and 0xFF).toByte(),
            ((offset shr 8) and 0xFF).toByte(),
            (offset and 0xFF).toByte(),
            0, 0,
            ((length shr 8) and 0xFF).toByte(),
            (length and 0xFF).toByte(),
            value,
        )
    }

    @Test
    fun normalAndRleRecords() {
        val original = ByteArray(16) { it.toByte() }
        val patch = buildPatch(record(4, byteArrayOf(9, 9, 9)), rle(10, 4, 7))
        val result = IpsPatcher.apply(original, patch)
        val expected = ByteArray(16) { it.toByte() }
        expected[4] = 9
        expected[5] = 9
        expected[6] = 9
        expected[10] = 7
        expected[11] = 7
        expected[12] = 7
        expected[13] = 7
        assertArrayEquals(expected, result)
    }

    @Test
    fun rejectsBadInputs() {
        val original = ByteArray(8)
        runCatching { IpsPatcher.apply(original, "NOPE".toByteArray()) }
            .onSuccess { throw AssertionError("bad header accepted") }
        runCatching { IpsPatcher.apply(original, buildPatch(record(100, byteArrayOf(1)))) }
            .onSuccess { throw AssertionError("out of range accepted") }
        runCatching { IpsPatcher.apply(original, "PATCH".toByteArray()) }
            .onSuccess { throw AssertionError("truncated accepted") }
    }

    @Test
    fun targetMapping() {
        assertEquals("assets/data/a.bin", IpsPatcher.targetFor("patches/assets/data/a.bin.ips"))
        assertEquals("assets/data/a.bin", IpsPatcher.targetFor("patches/assets/data/a.bin.IPS"))
        assertNull(IpsPatcher.targetFor("assets/data/a.bin"))
        assertNull(IpsPatcher.targetFor("patches/readme.txt"))
    }
}
