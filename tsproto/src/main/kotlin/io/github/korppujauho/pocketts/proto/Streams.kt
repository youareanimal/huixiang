package io.github.korppujauho.pocketts.proto

/**
 * TeamSpeak 6 streams (screen and camera), as WebTS speaks them: the commands, the signalling messages, and the
 * small changes the session descriptions need for the TeamSpeak app.
 *
 * Watching: `joinstreamrequest` → the streamer's offer (in `notifyrespondjoinstreamrequest`, or as signalling) →
 * our answer and connection addresses as `streamsignaling`. Sharing: `setupstream` → `notifystreamstarted` (our
 * stream's id) → each viewer's `notifyjoinstreamrequest` → `respondjoinstreamrequest` with our offer → their answer
 * and addresses as signalling.
 */
object Streams {
    /** TeamSpeak's own STUN servers (as its app uses). */
    val STUN = listOf("stun:turn.teamspeak.com:3478", "stun:turn2.teamspeak.com:3478")

    enum class Kind(val type: Int) { CAMERA(1), SCREEN(2) }

    fun join(streamId: String, streamerId: Int, returnCode: String) =
        Commands.build("joinstreamrequest", "id" to streamId, "clid" to streamerId, "msg" to "", "is_remove" to 0, "return_code" to returnCode)

    fun leave(streamId: String, streamerId: Int, returnCode: String) =
        Commands.build("joinstreamrequest", "id" to streamId, "clid" to streamerId, "msg" to "", "is_remove" to 1, "return_code" to returnCode)

    fun requestInfo(clientId: Int, returnCode: String) = Commands.build("requeststreaminfo", "clid" to clientId, "return_code" to returnCode)

    /** Our share: 1 camera, 2 screen; anyone may watch, peer to peer, no viewer limit. */
    fun setup(name: String, kind: Kind, bitrateKbps: Int, audio: Boolean, returnCode: String) = Commands.build(
        "setupstream",
        "name" to name.replace(Regex("[\r\n]+"), " ").trim().take(64).ifEmpty { "Screen" },
        "type" to kind.type,
        "bitrate" to bitrateKbps.coerceIn(500, 10000),
        "accessibility" to 1, "mode" to 1, "viewer_limit" to 0,
        "audio" to if (audio) 1 else 0,
        "return_code" to returnCode,
    )

    fun stop(streamId: String) = Commands.build("stopstream", "id" to streamId, "reason" to "")

    /** Letting a viewer in, with our offer for them. */
    fun accept(streamId: String, viewerId: Int, offer: String, returnCode: String) = Commands.build(
        "respondjoinstreamrequest", "id" to streamId, "clid" to viewerId, "msg" to "", "offer" to offer, "decision" to 1, "return_code" to returnCode,
    )

    /** A signalling message to the other end: {"cmd":…, "args":{…}} under `json`. */
    fun signal(streamId: String, otherId: Int, cmd: String, args: Map<String, Any?>, returnCode: String) = Commands.build(
        "streamsignaling", "id" to streamId, "clid" to otherId, "json" to Json.write(mapOf("cmd" to cmd, "args" to args)), "return_code" to returnCode,
    )

    fun answer(streamId: String, streamerId: Int, sdp: String, rc: String) = signal(streamId, streamerId, "answer", mapOf("answer" to sdp), rc)

    /** A connection address as the TeamSpeak app sends it: the candidate line under sdp, mid a string, mLine a number. */
    fun candidate(streamId: String, otherId: Int, candidate: String, mid: String?, mLine: Int?, rc: String) =
        signal(streamId, otherId, "iceCandidate", mapOf("sdp" to lowerProtocol(candidate), "mid" to (mid ?: "0"), "mLine" to (mLine ?: 0)), rc)

    fun reconnectOffer(streamId: String, viewerId: Int, sdp: String, rc: String) = signal(streamId, viewerId, "reconnectOffer", mapOf("offer" to sdp), rc)

    /** A new offer on a running stream, as the TeamSpeak app sends one to pause (all `inactive`) or go on (`sendonly`). */
    fun renegotiateOffer(streamId: String, viewerId: Int, sdp: String, rc: String) = signal(streamId, viewerId, "offer", mapOf("offer" to sdp), rc)

