package io.github.korppujauho.pocketts.proto

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue

/** Opt-in server check. Two temporary identities; private messages only, no files or channel changes. */
class ShengQiaoServerSmokeTest {
    private class Peer : TsClient.Listener {
        val lock = ReentrantLock()
        val changed = lock.newCondition()
        val commands = CopyOnWriteArrayList<Command>()
        val voices = CopyOnWriteArrayList<TsClient.Voice>()
        val stopped = CountDownLatch(1)
        var connected = false
        var ended = false
        var reason: String? = null
        val client = TsClient(this)
        private fun update(block: () -> Unit) = lock.withLock { block(); changed.signalAll() }
        override fun onConnected(clientId: Int) = update { connected = true }
        override fun onCommand(cmd: Command) = update {
            commands.add(cmd)
            if (cmd.name == "channellistfinished") client.sendCommand("channelsubscribeall")
        }
        override fun onVoice(voice: TsClient.Voice) = update { voices.add(voice) }
        override fun onDisconnected(reason: String?) = update { ended = true; this.reason = reason; stopped.countDown() }
        fun await(label: String, check: () -> Boolean) = lock.withLock {
            val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(40)
            while (!check()) {
                assertTrue(!ended, "$label: disconnected ($reason)")
                val left = until - System.nanoTime()
                assertTrue(left > 0, "$label: timed out")
                changed.awaitNanos(left)
            }
        }
    }

    @Test
    fun connectionChannelsPrivateChatAndSilentVoice() {
        val input = System.getenv("SHENGQIAO_TEST_SERVER").orEmpty()
        assumeTrue(input.isNotBlank(), "SHENGQIAO_TEST_SERVER not set")
        val address = requireNotNull(ServerAddress.parse(input))
        val a = Peer()
        val b = Peer()
        val run = (System.currentTimeMillis() % 100000).toString()
        try {
            a.client.connect(TsClient.Options(address.host, address.port ?: 9987, Identity.generate(8), "SQ-Test-A-$run"))
            a.await("A connects") { a.connected }
            a.await("Channel list") { a.commands.any { it.name == "channellistfinished" } }
            b.client.connect(TsClient.Options(address.host, address.port ?: 9987, Identity.generate(8), "SQ-Test-B-$run"))
            b.await("B connects") { b.connected }
            b.await("B sees A") { b.commands.any { it.name == "notifycliententerview" && it.groups.any { row -> row["clid"] == a.client.clientId.toString() } } }
            val text = "声桥 TS 私聊验收 $run | / + 中文"
            a.client.sendCommand(Commands.build("sendtextmessage", "targetmode" to 1, "target" to b.client.clientId, "msg" to text))
            b.await("Private message") { b.commands.any { it.name == "notifytextmessage" && it["msg"] == text && it["invokerid"] == a.client.clientId.toString() } }
            println("PASS: handshake, channel list, presence, Unicode private chat")
            println("Advertised server version: " + (a.commands.firstNotNullOfOrNull { it["virtualserver_version"] } ?: "not advertised"))
            // One valid Opus silence frame: no microphone or audible speech; verifies encrypted voice transport.
            val silence = byteArrayOf(0xf8.toByte(), 0xff.toByte(), 0xfe.toByte())
            a.client.sendVoice(4, silence)
            a.client.sendVoiceStop(4)
            b.await("Silent Opus packet") { b.voices.any { it.clientId == a.client.clientId && it.data.contentEquals(silence) } }
            assertEquals(4, b.voices.first { it.data.contentEquals(silence) }.codec)
            println("PASS: Opus voice packet arrived byte-for-byte; phone audio hardware not tested")
        } finally {
            a.client.disconnect("ShengQiao test completed")
            b.client.disconnect("ShengQiao test completed")
            if (a.connected) assertTrue(a.stopped.await(5, TimeUnit.SECONDS), "A disconnects cleanly")
            if (b.connected) assertTrue(b.stopped.await(5, TimeUnit.SECONDS), "B disconnects cleanly")
        }
    }
}
