package io.github.korppujauho.pocketts.proto

import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.Signature
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPrivateKeySpec
import java.security.spec.ECPublicKeySpec
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.KeyAgreement

/**
 * A TeamSpeak identity: a P-256 key pair plus the "key offset" that sets its security level. The same identity is
 * the same user to the server (its unique id, permissions and groups), so it's created once and kept.
 */
class Identity(val privateScalar: BigInteger, val keyOffset: Long) {
    val publicPoint: ECPoint = P256.multiplyBase(privateScalar)

    /** The public key as the server expects it ("omega"): base64 of a small DER structure. */
    val publicKeyString: String = Base64.getEncoder().encodeToString(
        Der.sequence(Der.bitString(0x00), Der.integer(BigInteger.valueOf(32)), Der.integer(publicPoint.affineX), Der.integer(publicPoint.affineY))
    )

    /** The unique id the server and other clients see. */
    val uid: String = Base64.getEncoder().encodeToString(Crypto.sha1(publicKeyString.toByteArray(Charsets.US_ASCII)))

    val hwid: String = Crypto.sha1((uid + "pocketts").toByteArray()).joinToString("") { "%02x".format(it) }.take(32)

    val securityLevel: Int get() = securityLevel(publicKeyString, keyOffset)

    val privateKey: PrivateKey by lazy {
        KeyFactory.getInstance("EC").generatePrivate(ECPrivateKeySpec(privateScalar, P256.params))
    }

    fun sign(data: ByteArray): ByteArray = Signature.getInstance("SHA256withECDSA").run {
        initSign(privateKey)
        update(data)
        sign()
    }

    /** The older handshake's shared secret: SHA-1 of the ECDH x coordinate with the server's key. */
    fun sharedSecret(serverPublicKeyDer: ByteArray): ByteArray {
        val parts = Der.parseSequence(serverPublicKeyDer)
        val pub = KeyFactory.getInstance("EC").generatePublic(
            ECPublicKeySpec(ECPoint(parts[2] as BigInteger, parts[3] as BigInteger), P256.params)
        )
        val ka = KeyAgreement.getInstance("ECDH")
        ka.init(privateKey)
        ka.doPhase(pub, true)
        val secret = ka.generateSecret()
        val x = when {
            secret.size == 32 -> secret
            secret.size > 32 -> secret.copyOfRange(secret.size - 32, secret.size)
            else -> ByteArray(32 - secret.size) + secret
        }
        return Crypto.sha1(x)
    }

    /**
     * A higher security level, up to [level]: keeps trying key offsets on [threads] threads (CPU work: reaching a
     * level takes about 2^level tries, so each level about doubles the time). [progress] hears each better level as
     * it's found. When [cancelled], the best level found so far is kept.
     */
    fun improvedTo(level: Int, cancelled: () -> Boolean = { false }, threads: Int = 1, progress: (Int) -> Unit = {}): Identity {
        val lock = Any()
        var best = securityLevel
        var bestOffset = keyOffset
        if (best >= level) return this
        val prefix = publicKeyString.toByteArray(Charsets.US_ASCII)
        val next = AtomicLong(keyOffset + 1)
        val stop = AtomicBoolean(false)
        val workers = List(threads.coerceAtLeast(1)) {
            Thread({
                val sha1 = MessageDigest.getInstance("SHA-1")
                val digits = ByteArray(20)
                while (!stop.get()) {
                    val start = next.getAndAdd(CHUNK)
                    for (offset in start until start + CHUNK) {
                        sha1.update(prefix)
                        val from = writeDecimal(offset, digits)
                        sha1.update(digits, from, digits.size - from)
                        val l = zeroBits(sha1.digest())
                        if (l > best) synchronized(lock) {
                            if (l > best) {
                                best = l
                                bestOffset = offset
                                progress(l)
                                if (l >= level) stop.set(true)
                            }
                        }
                    }
                    if (cancelled()) stop.set(true)
                }
            }, "identity-level-$it").apply { isDaemon = true; start() }
        }
        workers.forEach { it.join() }
        return synchronized(lock) { Identity(privateScalar, bestOffset) }
    }

    /** The format TeamSpeak clients export and import identities in ("<offset>V<data>"). */
    fun export(): String {
        val inner = Base64.getEncoder().encodeToString(
            Der.sequence(
                Der.bitString(0x80), Der.integer(BigInteger.valueOf(32)),
                Der.integer(publicPoint.affineX), Der.integer(publicPoint.affineY), Der.integer(privateScalar),
            )
        ).toByteArray(Charsets.US_ASCII)
        Crypto.xorInto(inner, OBFUSCATION_KEY, minOf(100, inner.size))
        Crypto.xorInto(inner, Crypto.sha1(hashedPart(inner)), 20)
        return "${keyOffset}V" + Base64.getEncoder().encodeToString(inner)
    }