    /**
     * Whether an offer pauses the stream, as the TeamSpeak app's pause does: every media line `inactive` (nothing sent).
     * A line without a direction is `sendrecv`, so not paused.
     */
    fun isPausedOffer(sdp: String): Boolean {
        val sections = sdp.split(Regex("""\r?\n(?=m=)""")).drop(1)
        if (sections.isEmpty()) return false
        return sections.all { section -> section.lines().any { it.trim() == "a=inactive" } }
    }

    /** A signalling message received: its command and arguments. */
    data class Signal(val streamId: String, val from: Int, val cmd: String, val args: Map<String, Any?>) {
        /** An offer or answer it carries. */
        val sdp: String? get() = (args["offer"] ?: args["answer"] ?: args["sdp"]) as? String
        val candidate: String? get() = (args["sdp"] ?: args["candidate"]) as? String
        val mid: String? get() = (args["mid"] ?: args["sdp_mid"])?.toString()
        val mLine: Int? get() = ((args["mLine"] ?: args["sdp_mline_index"]) as? Number)?.toInt()
    }

    @Suppress("UNCHECKED_CAST")
    fun readSignal(cmd: Command): Signal? {
        val g = cmd.params
        val text = g["json"] ?: g["data"] ?: return null
        val payload = try { Json.parse(text) as? Map<String, Any?> } catch (_: Exception) { null } ?: return null
        val args = when (val a = payload["args"]) {
            is Map<*, *> -> a as Map<String, Any?>
            is String -> mapOf("sdp" to a)
            else -> emptyMap()
        }
        return Signal(g["id"] ?: g["stream_id"].orEmpty(), g["clid"]?.toIntOrNull() ?: 0, payload["cmd"]?.toString().orEmpty(), args)
    }

    // ====== Session descriptions for the TeamSpeak app ======

    /** Small letters for the protocol in a candidate line ("UDP" → "udp"), as the TeamSpeak app sees from Chrome. */
    fun lowerProtocol(candidate: String): String =
        Regex("""^(candidate:\S+ \d+ )(UDP|TCP)( )""").replace(candidate) { it.groupValues[1] + it.groupValues[2].lowercase() + it.groupValues[3] }

    private val VERSION = Regex("""^o=\S+ \S+ (\d+) """, RegexOption.MULTILINE)

    /**
     * The answer's session version set to the offer's: the TeamSpeak app applies an answer only when that number
     * equals its own offer's, and silently drops it otherwise.
     */
    fun withOfferSessionVersion(answer: String, offer: String): String {
        val v = VERSION.find(offer)?.groupValues?.get(1) ?: return answer
        return Regex("""^(o=\S+ \S+ )\d+ """, RegexOption.MULTILINE).replaceFirst(answer, "$1$v ")
    }

    /** The offer relabelled for us, and per payload type TeamSpeak's original H.264 value. */
    data class Relabel(val sdp: String, val original: Map<String, String>)

    /**
     * TeamSpeak sends H.264 as Constrained High (profile-level-id 640cXX), which decoders don't always list. When the
     * offer has no other video format we play ([otherPlayable]), it's shown to us as [profile] (e.g. 42e0) and the
     * original goes back into the answer.
     */
    fun relabelOfferH264(sdp: String, profile: String?, otherPlayable: Boolean): Relabel {
        if (profile == null || otherPlayable) return Relabel(sdp, emptyMap())
        val original = LinkedHashMap<String, String>()
        val out = Regex("""^(a=fmtp:(\d+) .*profile-level-id=)640c([0-9a-f]{2})""", setOf(RegexOption.MULTILINE, RegexOption.IGNORE_CASE))
            .replace(sdp) { m ->
                original[m.groupValues[2]] = "640c${m.groupValues[3]}"
                m.groupValues[1] + profile + m.groupValues[3]
            }
        return Relabel(out, original)
    }

    /** An SDP with the H.264 values put back per payload type. */
    fun restoreH264(sdp: String, original: Map<String, String>): String =
        Regex("""^(a=fmtp:(\d+) .*profile-level-id=)([0-9a-f]{6})""", setOf(RegexOption.MULTILINE, RegexOption.IGNORE_CASE))
            .replace(sdp) { m -> original[m.groupValues[2]]?.let { m.groupValues[1] + it } ?: m.value }

