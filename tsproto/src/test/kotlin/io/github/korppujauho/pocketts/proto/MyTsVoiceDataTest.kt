package io.github.korppujauho.pocketts.proto

import java.util.Base64
import kotlin.test.*

class MyTsVoiceDataTest {
    private val certificate = "synthetic-public-certificate".toByteArray()
    private val avatar = CloudProto.bytes(1, CloudProto.bytes(1, CloudProto.integer(1, 2) + CloudProto.text(2, "fixture"))) +
        CloudProto.integer(2, 1_700_000_000L) + CloudProto.bytes(3, ByteArray(64) { 2 })
    private val badge = CloudProto.bytes(1, CloudProto.text(1, "fixture-uuid")) + CloudProto.bytes(2, ByteArray(64) { 3 }) +
        CloudProto.integer(3, 1_700_000_000L)
    private val badgeList = CloudProto.bytes(1, badge)
    private fun session(avatarBytes: ByteArray = avatar) = MyTsAccount.Session("synthetic-account-token", "Fixture",
        voiceMaterial = MyTsVoiceMaterial.fromLogin(CloudProto.read(
            CloudProto.bytes(10, CloudProto.bytes(1, certificate)) + CloudProto.bytes(11, avatarBytes))))

    @Test fun encodesOriginalPublicMetadataAndNeverSendsSessionTokenOrPrivateIdentityEnvelope() {
        val data = assertNotNull(MyTsVoiceData.fromSession(session(), badgeList))
        val command = Commands.parse(data.command())
        val decode = Base64.getDecoder()
        assertEquals("updatemytsdata", command.name)
        assertContentEquals(certificate, decode.decode(command["myts_certificate"]))
        assertContentEquals(avatar, decode.decode(command["myts_avatar"]))
        assertContentEquals(badgeList, decode.decode(command["myts_signed_badge"]))
        assertFalse(data.command().contains("synthetic-account-token"))
        assertEquals("MyTsVoiceData(redacted)", data.toString())
    }

    @Test fun usesSignedBadgeRpcWithSessionAndKeepsAvatarWhenBadgeServiceFails() {
        val account = MyTsAccount(CloudTransport { method, url, _, body ->
            assertEquals("POST", method)
            assertEquals("https://clientapi.myteamspeak.com/user", url)
            val payload = assertNotNull(body)
            assertEquals("getSignedBadges", payload.copyOfRange(1, 16).toString(Charsets.US_ASCII))
            assertEquals("synthetic-account-token", CloudProto.read(payload.copyOfRange(16, payload.size)).first().bytes!!.toString(Charsets.UTF_8))
            CloudReply(200, CloudProto.bytes(1, badgeList))
        })
        assertContentEquals(badgeList, Base64.getDecoder().decode(Commands.parse(account.voiceData(session())!!.command())["myts_signed_badge"]))
        for (status in listOf(503, 401)) {
            val failed = MyTsAccount(CloudTransport { _, _, _, _ -> CloudReply(status, byteArrayOf()) })
            val command = Commands.parse(assertNotNull(failed.voiceData(session())).command())
            assertNotNull(command["myts_avatar"])
            assertNull(command["myts_signed_badge"])
        }
    }

    @Test fun invalidOptionalSignaturesAreOmittedAndLegacySessionsHaveNoMetadata() {
        val command = Commands.parse(assertNotNull(MyTsVoiceData.fromSession(session(CloudProto.bytes(3, byteArrayOf(1))), CloudProto.bytes(1, byteArrayOf()))).command())
        assertNull(command["myts_avatar"])
        assertNull(command["myts_signed_badge"])
        assertNull(MyTsVoiceData.fromSession(MyTsAccount.Session("legacy", "Fixture")))
    }
}
