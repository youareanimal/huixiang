package io.github.korppujauho.pocketts.proto

import java.security.SecureRandom

/** The server's license chain (newer handshake): it yields the server key the connection's secret is agreed with. */
object License {
    private val ROOT_KEY = intArrayOf(
        0xcd, 0x0d, 0xe2, 0xae, 0xd4, 0x63, 0x45, 0x50, 0x9a, 0x7e, 0x3c, 0xfd,
        0x8f, 0x68, 0xb3, 0xdc, 0x75, 0x55, 0xb2, 0x9d, 0xcc, 0xec, 0x73, 0xcd,
        0x18, 0x75, 0x0f, 0x99, 0x38, 0x12, 0x40, 0x8a,
    ).map { it.toByte() }.toByteArray()

    class Block(val key: ByteArray, val hash: ByteArray, val type: Int)

    private fun u8(b: ByteArray, i: Int) = b[i].toInt() and 0xff

    fun parse(data: ByteArray): List<Block> {
        require(data.isNotEmpty()) { "License too short" }
        require(data[0].toInt() == 1) { "Unsupported license version" }
        val blocks = ArrayList<Block>()
        var pos = 1
        while (pos < data.size) {
            require(data.size - pos >= 42) { "License block too short" }
            require(data[pos].toInt() == 0) { "Wrong key kind ${data[pos]}" }
            val key = data.copyOfRange(pos + 1, pos + 33)
            val type = u8(data, pos + 33)
            val extra = when (type) {
                0 -> nullTerminator(data, pos + 46) + 5 // intermediate
                2 -> nullTerminator(data, pos + 47) + 6 // server
                8 -> { // newer server
                    var p = pos + 44
                    val props = u8(data, pos + 43)
                    repeat(props) { p += 1 + u8(data, p) }
                    p - (pos + 42)
                }
                32 -> 0 // ephemeral
                else -> throw IllegalArgumentException("Invalid license block type $type")
            }
            val len = 42 + extra
            val hash = Crypto.sha512(data.copyOfRange(pos + 1, pos + len)).copyOf(32)
            blocks.add(Block(key, hash, type))
            pos += len
        }
        return blocks
    }

    private fun nullTerminator(data: ByteArray, start: Int): Int {
        for (i in start until data.size) if (data[i].toInt() == 0) return i - start
        throw IllegalArgumentException("Non-null-terminated string in license")
    }

    fun deriveKey(blocks: List<Block>): ByteArray {
        var parent = ROOT_KEY.copyOf()
        for (b in blocks) {
            val scalar = b.hash.copyOf()
            scalar[0] = (scalar[0].toInt() and 0xf8).toByte()
            scalar[31] = ((scalar[31].toInt() and 0x3f) or 0x40).toByte()
            parent = Ed25519.add(Ed25519.scalarMul(scalar, b.key), parent)
        }
        return parent
    }

    fun sharedSecret(serverKey: ByteArray, tempPrivate: ByteArray): ByteArray {
        val k = tempPrivate.copyOf()
        k[31] = (k[31].toInt() and 0x7f).toByte()
        return Crypto.sha512(Ed25519.scalarMul(k, serverKey))
    }

    class TempKey(val publicKey: ByteArray, val privateKey: ByteArray)

    fun temporaryKey(random: SecureRandom = SecureRandom()): TempKey {
        val priv = ByteArray(32).also { random.nextBytes(it) }
        priv[0] = (priv[0].toInt() and 248).toByte()
        priv[31] = ((priv[31].toInt() and 127) or 64).toByte()
        return TempKey(Ed25519.scalarMulBase(priv), priv)
    }
}
