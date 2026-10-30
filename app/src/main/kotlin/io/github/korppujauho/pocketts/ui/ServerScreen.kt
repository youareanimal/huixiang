package io.github.korppujauho.pocketts.ui

import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.zIndex
import androidx.compose.foundation.layout.ime
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.material.icons.filled.Star
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.korppujauho.pocketts.Session
import io.github.korppujauho.pocketts.proto.Chat
import io.github.korppujauho.pocketts.proto.ServerModel
import io.github.korppujauho.pocketts.R
import androidx.compose.ui.res.stringResource

/** What the server screen can ask for. */
interface ServerActions {
    fun personDetails(person: ServerModel.Client) {}
    fun join(channel: ServerModel.Channel, password: String = "", remember: Boolean = false, quiet: Boolean = false)
    /** The password remembered for a channel on this server, if any. */
    fun savedPassword(channel: ServerModel.Channel): String? = null
    /** The refused password's channel is being asked again. */
    fun passwordAsked() {}
    fun disconnect()
    fun toggleMic()
    fun toggleSound()
    fun openSettings()
    fun send(key: Chat.Key, text: String)
    fun openPrivate(person: ServerModel.Client)
    fun closePrivate(uid: String)
    fun poke(person: ServerModel.Client, text: String)
    /** Admin: into another channel. */
    fun moveClient(person: ServerModel.Client, channelId: Int) {}
    /** Admin: out of their channel, or off the server. */
    fun kick(person: ServerModel.Client, fromServer: Boolean, reason: String) {}
    /** Admin: banned for [seconds] (0: for good). */
    fun ban(person: ServerModel.Client, seconds: Long, reason: String) {}
    /** Admin: a new channel. */
    fun createChannel(settings: io.github.korppujauho.pocketts.proto.ChannelEdits.Settings) {}
    /** Admin: a channel changed. */
    fun editChannel(channel: ServerModel.Channel, settings: io.github.korppujauho.pocketts.proto.ChannelEdits.Settings) {}
    /** Admin: a channel to come after [after] (0 = first) among its siblings. */
    fun reorderChannel(channel: ServerModel.Channel, after: Int) {}
    /** Admin: a channel deleted, with its sub-channels. */
    fun deleteChannel(channel: ServerModel.Channel) {}
    /** A channel's description, which the server sends only when asked. */
    fun channelDescription(channel: ServerModel.Channel) {}
    /** A folder of a channel's files ("/" for the top); [done] gets them, or why not (on another thread). */
    fun listFiles(channel: ServerModel.Channel, path: String, done: (Result<List<io.github.korppujauho.pocketts.proto.FileTransfer.Entry>>) -> Unit) {}
    /** A file from a channel's files saved where the person chose ([target]). */
    fun downloadFile(channel: ServerModel.Channel, path: String, target: android.net.Uri) {}
    fun cancelDownload() {}
    /** The last download's result was seen. */
    fun downloadResultSeen() {}
    /** A picked file ([size] bytes) sent as [path] into a channel's files; [overwrite] replaces one there. */
    fun uploadFile(channel: ServerModel.Channel, path: String, source: android.net.Uri, size: Long, overwrite: Boolean) {}
    /** Changes to a channel's files; [done] gets null when it went, or why not (on another thread). */
    fun deleteFile(channel: ServerModel.Channel, path: String, done: (String?) -> Unit) {}
    fun renameFile(channel: ServerModel.Channel, from: String, to: String, done: (String?) -> Unit) {}
    fun createFolder(channel: ServerModel.Channel, path: String, done: (String?) -> Unit) {}
    fun dismissPoke(id: Long)
    fun setVolume(person: ServerModel.Client, volume: Float)
    fun setPtt(held: Boolean)
    fun sendPictures(uris: List<android.net.Uri>, text: String)
    fun setAway(away: Boolean, message: String)
    /** Another server from the favourites (this one is left). */
    fun switchTo(f: io.github.korppujauho.pocketts.Favorite)
    fun addFavorite(f: io.github.korppujauho.pocketts.Favorite)
    fun watch(person: ServerModel.Client)
    fun stopWatching()
    /** The camera at [quality]: the picture's height, 480, 720 or 1080. */
    fun shareCamera(front: Boolean, quality: Int, sound: Boolean)
    /** Asks Android for the screen, then shares it. */
    fun shareScreen(sound: Boolean)
    fun stopShare()
    fun flipCamera()
    /** Both cameras at once (asked for first if needed). */
    fun shareBothCameras(quality: Int, sound: Boolean) {}
    /** The warning on screen was closed. */
    fun dismissError() {}
    fun setRotateLock(on: Boolean) {}
    fun setWatchMuted(muted: Boolean) {}
    fun setWatchVolume(volume: Float) {}
    /** The stream player as a small window over other apps. */
    fun pictureInPicture() {}
    /** The conversation on screen (null when the chat isn't showing). */
    fun viewing(key: Chat.Key?)
}

