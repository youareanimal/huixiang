package site.huixiang.web

import io.github.korppujauho.pocketts.proto.Commands
import io.github.korppujauho.pocketts.proto.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The bridge's two jobs: turn the tab's intents into TeamSpeak commands, and mend session descriptions so the
 * TeamSpeak app and a browser can understand each other. The SDP cases here are the ones that silently break
 * otherwise — the app applies an answer only when the session version matches its offer, and it decodes H.264
 * only in the profile it expects.
 */
class StreamBridgeTest {

    private val sent = mutableListOf<String>()
    private val pushed = mutableListOf<Map<String, Any?>>()
    private var me = 1

    private fun bridge() = StreamBridge(
        sendCommand = { sent += it },
        push = { pushed += params(it) },
        ownId = { me },
        log = {},
    )

    @Suppress("UNCHECKED_CAST")
    private fun params(json: String) = Json.parse(json) as Map<String, Any?>

    private fun lastCommand() = Commands.parse(sent.last())

    private fun event(name: String) = pushed.lastOrNull { it["event"] == name }

    // ====== 命令 ======

    @Test
    fun `sharing asks the server for a screen stream`() {
        val b = bridge()
        b.share(2, "我的屏幕", false)
        assertEquals(1, sent.size)
        val cmd = lastCommand()
        assertEquals("setupstream", cmd.name)
        assertEquals("2", cmd["type"])
        assertEquals("4000", cmd["bitrate"])
        assertEquals("0", cmd["audio"])
        assertEquals("我的屏幕", cmd["name"])
    }

    @Test
    fun `a camera share is cheaper than a screen`() {
        val b = bridge()
        b.share(1, "摄像头", true)
        val cmd = lastCommand()
        assertEquals("1", cmd["type"])
        assertEquals("2500", cmd["bitrate"])
        assertEquals("1", cmd["audio"])
    }

    @Test
    fun `watching asks for the stream id first`() {
        val b = bridge()
        b.watch(7)
        assertEquals("requeststreaminfo", lastCommand().name)
        assertEquals("7", lastCommand()["clid"])
        // 还没答应接，先别 join
        assertFalse(sent.any { it.startsWith("joinstreamrequest") })
    }

    @Test
    fun `the stream id leads to a join`() {
        val b = bridge()
        b.watch(7)
        b.onCommand(Commands.parse("notifystreaminfo id=abc clid=7"))
        assertTrue(sent.any { it.startsWith("joinstreamrequest") && it.contains("id=abc") && it.contains("clid=7") })
    }

    @Test
    fun `our own stream going live is announced, someone else's is not`() {
        val b = bridge()
        b.share(2, "屏幕", false)
        b.onCommand(Commands.parse("notifystreamstarted id=other clid=9"))
        assertNull(event("started"))

        b.onCommand(Commands.parse("notifystreamstarted id=abc clid=1"))
        assertEquals("abc", event("started")?.get("streamId"))
    }

    // ====== 共享：offer 出去要按 TeamSpeak 的样子改 ======

    private val browserOffer = listOf(
        "v=0",
        "o=- 4611731400430051336 2 IN IP4 127.0.0.1",
        "s=-",
        "t=0 0",
        "a=group:BUNDLE 0 1",
        "m=video 9 UDP/TLS/RTP/SAVPF 96 97",
        "c=IN IP4 0.0.0.0",
        "a=mid:0",
        "a=sendonly",
        "a=rtpmap:96 H264/90000",
        "a=fmtp:96 level-asymmetry-allowed=1;packetization-mode=1;profile-level-id=42e01f",
        "a=rtpmap:97 rtx/90000",
        "a=fmtp:97 apt=96",
        "a=extmap:3 urn:3gpp:video-orientation",
        "a=extmap:4 http://www.webrtc.org/experiments/rtp-hdrext/abs-send-time",
        "m=audio 9 UDP/TLS/RTP/SAVPF 111",
        "c=IN IP4 0.0.0.0",
        "a=mid:1",
        "a=sendonly",
        "a=rtpmap:111 opus/48000/2",
    ).joinToString("\r\n") + "\r\n"

    @Test
    fun `a viewer gets Constrained High and no turn extension`() {
        val b = bridge()
        b.share(2, "屏幕", false)
        b.onCommand(Commands.parse("notifystreamstarted id=abc clid=1"))

        b.onCommand(Commands.parse("notifyjoinstreamrequest id=abc clid=7 is_remove=0"))
        // Json 读回来的数字是 Long，比一下数值就好
        assertEquals(7, (event("viewer")?.get("viewer") as? Number)?.toInt())

        b.shareOffer(7, browserOffer)
        val cmd = lastCommand()
        assertEquals("respondjoinstreamrequest", cmd.name)
        assertEquals("7", cmd["clid"])
        assertEquals("1", cmd["decision"])

        val offer = cmd["offer"].orEmpty()
        assertTrue(offer.contains("profile-level-id=640c1f"), "H.264 应当被标成 Constrained High：$offer")
        assertFalse(offer.contains("urn:3gpp:video-orientation"), "视频方向扩展要去掉")
        assertTrue(offer.contains("m=audio"), "没有声音也要留一条音频 m-line")
    }

