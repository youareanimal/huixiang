package site.huixiang.web

import io.github.korppujauho.pocketts.proto.CloudAccountTransfer
import io.github.korppujauho.pocketts.proto.CloudFailure
import io.github.korppujauho.pocketts.proto.Json
import io.github.korppujauho.pocketts.proto.MatrixCloud
import io.github.korppujauho.pocketts.proto.MyTsAccount
import java.util.LinkedHashMap
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One browser tab's TeamSpeak cloud chat, carried over Matrix.
 *
 * It is built on the *same* myTeamSpeak sign-in as [AccountSession]: without a signed-in account there is no
 * chat credential to mint, so the browser only shows the friends entry while signed in, and a Matrix session is
 * created lazily the first time the panel is opened.
 *
 * All Matrix calls run on one worker thread because [MatrixCloud] keeps mutable room state and is not thread
 * safe. A small ticker long-polls the server every few seconds with `timeout=0` (see [MatrixCloud.sync]) so it
 * never parks the worker for half a minute; new messages are diffed against [seen] and pushed as `cloudMessage`.
 *
 * The thread never touches TeamSpeak voice: the account outlives leaving a server, and so does this.
 */
class CloudChatSession(
    private val push: (String) -> Unit,
    private val log: (String) -> Unit = {},
    /** The live myTeamSpeak session, minted from it the Matrix credentials. Null while signed out. */
    private val myTsSession: () -> MyTsAccount.Session?,
    /** The account username, carried in the cloud account transfer. */
    private val username: () -> String,
    /** A snapshot of the contacts, mapping a friend identifier to its Matrix user id. */
    private val contacts: () -> List<MyTsAccount.Contact>,
) {
    companion object {
        /** Turn a raw Matrix / myTeamSpeak error code into something the browser can show. */
        fun errorText(code: String?): String? = when {
            code == null -> null
            code == "MYTS_103" || code == "MYTS_NO_SESSION" -> "账号会话已过期，请重新登录"
            code.startsWith("MYTS_CHAT_") -> "这个账号还没开通云端聊天，请先在客户端登录一次"
            code == "HTTP_401" || code == "M_UNKNOWN_TOKEN" || code == "M_MISSING_TOKEN" -> "云端聊天会话已失效，请重试"
            code == "NETWORK" || code == "HTTP_502" || code == "HTTP_503" || code == "HTTP_504" -> "连不上云端聊天服务器，稍后再试"
            code == "ENCRYPTED_ROOM" -> "这个会话是端到端加密房间，网页版暂不支持收发"
            code == "CONTACT_NO_CHAT" -> "这位好友还没有开通云端聊天"
            code == "CHAT_CREATE_UNCERTAIN" -> "刚才没能确认会话是否建好，请再点一次"
            code == "ROOM_NOT_JOINED" || code == "UNKNOWN_ROOM" -> "会话状态不对，请重新打开好友聊天"
            code == "HISTORY_EXHAUSTED" -> "没有更早的消息了"
            code == "CHAT_NOT_CONNECTED" -> "云端聊天还没连上，请稍候或重试"
            code == "CHAT_CONNECT_FAILED" -> "云端聊天连接失败，稍后再试"
            code == "CHAT_OPEN_FAILED" -> "打开会话失败，稍后再试"
            code == "CHAT_SEND_FAILED" -> "发送失败，稍后再试"
            code.startsWith("HTTP_") -> "云端聊天服务器返回了错误（$code）"
            code.startsWith("MATRIX_") -> "云端聊天返回了无法识别的数据"
            else -> "云端聊天出错了（$code）"
        }
    }

    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "web-cloud-chat").apply { isDaemon = true }
    }
    private val ticker = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "web-cloud-chat-tick").apply { isDaemon = true }
    }
    private val deviceId = "webchat" + UUID.randomUUID().toString().replace("-", "")

    @Volatile private var api: MatrixCloud? = null
    @Volatile private var session: MatrixCloud.Session? = null
    private var cursor = ""

    /** roomId + text -> transaction id, so a retried send is idempotent on the Matrix side. */
    private val pending = mutableMapOf<String, String>()

    /** Message ids already delivered, so the poller only pushes genuinely new ones. */
    private val seen = object : LinkedHashMap<String, Boolean>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?) = size > 4000
    }
    private val seenLock = Any()

    @Volatile private var phase = "idle" // idle | connecting | connected | error
    @Volatile private var error: String? = null
    @Volatile private var userId = ""
    @Volatile private var closed = false
    private val started = AtomicBoolean(false)
    private val polling = AtomicBoolean(false)

    fun connect() {
        if (closed) return
        if (phase == "connecting" || phase == "connected") { pushState(); return }
        val my = myTsSession()
        if (my == null) { phase = "error"; error = "MYTS_103"; pushState(); return }
        phase = "connecting"; error = null; pushState()
        submit {
            try {
                val creds = MyTsAccount().chatCredentials(my)
                val transfer = CloudAccountTransfer(creds.domain, creds.userId, creds.password, my.token, username())
                val cloud = MatrixCloud(creds.domain)
                try { cloud.discoverServer() } catch (e: Exception) { log("discover failed: ${e.message}") }
                val sess = cloud.login(transfer, deviceId)
                if (closed) return@submit
                api = cloud; session = sess; userId = sess.userId
                val snap = cloud.sync(sess, "", 0)
                cursor = snap.cursor
                synchronized(seenLock) { seen.clear(); for (room in cloud.current()) for (m in room.messages) seen[key(room.id, m.id)] = true }
                phase = "connected"; error = null
                pushState()
                startTicker()
            } catch (e: CloudFailure) {
                fail(e.code)
            } catch (e: Exception) {
                log("connect failed: ${e.message}")
                fail("CHAT_CONNECT_FAILED")
            }
        }
    }

    fun disconnect() {
        phase = "idle"; error = null; userId = ""
        api = null; session = null; cursor = ""
        synchronized(seenLock) { seen.clear() }
        synchronized(pending) { pending.clear() }
        pushState()
    }

    fun open(friendId: String) {
        val peer = contacts().firstOrNull { it.identifier == friendId }?.matrixId?.takeIf { it.isNotBlank() }
        if (peer == null) { pushChatError(friendId, "CONTACT_NO_CHAT"); return }
        val cloud = api; val sess = session
        if (cloud == null || sess == null) { pushChatError(friendId, "CHAT_NOT_CONNECTED"); return }
        submit {
            try {
                val opened = cloud.openDirect(sess, peer)
                // Pull the room's timeline so the first view already has the recent history, not just an empty shell.
                try { cursor = cloud.sync(sess, cursor, 0).cursor } catch (e: CloudFailure) { log("sync after open: ${e.code}") }
                val room = cloud.current().firstOrNull { it.id == opened.id } ?: opened
                synchronized(seenLock) { for (m in room.messages) seen[key(room.id, m.id)] = true }
                pushChat(room, friendId)
            } catch (e: CloudFailure) {
                pushChatError(friendId, e.code)
            } catch (e: Exception) {
                log("open failed: ${e.message}")
                pushChatError(friendId, "CHAT_OPEN_FAILED")
            }
        }
    }

    fun send(roomId: String, text: String) {
        val body = text.trim()
        if (body.isEmpty()) return
        if (body.length > 4096) { pushSend(roomId, "CHAT_SEND_FAILED", ok = false); return }
        val cloud = api; val sess = session
        if (cloud == null || sess == null) { pushSend(roomId, "CHAT_NOT_CONNECTED", ok = false); return }
        submit {
            val pk = key(roomId, body)
            val tx = synchronized(pending) { pending.getOrPut(pk) { UUID.randomUUID().toString() } }
            try {
                cloud.sendText(sess, roomId, body, tx)
                synchronized(pending) { pending.remove(pk) }
                pushSend(roomId, null, ok = true)
                drain(cloud)
            } catch (e: CloudFailure) {
                pushSend(roomId, e.code, ok = false)
            } catch (e: Exception) {
                log("send failed: ${e.message}")
                pushSend(roomId, "CHAT_SEND_FAILED", ok = false)
            }
        }
    }

    fun history(roomId: String) {
        val cloud = api; val sess = session
        if (cloud == null || sess == null) { pushChatError(roomId, "CHAT_NOT_CONNECTED"); return }
        submit {
            try {
                if (cloud.current().none { it.id == roomId }) { pushChatError(roomId, "UNKNOWN_ROOM"); return@submit }
                val msgs = cloud.history(sess, roomId)
                synchronized(seenLock) { for (m in msgs) seen[key(roomId, m.id)] = true }
                val room = cloud.current().firstOrNull { it.id == roomId } ?: return@submit
                pushChat(room, "")
            } catch (e: CloudFailure) {
                pushChatError(roomId, e.code)
            } catch (e: Exception) {
                log("history failed: ${e.message}")
                pushChatError(roomId, "HISTORY_EXHAUSTED")
            }
        }
    }

    fun markRead(roomId: String, eventId: String) {
        if (eventId.isBlank()) return
        val cloud = api ?: return; val sess = session ?: return
        submit { try { cloud.markRead(sess, roomId, eventId) } catch (e: Exception) { log("markRead failed: ${e.message}") } }
    }

    fun refresh() {
        if (phase != "connected") { pushState(); return }
        val cloud = api ?: return; val sess = session ?: return
        submit { try { cursor = cloud.sync(sess, cursor, 0).cursor; drain(cloud) } catch (e: Exception) { log("refresh failed: ${e.message}") } }
    }

    fun close() {
        closed = true
        worker.shutdownNow()
        ticker.shutdownNow()
    }

    // ====== internals ======

    private fun submit(block: () -> Unit) {
        if (closed) return
        try {
            worker.execute {
                if (closed) return@execute
                try { block() } catch (e: Exception) { log("task failed: ${e.message}") }
            }
        } catch (_: RejectedExecutionException) {
            // The tab closed.
        }
    }

    private fun startTicker() {
        if (!started.compareAndSet(false, true)) return
        try {
            ticker.scheduleWithFixedDelay({ pollOnce() }, 4, 4, TimeUnit.SECONDS)
        } catch (_: RejectedExecutionException) {
        }
    }

    private fun pollOnce() {
        if (closed || phase != "connected") return
        if (!polling.compareAndSet(false, true)) return
        submit {
            try {
                val cloud = api; val sess = session
                if (cloud != null && sess != null && phase == "connected") {
                    cursor = cloud.sync(sess, cursor, 0).cursor
                    drain(cloud)
                }
            } catch (e: CloudFailure) {
                if (e.code == "M_UNKNOWN_TOKEN" || e.code == "HTTP_401") {
                    log("token expired during poll, trying relogin")
                    if (relogin()) pushState() else fail("M_UNKNOWN_TOKEN")
                }
            } catch (e: Exception) {
                log("poll failed: ${e.message}")
            } finally {
                polling.set(false)
            }
        }
    }

    private fun key(roomId: String, id: String) = roomId + "\u0000" + id

    /** Push every message id not yet delivered; callers hold no lock while pushing. */
    private fun drain(cloud: MatrixCloud) {
        val fresh = mutableListOf<Pair<String, MatrixCloud.Message>>()
        synchronized(seenLock) {
            for (room in cloud.current()) for (m in room.messages) {
                val k = key(room.id, m.id)
                if (seen.containsKey(k)) continue
                seen[k] = true
                fresh.add(room.id to m)
            }
        }
        for ((roomId, m) in fresh) pushMessage(roomId, m)
    }

    /** Re-mint a Matrix session after the access token expired; same device, cursor rebased to avoid replay. */
    private fun relogin(): Boolean {
        val my = myTsSession() ?: return false
        return try {
            val creds = MyTsAccount().chatCredentials(my)
            val transfer = CloudAccountTransfer(creds.domain, creds.userId, creds.password, my.token, username())
            val cloud = MatrixCloud(creds.domain)
            try { cloud.discoverServer() } catch (e: Exception) { log("discover failed: ${e.message}") }
            val sess = cloud.login(transfer, deviceId)
            if (closed) return false
            api = cloud; session = sess; userId = sess.userId
            val snap = cloud.sync(sess, "", 0)
            cursor = snap.cursor
            synchronized(seenLock) { seen.clear(); for (room in cloud.current()) for (m in room.messages) seen[key(room.id, m.id)] = true }
            phase = "connected"; error = null
            true
        } catch (e: Exception) {
            log("relogin failed: ${e.message}")
            false
        }
    }

    private fun fail(code: String) {
        phase = "error"; error = code; api = null; session = null
        pushState()
    }

    // ====== pushes ======

    private fun pushState() = push(
        Json.write(
            linkedMapOf(
                "t" to "cloud",
                "phase" to phase,
                "connected" to (phase == "connected"),
                "userId" to userId,
                "error" to error,
                "errorText" to errorText(error),
            )
        )
    )

    private fun pushChat(room: MatrixCloud.Room, friendId: String) = push(
        Json.write(
            linkedMapOf(
                "t" to "cloudChat",
                "roomId" to room.id,
                "peer" to room.directPeer,
                "name" to roomName(room, friendId),
                "encrypted" to room.encrypted,
                "messages" to room.messages.map { messageJson(it) },
                "more" to room.hasMoreHistory,
                "friendId" to friendId,
                "error" to null,
                "errorText" to null,
            )
        )
    )

    private fun pushChatError(friendId: String, code: String) = push(
        Json.write(
            linkedMapOf(
                "t" to "cloudChat",
                "roomId" to null,
                "peer" to null,
                "name" to null,
                "messages" to emptyList<Any?>(),
                "more" to false,
                "friendId" to friendId,
                "error" to code,
                "errorText" to errorText(code),
            )
        )
    )

    private fun pushMessage(roomId: String, m: MatrixCloud.Message) = push(
        Json.write(
            linkedMapOf(
                "t" to "cloudMessage",
                "roomId" to roomId,
                "message" to messageJson(m),
            )
        )
    )

    private fun pushSend(roomId: String, code: String?, ok: Boolean) = push(
        Json.write(
            linkedMapOf(
                "t" to "cloudSend",
                "roomId" to roomId,
                "ok" to ok,
                "error" to code,
                "errorText" to errorText(code),
            )
        )
    )

    /** 展示用会话名：优先联系人昵称，绝不把 Matrix 用户 id（@xxx:domain）当标题。 */
    private fun roomName(room: MatrixCloud.Room, friendId: String): String {
        val list = contacts()
        val match = list.firstOrNull { friendId.isNotBlank() && it.identifier == friendId }
            ?: list.firstOrNull { room.directPeer.isNotBlank() && it.matrixId == room.directPeer }
        match?.displayName?.takeIf { it.isNotBlank() }?.let { return it }
        return room.name.takeIf { it.isNotBlank() && !it.startsWith("@") } ?: room.directPeer
    }

    private fun messageJson(m: MatrixCloud.Message): Map<String, Any?> = linkedMapOf(
        "id" to m.id,
        "sender" to m.sender,
        "body" to m.body,
        "time" to m.timestamp,
        "encrypted" to m.encrypted,
    )
}