/** The two views on a phone: WebTS shows them side by side on a wide screen. */
enum class View { CHANNELS, CHAT }

@Composable
fun ServerScreen(
    state: Session.UiState,
    actions: ServerActions,
    settingsActions: SettingsActions? = null,
    nickname: String = "",
    startView: View = View.CHANNELS,
    startChat: Chat.Key = Chat.Key.Channel(0),
    startSettings: Int? = null,
    startEditing: Boolean = false, // in the channel edit mode (screenshots)
    favorites: List<io.github.korppujauho.pocketts.Favorite> = emptyList(),
    egl: org.webrtc.EglBase? = null,
    canBoth: Boolean = false, // the phone can use both cameras at once
    pip: Boolean = false, // the app is a small picture-in-picture window: the stream player is drawn there instead
    onBack: (() -> Unit)? = null,
    onOpenSettings: ((Int) -> Unit)? = null,
) {
    var shareDialog by remember { mutableStateOf(false) }
    var serversMenu by remember { mutableStateOf(false) }
    var addingFavorite by remember { mutableStateOf<io.github.korppujauho.pocketts.Favorite?>(null) }
    var settings by remember { mutableStateOf(startSettings) }
    var view by rememberSaveable { mutableStateOf(startView) }
    var chatKey by remember { mutableStateOf(startChat) }
    var askPassword by remember { mutableStateOf<ServerModel.Channel?>(null) }
    var rememberAgain by remember { mutableStateOf(false) }
    var person by remember { mutableStateOf<ServerModel.Client?>(null) }
    var pokeTarget by remember { mutableStateOf<ServerModel.Client?>(null) }
    // Admin actions on a person (Settings › UI › Show admin functions)
    var moveTarget by remember { mutableStateOf<ServerModel.Client?>(null) }
    var kickTarget by remember { mutableStateOf<ServerModel.Client?>(null) }
    var banTarget by remember { mutableStateOf<ServerModel.Client?>(null) }
    var serverInfo by remember { mutableStateOf(false) }
    // Editing the channels (Settings › UI › Show admin functions, then holding a channel)
    var editing by rememberSaveable { mutableStateOf(startEditing) }
    var held by remember { mutableStateOf<ServerModel.Channel?>(null) }
    var channelDialog by remember { mutableStateOf<ChannelEditing?>(null) }
    var filesChannel by remember { mutableStateOf<Int?>(null) } // the channel whose files are open
    var infoChannel by remember { mutableStateOf<Int?>(null) } // the channel whose info is open (held in the list)
    var deleteTarget by remember { mutableStateOf<ServerModel.Channel?>(null) }
    val editMode = editing && state.voice.adminTools && state.phase == Session.Phase.CONNECTED && view == View.CHANNELS
    androidx.activity.compose.BackHandler(editMode) { editing = false }
    val items = remember(state.tree) { flattenTree(state.tree) }

    // A private chat whose tab was closed: back to the channel
    if (chatKey is Chat.Key.Private && state.privateTabs.none { it.uid == (chatKey as Chat.Key.Private).uid }) chatKey = Chat.Key.Channel(0)
    val shownKey = if (chatKey is Chat.Key.Channel) Chat.Key.Channel(state.ownChannel) else chatKey
    LaunchedEffect(view, shownKey) { actions.viewing(if (view == View.CHAT) shownKey else null) }

    val display = androidx.compose.ui.platform.LocalView.current.display
    androidx.compose.ui.platform.LocalConfiguration.current // a new screen turn comes as a new configuration
    val screenTurn = if (state.stream.sharing == io.github.korppujauho.pocketts.proto.Streams.Kind.CAMERA) {
        (state.stream.phoneTurn + (display?.rotation ?: 0) * 90) % 360
    } else 0
    val iconAngle = remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
    LaunchedEffect(screenTurn) {
        // The nearest way round to the new angle
        val target = -screenTurn.toFloat()
        val diff = ((target - iconAngle.floatValue) % 360f + 540f) % 360f - 180f
        iconAngle.floatValue += diff
    }
    val iconTurn by androidx.compose.animation.core.animateFloatAsState(iconAngle.floatValue, label = "icons")
    androidx.compose.runtime.CompositionLocalProvider(LocalIconTurn provides iconTurn) {
    // The screen stays on while connected (when chosen), and always while a stream is shared or watched
    val keepOn = state.voice.keepScreenOn || state.stream.sharing != null || state.stream.watching != 0
    val hostView = androidx.compose.ui.platform.LocalView.current
    DisposableEffect(keepOn) {
        hostView.keepScreenOn = keepOn
        onDispose { hostView.keepScreenOn = false }
    }
    // The ring light: sharing the front camera, the screen at full brightness with a white frame lights your face
    var ringLight by rememberSaveable { mutableStateOf(false) }
    val frontCamera = state.stream.sharing == io.github.korppujauho.pocketts.proto.Streams.Kind.CAMERA && state.stream.shareFront && !state.stream.shareBoth
    val lightOn = ringLight && frontCamera
    val window = (androidx.compose.ui.platform.LocalContext.current as? android.app.Activity)?.window
    DisposableEffect(lightOn, window) {
        window?.attributes = window?.attributes?.apply {
            screenBrightness = if (lightOn) 1f else android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        }
        onDispose {
            window?.attributes = window?.attributes?.apply { screenBrightness = android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE }
        }
    }
    Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).imePadding()) {
        if (lightOn) {
            // On top of everything (zIndex), and it takes no taps
            Box(Modifier.zIndex(10f).fillMaxSize().border(22.dp, Color.White, RoundedCornerShape(28.dp)))
        }
        Column(Modifier.fillMaxSize()) {
            AppHeader(onBack = onBack) {
                ExitButton(stringResource(R.string.disconnect)) { actions.disconnect() }
                androidx.compose.foundation.layout.Spacer(Modifier.size(14.dp)) // so the star isn't Disconnect by mistake
                // WebTS's menu: the favourites (one tap switches), and adding the server you're on
                androidx.compose.foundation.layout.Box {
                    HeaderButton(androidx.compose.material.icons.Icons.Filled.Star, stringResource(R.string.servers)) { serversMenu = true }
                    androidx.compose.material3.DropdownMenu(
                        serversMenu, onDismissRequest = { serversMenu = false },
                        shape = RoundedCornerShape(16.dp), containerColor = Pop.top, shadowElevation = 12.dp,
                        border = androidx.compose.foundation.BorderStroke(1.dp, Pop.edge),
                    ) {
                        for (f in favorites) {
                            val here = io.github.korppujauho.pocketts.Favorites.same(f.address, state.address)
                            androidx.compose.material3.DropdownMenuItem(
                                text = { Text(f.label, color = Ts.text) },
                                leadingIcon = {
                                    androidx.compose.material3.Icon(
                                        if (here) androidx.compose.material.icons.Icons.Filled.CheckCircle else androidx.compose.material.icons.Icons.Filled.Star,
                                        contentDescription = if (here) stringResource(R.string.connected) else null, tint = if (here) Ts.green else Ts.muted,
                                    )
                                },
                                enabled = !here,
                                onClick = { serversMenu = false; actions.switchTo(f) },
                            )
                        }
                        if (favorites.none { io.github.korppujauho.pocketts.Favorites.same(it.address, state.address) }) {
                            androidx.compose.material3.DropdownMenuItem(
                                text = { Text(stringResource(R.string.favourite_add), color = Ts.text) },
                                leadingIcon = {
                                    androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Filled.StarBorder, contentDescription = null, tint = Ts.muted)
                                },
                                onClick = {
                                    serversMenu = false
                                    addingFavorite = io.github.korppujauho.pocketts.Favorite(state.address, state.serverName, nickname)
                                },
                            )
                        }                    }
                }
                SettingsGear { if (onOpenSettings != null) onOpenSettings(0) else settings = 0 }
            }
            Column(Modifier.fillMaxSize().padding(15.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val ptt = state.voice.activation == io.github.korppujauho.pocketts.VoiceSettings.Activation.PTT
                // Typing in the chat: the keyboard needs the room, so the buttons and the switch step aside until it closes
                val typing = view == View.CHAT &&
                    androidx.compose.foundation.layout.WindowInsets.ime.getBottom(androidx.compose.ui.platform.LocalDensity.current) > 0
                // Settings › UI › Buttons at the bottom: the buttons and the switch go under the view, closer to the thumb
                val bottom = state.voice.controlsAtBottom
                val controls = @Composable {
                    ControlsBar(
                        micOff = state.inputMuted || !state.micAllowed, soundOff = state.outputMuted,
                        onMic = actions::toggleMic, onSound = actions::toggleSound,
                        ptt = if (ptt) ({ PttButton(state.pttHeld, actions::setPtt) }) else null,
                        sharing = state.stream.sharing != null,
                        onShare = if (state.phase == Session.Phase.CONNECTED) ({ if (state.stream.sharing != null) actions.stopShare() else shareDialog = true }) else null,
                    )
                }
                if (!typing && !bottom) controls()
                if (egl != null) {
                    ShareBar(
                        state.stream, egl, actions::flipCamera, actions::stopShare, actions::setRotateLock, screenTurn = screenTurn,
                        light = if (frontCamera) ringLight else null, onLight = { ringLight = it },
                    )
                }
                if (state.stream.watching != 0 && egl != null && !pip) {
                    val who = state.client(state.stream.watching)?.nickname
                    WatchCard(
                        state.stream, who?.let { stringResource(R.string.stream_title, it) } ?: stringResource(R.string.stream_title_unknown), egl, actions::stopWatching, actions::setWatchMuted, actions::setWatchVolume,
                        actions::pictureInPicture,
                    )
                }
                val unread = state.unread.any { k ->
                    if (k is Chat.Key.Private) state.privateTabs.any { it.uid == k.uid } else k == Chat.Key.Channel(state.ownChannel)
                }
                val switch = @Composable { ViewSwitch(view, unread = unread) { view = it } }
                if (!typing && !bottom) switch()
                // The view, with the round push-to-talk button over its bottom corner (so it stays above the buttons when
                // they're at the bottom too)
                Box(Modifier.fillMaxWidth().weight(1f)) {
                    when (view) {
                        View.CHANNELS -> Panel(Modifier.fillMaxSize(), header = {
                            if (editMode) {
                                ChannelEditHeader(
                                    onAdd = {
                                        // Next to the channel that was held, else at the end
                                        val h = held?.let { state.channel(it.id) }
                                        val after = h?.id ?: items.lastOrNull { it.node.channel.parent == 0 }?.node?.channel?.id ?: 0
                                        channelDialog = ChannelEditing(null, io.github.korppujauho.pocketts.proto.ChannelEdits.Settings("", parent = h?.parent ?: 0, after = after))
                                    },
                                    onDone = { editing = false },
                                )
                                return@Panel
                            }
                            // The server's own icon, else a plain server one (as channels have the note)
                            if (state.serverIcon > 0) TsIcon(state.serverIcon, state.serverKey, 18.dp)
                            else androidx.compose.material3.Icon(
                                androidx.compose.material.icons.Icons.Filled.Dns, contentDescription = null, tint = Ts.accent, modifier = Modifier.size(18.dp),
                            )
                            androidx.compose.foundation.layout.Spacer(Modifier.size(8.dp))
                            ServerName(state.serverName.ifEmpty { stringResource(R.string.connecting_to, state.address) }) { serverInfo = true }
                        }) {
                            if (state.phase == Session.Phase.CONNECTING) {
                                Loading(
                                    when {
                                        state.waitingForOld -> stringResource(R.string.reconnecting_old)
                                        state.reconnecting > 0 -> stringResource(R.string.reconnecting_try, state.reconnecting)
                                        else -> stringResource(R.string.stream_connecting)
                                    }
                                )
                            } else if (editMode) {
                                ChannelEditList(
                                    items, state.serverKey,
                                    onTap = { ch ->
                                        actions.channelDescription(ch)
                                        channelDialog = ChannelEditing(ch.id, io.github.korppujauho.pocketts.proto.ChannelEdits.settingsOf(ch))
                                    },
                                    onReorder = actions::reorderChannel,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            } else {
                                ChannelTree(
                                    items,
                                    TreeContext(state.ownId, state.ownChannel, state.talking, state.serverKey, state.badges, actions::watch) { it.id !in state.cannotTalk },
                                    onChannel = { ch ->
                                        if (ch.id == state.ownChannel) return@ChannelTree
                                        // A spacer is a channel too (some servers' AFK channel), but easily tapped by mistake: only when allowed
                                        if (ch.spacer != null && !state.voice.joinSpacers) return@ChannelTree
                                        if (!ch.hasPassword) actions.join(ch)
                                        // A remembered password joins straight away. Without one, joining is first tried without a
                                        // password (as the TeamSpeak app does, for those allowed to skip it); a refusal asks for it
                                        else actions.savedPassword(ch)?.let { actions.join(ch, it, remember = true) }
                                            ?: actions.join(ch, quiet = true)
                                    },
                                    onPerson = { person = it; actions.personDetails(it) },
                                    modifier = Modifier.fillMaxSize(),
                                    onLongChannel = if (state.phase == Session.Phase.CONNECTED) ({ ch -> actions.channelDescription(ch); infoChannel = ch.id }) else null,
                                )
                            }
                        }
                        View.CHAT -> ChatPanel(
                            state, chatKey,
                            onView = { chatKey = it },
                            onCloseTab = actions::closePrivate,
                            onSend = actions::send,
                            onPicture = actions::sendPictures,
                            onPerson = { person = it; actions.personDetails(it) },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    if (ptt && state.voice.floatingPtt && state.phase == Session.Phase.CONNECTED) {
                        // In the chat, above the message box
                        FloatingPtt(
                            state.pttHeld, actions::setPtt,
                            Modifier.align(Alignment.BottomEnd).padding(end = 1.dp, bottom = if (view == View.CHAT) 81.dp else 9.dp),
                        )
                    }
                }
                if (!typing && bottom) {
                    switch()
                    controls()
                }
            }
        }
        PokeCards(state.pokes, actions::dismissPoke, Modifier.align(Alignment.TopCenter).padding(top = 56.dp))
        (state.stream.message ?: state.error.takeIf { state.phase == Session.Phase.CONNECTED })?.let {
            WarningBanner(it, actions::dismissError, Modifier.align(Alignment.TopCenter).padding(top = 52.dp))
        }
    }
    }
    settings?.let { tab ->
        if (settingsActions != null) SettingsDialog(
            state.voice, nickname, settingsActions, onDismiss = { settings = null }, startTab = tab,
            self = SelfInfo(state.client(state.ownId), state.serverKey, state.avatarStatus),
        )
    }

    // A password just refused: asked again, with remembering as it was (so a new one replaces a mistyped one)
    LaunchedEffect(state.retryPassword) {
        val id = state.retryPassword
        if (id == 0) return@LaunchedEffect
        actions.passwordAsked()
        val ch = state.channel(id) ?: return@LaunchedEffect
        rememberAgain = state.retryRemember
        askPassword = ch
    }
    askPassword?.let { ch ->
        var pw by remember { mutableStateOf("") }
        var keep by remember { mutableStateOf(rememberAgain) }
        TsDialog(
            onDismiss = { askPassword = null }, title = ch.displayName, icon = androidx.compose.material.icons.Icons.Rounded.Lock,
            subtitle = stringResource(R.string.channel_password_subtitle),
            buttons = {
                PillButton(stringResource(R.string.cancel)) { askPassword = null }
                PillButton(stringResource(R.string.join), PillKind.PRIMARY, enabled = pw.isNotEmpty()) {
                    actions.join(ch, pw, remember = keep)
                    askPassword = null
                    rememberAgain = false
                }
            },
        ) {
            TsField(pw, { pw = it }, stringResource(R.string.channel_password), password = true)
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { keep = !keep },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                androidx.compose.runtime.CompositionLocalProvider(androidx.compose.material3.LocalMinimumInteractiveComponentSize provides 0.dp) {
                    androidx.compose.material3.Checkbox(
                        keep, { keep = it },
                        colors = androidx.compose.material3.CheckboxDefaults.colors(checkedColor = Ts.accent, uncheckedColor = Ts.muted),
                    )
                }
                Text(stringResource(R.string.remember_password), color = Ts.text, fontSize = 14.sp, modifier = Modifier.padding(start = 10.dp, top = 6.dp, bottom = 6.dp))
            }
        }
    }
    person?.let { selected ->
        // The sheet follows avatar/status changes, and never acts on a reused client ID.
        val p = state.client(selected.id)?.takeIf { it.uid == selected.uid } ?: return@let
        PersonSheet(
            p, state.serverKey, isSelf = p.id == state.ownId, volume = state.volumes[p.uid] ?: 1f,
            onVolume = { actions.setVolume(p, it) },
            onPoke = { person = null; pokeTarget = p },
            onChat = {
                person = null
                actions.openPrivate(p)
                chatKey = Chat.Key.Private(p.uid)
                view = View.CHAT
            },
            onDismiss = { person = null },
            away = state.away, awayMessage = state.awayMessage,
            onAway = { a, m -> actions.setAway(a, m) },
            admin = state.voice.adminTools,
            onMove = { person = null; moveTarget = p },
            onKick = { person = null; kickTarget = p },
            onBan = { person = null; banTarget = p },
            channelName = state.channel(p.channel)?.displayName.orEmpty(),
            serverGroups = p.serverGroups.map { state.serverGroupNames[it] ?: "ID $it" },
            channelGroup = state.channelGroupNames[p.channelGroup].orEmpty().ifBlank { if (p.channelGroup > 0) "ID ${p.channelGroup}" else "" },
        )
    }
    moveTarget?.let { p ->
        MoveDialog(
            p, state.tree, spacers = state.voice.joinSpacers,
            onMove = { actions.moveClient(p, it); moveTarget = null }, onDismiss = { moveTarget = null },
        )
    }
    kickTarget?.let { p ->
        KickDialog(p, onKick = { server, reason -> actions.kick(p, server, reason); kickTarget = null }, onDismiss = { kickTarget = null })
    }
    banTarget?.let { p ->
        BanDialog(p, onBan = { seconds, reason -> actions.ban(p, seconds, reason); banTarget = null }, onDismiss = { banTarget = null })
    }
    addingFavorite?.let { f ->
        FavoriteDialog(f, onSave = { actions.addFavorite(it); addingFavorite = null }, onDelete = null) { addingFavorite = null }
    }
    pokeTarget?.let { p ->
        PokeDialog(p, onSend = { actions.poke(p, it); pokeTarget = null }, onDismiss = { pokeTarget = null })
    }
    if (serverInfo) ServerInfoDialog(state) { serverInfo = false }
    filesChannel?.let { id ->
        // Gone (deleted, or the connection ended): closed
        val ch = state.channel(id)?.takeIf { state.phase == Session.Phase.CONNECTED } ?: return@let
        FileBrowser(
            ch, state.download, state.downloadResult,
            list = { path, done -> actions.listFiles(ch, path, done) },
            onDownload = { path, target -> actions.downloadFile(ch, path, target) },
            onCancelDownload = actions::cancelDownload,
            onResultSeen = actions::downloadResultSeen,
            onUpload = { path, source, size, overwrite -> actions.uploadFile(ch, path, source, size, overwrite) },
            onChange = { change, done ->
                when (change) {
                    is FileChange.Delete -> actions.deleteFile(ch, change.path, done)
                    is FileChange.Rename -> actions.renameFile(ch, change.from, change.to, done)
                    is FileChange.NewFolder -> actions.createFolder(ch, change.path, done)
                }
            },
            onDismiss = { filesChannel = null },
        )
    }
    infoChannel?.let { id ->
        // Gone (deleted, or the connection ended): nothing to show
        val item = items.firstOrNull { it.node.channel.id == id } ?: return@let
        val ch = item.node.channel
        val admin = state.voice.adminTools && state.phase == Session.Phase.CONNECTED
        ChannelInfoDialog(
            ch, people = item.node.clients.size, here = ch.id == state.ownChannel,
            // Joining as a tap does it (a spacer too, from here: holding it was on purpose)
            onJoin = if (ch.id == state.ownChannel) null else ({
                infoChannel = null
                if (!ch.hasPassword) actions.join(ch)
                else actions.savedPassword(ch)?.let { actions.join(ch, it, remember = true) } ?: actions.join(ch, quiet = true)
            }),
            onEdit = if (admin) ({ infoChannel = null; channelDialog = ChannelEditing(ch.id, io.github.korppujauho.pocketts.proto.ChannelEdits.settingsOf(ch)) }) else null,
            onArrange = if (admin) ({ infoChannel = null; held = ch; editing = true }) else null,
            onDismiss = { infoChannel = null },
            onFiles = { infoChannel = null; filesChannel = ch.id },
        )
    }
    channelDialog?.let { d ->
        val ch = d.id?.let { state.channel(it) }
        if (d.id == null || ch != null) androidx.compose.runtime.key(d) {
            ChannelDialog(
                ch, d.start, state.tree, description = ch?.description.orEmpty(),
                onSave = { s ->
                    // Put under another parent than where it was: last there, as the location window showed it
                    fun lastIn(parent: Int) =
                        flattenTree(state.tree).lastOrNull { it.node.channel.parent == parent && it.node.channel.id != ch?.id }?.node?.channel?.id ?: 0
                    if (ch != null) actions.editChannel(ch, if (s.parent != ch.parent) s.copy(after = lastIn(s.parent)) else s)
                    else actions.createChannel(if (s.parent == d.start.parent) s else s.copy(after = lastIn(s.parent)))
                    channelDialog = null
                },
                onDelete = ch?.let { c -> { channelDialog = null; deleteTarget = c } },
                onDismiss = { channelDialog = null },
            )
        }
    }
    deleteTarget?.let { ch ->
        val under = io.github.korppujauho.pocketts.proto.ChannelEdits.subtree(items.map { it.node.channel }, ch.id)
        DeleteChannelDialog(
            ch, subChannels = under.size - 1, people = items.filter { it.node.channel.id in under }.sumOf { it.node.clients.size },
            onDelete = { actions.deleteChannel(ch); deleteTarget = null },
            onDismiss = { deleteTarget = null },
        )
    }
    if (shareDialog) {
        ShareDialog(
            onCamera = { front, quality, sound -> shareDialog = false; actions.shareCamera(front, quality, sound) },
            onBoth = { quality, sound -> shareDialog = false; actions.shareBothCameras(quality, sound) },
            sound = state.voice.cameraSound,
            canBoth = canBoth,
            screenSound = state.voice.screenSound,
            screenSoundBlocked = state.stream.phoneSoundBlocked,
            onScreen = { sound -> shareDialog = false; actions.shareScreen(sound) },
            onDismiss = { shareDialog = false },
        )
    }

}

/** "Channels" and "Chat" on a phone. */
@Composable
private fun ViewSwitch(view: View, unread: Boolean, onView: (View) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Ts.card)
            .border(1.dp, Ts.hairline, RoundedCornerShape(10.dp)).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        for ((v, label) in listOf(View.CHANNELS to stringResource(R.string.view_channels), View.CHAT to stringResource(R.string.chat))) {
            val active = v == view
            Row(
                Modifier.weight(1f).clip(RoundedCornerShape(7.dp)).background(if (active) Color(0x12FFFFFF) else Color.Transparent)
                    .clickable { onView(v) }.padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(label, color = if (active) Ts.bright else Color(0xFF94A3B8), fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                if (v == View.CHAT && unread && !active) {
                    Box(Modifier.padding(start = 6.dp).size(8.dp).clip(CircleShape).background(Ts.red))
                }
            }
        }
    }
}

@Composable
fun ErrorLine(text: String) {
    Text(text, color = Color(0xFFF87171), fontSize = 14.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
}

/** The channel settings window: a channel's own ([id]), or a new one (null), starting as [start]. */
private data class ChannelEditing(val id: Int?, val start: io.github.korppujauho.pocketts.proto.ChannelEdits.Settings)
