package io.github.korppujauho.pocketts.proto

import io.github.korppujauho.pocketts.proto.Chat.Entry
import io.github.korppujauho.pocketts.proto.Chat.Key
import io.github.korppujauho.pocketts.proto.Chat.Part
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChatHistoryTest {
    private val day = 86_400_000L
    private val now = 1_800_000_000_000L

    private fun msg(time: Long, text: String) = Entry(0, time, senderName = "Alex", senderUid = "alex=", text = text)

    @Test
    fun keepsNinetyDaysAndThreeHundredLines() {
        val channel = (0 until 400).map { msg(now - 400 + it, "m$it") }
        val old = listOf(msg(now - 91 * day, "too old"), msg(now - 89 * day, "still here"))
        val kept = ChatHistory.trim(mapOf(Key.Channel(1) to channel, Key.Private("alex=") to old, Key.Channel(2) to listOf(msg(now - 100 * day, "gone"))), now)

        assertEquals(300, kept[Key.Channel(1)]!!.size)
        assertEquals("m100", kept[Key.Channel(1)]!!.first().text) // the newest 300
        assertEquals("m399", kept[Key.Channel(1)]!!.last().text)
        assertEquals(listOf("still here"), kept[Key.Private("alex=")]!!.map { it.text })
        assertNull(kept[Key.Channel(2)]) // nothing left in it
    }

    @Test
    fun theConversationsQuietTheLongestGoFirst() {
        val history = (1..5).associate { Key.Channel(it) to listOf(msg(now - it * 1000L, "in $it")) }
        assertEquals(listOf(Key.Channel(1), Key.Channel(2), Key.Channel(3)), ChatHistory.trim(history, now, conversations = 3).keys.toList())
    }

    @Test
    fun savedAndReadBack() {
        val event = Entry(0, now - 5000, event = listOf(Part("Sam", bold = true), Part(" joined coming from "), Part("Games", bold = true)))
        val history = mapOf(
            Key.Channel(3) to listOf(event, msg(now - 4000, "Hello \"you\"\n[b]ä 😀[/b]")),
            Key.Private("alex+/=") to listOf(msg(now - 3000, "psst"), Entry(0, now - 2000, 7, "Me", "me=", "hi", outgoing = true)),
        )
        val back = ChatHistory.decode(ChatHistory.encode(history))

        assertEquals(history.keys, back.keys)
        assertEquals(history[Key.Channel(3)], back[Key.Channel(3)])
        val pm = back[Key.Private("alex+/=")]!!
        assertEquals(history[Key.Private("alex+/=")]!![0], pm[0])
        // Today's client id isn't kept: it belongs to someone else after a reconnect
        assertEquals(Entry(0, now - 2000, 0, "Me", "me=", "hi", outgoing = true), pm[1])
    }

    @Test
    fun notAHistory() {
        assertFailsWith<Exception> { ChatHistory.decode("not json") }
        assertFailsWith<Exception> { ChatHistory.decode("""{"version":2,"conversations":[]}""") }
        assertFailsWith<Exception> { ChatHistory.decode("[]") }
        // Broken pieces are skipped, the rest is kept
        val partly = ChatHistory.decode(
            """{"version":1,"conversations":[{"channel":0,"lines":[{"time":1,"text":"x"}]},""" +
                """{"private":"a=","lines":[{"text":"no time"},{"time":5,"text":"ok"}]},7]}"""
        )
        assertEquals(mapOf<Key, List<Entry>>(Key.Private("a=") to listOf(Entry(0, 5, text = "ok"))), partly)
    }

    @Test
    fun theSameServer() {
        assertEquals("ts.example.com", ChatHistory.serverKey("TS.Example.com"))
        assertEquals("ts.example.com", ChatHistory.serverKey("ts3server://ts.example.com:9987"))
        assertEquals("ts.example.com:9988", ChatHistory.serverKey(" ts.example.com:9988 "))
        assertEquals("[2001:db8::1]:9988", ChatHistory.serverKey("[2001:db8::1]:9988"))
        assertNull(ChatHistory.serverKey(""))
    }

    @Test
    fun restoredLinesComeFirstAndStayWhenATabCloses() {
        val m = ServerModel()
        val chat = Chat()
        fun feed(s: String) { val c = Commands.parse(s); chat.handle(c, m); m.apply(c) }
        feed("initserver virtualserver_name=Test aclid=1")
        feed("channellist cid=1 cpid=0 channel_order=0 channel_name=Lobby")
        feed("notifycliententerview ctid=1 reasonid=0 clid=1 client_nickname=Me client_type=0 client_unique_identifier=me=")
        feed("notifycliententerview ctid=1 reasonid=0 clid=2 client_nickname=Alex client_type=0 client_unique_identifier=alex=")
        chat.clear()
        chat.restore(mapOf(Key.Channel(1) to listOf(msg(1, "yesterday")), Key.Private("alex=") to listOf(msg(2, "old secret"))))
        assertTrue(chat.privateNames.isEmpty()) // no tab until they write or one is opened

        feed("notifytextmessage targetmode=2 msg=today invokerid=2 invokername=Alex invokeruid=alex=")
        feed("notifytextmessage targetmode=1 msg=new\\ssecret invokerid=2 invokername=Alex invokeruid=alex=")
        assertEquals(listOf("yesterday", "today"), chat.entries(Key.Channel(1)).map { it.text })
        assertEquals(listOf("old secret", "new secret"), chat.entries(Key.Private("alex=")).map { it.text })
        val ids = chat.keys().flatMap { k -> chat.entries(k).map { it.id } }
        assertEquals(ids.size, ids.toSet().size)

        chat.close("alex=")
        assertTrue("alex=" !in chat.privateNames)
        chat.open("alex=", "Alex")
        assertEquals(2, chat.entries(Key.Private("alex=")).size)

        chat.clearLines()
        assertTrue(chat.entries(Key.Channel(1)).isEmpty())
        assertEquals(setOf<Key>(Key.Private("alex=")), chat.keys()) // the open tab stays
    }
}
