package io.github.korppujauho.pocketts.proto

import java.util.Base64
import kotlin.test.*

class MyTsVoiceMaterialTest {
    private val idData = CloudProto.bytes(1, "synthetic-public-key".toByteArray()) +
        CloudProto.bytes(2, CloudProto.bytes(1, "synthetic-encrypted-key".toByteArray())) +
        CloudProto.integer(3, 0) + CloudProto.bytes(4, "synthetic-id".toByteArray()) +
        CloudProto.bytes(5, "synthetic-public-signature".toByteArray()) + CloudProto.text(99, "future-field")
    private val cert = CloudProto.bytes(1, "synthetic-certificate".toByteArray())
    private val avatar = CloudProto.bytes(1, CloudProto.bytes(1, CloudProto.integer(1, 2) + CloudProto.text(2, "object"))) +
        CloudProto.integer(2, 0) + CloudProto.bytes(3, "synthetic-signature".toByteArray())
    private val material = CloudProto.bytes(9, idData) + CloudProto.bytes(10, cert) + CloudProto.bytes(11, avatar)
    private fun api(extra: ByteArray) = MyTsAccount(CloudTransport { _, _, _, _ -> CloudReply(200,
        CloudProto.integer(5, 200) + CloudProto.text(2, "fixture-session") + CloudProto.text(8, "Fixture") + extra) })
    private fun check(session: MyTsAccount.Session) {
        val parsed = assertNotNull(session.voiceMaterial)
        assertContentEquals(idData, parsed.idData)
        assertContentEquals(cert, parsed.userCertificate)
        assertContentEquals(avatar, parsed.signedAvatar)
        assertNull(session.voiceMaterialError)
        assertEquals("MyTsVoiceMaterial(redacted)", parsed.toString())
        assertFalse(session.toString().contains("fixture-session"))
        parsed.idData!!.fill(0)
        assertContentEquals(idData, parsed.idData)
        assertEquals(parsed.encodeForStorage(), MyTsVoiceMaterial.decodeFromStorage(parsed.encodeForStorage()).encodeForStorage())
    }

    @Test fun loginPreservesOriginalMessagesIncludingUnknownFieldsAndReturnsDefensiveCopies() {
        check(api(material).loginDigest("fixture@example.com", "fixture-digest"))
    }

    @Test fun bookmarkKeySuccessMissingAndFailureCannotDiscardVoiceMaterial() {
        val wrapped = Base64.getDecoder().decode("AfA6UXyn5XKclrMfEY3u+5cqKioqKioqKioqKipHMvBKU+EvyD7OwQqRWQW7DpK223gNksrrgVAmQnGS5g==")
        val success = api(material + CloudProto.bytes(1, wrapped)).login("Alice@Example.com", "päss密碼")
        assertNotNull(success.syncKey); check(success)
        val missing = api(material).login("Alice@Example.com", "päss密碼")
        assertEquals("BOOKMARK_KEY_MISSING", missing.bookmarkKeyError); check(missing)
        val failed = api(material + CloudProto.bytes(1, wrapped)).login("Alice@Example.com", "wrong-password")
        assertEquals("BOOKMARK_KEY_AUTH", failed.bookmarkKeyError); check(failed)
    }

    @Test fun absentPartialAndMalformedOptionalMaterialDoNotBreakAccountLogin() {
        assertNull(api(byteArrayOf()).loginDigest("e", "d").voiceMaterial)
        val partial = api(CloudProto.bytes(11, avatar)).loginDigest("e", "d")
        assertNull(partial.voiceMaterial!!.idData)
        assertContentEquals(avatar, partial.voiceMaterial!!.signedAvatar)
        for (bad in listOf(CloudProto.integer(9, 1), CloudProto.bytes(9, byteArrayOf(10, 5)),
            material + CloudProto.bytes(9, idData), CloudProto.bytes(9, ByteArray(65537)))) {
            val session = api(bad).loginDigest("e", "d")
            assertEquals("fixture-session", session.token)
            assertNull(session.voiceMaterial)
            assertEquals("MYTS_VOICE_MATERIAL_INVALID", session.voiceMaterialError)
        }
    }

    @Test fun storageRejectsInvalidBase64OversizedAndEmptyEnvelopes() {
        for (bad in listOf("!", "A".repeat(270001), "", Base64.getEncoder().encodeToString(byteArrayOf(10, 5)))) {
            assertFails { MyTsVoiceMaterial.decodeFromStorage(bad) }
        }
    }
}
