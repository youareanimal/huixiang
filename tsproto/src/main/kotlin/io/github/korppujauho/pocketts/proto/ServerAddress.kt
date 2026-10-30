package io.github.korppujauho.pocketts.proto

import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * From what a person types to the server's real address and port, the way TeamSpeak apps do it:
 * a port given → that; a server nickname (a name without a dot) → what TeamSpeak's nickname service says; else the
 * server's DNS entry (`_ts3._udp.<name>`); else TeamSpeak's name service (TSDNS, found through
 * `_tsdns._tcp.<domain>`); else the name itself on the default port 9987.
 */
object ServerAddress {
    const val DEFAULT_PORT = 9987
    private const val TSDNS_PORT = 41144

    /** What was typed: a host and the port, when one was given. */
    data class Typed(val host: String, val port: Int?)

    data class Srv(val priority: Int, val weight: Int, val port: Int, val target: String)

    /** Looks up DNS SRV records (empty when there are none). */
    fun interface SrvLookup {
        fun srv(name: String): List<Srv>
    }

    /** Asks TeamSpeak's nickname service: a server nickname in, its "address:port" out (null: no such nickname). */
    fun interface NicknameLookup {
        fun lookup(nickname: String): String?
    }

    /**
     * TeamSpeak's own nickname service, as the TeamSpeak apps ask it: a plain-text "address:port" for a known
     * nickname, 404 for an unknown one. Gives up after a few seconds (behind some networks it never answers).
     */
    object MyTeamSpeakNicknames : NicknameLookup {
        private const val SERVICE = "https://named.myteamspeak.com/lookup?name="

        override fun lookup(nickname: String): String? {
            val c = java.net.URI(SERVICE + java.net.URLEncoder.encode(nickname, "UTF-8")).toURL().openConnection() as java.net.HttpURLConnection
            c.connectTimeout = 4000
            c.readTimeout = 4000
            c.instanceFollowRedirects = false
            try {
                return when (val code = c.responseCode) {
                    200 -> c.inputStream.use { input ->
                        // An address is short: a few hundred bytes at most
                        val out = ByteArrayOutputStream()
                        val buf = ByteArray(300)
                        while (out.size() < 300) {
                            val n = input.read(buf, 0, 300 - out.size())
                            if (n < 0) break
                            out.write(buf, 0, n)
                        }
                        out.toString("UTF-8").trim().ifEmpty { null }
                    }
                    404 -> null
                    else -> throw IllegalStateException("HTTP $code")
                }
            } finally {
                c.disconnect()
            }
        }
    }

    private val HOSTNAME = Regex("""^[A-Za-z0-9]([A-Za-z0-9-]*[A-Za-z0-9])?(\.[A-Za-z0-9]([A-Za-z0-9-]*[A-Za-z0-9])?)*\.?$""")

    /** A name the nickname service may know: no dot, no port, not an address, not this device. */
    fun isNickname(typed: Typed): Boolean =
        typed.port == null && '.' !in typed.host && !isIpLiteral(typed.host) && !typed.host.equals("localhost", ignoreCase = true)

    /** "host", "host:port", "[ipv6]:port", an IPv6 address, or a ts3server:// link (its port also as `?port=`). */
    fun parse(input: String): Typed? {
        val link = input.trim().removePrefix("ts3server://").removePrefix("ts://")
        val t = link.substringBefore('?').trimEnd('/')
        if (t.isEmpty() || t.any { it.isWhitespace() }) return null
        // A link's port can come after the address: ts3server://ts.example.com?port=9988&nickname=...
        // (a port in the address itself wins)
        val linkPort = link.substringAfter('?', "").split('&').firstOrNull { it.startsWith("port=") }?.removePrefix("port=")
        if (linkPort != null && t != link) {
            val typed = parse(t) ?: return null
            if (typed.port != null) return typed
            val port = linkPort.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
            return typed.copy(port = port)
        }
        val typed = when {
            t.startsWith("[") -> {
                if (!t.contains(']')) return null
                val port = t.substringAfter(']').removePrefix(":").takeIf { it.isNotEmpty() }
                Typed(t.substringAfter('[').substringBefore(']'), port?.let { it.toIntOrNull() ?: return null })
            }
            t.count { it == ':' } == 1 -> Typed(t.substringBefore(':'), t.substringAfter(':').toIntOrNull() ?: return null)
            else -> Typed(t, null)
        }
        if (typed.host.isEmpty() || (typed.port != null && typed.port !in 1..65535)) return null
        return typed
    }

    fun isIpLiteral(host: String): Boolean =
        host.contains(':') || Regex("""^\d{1,3}(\.\d{1,3}){3}$""").matches(host)

    /** The address to connect to, with a note of how it was found. */
    data class Resolved(val host: String, val port: Int, val how: String)

