package io.github.korppujauho.pocketts

import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Column
import io.github.korppujauho.pocketts.ui.BannerPicture
import androidx.compose.runtime.Composable
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.roborazziSystemPropertyOutputDirectory
import io.github.korppujauho.pocketts.proto.Chat
import io.github.korppujauho.pocketts.proto.Commands
import io.github.korppujauho.pocketts.proto.ServerModel
import io.github.korppujauho.pocketts.ui.ConnectActions
import io.github.korppujauho.pocketts.ui.ConnectScreen
import io.github.korppujauho.pocketts.ui.EntryScreen
import io.github.korppujauho.pocketts.ui.CloudAccountHome
import io.github.korppujauho.pocketts.ui.PocketTsTheme
import io.github.korppujauho.pocketts.ui.ServerActions
import io.github.korppujauho.pocketts.ui.ServerScreen
import io.github.korppujauho.pocketts.ui.View
import io.github.korppujauho.pocketts.ui.SettingsActions
import io.github.korppujauho.pocketts.ui.SettingsContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Pictures of the screens with example data, for checking the look without a phone
 * (scripts/dev.sh screenshots → docs/screenshots/). The size is a common phone's: 411 × 891 dp.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xxhdpi", sdk = [36])
class ScreenshotTest {
    init {
        io.github.korppujauho.pocketts.ui.chatNow = { Example.T0 + 600_000 } // the example lines are "today"
    }

    @OptIn(com.github.takahirom.roborazzi.ExperimentalRoborazziApi::class)
    private fun shot(name: String, content: @Composable () -> Unit) =
        captureRoboImage("${roborazziSystemPropertyOutputDirectory()}/$name.png") { PocketTsTheme { content() } }

    @Test
    @Config(qualifiers = "zh-rCN-w360dp-h800dp-xhdpi", sdk = [36])
    fun huiXiangEntryChinese() = shot("huixiang-entry-zh") {
        EntryScreen(onLogin = {}, onGuest = {})
    }

    @Test
    fun connect() = shot("connect") {
        ConnectScreen(
            "Jamie", "ts.example.com", "", listOf(
                Favorite("ts.example.com", "Friends", auto = true),
                Favorite("voice.example.org:9988"),
                Favorite("192.0.2.10:9987", "Test server"),
            ), null, Example.noConnect, settingsActions = Example.noSettings,
        )
    }

    @Test
    @Config(qualifiers = "zh-rCN-w411dp-h891dp-xxhdpi", sdk = [36])
    fun shengQiaoConnectChinese() = shot("shengqiao-connect-zh") {
        ConnectScreen("示例用户", "", "", emptyList(), null, Example.noConnect, settingsActions = Example.noSettings,
            accountUsername = "示例用户")
    }

    @Test
    @Config(qualifiers = "zh-rCN-w411dp-h891dp-xxhdpi", sdk = [36])
    fun shengQiaoCloudAccountEmptyChinese() {
        shot("shengqiao-cloud-empty-zh") {
            CloudAccountHome(CloudAccountController.State(), tab = 0, onTab = {}, onImport = {},
                onLogin = { _, _, _ -> }, onOpenLogin = {}, onForget = {}, onRoom = {}, onContact = {})
        }
    }

    @Test
    @Config(qualifiers = "zh-rCN-w411dp-h891dp-xxhdpi", sdk = [36])
    fun shengQiaoAccountLoggedInChinese() = shot("shengqiao-account-logged-in-zh") {
        Column(Modifier.fillMaxSize()) {
            CloudAccountHome(CloudAccountController.State(hasAccount = true, username = "示例用户",
                localServers = listOf(Favorite("ts.example.com", "我的本机收藏")), bookmarksLoaded = true,
                bookmarks = listOf(io.github.korppujauho.pocketts.proto.MyTsBookmarks.Bookmark(
                    "fixture-id", "我的账号书签", "voice.example.com:9988", "示例用户", ""))),
                tab = 0, onTab = {}, onImport = {}, onLogin = { _, _, _ -> },
                onOpenLogin = {}, onForget = {}, onRoom = {}, onContact = {})
        }
    }

