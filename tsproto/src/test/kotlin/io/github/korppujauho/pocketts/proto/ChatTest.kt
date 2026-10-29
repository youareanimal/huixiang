package io.github.korppujauho.pocketts.proto

import io.github.korppujauho.pocketts.proto.Chat.Key
import io.github.korppujauho.pocketts.proto.MessageText.Span
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ChatTest {
    private fun setup(): Pair<ServerModel, Chat> {
        val m = ServerModel()
        val chat = Chat()
        fun feed(s: String) { val c = Commands.parse(s); chat.handle(c, m); m.apply(c) }
        feed("initserver virtualserver_name=Test aclid=1")
        feed("channellist cid=1 cpid=0 channel_order=0 channel_name=Lobby|cid=2 cpid=0 channel_order=1 channel_name=[cspacer9]Games|cid=3 cpid=0 channel_order=2 channel_name=Music")
        feed("notifycliententerview ctid=1 reasonid=0 clid=1 client_nickname=Me client_type=0 client_unique_identifier=me=")
        feed("notifycliententerview ctid=2 reasonid=0 clid=2 client_nickname=Alex client_type=0 client_unique_identifier=alex=")
        chat.clear()
        return m to chat
    }

    private fun Chat.lines(cid: Int) = entries(Key.Channel(cid)).map { e ->
        e.event?.joinToString("") { if (it.bold) "<${it.text}>" else it.text } ?: "${e.senderName}: ${e.text}"
    }

    @Test
    fun joinAndLeaveLines() {
        val (m, chat) = setup()
        fun feed(s: String) { val c = Commands.parse(s); chat.handle(c, m); m.apply(c) }
        feed("notifyclientmoved ctid=1 reasonid=0 clid=2")
        feed("notifycliententerview ctid=1 reasonid=0 cfid=0 clid=3 client_nickname=Sam client_type=0")
        feed("notifyclientmoved ctid=3 reasonid=1 clid=3 invokername=Admin")
        feed("notifyclientleftview cfid=1 ctid=0 reasonid=8 reasonmsg=bye clid=2")
        feed("notifycliententerview ctid=1 reasonid=0 clid=4 client_nickname=Bot client_type=1")
        feed("notifyclientmoved ctid=3 reasonid=1 clid=1 invokername=Admin")
        assertEquals(
            listOf(
                "<Alex> joined coming from <Games>",
                "<Sam> connected",
                "<Sam> was moved to <Music> by <Admin>",
                "<Alex> disconnected (bye)",
            ),
            chat.lines(1),
        )
        assertEquals(listOf("You were moved to <Music> by <Admin>"), chat.lines(3))
    }

    @Test
    fun messagesAndPrivateChats() {
        val (m, chat) = setup()
        fun feed(s: String) = Commands.parse(s).let { c -> chat.handle(c, m).also { m.apply(c) } }
        feed("notifytextmessage targetmode=2 msg=Hello invokerid=2 invokername=Alex invokeruid=alex=")
        val pm = feed("notifytextmessage targetmode=1 msg=Hi\\syou invokerid=2 invokername=Alex invokeruid=alex=")
        assertEquals(Key.Private("alex="), pm.message?.first)
        chat.sendingPrivate(2, "alex=")
        feed("notifytextmessage targetmode=1 msg=Hey target=2 invokerid=1 invokername=Me invokeruid=me=")
        feed("notifytextmessage targetmode=3 msg=Server\\swide invokerid=2 invokername=Alex")
        val poke = feed("notifyclientpoke msg=Look invokerid=2 invokername=Alex invokeruid=alex=").poke
        assertEquals(listOf("Alex: Hello"), chat.lines(1))
        assertEquals(listOf("Hi you", "Hey"), chat.entries(Key.Private("alex=")).map { it.text })
        assertTrue(chat.entries(Key.Private("alex=")).last().outgoing)
        assertEquals("Alex", chat.privateNames["alex="])
        assertTrue(chat.keys().none { it !is Key.Channel && it !is Key.Private }) // nothing for the whole server
        assertNotNull(poke)
        assertEquals("Look", poke.text)
    }

    @Test
    fun refusedPrivateSendCannotMisrouteTheNextOutgoingEcho() {
        val (m, chat) = setup()
        chat.sendingPrivate(2, "alex=", "failed")
        chat.cancelPrivateSend("failed")
        chat.sendingPrivate(3, "sam=", "next")
        chat.handle(Commands.parse("notifytextmessage targetmode=1 msg=Hey invokerid=1 invokername=Me invokeruid=me="), m)
        assertEquals("Hey", chat.entries(Key.Private("sam=")).single().text)
        assertTrue(chat.entries(Key.Private("alex=")).isEmpty())
    }

    @Test
    fun explicitEchoTargetConsumesOnlyItsOwnPendingRecipient() {
        val (m, chat) = setup()
        chat.sendingPrivate(2, "alex=", "first")
        chat.sendingPrivate(3, "sam=", "second")
        chat.handle(Commands.parse("notifytextmessage targetmode=1 target=3 msg=Sam invokerid=1 invokername=Me"), m)
        chat.handle(Commands.parse("notifytextmessage targetmode=1 msg=Alex invokerid=1 invokername=Me"), m)
        assertEquals("Sam", chat.entries(Key.Private("sam=")).single().text)
        assertEquals("Alex", chat.entries(Key.Private("alex=")).single().text)
    }

    @Test
    fun messageFormatting() {
        assertEquals(
            listOf(Span("See "), Span("https://example.com/a", link = "https://example.com/a"), Span(".")),
            MessageText.parse("See https://example.com/a."),
        )
        assertEquals(
            listOf(Span("site", link = "https://example.com"), Span(" and "), Span("https://x.org", link = "https://x.org")),
            MessageText.parse("[URL=https://example.com]site[/URL] and [URL]https://x.org[/URL]"),
        )
        assertEquals(
            listOf(Span("a "), Span("bold", bold = true), Span(" "), Span("it", italic = true), Span(" "), Span("u", underline = true), Span(" red")),
            MessageText.parse("a [b]bold[/b] *it* ++u++ [color=red]red[/color]"),
        )
        assertEquals("Hi there", MessageText.plain("[b]Hi[/b] there"))
    }
}
