package site.huixiang.web

import io.github.korppujauho.pocketts.proto.Command
import io.github.korppujauho.pocketts.proto.Commands
import io.github.korppujauho.pocketts.proto.Crypto
import io.github.korppujauho.pocketts.proto.FileTransfer
import io.github.korppujauho.pocketts.proto.Identity
import io.github.korppujauho.pocketts.proto.Json
import io.github.korppujauho.pocketts.proto.Pictures
import io.github.korppujauho.pocketts.proto.ServerModel
import io.github.korppujauho.pocketts.proto.TsClient
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
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

    companion object {
        /** How often a changed state may be sent at most: people talk and move faster than a screen needs to know. */
        private const val PUSH_MS = 120L

        /** Uplink frame: [1] opus · Downlink frame: [2] opus. */
        private const val UP_VOICE = 1
        private const val DOWN_VOICE = 2

        private const val NO_PERMISSION = "2568"
        private const val WRONG_CHANNEL_PASSWORD = "781"
        private const val LEVEL_TOO_LOW = "519"
        private const val IDENTITY_ONLINE = "521"

        /**
         * The banners of the live sessions, looked up by the token in the state's banner address. One tab per server
         * downloads its own copy; two tabs on the same server cost one extra file transfer, which is fine at tab scale.
         */
        private val banners = ConcurrentHashMap<String, WebSession>()

        /**
         * Avatar image addresses are opened by `<img>`, which cannot send the file token as a header, so the address
         * carries a token of its own. This one names a session too, but it only opens avatars (channel 0), never the
         * channel file area — a proxy access log that captures it cannot read anyone's documents.
         */
        private val avatars = ConcurrentHashMap<String, WebSession>()

        /** Same ceiling tsproto applies to pictures in the chat: a banner is one of those files. */
        private const val MAX_BANNER_BYTES = Pictures.PICTURE_MAX_BYTES

        /** A browser will ask for the same visible people many times; keep their pictures for this tab. */
        private const val AVATAR_CACHE_ENTRIES = 64
        private const val AVATAR_CACHE_BYTES = 32L * 1024 * 1024

        /**
         * The sessions a file transfer may be run for, by the token in their `files` address.
         *
         * File bytes cannot go over the WebSocket: a frame is capped at [Limits.FRAME] and a 200 MB file would be
         * three thousand of them, each needing reassembly and a hand-written flow control. So the bytes go over
         * HTTP and only the commands go over the socket, which leaves one question — how does a `POST /files/upload`
         * know which tab it belongs to and therefore which TeamSpeak server to write to? This map answers it: the
         * token is a random string handed to that one tab inside its state snapshot, and forgotten with it.
         */
        private val fileSessions = ConcurrentHashMap<String, WebSession>()

        /** 一次下载最多这么大。服务器自己也有配额，这里是个防滥用的天花板，也防止一个请求把浏览器撑爆。 */
        const val MAX_FILE_BYTES = 1L shl 30 // 1 GiB

        fun byFile(id: String): WebSession? = fileSessions[id]

        fun byBanner(id: String): WebSession? = banners[id]

        fun byAvatar(id: String): WebSession? = avatars[id]
    }

    private val model = ServerModel()
    private val pushTimer = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "web-push").apply { isDaemon = true }
    }
    private val dirty = AtomicBoolean(false)
    private val bannerId = UUID.randomUUID().toString().replace("-", "")

    /**
     * The token the HTTP transfer endpoints look this session up by. Handed to the tab in every state snapshot,
     * meaningless to anyone else: it names a session, not a server, and dies with the tab.
     */
    private val fileId = UUID.randomUUID().toString().replace("-", "")

    /** The token in avatar addresses; see [avatars]. */
    private val avatarId = UUID.randomUUID().toString().replace("-", "")

    /** The tab's myTeamSpeak account: independent of [client], so it survives leaving a server. */
    private val account = AccountSession(
        push = { sink.text(it) },
        fileToken = fileId,
        log = { log("account: $it") },
    )

    /** Downloaded server avatars, by "uid|flag"; the flag changes whenever the picture does. */
    private data class CachedAvatar(val bytes: ByteArray, val type: String)
    private data class AvatarInfo(val uid: String, val avatarName: String, val flag: String)
    private val avatarCache = object : LinkedHashMap<String, CachedAvatar>(16, 0.75f, true) {}
    private var avatarCacheBytes = 0L

    /**
     * A file listing blocks on the TeamSpeak answer, so it cannot run on the connection's thread and it must not
     * wait behind an upload either — hence a pool of its own rather than one thread shared with the bytes. Two is
     * the whole point: a folder opens in the panel while another is still closing.
     */
    private val listPool = Executors.newFixedThreadPool(2) { r ->
        Thread(r, "web-file-list").apply { isDaemon = true }
    }

    /**
     * Channel passwords, by channel id, hashed the way joining hashes them (the tab only ever sends the plain
     * one). A password channel's files are behind that same password, and the hash is what the commands take.
     */
    private val channelPasswords = ConcurrentHashMap<Int, String>()

    /**
     * The password of the channel we were dropped into on connecting. It cannot go into [channelPasswords] yet —
     * the channel has no id until the server says which one we are in — so it waits here and [onCommand] files it.
     */
    @Volatile private var pendingChannelPassword = ""

    /**
     * Screen and camera streams: the talking part of them only. The pictures are WebRTC, straight between the tab
     * and the other end; this just carries the commands and session descriptions, and mends the latter.
     */
    private val streams = StreamBridge(
        sendCommand = { cmd -> client?.sendCommand(cmd) },
        push = { json -> sink.text(json) },
        ownId = { model.ownClientId },
        log = { log("$address: $it") },
    )

    @Volatile private var client: TsClient? = null
    @Volatile private var ft: FileTransfer? = null

    /** The downloaded ts3image banner, and its content type; null until it is in (or unless it is an https one). */
    @Volatile private var banner: ByteArray? = null
    @Volatile private var bannerType = "image/png"

    /** The gfx address the current [banner] belongs to: when the server changes it, the cache goes stale. */
    @Volatile private var lastBannerUrl: String? = null

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
        banners[bannerId] = this
        fileSessions[fileId] = this
        avatars[avatarId] = this
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
        channelPasswords.clear()
        pendingChannelPassword = channelPassword
        dirty.set(true)
        sink.text(Json.write(mapOf("t" to "identity", "v" to identity.export())))

        val c = TsClient(this) { log("$address: $it") }
        owned?.close()
        owned = c
        client = c
        // 新连接新横幅：上一次的缓存作废，等 initserver 带来新地址再取
        ft = FileTransfer(c)
        lastBannerUrl = null
        banner = null
        c.connect(
            TsClient.Options(
                host = host,
                port = port,
                identity = identity,
                nickname = nickname.ifBlank { "Web user" },
                serverPassword = password,
                defaultChannel = channel,
                defaultChannelPassword = channelPassword,
                // Signed in: prove the account to the server, so it can show the account's badges and signed avatar.
                myTsIdentity = account.voiceIdentity(),
            )
        )
    }

    // ====== Account: independent of the voice connection ======

    fun accountLogin(email: String, password: String, otp: String) = account.login(email, password, otp)

    fun accountLogout() = account.logout()

    fun accountRefresh() = account.refresh()

    /** Blocks on an HTTPS round trip; callers run it off Netty's event loop. */
    fun accountSetAvatar(png: ByteArray): String? = account.setAvatar(png)

    fun accountRemoveAvatar(): String? = account.removeAvatar()

    fun leave() {
        val c = client ?: return
        client = null
        streams.reset()
        c.close()
        ft?.onDisconnected()
        ft = null
        phase = Phase.IDLE
        error = null
        dirty.set(true)
    }

    fun joinChannel(channelId: Int, password: String = "") {
        val c = client ?: return
        val own = model.ownClientId
        if (own == 0) return
        // 频道密码不只用来进去：这个频道的文件区也要它，所以记下哈希，之后传文件时带上
        if (password.isNotEmpty()) channelPasswords[channelId] = Crypto.hashPassword(password)
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

    // ====== Streams: the talking part (the pictures go straight between the two ends) ======

    /** Start sharing: 1 camera, 2 screen, with the tab's own capture. */
    fun streamShare(kind: Int, name: String, audio: Boolean) = streams.share(kind, name, audio)

    fun streamStop() = streams.stopShare()

    /** Watch [streamer]: the stream id is asked from the server first, the join follows it. */
    fun streamWatch(streamer: Int) = streams.watch(streamer)

    fun streamUnwatch() = streams.unwatch()

    fun streamShareOffer(viewer: Int, sdp: String) = streams.shareOffer(viewer, sdp)

    fun streamAnswer(streamer: Int, sdp: String) = streams.answer(streamer, sdp)

    fun streamCandidate(to: Int, candidate: String, mid: String?, mLine: Int?) = streams.candidate(to, candidate, mid, mLine)

    // ====== Files: the channel's own file area, which lives on the TeamSpeak server ======
    //
    // 文件在服务器的频道文件区里（`ftgetfilelist` / `ftinitdownload` 的 cid + 虚拟路径），不在网关磁盘上，
    // 也不在浏览器缓存里。这是有意的：安卓 App 和 TeamSpeak 官方客户端读的是同一批文件，网页这边只是
    // 多开一个查看和取用的入口。**只读**——上传、删除、改名都不做，那些动的是服务器上的东西。
    //
    // 字节不走 WebSocket：一帧上限 64 KB，一个大文件要拆成上千帧，重组和流控都得自己写。所以命令走
    // WS（[listFiles]），字节走 HTTP（[downloadFile]，由 Gateway.kt 的 /files/download 调用，令牌
    // 见 [fileSessions]）。两边都靠 [FileTransfer] 做真正的事。

    /** 这个会话的传输令牌。只随状态快照发给这一个标签页，别人拿到也用不上（它认的是会话不是服务器）。 */
    fun fileToken(): String = fileId

    /**
     * 把频道 [cid] 里 [path] 这一层的内容推给标签页。立刻返回：列表要等服务器答，所以在 [listPool] 上跑，
     * 结果以一条 `files` 消息回来，形状和 `state` 一样。
     */
    fun listFiles(cid: Int, path: String) {
        val f = ft
        if (f == null) {
            sink.text(filesMessage(cid, path, emptyList(), "还没连上服务器"))
            return
        }
        try {
            listPool.execute {
                val body = try {
                    filesMessage(cid, path, f.list(cid, path, filePassword(cid)), null)
                } catch (e: Exception) {
                    log("$address: file list of $path in $cid failed: ${e.message}")
                    filesMessage(cid, path, emptyList(), fileError(e))
                }
                sink.text(body)
            }
        } catch (e: RejectedExecutionException) {
            // 会话已经关掉了，没人在等这份列表
        }
    }

    /** 下行：把频道 [cid] 的 [path] 写进 [out]，超过 [maxBytes] 的一开始就拒掉。阻塞。 */
    fun downloadFile(cid: Int, path: String, out: OutputStream, maxBytes: Long) {
        val f = ft ?: throw FileTransfer.Error("还没连上服务器")
        f.download(cid, path, out, filePassword(cid), maxBytes)
    }

    /**
     * 下载前先看一眼这一层：`respondOutputStream` 一旦开了头，200 就已经发出去了，之后才发现文件不存在的话
     * 浏览器只能看到一个莫名其妙的截断下载。多一次列目录往返，换来一句说得清的错误和一个准确的 404。
     */
    fun findFile(cid: Int, path: String): FileTransfer.Entry? {
        val f = ft ?: throw FileTransfer.Error("还没连上服务器")
        val parent = path.substringBeforeLast('/', "").ifEmpty { "/" }
        val name = path.substringAfterLast('/')
        return f.list(cid, parent, filePassword(cid)).firstOrNull { it.name == name }
    }

    /** 密码频道的文件区要同一个密码，形态是哈希（进入频道时用的是明文，见 [joinChannel]）。 */
    private fun filePassword(cid: Int): String = channelPasswords[cid].orEmpty()

    /**
     * Someone's server avatar, downloaded through the connection's file transfer on channel 0 and remembered here.
     * Null when the person has none, or their avatar file is gone: the tab then falls back to their myTeamSpeak
     * picture or an initial.
     */
    fun avatar(clientId: Int): Pair<ByteArray, String>? {
        val info = avatarInfo(clientId) ?: return null
        val key = "${info.uid}|${info.flag}"
        synchronized(avatarCache) { avatarCache[key] }?.let { return it.bytes to it.type }
        val transfer = ft ?: return null
        for (path in Pictures.avatarFileNames(info.uid, info.avatarName)) {
            try {
                val bytes = transfer.download(0, path, Pictures.AVATAR_MAX_BYTES)
                val type = Pictures.type(bytes) ?: continue
                rememberAvatar(key, CachedAvatar(bytes, type.mime))
                return bytes to type.mime
            } catch (e: Exception) {
                // Try the next name: the server does not tell which of them it has.
            }
        }
        return null
    }

    /** The model is only safe to read on the connection's own thread, so ask it there and wait a moment. */
    private fun avatarInfo(clientId: Int): AvatarInfo? {
        val c = client ?: return null
        val answer = CompletableFuture<AvatarInfo?>()
        try {
            c.post {
                val person = model.clients[clientId]
                answer.complete(
                    if (person == null || person.avatar.isBlank()) null
                    else AvatarInfo(person.uid, person.avatarName, person.avatar)
                )
            }
        } catch (_: RejectedExecutionException) {
            return null
        }
        return try {
            answer.get(2, TimeUnit.SECONDS)
        } catch (e: TimeoutException) {
            null
        } catch (e: Exception) {
            null
        }
    }

    private fun rememberAvatar(key: String, avatar: CachedAvatar) {
        synchronized(avatarCache) {
            avatarCache[key]?.let { avatarCacheBytes -= it.bytes.size }
            avatarCache[key] = avatar
            avatarCacheBytes += avatar.bytes.size
            val oldest = avatarCache.entries.iterator()
            while ((avatarCache.size > AVATAR_CACHE_ENTRIES || avatarCacheBytes > AVATAR_CACHE_BYTES) && oldest.hasNext()) {
                val entry = oldest.next()
                avatarCacheBytes -= entry.value.bytes.size
                oldest.remove()
            }
        }
    }

    /** 传输失败的原因，说人话。错误码是服务器自己的，见 tsproto 的 [FileTransfer]。 */
    fun fileError(e: Throwable): String = when ((e as? FileTransfer.Error)?.code) {
        FileTransfer.WRONG_PASSWORD -> "这个频道的文件需要频道密码"
        FileTransfer.NO_PERMISSION -> {
            // 服务器的原话里带着卡在哪一项（"insufficient client permissions (failed on i_…)"），
            // 那一项正是服务器管理员要改的东西，别把它吞掉
            val failedOn = e.message?.substringAfter("failed on ", "")?.trim(' ', '(', ')')?.takeIf { it.isNotEmpty() }
            if (failedOn != null) "没有权限操作这个频道的文件（服务器卡在 $failedOn）"
            else "没有权限操作这个频道的文件"
        }
        FileTransfer.FILE_NOT_FOUND, FileTransfer.INVALID_PATH -> "文件不存在"
        else -> when (e) {
            is java.net.SocketTimeoutException -> "传输超时"
            is java.net.ConnectException -> "连不上服务器的文件端口（通常是 30033）"
            is java.io.IOException -> "传输中断：${e.message ?: "连接断了"}"
            else -> e.message?.takeIf { it.isNotBlank() } ?: "文件传输失败"
        }
    }

    private fun filesMessage(cid: Int, path: String, entries: List<FileTransfer.Entry>, error: String?): String =
        Json.write(
            linkedMapOf(
                "t" to "files",
                "cid" to cid,
                "path" to path,
                "error" to error,
                "entries" to entries.map {
                    linkedMapOf("name" to it.name, "folder" to it.folder, "size" to it.size, "time" to it.time)
                },
            )
        )

    fun close() {
        closed = true
        banners.remove(bannerId)
        fileSessions.remove(fileId)
        avatars.remove(avatarId)
        account.close()
        leave()
        owned?.close()
        owned = null
        pushTimer.shutdownNow()
        listPool.shutdownNow()
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
        // Signed avatar, certificate and badges: optional, and only useful once the server accepted the proof.
        val c = client
        if (c != null) account.sendVoiceData { data -> if (client === c) c.sendMyTsData(data) }
    }

    override fun onCommand(cmd: Command) {
        // 文件传输的应答也走这条命令流，先让 FileTransfer 挑走它的，剩下的才进模型
        ft?.onCommand(cmd)
        when (cmd.name) {
            // Everyone on the server, not only the people in our own channel.
            "channellistfinished" -> client?.sendCommand("channelsubscribeall")
            "notifychannelcreated" -> client?.sendCommand(Commands.build("channelsubscribe", "cid" to cmd["cid"]))
            "notifytextmessage" -> {
                val from = cmd["invokerid"]?.toIntOrNull() ?: 0
                val person = model.clients[from]
                sink.text(
                    Json.write(
                        mapOf(
                            "t" to "chat",
                            "from" to from,
                            "name" to cmd["invokername"].orEmpty(),
                            "channel" to (cmd["target"]?.toIntOrNull() ?: 0),
                            "mode" to (cmd["targetmode"]?.toIntOrNull() ?: 2),
                            "text" to cmd["msg"].orEmpty(),
                            "time" to System.currentTimeMillis(),
                            "avatar" to (person?.avatar ?: ""),
                            "avatarName" to (person?.avatarName ?: ""),
                            "myTeamSpeakAvatar" to (person?.myTeamSpeakAvatar ?: ""),
                        )
                    )
                )
            }
            "error" -> {
                val id = cmd["id"]
                // 带 return_code 的是某一条命令的应答（文件传输、静默查询），该由发命令的那一方处理：
                // 空文件夹的 1281、没权限的 2568 都会这样回来，报到会话级就成了莫名其妙的红条
                if (cmd["return_code"] == null && id != null && id != "0") {
                    error = errorText(id, cmd["msg"].orEmpty(), cmd["extra_msg"])
                    dirty.set(true)
                    log("$address: server error $id ${cmd["msg"]}")
                }
            }
        }
        if (model.apply(cmd)) dirty.set(true)
        // 连上时被直接丢进某个频道的话，密码到这里才有频道 id 可挂
        if (pendingChannelPassword.isNotEmpty()) {
            val own = model.ownChannel?.id ?: 0
            if (own > 0) {
                channelPasswords.putIfAbsent(own, Crypto.hashPassword(pendingChannelPassword))
                pendingChannelPassword = ""
            }
        }
        maybeFetchBanner()
        // After the model, which also reads these (the streaming flag): the bridge only relays, it hides nothing.
        streams.onCommand(cmd)
    }

    override fun onVoice(voice: TsClient.Voice) {
        // An empty frame is "stopped talking": the tab needs to know, so it cancels the tail of the last one.
        val head = byteArrayOf(DOWN_VOICE.toByte(), voice.codec.toByte(), (voice.clientId ushr 8).toByte(), voice.clientId.toByte())
        sink.binary(head + voice.data)
    }

    override fun onPing(millis: Int) {
        // The tab adds this to its own round trip to the gateway for the latency it shows.
        sink.text(Json.write(mapOf("t" to "serverPing", "v" to millis)))
    }

    /** The tab's own round-trip probe: echo the token straight back so it can time the WebSocket hop. */
    fun pong(token: Any?) {
        sink.text(Json.write(mapOf("t" to "pong", "v" to token)))
    }

    override fun onDisconnected(reason: String?) {
        phase = Phase.IDLE
        client = null
        streams.reset()
        ft?.onDisconnected()
        ft = null
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
            "fileToken" to fileId,
            "avatarToken" to avatarId,
            "server" to serverInfo(),
            "tree" to model.tree().map { node(it) },
        )
    )

    /**
     * The details the server told about itself, for the tab's info panel. Everything comes from the
     * `virtualserver_*` variables [ServerModel] already collected (initserver / servergetvariables), so a server
     * that keeps them quiet just leaves the fields out and the panel shows less.
     */
    private fun serverInfo(): Map<String, Any?> {
        val v = model.serverVars
        val gfx = v["virtualserver_hostbanner_gfx_url"]?.trim().orEmpty()
        // https 的浏览器自己加载；ts3image 的走本会话下载好的字节，还没到就先不给
        val bannerUrl = when {
            gfx.startsWith("https://", ignoreCase = true) -> gfx
            banner != null -> "/server-banner/$bannerId"
            else -> null
        }
        return linkedMapOf(
            "maxClients" to v["virtualserver_maxclients"]?.toIntOrNull(),
            "clientsOnline" to v["virtualserver_clientsonline"]?.toIntOrNull(),
            "channelsOnline" to v["virtualserver_channelsonline"]?.toIntOrNull(),
            "uptime" to v["virtualserver_uptime"]?.toLongOrNull(),
            "receivedAt" to v["received_at"]?.toLongOrNull(),
            "createdAt" to v["virtualserver_created"]?.toLongOrNull(),
            "platform" to v["virtualserver_platform"],
            "version" to v["virtualserver_version"],
            "bannerUrl" to bannerUrl,
            "bannerLink" to v["virtualserver_hostbanner_url"]?.takeIf { it.startsWith("https://", true) },
            "hostbuttonUrl" to v["virtualserver_hostbutton_url"]?.takeIf { it.startsWith("http") },
            "hostbuttonTooltip" to v["virtualserver_hostbutton_tooltip"]?.takeIf { it.isNotBlank() },
        )
    }

    /** 服务器换了横幅地址（或刚连上）：https 的直接给浏览器，ts3image 的在后台线程拉字节。 */
    private fun maybeFetchBanner() {
        val url = model.serverVars["virtualserver_hostbanner_gfx_url"]?.trim().orEmpty()
        if (url.isEmpty() || url == lastBannerUrl) return
        lastBannerUrl = url
        banner = null
        val f = ft ?: return
        if (url.startsWith("https://", ignoreCase = true)) {
            dirty.set(true)
            return
        }
        val pic = Pictures.channelPicture(url) ?: return
        Thread {
            try {
                val bytes = f.download(pic.cid, pic.path, MAX_BANNER_BYTES)
                banner = bytes
                bannerType = bannerContentType(pic.name)
                dirty.set(true)
                log("$address: banner ${bytes.size} bytes")
            } catch (e: Exception) {
                log("$address: banner download failed: ${e.message}")
            }
        }.apply { isDaemon = true; name = "web-banner" }.start()
    }

    private fun bannerContentType(name: String): String = when {
        name.endsWith(".png", true) -> "image/png"
        name.endsWith(".gif", true) -> "image/gif"
        name.endsWith(".webp", true) -> "image/webp"
        name.endsWith(".svg", true) -> "image/svg+xml"
        else -> "image/jpeg"
    }

    /** The banner bytes for [byBanner], or null when this session has none (yet). */
    fun bannerData(): Pair<ByteArray, String>? = banner?.let { it to bannerType }

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
        "avatar" to c.avatar,
        "avatarName" to c.avatarName,
        "myTeamSpeakAvatar" to c.myTeamSpeakAvatar,
        "channelGroup" to (model.channelGroups[c.channelGroup]?.name ?: ""),
        "serverGroups" to c.serverGroups.mapNotNull { model.serverGroups[it]?.name },
    )
}
