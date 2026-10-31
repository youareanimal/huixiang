package site.huixiang.web

import io.github.korppujauho.pocketts.proto.Json
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.plugins.origin
import io.ktor.server.request.path
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondFile
import io.ktor.server.response.respondOutputStream
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readBytes
import io.ktor.websocket.readText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import java.io.File
import java.net.InetAddress
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * The web client's server: it holds the TeamSpeak connections the browser cannot make itself.
 *
 * A tab opens one WebSocket at `/ws` and talks to it in two shapes:
 *   · JSON text frames for everything that is state or a command (see [onText]);
 *   · binary frames for voice, `[1][codec][opus…]` up and `[2][codec][clientId:2][opus…]` down, so an Opus
 *     frame never gets parsed, copied through JSON or transcoded on the way.
 */
object Limits {
    /** Nobody needs more than a handful of tabs, and each one is a UDP socket plus a thread. */
    const val PER_IP = 4
    const val TOTAL = 96

    /** A TeamSpeak host name, IPv4 or bracketed IPv6: nothing clever gets past this. */
    val HOST = Regex("""^[A-Za-z0-9._-]{1,253}$|^\[[0-9A-Fa-f:.]{2,45}]$|^\d{1,3}(\.\d{1,3}){3}$""")

    /** The most a tab may send in one frame: a text message or one voice frame, nothing larger. */
    const val FRAME = 64 * 1024

    /** How long a new socket may stay unauthenticated before it is dropped. */
    const val AUTH_MS = 5_000L
}

/**
 * Who may use this gateway at all.
 *
 * Two checks, and they stop different people:
 *
 * · **Origin.** A browser sets this header itself and page script cannot change it, so an allow-list keeps every
 *   other site from opening a socket here inside its visitors' browsers. WebSocket has no same-origin policy of
 *   its own, so without this check any page on the internet could borrow a visitor's network to reach this box.
 *
 * · **Token.** An origin check does nothing against `curl`. A shared password does. It is compared in constant
 *   time and it lives in the server's environment, never in the bundle, so reading the front end does not reveal it.
 *
 * Neither is a login. Anyone who has the token may use the gateway as themselves; if it needs to be per-person,
 * that is an account system, not a token.
 */
class Access(val origins: Set<String>, val token: String?) {

    /** Same-origin requests and non-browser clients (no `Origin` at all) reach the token check instead. */
    fun originAllowed(call: ApplicationCall): Boolean {
        val origin = call.request.headers[HttpHeaders.Origin]?.trim()?.trimEnd('/') ?: return true
        if (origins.contains(origin)) return true
        val host = call.request.headers[HttpHeaders.Host]?.substringBefore(':') ?: return false
        return try {
            URI(origin).host?.equals(host, ignoreCase = true) == true
        } catch (e: Exception) {
            false
        }
    }

    /** Constant-time, so a wrong guess costs the same as a right one right up to the last byte. */
    fun tokenMatches(given: String?): Boolean {
        val want = token ?: return true
        if (given == null) return false
        return MessageDigest.isEqual(given.toByteArray(), want.toByteArray())
    }

    companion object {
        fun fromEnvironment(): Access = Access(
            origins = (System.getenv("ALLOWED_ORIGINS") ?: "")
                .split(',').map { it.trim().trimEnd('/') }.filter { it.isNotEmpty() }.toSet(),
            token = System.getenv("ACCESS_TOKEN")?.takeIf { it.isNotBlank() },
        )
    }
}

private val log = LoggerFactory.getLogger("web-gateway")
private val openTotal = AtomicInteger(0)
private val openPerIp = ConcurrentHashMap<String, AtomicInteger>()

/**
 * The header a transfer request names its session by.
 *
 * A header rather than a path segment, so the token never lands in a proxy's access log. A header rather than a
 * cookie, because a cookie would follow the person into every other tab of this site and outlive the session it
 * names; this one dies with the tab, in [WebSession.fileSessions].
 */