    /** The .ini file TeamSpeak apps save and open: this identity under [name], with the [nickname] used with it. */
    fun exportIni(name: String, nickname: String): String {
        fun clean(s: String) = s.replace(Regex("""[\r\n"]"""), " ").trim()
        return "[Identity]\r\nid=${clean(name)}\r\nidentity=\"${export()}\"\r\nnickname=${clean(nickname)}\r\nphonetic_nickname=\r\n"
    }

    companion object {
        // The export format's scrambling key: these characters as text (not as hex), of which the first 100 are used.
        private val OBFUSCATION_KEY: ByteArray = (
            "b9dfaa7bee6ac57ac7b65f1094a1c155e747327bc2fe5d51c512023fe54a2802" +
                "01004e90ad1daaae1075d53b7d571c30e063b5a62a4a017bb394833aa0983e6e"
            ).toByteArray(Charsets.US_ASCII)

        fun generate(securityLevel: Int = 8): Identity {
            val gen = KeyPairGenerator.getInstance("EC")
            gen.initialize(ECGenParameterSpec("secp256r1"))
            val pair = gen.generateKeyPair()
            val id = Identity((pair.private as ECPrivateKey).s, 0)
            check(id.publicPoint == (pair.public as ECPublicKey).w) { "Public key mismatch" }
            return if (securityLevel > 0) id.improvedTo(securityLevel) else id
        }

        /**
         * Reads an identity exported by a TeamSpeak client (or by [export]): the bare "<offset>V<data>", the same in
         * quotes, or the whole .ini file a TeamSpeak app saves (its identity="..." line).
         */
        fun import(exported: String): Identity {
            val m = Regex("""^(\d+)V([\w/+=]+)$""").matchEntire(identityText(exported))
                ?: throw IllegalArgumentException("Not a TeamSpeak identity")
            val offset = m.groupValues[1].toLong()
            val data = Base64.getDecoder().decode(m.groupValues[2])
            require(data.size >= 20) { "Identity too short" }
            Crypto.xorInto(data, Crypto.sha1(hashedPart(data)), 20)
            Crypto.xorInto(data, OBFUSCATION_KEY, minOf(100, data.size))
            val text = String(data, Charsets.UTF_8).substringBefore('\u0000')
            return fromDer(Base64.getDecoder().decode(text), offset)
        }

        /** The "<offset>V<data>" part of what was pasted or picked: an .ini file's identity line, or the text itself. */
        private fun identityText(text: String): String {
            val t = text.trim().removePrefix("﻿").trim()
            Regex("""(?im)^\s*identity\s*=\s*"?(\d+V[\w/+=]+)"?\s*$""").find(t)?.let { return it.groupValues[1] }
            return t.removeSurrounding("\"").trim()
        }

        private fun hashedPart(data: ByteArray): ByteArray {
            var end = data.size
            for (i in 20 until data.size) if (data[i].toInt() == 0) { end = i; break }
            return data.copyOfRange(20, end)
        }

        private fun fromDer(der: ByteArray, offset: Long): Identity {
            val parts = Der.parseSequence(der)
            require(parts.size >= 3) { "Invalid key data" }
            val scalar = when (parts[0] as Int) {
                0x80 -> parts.getOrNull(4) as? BigInteger
                0xc0 -> parts[2] as BigInteger
                0x00 -> null
                else -> throw IllegalArgumentException("Unknown key type")
            } ?: throw IllegalArgumentException("The identity has no private key")
            return Identity(scalar, offset)
        }

        fun securityLevel(publicKeyString: String, offset: Long): Int =
            zeroBits(Crypto.sha1((publicKeyString + offset.toString()).toByteArray(Charsets.US_ASCII)))

        /** Offsets each raising thread takes at a time (it checks for being stopped between them). */
        private const val CHUNK = 65_536L

        /** How many tries a second this machine makes on [threads] threads, measured for about [millis] ms. */
        fun triesPerSecond(millis: Long = 300, threads: Int = 1): Double {
            val id = generate(securityLevel = 0)
            val end = System.nanoTime() + millis * 1_000_000
            val tries = AtomicLong()
            val start = System.nanoTime()
            // Aims for a level nobody reaches, stopped by the clock; the tries are counted in whole chunks
            id.improvedTo(160, cancelled = { tries.addAndGet(CHUNK); System.nanoTime() > end }, threads = threads)
            return tries.get() / ((System.nanoTime() - start) / 1e9)
        }

        /** The time [level] takes on average at [triesPerSecond], in seconds (about 2^level tries). */
        fun secondsFor(level: Int, triesPerSecond: Double): Double = Math.pow(2.0, level.toDouble()) / triesPerSecond

        // The offset's decimal digits at the end of [into]; returns where they start
        private fun writeDecimal(value: Long, into: ByteArray): Int {
            var v = value
            var i = into.size
            do {
                into[--i] = ('0'.code + (v % 10).toInt()).toByte()
                v /= 10
            } while (v != 0L)
            return i
        }

        // The security level of a hash: its zero bits from the start, lowest bit of each byte first
        private fun zeroBits(hash: ByteArray): Int {
            var count = 0
            for (b in hash) {
                val v = b.toInt() and 0xff
                if (v == 0) { count += 8; continue }
                for (bit in 0 until 8) {
                    if (v and (1 shl bit) == 0) count++ else return count
                }
            }
            return count
        }

        internal fun hex(s: String): ByteArray = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }
}

