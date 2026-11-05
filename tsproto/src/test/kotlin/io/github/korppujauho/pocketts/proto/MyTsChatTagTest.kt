package io.github.korppujauho.pocketts.proto

import io.github.korppujauho.pocketts.proto.CloudProto.number
import io.github.korppujauho.pocketts.proto.CloudProto.string
import java.util.Base64
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class MyTsChatTagTest {
    private val account = MyTsAccount.Session("private-session", "ignored")
    private val matrixId = "@fixture:chat-2.tmspk.net"
    private val primaryTag = "fixture@myteamspeak.com"
    private val secondaryTag = "secondary@myteamspeak.com"

    private fun entry(tag: String, matrix: String = matrixId, primary: Boolean = true) =
        CloudProto.bytes(1, CloudProto.text(1, tag) + CloudProto.text(2, matrix) +
            CloudProto.integer(3, if (primary) 1 else 0))

    private val ok = CloudProto.bytes(2, CloudProto.integer(1, 1))

    private fun activeIdentifiers() = entry(secondaryTag, primary = false) + entry(primaryTag) + ok

    private fun signedToken(
        tags: List<String> = listOf(secondaryTag, primaryTag),
        signatureSize: Int = 64,
        certificateSize: Int = 112,
        timestamp: Long = 1_793_880_774L,
    ): ByteArray {
        val tagList = tags.fold(byteArrayOf()) { bytes, tag -> bytes + CloudProto.text(1, tag) }
        return CloudProto.bytes(1, ByteArray(signatureSize) { 1 }) +
            CloudProto.bytes(2, ByteArray(certificateSize) { 2 }) +
            CloudProto.integer(3, timestamp) +
            CloudProto.bytes(4, tagList)
    }

    private fun signedResponse(token: ByteArray) =
        CloudProto.bytes(1, token) + CloudProto.bytes(2, CloudProto.integer(1, 1))

    private fun api(
        token: ByteArray = signedToken(),
        onMethod: ((String, List<CloudProto.Field>) -> CloudReply)? = null,
        seen: MutableList<String> = mutableListOf(),
    ) = MyTsChatTag(CloudTransport { method, url, headers, body ->
        assertEquals("POST", method)
        assertEquals("https://clientapi.myteamspeak.com/tschat", url)
        assertEquals("application/ts3cloud", headers["Content-Type"])
        val bytes = body!!
        val length = bytes[0].toInt() and 0xff
        val methodName = bytes.copyOfRange(1, length + 1).toString(Charsets.US_ASCII)
        val fields = CloudProto.read(bytes.copyOfRange(length + 1, bytes.size))
        assertEquals(account.token, fields.string(1))
        assertEquals(matrixId, fields.string(2))
        seen += methodName
        onMethod?.invoke(methodName, fields) ?: when (methodName) {
            "getActiveIdentifierList" -> CloudReply(200, activeIdentifiers())
            "requestSignedAllowedIdentifierList" -> CloudReply(200, signedResponse(token))
            else -> fail("Unexpected RPC method: " + methodName)
        }
    })

    @Test
    fun fetchesOnlyPrimaryIdentifierForTheExpectedMatrixAccount() {
        val api = api()
        assertEquals(primaryTag, api.read(account, matrixId))
    }

    @Test
    fun readsSignedTokenFromOfficialRpcAndKeepsItsRawBytes() {
        val token = signedToken()
        val seen = mutableListOf<String>()
        val payload = api(token, seen = seen).readPayload(account, matrixId, 1_793_880_777_317L)!!

        assertEquals(listOf("getActiveIdentifierList", "requestSignedAllowedIdentifierList"), seen)
        assertEquals(primaryTag, payload.tag)
        assertEquals(Base64.getEncoder().encodeToString(token), payload.myTsToken)
        assertContentEquals(token, Base64.getDecoder().decode(payload.myTsToken))
        assertEquals(1_793_880_777_317L, payload.updated)
        assertFalse(payload.toString().contains(payload.myTsToken))
    }

    @Test
    fun sendsCompleteOfficialUserTagOnlyInClientUserTag() {
        val payload = api().readPayload(account, matrixId, 1_793_880_777_317L)!!
        val command = Commands.parse(MyTsChatTag.updateCommand(payload))

        assertEquals("clientupdate", command.name)
        val expected = linkedMapOf(
            "myts_token" to payload.myTsToken,
            "tag" to primaryTag,
            "updated" to 1_793_880_777_317L,
        )
        assertEquals(expected, Json.parse(command["client_user_tag"]!!))
        assertNull(command["client_meta_data"])
        assertEquals("quiet-myts-tag", command["return_code"])
        assertTrue(command["client_user_tag"]!!.length > 100)
    }

    @Test
    fun acceptsPrimaryTagWhenItIsNotTheFirstSignedAllowedTag() {
        val payload = api(signedToken(tags = listOf(secondaryTag, primaryTag)))
            .readPayload(account, matrixId, 1_793_880_777_317L)
        assertEquals(primaryTag, payload?.tag)
    }

    @Test
    fun refusesTokenThatDoesNotContainThePrimaryIdentifier() {
        val error = assertThrows(CloudFailure::class.java) {
            api(signedToken(tags = listOf(secondaryTag))).readPayload(account, matrixId)
        }
        assertEquals("MYTS_SIGNED_IDENTIFIER_TAG_MISMATCH", error.code)
    }

    @Test
    fun refusesMalformedSignedTokenEnvelope() {
        val malformed = signedToken(signatureSize = 63)
        val error = assertThrows(CloudFailure::class.java) {
            api(malformed).readPayload(account, matrixId)
        }
        assertEquals("MYTS_SIGNED_IDENTIFIER_INVALID", error.code)
    }

    @Test
    fun mapsSignedIdentifierSessionExpiredWithoutLeakingRemoteText() {
        val api = api(onMethod = { method, _ ->
            if (method == "getActiveIdentifierList") CloudReply(200, activeIdentifiers())
            else CloudReply(200, CloudProto.bytes(2,
                CloudProto.integer(1, 5) + CloudProto.text(2, "private-remote-message")))
        })
        val error = assertThrows(CloudFailure::class.java) {
            api.readPayload(account, matrixId)
        }
        assertEquals("MYTS_SIGNED_IDENTIFIER_5", error.code)
        assertFalse(error.toString().contains("private-remote-message"))
    }

    @Test
    fun refusesServerErrorsWhenReadingActiveIdentifiers() {
        val api = api(onMethod = { _, _ ->
            CloudReply(200, CloudProto.bytes(2,
                CloudProto.integer(1, 5) + CloudProto.text(2, "private-text")))
        })
        val error = assertThrows(CloudFailure::class.java) { api.read(account, matrixId) }
        assertEquals("MYTS_TAG_5", error.code)
        assertFalse(error.toString().contains("private-text"))
    }

    @Test
    fun neverPublishesOtherAccountsOrAmbiguousPrimaryEntries() {
        val responses = listOf(
            entry("wrong@myteamspeak.com", "@other:chat-2.tmspk.net") + ok,
            entry("one@myteamspeak.com") + entry("two@myteamspeak.com") + ok,
            entry("bad\nname") + ok,
            entry("x".repeat(101)) + ok,
        )
        for (response in responses) {
            val api = MyTsChatTag(CloudTransport { _, _, _, _ -> CloudReply(200, response) })
            assertNull(api.read(account, matrixId))
        }
    }

    @Test
    fun rejectsMissingSessionOrInvalidMatrixIdBeforeMakingRequests() {
        var requests = 0
        val api = MyTsChatTag(CloudTransport { _, _, _, _ ->
            requests++
            CloudReply(200, activeIdentifiers())
        })
        assertNull(api.read(MyTsAccount.Session("", "ignored"), matrixId))
        assertNull(api.read(account, "not-a-matrix-id"))
        assertEquals(0, requests)
    }

    @Test
    fun refusesInvalidUpdatedTimestamp() {
        val error = assertThrows(CloudFailure::class.java) {
            api().readPayload(account, matrixId, 123L)
        }
        assertEquals("MYTS_TAG_INVALID_UPDATED", error.code)
    }
}
