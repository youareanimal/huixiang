package io.github.korppujauho.pocketts.proto

import kotlin.test.*

class CloudAccountTest {
    private val domain = "chat-2.tmspk.net"
    private val uid = "@fixture:$domain"
    private fun account() = CloudAccountTransfer(domain, uid, "fixture-password", "fixture-session", "Fixture")
    private fun reply(value: Any?, status: Int = 200) = CloudReply(status, Json.write(value).toByteArray())

    @Test fun officialPasswordDigestVectorsIncludeUnicodeAndEmailCase() {
        assertEquals("10WdGC5D/bEhAmdvGATe2gbn6z6pz7/S/m/0Ma8+EG8yyXeBk2nUcvIANPU4Fh+8",
            MyTsAccount.passwordDigest("Alice@Example.com", "example-password"))
        assertEquals("0bC1w33SLwgo8UINew/Jz4WaSMJxBDfpTYcNSZCuE9RbdR7kpgbS48sWigPCuKOJ",
            MyTsAccount.passwordDigest("TEST@example.com", "päss密碼"))
        assertEquals(MyTsAccount.passwordDigest("ALICE@example.com", "x"), MyTsAccount.passwordDigest("alice@example.com", "x"))
    }

    @Test fun accountRpcUsesVerifiedFramingAndReportsOtp() {
        val api = MyTsAccount(CloudTransport { method, url, headers, body ->
            assertEquals("POST", method); assertEquals("https://clientapi.myteamspeak.com/authentication", url)
            assertEquals("application/ts3cloud", headers["Content-Type"])
            assertEquals(5, body!![0].toInt()); assertEquals("login", body.copyOfRange(1, 6).decodeToString())
            val fields = CloudProto.read(body.copyOfRange(6, body.size))
            assertEquals("fixture@example.com", fields.first { it.number == 1 }.bytes!!.decodeToString())
            CloudReply(200, CloudProto.integer(5, 208))
        })
        assertEquals("MYTS_208", assertFailsWith<CloudFailure> { api.login("fixture@example.com", "not-a-real-password") }.code)
    }

    @Test fun malformedProtobufAndUntrustedTransferHostsAreRejected() {
        for (bad in listOf(byteArrayOf(0), byteArrayOf(10, 120, 1), ByteArray(11) { 0x80.toByte() }))
            assertFails { CloudProto.read(bad) }
        val original = account().encode()
        assertEquals(uid, CloudAccountTransfer.parse(original).userId)
        for (host in listOf("chat-2.tmspk.net.evil.test", "chat-2.tmspk.net@evil.test", "127.0.0.1", "chat-2.tmspk.net:443"))
            assertFailsWith<CloudFailure> { CloudAccountTransfer.parse(original.replace(domain, host)) }
        assertFalse(account().toString().contains("fixture-password"))
        assertFalse(MyTsAccount.Session("private", "Fixture").toString().contains("private"))
    }

    @Test fun contactsAreReadUsingTheSessionWithoutCloudCredentialDependency() {
        val api = MyTsAccount(CloudTransport { _, url, _, body ->
            assertEquals("https://clientapi.myteamspeak.com/tschat", url)
            assertTrue(body!!.decodeToString().contains("getContactList"))
            CloudReply(200, CloudProto.bytes(1, CloudProto.text(1, "friend@myteamspeak.com") + CloudProto.text(3, "@friend:$domain")) +
                CloudProto.bytes(2, CloudProto.integer(1, 1)))
        })
        val list = api.contacts(MyTsAccount.Session("secret", "Fixture"))
        assertEquals("friend@myteamspeak.com", list.single().identifier)
        assertEquals("@friend:$domain", list.single().matrixId)
    }

    @Test fun encodedContactMetadataIsNotANameAndPublicProfilesNeedNoChatLogin() {
        val metadata = java.util.Base64.getEncoder().encodeToString(Json.write(mapOf("ownMatrixId" to "@self:$domain")).toByteArray())
        val contact = MyTsAccount.Contact("", "@friend:$domain", metadata)
        assertEquals("TeamSpeak 好友", contact.displayName)
        assertEquals("", contact.subtitle)
        var calls = 0
        val api = MyTsAccount(CloudTransport { method, url, headers, body ->
            calls++
            assertEquals("GET", method)
            assertEquals("https://$domain/_matrix/client/r0/profile/%40friend%3A$domain/displayname", url)
            assertFalse(headers.keys.any { it.equals("Authorization", ignoreCase = true) })
            assertNull(body)
            reply(mapOf("displayname" to "好友昵称"))
        })
        val named = api.withContactProfile(contact)
        assertEquals("好友昵称", named.displayName)
        assertEquals(metadata, named.note)
        assertEquals(1, calls)
        assertFalse(named.toString().contains(metadata))
        assertEquals("自定义备注", api.withContactProfile(contact.copy(note = "自定义备注")).displayName)
        assertEquals(1, calls)
    }

