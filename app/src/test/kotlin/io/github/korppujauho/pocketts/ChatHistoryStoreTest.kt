package io.github.korppujauho.pocketts

import io.github.korppujauho.pocketts.proto.Chat
import io.github.korppujauho.pocketts.proto.Chat.Key
import io.github.korppujauho.pocketts.ui.lineTime
import java.io.File
import java.time.ZoneOffset
import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** The chat history on the phone: encrypted per server, read back, and quietly started over when it can't be opened. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ChatHistoryStoreTest {
    private val dir: File get() = File(RuntimeEnvironment.getApplication().noBackupFilesDir, "chat")
    private val store get() = ChatHistoryStore(dir)
    private val original = Secrets.box
    private val now = System.currentTimeMillis()
    private val day = 86_400_000L

    private val chat = mapOf(
        Key.Channel(1) to listOf(
            Chat.Entry(5, now - 2 * day, 4, "Alex", "alex=", "see you tomorrow"),
            Chat.Entry(6, now - day, event = listOf(Chat.Part("Sam", bold = true), Chat.Part(" connected"))),
        ),
        Key.Private("alex=") to listOf(Chat.Entry(7, now - 1000, 1, "Me", "me=", "a secret", outgoing = true)),
    )

    @Before
    fun softwareKey() {
        Secrets.box = SecretBox(SoftwareKey())
        dir.deleteRecursively()
    }

    @After
    fun keystoreKey() {
        Secrets.box = original
    }

    private fun files() = dir.listFiles().orEmpty().toList()

    @Test
    fun savedEncryptedAndReadBack() {
        store.save("ts.example.com", chat, now)
        val file = files().single()
        val stored = file.readText()
        assertTrue(SecretBox.isSealed(stored))
        for (plain in listOf("secret", "Alex", "alex=", "example")) assertFalse(plain, plain in stored)
        assertFalse("example" in file.name) // the address isn't in the name either

        val back = store.load("ts.example.com", now)
        assertEquals(listOf("see you tomorrow", ""), back[Key.Channel(1)]!!.map { it.text })
        assertEquals(chat[Key.Channel(1)]!![1].event, back[Key.Channel(1)]!![1].event)
        assertEquals("a secret", back[Key.Private("alex=")]!!.single().text)
        assertTrue(back[Key.Private("alex=")]!!.single().outgoing)
        assertTrue(store.load("other.example.com", now).isEmpty()) // each server its own
    }

    @Test
    fun trimmedWhenSavedAndLoaded() {
        val many = mapOf(Key.Channel(1) to (0 until 350).map { Chat.Entry(0, now - 91 * day + (it + 1) * (91 * day / 350), text = "m$it") })
        store.save("ts.example.com", many, now)
        val back = store.load("ts.example.com", now)[Key.Channel(1)]!!
        assertTrue(back.size <= 300)
        assertTrue(back.all { it.time >= now - 90 * day })
        assertEquals("m349", back.last().text)
        // Later, past the 90 days: nothing is left
        assertTrue(store.load("ts.example.com", now + 200 * day).isEmpty())
    }

    @Test
    fun unreadableIsDroppedQuietly() {
        store.save("ts.example.com", chat, now)
        Secrets.box = SecretBox(SoftwareKey()) // as if the key were gone
        assertTrue(store.load("ts.example.com", now).isEmpty())
        assertTrue(files().isEmpty())

        // Another server's file under this one's name doesn't open either
        Secrets.box = SecretBox(SoftwareKey())
        store.save("a.example.com", chat, now)
        val a = files().single()
        store.save("b.example.com", chat, now)
        val b = files().single { it != a }
        a.copyTo(b, overwrite = true)
        assertTrue(store.load("b.example.com", now).isEmpty())

        // Nor does one that isn't encrypted
        File(dir, b.name).writeText("""{"version":1,"conversations":[]}""")
        assertTrue(store.load("b.example.com", now).isEmpty())
        assertEquals(listOf(a), files())
        assertEquals(2, store.load("a.example.com", now).size)
    }

    @Test
    fun clearedFromSettings() {
        store.save("a.example.com", chat, now)
        store.save("b.example.com", chat, now)
        assertEquals(2, files().size)
        store.clear()
        assertTrue(files().isEmpty())
        assertTrue(store.load("a.example.com", now).isEmpty())
    }

    @Test
    fun daysShownForLinesFromBefore() {
        val zone = ZoneOffset.UTC
        val t = 1_760_000_000_000L // a Thursday, 08:53 UTC
        fun label(time: Long) = lineTime(time, t, "HH:MM", zone, Locale.UK)
        assertEquals("HH:MM", label(t - 3_600_000))
        assertEquals("HH:MM - Wed", label(t - day))
        assertEquals("HH:MM - Fri", label(t - 6 * day))
        assertEquals("HH:MM - 2 Oct", label(t - 7 * day))
    }
}
