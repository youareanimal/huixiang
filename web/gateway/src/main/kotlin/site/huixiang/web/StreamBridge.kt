package site.huixiang.web

import io.github.korppujauho.pocketts.proto.Command
import io.github.korppujauho.pocketts.proto.Json
import io.github.korppujauho.pocketts.proto.Streams
import java.util.concurrent.atomic.AtomicInteger

/**
 * Screen and camera streams, between a browser tab and the person it watches (or the people who watch it).
 *
 * The pictures never pass through this box: they are WebRTC, straight between the tab and the other end, exactly
 * as they are between two phones. What does pass is the talking part — `setupstream`, `joinstreamrequest`, and the
 * session descriptions and addresses of `streamsignaling` — and that is all this class does.
 *
 * Two things make it more than a relay:
 *
 * · **The session descriptions are mended here, not in the tab.** TeamSpeak sends H.264 as Constrained High
 *   (`profile-level-id 640cXX`), which browsers do not always list among the profiles they decode, and it applies
 *   an answer only when the answer's session version equals its own offer's. [Streams] already knows both quirks
 *   (the Android app needs them too); the tab is then left with plain WebRTC and no string surgery.
 *
 * · **Stream ids are kept here.** The tab asks to watch a person, not a stream: `requeststreaminfo` answers with
 *   the id, which the join then needs. Likewise a share has no id until `notifystreamstarted`.
 *
 * Threading: [onCommand] runs on the connection's thread, the browser's messages on the WebSocket's. Everything
 * touches the fields below under [lock]; only the two lambdas are called with it held, and both are non-blocking
 * (a command queue put, a channel trySend).
 */