private const val FILE_TOKEN = "X-File-Token"

fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
    // 默认只监听本机：对外应该走 Nginx 的 443（wss），把 8080 直接暴露出去等于绕过 TLS。
    // 确实需要直连时再设 HOST=0.0.0.0。
    val host = System.getenv("HOST")?.takeIf { it.isNotBlank() } ?: "127.0.0.1"
    val dist = File(System.getenv("WEB_DIST") ?: "web/client/dist")
    val access = Access.fromEnvironment()
    log.info("Listening on $host:$port, serving ${dist.absolutePath} ${if (dist.isDirectory) "" else "(not built yet: run npm run build in web/client)"}")
    when {
        access.token == null -> log.warn("ACCESS_TOKEN is not set: anyone who finds this address can connect through it")
        else -> log.info("A shared access token is required to open a socket")
    }
    if (access.origins.isEmpty()) log.warn("ALLOWED_ORIGINS is not set: only same-origin pages are accepted")
    else log.info("Origins accepted: ${access.origins.joinToString(", ")}")
    embeddedServer(Netty, port = port, host = host) { gateway(dist, access) }.start(wait = true)
}

fun Application.gateway(dist: File, access: Access) {
    install(WebSockets) {
        pingPeriodMillis = 20_000
        timeoutMillis = 60_000
        maxFrameSize = Limits.FRAME.toLong()
        masking = false
    }
    // Only a browser bundle served from another origin needs CORS; the rest of the time everything is same-origin.
    if (access.origins.isNotEmpty()) {
        install(CORS) {
            allowMethod(HttpMethod.Get)
            allowMethod(HttpMethod.Post)
            allowHeader(HttpHeaders.ContentType)
            // 两个文件端点靠这个头认会话（见 WebSession.fileSessions），跨域部署时不能让预检拦下来
            allowHeader(FILE_TOKEN)
            for (o in access.origins) {
                runCatching {
                    val u = URI(o)
                    allowHost(u.host, schemes = listOfNotNull(u.scheme))
                }
            }
        }
    }

    routing {
        get("/healthz") { call.respondText("ok") }

        /**
         * A session's downloaded ts3image banner. The address carries a per-session token that only the tab the
         * state was pushed to knows, so this is not a door to anyone else's transfer.
         */
        get("/server-banner/{id}") {
            val data = WebSession.byBanner(call.parameters["id"].orEmpty())?.bannerData()
            if (data == null) {
                call.respond(HttpStatusCode.NotFound, "no banner")
            } else {
                call.respondBytes(data.first, io.ktor.http.ContentType.parse(data.second))
            }
        }

        /** What a front end needs to know before it asks for a token: whether one is needed at all. */
        get("/auth") {
            call.respondText(
                Json.write(mapOf("required" to (access.token != null))),
                io.ktor.http.ContentType.Application.Json,
            )
        }

        /**
         * 一个文件的字节：从频道文件区流到浏览器，一个字节都不落在这台机器上。
         *
         * 认 `X-File-Token`（状态快照里下发的会话令牌），它只对这一个标签页的这一个会话有效。开流之前先查
         * 一次目录：`respondOutputStream` 一旦起了头，200 就已经发了出去，此后再发现文件不存在，浏览器只能
         * 收到一个莫名其妙的半截下载。多一次往返，换一个说得清的 404 / 400 / 413。
         */
        get("/files/download") {
            val session = call.fileSession()
            if (session == null) {
                call.respond(HttpStatusCode.Unauthorized, "会话已结束，刷新页面再连一次")
                return@get
            }
            val cid = call.request.queryParameters["cid"]?.toIntOrNull()
            val path = filePath(call.request.queryParameters["path"])
            if (cid == null || path == null || path == "/") {
                call.respond(HttpStatusCode.BadRequest, "缺少文件路径")
                return@get
            }

            val entry = try {
                withContext(Dispatchers.IO) { session.findFile(cid, path) }
            } catch (e: Exception) {
                log.info("download $path of channel $cid failed: ${e.message}")
                call.respond(HttpStatusCode.BadGateway, session.fileError(e))
                return@get
            }
            when {
                entry == null -> {
                    call.respond(HttpStatusCode.NotFound, "文件不存在")
                    return@get
                }
                entry.folder -> {
                    call.respond(HttpStatusCode.BadRequest, "这是个文件夹")
                    return@get
                }
                entry.size > WebSession.MAX_FILE_BYTES -> {
                    call.respond(HttpStatusCode.PayloadTooLarge, "文件太大")
                    return@get
                }
            }

            call.respondOutputStream(
                contentType = io.ktor.http.ContentType.Application.OctetStream,
                status = HttpStatusCode.OK,
                // 说清楚总长，浏览器才能给出一个真进度；给错了它自己也会察觉（下载会断）
                contentLength = entry.size,
            ) {
                val out = this
                // 传输是阻塞的（先 ftinitdownload，再跟服务器开一条 TCP 把字节读回来），不能压在 Netty 的
                // 事件循环上，否则一个下载就能让整个网关停摆
                withContext(Dispatchers.IO) { session.downloadFile(cid, path, out, entry.size) }
            }
        }

        webSocket("/ws") {
            val ip = clientIp(call)
            if (!access.originAllowed(call)) {
                log.info("[$ip] refused: origin ${call.request.headers[HttpHeaders.Origin]}")
                close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "origin not allowed"))
                return@webSocket
            }
            if (!acquire(ip)) {
                close(CloseReason(CloseReason.Codes.TRY_AGAIN_LATER, "too many connections"))
                return@webSocket
            }
            val out = Channel<Frame>(capacity = 256)
            val sender = launch { for (f in out) send(f) }
            val session = WebSession(
                sink = object : WebSession.Sink {
                    override fun text(json: String) {
                        out.trySend(Frame.Text(json))
                    }

                    override fun binary(bytes: ByteArray) {
                        // A full queue means this tab is not keeping up: dropping a voice frame is better than
                        // letting the delay grow.
                        out.trySend(Frame.Binary(true, bytes))
                    }
                },
                // info 而不是 debug：连接、出错、每次串流的开合都在这里，出问题时这是唯一的现场
                log = { log.info("[$ip] $it") },
            )
            try {
                // The password travels in a message, not in the URL, so it stays out of proxy and server logs.
                if (access.token != null) {
                    val first = withTimeoutOrNull(Limits.AUTH_MS) { incoming.receive() }
                    if (first == null || !access.tokenMatches(tokenOf(first))) {
                        log.info("[$ip] refused: no valid token")
                        close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "unauthorized"))
                        return@webSocket
                    }
                    out.trySend(Frame.Text("""{"t":"ready"}"""))
                }
                for (frame in incoming) {
                    when (frame) {
                        is Frame.Text -> onText(session, frame.readText(), ip)
                        is Frame.Binary -> onBinary(session, frame.readBytes())
                        else -> {}
                    }
                }
            } catch (e: Exception) {
                log.debug("[$ip] socket ended: ${e.message}")
            } finally {
                session.close()
                sender.cancel()
                release(ip)
            }
        }
    }

    if (dist.isDirectory) {
        // The built bundle: a single page, so anything that is not a file goes to index.html.
        routing {
            get("{...}") {
                // 用请求路径而不是路由参数：tailcard 的参数名是 "..."，跨版本取值不稳，
                // 取不到就会把所有静态资源都当成 index.html 返回（JS 报 MIME 错）。
                val path = call.request.path().trimStart('/')
                val file = File(dist, path.ifEmpty { "index.html" })
                val safe = file.canonicalPath.startsWith(dist.canonicalPath)
                if (safe && file.isFile) {
                    call.respondFile(file)
                } else if (safe && path.isNotEmpty() && !path.contains('.')) {
                    call.respondFile(File(dist, "index.html"))
                } else {
                    call.respond(HttpStatusCode.NotFound, "Not found")
                }
            }
        }
    }
}

