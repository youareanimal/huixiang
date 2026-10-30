package io.github.korppujauho.pocketts.proto

import java.math.BigInteger

/**
 * The few Ed25519 point operations the newer handshake needs: decoding and encoding points, adding them, and
 * multiplying by a scalar exactly as given (no clamping, top bit cleared). Plain BigInteger arithmetic: it runs a
 * handful of times per connection, so clarity beats speed.
 */
object Ed25519 {
    private val P: BigInteger = BigInteger.valueOf(2).pow(255) - BigInteger.valueOf(19)
    private val D: BigInteger = BigInteger.valueOf(-121665).mod(P) * BigInteger.valueOf(121666).modInverse(P) % P
    private val SQRT_M1: BigInteger = BigInteger.valueOf(2).modPow((P - BigInteger.ONE) / BigInteger.valueOf(4), P)

    /** A point in affine coordinates. */
    data class Point(val x: BigInteger, val y: BigInteger)

    private val IDENTITY = Point(BigInteger.ZERO, BigInteger.ONE)

    val BASE: Point = run {
        val y = BigInteger.valueOf(4) * BigInteger.valueOf(5).modInverse(P) % P
        decode(encodeY(y, false))
    }

    private fun encodeY(y: BigInteger, xOdd: Boolean): ByteArray {
        val be = y.toByteArray()
        val out = ByteArray(32)
        for (i in 0 until minOf(32, be.size)) out[i] = be[be.size - 1 - i]
        if (xOdd) out[31] = (out[31].toInt() or 0x80).toByte()
        return out
    }

    fun encode(p: Point): ByteArray = encodeY(p.y, p.x.testBit(0))

    fun decode(bytes: ByteArray): Point {
        require(bytes.size == 32) { "An Ed25519 point is 32 bytes" }
        val le = bytes.copyOf()
        val sign = (le[31].toInt() ushr 7) and 1
        le[31] = (le[31].toInt() and 0x7f).toByte()
        val y = BigInteger(1, le.reversedArray())
        require(y < P) { "Not a valid point" }
        val y2 = y * y % P
        val u = (y2 - BigInteger.ONE).mod(P)
        val v = (D * y2 + BigInteger.ONE) % P
        val x2 = u * v.modInverse(P) % P
        var x = x2.modPow((P + BigInteger.valueOf(3)) / BigInteger.valueOf(8), P)
        if ((x * x - x2).mod(P) != BigInteger.ZERO) x = x * SQRT_M1 % P
        require((x * x - x2).mod(P) == BigInteger.ZERO) { "Not a valid point" }
        if (x == BigInteger.ZERO && sign == 1) throw IllegalArgumentException("Not a valid point")
        if (x.testBit(0) != (sign == 1)) x = P - x
        return Point(x, y)
    }

    fun add(a: Point, b: Point): Point {
        val xx = a.x * b.x % P
        val yy = a.y * b.y % P
        val dxy = D * xx % P * yy % P
        val x3 = (a.x * b.y + a.y * b.x) % P * (BigInteger.ONE + dxy).modInverse(P) % P
        val y3 = (yy + xx) % P * (BigInteger.ONE - dxy).mod(P).modInverse(P) % P
        return Point(x3, y3)
    }

    /** [scalar] (32 bytes, little-endian, top bit cleared, not reduced) times [point]. */
    fun scalarMul(scalar: ByteArray, point: Point): Point {
        val s = scalar.copyOf()
        s[31] = (s[31].toInt() and 0x7f).toByte()
        val k = BigInteger(1, s.reversedArray())
        var result = IDENTITY
        for (i in k.bitLength() - 1 downTo 0) {
            result = add(result, result)
            if (k.testBit(i)) result = add(result, point)
        }
        require(result != IDENTITY) { "The result is the identity point" }
        return result
    }

    fun scalarMul(scalar: ByteArray, point: ByteArray): ByteArray = encode(scalarMul(scalar, decode(point)))
    fun scalarMulBase(scalar: ByteArray): ByteArray = encode(scalarMul(scalar, BASE))
    fun add(a: ByteArray, b: ByteArray): ByteArray = encode(add(decode(a), decode(b)))
}
