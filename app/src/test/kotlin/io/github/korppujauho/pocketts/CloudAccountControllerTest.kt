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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class CloudAccountControllerTest {
    private val context get() = RuntimeEnvironment.getApplication() as Context
    private fun store(): CloudAccountStore {
        val prefs = context.getSharedPreferences("controller-fixture", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val box = SecretBox(SoftwareKey())
        return CloudAccountStore(prefs) { box }
    }
    private fun bytes(field: Int, value: ByteArray): ByteArray {
        require(value.size < 128)
        return byteArrayOf((field * 8 + 2).toByte(), value.size.toByte()) + value
    }
    private fun text(field: Int, value: String) = bytes(field, value.toByteArray())
    private fun authenticated() = CloudReply(200, byteArrayOf(40, 0xc8.toByte(), 1) + text(2, "fixture-token") + text(8, "Fixture"))
    private fun friends() = CloudReply(200, bytes(1,
        text(1, "Friend") + text(2, "好友备注") + text(3, "@friend:chat-2.tmspk.net")) + byteArrayOf(18, 2, 8, 1))
    private fun controller(store: CloudAccountStore, transport: CloudTransport, profile: Boolean = false) =
        CloudAccountController(context, CloudTransport { method, url, headers, body ->
            if (!profile && url.endsWith("/user")) CloudReply(503, byteArrayOf()) else transport.request(method, url, headers, body)
        }, Dispatchers.Unconfined, store)

    @Test fun olderAccountsGainEmailAndAvatarWithoutChatLoginAndKeepCacheWhenProfileIsUnavailable() {
        val saved = store()
        val old = MyTsAccount.Session("fixture-token", "Fixture")
        saved.writeIdentity(old)
        saved.writeContacts(old, listOf(MyTsAccount.Contact("Friend", "", "好友备注")))
        val image = "https://storage.googleapis.com/test-bucket/fixture"
        val profileBody = bytes(1, byteArrayOf(8, 1)) + text(2, "Fixture") + text(3, "fixture@example.com") +
            bytes(5, bytes(1, byteArrayOf(8, 2) + text(2, image)))
        var fail = false
        val c = controller(saved, CloudTransport { _, url, _, body ->
            when {
                url.endsWith("/user") -> {
                    assertTrue(body!!.toString(Charsets.UTF_8).contains("getAccountData"))
                    if (fail) CloudReply(503, byteArrayOf()) else CloudReply(200, profileBody)
                }
                url.endsWith("/tschat") -> friends()
                else -> error("Profile must not connect to chat")
            }
        }, profile = true)
        try {
            assertEquals("fixture@example.com", c.state.value.email)
            assertEquals(image, c.state.value.avatarUrl)
            assertNotNull(saved.contacts(old))
            fail = true; c.refreshProfile()
            assertEquals("fixture@example.com", c.state.value.email)
            assertEquals(image, c.state.value.avatarUrl)
            assertEquals("HTTP_503", c.state.value.profileError)
            assertNull(c.state.value.error)
            assertFalse(c.state.value.cloudConnected)
        } finally { c.close() }
        val offline = controller(saved, CloudTransport { _, _, _, _ -> CloudReply(503, byteArrayOf()) })
        try {
            assertEquals("fixture@example.com", offline.state.value.email)
            assertEquals(image, offline.state.value.avatarUrl)
            offline.forget()
            assertEquals("", offline.state.value.email)
            assertEquals("", offline.state.value.avatarUrl)
            assertNull(saved.profile(old))
        } finally { offline.close() }
    }

    @Test fun loginAndReopeningTheAccountPageAutomaticallyLoadFriendsWithoutChatLogin() {
        val saved = store()
        val calls = mutableListOf<String>()
        val c = controller(saved, CloudTransport { _, url, _, _ ->
            calls += url
            when {
                url.endsWith("/authentication") -> authenticated()
                url.endsWith("/tschat") -> friends()
                else -> error("Account page must not log in to Matrix")
            }
        })
        try {
            c.loginAccount("fixture@example.com", "fixture-password", "")
            assertTrue(c.state.value.hasAccount)
            assertEquals("Fixture", c.state.value.username)
            assertEquals("Fixture", Settings(context).nickname)
            assertNull(c.state.value.error)
            assertFalse(c.state.value.cloudConnected)
            assertEquals(2, calls.size)
            assertTrue(c.state.value.contactsLoaded)
            assertFalse(c.state.value.contactsLoading)
            assertEquals("好友备注", c.state.value.contacts.single().displayName)
            assertEquals("fixture-token", saved.identity()!!.token)
            assertEquals("BOOKMARK_KEY_MISSING", saved.identity()!!.bookmarkKeyError)
            assertEquals("BOOKMARK_KEY_MISSING", c.state.value.bookmarkError)
            assertNull(saved.account())
        } finally { c.close() }
        val restoredCalls = mutableListOf<String>()
        val restored = controller(saved, CloudTransport { _, url, _, body ->
            restoredCalls += url
            assertEquals("https://clientapi.myteamspeak.com/tschat", url)
            assertTrue(body!!.toString(Charsets.UTF_8).contains("getContactList"))
            friends()
        })
        try {
            assertTrue(restored.state.value.hasAccount)
            assertEquals("Fixture", restored.state.value.username)
            assertFalse(restored.state.value.cloudConnected)
            assertEquals("BOOKMARK_KEY_MISSING", restored.state.value.bookmarkError)
            assertEquals(1, restoredCalls.size)
            assertTrue(restored.state.value.contactsLoaded)
            assertEquals("好友备注", restored.state.value.contacts.single().displayName)
            restored.refresh()
            assertNull(restored.state.value.cloudError)
        } finally { restored.close() }
    }

    @Test fun keyValidationFailureRemainsSpecificAfterClosingTheAccountPage() {
        val saved = store()
        saved.writeIdentity(MyTsAccount.Session("fixture-token", "Fixture", bookmarkKeyError = "BOOKMARK_KEY_AUTH"))
        val c = controller(saved, CloudTransport { _, url, _, _ ->
            assertEquals("https://clientapi.myteamspeak.com/tschat", url)
            friends()
        })
        try {
            assertTrue(c.state.value.hasAccount)
            assertEquals("BOOKMARK_KEY_AUTH", c.state.value.bookmarkError)
            assertNull(c.state.value.error)
        } finally { c.close() }
    }

    @Test fun versionTwoPasswordLoginSynchronizesBookmarksAndRestoresWithoutChatLogin() {
        val saved = store()
        val v2 = java.util.Base64.getDecoder().decode("AfA6UXyn5XKclrMfEY3u+5cqKioqKioqKioqKipHMvBKU+EvyD7OwQqRWQW7DpK223gNksrrgVAmQnGS5g==").also { it[0] = 2 }
        val calls = mutableListOf<String>()
        val c = controller(saved, CloudTransport { _, url, _, _ ->
            calls += url
            when {
                url.endsWith("/authentication") -> CloudReply(200, authenticated().body + byteArrayOf(10, v2.size.toByte()) + v2)
                url.endsWith("/synchronization") -> CloudReply(200, byteArrayOf(8, 0xad.toByte(), 2))
                url.endsWith("/tschat") -> friends()
                else -> error("Bookmark login must not connect to cloud chat")
            }
        })
        try {
            c.loginAccount("Alice@Example.com", "päss密碼", "")
            assertTrue(c.state.value.hasAccount)
            assertTrue(c.state.value.bookmarksLoaded)
            assertNull(c.state.value.bookmarkError)
            assertEquals(3, calls.size)
            assertTrue(c.state.value.contactsLoaded)
            assertArrayEquals(ByteArray(32) { it.toByte() }, saved.identity()!!.syncKey)
        } finally { c.close() }
        val restored = controller(saved, CloudTransport { _, url, _, _ ->
            if (url.endsWith("/synchronization")) return@CloudTransport CloudReply(200, byteArrayOf(8, 0xad.toByte(), 2))
            assertEquals("https://clientapi.myteamspeak.com/tschat", url)
            friends()
        })
        try {
            assertTrue(restored.state.value.bookmarksLoaded)
            assertNull(restored.state.value.bookmarkError)
            assertTrue(restored.state.value.contactsLoaded)
        } finally { restored.close() }
    }

    @Test fun explicitChat503AndFriendFailureCannotUndoAccountLogin() {
        val saved = store()
        val calls = mutableListOf<String>()
        val c = controller(saved, CloudTransport { _, url, _, body ->
            calls += url
            when {
                url.endsWith("/authentication") -> authenticated()
                url.endsWith("/tschat") && body!!.toString(Charsets.UTF_8).contains("createAccount") -> {
                    val credentials = text(1, "chat-2.tmspk.net") + text(2, "fixture") + text(3, "chat-password")
                    CloudReply(200, byteArrayOf(10, 2, 8, 1, 18, credentials.size.toByte()) + credentials)
                }
                else -> CloudReply(503, byteArrayOf())
            }
        })
        try {
            c.loginAccount("fixture@example.com", "fixture-password", "")
            assertEquals(2, calls.size)
            assertTrue(c.state.value.hasAccount)
            assertEquals("HTTP_503", c.state.value.contactError)
            assertNull(c.state.value.error)
            assertFalse(calls.any { it.contains("tmspk.net") })
            c.connectCloud()
            assertTrue(c.state.value.hasAccount)
            assertEquals("Fixture", c.state.value.username)
            assertEquals("CHAT_LOGIN_HTTP_503", c.state.value.cloudError)
            assertNull(c.state.value.error)
            assertFalse(c.state.value.cloudConnected)
            assertEquals("fixture-token", saved.identity()!!.token)
        } finally { c.close() }
    }

    @Test fun rejectedLoginPreservesThePreviouslySavedIdentity() {
        val saved = store()
        saved.writeIdentity(MyTsAccount.Session("old-token", "Old account"))
        val c = controller(saved, CloudTransport { _, url, _, _ ->
            if (url.endsWith("/tschat")) friends() else CloudReply(200, byteArrayOf(40, 0xca.toByte(), 1))
        })
        try {
            c.loginAccount("wrong@example.com", "wrong-password", "")
            assertTrue(c.state.value.hasAccount)
            assertEquals("Old account", c.state.value.username)
            assertEquals("MYTS_202", c.state.value.error)
            assertEquals("old-token", saved.identity()!!.token)
        } finally { c.close() }
    }

    @Test fun legacyChatCredentialsDoNotTriggerAutomaticChatConnections() {
        val saved = store()
        saved.write(CloudAccountTransfer("chat-2.tmspk.net", "@fixture:chat-2.tmspk.net", "private", "old-token", "Old account"),
            MatrixCloud.Session("@fixture:chat-2.tmspk.net", "private-token", "DEVICE_FIXTURE"))
        val c = controller(saved, CloudTransport { _, url, _, body ->
            assertEquals("https://clientapi.myteamspeak.com/tschat", url)
            assertTrue(body!!.toString(Charsets.UTF_8).contains("getContactList"))
            friends()
        })
        try {
            assertTrue(c.state.value.hasAccount)
            assertEquals("Old account", c.state.value.username)
            assertFalse(c.state.value.cloudConnected)
            assertNull(c.state.value.error)
            assertTrue(c.state.value.contactsLoaded)
        } finally { c.close() }
    }

    @Test fun cachedFriendNamesSurviveOfflineRestorationWithoutCloudLogin() {
        val saved = store()
        val identity = MyTsAccount.Session("fixture-token", "Fixture")
        saved.writeIdentity(identity)
        saved.writeContacts(identity, listOf(MyTsAccount.Contact("", "@fixture:chat-2.tmspk.net", "", "熟悉的好友")))
        val c = controller(saved, CloudTransport { _, url, _, _ ->
            assertEquals("https://clientapi.myteamspeak.com/tschat", url)
            CloudReply(503, byteArrayOf())
        })
        try {
            assertTrue(c.state.value.restored)
            assertTrue(c.state.value.hasAccount)
            assertEquals("熟悉的好友", c.state.value.contacts.single().displayName)
            assertEquals("HTTP_503", c.state.value.contactError)
            assertFalse(c.state.value.cloudConnected)
            assertNull(c.state.value.error)
        } finally { c.close() }
    }

    @Test fun slowFriendRequestsDoNotHoldTheAuthenticationAndChatOperationLock() {
        val saved = store()
        val started = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val c = CloudAccountController(context, CloudTransport { _, url, _, body ->
            when {
                url.endsWith("/authentication") -> authenticated()
                body?.toString(Charsets.UTF_8)?.contains("getContactList") == true -> {
                    started.countDown(); release.await(5, java.util.concurrent.TimeUnit.SECONDS); friends()
                }
                else -> CloudReply(503, byteArrayOf())
            }
        }, Dispatchers.IO, saved)
        try {
            c.loginAccount("fixture@example.com", "fixture-password", "")
            assertTrue(started.await(3, java.util.concurrent.TimeUnit.SECONDS))
            assertTrue(c.state.value.hasAccount)
            c.connectCloud()
            val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(3)
            while (c.state.value.cloudError == null && System.nanoTime() < deadline) Thread.sleep(10)
            assertEquals("CHAT_CREDENTIALS_HTTP_503", c.state.value.cloudError)
            assertTrue(c.state.value.hasAccount)
        } finally { release.countDown(); c.close() }
    }
}
