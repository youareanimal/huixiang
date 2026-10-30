package io.github.korppujauho.pocketts.proto

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Adding, changing, reordering and deleting channels: the commands sent, and the tree kept in the server's order when
 * the server tells only about the channel that changed (as a TeamSpeak 6 server was seen doing).
 */
class ChannelEditsTest {
    private fun model(vararg lines: String) = ServerModel().also { m -> lines.forEach { m.apply(Commands.parse(it)) } }

    private fun ServerModel.names(parent: Int = 0): List<String> {
        fun find(nodes: List<ServerModel.Node>): List<ServerModel.Node>? =
            if (parent == 0) nodes else nodes.firstNotNullOfOrNull { n -> if (n.channel.id == parent) n.children else find(n.children) }
        return find(tree()).orEmpty().map { it.channel.name }
    }

    // Default, A, B, C at the top
    private fun start() = model(
        "channellist cid=1 cpid=0 channel_order=0 channel_name=Default|cid=2 cpid=0 channel_order=1 channel_name=A" +
            "|cid=3 cpid=0 channel_order=2 channel_name=B|cid=4 cpid=0 channel_order=3 channel_name=C",
    )

    @Test
    fun aNewChannelInTheMiddlePushesTheNextOneDown() {
        val m = start()
        // The server says only that N comes after A; B's link to A changes without a word
        m.apply(Commands.parse("notifychannelcreated cid=5 cpid=0 channel_name=N channel_order=2"))
        assertEquals(listOf("Default", "A", "N", "B", "C"), m.names())
    }

    @Test
    fun movingToTheTopAndBetween() {
        val m = start()
        m.apply(Commands.parse("notifychanneledited cid=4 reasonid=10 channel_order=0"))
        assertEquals(listOf("C", "Default", "A", "B"), m.names())
        m.apply(Commands.parse("notifychanneledited cid=4 reasonid=10 channel_order=2"))
        assertEquals(listOf("Default", "A", "C", "B"), m.names())
        // An edit that doesn't touch the order leaves it be
        m.apply(Commands.parse("notifychanneledited cid=2 reasonid=10 channel_name=A2"))
        assertEquals(listOf("Default", "A2", "C", "B"), m.names())
    }

    @Test
    fun movingUnderAnotherChannelAndDeletingWithSubChannels() {
        val m = start()
        m.apply(Commands.parse("notifychannelmoved cid=3 cpid=2 order=0 reasonid=1"))
        assertEquals(listOf("Default", "A", "C"), m.names())
        assertEquals(listOf("B"), m.names(2))
        m.apply(Commands.parse("notifychannelcreated cid=6 cpid=3 channel_name=Deep channel_order=0"))
        // Deleting A takes B and Deep with it; C now follows Default
        m.apply(Commands.parse("notifychanneldeleted invokerid=1 cid=6|cid=3|cid=2"))
        assertEquals(listOf("Default", "C"), m.names())
        assertEquals(setOf(1, 4), m.channels.keys)
    }

    @Test
    fun deletingOnlyTheParentStillDropsWhatWasUnderIt() {
        val m = start()
        m.apply(Commands.parse("notifychannelcreated cid=6 cpid=2 channel_name=Sub channel_order=0"))
        m.apply(Commands.parse("notifychanneldeleted cid=2"))
        assertEquals(listOf("Default", "B", "C"), m.names())
        assertTrue(6 !in m.channels)
    }

    @Test
    fun unlimitedUsersAndCodecQuality() {
        val m = model("channellist cid=1 cpid=0 channel_order=0 channel_name=X channel_maxclients=5 channel_codec_quality=7")
        assertEquals(5, m.channels[1]!!.maxClients)
        assertEquals(7, m.channels[1]!!.codecQuality)
        m.apply(Commands.parse("notifychanneledited cid=1 channel_flag_maxclients_unlimited=1"))
        assertEquals(-1, m.channels[1]!!.maxClients)
        m.apply(Commands.parse("notifychanneledited cid=1 channel_maxclients=10 channel_flag_maxclients_unlimited=0"))
        assertEquals(10, m.channels[1]!!.maxClients)
    }

    private fun ChannelEdits.Edit.line() = Commands.build(name, *params.toTypedArray())

    @Test
    fun creating() {
        val s = ChannelEdits.Settings(
            name = " Squad 3 ", description = "For [b]us[/b]", kind = ChannelEdits.Kind.SEMI_PERMANENT, password = true,
            newPassword = "pw", codec = 5, quality = 10, maxClients = 8, parent = 2, after = 7,
        )
        assertEquals(
            "channelcreate channel_name=Squad\\s3 cpid=2 channel_order=7 channel_description=For\\s[b]us[\\/b] channel_codec=5" +
                " channel_codec_quality=10 channel_password=${Commands.escape(Crypto.hashPassword("pw"))} channel_flag_semi_permanent=1 " +
                "channel_flag_maxclients_unlimited=0 channel_maxclients=8",
            ChannelEdits.create(s).line(),
        )
        // A temporary one at the top, unlimited, no password: no flags, no parent
        assertEquals(
            "channelcreate channel_name=T channel_order=0 channel_codec=4 channel_codec_quality=6 channel_flag_maxclients_unlimited=1",
            ChannelEdits.create(ChannelEdits.Settings("T", kind = ChannelEdits.Kind.TEMPORARY)).line(),
        )
    }

    @Test
    fun editingSendsOnlyWhatChanged() {
        val ch = ServerModel.Channel(5, 0, 2, "Room", hasPassword = true, codec = 4, codecQuality = 6, maxClients = -1)
        val same = ChannelEdits.settingsOf(ch)
        assertEquals(emptyList(), ChannelEdits.edit(ch, same))
        // The password left blank stays as it is
        assertEquals(emptyList(), ChannelEdits.edit(ch, same.copy(newPassword = "")))
        assertEquals(
            listOf("channeledit cid=5 channel_name=Hall channel_flag_semi_permanent=1 channel_flag_permanent=0 channel_password="),
            ChannelEdits.edit(ch, same.copy(name = "Hall", kind = ChannelEdits.Kind.SEMI_PERMANENT, password = false)).map { it.line() },
        )
        assertEquals(
            listOf("channeledit cid=5 channel_flag_maxclients_unlimited=0 channel_maxclients=4", "channelmove cid=5 cpid=9 order=12"),
            ChannelEdits.edit(ch, same.copy(maxClients = 4, parent = 9, after = 12)).map { it.line() },
        )
        assertNull(ChannelEdits.edit(ch, same.copy(parent = 9)).firstOrNull { it.name == "channeledit" })
    }

    @Test
    fun aSubtree() {
        val m = start()
        m.apply(Commands.parse("notifychannelcreated cid=6 cpid=2 channel_name=Sub channel_order=0"))
        m.apply(Commands.parse("notifychannelcreated cid=7 cpid=6 channel_name=Deeper channel_order=0"))
        assertEquals(setOf(2, 6, 7), ChannelEdits.subtree(m.channels.values, 2))
    }
}