    @Test
    fun `their answer gets the level it can actually send`() {
        val b = bridge()
        b.share(2, "屏幕", false)
        b.onCommand(Commands.parse("notifystreamstarted id=abc clid=1"))
        b.onCommand(Commands.parse("notifyjoinstreamrequest id=abc clid=7 is_remove=0"))
        b.shareOffer(7, browserOffer)

        val answer = listOf(
            "v=0",
            "o=- 1 2 IN IP4 127.0.0.1",
            "s=-",
            "t=0 0",
            "m=video 9 UDP/TLS/RTP/SAVPF 96",
            "c=IN IP4 0.0.0.0",
            "a=mid:0",
            "a=recvonly",
            "a=rtpmap:96 H264/90000",
            "a=fmtp:96 profile-level-id=640c1f;packetization-mode=1",
        ).joinToString("\r\n") + "\r\n"

        b.onCommand(
            Commands.parse(
                Commands.build(
                    "notifystreamsignaling", "id" to "abc", "clid" to 7,
                    "json" to Json.write(mapOf("cmd" to "answer", "args" to mapOf("answer" to answer))),
                )
            )
        )
        val got = event("answer")?.get("sdp") as? String ?: error("没有把 answer 转给浏览器")
        // 观众看到的是 640c，回来要还原成我们真正发的 42e0；level 再抬到 5.2（0x34），
        // 否则 TeamSpeak 的客户端按 3.1 回，把我们卡在 720p
        assertTrue(got.contains("profile-level-id=42e034"), got)
        assertTrue(got.contains("level-asymmetry-allowed=1"), got)
    }

    // ====== 观看：对方的 offer 要改成浏览器能解的 ======

    private val teamSpeakOffer = listOf(
        "v=0",
        "o=- 123456 7 IN IP4 10.0.0.2",
        "s=-",
        "t=0 0",
        "m=video 9 UDP/TLS/RTP/SAVPF 96",
        "c=IN IP4 0.0.0.0",
        "a=mid:0",
        "a=sendonly",
        "a=rtpmap:96 H264/90000",
        "a=fmtp:96 packetization-mode=1;profile-level-id=640c1f",
    ).joinToString("\r\n") + "\r\n"

    @Test
    fun `TeamSpeak's Constrained High is shown to the browser as baseline`() {
        val b = bridge()
        b.watch(7)
        b.onCommand(Commands.parse("notifystreaminfo id=abc clid=7"))
        b.onCommand(
            Commands.parse(
                Commands.build(
                    "notifyrespondjoinstreamrequest", "id" to "abc", "clid" to 7, "decision" to "1",
                    "offer" to teamSpeakOffer,
                )
            )
        )
        val offer = event("offer")?.get("sdp") as? String ?: error("没有把 offer 转给浏览器")
        assertTrue(offer.contains("profile-level-id=42e0"), offer)
        assertFalse(offer.contains("640c"), "640c 不该出现在给浏览器的 SDP 里：$offer")
    }

    @Test
    fun `the browser's answer carries the original profile and the offer's session version`() {
        val b = bridge()
        b.watch(7)
        b.onCommand(Commands.parse("notifystreaminfo id=abc clid=7"))
        b.onCommand(
            Commands.parse(
                Commands.build(
                    "notifyrespondjoinstreamrequest", "id" to "abc", "clid" to 7, "decision" to "1",
                    "offer" to teamSpeakOffer,
                )
            )
        )

        // 浏览器把收到的（已改成 42e0 的）SDP 答回来，version 是它自己的一串数字
        val answer = listOf(
            "v=0",
            "o=- 999 1 IN IP4 127.0.0.1",
            "s=-",
            "t=0 0",
            "m=video 9 UDP/TLS/RTP/SAVPF 96",
            "c=IN IP4 0.0.0.0",
            "a=mid:0",
            "a=recvonly",
            "a=rtpmap:96 H264/90000",
            "a=fmtp:96 profile-level-id=42e01f",
        ).joinToString("\r\n") + "\r\n"

        b.answer(7, answer)
        val cmd = lastCommand()
        assertEquals("streamsignaling", cmd.name)
        val json = cmd["json"].orEmpty()
        assertTrue(json.contains("640c1f"), "answer 里要还原成 640c：$json")
        assertFalse(json.contains("42e01f"), "42e0 只是给我们看的：$json")
        assertTrue(json.contains("o=- 999 7 "), "session version 要和对方的 offer 一致：$json")
    }

    // ====== 收尾 ======

    @Test
    fun `a command's own ok is not a failure`() {
        // 每条命令的应答都是 error id=0 msg=ok：认成拒绝的话，join 一成功就被自己撤掉，
        // 再点同一个人就成了 already joined
        val b = bridge()
        b.share(2, "屏幕", false)
        b.onCommand(Commands.parse("error id=0 msg=ok return_code=stream-setup-1"))
        assertTrue(b.isSharing())
        assertNull(event("failed"))
    }

    @Test
    fun `a refused setup stops the share and tells the tab why`() {
        val b = bridge()
        b.share(2, "屏幕", false)
        b.onCommand(Commands.parse("error id=2568 msg=insufficient\\spermissions return_code=stream-setup-1"))
        val failed = event("failed")
        assertEquals("share", failed?.get("what"))
        assertFalse(b.isSharing())
    }

    @Test
    fun `stopping the stream tells the server`() {
        val b = bridge()
        b.share(2, "屏幕", false)
        b.onCommand(Commands.parse("notifystreamstarted id=abc clid=1"))
        b.stopShare()
        assertTrue(sent.any { it.startsWith("stopstream") && it.contains("id=abc") })
        assertEquals("stopped", pushed.last()["event"])
    }
}
