package io.github.korppujauho.pocketts.proto

import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** The packet encryption: AES-128 in EAX mode, and the hashes and constants around it. */
object Crypto {
    const val MAC_LEN = 8
    private const val BLOCK = 16
    private val ZERO_BLOCK = ByteArray(BLOCK)

    val INIT_MAC: ByteArray = "TS3INIT1".toByteArray(Charsets.US_ASCII)

    // The key and nonce used until the connection's own ones are agreed ("c:\windows\system\firewall32.cpl").
    val DUMMY_KEY: ByteArray = "c:\\windows\\syste".toByteArray(Charsets.US_ASCII)
    val DUMMY_NONCE: ByteArray = "m\\firewall32.cpl".toByteArray(Charsets.US_ASCII)

    const val INIT_VERSION = 1566914096L // 3.5.0 [Stable]

    fun sha1(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-1").digest(data)
    fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)
    fun sha512(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-512").digest(data)

    /** How passwords are sent: base64 of their SHA-1. */
    fun hashPassword(password: String): String =
        if (password.isEmpty()) "" else Base64.getEncoder().encodeToString(sha1(password.toByteArray(Charsets.UTF_8)))

    private fun aesBlock(key: ByteArray, block: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/ECB/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
        return c.doFinal(block)
    }

    private fun ctr(key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray {
        if (data.isEmpty()) return ByteArray(0)
        val c = Cipher.getInstance("AES/CTR/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return c.doFinal(data)
    }

    private fun doubleBlock(b: ByteArray): ByteArray {
        val out = ByteArray(BLOCK)
        var carry = 0
        for (i in BLOCK - 1 downTo 0) {
            val v = b[i].toInt() and 0xff
            out[i] = ((v shl 1) or carry).toByte()
            carry = (v shr 7) and 1
        }
        if (carry != 0) out[BLOCK - 1] = (out[BLOCK - 1].toInt() xor 0x87).toByte()
        return out
    }

    /** AES-CMAC (OMAC1). */
    fun cmac(key: ByteArray, data: ByteArray): ByteArray {
        val l = aesBlock(key, ZERO_BLOCK)
        val k1 = doubleBlock(l)
        val k2 = doubleBlock(k1)
        val n = data.size
        val blocks = if (n == 0) 1 else (n + BLOCK - 1) / BLOCK
        val lastComplete = n > 0 && n % BLOCK == 0

        var x = ByteArray(BLOCK)
        for (i in 0 until blocks - 1) {
            x = aesBlock(key, xor(x, data.copyOfRange(i * BLOCK, (i + 1) * BLOCK)))
        }
        val last = if (lastComplete) {
            xor(data.copyOfRange((blocks - 1) * BLOCK, n), k1)
        } else {
            val padded = ByteArray(BLOCK)
            val rest = data.copyOfRange((blocks - 1) * BLOCK, n)
            rest.copyInto(padded)
            padded[rest.size] = 0x80.toByte()
            xor(padded, k2)
        }
        return aesBlock(key, xor(x, last))
    }

    private fun omac(key: ByteArray, tag: Int, data: ByteArray): ByteArray {
        val input = ByteArray(BLOCK + data.size)
        input[BLOCK - 1] = tag.toByte()
        data.copyInto(input, BLOCK)
        return cmac(key, input)
    }

    class Sealed(val ciphertext: ByteArray, val mac: ByteArray)

    fun eaxEncrypt(key: ByteArray, nonce: ByteArray, header: ByteArray, plain: ByteArray, macLen: Int = MAC_LEN): Sealed {
        val n = omac(key, 0, nonce)
        val h = omac(key, 1, header)
        val ct = ctr(key, n, plain)
        val c = omac(key, 2, ct)
        val tag = xor(xor(n, h), c)
        return Sealed(ct, tag.copyOf(macLen))
    }

    /** The plaintext, or null when the MAC doesn't match. */
    fun eaxDecrypt(key: ByteArray, nonce: ByteArray, header: ByteArray, ct: ByteArray, mac: ByteArray, macLen: Int = MAC_LEN): ByteArray? {
        val n = omac(key, 0, nonce)
        val h = omac(key, 1, header)
        val c = omac(key, 2, ct)
        val tag = xor(xor(n, h), c)
        if (!MessageDigest.isEqual(tag.copyOf(macLen), mac.copyOf(macLen))) return null
        return ctr(key, n, ct)
    }

    class KeyNonce(val key: ByteArray, val nonce: ByteArray)

    /** Each packet's own key and nonce, from the connection's shared secret (ivStruct). */
    fun deriveKeyNonce(fromServer: Boolean, packetId: Int, generation: Long, packetType: Int, ivStruct: ByteArray): KeyNonce {
        val tmp = ByteArray(if (ivStruct.size == 20) 26 else 70)
        tmp[0] = if (fromServer) 0x30 else 0x31
        tmp[1] = packetType.toByte()
        ByteBuffer.wrap(tmp, 2, 4).putInt(generation.toInt())
        ivStruct.copyInto(tmp, 6)
        val kn = sha256(tmp)
        val key = kn.copyOfRange(0, 16)
        val nonce = kn.copyOfRange(16, 32)
        key[0] = (key[0].toInt() xor ((packetId shr 8) and 0xff)).toByte()
        key[1] = (key[1].toInt() xor (packetId and 0xff)).toByte()
        return KeyNonce(key, nonce)
    }

    fun xor(a: ByteArray, b: ByteArray): ByteArray {
        val len = minOf(a.size, b.size)
        return ByteArray(len) { (a[it].toInt() xor b[it].toInt()).toByte() }
    }

    /** XOR the first [len] bytes of [b] into [a], starting at [aOffset]. */
    fun xorInto(a: ByteArray, b: ByteArray, len: Int, aOffset: Int = 0) {
        for (i in 0 until len) a[aOffset + i] = (a[aOffset + i].toInt() xor b[i].toInt()).toByte()
    }
}