    @Test
    @Config(qualifiers = "zh-rCN-w411dp-h891dp-xxhdpi", sdk = [36])
    fun shengQiaoBookmarkKeyFailureChinese() = shot("shengqiao-bookmark-key-failure-zh") {
        Column(Modifier.fillMaxSize()) {
            CloudAccountHome(CloudAccountController.State(hasAccount = true, username = "示例用户",
                bookmarkError = "BOOKMARK_KEY_AUTH"), tab = 0, onTab = {}, onImport = {},
                onLogin = { _, _, _ -> }, onOpenLogin = {}, onForget = {}, onRoom = {}, onContact = {})
        }
    }

    @Test
    fun server() = shot("server") { ServerScreen(Example.state(), Example.noActions) }

    @Test
    fun chat() = shot("chat") { ServerScreen(Example.chatState(), Example.noActions, startView = View.CHAT) }

    /** Settings › UI › Buttons at the bottom, with push-to-talk's round button kept above them. */
    @Test
    fun buttonsAtBottom() = shot("buttons-at-bottom") {
        ServerScreen(
            Example.state().copy(voice = VoiceSettings(controlsAtBottom = true, activation = VoiceSettings.Activation.PTT), micAllowed = true),
            Example.noActions,
        )
    }

    @Test
    fun chatButtonsAtBottom() = shot("chat-buttons-at-bottom") {
        ServerScreen(Example.chatState().copy(voice = VoiceSettings(controlsAtBottom = true)), Example.noActions, startView = View.CHAT)
    }

    /** Settings › UI › Show admin functions, after holding a channel: edit mode. */
    @Test
    fun channelEditing() = shot("channel-editing") {
        ServerScreen(Example.state().copy(voice = VoiceSettings(adminTools = true)), Example.noActions, startEditing = true)
    }

    @Test
    fun channelNew() = popup("popup-channel-new") {
        io.github.korppujauho.pocketts.ui.ChannelDialog(
            null, io.github.korppujauho.pocketts.proto.ChannelEdits.Settings("Squad 3", password = true, newPassword = "pw", parent = 0),
            Example.state().tree, "", onSave = {}, onDelete = null, onDismiss = {},
        )
    }

    @Test
    fun channelEdit() = popup("popup-channel-edit") {
        val s = Example.state()
        val ch = s.tree.first { it.channel.name == "Gaming" }.channel.copy(description = "Games every evening.", maxClients = 10)
        io.github.korppujauho.pocketts.ui.ChannelDialog(
            ch, io.github.korppujauho.pocketts.proto.ChannelEdits.settingsOf(ch), s.tree, ch.description, onSave = {}, onDelete = {}, onDismiss = {},
        )
    }

    /** Choosing where a channel goes: Gaming picked, the new channel shown dashed at the end of it. */
    @Test
    fun channelLocation() = popup("popup-channel-location") {
        val tree = Example.state().tree
        io.github.korppujauho.pocketts.ui.LocationDialog(tree, tree.first { it.channel.name == "Gaming" }.channel.id, null, "Squad 3", {}, {})
    }

    /** A channel's files: folders first, a picture being saved at the bottom. */
    @Test
    fun files() = popup("popup-files") {
        val ch = Example.state().tree.first { it.channel.name == "Gaming" }.channel
        val files = sampleFiles()
        io.github.korppujauho.pocketts.ui.FileBrowser(
            ch, Session.FileDownload("boss-fight.mp4", 19_000_000, 48_234_496), null,
            list = { _, done -> done(Result.success(files)) },
            onDownload = { _, _ -> }, onCancelDownload = {}, onResultSeen = {}, onDismiss = {},
        )
    }

    private fun sampleFiles(): List<io.github.korppujauho.pocketts.proto.FileTransfer.Entry> {
        val day = Example.T0
        return listOf(
            io.github.korppujauho.pocketts.proto.FileTransfer.Entry("Screenshots", folder = true, size = 0, time = day - 86_400_000L * 3),
            io.github.korppujauho.pocketts.proto.FileTransfer.Entry("PocketTS", folder = true, size = 0, time = day - 3_600_000),
            io.github.korppujauho.pocketts.proto.FileTransfer.Entry("raid-plan.pdf", folder = false, size = 245_760, time = day - 86_400_000L * 10),
            io.github.korppujauho.pocketts.proto.FileTransfer.Entry("boss-fight.mp4", folder = false, size = 48_234_496, time = day - 86_400_000L),
            io.github.korppujauho.pocketts.proto.FileTransfer.Entry("map.png", folder = false, size = 1_621_351, time = day - 600_000),
            io.github.korppujauho.pocketts.proto.FileTransfer.Entry("theme.ogg", folder = false, size = 3_145_728, time = day - 86_400_000L * 30),
        )
    }

