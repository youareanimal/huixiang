package site.huixiang.web

import io.github.korppujauho.pocketts.proto.Command
import io.github.korppujauho.pocketts.proto.Commands
import io.github.korppujauho.pocketts.proto.Identity
import io.github.korppujauho.pocketts.proto.Json
import io.github.korppujauho.pocketts.proto.ServerModel
import io.github.korppujauho.pocketts.proto.TsClient
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One browser tab's connection to a TeamSpeak server.
 *
 * The tab cannot open a UDP socket, so this class holds the real connection: it runs a [TsClient] (the very same
 * protocol code the Android app uses), keeps a [ServerModel] of the channels and people, and hands the tab a
 * snapshot of that state plus the raw Opus frames. Voice needs no transcoding: the browser's WebCodecs decodes
 * what the server sends and encodes what the microphone picks up, so a frame goes through untouched.
 *
 * Threading: [TsClient] calls its listener on its own thread and [ServerModel] is not thread-safe, so every read
 * of the model happens there too (see [push]).
 */
class WebSession(private val sink: Sink, private val log: (String) -> Unit = {}) : TsClient.Listener {

    /** Where the tab is written to: a WebSocket in practice, a list in tests. */
    interface Sink {
        fun text(json: String)
        fun binary(bytes: ByteArray)
    }

    enum class Phase { IDLE, CONNECTING, CONNECTED }

    private companion object {
        /** How often a changed state may be sent at most: people talk and move faster than a screen needs to know. */
        const val PUSH_MS = 120L

        /** Uplink frame: [1] opus · Downlink frame: [2] opus. */
        const val UP_VOICE = 1
        const val DOWN_VOICE = 2

        const val NO_PERMISSION = "2568"
        const val WRONG_CHANNEL_PASSWORD = "781"
        const val LEVEL_TOO_LOW = "519"
        const val IDENTITY_ONLINE = "521"
    }

