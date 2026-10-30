package io.github.korppujauho.pocketts.proto

import java.net.DatagramSocket
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue

/** Connecting to an address where no TeamSpeak server answers says so, not that a connection was lost. */
class ConnectFailTest {
    private class Ended(val reason: String, val end: TsClient.End?, val detail: String)

    private fun endFor(host: String, port: Int): Ended? {
        val ends = ArrayBlockingQueue<Ended>(1)
        lateinit var client: TsClient
        client = TsClient(object : TsClient.Listener {
            override fun onDisconnected(reason: String?) {
                ends.offer(Ended(reason ?: "(none)", client.end, client.endDetail))
            }
        })
        client.connect(TsClient.Options(host, port, Identity.generate(8), "PocketTS test"))
        return ends.poll(TsClient.SILENCE_MS + 5_000, TimeUnit.MILLISECONDS)
    }

    @Test
    fun aSilentAddress() {
        // Something listens there but never answers
        DatagramSocket(0, InetAddress.getLoopbackAddress()).use { silent ->
            val e = endFor("127.0.0.1", silent.localPort)
            assertEquals(TsClient.NO_ANSWER, e?.reason)
            assertEquals(TsClient.End.NO_ANSWER, e?.end)
        }
    }

    @Test
    fun aClosedPort() {
        val port = DatagramSocket(0, InetAddress.getLoopbackAddress()).use { it.localPort } // free again
        val e = endFor("127.0.0.1", port)
        assertEquals(TsClient.NO_ANSWER, e?.reason)
        assertEquals(TsClient.End.NO_ANSWER, e?.end)
    }

    @Test
    fun anUnknownName() {
        // Some VPN/proxy DNS servers synthesize addresses even for .invalid. This case requires NXDOMAIN.
        val unknown = try { InetAddress.getByName("no-such-server.invalid"); false } catch (_: UnknownHostException) { true }
        assumeTrue(unknown, "DNS resolves .invalid (synthetic DNS); no-such-host behaviour cannot be exercised here")
        val e = endFor("no-such-server.invalid", 9987)
        assertTrue(e?.reason?.startsWith("Couldn't connect: no server found at \"no-such-server.invalid\"") == true, "was: ${e?.reason}")
        assertEquals(TsClient.End.NO_SUCH_HOST, e?.end)
        assertEquals("no-such-server.invalid", e?.detail)
    }
}