/** P-256, for deriving the public key from a stored private key (the platform's crypto doesn't offer that). */
internal object P256 {
    val params: ECParameterSpec = AlgorithmParameters.getInstance("EC").run {
        init(ECGenParameterSpec("secp256r1"))
        getParameterSpec(ECParameterSpec::class.java)
    }
    private val p: BigInteger = (params.curve.field as java.security.spec.ECFieldFp).p
    private val a: BigInteger = params.curve.a

    private fun add(p1: ECPoint, p2: ECPoint): ECPoint {
        if (p1 == ECPoint.POINT_INFINITY) return p2
        if (p2 == ECPoint.POINT_INFINITY) return p1
        val (x1, y1) = p1.affineX to p1.affineY
        val (x2, y2) = p2.affineX to p2.affineY
        val lambda = if (x1 == x2) {
            if ((y1 + y2).mod(p) == BigInteger.ZERO) return ECPoint.POINT_INFINITY
            (x1 * x1 * BigInteger.valueOf(3) + a) * (y1 * BigInteger.valueOf(2)).modInverse(p)
        } else {
            (y2 - y1) * (x2 - x1).modInverse(p)
        }.mod(p)
        val x3 = (lambda * lambda - x1 - x2).mod(p)
        val y3 = (lambda * (x1 - x3) - y1).mod(p)
        return ECPoint(x3, y3)
    }

    fun multiplyBase(k: BigInteger): ECPoint {
        require(k.signum() > 0 && k < params.order) { "Invalid private key" }
        var result = ECPoint.POINT_INFINITY
        for (i in k.bitLength() - 1 downTo 0) {
            result = add(result, result)
            if (k.testBit(i)) result = add(result, params.generator)
        }
        return result
    }
}

/** Just enough DER for the key formats TeamSpeak uses. */
internal object Der {
    private fun length(len: Int): ByteArray = when {
        len < 0x80 -> byteArrayOf(len.toByte())
        len < 0x100 -> byteArrayOf(0x81.toByte(), len.toByte())
        else -> byteArrayOf(0x82.toByte(), (len shr 8).toByte(), len.toByte())
    }

    fun integer(v: BigInteger): ByteArray {
        val b = v.toByteArray() // big-endian, with a leading 0 when the top bit is set
        return byteArrayOf(0x02) + length(b.size) + b
    }

    fun bitString(flags: Int): ByteArray = byteArrayOf(0x03, 0x02, 0x07, flags.toByte())

    fun sequence(vararg parts: ByteArray): ByteArray {
        val content = parts.fold(ByteArray(0)) { acc, p -> acc + p }
        return byteArrayOf(0x30) + length(content.size) + content
    }

    /** The items of a SEQUENCE: a bit string's flag byte as Int, integers as BigInteger. */
    fun parseSequence(data: ByteArray): List<Any> {
        val out = ArrayList<Any>()
        var pos = 0
        require(data[pos].toInt() == 0x30) { "Expected SEQUENCE" }
        pos++
        val (seqLen, n) = readLength(data, pos)
        pos += n
        val end = pos + seqLen
        while (pos < end) {
            val tag = data[pos].toInt() and 0xff
            pos++
            val (len, ln) = readLength(data, pos)
            pos += ln
            when (tag) {
                0x03 -> out.add(data[pos + 1].toInt() and 0xff)
                0x02 -> out.add(BigInteger(1, data.copyOfRange(pos, pos + len)))
            }
            pos += len
        }
        return out
    }

    private fun readLength(data: ByteArray, pos: Int): Pair<Int, Int> {
        val first = data[pos].toInt() and 0xff
        if (first < 0x80) return first to 1
        val count = first and 0x7f
        var len = 0
        for (i in 0 until count) len = (len shl 8) or (data[pos + 1 + i].toInt() and 0xff)
        return len to 1 + count
    }
}
