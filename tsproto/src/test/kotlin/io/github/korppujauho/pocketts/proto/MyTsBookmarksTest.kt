package io.github.korppujauho.pocketts.proto

import java.util.Base64
import kotlin.test.*

class MyTsBookmarksTest {
    private fun decode(value: String) = Base64.getDecoder().decode(value)
    private val key = decode("AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=")
    private val blob = decode("/v8AAAAAAAAAAAAAAAAAAKcqhMVJAiy1KAJNj7GZjU9GNPdWUh7T5h2q7YtT75skQgVuJOyCOAdGTEbopecBgyetq3PtJ3lnkESqLgtRAHBJlNmk/a2/aE3KUBFZ2I1Q3sMmnslK2c7663IHY4RojInmTHt9BRwZE+SLqVgoBRqH0uXrsQMA1GBmnLVBQiBa63UrYJe7Ycii0MFoUQjqdmo4wcn6DNsi7vitqDcrTx061GZSKkWxZQXtScRLdcs=")
    private val wrapped = decode("AfA6UXyn5XKclrMfEY3u+5cqKioqKioqKioqKipHMvBKU+EvyD7OwQqRWQW7DpK223gNksrrgVAmQnGS5g==")
    private val id = "00000000-0000-0000-0000-000000000001"
    private fun response(ciphertext: ByteArray = blob, uuid: String = id): ByteArray {
        val item = CloudProto.text(1, uuid) + CloudProto.bytes(3, ciphertext)
        val bookmarks = CloudProto.integer(1, 0) + CloudProto.bytes(3, item)
        // An unrelated class must be ignored, without attempting to decrypt identity data.
        val identity = CloudProto.integer(1, 1) + CloudProto.bytes(3, CloudProto.bytes(3, byteArrayOf(1)))
        return CloudProto.integer(1, 301) + CloudProto.bytes(2, bookmarks) + CloudProto.bytes(2, identity)
    }

    @Test fun independentVectorExercisesLittleEndianCounterCarryAndAccountKeyWrapping() {
        // Independently executed official native helper, synthetic input only (2026-10-26).
        assertEquals("56Cv0aPZGaIZDn1LTm+nkz1O7LP7qvdq3Yb/sn+FBq8=", Base64.getEncoder().encodeToString(
            MyTsBookmarks.encryptionPassword("Alice@Example.com", "fixture-password")))
        val derived = MyTsBookmarks.encryptionPassword("Alice@Example.com", "päss密碼")
        assertEquals("joQXb8f/kM7sjVJFV8M3x2YI3718ddUYS32HQzv/U70=", Base64.getEncoder().encodeToString(derived))
        assertContentEquals(key, MyTsBookmarks.unlockKey(wrapped, derived))
        val raw = decode("EiQwMDAwMDAwMC0wMDAwLTAwMDAtMDAwMC0wMDAwMDAwMDAwMDGCAUYKDkZpeHR1cmUgU2VydmVyEhF2b2ljZS5leGFtcGxlLmNvbRiETiIFQWxpY2U6F2ZpeHR1cmUtc2VydmVyLXBhc3N3b3Jk")
        assertContentEquals(raw, MyTsBookmarks.openItem(blob, key))
        assertEquals("BOOKMARK_KEY_AUTH", assertFailsWith<CloudFailure> { MyTsBookmarks.unlockKey(wrapped, ByteArray(32)) }.code)
    }

    @Test fun bookmarksUseOnlyTheAccountSyncEndpointAndSeparatePasswordsFromAddresses() {
        val api = MyTsBookmarks(CloudTransport { method, url, headers, body ->
            assertEquals("POST", method)
            assertEquals("https://clientapi.myteamspeak.com/synchronization", url)
            assertEquals("application/ts3cloud", headers["Content-Type"])
            assertEquals("requestServerItems", body!!.copyOfRange(1, 19).decodeToString())
            CloudReply(200, response())
        })
        val bookmark = api.read(MyTsAccount.Session("fixture-token", "Alice", key)).single()
        assertEquals("Fixture Server", bookmark.name)
        assertEquals("voice.example.com:9988", bookmark.address)
        assertEquals("Alice", bookmark.nickname)
        assertEquals("fixture-server-password", bookmark.password)
        assertFalse(bookmark.toString().contains("fixture-server-password"))
    }