    private val model = ServerModel()
    private val pushTimer = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "web-push").apply { isDaemon = true }
    }
    private val dirty = AtomicBoolean(false)

    @Volatile private var client: TsClient? = null

    /**
     * The last [TsClient] this session made. Kept apart from [client] because a connection that ends by itself
     * (a kick, the network going away) clears that field, and its thread still has to be let go.
     */
    @Volatile private var owned: TsClient? = null
    @Volatile private var phase = Phase.IDLE
    @Volatile private var error: String? = null
    @Volatile private var address = ""
    @Volatile private var nickname = ""
    @Volatile private var inputMuted = false
    @Volatile private var outputMuted = false
    @Volatile private var closed = false

    init {
        pushTimer.scheduleWithFixedDelay({ if (dirty.getAndSet(false)) push() }, PUSH_MS, PUSH_MS, TimeUnit.MILLISECONDS)
    }

    // ====== What the tab asks for ======

    fun connect(host: String, port: Int, nickname: String, password: String, identityText: String?, channel: String = "", channelPassword: String = "") {
        if (closed) return
        leave()
        val identity = try {
            if (identityText.isNullOrBlank()) Identity.generate() else Identity.import(identityText)
        } catch (e: Exception) {
            log("Could not read the identity the tab sent: ${e.message}")
            fail("身份数据无法读取，请重新生成")
            return
        }
        this.nickname = nickname
        address = if (port == 9987) host else "$host:$port"
        error = null
        phase = Phase.CONNECTING
        model.clear()
        dirty.set(true)
        sink.text(Json.write(mapOf("t" to "identity", "v" to identity.export())))

        val c = TsClient(this) { log("$address: $it") }
        owned?.close()
        owned = c
        client = c
        c.connect(
            TsClient.Options(
                host = host,
                port = port,
                identity = identity,
                nickname = nickname.ifBlank { "Web user" },
                serverPassword = password,
                defaultChannel = channel,
                defaultChannelPassword = channelPassword,
            )
        )
    }

    fun leave() {
        val c = client ?: return
        client = null
        c.close()
        phase = Phase.IDLE
        error = null
        dirty.set(true)
    }

    fun joinChannel(channelId: Int, password: String = "") {
        val c = client ?: return
        val own = model.ownClientId
        if (own == 0) return
        c.sendCommand(
            Commands.build("clientmove", "clid" to own, "cid" to channelId, "cpw" to password.ifEmpty { null })
        )
    }

    /** A channel (targetmode 2) or a person (1): the same text the app sends. */
    fun sendMessage(text: String, channelId: Int, clientId: Int) {
        val c = client ?: return
        if (text.isBlank()) return
        val targetMode = if (clientId > 0) 1 else 2
        val target = if (clientId > 0) clientId else channelId
        c.sendCommand(Commands.build("sendtextmessage", "targetmode" to targetMode, "target" to target, "msg" to text))
    }

    fun rename(name: String) {
        val c = client ?: return
        if (name.isBlank()) return
        nickname = name
        c.sendCommand(Commands.build("clientupdate", "client_nickname" to name))
    }

    fun setInputMuted(muted: Boolean) {
        inputMuted = muted
        syncMutes()
        dirty.set(true)
    }

    fun setOutputMuted(muted: Boolean) {
        outputMuted = muted
        syncMutes()
        dirty.set(true)
    }

    /** One Opus frame from the browser's microphone: handed to the server as it is. */
    fun voice(codec: Int, opus: ByteArray) {
        client?.sendVoice(codec, opus)
    }

    fun voiceStop(codec: Int) {
        client?.sendVoiceStop(codec)
    }

    fun close() {
        closed = true
        leave()
        owned?.close()
        owned = null
        pushTimer.shutdownNow()
    }

    /** Refused before anything was opened (a disallowed address, say): the tab shows it like any other failure. */
    fun reject(text: String) {
        error = text
        phase = Phase.IDLE
        dirty.set(true)
    }

    private fun syncMutes() {
        val c = client ?: return
        c.post {
            if (c.state != TsClient.State.CONNECTED) return@post
            c.sendCommand(
                Commands.build("clientupdate", "client_input_muted" to inputMuted, "client_output_muted" to outputMuted)
            )
        }
    }

    // ====== What the server says ======

    override fun onConnected(clientId: Int) {
        phase = Phase.CONNECTED
        error = null
        // The groups (for their icons) and the rest of the server's own details; a refusal just leaves them out.
        listOf("servergrouplist", "channelgrouplist", "servergetvariables").forEachIndexed { i, cmd ->
            client?.sendCommand(Commands.build(cmd, "return_code" to "quiet-$i"))
        }
        syncMutes()
        dirty.set(true)
    }

    override fun onCommand(cmd: Command) {
        when (cmd.name) {
            // Everyone on the server, not only the people in our own channel.
            "channellistfinished" -> client?.sendCommand("channelsubscribeall")
            "notifychannelcreated" -> client?.sendCommand(Commands.build("channelsubscribe", "cid" to cmd["cid"]))
            "notifytextmessage" -> {
                sink.text(
                    Json.write(
                        mapOf(
                            "t" to "chat",
                            "from" to (cmd["invokerid"]?.toIntOrNull() ?: 0),
                            "name" to cmd["invokername"].orEmpty(),
                            "channel" to (cmd["target"]?.toIntOrNull() ?: 0),
                            "mode" to (cmd["targetmode"]?.toIntOrNull() ?: 2),
                            "text" to cmd["msg"].orEmpty(),
                            "time" to System.currentTimeMillis(),
                        )
                    )
                )
            }
            "error" -> {
                val id = cmd["id"]
                if (id != null && id != "0") {
                    error = errorText(id, cmd["msg"].orEmpty(), cmd["extra_msg"])
                    dirty.set(true)
                    log("$address: server error $id ${cmd["msg"]}")
                }
            }
        }
        if (model.apply(cmd)) dirty.set(true)
    }

    override fun onVoice(voice: TsClient.Voice) {
        // An empty frame is "stopped talking": the tab needs to know, so it cancels the tail of the last one.
        val head = byteArrayOf(DOWN_VOICE.toByte(), voice.codec.toByte(), (voice.clientId ushr 8).toByte(), voice.clientId.toByte())
        sink.binary(head + voice.data)
    }

    override fun onDisconnected(reason: String?) {
        phase = Phase.IDLE
        client = null
        error = reason
        dirty.set(true)
        // Ended by the server or the network: this instance is done with, so its thread can go now rather than
        // waiting for the tab to close.
        owned?.close()
    }

    private fun errorText(id: String, msg: String, extra: String?): String = when (id) {
        WRONG_CHANNEL_PASSWORD -> "频道密码不正确"
        NO_PERMISSION -> msg.ifBlank { "权限不足" }
        // The server wants a stronger identity than the one this tab has: it has to be raised, which takes a while.
        LEVEL_TOO_LOW -> {
            val level = extra?.toIntOrNull() ?: 0
            "该服务器要求身份安全等级 $level，当前身份等级不够。请让本站在服务端提升身份后重试"
        }
        IDENTITY_ONLINE -> "这个身份已经在服务器上登录了（同一个身份不能同时在线两次）"
        else -> msg.ifBlank { "服务器返回错误 $id" }
    }

    private fun fail(text: String) {
        phase = Phase.IDLE
        error = text
        dirty.set(true)
    }

    // ====== Snapshots ======

    private fun push() {
        val c = client
        if (c == null) {
            sink.text(snapshotText())
            return
        }
        // On the connection's own thread: that is where the model is written.
        try {
            c.post { sink.text(snapshotText()) }
        } catch (e: java.util.concurrent.RejectedExecutionException) {
            // The connection was let go between reading the field and queueing: nothing to show anymore.
            sink.text(snapshotText())
        }
    }

    private fun snapshotText(): String = Json.write(
        linkedMapOf(
            "t" to "state",
            "phase" to phase.name.lowercase(),
            "error" to error,
            "address" to address,
            "nickname" to nickname,
            "serverName" to model.serverName,
            "welcome" to model.welcomeMessage,
            "ownId" to model.ownClientId,
            "ownChannel" to (model.ownChannel?.id ?: 0),
            "inputMuted" to inputMuted,
            "outputMuted" to outputMuted,
            "clientCount" to model.clients.values.count { it.type == 0 },
            "tree" to model.tree().map { node(it) },
        )
    )

    private fun node(n: ServerModel.Node): Map<String, Any?> = linkedMapOf(
        "id" to n.channel.id,
        "parent" to n.channel.parent,
        "name" to n.channel.name,
        "display" to n.channel.displayName,
        "spacer" to (n.channel.spacer != null),
        "password" to n.channel.hasPassword,
        "topic" to n.channel.topic,
        "codec" to n.channel.codec,
        "neededTalkPower" to n.channel.neededTalkPower,
        "maxClients" to n.channel.maxClients,
        "clients" to n.clients.map { client(it) },
        "children" to n.children.map { node(it) },
    )

    private fun client(c: ServerModel.Client): Map<String, Any?> = linkedMapOf(
        "id" to c.id,
        "nickname" to c.nickname,
        "uid" to c.uid,
        "talking" to c.talking,
        "away" to c.away,
        "inputMuted" to c.inputMuted,
        "outputMuted" to c.outputMuted,
        "talkPower" to c.talkPower,
        "isTalker" to c.isTalker,
        "priority" to c.prioritySpeaker,
        "streaming" to c.streaming,
        "channelGroup" to (model.channelGroups[c.channelGroup]?.name ?: ""),
        "serverGroups" to c.serverGroups.mapNotNull { model.serverGroups[it]?.name },
    )
}