    /** Deleting a folder from a channel's files: asked first, and its contents go too. */
    @Test
    fun filesDelete() = popup("popup-files-delete") {
        val ch = Example.state().tree.first { it.channel.name == "Gaming" }.channel
        val files = sampleFiles()
        io.github.korppujauho.pocketts.ui.FileBrowser(
            ch, null, null,
            list = { _, done -> done(Result.success(files)) },
            onDownload = { _, _ -> }, onCancelDownload = {}, onResultSeen = {}, onDismiss = {},
            startAsk = io.github.korppujauho.pocketts.ui.FileAsk.Delete(files.first()),
        )
    }

    /** A file from the phone on its way into a channel's files. */
    @Test
    fun filesUpload() = popup("popup-files-upload") {
        val ch = Example.state().tree.first { it.channel.name == "Gaming" }.channel
        val files = sampleFiles()
        io.github.korppujauho.pocketts.ui.FileBrowser(
            ch, Session.FileDownload("holiday.jpg", 2_400_000, 3_600_000, upload = true), null,
            list = { _, done -> done(Result.success(files)) },
            onDownload = { _, _ -> }, onCancelDownload = {}, onResultSeen = {}, onDismiss = {},
        )
    }

    /** A password channel's files without its password remembered. */
    @Test
    fun filesPassword() = popup("popup-files-password") {
        val ch = Example.state().tree.first { it.channel.name == "Gaming" }.channel
        val why = androidx.compose.ui.res.stringResource(io.github.korppujauho.pocketts.R.string.files_needs_password)
        io.github.korppujauho.pocketts.ui.FileBrowser(
            ch, null, null,
            list = { _, done -> done(Result.failure(IllegalStateException(why))) },
            onDownload = { _, _ -> }, onCancelDownload = {}, onResultSeen = {}, onDismiss = {},
        )
    }

    /** A TeamSpeak link opened while on another server: asked first, with its privilege key left unticked. */
    @Test
    fun link() = popup("popup-link") {
        val link = io.github.korppujauho.pocketts.proto.JoinLink(
            "voice.example.org:9988", channel = "Games/Raid", password = "x", token = "KEY",
        )
        io.github.korppujauho.pocketts.ui.LinkDialog(link, "Jamie", leaving = "Friends", onConnect = {}, onDismiss = {})
    }

    /** Holding a channel: its info, with the admin buttons (the admin functions on). */
    @Test
    fun channelInfo() = popup("popup-channel-info") {
        val node = Example.state().tree.first { it.channel.name == "Gaming" }
        val ch = node.channel.copy(
            topic = "Tonight: co-op", maxClients = 10, hasPassword = true, codecQuality = 8,
            description = "[b]Games every evening.[/b]\nAsk Lee for the password. Rules: https://example.com/rules",
        )
        io.github.korppujauho.pocketts.ui.ChannelInfoDialog(ch, people = 3, here = false, onJoin = {}, onEdit = {}, onArrange = {}, onDismiss = {}, onFiles = {})
    }

    @Test
    fun channelDelete() = popup("popup-channel-delete") {
        val ch = Example.state().tree.first { it.channel.name == "Gaming" }.channel
        io.github.korppujauho.pocketts.ui.DeleteChannelDialog(ch, subChannels = 2, people = 3, onDelete = {}, onDismiss = {})
    }

    @Test
    fun poked() = shot("poke") {
        ServerScreen(Example.state().copy(pokes = listOf(Chat.Poke(1, Example.T0 + 300_000, 1, "Lee", "", "Come to Gaming!"))), Example.noActions)
    }

    @Test
    fun pushToTalk() = shot("push-to-talk") {
        val s = Example.state()
        ServerScreen(
            s.copy(voice = VoiceSettings(activation = VoiceSettings.Activation.PTT), micAllowed = true, pttHeld = true, talking = s.talking + 3),
            Example.noActions,
        )
    }

