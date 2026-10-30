package io.github.korppujauho.pocketts

import android.content.Context
import io.github.korppujauho.pocketts.proto.Identity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * What an update finds saved by an older version gets encrypted and moved to the secrets file, and nothing is lost;
 * a copy to a new phone brings everything but the secrets.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SavedSecretsTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private fun prefs(name: String) = context.getSharedPreferences(name, Context.MODE_PRIVATE)
    private val original = Secrets.box

    @Before
    fun softwareKey() {
        Secrets.box = SecretBox(SoftwareKey())
    }

    @After
    fun keystoreKey() {
        Secrets.box = original
    }

    /** Every secret is sealed in the secrets file, and none is left where an earlier version kept it. */
    private fun assertAllInSecrets() {
        for (name in listOf("identity", "serverPassword", "turnPassword", "favorites")) {
            val stored = prefs("secrets").getString(name, null)
            assertTrue(name, stored != null && SecretBox.isSealed(stored))
        }
        assertFalse(prefs("settings").contains("identity"))
        assertFalse(prefs("settings").contains("serverPassword"))
        assertFalse(prefs("voice").contains("turnPassword"))
        assertFalse(prefs("favorites").getString("list", null)!!.contains("password"))
    }

    @Test
    fun oldPlainValuesAreKeptAndEncrypted() {
        val id = Identity.generate(securityLevel = 0)
        prefs("settings").edit().putString("identity", id.export()).putString("serverPassword", "server pw").commit()
        prefs("voice").edit().putString("turnPassword", "relay pw").commit()
        prefs("favorites").edit()
            .putString("list", """[{"address":"ts.example.com","name":"Friends","password":"fav pw","auto":true},{"address":"b.example.com"}]""")
            .commit()

        assertEquals(id.uid, Settings(context).identity().uid)
        assertEquals("server pw", Settings(context).serverPassword)
        assertEquals("relay pw", VoiceSettings.load(context).turnPassword)
        val favs = Favorites.load(context)
        assertEquals(listOf("fav pw", ""), favs.map { it.password })
        assertTrue(favs[0].auto)

        // Now saved encrypted with the other secrets, and read the same again
        assertAllInSecrets()
        assertEquals(id.uid, Settings(context).identity().uid)
        assertEquals("server pw", Settings(context).serverPassword)
        assertEquals("relay pw", VoiceSettings.load(context).turnPassword)
        assertEquals(listOf("fav pw", ""), Favorites.load(context).map { it.password })
        assertEquals("Friends", Favorites.load(context)[0].name)
    }

    @Test
    fun valuesSealedWhereTheyUsedToBeAreMoved() {
        // As the first encrypting version saved them: sealed, but next to the other settings
        val box = Secrets.box
        val id = Identity.generate(securityLevel = 0)
        prefs("settings").edit().putString("identity", box.seal(id.export(), "settings/identity"))
            .putString("serverPassword", box.seal("server pw", "settings/serverPassword")).commit()
        prefs("voice").edit().putString("turnPassword", box.seal("relay pw", "voice/turnPassword")).commit()
        val sealedFav = box.seal("fav pw", "favorites/password")
        prefs("favorites").edit().putString("list", """[{"address":"ts.example.com","password":"$sealedFav"}]""").commit()

        assertEquals(id.uid, Settings(context).identity().uid)
        assertEquals("server pw", Settings(context).serverPassword)
        assertEquals("relay pw", VoiceSettings.load(context).turnPassword)
        assertEquals("fav pw", Favorites.load(context)[0].password)
        assertAllInSecrets()
        assertEquals("fav pw", Favorites.load(context)[0].password)
        assertEquals(id.uid, Settings(context).identity().uid)
    }

    @Test
    fun newValuesAreSavedEncrypted() {
        Settings(context).serverPassword = "typed pw"
        VoiceSettings(turnPassword = "relay pw").save(context)
        Favorites.save(context, listOf(Favorite("ts.example.com", password = "fav pw"), Favorite("b.example.com", "B")))
        assertNotNull(Settings(context).importIdentity("not an identity"))
        val id = Identity.generate(securityLevel = 0)
        assertNull(Settings(context).importIdentity("[Identity]\nid=Main\nidentity=\"${id.export()}\"\n"))

        assertAllInSecrets()
        assertEquals("typed pw", Settings(context).serverPassword)
        assertEquals("relay pw", VoiceSettings.load(context).turnPassword)
        assertEquals(listOf("fav pw", ""), Favorites.load(context).map { it.password })
        assertEquals(id.uid, Settings(context).identity().uid)

        // No passwords left: nothing kept for them
        Favorites.save(context, Favorites.load(context).map { it.copy(password = "") })
        assertFalse(prefs("secrets").contains("favorites"))
    }

    @Test
    fun onANewPhoneTheRestComesAlong() {
        Settings(context).nickname = "Jamie"
        Settings(context).serverPassword = "server pw"
        val old = Settings(context).identity()
        VoiceSettings(turnUser = "relay", turnPassword = "relay pw").save(context)
        Favorites.save(context, listOf(Favorite("ts.example.com", "Friends", password = "fav pw")))

        // Android's copy to a new phone leaves out the secrets file, and the new phone has its own key
        prefs("secrets").edit().clear().commit()
        Secrets.box = SecretBox(SoftwareKey())

        assertEquals("Jamie", Settings(context).nickname)
        assertEquals("", Settings(context).serverPassword)
        assertEquals("relay", VoiceSettings.load(context).turnUser)
        assertEquals("", VoiceSettings.load(context).turnPassword)
        assertEquals(listOf(Favorite("ts.example.com", "Friends")), Favorites.load(context))
        assertNull(Settings(context).identityProblem())
        assertNotEquals(old.uid, Settings(context).identity().uid)
    }

    @Test
    fun unreadableIdentityIsKeptNotReplaced() {
        Settings(context).identity()
        Settings(context).serverPassword = "server pw"
        val saved = prefs("secrets").getString("identity", null)
        Secrets.box = SecretBox(SoftwareKey()) // as if the key were gone

        assertThrows(Settings.IdentityUnreadable::class.java) { Settings(context).identity() }
        assertEquals(saved, prefs("secrets").getString("identity", null))
        assertNotNull(Settings(context).identityProblem())
        assertEquals("", Settings(context).serverPassword)

        // Settings › New identity: a new one at the next connect
        Settings(context).forgetIdentity()
        assertNull(Settings(context).identityProblem())
        val fresh = Settings(context).identity()
        assertTrue(SecretBox.isSealed(prefs("secrets").getString("identity", null)!!))
        assertEquals(fresh.uid, Settings(context).identity().uid)
    }
}
