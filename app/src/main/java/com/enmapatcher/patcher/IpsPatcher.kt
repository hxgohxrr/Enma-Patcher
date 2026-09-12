package com.enmapatcher.patcher

object IpsPatcher {

    fun apply(original: ByteArray, patch: ByteArray): ByteArray {
        if (patch.size < 8) throw IllegalArgumentException("IpsTooSmall")
        if (!patch.copyOfRange(0, 5).contentEquals("PATCH".toByteArray(Charsets.US_ASCII))) {
            throw IllegalArgumentException("IpsBadHeader")
        }
        var result = original.copyOf()
        var pos = 5
        while (true) {
            if (pos + 3 > patch.size) throw IllegalArgumentException("IpsTruncated")
            if (patch[pos] == 'E'.code.toByte() && patch[pos + 1] == 'O'.code.toByte() && patch[pos + 2] == 'F'.code.toByte()) {
                pos += 3
                break
            }
            if (pos + 5 > patch.size) throw IllegalArgumentException("IpsTruncated")
            val offset = ((patch[pos].toInt() and 0xFF) shl 16) or
                ((patch[pos + 1].toInt() and 0xFF) shl 8) or
                (patch[pos + 2].toInt() and 0xFF)
            val size = ((patch[pos + 3].toInt() and 0xFF) shl 8) or (patch[pos + 4].toInt() and 0xFF)
            pos += 5
            if (size == 0) {
                if (pos + 3 > patch.size) throw IllegalArgumentException("IpsTruncated")
                val runLength = ((patch[pos].toInt() and 0xFF) shl 8) or (patch[pos + 1].toInt() and 0xFF)
                val value = patch[pos + 2]
                pos += 3
                if (runLength == 0) throw IllegalArgumentException("IpsBadRle")
                if (offset + runLength > result.size) throw IllegalArgumentException("IpsOutOfRange")
                for (i in 0 until runLength) result[offset + i] = value
            } else {
                if (pos + size > patch.size) throw IllegalArgumentException("IpsTruncated")
                if (offset + size > result.size) throw IllegalArgumentException("IpsOutOfRange")
                patch.copyInto(result, offset, pos, pos + size)
                pos += size
            }
        }
        if (pos + 3 == patch.size) {
            val cut = ((patch[pos].toInt() and 0xFF) shl 16) or
                ((patch[pos + 1].toInt() and 0xFF) shl 8) or
                (patch[pos + 2].toInt() and 0xFF)
            if (cut < result.size) result = result.copyOf(cut)
        }
        return result
    }

    fun targetFor(ipsPath: String): String? {
        val stripped = if (ipsPath.startsWith("patches/")) {
            ipsPath.removePrefix("patches/")
        } else {
            ipsPath
        }
        if (!stripped.lowercase().endsWith(".ips")) return null
        return stripped.substring(0, stripped.length - 4)
    }

    fun isIpsEntry(path: String): Boolean {
        val lower = path.lowercase()
        return lower.startsWith("patches/") && lower.endsWith(".ips")
    }
}
