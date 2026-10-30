package io.github.korppujauho.pocketts.proto

import java.net.HttpURLConnection
import java.net.URI

/** Small injectable HTTP boundary. Requests carrying account secrets never follow redirects. */
fun interface CloudTransport {
    fun request(method: String, url: String, headers: Map<String, String>, body: ByteArray?): CloudReply
}

class CloudReply(val status: Int, val body: ByteArray)

object HttpsCloudTransport : CloudTransport {
    override fun request(method: String, url: String, headers: Map<String, String>, body: ByteArray?): CloudReply {
        val uri = URI(url)
        require(uri.scheme == "https" && uri.userInfo == null && uri.fragment == null)
        val c = uri.toURL().openConnection() as HttpURLConnection
        try {
            c.instanceFollowRedirects = false
            c.requestMethod = method
            c.connectTimeout = 15_000
            c.readTimeout = 35_000
            c.setRequestProperty("User-Agent", "HuiXiang/0.3")
            for ((name, value) in headers) c.setRequestProperty(name, value)
            if (body != null) {
                c.doOutput = true
                c.setFixedLengthStreamingMode(body.size)
                c.outputStream.use { it.write(body) }
            }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val data = stream?.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    if (out.size() + n > 4 * 1024 * 1024) throw CloudFailure("RESPONSE_TOO_LARGE")
                    out.write(buffer, 0, n)
                }
                out.toByteArray()
            } ?: byteArrayOf()
            return CloudReply(code, data)
        } catch (e: CloudFailure) {
            throw e
        } catch (_: Exception) {
            // Exception URLs or response bodies can contain credentials or message content.
            throw CloudFailure("NETWORK")
        } finally { c.disconnect() }
    }
}

class CloudFailure(val code: String) : Exception(code)

/** A bounded protobuf subset for the account RPC. No generated proprietary sources are included. */
internal object CloudProto {
    class Field(val number: Int, val integer: Long? = null, val bytes: ByteArray? = null)
    fun text(number: Int, value: String) = bytes(number, value.toByteArray(Charsets.UTF_8))
    fun bytes(number: Int, value: ByteArray) = varint(number.toLong() * 8 + 2) + varint(value.size.toLong()) + value
    fun integer(number: Int, value: Long) = varint(number.toLong() * 8) + varint(value)
    fun varint(value: Long): ByteArray {
        require(value >= 0)
        var n = value
        val out = java.io.ByteArrayOutputStream()
        do { out.write(((n and 127).toInt()) or if (n ushr 7 != 0L) 128 else 0); n = n ushr 7 } while (n != 0L)
        return out.toByteArray()
    }
    fun read(data: ByteArray): List<Field> {
        require(data.size <= 4 * 1024 * 1024)
        var p = 0
        fun take(n: Int): ByteArray {
            require(n >= 0 && n <= data.size - p)
            return data.copyOfRange(p, p + n).also { p += n }
        }
        fun v(): Long {
            var n = 0L
            for (i in 0..9) {
                require(p < data.size)
                val b = data[p++].toInt() and 255
                require(i < 9 || b <= 1)
                n = n or ((b and 127).toLong() shl (7 * i))
                if (b and 128 == 0) return n
            }
            error("Invalid varint")
        }
        val out = mutableListOf<Field>()
        while (p < data.size) {
            require(out.size < 10_000)
            val key = v()
            val number = key ushr 3
            require(number in 1..536870911)
            when ((key and 7).toInt()) {
                0 -> out += Field(number.toInt(), integer = v())
                2 -> { val length = v(); require(length in 0..(data.size - p).toLong()); out += Field(number.toInt(), bytes = take(length.toInt())) }
                1 -> { take(8); out += Field(number.toInt()) }
                5 -> { take(4); out += Field(number.toInt()) }
                else -> error("Unsupported protobuf wire type")
            }
        }
        return out
    }
    fun List<Field>.string(number: Int) = firstOrNull { it.number == number }?.bytes?.toString(Charsets.UTF_8).orEmpty()
    fun List<Field>.number(number: Int) = firstOrNull { it.number == number }?.integer
}