    fun resolve(typed: Typed, dns: SrvLookup, nicknames: NicknameLookup? = null, log: (String) -> Unit = {}): Resolved {
        typed.port?.let { return Resolved(typed.host, it, "as typed") }
        if (isIpLiteral(typed.host)) return Resolved(typed.host, DEFAULT_PORT, "as typed")
        val name = typed.host.trimEnd('.').lowercase()

        // 0. A server nickname: TeamSpeak's nickname service knows where it is (an unknown one goes on as a name)
        if (nicknames != null && isNickname(Typed(name, null))) {
            try {
                // As typed: the service doesn't mind the case, but the nickname is the owner's to spell
                val answer = nicknames.lookup(typed.host.trimEnd('.'))
                val found = answer?.let { parse(it) }?.takeIf { isIpLiteral(it.host) || HOSTNAME.matches(it.host) }
                if (found != null) {
                    // A name without a port goes through the usual steps (its DNS entry may name another host and port)
                    if (found.port != null || isIpLiteral(found.host)) return Resolved(found.host, found.port ?: DEFAULT_PORT, "nickname")
                    val r = resolve(found, dns, null, log)
                    return r.copy(how = "nickname, then ${r.how}")
                }
                if (answer != null) log("Nickname $name: an answer that isn't an address")
            } catch (e: Exception) {
                log("Nickname lookup for $name failed: ${e.message}")
            }
        }

        // 1. The server's own DNS entry
        try {
            pick(dns.srv("_ts3._udp.$name"))?.let { return Resolved(it.target.trimEnd('.'), it.port, "DNS entry") }
        } catch (e: Exception) {
            log("DNS lookup for $name failed: ${e.message}")
        }

        // 2. TeamSpeak's name service, for the name and the domains above it
        for (domain in parents(name)) {
            val services = try { dns.srv("_tsdns._tcp.$domain") } catch (e: Exception) { emptyList() }
            for (s in services.sortedWith(srvOrder)) {
                try {
                    tsdns(s.target, s.port, name)?.let { return it }
                } catch (e: Exception) {
                    log("TSDNS at ${s.target}:${s.port} failed: ${e.message}")
                }
            }
        }

        // 3. The name itself
        return Resolved(name, DEFAULT_PORT, "name")
    }

    /** "a.b.example.com" → itself, "b.example.com", "example.com". */
    internal fun parents(name: String): List<String> {
        val labels = name.split('.')
        if (labels.size < 2) return listOf(name)
        return (0..labels.size - 2).map { labels.drop(it).joinToString(".") }
    }

    private val srvOrder = compareBy<Srv> { it.priority }.thenByDescending { it.weight }

    private fun pick(records: List<Srv>): Srv? =
        records.filter { it.target.isNotEmpty() && it.target != "." && it.port > 0 }.sortedWith(srvOrder).firstOrNull()

    /** Asks a TSDNS server: send the name, get "address:port" back (or "404"). */
    private fun tsdns(host: String, port: Int, name: String): Resolved? {
        Socket().use { s ->
            s.connect(InetSocketAddress(host.trimEnd('.'), port), 3000)
            s.soTimeout = 3000
            s.getOutputStream().apply { write(name.toByteArray(Charsets.US_ASCII)); flush() }
            val out = ByteArrayOutputStream()
            val buf = ByteArray(256)
            while (out.size() < 1024) {
                val n = try { s.getInputStream().read(buf) } catch (_: Exception) { -1 }
                if (n <= 0) break
                out.write(buf, 0, n)
            }
            val answer = out.toString("US-ASCII").trim()
            if (answer.startsWith("404") || answer.contains("No Match", ignoreCase = true)) return null
            val typed = parse(answer) ?: return null
            return Resolved(typed.host, typed.port ?: DEFAULT_PORT, "TSDNS")
        }
    }
}

/** Builds DNS SRV queries and reads answers (for platforms that hand over raw DNS messages). */
object DnsMessage {
    const val TYPE_SRV = 33

    fun srvQuery(name: String, id: Int = 0x4d54): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf((id shr 8).toByte(), id.toByte(), 0x01, 0x00, 0, 1, 0, 0, 0, 0, 0, 0)) // RD, 1 question
        for (label in name.trimEnd('.').split('.')) {
            val b = label.toByteArray(Charsets.US_ASCII)
            out.write(b.size); out.write(b)
        }
        out.write(byteArrayOf(0, 0, TYPE_SRV.toByte(), 0, 1))
        return out.toByteArray()
    }

    fun parseSrv(msg: ByteArray): List<ServerAddress.Srv> {
        fun u16(i: Int) = ((msg[i].toInt() and 0xff) shl 8) or (msg[i + 1].toInt() and 0xff)
        if (msg.size < 12) return emptyList()
        val qd = u16(4)
        val an = u16(6)
        var pos = 12
        repeat(qd) { pos = skipName(msg, pos) + 4 }
        val out = ArrayList<ServerAddress.Srv>()
        repeat(an) {
            pos = skipName(msg, pos)
            val type = u16(pos)
            val len = u16(pos + 8)
            val data = pos + 10
            if (type == TYPE_SRV && len >= 7) {
                out.add(ServerAddress.Srv(u16(data), u16(data + 2), u16(data + 4), readName(msg, data + 6)))
            }
            pos = data + len
        }
        return out
    }

    private fun skipName(msg: ByteArray, start: Int): Int {
        var pos = start
        while (true) {
            val len = msg[pos].toInt() and 0xff
            if (len == 0) return pos + 1
            if (len and 0xc0 == 0xc0) return pos + 2
            pos += 1 + len
        }
    }

    private fun readName(msg: ByteArray, start: Int): String {
        val labels = ArrayList<String>()
        var pos = start
        var jumps = 0
        while (jumps < 20) {
            val len = msg[pos].toInt() and 0xff
            when {
                len == 0 -> break
                len and 0xc0 == 0xc0 -> {
                    pos = ((len and 0x3f) shl 8) or (msg[pos + 1].toInt() and 0xff)
                    jumps++
                }
                else -> {
                    labels.add(String(msg, pos + 1, len, Charsets.US_ASCII))
                    pos += 1 + len
                }
            }
        }
        return labels.joinToString(".")
    }
}
