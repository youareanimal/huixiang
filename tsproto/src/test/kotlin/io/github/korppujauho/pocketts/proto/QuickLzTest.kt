package io.github.korppujauho.pocketts.proto

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Compressed commands (the server packs long ones): packed here as QuickLZ level 1 does, then unpacked. */
class QuickLzTest {

    @Test
    fun channelListRoundTrip() {
        // Long lists repeat themselves: most of it becomes references back
        val text = (1..60).joinToString("|") { "cid=$it cpid=0 channel_order=${it - 1} channel_name=Room\\s$it channel_flag_permanent=1" }
        val cmd = "channellist $text"
        val packed = pack(cmd.toByteArray())
        assertTrue(packed.size < cmd.length / 2, "packed ${packed.size} of ${cmd.length}")
        assertEquals(1, packed[0].toInt() and 0x01) // compressed
        assertEquals(0x02, packed[0].toInt() and 0x02) // long header (over 255 bytes)
        val back = QuickLz.decompress(packed)
        assertEquals(cmd, String(back))
        assertEquals(60, Commands.parse(String(back)).groups.size)
    }

    @Test
    fun shapes() {
        val random = Random(7)
        val inputs = listOf(
            "a".repeat(600), // overlapping copies, the long (3-byte) length
            "abc".repeat(5), // a short one, with the short header
            "notifycliententerview clid=5 client_nickname=Alex",
            ByteArray(2000) { random.nextInt(256).toByte() }.decodeToString(), // little to repeat
            String(CharArray(700) { "abcd"[random.nextInt(4)] }), // many short matches
            "short",
        )
        for (s in inputs) {
            val raw = s.toByteArray()
            assertContentEquals(raw, QuickLz.decompress(pack(raw)), "input of ${raw.size} bytes")
        }
    }

    // ====== A QuickLZ level 1 packer, keeping the same table of earlier positions as the unpacker ======

    private fun u8(b: ByteArray, i: Int) = b[i].toInt() and 0xff
    private fun read24(b: ByteArray, i: Int) = u8(b, i) or (u8(b, i + 1) shl 8) or (u8(b, i + 2) shl 16)
    private fun hash(v: Int) = ((v ushr 12) xor v) and 0xfff

    private fun pack(input: ByteArray): ByteArray {
        val size = input.size
        val table = IntArray(4096)
        val tokens = ArrayList<Pair<Boolean, ByteArray>>() // (is a reference, its bytes)
        var pos = 0
        var nextHashed = 0
        fun hashUpTo(end: Int) {
            for (i in nextHashed until end) table[hash(read24(input, i))] = i
        }
        while (pos < size) {
            if (pos < maxOf(size, 10) - 10 && pos + 3 <= size) {
                val h = hash(read24(input, pos))
                val from = table[h]
                if (from < pos && (0 until 3).all { input[from + it] == input[pos + it] }) {
                    var len = 3
                    while (len < 255 && pos + len < size && input[from + len] == input[pos + len]) len++
                    tokens.add(
                        true to if (len <= 17) {
                            byteArrayOf(((len - 2) or ((h and 0x0f) shl 4)).toByte(), (h shr 4).toByte())
                        } else {
                            byteArrayOf(((h and 0x0f) shl 4).toByte(), (h shr 4).toByte(), len.toByte())
                        }
                    )
                    hashUpTo(pos + 1)
                    nextHashed = pos + len
                    pos += len
                    continue
                }
            }
            tokens.add(false to byteArrayOf(input[pos]))
            pos++
            val end = maxOf(pos - 2, 0)
            if (nextHashed < end) hashUpTo(end)
            nextHashed = maxOf(nextHashed, end)
        }
        // Each control word flags the next 31 tokens (lowest bit first) under a top bit that marks its end
        val body = java.io.ByteArrayOutputStream()
        for (group in tokens.chunked(31)) {
            var control = 1L shl 31
            group.forEachIndexed { i, t -> if (t.first) control = control or (1L shl i) }
            for (k in 0 until 4) body.write(((control shr (8 * k)) and 0xff).toInt())
            group.forEach { body.write(it.second) }
        }
        val data = body.toByteArray()
        val long = size > 255 || data.size + 3 > 255
        val header = if (long) {
            val total = data.size + 9
            byteArrayOf(0x07) + le32(total) + le32(size)
        } else {
            byteArrayOf(0x05, (data.size + 3).toByte(), size.toByte())
        }
        return header + data
    }

    private fun le32(v: Int) = ByteArray(4) { (v shr (8 * it)).toByte() }
}
