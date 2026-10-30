package io.github.korppujauho.pocketts.proto

import java.net.URI
import kotlin.test.*

class MatrixDirectChatTest {
    private val domain = "chat-2.tmspk.net"
    private val own = "@self:$domain"
    private val peer = "@friend:$domain"
    private val roomId = "!direct:$domain"
    private val session = MatrixCloud.Session(own, "private-token", "DEVICE_FIXTURE")
    private fun reply(value: Any?, status: Int = 200) = CloudReply(status, Json.write(value).toByteArray())
    private fun member(id: String, membership: String = "join", direct: Boolean = false) = mapOf(
        "type" to "m.room.member", "state_key" to id, "sender" to peer,
        "content" to mapOf("membership" to membership, "displayname" to if (id == own) "Self" else "Friend", "is_direct" to direct))
    private fun text(id: String = "event1", body: String = "Hello") = mapOf("type" to "m.room.message", "event_id" to id,
        "sender" to peer, "origin_server_ts" to 123, "content" to mapOf("msgtype" to "m.text", "body" to body))

    private inner class Server : CloudTransport {
        var direct = mapOf<String, Any?>("@other:$domain" to listOf("!other:$domain"))
        var events: List<Any?> = listOf(member(own), member(peer))
        var snapshot: Map<String, Any?> = mapOf("next_batch" to "cursor", "rooms" to emptyMap<String, Any?>())
        var createCalls = 0
        var joinCalls = 0
        var receiptCalls = 0
        var failMapping = false
        var failCreate = false
        val sends = mutableListOf<String>()
        var creation: Map<*, *>? = null
        override fun request(method: String, url: String, headers: Map<String, String>, body: ByteArray?): CloudReply {
            assertEquals("Bearer private-token", headers["Authorization"])
            assertEquals(domain, URI(url).host)
            val path = URI(url).path.removePrefix("/_matrix/client/r0")
            val json = body?.let { Json.parse(it.decodeToString()) as Map<*, *> }
            return when {
                path == "/sync" -> reply(snapshot)
                path.endsWith("/account_data/m.direct") && method == "GET" -> reply(direct)
                path.endsWith("/account_data/m.direct") && method == "PUT" -> {
                    if (failMapping) reply(mapOf("errcode" to "M_UNKNOWN"), 503)
                    else { direct = json!!.entries.associate { it.key.toString() to it.value }; reply(emptyMap<String, Any?>()) }
                }
                path == "/createRoom" -> {
                    createCalls++; creation = json
                    if (failCreate) throw CloudFailure("NETWORK") else reply(mapOf("room_id" to roomId))
                }
                path.startsWith("/join/") -> { joinCalls++; events = listOf(member(own), member(peer)); reply(mapOf("room_id" to roomId)) }
                path.endsWith("/state") -> reply(events)
                "/send/m.room.message/" in path -> { sends += url; reply(mapOf("event_id" to "accepted")) }
                "/receipt/m.read/" in path -> { receiptCalls++; reply(emptyMap<String, Any?>()) }
                path.endsWith("/messages") -> reply(mapOf("chunk" to emptyList<Any?>()))
                else -> error("Unexpected fixture request: $method $path")
            }
        }
        fun joined(messages: List<Any?> = listOf(text()), unread: Int = 0) {
            snapshot = mapOf("next_batch" to "next", "rooms" to mapOf("join" to mapOf(roomId to mapOf(
                "state" to mapOf("events" to events), "timeline" to mapOf("events" to messages, "prev_batch" to "history"),
                "unread_notifications" to mapOf("notification_count" to unread)))))
        }
    }

    @Test fun createPrivateDirectChatPreservesOtherMappingsAndReopeningNeverCreatesAnotherRoom() {
        val server = Server(); val api = MatrixCloud(domain, server)
        val room = api.openDirect(session, peer)
        assertEquals(roomId, room.id); assertEquals(peer, room.directPeer); assertTrue(room.joined)
        assertEquals("private", server.creation!!["visibility"])
        assertEquals("trusted_private_chat", server.creation!!["preset"])
        assertEquals(true, server.creation!!["is_direct"])
        assertEquals(listOf(peer), server.creation!!["invite"])
        assertFalse(server.creation!!.containsKey("name")) // Never publish a locally chosen friend remark.
        assertEquals(listOf("!other:$domain"), server.direct["@other:$domain"])
        assertEquals(listOf(roomId), server.direct[peer])
        assertEquals(roomId, api.openDirect(session, peer).id)
        assertEquals(1, server.createCalls)
    }

