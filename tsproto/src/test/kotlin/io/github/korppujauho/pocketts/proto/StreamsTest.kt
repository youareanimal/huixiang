package io.github.korppujauho.pocketts.proto

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The stream description changes must match WebTS's page script (run in tools/make-test-vectors.mts). */
@Suppress("UNCHECKED_CAST")
class StreamsTest {
    private val v = (MiniJson.parse(javaClass.getResource("/webts-vectors.json")!!.readText()) as Map<String, Any?>)["sdp"] as Map<String, Any?>
    private fun s(k: String) = v[k] as String

    @Test
    fun sameAsWebTs() {
        assertEquals(s("answerWithVersion"), Streams.withOfferSessionVersion(s("answer"), s("offer")))
        val share = Streams.relabelShareOfferH264(s("offer"))
        assertEquals(s("shareRelabelled"), share.sdp)
        assertEquals(v["shareOriginal"] as Map<String, Any?>, share.original)
        assertEquals(s("restored"), Streams.restoreH264(share.sdp, share.original))
        assertEquals(s("sendLevel"), Streams.withShareSendLevel(s("offer")))
        assertEquals(s("withoutStray"), Streams.withoutStrayPayloadLines(s("offer")))
        assertEquals(s("candidate"), Streams.lowerProtocol("candidate:1 1 UDP 2122260223 192.0.2.10 50000 typ host"))
    }

    @Test
    fun pausedOffers() {
        // As the TeamSpeak app sends them: pausing marks every line inactive, going on marks them sendonly again
        fun offer(video: String?, audio: String?) = listOfNotNull(
            "v=0", "o=- 1 3 IN IP4 127.0.0.1", "s=-", "t=0 0",
            "m=video 9 UDP/TLS/RTP/SAVPF 96", "a=mid:0", video, "a=rtpmap:96 H264/90000",
            "m=audio 9 UDP/TLS/RTP/SAVPF 111", "a=mid:1", audio, "a=rtpmap:111 opus/48000/2", "",
        ).joinToString("\r\n")
        assertTrue(Streams.isPausedOffer(offer("a=inactive", "a=inactive")))
        assertEquals(false, Streams.isPausedOffer(offer("a=sendonly", "a=sendonly")))
        assertEquals(false, Streams.isPausedOffer(offer("a=inactive", "a=sendonly")))
        assertEquals(false, Streams.isPausedOffer(offer(null, null))) // no direction: sendrecv
        assertEquals(false, Streams.isPausedOffer("v=0\r\ns=-\r\n"))
        // The cmd and the offer, as the TeamSpeak app sends a renegotiation
        val cmd = Commands.parse(Streams.renegotiateOffer("abc", 5, "v=0", "rc"))
        val sig = assertNotNull(Streams.readSignal(cmd))
        assertEquals("offer", sig.cmd)
        assertEquals("v=0", sig.sdp)
    }

    @Test
    fun noVideoOrientation() {
        val sdp = listOf(
            "v=0", "m=video 9 UDP/TLS/RTP/SAVPF 96", "a=extmap:3 urn:3gpp:video-orientation",
            "a=extmap:4 http://www.webrtc.org/experiments/rtp-hdrext/abs-send-time", "a=rtpmap:96 H264/90000", "",
        )
        assertEquals((sdp - sdp[2]).joinToString("\r\n"), Streams.withoutVideoOrientation(sdp.joinToString("\r\n")))
    }

    @Test
    fun viewerRelabel() {
        val offer = s("offer")
        // A VP8 we can play: nothing changes
        assertEquals(offer, Streams.relabelOfferH264(offer, "42e0", Streams.offerHasOther(offer, listOf("VP8"))).sdp)
        // H.264 only: Constrained High shown as our profile, and back in the answer
        val r = Streams.relabelOfferH264(offer, "42e0", otherPlayable = false)
        assertTrue(r.sdp.contains("a=fmtp:103 packetization-mode=1;profile-level-id=42e01f"))
        assertEquals(mapOf("103" to "640c1f"), r.original)
        assertEquals(offer, Streams.restoreH264(r.sdp, r.original))
    }

    @Test
    fun signalling() {
        val cmd = Commands.parse(
            Streams.signal("s1", 7, "iceCandidate", mapOf("sdp" to "candidate:1 1 UDP 1 192.0.2.1 5 typ host", "mid" to "0", "mLine" to 0), "rc-1")
                .replaceFirst("streamsignaling", "notifystreamsignaling"),
        )
        val sig = assertNotNull(Streams.readSignal(cmd))
        assertEquals("s1", sig.streamId)
        assertEquals(7, sig.from)
        assertEquals("iceCandidate", sig.cmd)
        assertEquals("0", sig.mid)
        assertEquals(0, sig.mLine)
        assertEquals("candidate:1 1 UDP 1 192.0.2.1 5 typ host", sig.candidate)

        val json = Json.write(mapOf("cmd" to "answer", "args" to mapOf("answer" to "v=0\r\na=x \"q\"\n")))
        assertEquals(mapOf("cmd" to "answer", "args" to mapOf("answer" to "v=0\r\na=x \"q\"\n")), Json.parse(json))
    }

    @Test
    fun streamsInTheModel() {
        val m = ServerModel()
        m.apply(Commands.parse("notifycliententerview ctid=1 clid=5 client_nickname=A client_is_streaming=1"))
        m.apply(Commands.parse("notifystreaminfo id=abc clid=5 type=2"))
        assertEquals("abc", m.streams[5])
        assertTrue(m.clients[5]!!.streaming)
        m.apply(Commands.parse("notifystreamstopped id=abc clid=5"))
        assertEquals(null, m.streams[5])
        // A group list as a plain answer
        m.apply(Commands.parse("sgid=6 name=Admin iconid=100 sortid=1|sgid=8 name=Guest iconid=0"))
        assertEquals("Admin", m.serverGroups[6]?.name)
        assertEquals("Guest", m.serverGroups[8]?.name)
    }
}
