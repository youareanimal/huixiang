package io.github.korppujauho.pocketts.proto

import io.github.korppujauho.pocketts.proto.ServerAddress.Srv
import io.github.korppujauho.pocketts.proto.ServerAddress.Typed
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ServerAddressTest {
    @Test
    fun parsing() {
        assertEquals(Typed("ts.example.com", null), ServerAddress.parse(" ts.example.com "))
        assertEquals(Typed("ts.example.com", 9988), ServerAddress.parse("ts.example.com:9988"))
        assertEquals(Typed("10.0.0.5", 9987), ServerAddress.parse("ts3server://10.0.0.5:9987?nickname=x"))
        assertEquals(Typed("2001:db8::1", null), ServerAddress.parse("2001:db8::1"))
        assertEquals(Typed("2001:db8::1", 9000), ServerAddress.parse("[2001:db8::1]:9000"))
        // A link's port after the address (the address's own port wins)
        assertEquals(Typed("ts.example.com", 9988), ServerAddress.parse("ts3server://ts.example.com?port=9988&nickname=Lee"))
        assertEquals(Typed("ts.example.com", 9000), ServerAddress.parse("ts3server://ts.example.com:9000?port=9988"))
        assertEquals(Typed("2001:db8::1", 9988), ServerAddress.parse("ts3server://[2001:db8::1]?port=9988"))
        assertEquals(Typed("ts.example.com", null), ServerAddress.parse("ts3server://ts.example.com?nickname=Lee"))
        assertNull(ServerAddress.parse("ts3server://ts.example.com?port=abc"))
        assertNull(ServerAddress.parse(""))
        assertNull(ServerAddress.parse("host:abc"))
        assertNull(ServerAddress.parse("host:70000"))
        assertNull(ServerAddress.parse("two words"))
    }

    @Test
    fun portOrIpSkipsLookups() {
        val noDns = ServerAddress.SrvLookup { error("no lookups expected") }
        assertEquals(9000, ServerAddress.resolve(Typed("ts.example.com", 9000), noDns).port)
        assertEquals("10.0.0.5", ServerAddress.resolve(Typed("10.0.0.5", null), noDns).host)
    }

    @Test
    fun dnsEntryWins() {
        val dns = ServerAddress.SrvLookup { name ->
            if (name == "_ts3._udp.example.com") listOf(Srv(10, 0, 9999, "backup.example.com."), Srv(0, 5, 10001, "voice.example.com."))
            else emptyList()
        }
        val r = ServerAddress.resolve(Typed("Example.com", null), dns)
        assertEquals("voice.example.com", r.host)
        assertEquals(10001, r.port)
    }

    @Test
    fun plainNameLast() {
        val asked = mutableListOf<String>()
        val r = ServerAddress.resolve(Typed("ts.example.com", null), { asked.add(it); emptyList() })
        assertEquals(ServerAddress.Resolved("ts.example.com", 9987, "name"), r)
        assertEquals(listOf("_ts3._udp.ts.example.com", "_tsdns._tcp.ts.example.com", "_tsdns._tcp.example.com"), asked)
    }

    @Test
    fun nicknameFound() {
        val asked = mutableListOf<String>()
        val noDns = ServerAddress.SrvLookup { error("no DNS lookups expected") }
        val r = ServerAddress.resolve(Typed("MyClan", null), noDns, { asked.add(it); "203.0.113.7:10075" })
        assertEquals(ServerAddress.Resolved("203.0.113.7", 10075, "nickname"), r)
        assertEquals(listOf("MyClan"), asked) // as typed
    }

    @Test
    fun nicknameToANameGoesOnThroughDns() {
        // The nickname names a host without a port; that host's DNS entry says where the server really is
        val dns = ServerAddress.SrvLookup { name ->
            if (name == "_ts3._udp.ts.example.com") listOf(Srv(0, 0, 9987, "vpn.example.com.")) else emptyList()
        }
        assertEquals(
            ServerAddress.Resolved("vpn.example.com", 9987, "nickname, then DNS entry"),
            ServerAddress.resolve(Typed("myclan", null), dns, { "ts.example.com" }),
        )
        // No DNS entry: the name itself on the default port
        assertEquals(
            ServerAddress.Resolved("voice.example.com", 9987, "nickname, then name"),
            ServerAddress.resolve(Typed("myclan", null), { emptyList() }, { "voice.example.com" }),
        )
        // A nickname pointing at another dotless name isn't looked up again
        val asked = mutableListOf<String>()
        ServerAddress.resolve(Typed("myclan", null), { emptyList() }, { asked.add(it); "otherclan" })
        assertEquals(listOf("myclan"), asked)
    }

    @Test
    fun unknownOrBrokenNicknameGoesOnAsAName() {
        val asked = mutableListOf<String>()
        val dns = ServerAddress.SrvLookup { asked.add(it); emptyList() }
        assertEquals(ServerAddress.Resolved("nas", 9987, "name"), ServerAddress.resolve(Typed("nas", null), dns, { null }))
        assertEquals(listOf("_ts3._udp.nas", "_tsdns._tcp.nas"), asked)
        // The service down, or saying something that isn't an address
        assertEquals("name", ServerAddress.resolve(Typed("nas", null), dns, { throw java.io.IOException("timed out") }).how)
        assertEquals("name", ServerAddress.resolve(Typed("nas", null), dns, { "<html>oops</html>" }).how)
    }

    @Test
    fun onlyNicknamesAreAsked() {
        val never = ServerAddress.NicknameLookup { error("the nickname service mustn't be asked about $it") }
        val dns = ServerAddress.SrvLookup { emptyList() }
        ServerAddress.resolve(Typed("ts.example.com", null), dns, never)
        ServerAddress.resolve(Typed("myclan", 9987), dns, never)
        ServerAddress.resolve(Typed("10.0.0.5", null), dns, never)
        ServerAddress.resolve(Typed("2001:db8::1", null), dns, never)
        ServerAddress.resolve(Typed("localhost", null), dns, never)
    }

    @Test
    fun dnsMessages() {
        val query = DnsMessage.srvQuery("_ts3._udp.example.com")
        // An answer: the query's header and question, then one SRV record whose name points back to the question
        val answer = ByteArrayOutputStream().apply {
            write(query, 0, 2)
            write(byteArrayOf(0x81.toByte(), 0x80.toByte(), 0, 1, 0, 1, 0, 0, 0, 0))
            write(query, 12, query.size - 12)
            write(byteArrayOf(0xc0.toByte(), 12, 0, 33, 0, 1, 0, 0, 0x0e, 0x10))
            val target = byteArrayOf(5) + "voice".toByteArray() + byteArrayOf(0xc0.toByte(), 22) // "voice" + "example.com" from the question
            val rdata = byteArrayOf(0, 1, 0, 5, 0x27, 0x11) + target
            write(byteArrayOf(0, rdata.size.toByte()))
            write(rdata)
        }.toByteArray()
        assertEquals(listOf(Srv(1, 5, 10001, "voice.example.com")), DnsMessage.parseSrv(answer))
    }
}
