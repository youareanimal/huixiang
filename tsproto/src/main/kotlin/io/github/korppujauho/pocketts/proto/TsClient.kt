package io.github.korppujauho.pocketts.proto

import java.math.BigInteger
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * One connection to a TeamSpeak server over UDP: the handshake, encryption, acknowledgements and resending, pings,
 * commands in and out (in order, without duplicates) and voice packets.
 *
 * Everything runs on one thread of its own; [Listener] is called on that thread.
 */
class TsClient(private val listener: Listener, private val log: (String) -> Unit = {}) {

    interface Listener {
        fun onConnected(clientId: Int) {}
        fun onCommand(cmd: Command) {}
        fun onMyTsVoiceStatus(status: MyTsVoiceStatus) {}
        /** A voice packet: who is talking, the codec (4 Opus Voice, 5 Opus Music), and the frame (empty = stopped). */
        fun onVoice(voice: Voice) {}
        /** Round trip of a keepalive ping, in milliseconds (one goes out every second). */
        fun onPing(millis: Int) {}
        /** The connection ended: [reason] is null when we left ourselves. */
        fun onDisconnected(reason: String?) {}
    }

    class Voice(val packetId: Int, val clientId: Int, val codec: Int, val data: ByteArray, val whisper: Boolean)

    class Options(
        val host: String,
        val port: Int = 9987,
        val identity: Identity,
        val nickname: String,
        val serverPassword: String = "",
        val defaultChannel: String = "",
        val defaultChannelPassword: String = "",
        val myTsIdentity: MyTsVoiceIdentity? = null,
    )

    enum class State { DISCONNECTED, CONNECTING, CONNECTED, DISCONNECTING }

    private object Type {
        const val VOICE = 0
        const val VOICE_WHISPER = 1
        const val COMMAND = 2
        const val COMMAND_LOW = 3
        const val PING = 4
        const val PONG = 5
        const val ACK = 6
        const val ACK_LOW = 7
        const val INIT = 8
    }

    internal companion object {
        const val FLAG_FRAGMENTED = 0x10
        const val FLAG_NEWPROTOCOL = 0x20
        const val FLAG_COMPRESSED = 0x40
        const val FLAG_UNENCRYPTED = 0x80

        const val MAC = Crypto.MAC_LEN
        const val C2S_HEADER = 5 // packet id (2), client id (2), type and flags (1)
        const val S2C_HEADER = 3 // packet id (2), type and flags (1)
        const val MAX_PACKET = 500
        const val MAX_CONTENT = MAX_PACKET - MAC - C2S_HEADER
        const val RECEIVE_WINDOW = 1024

        const val RESEND_AFTER_MS = 1000L
        const val GIVE_UP_MS = 30_000L
        const val SILENCE_MS = 10_000L // pings go every second, and the server answers each

        /** Nothing ever came back from the address: most likely a wrong address or port, or the server is down. */
        const val NO_ANSWER = "Couldn't connect: no answer from the server. Check the address and port."

        // A client version the servers accept.
        const val VERSION = "3.?.? [Build: 5680278000]"
        const val PLATFORM = "Linux"
        const val VERSION_SIGN = "Hjd+N58Gv3ENhoKmGYy2bNRBsNNgm5kpiaQWxOj5HN2DXttG6REjymSwJtpJ8muC2gSwRuZi0R+8Laan5ts5CQ=="
    }

