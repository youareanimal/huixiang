package io.github.korppujauho.pocketts

import android.content.Context
import io.github.korppujauho.pocketts.proto.*
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.net.URI

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class CloudFriendChatControllerTest {
    private val context get() = RuntimeEnvironment.getApplication() as Context
    private val domain = "chat-2.tmspk.net"
    private val self = "@fixture:$domain"
    private val peer = "@friend:$domain"
    private val room = "!friend:$domain"
    private val friend = MyTsAccount.Contact("friend-id", peer, "好友")
    private fun reply(value: Any?, status: Int = 200) = CloudReply(status, Json.write(value).toByteArray())
    private fun bytes(n: Int, value: ByteArray) = byteArrayOf((n * 8 + 2).toByte(), value.size.toByte()) + value
    private fun text(n: Int, value: String) = bytes(n, value.toByteArray())

    private inner class Server : CloudTransport {
        var matrixCalls = 0; var createCalls = 0; var joins = 0; var receipts = 0
        var created = false; var invited = false; var joined = true
        var failSend = false; var failSync = false
        val sendUrls = mutableListOf<String>()
        var mapping = emptyMap<String, Any?>()
        var messages = emptyList<Any?>()
        private fun members() = listOf(
            mapOf("type" to "m.room.member", "state_key" to self, "sender" to peer,
                "content" to mapOf("membership" to if (joined) "join" else "invite", "displayname" to "Fixture", "is_direct" to true)),
            mapOf("type" to "m.room.member", "state_key" to peer, "content" to mapOf("membership" to "join", "displayname" to "好友")))
        override fun request(method: String, url: String, headers: Map<String, String>, body: ByteArray?): CloudReply {
            if (url.endsWith("/tschat")) return CloudReply(200, bytes(1, text(1, "friend-id") + text(2, "好友") + text(3, peer)) + byteArrayOf(18, 2, 8, 1))
            if (URI(url).host == "clientapi.myteamspeak.com") return CloudReply(503, byteArrayOf())
            matrixCalls++
            assertEquals("Bearer private-matrix-token", headers["Authorization"])
            val path = URI(url).path.removePrefix("/_matrix/client/r0")
            val data = body?.let { Json.parse(it.decodeToString()) as Map<*, *> }
            return when {
                path == "/sync" -> {
                    if (failSync) return reply(emptyMap<String, Any?>(), 503)
                    val value = if (invited && !joined) mapOf("invite" to mapOf(room to mapOf("invite_state" to mapOf("events" to members()))))
                        else if (created || invited) mapOf("join" to mapOf(room to mapOf("state" to mapOf("events" to members()),
                            "timeline" to mapOf("events" to messages, "prev_batch" to "history")))) else emptyMap()
                    reply(mapOf("next_batch" to "cursor", "rooms" to value,
                        "account_data" to mapOf("events" to listOf(mapOf("type" to "m.direct", "content" to mapping)))))
                }
                path.endsWith("/account_data/m.direct") && method == "GET" -> reply(mapping)
                path.endsWith("/account_data/m.direct") && method == "PUT" -> {
                    mapping = data!!.entries.associate { it.key.toString() to it.value }; reply(emptyMap<String, Any?>())
                }
                path == "/createRoom" -> { createCalls++; created = true; reply(mapOf("room_id" to room)) }
                path.startsWith("/join/") -> { joins++; joined = true; reply(mapOf("room_id" to room)) }
                path.endsWith("/state") -> reply(members())
                "/send/m.room.message/" in path -> {
                    sendUrls += url
                    if (failSend) throw CloudFailure("NETWORK")
                    messages = listOf(mapOf("event_id" to "accepted", "type" to "m.room.message", "sender" to self,
                        "origin_server_ts" to 123, "content" to mapOf("msgtype" to "m.text", "body" to data!!["body"])))
                    reply(mapOf("event_id" to "accepted"))
                }
                "/receipt/" in path -> { receipts++; reply(emptyMap<String, Any?>()) }
                else -> error("Unexpected fixture path $path")
            }
        }
    }
    private fun controller(server: Server): CloudAccountController {
        context.getSharedPreferences("cloud-account", Context.MODE_PRIVATE).edit().clear().putString("device", "DEVICE_FIXTURE").commit()
        val prefs = context.getSharedPreferences("friend-chat-fixture", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val box = SecretBox(SoftwareKey())
        val store = CloudAccountStore(prefs) { box }
        store.write(CloudAccountTransfer(domain, self, "private-password", "fixture-session", "Fixture"),
            MatrixCloud.Session(self, "private-matrix-token", "DEVICE_FIXTURE"))
        store.writeContacts(store.identity()!!, listOf(friend))
        return CloudAccountController(context, server, Dispatchers.Unconfined, store)
    }

    @Test fun tappingFriendConnectsOnlyOnDemandAndOpensOrReusesThePrivateConversation() {
        val server = Server(); val c = controller(server)
        try {
            assertTrue(c.state.value.hasAccount); assertFalse(c.state.value.cloudConnected); assertEquals(0, server.matrixCalls)
            c.openFriendChat(friend)
            assertTrue(c.state.value.cloudConnected); assertEquals(room, c.state.value.openRoomId)
            assertEquals(self, c.state.value.cloudUserId)
            c.consumeOpenRoom(room); assertNull(c.state.value.openRoomId)
            c.openFriendChat(friend)
            assertEquals(1, server.createCalls); assertEquals(room, c.state.value.openRoomId)
        } finally { c.close() }
    }

    @Test fun failedSendKeepsItsTransactionForRetryAndAcknowledgedTextAppearsOnce() {
        val server = Server(); val c = controller(server)
        try {
            c.openFriendChat(friend)
            server.failSend = true; c.send(room, "你好 👋")
            assertEquals("NETWORK", c.state.value.sendError); assertEquals(room, c.state.value.sendErrorRoomId)
            assertEquals(0, c.state.value.sent); assertFalse(c.state.value.sending)
            server.failSend = false; c.send(room, "你好 👋")
            assertEquals(server.sendUrls[0], server.sendUrls[1])
            assertEquals(1, c.state.value.sent); assertEquals(room, c.state.value.lastSentRoomId)
            assertEquals("你好 👋", c.state.value.lastSentText)
            assertEquals(1, c.state.value.rooms.single().messages.size)
            assertNull(c.state.value.sendError)
        } finally { c.close() }
    }

    @Test fun syncFailureAfterAcceptanceDoesNotTurnAnAcceptedMessageIntoAFailedSend() {
        val server = Server(); val c = controller(server)
        try {
            c.openFriendChat(friend); server.failSync = true; c.send(room, "accepted text")
            assertEquals(1, c.state.value.sent); assertNull(c.state.value.sendError)
            assertEquals("HTTP_503", c.state.value.cloudError)
            assertEquals("accepted text", c.state.value.rooms.single().messages.single().body)
            assertTrue(c.state.value.hasAccount); assertTrue(c.state.value.cloudConnected)
        } finally { c.close() }
    }

    @Test fun repeatedVisibilityCallbacksOnlySendOneReceiptForTheSameMessage() {
        val server = Server(); val c = controller(server)
        try {
            c.openFriendChat(friend); c.send(room, "accepted text")
            c.markRead(room, "accepted"); c.markRead(room, "accepted")
            assertEquals(1, server.receipts)
        } finally { c.close() }
    }

    @Test fun incomingInviteRequiresAcceptanceAndAnOldContactCannotBeUsedAfterLogout() {
        val server = Server().apply { invited = true; joined = false }; val c = controller(server)
        try {
            c.connectCloud(); assertFalse(c.state.value.rooms.single().joined); assertEquals(0, server.joins)
            c.acceptRoom(room); assertTrue(c.state.value.rooms.single().joined); assertEquals(room, c.state.value.openRoomId)
            assertEquals(1, server.joins); assertEquals(0, server.createCalls)
            c.forget(); val before = server.matrixCalls; c.openFriendChat(friend)
            assertEquals(before, server.matrixCalls); assertFalse(c.state.value.hasAccount)
            assertEquals("CONTACT_UNAVAILABLE", c.state.value.cloudError)
        } finally { c.close() }
    }
}
