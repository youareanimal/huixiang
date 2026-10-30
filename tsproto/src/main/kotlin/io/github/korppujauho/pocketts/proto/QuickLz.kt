package io.github.korppujauho.pocketts.proto

/** QuickLZ level 1 decompression: the server compresses large commands (long channel and client lists). */
object QuickLz {
    private const val TABLE_SIZE = 4096
    private const val MAX_SIZE = 1024 * 1024

    private fun u8(b: ByteArray, i: Int) = b[i].toInt() and 0xff
    private fun le32(b: ByteArray, i: Int) = u8(b, i) or (u8(b, i + 1) shl 8) or (u8(b, i + 2) shl 16) or (u8(b, i + 3) shl 24)
    private fun read24(b: ByteArray, i: Int) = u8(b, i) or (u8(b, i + 1) shl 8) or (u8(b, i + 2) shl 16)
    private fun hash(v: Int) = ((v ushr 12) xor v) and 0xfff

    fun decompressedSize(data: ByteArray): Int = if (data[0].toInt() and 0x02 != 0) le32(data, 5) else u8(data, 2)

    fun decompress(data: ByteArray): ByteArray {
        val flags = u8(data, 0)
        val level = (flags shr 2) and 0x03
        require(level == 1) { "QuickLZ level $level not supported" }
        val headerLen = if (flags and 0x02 != 0) 9 else 3
        val size = decompressedSize(data)
        require(size in 0..MAX_SIZE) { "Decompressed size $size exceeds max" }
        val dest = ByteArray(size)

        if (flags and 0x01 == 0) {
            data.copyInto(dest, 0, headerLen, headerLen + size)
            return dest
        }

        val table = IntArray(TABLE_SIZE)
        var control = 1L
        var src = headerLen
        var dst = 0
        var nextHashed = 0

        while (true) {
            if (control == 1L) {
                control = le32(data, src).toLong() and 0xffffffffL
                src += 4
            }
            if (control and 1L != 0L) {
                // A back-reference to earlier output
                control = control ushr 1
                val next = u8(data, src++)
                val hashIdx = (next shr 4) or (u8(data, src++) shl 4)
                var matchLen = next and 0x0f
                if (matchLen != 0) matchLen += 2 else matchLen = u8(data, src++)
                val offset = table[hashIdx]
                for (i in 0 until matchLen) dest[dst + i] = dest[offset + i] // may overlap: byte by byte
                dst += matchLen

                val end = dst + 1 - matchLen
                if (nextHashed < end) {
                    var nxt = read24(dest, nextHashed)
                    table[hash(nxt)] = nextHashed
                    for (i in nextHashed + 1 until end) {
                        nxt = (nxt ushr 8) or (u8(dest, i + 2) shl 16)
                        table[hash(nxt)] = i
                    }
                }
                nextHashed = dst
            } else if (dst >= maxOf(size, 10) - 10) {
                // The last bytes are always literals
                while (dst < size) {
                    if (control == 1L) src += 4
                    control = control ushr 1
                    dest[dst++] = data[src++]
                }
                break
            } else {
                dest[dst++] = data[src++]
                control = control ushr 1
                val end = maxOf(dst - 2, 0)
                if (nextHashed < end) {
                    var nxt = read24(dest, nextHashed)
                    table[hash(nxt)] = nextHashed
                    for (i in nextHashed + 1 until end) {
                        nxt = (nxt ushr 8) or (u8(dest, i + 2) shl 16)
                        table[hash(nxt)] = i
                    }
                }
                nextHashed = maxOf(nextHashed, end)
            }
        }
        return dest
    }
}