    private val exec: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "ts-connection").apply { isDaemon = true }
    }
    private val random = SecureRandom()

    @Volatile var state = State.DISCONNECTED
        private set
    @Volatile var clientId = 0
        private set

    /** The last end was the network's (no answer, network error), not the server's (kick, ban, refusal) nor ours. */
    @Volatile var lostConnection = false
        private set

    /** The server's error number when it refused the connection (such as "521": this identity is already on). */
    @Volatile var refusal: String? = null
        private set

    /**
     * How a connection ends, so the app can say it in its user's language ([Listener.onDisconnected] says it in
     * English, for logs). [endDetail] goes with it: the server's message, the address, or the error.
     */
    enum class End {
        NO_ANSWER, NO_SUCH_HOST, CANT_CONNECT, NETWORK_ERROR, NETWORK_GONE, SERVER_SILENT, SERVER_STOPPED, LOST,
        KICKED_CHANNEL, KICKED, BANNED, SHUT_DOWN, BY_SERVER, REFUSED, HANDSHAKE,
    }

    /** How the last connection ended (null when we left, or while it's on). */
    @Volatile var end: End? = null
        private set
    @Volatile var endDetail: String = ""
        private set

    /** The server's address (for file transfers), once connecting has started. */
    val serverAddress: InetAddress? get() = server?.address

    private lateinit var opts: Options
    private var myTsAuthenticated = false
    private var myTsServerConfirmed = false
    private var socket: DatagramSocket? = null
    @Volatile private var server: InetSocketAddress? = null
    private var ticker: ScheduledFuture<*>? = null
    private var pinger: ScheduledFuture<*>? = null

    // Outgoing packet ids and generations (the generation counts the 16-bit id's wraps)
    private val outId = IntArray(9)
    private val outGen = LongArray(9)
    // Incoming generation per type
    private val inGen = LongArray(9)
    // Commands in, in order: the next expected sequence number (generation * 65536 + id; -1 until the first one)
    // and those that came early
    private val nextIn = LongArray(9)
    private val early = Array(9) { HashMap<Long, Pair<ByteArray, Int>>() }

    private class Pending(val raw: ByteArray, val first: Long, var last: Long)
    private val pending = HashMap<Int, Pending>() // (type shl 16) or id -> packet waiting for its ack
    private var initPending: Pending? = null
    private var lastHeard = 0L
    private var heardAny = false // anything from the server since this connection started
    // When each keepalive ping went out; the matching pong gives the round trip. One thread only.
    private val pingSent = ArrayDeque<Long>()

    // Crypto
    private var cryptoReady = false
    private var ivStruct: ByteArray? = null
    private val fakeSignature = ByteArray(MAC)
    private var alpha: ByteArray? = null

    // Fragment reassembly
    private var fragments: MutableList<ByteArray>? = null
    private var fragmentFlags = 0

    private var disconnectReason: String? = null

    // Commands out, kept under the server's flood limit
    private val pacer = CommandPacer()
    private var drainLater: ScheduledFuture<*>? = null

    // ====== API (any thread) ======

    /**
     * Queues [block] on the connection's thread. A connection that has already been let go drops it: callers
     * reach here from their own threads, where the instance may have just been closed.
     */
    private fun onThread(block: () -> Unit) {
        try {
            exec.execute(block)
        } catch (_: java.util.concurrent.RejectedExecutionException) {
        }
    }

    fun connect(options: Options) {
        onThread { startConnect(options) }
    }

    fun disconnect(message: String = "leaving") {
        onThread {
            when (state) {
                State.CONNECTED -> {
                    state = State.DISCONNECTING
                    sendCommandNow(Commands.build("clientdisconnect", "reasonid" to 8, "reasonmsg" to message))
                    exec.schedule({ finish(null) }, 1500, TimeUnit.MILLISECONDS)
                }
                State.CONNECTING -> finish(null)
                else -> {}
            }
        }
    }

    /** The phone's network went away or changed: the connection is lost now (not after the silence runs out). */
    fun networkGone(reason: String) {
        onThread { if (state == State.CONNECTING || state == State.CONNECTED) fail(reason, End.NETWORK_GONE, reason) }
    }

    /**
     * Ends the connection and lets this instance's thread go. An instance is made for one connection, so a server
     * that keeps opening and closing them (the web gateway does) would otherwise leave a thread behind each time.
     */
    fun close(message: String = "leaving") {
        if (exec.isShutdown) return
        disconnect(message)
        // Queued behind the disconnect, which may have scheduled its own end: the thread stops once it is done.
        try {
            exec.schedule({ exec.shutdown() }, 3, TimeUnit.SECONDS)
        } catch (_: java.util.concurrent.RejectedExecutionException) {
        }
    }

    /** Runs [block] on the connection's thread (where the listener is called), after what was asked before. */
    fun post(block: () -> Unit) {
        onThread(block)
    }

    fun sendCommand(cmd: String) {
        onThread {
            if (state != State.CONNECTED) return@onThread
            pacer.add(cmd)
            drain()
        }
    }

    fun sendMyTsData(data: MyTsVoiceData) {
        exec.execute {
            if (state == State.CONNECTED && myTsAuthenticated) {
                pacer.add(data.command())
                drain()
            }
        }
    }

    /** One Opus frame; [codec] should be the channel's (4 Opus Voice or 5 Opus Music). */
    fun sendVoice(codec: Int, opus: ByteArray) {
        onThread {
            if (state != State.CONNECTED) return@onThread
            val buf = ByteArray(3 + opus.size)
            buf[2] = codec.toByte()
            opus.copyInto(buf, 3)
            sendOutgoing(buf, Type.VOICE)
        }
    }

    /** The end of talking: an empty voice packet. */
    fun sendVoiceStop(codec: Int) = sendVoice(codec, ByteArray(0))

    // ====== Connecting ======

    private fun startConnect(options: Options) {
        if (state != State.DISCONNECTED) return
        opts = options
        state = State.CONNECTING
        lostConnection = false
        clientId = 0
        cryptoReady = false
        myTsAuthenticated = false
        myTsServerConfirmed = false
        ivStruct = null
        fakeSignature.fill(0)
        alpha = null
        outId.fill(0); outGen.fill(0); inGen.fill(0); nextIn.fill(-1)
        early.forEach { it.clear() }
        pingSent.clear()
        pending.clear()
        initPending = null
        fragments = null
        disconnectReason = null
        heardAny = false
        end = null
        endDetail = ""

        try {
            val address = InetSocketAddress(InetAddress.getByName(options.host), options.port)
            server = address
            val s = DatagramSocket()
            try {
                s.receiveBufferSize = 1 shl 20
                s.sendBufferSize = 1 shl 20
            } catch (_: Exception) {
            }
            s.connect(address)
            socket = s
            Thread({ receiveLoop(s) }, "ts-receive").apply { isDaemon = true }.start()
        } catch (e: Exception) {
            log("Can't reach ${options.host}:${options.port}: $e")
            state = State.DISCONNECTED
            if (e is java.net.UnknownHostException) {
                end = End.NO_SUCH_HOST
                endDetail = options.host
            } else {
                end = End.CANT_CONNECT
                endDetail = e.message ?: e.javaClass.simpleName
            }
            listener.onDisconnected(
                if (e is java.net.UnknownHostException) "Couldn't connect: no server found at \"${options.host}\". Check the address."
                else "Couldn't connect: ${e.message ?: e.javaClass.simpleName}"
            )
            return
        }

        lastHeard = now()
        ticker = exec.scheduleWithFixedDelay({ tick() }, 100, 100, TimeUnit.MILLISECONDS)
        // The first command (clientinitiv) travels inside the handshake, but still uses up command id 0.
        nextOutId(Type.COMMAND)
        sendInit(init0())
    }

    private fun receiveLoop(s: DatagramSocket) {
        val buf = ByteArray(4096)
        while (!s.isClosed) {
            try {
                val p = DatagramPacket(buf, buf.size)
                s.receive(p)
                val data = buf.copyOf(p.length)
                onThread { if (socket === s) handlePacket(data) }
            } catch (e: Exception) {
                // Nothing listens on that port (the computer said so before the server ever answered)
                val noServer = e is java.net.PortUnreachableException
                if (!s.isClosed) onThread { if (socket === s) if (noServer && !heardAny) fail(NO_ANSWER, End.NO_ANSWER) else fail("Network error: ${e.message}", End.NETWORK_ERROR, e.message.orEmpty()) }
                return
            }
        }
    }

    private fun fail(reason: String, kind: End, detail: String = "", network: Boolean = true) {
        log("Disconnected: $reason")
        lostConnection = network
        end = kind
        endDetail = detail
        finish(reason)
    }

    private fun finish(reason: String?) {
        if (state == State.DISCONNECTED) return
        state = State.DISCONNECTED
        ticker?.cancel(false); ticker = null
        pinger?.cancel(false); pinger = null
        drainLater?.cancel(false); drainLater = null
        pacer.clear()
        socket?.close(); socket = null
        listener.onDisconnected(reason ?: disconnectReason)
    }

    private fun tick() {
        val t = now()
        initPending?.let { p ->
            if (t - p.first > GIVE_UP_MS) return if (heardAny) fail("The server didn't answer", End.SERVER_SILENT) else fail(NO_ANSWER, End.NO_ANSWER)
            if (t - p.last > RESEND_AFTER_MS) { p.last = t; sendRaw(p.raw) }
        }
        for (p in pending.values) {
            if (t - p.first > GIVE_UP_MS) return fail("The server stopped answering", End.SERVER_STOPPED)
            if (t - p.last > RESEND_AFTER_MS) { p.last = t; sendRaw(p.raw) }
        }
        // Silence before the server ever answered isn't a lost connection: there never was one
        if (t - lastHeard > SILENCE_MS) { if (heardAny) fail("Connection lost", End.LOST) else fail(NO_ANSWER, End.NO_ANSWER) }
    }

    // ====== Sending ======

    private fun nextOutId(type: Int): Pair<Int, Long> {
        val id = outId[type]
        val gen = outGen[type]
        outId[type] = (id + 1) and 0xffff
        if (outId[type] == 0) outGen[type]++
        return id to gen
    }

    private fun init0(): ByteArray {
        val b = ByteBuffer.allocate(21)
        b.putInt(Crypto.INIT_VERSION.toInt())
        b.put(0)
        b.putInt((System.currentTimeMillis() / 1000).toInt())
        b.put(ByteArray(4).also { random.nextBytes(it) })
        return b.array()
    }

    private fun sendInit(data: ByteArray) {
        val raw = rawPacket(data, Type.INIT, 101, FLAG_UNENCRYPTED)
        Crypto.INIT_MAC.copyInto(raw, 0)
        initPending = Pending(raw, now(), now())
        sendRaw(raw)
    }

    /** Sends the commands that may go now, and comes back when the next one may. */
    private fun drain() {
        if (state != State.CONNECTED) return
        for (cmd in pacer.take(now())) sendCommandNow(cmd)
        val wait = pacer.waitMs(now()) ?: return
        if (drainLater != null) return
        drainLater = exec.schedule({
            drainLater = null
            drain()
        }, wait, TimeUnit.MILLISECONDS)
    }

    private fun sendCommandNow(cmd: String) {
        val data = cmd.toByteArray(Charsets.UTF_8)
        if (data.size <= MAX_CONTENT) {
            sendOutgoing(data, Type.COMMAND)
            return
        }
        // Too big for one packet: fragments, flagged on the first and the last
        val chunks = (data.indices step MAX_CONTENT).map { data.copyOfRange(it, minOf(it + MAX_CONTENT, data.size)) }
        chunks.forEachIndexed { i, chunk ->
            sendOutgoing(chunk, Type.COMMAND, if (i == 0 || i == chunks.size - 1) FLAG_FRAGMENTED else 0)
        }
    }

    private fun sendOutgoing(data: ByteArray, type: Int, extraFlags: Int = 0) {
        val (id, gen) = nextOutId(type)
        if (type == Type.PING) {
            // A server that stops answering pings while still sending other things shouldn't grow this forever.
            if (pingSent.size >= 16) pingSent.removeFirst()
            pingSent.addLast(now())
        }
        var flags = extraFlags
        when (type) {
            Type.VOICE, Type.VOICE_WHISPER -> { data[0] = (id shr 8).toByte(); data[1] = id.toByte() }
            Type.COMMAND, Type.COMMAND_LOW -> flags = flags or FLAG_NEWPROTOCOL
            Type.PING, Type.PONG -> flags = flags or FLAG_UNENCRYPTED
        }
        val raw = rawPacket(data, type, id, flags)
        if (flags and FLAG_UNENCRYPTED != 0) {
            fakeSignature.copyInto(raw, 0)
        } else {
            val kn = if (cryptoReady) keyNonce(false, id, gen, type) else Crypto.KeyNonce(Crypto.DUMMY_KEY, Crypto.DUMMY_NONCE)
            val header = raw.copyOfRange(MAC, MAC + C2S_HEADER)
            val sealed = Crypto.eaxEncrypt(kn.key, kn.nonce, header, data)
            sealed.mac.copyInto(raw, 0)
            sealed.ciphertext.copyInto(raw, MAC + C2S_HEADER)
        }
        if (type == Type.COMMAND || type == Type.COMMAND_LOW) pending[(type shl 16) or id] = Pending(raw, now(), now())
        sendRaw(raw)
    }

    private fun rawPacket(data: ByteArray, type: Int, id: Int, flags: Int): ByteArray {
        val raw = ByteArray(MAC + C2S_HEADER + data.size)
        raw[MAC] = (id shr 8).toByte()
        raw[MAC + 1] = id.toByte()
        raw[MAC + 2] = (clientId shr 8).toByte()
        raw[MAC + 3] = clientId.toByte()
        raw[MAC + 4] = ((flags and 0xf0) or (type and 0x0f)).toByte()
        data.copyInto(raw, MAC + C2S_HEADER)
        return raw
    }

    private fun sendRaw(raw: ByteArray) {
        val s = socket ?: return
        try {
            s.send(DatagramPacket(raw, raw.size))
        } catch (e: Exception) {
            log("Send failed: $e")
        }
    }

    private fun keyNonce(fromServer: Boolean, id: Int, gen: Long, type: Int): Crypto.KeyNonce {
        val iv = ivStruct ?: return Crypto.KeyNonce(Crypto.DUMMY_KEY, Crypto.DUMMY_NONCE)
        return Crypto.deriveKeyNonce(fromServer, id, gen, type, iv)
    }

    // ====== Receiving ======

    private fun handlePacket(raw: ByteArray) {
        if (raw.size < MAC + S2C_HEADER) return
        val id = ((raw[MAC].toInt() and 0xff) shl 8) or (raw[MAC + 1].toInt() and 0xff)
        val typeByte = raw[MAC + 2].toInt() and 0xff
        val type = typeByte and 0x0f
        val flags = typeByte and 0xf0
        if (type > Type.INIT) return

        // The server's generation goes up when its 16-bit id wraps. Rather than guess that from ids (reordering
        // would fool it), a packet that doesn't decrypt is tried with the next generation; the MAC proves it right.
        var gen = inGen[type]
        var data = decrypt(raw, type, id, gen, flags)
        if (data == null && flags and FLAG_UNENCRYPTED == 0 && type != Type.INIT) {
            data = decrypt(raw, type, id, gen + 1, flags)
            if (data != null) { gen++; inGen[type] = gen }
        }
        if (data == null) {
            log("Dropped a packet that didn't decrypt (type $type id $id)")
            return
        }
        lastHeard = now()
        heardAny = true

        when (type) {
            Type.INIT -> handleInit(data)
            Type.COMMAND, Type.COMMAND_LOW -> {
                val ack = ByteArray(2).also { it[0] = (id shr 8).toByte(); it[1] = id.toByte() }
                sendOutgoing(ack, if (type == Type.COMMAND) Type.ACK else Type.ACK_LOW)
                receiveInOrder(type, gen * 65536 + id, data, flags)
            }
            Type.ACK, Type.ACK_LOW -> if (data.size >= 2) {
                val acked = ((data[0].toInt() and 0xff) shl 8) or (data[1].toInt() and 0xff)
                val ackedType = if (type == Type.ACK) Type.COMMAND else Type.COMMAND_LOW
                pending.remove((ackedType shl 16) or acked)
            }
            Type.PING -> sendOutgoing(ByteArray(2).also { it[0] = (id shr 8).toByte(); it[1] = id.toByte() }, Type.PONG)
            Type.PONG -> {
                // The server answers every keepalive ping, so the oldest unanswered one is this pong's match.
                val sentAt = pingSent.removeFirstOrNull()
                if (sentAt != null) listener.onPing((now() - sentAt).toInt().coerceAtLeast(0))
            }
            Type.VOICE, Type.VOICE_WHISPER -> if (data.size >= 5) {
                listener.onVoice(
                    Voice(
                        packetId = ((data[0].toInt() and 0xff) shl 8) or (data[1].toInt() and 0xff),
                        clientId = ((data[2].toInt() and 0xff) shl 8) or (data[3].toInt() and 0xff),
                        codec = data[4].toInt() and 0xff,
                        data = data.copyOfRange(5, data.size),
                        whisper = type == Type.VOICE_WHISPER,
                    )
                )
            }
        }
    }

    private fun decrypt(raw: ByteArray, type: Int, id: Int, gen: Long, flags: Int): ByteArray? {
        if (type == Type.INIT) {
            if (!raw.copyOf(MAC).contentEquals(Crypto.INIT_MAC)) return null
            return raw.copyOfRange(MAC + S2C_HEADER, raw.size)
        }
        if (flags and FLAG_UNENCRYPTED != 0) {
            if (cryptoReady && !raw.copyOf(MAC).contentEquals(fakeSignature)) return null
            return raw.copyOfRange(MAC + S2C_HEADER, raw.size)
        }
        // Around the switch to the connection's own keys a packet may still use the other ones: try both.
        return tryDecrypt(raw, type, id, gen, !cryptoReady) ?: tryDecrypt(raw, type, id, gen, cryptoReady)
    }

    private fun tryDecrypt(raw: ByteArray, type: Int, id: Int, gen: Long, dummy: Boolean): ByteArray? {
        val kn = if (dummy) Crypto.KeyNonce(Crypto.DUMMY_KEY, Crypto.DUMMY_NONCE) else keyNonce(true, id, gen, type)
        val header = raw.copyOfRange(MAC, MAC + S2C_HEADER)
        return Crypto.eaxDecrypt(kn.key, kn.nonce, header, raw.copyOfRange(MAC + S2C_HEADER, raw.size), raw.copyOf(MAC))
    }

    /** Commands are handled in the order the server sent them, each once (a resend of one we have is dropped). */
    private fun receiveInOrder(type: Int, seq: Long, data: ByteArray, flags: Int) {
        if (nextIn[type] < 0) nextIn[type] = seq
        val next = nextIn[type]
        if (seq < next || seq >= next + RECEIVE_WINDOW) return
        early[type][seq] = data to flags
        while (true) {
            val (d, f) = early[type].remove(nextIn[type]) ?: break
            nextIn[type]++
            receiveCommandPart(d, f)
            if (state == State.DISCONNECTED) return
        }
    }

    private fun receiveCommandPart(data: ByteArray, flags: Int) {
        val parts = fragments
        if (flags and FLAG_FRAGMENTED != 0) {
            if (parts == null) {
                fragments = mutableListOf(data)
                fragmentFlags = flags
            } else {
                parts.add(data)
                fragments = null
                val whole = ByteArray(parts.sumOf { it.size })
                var o = 0
                for (p in parts) { p.copyInto(whole, o); o += p.size }
                processCommand(whole, fragmentFlags)
            }
            return
        }
        if (parts != null) {
            parts.add(data)
            return
        }
        processCommand(data, flags)
    }

    private fun processCommand(raw: ByteArray, flags: Int) {
        val data = if (flags and FLAG_COMPRESSED != 0) {
            try {
                QuickLz.decompress(raw)
            } catch (e: Exception) {
                log("Couldn't decompress a command: ${e.message}")
                return
            }
        } else raw
        val cmd = try {
            Commands.parse(String(data, Charsets.UTF_8))
        } catch (e: Exception) {
            log("Couldn't read a command: ${e.message}")
            return
        }

        // "Client is flooding": the pacer sends that command again after the wait the server asked for
        if (cmd.name == "error" && state == State.CONNECTED && pacer.onError(cmd, now())) {
            log("Flood limit: a command goes again shortly")
            drainLater?.cancel(false)
            drainLater = null
            drain()
            return
        }

        when (cmd.name) {
            "initivexpand" -> initIvExpand(cmd)
            "initivexpand2" -> initIvExpand2(cmd)
            "initserver" -> initServer(cmd)
            "notifyclientleftview" -> if (cmd.groups.any { it["clid"]?.toIntOrNull() == clientId }) {
                val msg = cmd["reasonmsg"].orEmpty()
                end = when (cmd["reasonid"]) {
                    "4" -> End.KICKED_CHANNEL
                    "5" -> End.KICKED
                    "6" -> End.BANNED
                    "11" -> End.SHUT_DOWN
                    else -> if (state == State.DISCONNECTING) null else End.BY_SERVER
                }
                endDetail = msg
                disconnectReason = when (cmd["reasonid"]) {
                    "4" -> "Kicked from the channel".let { if (msg.isNotEmpty()) "$it: $msg" else it }
                    "5" -> "Kicked from the server".let { if (msg.isNotEmpty()) "$it: $msg" else it }
                    "6" -> "Banned from the server".let { if (msg.isNotEmpty()) "$it: $msg" else it }
                    "11" -> "The server shut down".let { if (msg.isNotEmpty()) "$it: $msg" else it }
                    else -> if (state == State.DISCONNECTING) null else msg.ifEmpty { "Disconnected by the server" }
                }
            }
            "error" -> if (state == State.CONNECTING && cmd["id"] != "0") {
                // While connecting, an error answers clientinit: wrong password, banned, and so on.
                refusal = cmd["id"]
                listener.onCommand(cmd)
                return fail(cmd["msg"]?.ifEmpty { null } ?: "The server refused the connection (${cmd["id"]})", End.REFUSED, cmd["msg"].orEmpty(), network = false)
            }
        }
        if (state == State.CONNECTED && myTsAuthenticated) {
            if (cmd.name in setOf("clientvariables", "notifyclientupdated")) {
                for (fields in cmd.groups) {
                    if (fields["clid"]?.toIntOrNull() == clientId && "client_myteamspeak_id" in fields) {
                        myTsServerConfirmed = opts.myTsIdentity?.matchesServerId(
                            fields["client_myteamspeak_id"].orEmpty()) == true
                        listener.onMyTsVoiceStatus(if (myTsServerConfirmed)
                            MyTsVoiceStatus.SERVER_CONFIRMED else MyTsVoiceStatus.SERVER_NOT_CONFIRMED)
                    }
                }
            } else if (cmd.name == "error" && cmd["return_code"] == "quiet-myts-verify" && !myTsServerConfirmed) {
                listener.onMyTsVoiceStatus(MyTsVoiceStatus.SERVER_NOT_CONFIRMED)
            }
        }
        listener.onCommand(cmd)
        if (cmd.name == "notifyclientleftview" && cmd.groups.any { it["clid"]?.toIntOrNull() == clientId }) finish(disconnectReason)
    }

    // ====== Handshake ======

    private fun handleInit(data: ByteArray) {
        if (data.isEmpty()) return
        when (data[0].toInt() and 0xff) {
            1 -> {
                if (data.size < 21) return
                val b = ByteBuffer.allocate(25)
                b.putInt(Crypto.INIT_VERSION.toInt())
                b.put(2)
                b.put(data, 1, 20)
                sendInit(b.array())
            }
            3 -> {
                if (data.size < 1 + 64 + 64 + 4 + 100) return
                val x = data.copyOfRange(1, 65)
                val n = data.copyOfRange(65, 129)
                val level = ByteBuffer.wrap(data, 129, 4).int
                val serverData = data.copyOfRange(133, 233)

                // The server's puzzle: y = x^(2^level) mod n
                val nBig = BigInteger(1, n)
                var y = BigInteger(1, x).mod(nBig)
                repeat(level) { y = y.multiply(y).mod(nBig) }
                val yBytes = toFixed(y, 64)

                val a = ByteArray(10).also { random.nextBytes(it) }
                alpha = a
                val initiv = Commands.build(
                    "clientinitiv",
                    "alpha" to Base64.getEncoder().encodeToString(a),
                    "omega" to opts.identity.publicKeyString,
                    "ot" to 1,
                    "ip" to "",
                ).toByteArray(Charsets.UTF_8)

                val b = ByteBuffer.allocate(4 + 1 + 64 + 64 + 4 + 100 + 64 + initiv.size)
                b.putInt(Crypto.INIT_VERSION.toInt())
                b.put(4)
                b.put(x); b.put(n); b.putInt(level); b.put(serverData); b.put(yBytes); b.put(initiv)
                sendInit(b.array())
            }
            0x7f -> sendInit(init0()) // the server wants to start over
        }
    }

    /** The older handshake: the secret comes from ECDH with the server's key. */
    private fun initIvExpand(cmd: Command) {
        val a = alpha ?: return
        initPending = null
        val beta = Base64.getDecoder().decode(cmd["beta"] ?: return fail("Bad handshake", End.HANDSHAKE))
        val omega = Base64.getDecoder().decode(cmd["omega"] ?: return fail("Bad handshake", End.HANDSHAKE))
        val shared = opts.identity.sharedSecret(omega)
        val iv = ByteArray(10 + beta.size)
        for (i in 0 until 10) iv[i] = (shared[i].toInt() xor a[i].toInt()).toByte()
        for (i in beta.indices) iv[10 + i] = (shared[10 + i].toInt() xor beta[i].toInt()).toByte()
        startEncryption(iv)
    }

    /** The newer handshake: the server's key comes from its license chain, ours is a one-time key. */
    private fun initIvExpand2(cmd: Command) {
        val a = alpha
        if (cryptoReady || a == null) return
        initPending = null
        val license = cmd["l"]
        val betaB64 = cmd["beta"]
        if (license.isNullOrEmpty() || betaB64.isNullOrEmpty() || cmd["omega"].isNullOrEmpty()) return fail("Bad handshake", End.HANDSHAKE)
        try {
            val beta = Base64.getDecoder().decode(betaB64)
            val serverKey = License.deriveKey(License.parse(Base64.getDecoder().decode(license)))
            val temp = License.temporaryKey(random)
            val proof = opts.identity.sign(temp.publicKey + beta)
            sendCommandNow(
                Commands.build(
                    "clientek",
                    "ek" to Base64.getEncoder().encodeToString(temp.publicKey),
                    "proof" to Base64.getEncoder().encodeToString(proof),
                )
            )
            val iv = License.sharedSecret(serverKey, temp.privateKey)
            Crypto.xorInto(iv, a, 10)
            Crypto.xorInto(iv, beta, beta.size, 10)
            startEncryption(iv)
        } catch (e: Exception) {
            fail("Handshake failed: ${e.message}", End.HANDSHAKE, e.message.orEmpty(), network = false)
        }
    }

    private fun startEncryption(iv: ByteArray) {
        ivStruct = iv
        Crypto.sha1(iv).copyInto(fakeSignature, 0, 0, MAC)
        cryptoReady = true
        alpha = null
        val o = opts
        val accountFields = try { o.myTsIdentity?.authentication(iv).orEmpty() }
            catch (_: Exception) { log("myTeamSpeak voice proof unavailable (MYTS_VOICE_PROOF_INVALID)"); emptyMap() }
        myTsAuthenticated = accountFields.isNotEmpty()
        if (o.myTsIdentity != null) listener.onMyTsVoiceStatus(
            if (myTsAuthenticated) MyTsVoiceStatus.PROOF_SENT else MyTsVoiceStatus.PROOF_UNAVAILABLE)
        sendCommandNow(
            Commands.build(
                "clientinit", linkedMapOf<String, Any?>(
                "client_nickname" to o.nickname,
                "client_version" to VERSION,
                "client_platform" to PLATFORM,
                "client_input_hardware" to 1,
                "client_output_hardware" to 1,
                "client_default_channel" to o.defaultChannel,
                "client_default_channel_password" to Crypto.hashPassword(o.defaultChannelPassword),
                "client_server_password" to Crypto.hashPassword(o.serverPassword),
                "client_meta_data" to "",
                "client_version_sign" to VERSION_SIGN,
                "client_key_offset" to o.identity.keyOffset,
                "client_nickname_phonetic" to "",
                "client_default_token" to "",
                "hwid" to o.identity.hwid,
            ) + accountFields)
        )
    }

    private fun initServer(cmd: Command) {
        clientId = cmd["aclid"]?.toIntOrNull() ?: 0
        state = State.CONNECTED
        log("Connected, client id $clientId")
        pinger = exec.scheduleWithFixedDelay({
            if (state == State.CONNECTED) sendOutgoing(ByteArray(0), Type.PING)
        }, 1000, 1000, TimeUnit.MILLISECONDS)
        listener.onConnected(clientId)
        if (myTsAuthenticated) sendCommand(Commands.build("clientgetvariables",
            "clid" to clientId, "return_code" to "quiet-myts-verify"))
    }

    private fun toFixed(v: BigInteger, size: Int): ByteArray {
        val b = v.toByteArray()
        return when {
            b.size == size -> b
            b.size > size -> b.copyOfRange(b.size - size, b.size)
            else -> ByteArray(size - b.size) + b
        }
    }

    private fun now() = System.nanoTime() / 1_000_000
}