    @Test fun profileFailureAndUntrustedHostsDoNotHideTheContactListOrExposeMetadata() {
        val metadata = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString("{\"ownMatrixId\":\"@self:example.com\"}".toByteArray())
        val contact = MyTsAccount.Contact("", "@friend:$domain", metadata)
        val unavailable = MyTsAccount(CloudTransport { _, _, _, _ -> CloudReply(503, byteArrayOf()) })
        assertEquals("TeamSpeak 好友", unavailable.withContactProfile(contact).displayName)
        val offline = MyTsAccount(CloudTransport { _, _, _, _ -> error("Untrusted homeservers must not receive a request") })
        assertEquals("TeamSpeak 好友", offline.withContactProfile(contact.copy(matrixId = "@friend:chat-2.tmspk.net.evil.test")).displayName)
        assertEquals("普通备注", contact.copy(note = "普通备注").displayName)
    }

    @Test fun directMyTsAccountLoginCanFetchTheExistingCloudCredentials() {
        var calls = 0
        val api = MyTsAccount(CloudTransport { method, url, headers, body ->
            calls++
            assertEquals("POST", method)
            assertEquals("https://clientapi.myteamspeak.com/tschat", url)
            assertEquals("application/ts3cloud", headers["Content-Type"])
            val methodLength = body!![0].toInt()
            assertEquals("createAccount", body.copyOfRange(1, 1 + methodLength).decodeToString())
            val fields = CloudProto.read(body.copyOfRange(1 + methodLength, body.size))
            assertEquals("fixture-session", fields.first { it.number == 1 }.bytes!!.decodeToString())
            CloudReply(200,
                CloudProto.bytes(1, CloudProto.integer(1, 1)) +
                    CloudProto.bytes(2,
                        CloudProto.text(1, domain) +
                            CloudProto.text(2, "fixture-user") +
                            CloudProto.text(3, "fixture-chat-password")))
        })
        val account = api.chatCredentials(MyTsAccount.Session("fixture-session", "Fixture"))
        assertEquals(domain, account.domain)
        assertEquals("@fixture-user:$domain", account.userId)
        assertEquals("fixture-chat-password", account.password)
        assertEquals(1, calls)
        assertFalse(account.toString().contains("fixture-chat-password"))
    }

    @Test fun cloudCredentialFailuresStayMachineReadableAndDoNotReturnSecrets() {
        val expired = MyTsAccount(CloudTransport { _, _, _, _ ->
            CloudReply(200, CloudProto.bytes(1, CloudProto.integer(1, 5)))
        })
        assertEquals("MYTS_CHAT_5", assertFailsWith<CloudFailure> {
            expired.chatCredentials(MyTsAccount.Session("fixture-session", "Fixture"))
        }.code)

        val invalid = MyTsAccount(CloudTransport { _, _, _, _ ->
            CloudReply(200,
                CloudProto.bytes(1, CloudProto.integer(1, 1)) +
                    CloudProto.bytes(2,
                        CloudProto.text(1, "chat-2.tmspk.net.evil.test") +
                            CloudProto.text(2, "fixture") +
                            CloudProto.text(3, "opaque")))
        })
        assertEquals("MYTS_CHAT_INVALID_CREDENTIALS", assertFailsWith<CloudFailure> {
            invalid.chatCredentials(MyTsAccount.Session("fixture-session", "Fixture"))
        }.code)
    }

    private fun event(id: String, type: String, content: Map<String, Any?>, timestamp: Int = 1) = mapOf(
        "event_id" to id, "type" to type, "content" to content, "sender" to "@friend:$domain", "origin_server_ts" to timestamp)
    private fun sync(events: List<Any?>, state: List<Any?> = emptyList(), cursor: String = "cursor") = mapOf(
        "next_batch" to cursor, "rooms" to mapOf("join" to mapOf("!room:$domain" to mapOf(
            "state" to mapOf("events" to state), "timeline" to mapOf("events" to events, "prev_batch" to "history-token")))))