    @Test fun tamperedItemsAndMismatchedIdsCannotBecomeConnectableBookmarks() {
        val damaged = blob.copyOf().also { it[25] = (it[25].toInt() xor 1).toByte() }
        assertEquals("BOOKMARK_CHECKSUM", assertFailsWith<CloudFailure> { MyTsBookmarks.openItem(damaged, key) }.code)
        val wrongOwner = MyTsBookmarks(CloudTransport { _, _, _, _ -> CloudReply(200, response(uuid = "wrong-item")) })
        assertEquals("BOOKMARK_INVALID_REPLY", assertFailsWith<CloudFailure> { wrongOwner.read(MyTsAccount.Session("fixture", "Alice", key)) }.code)
        val missingKey = MyTsBookmarks(CloudTransport { _, _, _, _ -> error("A missing key must not trigger network access") })
        assertEquals("BOOKMARK_KEY_REQUIRED", assertFailsWith<CloudFailure> { missingKey.read(MyTsAccount.Session("fixture", "Alice")) }.code)
    }

    @Test fun passwordLoginUnlocksBookmarksButAnUnavailableKeyDoesNotFailAccountAuthentication() {
        fun api(keyBlob: ByteArray) = MyTsAccount(CloudTransport { _, _, _, _ ->
            CloudReply(200, CloudProto.bytes(1, keyBlob) + CloudProto.text(2, "fixture-token") + CloudProto.integer(5, 200) + CloudProto.text(8, "Alice"))
        })
        assertContentEquals(key, api(wrapped).login("Alice@Example.com", "päss密碼").syncKey)
        val account = api(byteArrayOf(1)).login("Alice@Example.com", "päss密碼")
        assertEquals("Alice", account.username)
        assertEquals("fixture-token", account.token)
        assertNull(account.syncKey)
        assertEquals("BOOKMARK_KEY_FORMAT_B1_V1_K32", account.bookmarkKeyError)
        val wrongKey = api(wrapped).login("Alice@Example.com", "other-fixture-password")
        assertEquals("BOOKMARK_KEY_AUTH", wrongKey.bookmarkKeyError)
        val reader = MyTsBookmarks(CloudTransport { _, _, _, _ -> error("Failed unlocking must not request bookmarks") })
        assertEquals("BOOKMARK_KEY_AUTH", assertFailsWith<CloudFailure> { reader.read(wrongKey) }.code)
        assertEquals("BOOKMARK_KEY_MISSING", api(byteArrayOf()).login("Alice@Example.com", "päss密碼").bookmarkKeyError)
    }

    @Test fun versionTwoEnvelopeMatchesTheOfficialNativeReaderAndStillAuthenticatesEveryByte() {
        // Official native reader returned success and exactly the same seed for this synthetic envelope.
        val v2 = wrapped.copyOf().also { it[0] = 2 }
        val derived = MyTsBookmarks.encryptionPassword("Alice@Example.com", "päss密碼")
        assertContentEquals(key, MyTsBookmarks.unlockKey(v2, derived))
        val api = MyTsAccount(CloudTransport { _, _, _, _ -> CloudReply(200,
            CloudProto.bytes(1, v2) + CloudProto.text(2, "fixture-token") + CloudProto.integer(5, 200) + CloudProto.text(8, "Alice")) })
        val session = api.login("Alice@Example.com", "päss密碼")
        assertContentEquals(key, session.syncKey)
        assertNull(session.bookmarkKeyError)
        for (index in listOf(1, 17, 29, 60)) {
            val damaged = v2.copyOf().also { it[index] = (it[index].toInt() xor 1).toByte() }
            assertEquals("BOOKMARK_KEY_AUTH", assertFailsWith<CloudFailure> { MyTsBookmarks.unlockKey(damaged, derived) }.code)
        }
        val unsupported = v2.copyOf().also { it[0] = 3 }
        assertEquals("BOOKMARK_KEY_FORMAT_B61_V3_K32", assertFailsWith<CloudFailure> { MyTsBookmarks.unlockKey(unsupported, derived) }.code)
        assertEquals("BOOKMARK_KEY_FORMAT_B60_V2_K32", assertFailsWith<CloudFailure> { MyTsBookmarks.unlockKey(v2.copyOf(60), derived) }.code)
    }
}
