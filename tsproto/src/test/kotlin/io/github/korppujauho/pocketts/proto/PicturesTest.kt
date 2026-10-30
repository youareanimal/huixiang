package io.github.korppujauho.pocketts.proto

import io.github.korppujauho.pocketts.proto.Pictures.ChannelPicture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PicturesTest {
    @Test
    fun teamSpeakFileNotes() {
        val image = Pictures.teamSpeakFile("""{"msg_type":"ts.file.myts","file_name":"Screenshot 2026.png","size":12345}""")
        assertEquals(Pictures.TeamSpeakFile("Screenshot 2026.png", true), image)
        assertEquals("TeamSpeak images aren't supported in PocketTS", image?.text)
        assertEquals(false, Pictures.teamSpeakFile("""{"msg_type":"ts.file.myts","file_name":"notes.zip"}""")?.isImage)
        assertEquals(true, Pictures.teamSpeakFile("""{"msg_type":"ts.file.myts","body":"cat.JPG"}""")?.isImage)
        // Ordinary messages, even ones that look a little like it
        for (text in listOf("hello", """{"a":1}""", """{"msg_type":"other"}""", """{"msg_type":"ts.file" broken""", """say {"ts.file"}""")) {
            assertEquals(null, Pictures.teamSpeakFile(text), text)
        }
    }

    @Test
    fun channelPicturesInMessages() {
        val (pics, rest) = Pictures.channelPictures("Look [img]ts3image://cat.jpg?channel=5&path=/webTS[/img] nice")
        assertEquals(listOf(ChannelPicture(5, "/webTS", "cat.jpg")), pics)
        assertEquals("Look  nice", rest)
        assertEquals("/webTS/cat.jpg", pics[0].path)

        // A picture with words, as the app sends them: the picture first, the words under it
        val sent = Pictures.pictureMessage("dog.jpg", 3) + "\nLook at this"
        assertEquals(listOf(ChannelPicture(3, Pictures.PICTURE_FOLDER, "dog.jpg")) to "Look at this", Pictures.channelPictures(sent))

        // The TeamSpeak app's own form, with the folder at the root and an escaped name
        assertEquals(listOf(ChannelPicture(2, "/", "my pic.png")), Pictures.channelPictures("[IMG]ts3image://my%20pic.png?channel=2&path=/[/IMG]").first)

        // Nothing that leaves the channel's files
        assertEquals(emptyList(), Pictures.channelPictures("[img]ts3image://x.png?channel=2&path=/../secret[/img]").first)
        assertEquals(emptyList(), Pictures.channelPictures("[img]ts3image://x.png?channel=0&path=/[/img]").first)
        assertNull(Pictures.channelPicture("https://example.com/a.png"))
    }

    @Test
    fun types() {
        assertEquals(Pictures.Type.PNG, Pictures.type(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0)))
        assertEquals(Pictures.Type.JPG, Pictures.type(byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0)))
        assertEquals(Pictures.Type.GIF, Pictures.type("GIF89a....".toByteArray()))
        assertNull(Pictures.type("<svg xmlns=\"http://www.w3.org/2000/svg\"></svg>".toByteArray()))
        assertEquals(Pictures.Type.SVG, Pictures.type("<?xml version=\"1.0\"?><svg viewBox=\"0 0 1 1\"></svg>".toByteArray(), svgAllowed = true))
    }

    @Test
    fun badges() {
        val m = ServerModel()
        m.apply(Commands.parse("notifyservergrouplist sgid=6 name=Admin iconid=300 sortid=10|sgid=8 name=Guest iconid=0 sortid=20|sgid=9 name=VIP iconid=-1294967296 sortid=5"))
        m.apply(Commands.parse("notifychannelgrouplist cgid=5 name=Channel\\sAdmin iconid=100"))
        m.apply(Commands.parse("notifycliententerview ctid=1 clid=1 client_nickname=A client_servergroups=6,8,9 client_channel_group_id=5 client_icon_id=2000"))
        assertEquals(listOf(100L, 3000000000L, 300L, 2000L), m.badges(m.clients[1]!!))
    }
}