/**
 * The person's own address, not the proxy's.
 *
 * `wss` means something in front terminates TLS, so every socket would arrive from the proxy and the per-IP cap
 * would apply to the whole internet at once. `X-Forwarded-For` is believed only when the connection really did
 * come from this machine or a private network; trusting it from anyone would let a client name its own address
 * and walk past the cap.
 */
private fun clientIp(call: ApplicationCall): String {
    val direct = call.request.origin.remoteHost
    if (!isLocalPeer(direct)) return direct
    val forwarded = call.request.headers["X-Forwarded-For"]
        ?.split(',')?.firstOrNull()?.trim()
    return forwarded?.takeIf { it.isNotEmpty() && it.length <= 45 } ?: direct
}

/** Whether [host] is this machine or the private network a proxy would sit on. Names are not resolved. */
private fun isLocalPeer(host: String): Boolean {
    val literal = host.matches(Regex("""^\d{1,3}(\.\d{1,3}){3}$""")) || host.contains(':')
    if (!literal) return false
    return try {
        val addr = InetAddress.getByName(host.trim('[', ']'))
        addr.isLoopbackAddress || addr.isSiteLocalAddress
    } catch (e: Exception) {
        false
    }
}

/** The `token` field of an `auth` message, or null for anything else. */
private fun tokenOf(frame: Frame): String? {    val text = (frame as? Frame.Text)?.readText() ?: return null
    return try {
        val m = Json.parse(text) as? Map<*, *> ?: return null
        if (m["t"] != "auth") return null
        m["token"] as? String
    } catch (e: Exception) {
        null
    }
}