class StreamBridge(
    /** Queue a command for the TeamSpeak server (the connection drops it if it is gone). */
    private val sendCommand: (String) -> Unit,
    /** Hand the tab one JSON event. */
    private val push: (String) -> Unit,
    /** Our own client id: a `notifystreamstarted` for anyone else is not our share going live. */
    private val ownId: () -> Int,
    private val log: (String) -> Unit = {},
) {
    private val lock = Any()
    private val codes = AtomicInteger(0)

    /** Our share, once the server gave it an id. */
    private var shareId: String? = null
    private var sharing = false
    private var sharePending = false

    /**
     * The people watching us, each with the H.264 values they were shown in our offer. Their answer comes back
     * with those values restored, so only they and we ever see the relabelling.
     */
    private val viewers = LinkedHashMap<Int, Map<String, String>>()

    /** The stream we watch: whose it is, its id (once known), and the offer we still owe an answer to. */
    private var watchStreamer = 0
    private var watchId: String? = null
    private var watchOriginal: Map<String, String> = emptyMap()
    private var watchOffer = ""

    /** Whether the tab holds a microphone for a camera share; only used to say so in a log line. */
    private var shareAudio = false

    // ====== What the tab asks for ======

    /** Start sharing: 1 camera, 2 screen. [audio] says the stream carries sound. */
    fun share(kind: Int, name: String, audio: Boolean) = synchronized(lock) {
        if (sharing) return@synchronized
        val k = if (kind == Streams.Kind.CAMERA.type) Streams.Kind.CAMERA else Streams.Kind.SCREEN
        val bitrate = if (k == Streams.Kind.SCREEN) 4000 else 2500
        sharing = true
        sharePending = true
        shareAudio = audio
        sendCommand(Streams.setup(name, k, bitrate, audio, code("setup")))
        log("share asked: $k $bitrate kbps${if (audio) " with sound" else ""}")
    }

    fun stopShare() = synchronized(lock) {
        val id = shareId
        shareId = null
        sharing = false
        sharePending = false
        shareAudio = false
        viewers.clear()
        if (id != null) sendCommand(Streams.stop(id))
        push(event("stopped"))
    }

    /** Watch [streamer]: the id is asked from the server, the join follows it. */
    fun watch(streamer: Int) = synchronized(lock) {
        if (streamer <= 0 || streamer == ownId()) return@synchronized
        if (watchStreamer == streamer && (watchId != null || watchOffer.isNotEmpty())) return@synchronized
        val old = watchId
        if (old != null && watchStreamer > 0) sendCommand(Streams.leave(old, watchStreamer, code("leave")))
        watchStreamer = streamer
        watchId = null
        watchOriginal = emptyMap()
        watchOffer = ""
        push(event("watching", "streamer" to streamer))
        sendCommand(Streams.requestInfo(streamer, code("info")))
    }

    fun unwatch() = synchronized(lock) {
        val id = watchId
        if (id != null && watchStreamer > 0) sendCommand(Streams.leave(id, watchStreamer, code("leave")))
        watchStreamer = 0
        watchId = null
        watchOriginal = emptyMap()
        watchOffer = ""
    }

    /**
     * The tab's offer for one of its viewers, mended for the TeamSpeak app: no turn extension (the app ignores it
     * and shows a phone's camera on its side), no payload lines the media section does not list, and every H.264
     * format as Constrained High.
     */
    fun shareOffer(viewer: Int, sdp: String) = synchronized(lock) {
        val id = shareId ?: return@synchronized
        if (viewer !in viewers) return@synchronized
        val mended = Streams.relabelShareOfferH264(Streams.withoutVideoOrientation(Streams.withoutStrayPayloadLines(sdp)))
        viewers[viewer] = mended.original
        sendCommand(Streams.accept(id, viewer, mended.sdp, code("accept")))
        // 发出去的格式清单：对方要能从这里面挑一个它解得了的
        log("offer -> viewer $viewer (${mended.sdp.length} bytes, ${videoFormats(mended.sdp)})")
    }

    /**
     * The tab's answer to the stream it watches. The session version is set to the offer's, or the TeamSpeak app
     * drops it without a word, and the H.264 values go back to what the streamer actually sends.
     */
    fun answer(streamer: Int, sdp: String) = synchronized(lock) {
        val id = watchId ?: return@synchronized
        if (streamer != watchStreamer) return@synchronized
        val mended = Streams.withOfferSessionVersion(Streams.restoreH264(sdp, watchOriginal), watchOffer)
        sendCommand(Streams.answer(id, streamer, mended, code("answer")))
        log("answer -> $streamer (${mended.length} bytes)")
    }

    /** One connection address, whichever way it travels. */
    fun candidate(to: Int, candidate: String, mid: String?, mLine: Int?) = synchronized(lock) {
        val id = shareId ?: watchId ?: return@synchronized
        if (candidate.isBlank()) return@synchronized
        sendCommand(Streams.candidate(id, to, candidate, mid, mLine, code("ice")))
    }

    /** The connection is gone: nothing of it survives into the next one. */
    fun reset() = synchronized(lock) {
        shareId = null
        sharing = false
        sharePending = false
        viewers.clear()
        watchStreamer = 0
        watchId = null
        watchOriginal = emptyMap()
        watchOffer = ""
    }

    /** Whether this tab is sharing right now (for a log line or a guard). */
    fun isSharing(): Boolean = synchronized(lock) { sharing }

    // ====== What the server says ======

    /**
     * Every command, from the connection's thread. Only the stream ones are looked at; the rest is the model's
     * business and is left alone.
     */
    fun onCommand(cmd: Command) {
        when (cmd.name) {
            "notifystreamstarted", "notifystreaminfo" -> onStarted(cmd)
            "notifystreamstopped" -> onStopped(cmd)
            "notifyjoinstreamrequest" -> onJoinRequest(cmd)
            "notifystreamclientleft" -> onViewerLeft(cmd)
            "notifyrespondjoinstreamrequest" -> onTheirOffer(cmd)
            "notifystreamsignaling" -> onSignal(cmd)
            "error" -> onError(cmd)
        }
    }

    private fun onStarted(cmd: Command) = synchronized(lock) {
        val id = cmd["id"] ?: cmd["stream_id"] ?: return@synchronized
        val clid = cmd["clid"]?.toIntOrNull() ?: return@synchronized
        if (cmd.name == "notifystreamstarted" && sharePending && clid == ownId()) {
            sharePending = false
            shareId = id
            push(event("started", "streamId" to id, "audio" to shareAudio))
            log("share live as $id")
        }
        // A stream id we asked for: the join can go now
        if (watchStreamer == clid && watchId == null) {
            watchId = id
            sendCommand(Streams.join(id, clid, code("join")))
            log("watching $clid as $id")
        }
    }

    private fun onStopped(cmd: Command) = synchronized(lock) {
        val id = cmd["id"] ?: cmd["stream_id"]
        val clid = cmd["clid"]?.toIntOrNull() ?: 0
        if (id != null && id == shareId) {
            shareId = null
            sharing = false
            sharePending = false
            viewers.clear()
            push(event("stopped"))
            log("share ended by the server")
        }
        if (watchStreamer > 0 && (id == watchId || (clid > 0 && clid == watchStreamer))) {
            watchStreamer = 0
            watchId = null
            watchOriginal = emptyMap()
            watchOffer = ""
            push(event("ended"))
            log("stream ended")
        }
    }

    /** Someone asks to watch our share: everyone may, so the tab is asked for an offer right away. */
    private fun onJoinRequest(cmd: Command) = synchronized(lock) {
        val clid = cmd["clid"]?.toIntOrNull() ?: return@synchronized
        val id = shareId ?: return@synchronized
        if (cmd["id"] != id) return@synchronized
        if (cmd["is_remove"] == "1") {
            viewers.remove(clid)
            push(event("viewerLeft", "viewer" to clid, "count" to viewers.size))
        } else {
            if (viewers.containsKey(clid)) return@synchronized
            viewers[clid] = emptyMap()
            push(event("viewer", "viewer" to clid, "count" to viewers.size))
            log("viewer $clid wants in")
        }
    }

    private fun onViewerLeft(cmd: Command) = synchronized(lock) {
        val id = shareId ?: return@synchronized
        if (cmd["id"] != id) return@synchronized
        val clid = cmd["clid"]?.toIntOrNull() ?: return@synchronized
        if (viewers.remove(clid) != null) push(event("viewerLeft", "viewer" to clid, "count" to viewers.size))
    }

    /** Their answer to our share's offer was accepted: the tab gets it with the levels it really sends. */
    private fun onSignal(cmd: Command) = synchronized(lock) {
        val s = Streams.readSignal(cmd) ?: return@synchronized
        val mine = shareId
        if (mine != null && s.streamId == mine) {
            when (s.cmd) {
                "answer" -> {
                    val sdp = s.sdp ?: return@synchronized
                    // 对方的答案里挑了什么格式：黑屏时第一眼要看的就是这里
                    log("viewer ${s.from} answered: ${videoFormats(sdp)}")
                    val mended = Streams.withShareSendLevel(Streams.restoreH264(sdp, viewers[s.from].orEmpty()))
                    push(event("answer", "from" to s.from, "sdp" to mended))
                }
                "iceCandidate" -> push(candidateEvent(s))
                "reconnect" -> push(event("reconnect", "from" to s.from))
                else -> log("share: unhandled signalling ${s.cmd}")
            }
            return@synchronized
        }
        if (watchStreamer == 0) return@synchronized
        when (s.cmd) {
            "offer", "reconnectOffer" -> s.sdp?.let { offer ->
                if (s.streamId.isNotEmpty()) watchId = s.streamId
                val mended = Streams.relabelOfferH264(offer, BROWSER_H264, otherPlayable = false)
                watchOriginal = mended.original
                watchOffer = offer
                push(event("offer", "from" to s.from, "streamId" to (watchId ?: ""), "sdp" to mended.sdp))
            }
            "iceCandidate" -> push(candidateEvent(s))
            "joinResponse" -> {
                val offer = s.args["offer"] as? String
                if (offer != null) {
                    if (s.streamId.isNotEmpty()) watchId = s.streamId
                    val mended = Streams.relabelOfferH264(offer, BROWSER_H264, otherPlayable = false)
                    watchOriginal = mended.original
                    watchOffer = offer
                    push(event("offer", "from" to s.from, "streamId" to (watchId ?: ""), "sdp" to mended.sdp))
                }
            }
            else -> log("watch: unhandled signalling ${s.cmd}")
        }
    }

    private fun candidateEvent(s: Streams.Signal) = event(
        "candidate",
        "from" to s.from,
        "candidate" to (s.candidate ?: ""),
        "mid" to (s.mid ?: "0"),
        "mLine" to (s.mLine ?: 0),
    )

    /** The streamer's offer to the tab that wants to watch it (or a later one, when they pause or go on). */
    private fun onTheirOffer(cmd: Command) = synchronized(lock) {
        if (watchStreamer == 0) return@synchronized
        val offer = cmd["offer"]
        if (cmd["decision"] != "1" || offer.isNullOrEmpty()) {
            val why = cmd["msg"]?.ifBlank { null } ?: "对方没有开播或已结束"
            watchStreamer = 0
            watchId = null
            push(event("refused", "reason" to why))
            log("join refused: $why")
            return@synchronized
        }
        cmd["id"]?.let { if (it.isNotEmpty()) watchId = it }
        val mended = Streams.relabelOfferH264(offer, BROWSER_H264, otherPlayable = false)
        watchOriginal = mended.original
        watchOffer = offer
        push(event("offer", "from" to watchStreamer, "streamId" to (watchId ?: ""), "sdp" to mended.sdp))
        log("offer from $watchStreamer (${offer.length} bytes)")
    }

    private fun onError(cmd: Command) = synchronized(lock) {
        val code = cmd["return_code"].orEmpty()
        if (!code.startsWith("stream-")) return@synchronized
        // 每条命令的应答都是一条 error，成功的也是：id=0 msg=ok。那不是拒绝，是「办好了」。
        if (cmd["id"] == "0") return@synchronized
        val why = cmd["msg"]?.ifBlank { null } ?: "服务器拒绝了"
        when {
            code.startsWith("stream-setup") -> {
                sharing = false
                sharePending = false
                shareId = null
                viewers.clear()
                push(event("failed", "what" to "share", "reason" to why))
            }
            code.startsWith("stream-info") || code.startsWith("stream-join") -> {
                watchStreamer = 0
                watchId = null
                push(event("failed", "what" to "watch", "reason" to why))
            }
            code.startsWith("stream-accept") || code.startsWith("stream-answer") || code.startsWith("stream-ice") ->
                log("signalling refused: $why ($code)")
        }
        log("refused: $why ($code)")
    }

    private fun code(tag: String) = "stream-$tag-${codes.incrementAndGet()}"

    /**
     * What a session description lists for video, for a log line: `port 9: 96=H264, 97=rtx`.
     *
     * Port 0 means the other end refused the media line — from this side, that is exactly what a black picture
     * looks like, so it is worth being able to see at a glance.
     */
    private fun videoFormats(sdp: String): String {
        val lines = sdp.lines()
        val section = lines.firstOrNull { it.trimStart().startsWith("m=video") } ?: return "no video line"
        val parts = section.trim().split(Regex("\\s+"))
        val port = parts.getOrNull(1) ?: "?"
        val names = parts.drop(3).mapNotNull { pt ->
            val rm = lines.firstOrNull { it.trimStart().startsWith("a=rtpmap:$pt ") } ?: return@mapNotNull null
            "$pt=" + rm.trim().substringAfter(' ').substringBefore('/')
        }
        return "video port $port: ${names.joinToString(",")}"
    }

    private fun event(name: String, vararg fields: Pair<String, Any?>): String {
        val m = linkedMapOf<String, Any?>("t" to "stream", "event" to name)
        for ((k, v) in fields) m[k] = v
        return Json.write(m)
    }

    private companion object {
        /**
         * The H.264 profile a browser is sure to decode: Constrained Baseline. TeamSpeak's Constrained High is
         * shown as this for the negotiation, and the answer carries the original value back to the streamer.
         */
        const val BROWSER_H264 = "42e0"
    }
}
