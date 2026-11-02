package io.github.korppujauho.pocketts.proto

import io.github.korppujauho.pocketts.proto.CloudProto.number
import io.github.korppujauho.pocketts.proto.CloudProto.string
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class MyTsChatTagTest {
    private val account = MyTsAccount.Session("private-session", "ignored")
    private val matrixId = "@fixture:chat-2.tmspk.net"
    private fun entry(tag: String, matrix: String = matrixId, primary: Boolean = true) =
        CloudProto.bytes(1, CloudProto.text(1, tag) + CloudProto.text(2, matrix) +
            CloudProto.integer(3, if (primary) 1 else 0))
    private val ok = CloudProto.bytes(2, CloudProto.integer(1, 1))

    @Test fun fetchesOnlyPrimaryIdentifierForTheExpectedMatrixAccount() {
        val api = MyTsChatTag(CloudTransport { method, url, headers, body ->
            assertEquals("POST", method)
            assertEquals("https://clientapi.myteamspeak.com/tschat", url)
            assertEquals("application/ts3cloud", headers["Content-Type"])
            val bytes = body!!
            val length = bytes[0].toInt()
            assertEquals("getActiveIdentifierList", bytes.copyOfRange(1, length + 1).toString(Charsets.US_ASCII))
            val fields = CloudProto.read(bytes.copyOfRange(length + 1, bytes.size))
            assertEquals(account.token, fields.string(1))
            assertEquals(matrixId, fields.string(2))
            CloudReply(200, entry("secondary@myteamspeak.com", primary = false) +
                entry("fixture@myteamspeak.com") + ok)
        })
        assertEquals("fixture@myteamspeak.com", api.read(account, matrixId))
    }

    @Test fun neverPublishesOtherAccountsOrAmbiguousPrimaryEntries() {
        val responses = listOf(entry("wrong@myteamspeak.com", "@other:chat-2.tmspk.net") + ok,
            entry("one@myteamspeak.com") + entry("two@myteamspeak.com") + ok,
            entry("bad\nname") + ok, entry("x".repeat(100)) + ok)
        for (response in responses) {
            val api = MyTsChatTag(CloudTransport { _, _, _, _ -> CloudReply(200, response) })
            assertNull(api.read(account, matrixId))
        }
    }

    @Test fun refusesServerErrorsWithoutExposingRemoteErrorText() {
        val api = MyTsChatTag(CloudTransport { _, _, _, _ ->
            CloudReply(200, CloudProto.bytes(2, CloudProto.integer(1, 5) + CloudProto.text(2, "private-text")))
        })
        val error = assertThrows(CloudFailure::class.java) { api.read(account, matrixId) }
        assertEquals("MYTS_TAG_5", error.code)
        assertFalse(error.toString().contains("private-text"))
    }

    @Test fun updateCarriesEscapedStructuredTagInBothOfficialFields() {
        val command = Commands.parse(MyTsChatTag.updateCommand("fixture@myteamspeak.com"))
        assertEquals("clientupdate", command.name)
        val expected = mapOf("tag" to "fixture@myteamspeak.com")
        assertEquals(expected, Json.parse(command["client_user_tag"]!!))
        assertEquals(expected, Json.parse(command["client_meta_data"]!!))
        assertEquals("quiet-myts-tag", command["return_code"])
    }
}