/** Which tab's session this request belongs to; null when the token names nothing alive. */
private fun ApplicationCall.fileSession(): WebSession? =
    request.headers[FILE_TOKEN]?.takeIf { it.length in 16..64 }?.let { WebSession.byFile(it) }

/**
 * A virtual path in a channel's file area: `/` or `/dir/file`. Anything that could climb out of it or is
 * absurdly long is refused here — TeamSpeak would refuse it too, but a door is cheaper shut at this end.
 */
private fun filePath(raw: String?): String? {
    val p = raw?.trim().orEmpty()
    if (p.isEmpty()) return "/"
    if (!p.startsWith('/') || p.length > 512) return null
    if (p.split('/').any { it == ".." }) return null
    return p
}

private fun acquire(ip: String): Boolean {
    if (openTotal.incrementAndGet() > Limits.TOTAL) {
        openTotal.decrementAndGet()
        return false
    }
    val n = openPerIp.computeIfAbsent(ip) { AtomicInteger(0) }
    if (n.incrementAndGet() > Limits.PER_IP) {
        n.decrementAndGet()
        openTotal.decrementAndGet()
        return false
    }
    return true
}

private fun release(ip: String) {
    openTotal.decrementAndGet()
    openPerIp[ip]?.let { if (it.decrementAndGet() <= 0) openPerIp.remove(ip, it) }
}