    @Test fun failedMappingWriteRecoversTheAcknowledgedRoomInsteadOfInvitingAgain() {
        val server = Server().apply { failMapping = true }; val api = MatrixCloud(domain, server)
        assertEquals("HTTP_503", assertFailsWith<CloudFailure> { api.openDirect(session, peer) }.code)
        server.failMapping = false
        assertEquals(roomId, api.openDirect(session, peer).id)
        assertEquals(1, server.createCalls)
    }

    @Test fun anUncertainCreateIsNotBlindlyRepeated() {
        val server = Server().apply { failCreate = true }; val api = MatrixCloud(domain, server)
        assertEquals("NETWORK", assertFailsWith<CloudFailure> { api.openDirect(session, peer) }.code)
        assertEquals("CHAT_CREATE_UNCERTAIN", assertFailsWith<CloudFailure> { api.openDirect(session, peer) }.code)
        assertEquals(1, server.createCalls)
    }

    @Test fun existingEncryptedDirectRoomIsReusedWithoutDowngradingOrSendingPlaintext() {
        val server = Server().apply {
            direct = direct + (peer to listOf(roomId))
            events = events + mapOf("type" to "m.room.encryption", "content" to mapOf("algorithm" to "m.megolm.v1.aes-sha2"))
        }
        val api = MatrixCloud(domain, server)
        assertTrue(api.openDirect(session, peer).encrypted)
        assertEquals("ENCRYPTED_ROOM", assertFailsWith<CloudFailure> { api.sendText(session, roomId, "secret", "TRANSACTION_1") }.code)
        assertEquals(0, server.createCalls); assertEquals(emptyList(), server.sends)
    }

    @Test fun aDirectInviteIsShownWithoutAutoJoiningAndExplicitOpeningAcceptsIt() {
        val server = Server().apply { snapshot = mapOf("next_batch" to "invited", "rooms" to mapOf("invite" to mapOf(
            roomId to mapOf("invite_state" to mapOf("events" to listOf(member(own, "invite", true), member(peer))))))) }
        val api = MatrixCloud(domain, server)
        assertFalse(api.sync(session).rooms.single().joined)
        assertEquals(0, server.joinCalls)
        assertTrue(api.openDirect(session, peer).joined)
        assertEquals(1, server.joinCalls); assertEquals(0, server.createCalls)
    }

    @Test fun membershipAndEncryptionAreRecheckedBeforeEverySend() {
        val server = Server(); val api = MatrixCloud(domain, server)
        api.openDirect(session, peer)
        server.events = server.events + member("@third:$domain")
        assertEquals("NOT_DIRECT_ROOM", assertFailsWith<CloudFailure> { api.sendText(session, roomId, "private", "TRANSACTION_1") }.code)
        server.events = listOf(member(own), member(peer), mapOf("type" to "m.room.encryption", "content" to emptyMap<String, Any?>()))
        assertEquals("ENCRYPTED_ROOM", assertFailsWith<CloudFailure> { api.sendText(session, roomId, "private", "TRANSACTION_1") }.code)
        assertEquals(emptyList(), server.sends)
    }

    @Test fun acceptedSendAppearsImmediatelyAndServerEchoUpdatesRatherThanDuplicatesIt() {
        val server = Server(); val api = MatrixCloud(domain, server)
        api.openDirect(session, peer)
        api.sendText(session, roomId, "你好 👋", "TRANSACTION_1")
        assertEquals("你好 👋", api.current().single().messages.single().body)
        api.sendText(session, roomId, "你好 👋", "TRANSACTION_1")
        assertEquals(server.sends[0], server.sends[1])
        assertEquals(1, api.current().single().messages.size)
        server.joined(listOf(text("accepted", "你好 👋")))
        assertEquals(123L, api.sync(session).rooms.single().messages.single().timestamp)
    }

    @Test fun exhaustedHistoryDoesNotReappearOnTheNextSyncAndReadingClearsUnread() {
        val server = Server().apply { joined(unread = 3) }; val api = MatrixCloud(domain, server)
        assertTrue(api.sync(session).rooms.single().hasMoreHistory)
        api.history(session, roomId)
        assertFalse(api.sync(session, "next").rooms.single().hasMoreHistory)
        assertEquals("HISTORY_EXHAUSTED", assertFailsWith<CloudFailure> { api.history(session, roomId) }.code)
        api.markRead(session, roomId, "event1")
        assertEquals(1, server.receiptCalls); assertEquals(0, api.current().single().unread)
    }

    @Test fun invalidOrUntrustedRecipientDoesNotMakeAnyNetworkRequest() {
        val api = MatrixCloud(domain, CloudTransport { _, _, _, _ -> error("Invalid recipient must not reach HTTP") })
        for (bad in listOf("", own, "@friend:evil.test", "@friend:$domain/path", "@friend:$domain\n"))
            assertEquals("CONTACT_NO_CHAT", assertFailsWith<CloudFailure> { api.openDirect(session, bad) }.code)
    }
}