    @get:Rule
    val compose = androidx.compose.ui.test.junit4.createComposeRule()

    // Popups open in windows of their own: the whole screen is captured
    @OptIn(com.github.takahirom.roborazzi.ExperimentalRoborazziApi::class)
    private fun popup(name: String, content: @Composable () -> Unit) {
        compose.setContent { PocketTsTheme { content() } }
        compose.waitForIdle()
        com.github.takahirom.roborazzi.captureScreenRoboImage("${roborazziSystemPropertyOutputDirectory()}/$name.png")
    }

    @Test
    fun favouritePopup() = popup("popup-favourite") {
        io.github.korppujauho.pocketts.ui.FavoriteDialog(Favorite("ts.example.com", "Friends", "Jamie"), {}, onDelete = {}) {}
    }

    @Test
    fun personPopup() = popup("popup-person") {
        val s = Example.state()
        io.github.korppujauho.pocketts.ui.PersonSheet(s.client(2)!!, s.serverKey, isSelf = false, volume = 1f, onVolume = {}, onPoke = {}, onChat = {}, onDismiss = {})
    }

    /** The app icon as launchers cut it (rounded square, circle), its themed one-colour version, and the notification icon. */
    @Test
    @Config(qualifiers = "w411dp-h891dp-mdpi", sdk = [36])
    fun appIcon() = shot("app-icon") {
        Row(
            Modifier.fillMaxSize().background(Color(0xFF6B7280)),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconTile(RoundedCornerShape(24), Color(0xFF24182F), R.drawable.ic_launcher_foreground)
            IconTile(CircleShape, Color(0xFF24182F), R.drawable.ic_launcher_foreground)
            IconTile(CircleShape, Color(0xFFD7E3FF), R.drawable.ic_launcher_monochrome, tint = Color(0xFF1B3A7A))
            Image(painterResource(R.drawable.ic_notification), null, Modifier.size(32.dp), colorFilter = ColorFilter.tint(Color.White))
        }
    }

    /** Channel banners in their rows (as the channel list measures them): a wide one, a tall one, and a spacer's. */
    @Test
    fun channelBanners() = shot("channel-banners") {
        Column(
            Modifier.fillMaxSize().background(io.github.korppujauho.pocketts.ui.Ts.panel).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for ((name, picture, align) in listOf(
                Triple("Wide banner", stripes(500, 60), null),
                Triple("Tall banner", stripes(120, 200), null),
                Triple("AFK", stripes(400, 48), ServerModel.Spacer.Align.CENTER),
            )) {
                Box(Modifier.fillMaxWidth().height(androidx.compose.foundation.layout.IntrinsicSize.Min).background(io.github.korppujauho.pocketts.ui.Ts.card)) {
                    BannerPicture(picture, align)
                    androidx.compose.material3.Text(name, color = Color.White, modifier = Modifier.padding(horizontal = 12.dp, vertical = 14.dp))
                }
            }
        }
    }

    // Coloured diagonal stripes, so stretching or a gap would show
    private fun stripes(w: Int, h: Int): androidx.compose.ui.graphics.painter.Painter {
        val bitmap = androidx.compose.ui.graphics.ImageBitmap(w, h)
        val canvas = androidx.compose.ui.graphics.Canvas(bitmap)
        val paint = androidx.compose.ui.graphics.Paint()
        for (i in -h until w step 20) {
            paint.color = if ((i / 20) % 2 == 0) Color(0xFF3B82F6) else Color(0xFF22C55E)
            paint.strokeWidth = 10f
            canvas.drawLine(androidx.compose.ui.geometry.Offset(i.toFloat(), h.toFloat()), androidx.compose.ui.geometry.Offset((i + h).toFloat(), 0f), paint)
        }
        return androidx.compose.ui.graphics.painter.BitmapPainter(bitmap)
    }