    /** Our share's offer as TeamSpeak expects it: every H.264 format as Constrained High (640c + the same level). */
    fun relabelShareOfferH264(sdp: String): Relabel {
        val h264 = Regex("""^a=rtpmap:(\d+) H264/""", setOf(RegexOption.MULTILINE, RegexOption.IGNORE_CASE)).findAll(sdp).map { it.groupValues[1] }.toSet()
        val original = LinkedHashMap<String, String>()
        val out = Regex("""^(a=fmtp:(\d+) .*profile-level-id=)([0-9a-f]{4})([0-9a-f]{2})""", setOf(RegexOption.MULTILINE, RegexOption.IGNORE_CASE))
            .replace(sdp) { m ->
                val pt = m.groupValues[2]
                if (pt !in h264 || m.groupValues[3].lowercase() == "640c") m.value
                else {
                    original[pt] = m.groupValues[3] + m.groupValues[4]
                    m.groupValues[1] + "640c" + m.groupValues[4]
                }
            }
        return Relabel(out, original)
    }

    /**
     * The TeamSpeak app answers H.264 at level 3.1 (at most 720p); we're told level 5.2 with level-asymmetry-allowed,
     * so what we send isn't held to 720p. Only our side sees this.
     */
    fun withShareSendLevel(sdp: String): String {
        val h264 = Regex("""^a=rtpmap:(\d+) H264/""", setOf(RegexOption.MULTILINE, RegexOption.IGNORE_CASE)).findAll(sdp).map { it.groupValues[1] }.toSet()
        return Regex("""^(a=fmtp:(\d+) )(.*)$""", setOf(RegexOption.MULTILINE, RegexOption.IGNORE_CASE)).replace(sdp) { m ->
            if (m.groupValues[2] !in h264) return@replace m.value
            val rest = m.groupValues[3].trimEnd('\r').split(';').map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("level-asymmetry-allowed=", ignoreCase = true) }
                .map { p ->
                    Regex("""^(profile-level-id=[0-9a-f]{4})([0-9a-f]{2})$""", RegexOption.IGNORE_CASE).replace(p) { pm ->
                        if (pm.groupValues[2].toInt(16) < 0x34) pm.groupValues[1] + "34" else pm.value
                    }
                }
            m.groupValues[1] + (listOf("level-asymmetry-allowed=1") + rest).joinToString(";") + if (m.groupValues[3].endsWith("\r")) "\r" else ""
        }
    }

    /** Lines about formats a media section doesn't list taken out (the TeamSpeak app refuses an offer with them). */
    fun withoutStrayPayloadLines(sdp: String): String {
        val eol = if (sdp.contains("\r\n")) "\r\n" else "\n"
        var listed: Set<String>? = null
        return sdp.split(Regex("\r?\n")).filter { line ->
            Regex("""^m=\S+ \d+ \S+ (.*)$""").find(line)?.let { listed = it.groupValues[1].trim().split(Regex("\\s+")).toSet(); return@filter true }
            val pt = Regex("""^a=(?:rtcp-fb|fmtp|rtpmap):(\d+) """).find(line)?.groupValues?.get(1)
            !(listed != null && pt != null && pt !in listed!!)
        }.joinToString(eol)
    }

    /**
     * Without the header extension that says how a picture is turned (urn:3gpp:video-orientation): the TeamSpeak app
     * accepts it but doesn't turn the picture, so a phone camera showed on its side. Without it the sender turns the
     * picture itself before it's sent.
     */
    fun withoutVideoOrientation(sdp: String): String {
        val eol = if (sdp.contains("\r\n")) "\r\n" else "\n"
        return sdp.split(Regex("\r?\n")).filterNot { it.startsWith("a=extmap:") && it.contains("urn:3gpp:video-orientation") }.joinToString(eol)
    }

    /** Does the offer have a video format other than H.264 among [playable] (e.g. "VP8", "VP9", "AV1")? */
    fun offerHasOther(sdp: String, playable: Collection<String>): Boolean =
        listOf("AV1", "VP9", "VP8").any { name ->
            playable.any { it.equals(name, true) } && Regex("""^a=rtpmap:\d+ $name/""", setOf(RegexOption.MULTILINE, RegexOption.IGNORE_CASE)).containsMatchIn(sdp)
        }
}
