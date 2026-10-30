package io.github.korppujauho.pocketts

import android.content.Context
import io.github.korppujauho.pocketts.proto.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class CloudAccountStoreTest {
    @Test fun profileAndEmailAreSealedOwnerBoundAndDoNotEraseChatOrBookmarkKeys() {
        val p = prefs(); val box = SecretBox(SoftwareKey()); val store = CloudAccountStore(p) { box }
        store.write(account(), session())
        val owner = store.identity()!!
        store.writeContacts(owner, listOf(MyTsAccount.Contact("private-contact", "", "")))
        store.writeProfile(owner, MyTsProfile.Data("Fixture", "private-email@example.com", "https://storage.googleapis.com/bucket/private-avatar"))
        assertEquals(account().password, store.account()!!.password)
        assertEquals(session().token, store.session(account().userId, "DEVICE_FIXTURE")!!.token)
        assertNotNull(store.contacts(owner))
        assertEquals("private-email@example.com", store.profile(owner)!!.email)
        assertNull(store.profile(MyTsAccount.Session("another-token", "Other")))
        assertFalse(p.all.values.joinToString().contains("private-"))
        store.writeIdentity(MyTsAccount.Session("next-token", "Next", email = "next@example.com"))
        assertEquals("next@example.com", store.identity()!!.email)
        assertNull(store.profile(owner))
    }
    @Test fun contactCacheIsSealedOwnerBoundAndRemovedWhenSwitchingAccounts() {
        val p = prefs(); val box = SecretBox(SoftwareKey()); val store = CloudAccountStore(p) { box }
        val identity = MyTsAccount.Session("private-token", "Fixture")
        store.writeIdentity(identity)
        store.writeContacts(identity, listOf(MyTsAccount.Contact("private-id", "@fixture:chat-2.tmspk.net", "", "private-name")))
        assertFalse(p.all.values.joinToString().contains("private-"))
        assertEquals("private-name", store.contacts(identity)!!.single().displayName)
        assertNull(store.contacts(MyTsAccount.Session("another-token", "Other")))
        store.writeIdentity(MyTsAccount.Session("new-token", "New"))
        assertNull(store.contacts(identity))
    }
    @Test fun voiceMaterialIsSealedRestoredAndCannotCrossAccountSessions() {
        val p = prefs(); val box = SecretBox(SoftwareKey()); val store = CloudAccountStore(p) { box }
        // Entirely synthetic protobuf: LoginSession #9 id data, #10 certificate, #11 signed avatar.
        val encoded = java.util.Base64.getEncoder().encodeToString(byteArrayOf(74, 5, 10, 3, 107, 101, 121,
            82, 3, 10, 1, 99, 90, 3, 26, 1, 115))
        val material = MyTsVoiceMaterial.decodeFromStorage(encoded)
        store.writeIdentity(MyTsAccount.Session(account().myTsSession, "Fixture", voiceMaterial = material))
        assertEquals(encoded, store.identity()!!.voiceMaterial!!.encodeForStorage())
        assertFalse(p.all.values.joinToString().contains(encoded))
        assertTrue(p.all.values.all { SecretBox.isSealed(it as String) })
        store.write(account(), session())
        assertEquals(encoded, store.identity()!!.voiceMaterial!!.encodeForStorage())
        store.writeIdentity(MyTsAccount.Session("other-session", "Other"))
        assertNull(store.identity()!!.voiceMaterial)
        store.forget()
        assertNull(store.identity())
    }

    @Test fun legacySessionsWithoutVoiceMaterialStillRestore() {
        val p = prefs(); val box = SecretBox(SoftwareKey()); val store = CloudAccountStore(p) { box }
        p.edit().putString("myts-identity", box.seal(Json.write(mapOf("token" to "legacy-token",
            "username" to "Legacy")), "cloud/identity")).commit()
        assertEquals("legacy-token", store.identity()!!.token)
        assertNull(store.identity()!!.voiceMaterial)
    }

    private fun prefs() = RuntimeEnvironment.getApplication().getSharedPreferences("cloud-store-test", Context.MODE_PRIVATE).also { it.edit().clear().commit() }
    private fun account() = CloudAccountTransfer("chat-2.tmspk.net", "@fixture:chat-2.tmspk.net", "private-chat-password", "private-myts-session", "Fixture")
    private fun session() = MatrixCloud.Session(account().userId, "private-matrix-token", "DEVICE_FIXTURE")

    @Test fun savedCredentialsAreSealedAndCanBeRestored() {
        val p = prefs(); val box = SecretBox(SoftwareKey()); val store = CloudAccountStore(p) { box }
        store.write(account(), session())
        val stored = p.all.values.joinToString()
        assertFalse(stored.contains("private-")); assertTrue(p.all.values.all { SecretBox.isSealed(it as String) })
        assertEquals(account().password, store.account()!!.password)
        assertEquals(session().token, store.session(account().userId, "DEVICE_FIXTURE")!!.token)
        assertNull(store.session("@other:chat-2.tmspk.net", "DEVICE_FIXTURE"))
    }

    @Test fun encryptionFailureKeepsTheOldAccountAndNeverFallsBackToPlaintext() {
        val p = prefs(); val good = SecretBox(SoftwareKey()); val store = CloudAccountStore(p) { good }
        store.write(account(), session()); val before = p.all.toMap()
        val bad = SecretBox(object : SecretBox.Key {
            override fun encrypt(plain: ByteArray, aad: ByteArray): ByteArray = error("Keystore unavailable")
            override fun decrypt(sealed: ByteArray, aad: ByteArray): ByteArray = error("Keystore unavailable")
        })
        assertThrows(IllegalStateException::class.java) { CloudAccountStore(p) { bad }.write(account(), session()) }
        assertEquals(before, p.all)
    }

    @Test fun unsealedCloudDataIsRejectedAndForgetLeavesOtherPreferencesAlone() {
        val p = prefs(); p.edit().putString("account", account().encode()).putString("device", "DEVICE_FIXTURE").commit()
        val store = CloudAccountStore(p) { SecretBox(SoftwareKey()) }
        assertThrows(CloudFailure::class.java) { store.account() }
        store.forget(); assertFalse(p.contains("account")); assertEquals("DEVICE_FIXTURE", p.getString("device", null))
    }

    @Test fun cloudSecretsUseTheirOwnExcludedPreferencesFile() {
        val context = RuntimeEnvironment.getApplication()
        assertSame(context.getSharedPreferences("cloud-secrets", Context.MODE_PRIVATE), cloudSecretPrefs(context))
        assertNotSame(context.getSharedPreferences("cloud-account", Context.MODE_PRIVATE), cloudSecretPrefs(context))
    }

    @Test fun accountOnlyLoginIsSealedAndClearsPreviousChatCredentials() {
        val p = prefs(); val box = SecretBox(SoftwareKey()); val store = CloudAccountStore(p) { box }
        store.write(account(), session())
        store.writeIdentity(MyTsAccount.Session("private-new-token", "New account"))
        assertNull(store.account())
        assertNull(store.session(account().userId, "DEVICE_FIXTURE"))
        assertEquals("private-new-token", store.identity()!!.token)
        assertEquals("New account", store.identity()!!.username)
        assertFalse(p.all.values.joinToString().contains("private-"))
        store.forget()
        assertNull(store.identity())
    }

    @Test fun syncKeyAndBookmarkPasswordsAreSealedAndStayBoundToTheAccount() {
        val p = prefs(); val box = SecretBox(SoftwareKey()); val store = CloudAccountStore(p) { box }
        val identity = MyTsAccount.Session("private-myts-session", "Fixture", ByteArray(32) { it.toByte() })
        store.writeIdentity(identity)
        val items = listOf(MyTsBookmarks.Bookmark("fixture-id", "Fixture", "voice.example.com", "", "private-server-password"))
        store.writeBookmarks(identity, items)
        assertArrayEquals(identity.syncKey, store.identity()!!.syncKey)
        assertEquals("private-server-password", store.bookmarks(identity)!!.single().password)
        assertNull(store.bookmarks(MyTsAccount.Session("another-token", "Another")))
        store.write(account(), session())
        assertArrayEquals(identity.syncKey, store.identity()!!.syncKey)
        assertEquals(1, store.bookmarks(identity)!!.size)
        assertFalse(p.all.values.joinToString().contains("private-"))
        store.writeIdentity(MyTsAccount.Session("new-token", "New"))
        assertNull(store.bookmarks(identity))
    }
}