/** Text frames: what the tab wants. Anything unknown is ignored, so an older tab keeps working. */
private fun onText(session: WebSession, text: String, ip: String) {
    val m = try {
        Json.parse(text) as? Map<*, *> ?: return
    } catch (e: Exception) {
        log.debug("[$ip] unreadable message: ${e.message}")
        return
    }
    when (m["t"]) {
        "connect" -> {
            val host = (m["host"] as? String)?.trim().orEmpty()
            val port = (m["port"] as? Number)?.toInt() ?: 9987
            if (!Limits.HOST.matches(host) || port !in 1..65535 || isInternal(host)) {
                session.reject("这个服务器地址不被允许")
                return
            }
            session.connect(
                host = host,
                port = port,
                nickname = (m["nickname"] as? String).orEmpty(),
                password = (m["password"] as? String).orEmpty(),
                identityText = m["identity"] as? String,
                channel = (m["channel"] as? String).orEmpty(),
                channelPassword = (m["channelPassword"] as? String).orEmpty(),
            )
        }
        "leave" -> session.leave()
        "join" -> session.joinChannel((m["cid"] as? Number)?.toInt() ?: return, (m["password"] as? String).orEmpty())
        "say" -> session.sendMessage(
            (m["text"] as? String).orEmpty(),
            (m["channel"] as? Number)?.toInt() ?: 0,
            (m["client"] as? Number)?.toInt() ?: 0,
        )
        "rename" -> session.rename((m["name"] as? String).orEmpty())
        "mute" -> {
            session.setInputMuted(m["input"] == true)
            session.setOutputMuted(m["output"] == true)
        }
        "stream" -> onStream(session, m)
        "file" -> onFile(session, m)
    }
}

/**
 * 文件区：只读——这里只有一个「列一层目录」的命令，字节走上面那个 HTTP 端点。
 *
 * 分开的原因在 [WebSession.fileSessions] 上写着——一句话：WebSocket 一帧 64 KB，搬大文件要自己写分片、
 * 重组和流控，而 HTTP 这三样都是现成的。
 */
private fun onFile(session: WebSession, m: Map<*, *>) {
    if (m["act"] != "list") return
    val cid = (m["cid"] as? Number)?.toInt() ?: return
    val path = filePath(m["path"] as? String) ?: return
    session.listFiles(cid, path)
}

/**
 * Screen and camera streams: the tab's capture and WebRTC live there, so all it sends here is what the TeamSpeak
 * server has to be told, and the session descriptions it wants mended on the way.
 */
private fun onStream(session: WebSession, m: Map<*, *>) {
    val to = (m["to"] as? Number)?.toInt() ?: 0
    val sdp = (m["sdp"] as? String).orEmpty()
    when (m["act"]) {
        "share" -> session.streamShare(
            kind = (m["kind"] as? Number)?.toInt() ?: 2,
            name = (m["name"] as? String)?.take(64).orEmpty(),
            audio = m["audio"] == true,
        )
        "stop" -> session.streamStop()
        "watch" -> session.streamWatch((m["streamer"] as? Number)?.toInt() ?: 0)
        "unwatch" -> session.streamUnwatch()
        // A session description is a few kilobytes; anything much larger is not one, and the command frame has a
        // ceiling of its own.
        "offer" -> if (to > 0 && sdp.length in 1..48_000) session.streamShareOffer(to, sdp)
        "answer" -> if (to > 0 && sdp.length in 1..48_000) session.streamAnswer(to, sdp)
        "candidate" -> {
            val candidate = (m["candidate"] as? String).orEmpty()
            if (to > 0 && candidate.length in 1..2_000) {
                session.streamCandidate(to, candidate, (m["mid"] as? String), (m["mLine"] as? Number)?.toInt())
            }
        }
    }
}

/** Binary frames: voice only, up. */
private fun onBinary(session: WebSession, bytes: ByteArray) {
    if (bytes.size < 2) return
    when (bytes[0].toInt()) {
        1 -> {
            val codec = bytes[1].toInt()
            if (codec != 4 && codec != 5) return
            session.voice(codec, bytes.copyOfRange(2, bytes.size))
        }
    }
}

/**
 * A gateway that connects anywhere is an open proxy: it would let a stranger on the internet have this machine
 * reach addresses it can reach and the stranger cannot. Loopback and private ranges are refused for that reason.
 */
private fun isInternal(host: String): Boolean = try {
    val addr = InetAddress.getByName(host.trim('[', ']'))
    addr.isLoopbackAddress || addr.isSiteLocalAddress || addr.isLinkLocalAddress || addr.isAnyLocalAddress ||
        addr.isMulticastAddress
} catch (e: Exception) {
    // A name that does not resolve is not a proxy risk; the connection will say so to the person trying.
    false
}