    // A launcher shows the middle 72 of an adaptive icon's 108 dp: drawn at 108 * 4/3 of the tile and cut to it
    @Composable
    private fun IconTile(shape: androidx.compose.ui.graphics.Shape, background: Color, icon: Int, tint: Color? = null) {
        Box(Modifier.size(84.dp).clip(shape).background(background), contentAlignment = Alignment.Center) {
            Image(
                androidDrawablePainter(icon, 126.dp), null, Modifier.requiredSize(126.dp),
                colorFilter = tint?.let { ColorFilter.tint(it) },
            )
        }
    }

    @Test
    fun settings() = shot("settings-capture") {
        SettingsContent(VoiceSettings(), "Jamie", Example.noSettings, {}, startTab = 2, modifier = Modifier.fillMaxSize())
    }

    @Test
    fun settingsUi() = shot("settings-ui") {
        SettingsContent(VoiceSettings(), "Jamie", Example.noSettings, {}, startTab = 3, modifier = Modifier.fillMaxSize())
    }

    @Test
    fun serverConnecting() = shot("server-connecting") {
        ServerScreen(Session.UiState(phase = Session.Phase.CONNECTING, address = "ts.example.com", reconnecting = 2), Example.noActions)
    }
}

/** Render the actual Android launcher layer, including inset bitmaps, in Compose screenshot previews. */
@Composable
internal fun androidDrawablePainter(icon: Int, edge: androidx.compose.ui.unit.Dp): androidx.compose.ui.graphics.painter.Painter {
    val context = androidx.compose.ui.platform.LocalContext.current
    val pixels = with(androidx.compose.ui.platform.LocalDensity.current) { edge.roundToPx() }.coerceAtLeast(1)
    val bitmap = androidx.compose.runtime.remember(context, icon, pixels) {
        android.graphics.Bitmap.createBitmap(pixels, pixels, android.graphics.Bitmap.Config.ARGB_8888).also { target ->
            context.getDrawable(icon)!!.apply {
                setBounds(0, 0, pixels, pixels)
                draw(android.graphics.Canvas(target))
            }
        }.asImageBitmap()
    }
    return androidx.compose.ui.graphics.painter.BitmapPainter(bitmap)
}

/** An example server like the one in WebTS's screenshots. */
object Example {
    val noActions = object : ServerActions {
        override fun join(channel: ServerModel.Channel, password: String, remember: Boolean, quiet: Boolean) {}
        override fun disconnect() {}
        override fun toggleMic() {}
        override fun toggleSound() {}
        override fun openSettings() {}
        override fun send(key: Chat.Key, text: String) {}
        override fun openPrivate(person: ServerModel.Client) {}
        override fun closePrivate(uid: String) {}
        override fun poke(person: ServerModel.Client, text: String) {}
        override fun dismissPoke(id: Long) {}
        override fun setVolume(person: ServerModel.Client, volume: Float) {}
        override fun viewing(key: Chat.Key?) {}
        override fun setPtt(held: Boolean) {}
        override fun sendPictures(uris: List<android.net.Uri>, text: String) {}
        override fun setAway(away: Boolean, message: String) {}
        override fun switchTo(f: Favorite) {}
        override fun addFavorite(f: Favorite) {}
        override fun watch(person: ServerModel.Client) {}
        override fun stopWatching() {}
        override fun shareCamera(front: Boolean, quality: Int, sound: Boolean) {}
        override fun shareScreen(sound: Boolean) {}
        override fun stopShare() {}
        override fun flipCamera() {}
    }

    val noConnect = object : ConnectActions {
        override fun connect(address: String, nickname: String, password: String, remember: Boolean, favorite: Boolean) {}
        override fun connectFavorite(f: Favorite) {}
        override fun saveFavorite(old: Favorite?, new: Favorite) {}
        override fun removeFavorite(f: Favorite) {}
        override fun toggleAuto(f: Favorite) {}
    }

    val noSettings = object : SettingsActions {
        override fun save(voice: VoiceSettings, nickname: String) {}
        override fun micLevel() = 0.022f to true
        override fun micTest(on: Boolean) {}
        override fun playTestSound() {}
    }

