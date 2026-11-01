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
    private fun session(avatarBytes: ByteArray = avatar, certificateBytes: ByteArray = certificate) = MyTsAccount.Session("synthetic-account-token", "Fixture",
        voiceMaterial = MyTsVoiceMaterial.fromLogin(CloudProto.read(
            CloudProto.bytes(10, CloudProto.bytes(1, certificateBytes)) + CloudProto.bytes(11, avatarBytes))))

    private fun parse(data: MyTsVoiceData) = Commands.parse(data.commandBytes().toString(Charsets.ISO_8859_1))
    private fun bytes(command: Command, key: String) = command[key]?.toByteArray(Charsets.ISO_8859_1)

    @Test fun encodesOriginalPublicMetadataAndNeverSendsSessionTokenOrPrivateIdentityEnvelope() {
        val data = assertNotNull(MyTsVoiceData.fromSession(session(), badgeList))
        val command = parse(data)
        assertEquals("updatemytsdata", command.name)
        assertContentEquals(certificate, bytes(command, "myts_certificate"))
        assertContentEquals(avatar, bytes(command, "myts_avatar"))
        assertContentEquals(badgeList, bytes(command, "myts_signed_badge"))
        assertFalse(data.commandBytes().toString(Charsets.ISO_8859_1).contains("synthetic-account-token"))
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
        assertContentEquals(badgeList, bytes(parse(account.voiceData(session())!!), "myts_signed_badge"))
        for (status in listOf(503, 401)) {
            val failed = MyTsAccount(CloudTransport { _, _, _, _ -> CloudReply(status, byteArrayOf()) })
            val command = parse(assertNotNull(failed.voiceData(session())))
            assertNotNull(command["myts_avatar"])
            assertNull(command["myts_signed_badge"])
        }
    }

    @Test fun reportsNoBadgesSeparatelyFromFetchFailureAndInvalidPayload() {
        val noBadges = MyTsAccount(CloudTransport { _, _, _, _ -> CloudReply(200, byteArrayOf()) })
            .voiceDataResult(session())
        assertEquals(MyTsBadgeStatus.NO_BADGES, noBadges.badgeStatus)
        assertEquals(0, noBadges.badgeCount)
        assertNotNull(noBadges.data)

        val available = MyTsAccount(CloudTransport { _, _, _, _ -> CloudReply(200, CloudProto.bytes(1, badgeList)) })
            .voiceDataResult(session())
        assertEquals(MyTsBadgeStatus.AVAILABLE, available.badgeStatus)
        assertEquals(1, available.badgeCount)
        assertNotNull(available.data)
        assertEquals(1, available.data.badgeCount)

        val failed = MyTsAccount(CloudTransport { _, _, _, _ -> CloudReply(503, byteArrayOf()) })
            .voiceDataResult(session())
        assertEquals(MyTsBadgeStatus.FETCH_FAILED, failed.badgeStatus)
        assertNotNull(failed.data) // Badge lookup is optional; certificate and avatar remain available.

        val invalid = MyTsAccount(CloudTransport { _, _, _, _ -> CloudReply(200, CloudProto.bytes(1, byteArrayOf(0x80.toByte()))) })
            .voiceDataResult(session())
        assertEquals(MyTsBadgeStatus.INVALID, invalid.badgeStatus)
        assertNotNull(invalid.data)
        assertEquals(0, invalid.data.badgeCount)
    }

    @Test fun invalidOptionalSignaturesAreOmittedAndLegacySessionsHaveNoMetadata() {
        val command = parse(assertNotNull(MyTsVoiceData.fromSession(session(CloudProto.bytes(3, byteArrayOf(1))), CloudProto.bytes(1, byteArrayOf()))))
        assertNull(command["myts_avatar"])
        assertNull(command["myts_signed_badge"])
        val emptyBadges = assertNotNull(MyTsVoiceData.fromSession(session(), byteArrayOf()))
        assertNull(parse(emptyBadges)["myts_signed_badge"])
        assertContentEquals(assertNotNull(MyTsVoiceData.fromSession(session())).commandBytes(), emptyBadges.commandBytes())
        assertNull(MyTsVoiceData.fromSession(MyTsAccount.Session("legacy", "Fixture")))
    }

    @Test fun binaryCommandMatchesCapturedOfficialNativeBuilderForEveryByte() {
        val resource = assertNotNull(javaClass.getResourceAsStream("/myts-native-command-vectors.json"))
        val root = resource.use { Json.parse(it.readBytes().toString(Charsets.UTF_8)) } as Map<*, *>
        assertEquals(true, root["synthetic"])
        assertEquals("1189672c2c290997a419100b0f29f4da95d0df9d1e744b07a764eba1e8a0944f", root["nativeBinarySHA256"])
        val vectors = root["commandVectors"] as List<*>
        assertEquals(3, vectors.size)
        for (item in vectors) {
            val vector = item as Map<*, *>
            val input = Base64.getDecoder().decode(vector["inputBase64"] as String)
            val expected = Base64.getDecoder().decode(vector["wireBase64"] as String)
            assertContentEquals(expected, Commands.buildBytes("updatemytsdata", "myts_certificate" to input))
            val metadata = assertNotNull(MyTsVoiceData.fromSession(session(certificateBytes = input)))
            assertContentEquals(input, bytes(parse(metadata), "myts_certificate"))
        }
        val nickname = "fixture-昵称🙂"
        assertContentEquals(Commands.build("clientupdate", "client_nickname" to nickname).toByteArray(Charsets.UTF_8),
            Commands.buildBytes("clientupdate", "client_nickname" to nickname))
    }

    @Test fun binaryMetadataSurvivesFloodRetryWithoutChangingOrExposingPayload() {
        val data = assertNotNull(MyTsVoiceData.fromSession(session(certificateBytes = ByteArray(256) { it.toByte() }), badgeList))
        val original = data.commandBytes()
        val callerBytes = original.copyOf()
        val pacer = CommandPacer()
        pacer.add(callerBytes)
        callerBytes.fill(0)
        val first = pacer.takePackets(0).single()
        assertContentEquals(original, first.bytes)
        assertEquals("PacedCommand(redacted)", first.toString())
        assertTrue(pacer.onError(Commands.parse("error id=524 return_code=quiet-myts-data"), 1))
        assertTrue(pacer.takePackets(1000).isEmpty())
        val retried = pacer.takePackets(5301).single()
        assertContentEquals(original, retried.bytes)
        assertFalse(pacer.onError(Commands.parse("error id=0 return_code=quiet-myts-data"), 5302))
    }

    @Test fun binaryMetadataSurvivesTheActualEncryptedFragmentedSendPath() {
        val client = TsClient(object : TsClient.Listener {})
        fun field(name: String, value: Any) {
            TsClient::class.java.getDeclaredField(name).apply { isAccessible = true }.set(client, value)
        }
        try {
            val iv = ByteArray(64) { (it + 1).toByte() }
            field("opts", TsClient.Options("unused.invalid", identity = Identity.generate(0), nickname = "Fixture"))
            field("state", TsClient.State.CONNECTED)
            field("clientId", 7)
            field("ivStruct", iv)
            field("cryptoReady", true)
            field("myTsAuthenticated", true)
            val data = assertNotNull(MyTsVoiceData.fromSession(session(certificateBytes = ByteArray(1024) { it.toByte() }), badgeList))
            client.sendMyTsData(data)
            val executor = TsClient::class.java.getDeclaredField("exec").apply { isAccessible = true }.get(client) as java.util.concurrent.ScheduledExecutorService
            executor.submit {}.get(5, java.util.concurrent.TimeUnit.SECONDS)
            val pending = TsClient::class.java.getDeclaredField("pending").apply { isAccessible = true }.get(client) as Map<*, *>
            assertTrue(pending.size > 1)
            val parts = pending.values.map { packet ->
                val raw = packet!!.javaClass.getDeclaredField("raw").apply { isAccessible = true }.get(packet) as ByteArray
                val packetId = ((raw[8].toInt() and 255) shl 8) or (raw[9].toInt() and 255)
                val kn = Crypto.deriveKeyNonce(false, packetId, 0, 2, iv)
                packetId to assertNotNull(Crypto.eaxDecrypt(kn.key, kn.nonce, raw.copyOfRange(8, 13), raw.copyOfRange(13, raw.size), raw.copyOfRange(0, 8)))
            }.sortedBy { it.first }
            assertContentEquals(data.commandBytes(), parts.fold(ByteArray(0)) { result, part -> result + part.second })
        } finally { client.close() }
    }

}