    @Test fun syncDeduplicatesMessagesAndSendingIsIdempotentAndEscaped() {
        val payload = sync(listOf(event("event1", "m.room.message", mapOf("body" to "你好 | + /", "msgtype" to "m.text"))))
        val sent = mutableListOf<String>()
        val api = MatrixCloud(domain, CloudTransport { method, url, headers, body ->
            assertEquals("Bearer token", headers["Authorization"])
            if (method == "GET" && url.endsWith("/state")) reply(listOf(
                mapOf("type" to "m.room.member", "state_key" to uid, "content" to mapOf("membership" to "join"))))
            else if (method == "GET") { assertTrue(url.contains("set_presence=offline")); reply(payload) }
            else { sent += url; assertEquals("PUT", method)
                val json = Json.parse(body!!.decodeToString()) as Map<*, *>
                assertEquals("文字测试", json["body"]); reply(mapOf("event_id" to "sent1")) }
        })
        val session = MatrixCloud.Session(uid, "token", "DEVICE_TEST")
        api.sync(session); val next = api.sync(session, "cursor with / &")
        assertEquals(1, next.rooms.single().messages.size)
        assertEquals("你好 | + /", next.rooms.single().messages.single().body)
        api.sendText(session, "!room:$domain", "文字测试", "TRANSACTION_1")
        api.sendText(session, "!room:$domain", "文字测试", "TRANSACTION_1")
        assertEquals(sent[0], sent[1]); assertTrue(sent[0].contains("%21room%3Achat-2.tmspk.net"))
    }

    @Test fun encryptedRoomCannotAccidentallySendPlaintext() {
        var writes = 0
        val api = MatrixCloud(domain, CloudTransport { method, _, _, _ ->
            if (method != "GET") writes++
            reply(sync(listOf(event("encrypted", "m.room.encrypted", mapOf("ciphertext" to "opaque")))))
        })
        val session = MatrixCloud.Session(uid, "token", "DEVICE_TEST")
        val room = api.sync(session).rooms.single()
        assertTrue(room.encrypted); assertEquals("[加密消息]", room.messages.single().body)
        assertEquals("ENCRYPTED_ROOM", assertFailsWith<CloudFailure> { api.sendText(session, room.id, "private", "TRANSACTION_2") }.code)
        assertEquals(0, writes)
    }

    @Test fun historyUsesTheSyncTokenOnceAndStopsWhenExhausted() {
        var historyCalls = 0
        val api = MatrixCloud(domain, CloudTransport { method, url, headers, _ ->
            assertEquals("Bearer token", headers["Authorization"])
            if (method == "GET" && url.contains("/sync")) {
                reply(sync(emptyList()))
            } else {
                historyCalls++
                assertEquals(1, historyCalls)
                assertTrue(url.contains("from=history-token"))
                reply(mapOf("chunk" to emptyList<Any?>()))
            }
        })
        val session = MatrixCloud.Session(uid, "token", "DEVICE_TEST")
        val room = api.sync(session).rooms.single()
        assertEquals(emptyList(), api.history(session, room.id))
        assertEquals("HISTORY_EXHAUSTED", assertFailsWith<CloudFailure> { api.history(session, room.id) }.code)
    }

    @Test fun transportRedirectAndTokenErrorsDoNotLeakResponseBodies() {
        val secret = "private-server-detail"
        val api = MatrixCloud(domain, CloudTransport { _, _, _, _ -> reply(mapOf("errcode" to "M_UNKNOWN_TOKEN", "error" to secret), 401) })
        val failure = assertFailsWith<CloudFailure> { api.sync(MatrixCloud.Session(uid, "token", "DEVICE_TEST")) }
        assertEquals("M_UNKNOWN_TOKEN", failure.code); assertFalse(failure.toString().contains(secret))
        val redirect = MyTsAccount(CloudTransport { _, _, _, _ -> CloudReply(302, byteArrayOf()) })
        assertEquals("HTTP_302", assertFailsWith<CloudFailure> { redirect.login("fixture@example.com", "x") }.code)
    }

    @Test fun loginRetriesTransientGatewayFailuresButNotAuthenticationFailures() {
        var gatewayAttempts = 0
        val retried = MatrixCloud(domain, CloudTransport { _, _, _, _ ->
            gatewayAttempts++
            if (gatewayAttempts < 3) CloudReply(503, byteArrayOf()) else
                reply(mapOf("user_id" to uid, "access_token" to "token", "device_id" to "DEVICE_TEST"))
        })
        retried.login(account(), "DEVICE_TEST")
        assertEquals(3, gatewayAttempts)

        var forbiddenAttempts = 0
        val forbidden = MatrixCloud(domain, CloudTransport { _, _, _, _ ->
            forbiddenAttempts++
            reply(mapOf("errcode" to "M_FORBIDDEN", "error" to "opaque"), 403)
        })
        assertEquals("M_FORBIDDEN", assertFailsWith<CloudFailure> { forbidden.login(account(), "DEVICE_TEST") }.code)
        assertEquals(1, forbiddenAttempts)
    }
}