    fun model(): ServerModel {
        val m = ServerModel()
        fun cmd(s: String) = m.apply(Commands.parse(s))
        cmd("initserver virtualserver_name=TeamSpeak\\s6\\sServer aclid=3")
        cmd(
            "channellist cid=1 cpid=0 channel_order=0 channel_name=Default\\sChannel channel_flag_default=1" +
                "|cid=9 cpid=0 channel_order=1 channel_name=[cspacer]Lobbies channel_flag_permanent=1" +
                "|cid=2 cpid=0 channel_order=9 channel_name=Gaming" +
                "|cid=6 cpid=2 channel_order=0 channel_name=Squad\\s1" +
                "|cid=7 cpid=2 channel_order=6 channel_name=Squad\\s2 channel_flag_password=1" +
                "|cid=10 cpid=0 channel_order=2 channel_name=[*spacer1]-=" +
                "|cid=3 cpid=0 channel_order=10 channel_name=Music channel_needed_talk_power=50" +
                "|cid=4 cpid=0 channel_order=3 channel_name=Study\\sroom" +
                "|cid=11 cpid=0 channel_order=4 channel_name=[spacer2]---" +
                "|cid=5 cpid=0 channel_order=11 channel_name=AFK channel_needed_talk_power=99",
        )
        cmd("notifyservergrouplist sgid=6 name=Admin iconid=100 sortid=1|sgid=8 name=Guest iconid=0 sortid=9")
        fun person(id: Int, cid: Int, nick: String, extra: String = "") =
            cmd("notifycliententerview ctid=$cid clid=$id client_nickname=$nick client_type=0 client_talk_power=75 $extra")
        person(1, 1, "Lee")
        person(2, 1, "Noa", "client_input_muted=1")
        person(3, 1, "Jamie")
        person(4, 1, "Riley", "client_is_streaming=1")
        person(5, 2, "Alex", "client_servergroups=6 client_is_streaming=1")
        person(8, 6, "Sam", "client_output_muted=1")
        person(9, 6, "Robin")
        person(10, 3, "Kai", "client_talk_power=0 client_servergroups=6")
        person(11, 4, "Mia", "client_servergroups=6")
        person(12, 5, "Quinn", "client_away=1 client_talk_power=0 client_servergroups=6")
        return m
    }

    const val T0 = 1_760_000_000_000L // a fixed time for the example lines

    fun chatState(): Session.UiState {
        val m = model()
        val chat = Chat()
        var t = T0
        chat.now = { t.also { t += 45_000 } }
        // A line from yesterday, kept in the chat history
        chat.restore(mapOf(Chat.Key.Channel(1) to listOf(Chat.Entry(0, T0 - 86_400_000L, senderName = "Noa", text = "Good game yesterday, everyone!"))))
        fun feed(s: String) { val c = Commands.parse(s); chat.handle(c, m); m.apply(c) }
        fun say(id: Int, name: String, msg: String) =
            feed(Commands.build("notifytextmessage", "targetmode" to 2, "msg" to msg, "invokerid" to id, "invokername" to name))
        say(4, "Riley", "Anyone up for a match later tonight?")
        say(3, "Jamie", "Count me in, after 8 pm")
        feed("notifyclientmoved ctid=1 reasonid=0 clid=5")
        say(4, "Riley", "Nice! I will set up a **lobby** at 8: https://example.com/lobby")
        feed(Commands.build("notifyclientleftview", "cfid" to 1, "ctid" to 0, "reasonid" to 8, "reasonmsg" to "see you", "clid" to 2))
        feed("notifytextmessage targetmode=1 msg=Psst invokerid=9 invokername=Robin invokeruid=robin=")
        val s = state()
        return s.copy(
            tree = m.tree(),
            chat = chat.keys().associateWith { chat.entries(it) },
            privateTabs = chat.privateNames.map { (uid, name) -> Session.PrivateTab(uid, name) },
            unread = setOf(Chat.Key.Private("robin=")),
        )
    }

    fun state(): Session.UiState {
        val m = model()
        return Session.UiState(
            phase = Session.Phase.CONNECTED,
            address = "ts.example.com",
            serverName = m.serverName,
            tree = m.tree(),
            ownId = 3,
            ownChannel = 1,
            talking = setOf(1, 4), // only your own channel's talking can be seen
            cannotTalk = m.clients.values.filter { !m.canTalk(it) }.map { it.id }.toSet(),
            micAllowed = true,
            badges = m.clients.values.associate { it.id to m.badges(it) },
        )
    }
}
