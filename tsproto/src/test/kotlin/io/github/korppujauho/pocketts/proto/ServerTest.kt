package io.github.korppujauho.pocketts.proto

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue

/**
 * Two connections to a real TeamSpeak server (TS_TEST_SERVER=host:port, e.g. a local test server; skipped without
 * it): both connect, see each other, one moves and the other sees it, one talks and the other hears exactly what was
 * sent, a chat message arrives, and both leave cleanly.
 */
class ServerTest {
    private val server = System.getenv("TS_TEST_SERVER").orEmpty()

    private val ftPort = System.getenv("TS_TEST_FT_PORT").orEmpty().toIntOrNull()

    private inner class Peer(val name: String) : TsClient.Listener {
        val lock = ReentrantLock()
        val changed = lock.newCondition()
        val model = ServerModel()
        val voices = CopyOnWriteArrayList<TsClient.Voice>()
        val messages = CopyOnWriteArrayList<Command>()
        val pokes = CopyOnWriteArrayList<Command>()
        val commands = CopyOnWriteArrayList<Command>()
        val errors = CopyOnWriteArrayList<Command>()
        var connected = false
        var disconnected: String? = "not yet"
        val client = TsClient(this) { println("[$name] $it") }
        val files = FileTransfer(client, ftPort)

        private fun update(block: () -> Unit) = lock.withLock { block(); changed.signalAll() }

        override fun onConnected(clientId: Int) = update { connected = true }
        override fun onCommand(cmd: Command) = update {
            files.onCommand(cmd)
            commands.add(cmd)
            model.apply(cmd)
            when (cmd.name) {
                "channellistfinished" -> client.sendCommand("channelsubscribeall")
                // A new channel isn't subscribed yet: without this, people moving into it just disappear.
                "notifychannelcreated" -> client.sendCommand(Commands.build("channelsubscribe", "cid" to cmd["cid"]))
                "notifytextmessage" -> messages.add(cmd)
                "notifyclientpoke" -> pokes.add(cmd)
                "error" -> if (cmd["id"] != "0" && cmd["return_code"]?.startsWith("ft-") != true) errors.add(cmd)
            }
        }
        override fun onVoice(voice: TsClient.Voice) = update { voices.add(voice) }
        override fun onDisconnected(reason: String?) = update { connected = false; disconnected = reason }

        fun await(what: String, seconds: Long = 20, check: () -> Boolean) = lock.withLock {
            val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds)
            while (!check()) {
                val left = end - System.nanoTime()
                assertTrue(left > 0, "[$name] timed out waiting: $what")
                changed.awaitNanos(left)
            }
        }
    }

    @Test
    fun twoClientsSeeAndHearEachOther() {
        assumeTrue(server.isNotEmpty(), "TS_TEST_SERVER not set")
        val host = server.substringBeforeLast(':')
        val port = server.substringAfterLast(':').toInt()

        val a = Peer("a")
        val b = Peer("b")
        val run = System.currentTimeMillis() % 10000
        a.client.connect(TsClient.Options(host, port, Identity.generate(8), "PocketTS test A$run"))
        a.await("A connected") { a.connected }
        b.client.connect(TsClient.Options(host, port, Identity.generate(8), "PocketTS test B$run"))
        b.await("B connected") { b.connected }

        val aId = a.client.clientId
        val bId = b.client.clientId
        a.await("A sees B") { a.model.clients.containsKey(bId) }
        b.await("B sees A") { b.model.clients.containsKey(aId) }
        println("Server: ${a.model.serverName}, ${a.model.channels.size} channels")
        val home = a.model.clients[aId]!!.channel
        assertEquals(home, b.model.clients[bId]!!.channel, "Both start in the default channel")

        // A moves to another channel and back; B sees both moves. With only one channel, A makes a temporary one
        // (creating it moves A there), if the server lets it.
        val other = a.model.channels.values.firstOrNull { it.id != home && !it.hasPassword && it.maxClients != 0 }
        if (other == null) {
            val errorsBefore = a.errors.size
            a.client.sendCommand(Commands.build("channelcreate", "channel_name" to "PocketTS test $run"))
            a.await("A's channel or an error") { a.model.channels.size > 1 || a.errors.size > errorsBefore }
            if (a.errors.size > errorsBefore) {
                println("Can't create a channel here (${a.errors.removeLast()["msg"]})")
            } else {
                val temp = a.model.channels.values.first { it.id != home }.id
                b.await("B sees A's channel") { b.model.channels.containsKey(temp) }
                b.await("B sees A in it") { b.model.clients[aId]?.channel == temp }
                a.client.sendCommand(Commands.build("clientmove", "cid" to home, "clid" to aId))
                b.await("B sees A come back") { b.model.clients[aId]?.channel == home }
                b.await("The empty temporary channel goes away") { !b.model.channels.containsKey(temp) }
                println("Moved through a temporary channel")
            }
        }
        other?.let { other ->
            a.client.sendCommand(Commands.build("clientmove", "cid" to other.id, "clid" to aId))
            b.await("B sees A move away") { b.model.clients[aId]?.channel == other.id }
            a.client.sendCommand(Commands.build("clientmove", "cid" to home, "clid" to aId))
            b.await("B sees A come back") { b.model.clients[aId]?.channel == home }
        }

        // A "talks": 20 frames (any bytes; the server passes them on), then stops.
        val frames = (0 until 20).map { i -> byteArrayOf(0xfc.toByte(), i.toByte(), 0x55, 0x66, 0x77) }
        for (f in frames) {
            a.client.sendVoice(4, f)
            Thread.sleep(20)
        }
        a.client.sendVoiceStop(4)
        b.await("B hears all of A") { b.voices.count { it.clientId == aId && it.data.isNotEmpty() } >= frames.size }
        val heard = b.voices.filter { it.clientId == aId && it.data.isNotEmpty() }.sortedBy { it.packetId }
        assertEquals(frames.map { it.toList() }, heard.map { it.data.toList() })
        assertTrue(heard.all { it.codec == 4 })
        b.await("B hears A stop") { b.voices.any { it.clientId == aId && it.data.isEmpty() } }

        // A chat message in the channel
        val text = "Hei | kaikki / ä ö ${'$'}run"
        a.client.sendCommand(Commands.build("sendtextmessage", "targetmode" to 2, "target" to home, "msg" to text))
        b.await("B gets the message") { b.messages.any { it["msg"] == text && it["invokerid"] == aId.toString() } }

        // A picture in the channel's files, and its chat line; B finds it from the line and downloads it
        val picture = ByteArray(5000) { (it * 31 + 7).toByte() }.also {
            byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a).copyInto(it)
        }
        val pictureName = Pictures.pictureName(Pictures.Type.PNG, "t$run")
        try {
            a.files.createDirectory(home, Pictures.PICTURE_FOLDER)
            a.files.upload(home, "${Pictures.PICTURE_FOLDER}/$pictureName", picture)
            a.client.sendCommand(Commands.build("sendtextmessage", "targetmode" to 2, "target" to home, "msg" to Pictures.pictureMessage(pictureName, home)))
            b.await("B gets the picture line") { b.messages.any { Pictures.channelPictures(it["msg"].orEmpty()).first.any { p -> p.name == pictureName } } }
            val line = b.messages.first { Pictures.channelPictures(it["msg"].orEmpty()).first.isNotEmpty() }
            val ref = Pictures.channelPictures(line["msg"]!!).first.single()
            assertContentEquals(picture, b.files.download(ref.cid, ref.path, Pictures.PICTURE_MAX_BYTES))
            println("Picture sent and received through the channel's files")
            // The file browser: the picture folder at the top of the channel's files, the picture in it with its size
            assertTrue(b.files.list(home, "/").any { it.folder && it.name == Pictures.PICTURE_FOLDER.trimStart('/') })
            val listed = b.files.list(home, Pictures.PICTURE_FOLDER).single { it.name == pictureName }
            assertEquals(picture.size.toLong(), listed.size)
            assertTrue(!listed.folder && listed.time > 0)
        } catch (e: FileTransfer.Error) {
            println("Pictures not allowed here (${e.code}: ${e.message})")
        }

        // An avatar: A puts one up, B sees the flag and finds the file by A's identity
        val avatar = picture.copyOf(2000)
        try {
            a.files.upload(0, Pictures.AVATAR_UPLOAD_NAME, avatar, overwrite = true)
            val flag = Pictures.avatarFlag(avatar)
            a.client.sendCommand(Commands.build("clientupdate", "client_flag_avatar" to flag))
            b.await("B sees A's avatar") { b.model.clients[aId]?.avatar == flag }
            val who = b.model.clients[aId]!!
            val got = Pictures.avatarFileNames(who.uid, who.avatarName).firstNotNullOfOrNull { name ->
                try { b.files.download(0, name, Pictures.AVATAR_MAX_BYTES) } catch (_: FileTransfer.Error) { null }
            }
            assertContentEquals(avatar, got)
            println("Avatar set and found")
        } catch (e: FileTransfer.Error) {
            println("Avatars not allowed here (${e.code}: ${e.message})")
        }

        // A stream's signalling (no media here: that's WebRTC on the phone): A shares, B asks to watch, A's offer
        // reaches B, B's answer and an address reach A
        val streamErrors = a.errors.size
        a.client.sendCommand(Streams.setup("PocketTS test", Streams.Kind.SCREEN, 2000, audio = false, returnCode = "stream-setup-t"))
        a.await("A's stream starts or is refused") {
            a.model.streams.containsKey(aId) || a.errors.size > streamErrors
        }
        val streamId = a.model.streams[aId]
        if (streamId == null) {
            println("Streams not allowed here (${a.errors.removeLast()["msg"]})")
        } else {
            b.await("B sees A streaming") { b.model.clients[aId]?.streaming == true || b.model.streams.containsKey(aId) }
            if (!b.model.streams.containsKey(aId)) b.client.sendCommand(Streams.requestInfo(aId, "stream-info-t"))
            b.await("B knows the stream's id") { b.model.streams[aId] == streamId }
            b.client.sendCommand(Streams.join(streamId, aId, "stream-join-t"))
            a.await("A gets B's request") { a.commands.any { it.name == "notifyjoinstreamrequest" && it["clid"] == bId.toString() } }
            val fakeOffer = "v=0\r\no=- 1 2 IN IP4 127.0.0.1\r\ns=-\r\nt=0 0\r\n"
            a.client.sendCommand(Streams.accept(streamId, bId, fakeOffer, "stream-accept-t"))
            b.await("B gets A's offer") {
                b.commands.any { it.name == "notifyrespondjoinstreamrequest" && it["offer"] == fakeOffer && it["decision"] == "1" } ||
                    b.commands.any { it.name == "notifystreamsignaling" && Streams.readSignal(it)?.sdp == fakeOffer }
            }
            b.client.sendCommand(Streams.answer(streamId, aId, "v=0\r\nanswer\r\n", "stream-answer-t"))
            a.await("A gets B's answer") {
                a.commands.any { it.name == "notifystreamsignaling" && Streams.readSignal(it)?.let { s -> s.cmd == "answer" && s.from == bId } == true }
            }
            b.client.sendCommand(Streams.candidate(streamId, aId, "candidate:1 1 UDP 2122260223 192.0.2.10 50000 typ host", "0", 0, "stream-ice-t"))
            a.await("A gets B's address") {
                a.commands.any { it.name == "notifystreamsignaling" && Streams.readSignal(it)?.cmd == "iceCandidate" }
            }
            // The test sends many commands at once; the server may ask to wait ("client is flooding")
            val before = a.commands.size
            a.client.sendCommand(Streams.stop(streamId))
            a.await("A's stop answered") { a.commands.drop(before).any { it.name == "error" } }
            a.commands.drop(before).firstOrNull { it.name == "error" && it["id"] == "524" }?.let { flood ->
                val wait = Regex("""(\d+)ms""").find(flood["extra_msg"].orEmpty())?.groupValues?.get(1)?.toLong() ?: 10_000
                println("Server asked to wait ${wait}ms (flood limit), stopping again")
                Thread.sleep(wait + 500)
                a.client.sendCommand(Streams.stop(streamId))
            }
            b.await("B sees the stream stop") { !b.model.streams.containsKey(aId) }
            println("Stream signalling went both ways")
        }
        a.errors.removeAll { it["return_code"]?.startsWith("stream-") == true || it["id"] == "524" }
        b.errors.removeAll { it["return_code"]?.startsWith("stream-") == true }

        // A private message and a poke, from B to A
        val private = "Vain sinulle ${'$'}run"
        b.client.sendCommand(Commands.build("sendtextmessage", "targetmode" to 1, "target" to aId, "msg" to private))
        a.await("A gets the private message") { a.messages.any { it["msg"] == private && it["targetmode"] == "1" } }
        b.client.sendCommand(Commands.build("clientpoke", "clid" to aId, "msg" to "Hei!"))
        a.await("A gets poked") { a.pokes.any { it["msg"] == "Hei!" && it["invokerid"] == bId.toString() } }

        assertTrue(a.errors.isEmpty() && b.errors.isEmpty(), "Errors: ${a.errors + b.errors}")

        a.client.disconnect()
        b.await("B sees A leave") { !b.model.clients.containsKey(aId) }
        a.await("A disconnected") { a.disconnected != "not yet" && !a.connected }
        assertNull(a.disconnected)
        b.client.disconnect()
        b.await("B disconnected") { !b.connected && b.disconnected != "not yet" }
        assertNull(b.disconnected)
    }
}
