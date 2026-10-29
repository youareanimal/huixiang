package io.github.korppujauho.pocketts

import android.Manifest
import android.os.Build
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.LaunchedEffect
import androidx.activity.compose.BackHandler
import io.github.korppujauho.pocketts.ui.*
import io.github.korppujauho.pocketts.proto.Chat
import io.github.korppujauho.pocketts.proto.JoinLink
import io.github.korppujauho.pocketts.proto.ServerModel
import io.github.korppujauho.pocketts.ui.ConnectActions
import io.github.korppujauho.pocketts.ui.ConnectScreen
import io.github.korppujauho.pocketts.ui.CloudAccountDialog
import io.github.korppujauho.pocketts.ui.EntryScreen
import io.github.korppujauho.pocketts.ui.PocketTsTheme
import io.github.korppujauho.pocketts.ui.ServerActions
import io.github.korppujauho.pocketts.ui.ServerScreen
import io.github.korppujauho.pocketts.ui.SettingsActions

class MainActivity : ComponentActivity() {
    val accountModel by lazy { androidx.lifecycle.ViewModelProvider(this)[HubAccountModel::class.java] }
    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
    private val askMic = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        Session.micPermission(this, granted)
    }

    private var cameraWanted: (() -> Unit)? = null
    private val askCamera = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val start = cameraWanted ?: return@registerForActivityResult
        cameraWanted = null
        if (granted) start()
    }

    /** The camera: asked for first if needed. */
    fun shareCamera(front: Boolean, quality: Int, sound: Boolean) = withCamera { Session.shareCamera(front, quality, sound) }

    fun shareBothCameras(quality: Int, sound: Boolean) = withCamera { Session.shareBothCameras(quality, sound) }

    private fun withCamera(start: () -> Unit) {
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            start()
        } else {
            cameraWanted = start
            askCamera.launch(Manifest.permission.CAMERA)
        }
    }

    // ====== The watched stream as a small window (picture-in-picture), like a video app ======

    private fun pipParams(): android.app.PictureInPictureParams {
        // Android allows shapes between 1:2.39 and 2.39:1
        val w = io.github.korppujauho.pocketts.ui.StreamSize.width
        val h = io.github.korppujauho.pocketts.ui.StreamSize.height
        val ratio = (w * 1000L / h).coerceIn(419L, 2390L).toInt()
        val b = android.app.PictureInPictureParams.Builder().setAspectRatio(android.util.Rational(ratio, 1000))
        if (Build.VERSION.SDK_INT >= 31) b.setAutoEnterEnabled(Session.state.value.stream.watching != 0).setSeamlessResizeEnabled(false)
        return b.build()
    }

    /** Leaving the app while watching makes the stream a small window (Android 12 and later do it by themselves). */
    fun updatePip() {
        try { setPictureInPictureParams(pipParams()) } catch (e: Exception) { AppLog.log("Small window: $e") }
    }

    fun enterPip() {
        try { enterPictureInPictureMode(pipParams()) } catch (e: Exception) { AppLog.log("Small window: $e") }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT < 31 && Session.state.value.stream.watching != 0) enterPip()
    }

    private val askScreen = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        if (result.resultCode == RESULT_OK && data != null) {
            val m = resources.displayMetrics
            Session.shareScreen(data, m.widthPixels, m.heightPixels)
        }
    }

    /** The screen ([sound]: with what the phone plays): Android asks the person first. */
    fun shareScreen(sound: Boolean) {
        Session.setScreenSound(sound) // remembered now: the app may be made again while Android asks
        val pm = getSystemService(android.media.projection.MediaProjectionManager::class.java)
        askScreen.launch(pm.createScreenCaptureIntent())
    }

    /** Asks for the microphone if it isn't allowed yet; true when it already is. */
    fun ensureMic(): Boolean {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) return true
        askMic.launch(Manifest.permission.RECORD_AUDIO)
        return false
    }

    // The app's own language, on Androids older than 13 (newer ones do it themselves)
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLanguage.wrap(newBase))
    }

    // Volume buttons as push-to-talk (Settings > General), while the app is on screen
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val s = Session.state.value
        val volumeKey = event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || event.keyCode == KeyEvent.KEYCODE_VOLUME_UP
        if (volumeKey && s.phase == Session.Phase.CONNECTED && s.voice.volumeKeysPtt &&
            s.voice.activation == VoiceSettings.Activation.PTT
        ) {
            when (event.action) {
                KeyEvent.ACTION_DOWN -> if (event.repeatCount == 0) Session.setPtt(true)
                KeyEvent.ACTION_UP -> Session.setPtt(false)
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppLog.start(this)
        // The chat's event lines in the app's language (made in the protocol module, which has no text of its own)
        io.github.korppujauho.pocketts.proto.Chat.words = io.github.korppujauho.pocketts.proto.Chat.Words(
            connected = getString(R.string.event_connected),
            joinedFrom = getString(R.string.event_joined_from),
            youMovedTo = getString(R.string.event_you_moved_to),
            youKickedFrom = getString(R.string.event_you_kicked_from),
            movedHereFrom = getString(R.string.event_moved_here_from),
            kickedFrom = getString(R.string.event_kicked_from),
            movedTo = getString(R.string.event_moved_to),
            kickedFromChannel = getString(R.string.event_kicked_from_channel),
            leftTo = getString(R.string.event_left_to),
            youKickedFromServer = getString(R.string.event_you_kicked_from_server),
            youBanned = getString(R.string.event_you_banned),
            lostConnection = getString(R.string.event_lost_connection),
            kickedFromServer = getString(R.string.event_kicked_from_server),
            banned = getString(R.string.event_banned),
            disconnected = getString(R.string.event_disconnected),
            by = getString(R.string.event_by),
            because = getString(R.string.event_because),
            anotherChannel = getString(R.string.event_another_channel),
        )
        FrameLog.start(this)
        ServerFiles.install(applicationContext)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        current = this
        Settings(this).restoreAccountNickname()
        favorites.value = Favorites.load(this)
        Session.load(this)
        // A TeamSpeak link that opened the app: asked first, and no favourite connects by itself meanwhile
        if (savedInstanceState == null) openLink(intent)
        // A saved server may connect only after account entry or the explicit guest choice.
        TalkOverlay.start(this)
        HeadsetButton.start(this)
        UpdateCheck.start(this)
        accountModel.updater.start()
        io.github.korppujauho.pocketts.ui.StreamSize.onChange = { runOnUiThread { current?.updatePip() } }
        addOnPictureInPictureModeChangedListener { info ->
            inPip.value = info.isInPictureInPictureMode
            Session.pip = info.isInPictureInPictureMode
            // The small window closed (not opened full again): watching ends, as in a video app
            if (!info.isInPictureInPictureMode && lifecycle.currentState == androidx.lifecycle.Lifecycle.State.CREATED) Session.stopWatching()
        }
        setContent { PocketTsTheme { App() } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        openLink(intent)
    }

    /** A ts3server:// or teamspeak:// link: asked about before connecting (a web page can open one). True when it was one. */
    private fun openLink(intent: Intent?): Boolean {
        val data = intent?.dataString ?: intent?.takeIf {
            it.action == Intent.ACTION_SEND && it.type == "text/plain"
        }?.getStringExtra(Intent.EXTRA_TEXT)?.trim() ?: return false
        if (!JoinLink.isLink(data)) return false
        // (not the link itself in the log: it can hold passwords and a privilege key)
        val link = JoinLink.parse(data)
        AppLog.log(if (link != null) "A TeamSpeak link was opened" else "A TeamSpeak link without a usable address was opened")
        if (link == null) android.widget.Toast.makeText(this, R.string.not_an_address, android.widget.Toast.LENGTH_LONG).show()
        else pendingLink.value = link
        return true
    }

    override fun onStart() {
        super.onStart()
        Session.foreground = true
    }

    override fun onStop() {
        Session.foreground = false
        super.onStop()
    }

    override fun onDestroy() {
        if (current === this) current = null
        super.onDestroy()
    }

    companion object {
        var current: MainActivity? = null
    }
}

/** The app is a small picture-in-picture window. */
private val inPip = mutableStateOf(false)

/** A TeamSpeak link waiting for the person's yes or no. */
private val pendingLink = mutableStateOf<JoinLink?>(null)

/** Whether this phone can use both cameras at once (asked once). */
private val canBoth by lazy { MainActivity.current?.let { DualCamera.pair(it) != null } ?: false }

/** The favourites, shared by the screens (they change from both). */
private val favorites = kotlinx.coroutines.flow.MutableStateFlow<List<Favorite>>(emptyList())

@Composable
private fun App() {
    val ctx = LocalContext.current
    val state by Session.state.collectAsState()
    val controller = (ctx as MainActivity).accountModel.controller
    val updater = ctx.accountModel.updater
    val update by updater.state.collectAsState()
    val account by controller.state.collectAsState()
    val favs by favorites.collectAsState()
    val entry = remember(ctx) { ConnectionEntry(ctx) }
    val names = rememberConnectionNames(ctx)
    var guestEntered by rememberSaveable { mutableStateOf(entry.guestEntered) }
    val entered = account.restored && (account.hasAccount || guestEntered)
    var route by rememberSaveable { mutableStateOf(if (state.phase == Session.Phase.IDLE) "HOME" else "VOICE") }
    var tabName by rememberSaveable { mutableStateOf(HubTab.SERVERS.name) }
    val tab = runCatching { HubTab.valueOf(tabName) }.getOrDefault(HubTab.SERVERS)
    var recent by remember(ctx) { mutableStateOf(RecentServers.load(ctx)) }
    var seed by remember { mutableStateOf<Favorite?>(null) }
    var switching by remember { mutableStateOf<Favorite?>(null) }
    var editing by remember { mutableStateOf<Favorite?>(null) }
    var contact by remember { mutableStateOf<io.github.korppujauho.pocketts.proto.MyTsAccount.Contact?>(null) }
    var roomId by rememberSaveable { mutableStateOf<String?>(null) }
    var settingsTab by rememberSaveable { mutableStateOf(0) }
    var settingsOrigin by rememberSaveable { mutableStateOf("HOME") }
    var logout by remember { mutableStateOf(false) }
    var about by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    val pip by inPip
    val importer = androidx.activity.compose.rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(controller::importAccount) }
    fun home() { route = "HOME" }
    fun openSettings(index: Int) { settingsTab = index; settingsOrigin = route; route = "SETTINGS" }
    fun closeSettings() { route = settingsOrigin.takeUnless { it == "SETTINGS" } ?: "HOME" }
    fun join(f: Favorite) {
        if (!entry.canConnect(account)) { home(); return }
        val target = f.copy(nickname = entry.nickname(account, f.nickname.ifBlank { names.first }).orEmpty())
        if (target.nickname.isBlank()) { seed = target; route = "CONNECT"; return }
        if (state.phase != Session.Phase.IDLE) {
            if (Favorites.same(target.address, state.address) && target.nickname == Settings(ctx).nickname) route = "VOICE"
            else switching = target
        } else { route = "VOICE"; connectFavorite(ctx, target) }
    }
    LaunchedEffect(account.restored, account.hasAccount, account.username) {
        if (!account.restored) return@LaunchedEffect
        if (account.hasAccount) {
            if (state.phase != Session.Phase.IDLE &&
                (guestEntered || state.client(state.ownId)?.nickname != account.username)) Session.disconnect()
            guestEntered = false
            entry.clearGuest()
        } else {
            roomId = null
            if (!guestEntered) { seed = null; switching = null; contact = null; home() }
        }
    }
    LaunchedEffect(account.authRevision) {
        if (account.authRevision > 0) { route = "HOME"; tabName = HubTab.SERVERS.name }
    }
    LaunchedEffect(entered, account.restored, state.phase) {
        if (!account.restored) return@LaunchedEffect
        if (!entered) {
            if (state.phase != Session.Phase.IDLE) Session.disconnect()
            return@LaunchedEffect
        }
        if (state.phase != Session.Phase.IDLE) {
            Session.autoConnected = true
        } else if (route == "VOICE") home()
        if (entered && account.restored && !Session.autoConnected) {
            Session.autoConnected = true
            if (pendingLink.value == null) favorites.value.firstOrNull { it.auto }?.let { join(it) }
        }
    }
    LaunchedEffect(state.phase, state.address) {
        if (state.phase == Session.Phase.CONNECTED) recent = RecentServers.record(ctx, Favorite(state.address, state.serverName, Settings(ctx).nickname))
    }
    LaunchedEffect(route, tabName) { Session.setPtt(false); if (route != "VOICE") SessionActions.viewing(null) }
    LaunchedEffect(account.cloudConnected) {
        if (account.cloudConnected) while (true) {
            kotlinx.coroutines.delay(3_000)
            if (ctx.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) controller.refresh()
        }
    }
    LaunchedEffect(account.openRoomId) {
        account.openRoomId?.let { id ->
            if (account.hasAccount && (route == "ROOM" || (route == "HOME" && tab == HubTab.MESSAGES))) {
                roomId = id; route = "ROOM"
            }
            controller.consumeOpenRoom(id)
        }
    }
    BackHandler(route != "HOME" || (entered && tab != HubTab.SERVERS)) {
        if (route == "SETTINGS") closeSettings() else if (route != "HOME") home() else tabName = HubTab.SERVERS.name
    }
    val typing = WindowInsets.ime.getBottom(androidx.compose.ui.platform.LocalDensity.current) > 0
    if (pip && entered) VoiceApp(onBack = ::home, onSettings = ::openSettings)
    else Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).consumeWindowInsets(WindowInsets.safeDrawing)) {
        if (route == "HOME" && entered) AppUpdateBanner(update, { about = true }, updater::dismiss)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                !account.restored -> Loading("带上你的回响…")
                route == "LOGIN" -> HubLogin(account, ::home) { email, password, otp -> controller.loginAccount(email, password, otp) }
                !entered -> EntryScreen(onLogin = { route = "LOGIN" }, onGuest = {
                    if (entry.enterGuest(controller.state.value)) guestEntered = true
                })
                route == "VOICE" -> VoiceApp(onBack = ::home, onSettings = ::openSettings)
                route == "CONNECT" -> Connect(state, favs, account, seed, onBack = ::home, onTarget = ::join)
                route == "AVATAR" -> AccountAvatarScreen(account,
                    SelfInfo(state.client(state.ownId), state.serverKey, state.avatarStatus), ::home,
                    controller::updateAccountAvatar, controller::removeAccountAvatar, { openSettings(0) }, controller::refreshProfile)
                route == "SETTINGS" -> Column(Modifier.fillMaxSize()) {
                    HubTopBar(listOf("个人资料与后台", "声音与播放", "发言与录音", "界面与语言", "身份与高级设置")[settingsTab], onBack = ::closeSettings)
                    androidx.compose.runtime.key(settingsTab) {
                        SettingsContent(state.voice, names.first, AppSettingsActions(ctx), ::closeSettings, startTab = settingsTab,
                            modifier = Modifier.weight(1f).fillMaxWidth(), self = SelfInfo(state.client(state.ownId), state.serverKey, state.avatarStatus), singleSection = true)
                    }
                }
                route == "ROOM" -> Column(Modifier.fillMaxSize().imePadding()) {
                    val room = account.rooms.firstOrNull { it.id == roomId }
                    HubTopBar(room?.let { roomTitle(it, account.username, account.cloudUserId, account.contacts) } ?: "云消息", onBack = ::home)
                    account.cloudError?.let { HubError(chatErrorMessage(it), it, { controller.connectCloud() }) }
                    if (room != null) CloudConversation(room, account.busy || account.sending, account.sent,
                        { controller.history(room.id) }, { controller.send(room.id, it) },
                        ownUserId = account.cloudUserId, lastSentRoomId = account.lastSentRoomId, lastSentText = account.lastSentText,
                        sendError = account.sendError.takeIf { account.sendErrorRoomId == room.id }, onJoin = { controller.acceptRoom(room.id) },
                        onRead = { controller.markRead(room.id, it) }, senderNames = account.contacts.filter { it.matrixId.isNotBlank() }.associate { it.matrixId to it.displayName })
                    else HubEmpty("会话暂不可用", "返回消息列表刷新后再试。", "返回", ::home)
                }
                else -> Column(Modifier.fillMaxSize()) {
                    if (state.error != null && state.phase == Session.Phase.IDLE) HubError("语音连接未完成，账号状态不受影响。", state.error!!) {
                        seed = Favorite(state.address, nickname = names.first); route = "CONNECT"; Session.clearError()
                    }
                    Box(Modifier.weight(1f)) {
                        when (tab) {
                            HubTab.SERVERS -> ServerHub(account, favs, recent,
                                onAdd = { seed = null; route = "CONNECT" }, onJoin = ::join,
                                onRecent = { seed = entry.recentTarget(account, it, favs); route = "CONNECT" }, onEdit = { editing = it },
                                onRefresh = controller::refreshBookmarks, onLogin = { route = "LOGIN" })
                            HubTab.FRIENDS -> FriendsHub(account, { route = "LOGIN" }, controller::refreshContacts, { contact = it })
                            HubTab.MESSAGES -> MessagesHub(account, { route = "LOGIN" }, { controller.connectCloud() }, { roomId = it; route = "ROOM" })
                            HubTab.ME -> MeHub(account, state.voice, { route = "LOGIN" }, { logout = true },
                                ::openSettings, { importer.launch(arrayOf("*/*")) }, { about = true },
                                controller::refreshProfile, { route = "AVATAR" })
                        }
                    }
                }
            }
        }
        if (entered && !typing) {
            if (state.phase != Session.Phase.IDLE && route != "VOICE") VoiceMiniBar(state, { route = "VOICE" }, SessionActions::toggleMic)
            if (route == "HOME" || route == "VOICE") HubNavigation(if (route == "VOICE") HubTab.SERVERS else tab) { tabName = it.name; route = "HOME" }
        }
    }
    switching?.let { f -> AlertDialog(onDismissRequest = { switching = null }, title = { Text("换个服务器聊？") },
        text = { Text("将离开当前服务器并连接 ${f.label}。") },
        confirmButton = { TextButton(onClick = { switching = null; route = "VOICE"; Session.switchTo { connectFavorite(ctx, f) } }) { Text("切换服务器") } },
        dismissButton = { TextButton(onClick = { switching = null }) { Text("取消") } }) }
    editing?.let { f -> FavoriteDialog(f, onSave = { replacement -> saveFavorites(ctx, favs.map { if (it == f) replacement else if (replacement.auto) it.copy(auto = false) else it }); editing = null },
        onDelete = { saveFavorites(ctx, favs - f); editing = null }, onDismiss = { editing = null }) }
    contact?.let { friend -> AlertDialog(onDismissRequest = { contact = null }, title = { Text(friend.displayName) },
        text = { Text(if (friend.matrixId.isBlank()) "这位好友暂时无法发起云聊天。" else
            "按需连接云聊天。已有会话会直接打开；没有会话时，将创建私聊并邀请该好友。") },
        confirmButton = { TextButton(enabled = !account.busy && friend.matrixId.isNotBlank(), onClick = {
            contact = null; tabName = HubTab.MESSAGES.name
            route = "HOME"; controller.openFriendChat(friend)
        }) { Text("发消息") } }, dismissButton = { TextButton(onClick = { contact = null }) { Text("关闭") } }) }
    if (logout) AlertDialog(onDismissRequest = { logout = false }, title = { Text("退出本机账号？") }, text = { Text("将断开当前语音并清除本机账号和云消息凭据，返回登录或访客选择页。本机收藏和语音身份会保留。") },
        confirmButton = { TextButton(onClick = {
            entry.clearGuest(); guestEntered = false; Session.autoConnected = true
            if (state.phase != Session.Phase.IDLE) Session.disconnect()
            if (state.neededLevel > 0) Session.dismissNeededLevel()
            pendingLink.value = null; switching = null; seed = null; contact = null; roomId = null
            controller.forget(); logout = false; tabName = HubTab.SERVERS.name; home()
        }) { Text("退出账号") } },
        dismissButton = { TextButton(onClick = { logout = false }) { Text("取消") } })
    if (about) AppUpdateDialog(update, { about = false }, updater::check, updater::download, updater::cancel,
        { updater.install(ctx) }, updater::automatic)
    notice?.let { message -> AlertDialog(onDismissRequest = { notice = null }, text = { Text(message) }, confirmButton = { TextButton(onClick = { notice = null }) { Text("知道了") } }) }
    pendingLink.value?.takeIf { entered }?.let { link ->
        val nickname = entry.nickname(account, link.nickname.ifBlank { names.first }.ifBlank { "回响访客" }).orEmpty()
        LinkDialog(link, nickname, leaving = state.serverName.ifBlank { state.address }.takeIf { state.phase != Session.Phase.IDLE },
            onConnect = { useKey -> pendingLink.value = null; route = "VOICE"; Session.autoConnected = true
                Session.switchTo { connect(ctx, link.address, nickname, link.password, channel = link.channel, channelPassword = link.channelPassword, privilegeKey = if (useKey) link.token else "") }
            }, onDismiss = { pendingLink.value = null })
    }
    if (entered && state.phase == Session.Phase.IDLE && state.neededLevel > 0) {
        val current = remember { IdentityRaise.level(ctx) }
        if (current != null) RaiseLevelDialog(current, needed = state.neededLevel,
            onReached = {
                entry.nickname(controller.state.value, names.first)?.let { nickname -> route = "VOICE"; Session.connectAgain(nickname) }
            }, onClose = Session::dismissNeededLevel)
    }
}

@Composable
private fun VoiceApp(onBack: () -> Unit, onSettings: ((Int) -> Unit)? = null) {
    val state by Session.state.collectAsState()
    val favs by favorites.collectAsState()
    val ctx = LocalContext.current
    val account by (ctx as MainActivity).accountModel.controller.state.collectAsState()
    val settings = remember { Settings(ctx) }
    val pip by inPip
    androidx.compose.runtime.LaunchedEffect(state.stream.watching) { MainActivity.current?.updatePip() }
    if (state.phase == Session.Phase.IDLE) {
        Loading("正在准备语音连接…")
    } else {
        val egl = Session.streams().egl
        androidx.compose.runtime.CompositionLocalProvider(LocalVoiceAccountProfile provides
            if (account.hasAccount) VoiceAccountProfile(state.ownId, account.username, account.email, account.avatarUrl) else VoiceAccountProfile()) {
        androidx.compose.foundation.layout.Box {
            ServerScreen(
                state, SessionActions, AppSettingsActions(ctx), settings.nickname, favorites = favs, egl = egl,
                canBoth = canBoth, pip = pip,
                onBack = onBack,
                onOpenSettings = onSettings,
            )
            if (pip) io.github.korppujauho.pocketts.ui.PipPlayer(state.stream, egl)
        }
        }
    }
}

/** Connects (the microphone is asked for once; talking starts when it's allowed). */
private fun connect(
    context: Context, address: String, nickname: String, password: String, filePort: Int? = null,
    channel: String = "", channelPassword: String = "", privilegeKey: String = "",
) {
    val account = MainActivity.current?.accountModel?.controller?.state?.value ?: return
    val effectiveNickname = ConnectionEntry(context).nickname(account, nickname)
    if (effectiveNickname == null) {
        android.widget.Toast.makeText(context, "请先登录账号或在欢迎页选择访客进入，并确认连接昵称。", android.widget.Toast.LENGTH_LONG).show()
        return
    }
    Settings(context).let {
        it.nickname = effectiveNickname
        it.address = address
    }
    MainActivity.current?.ensureMic()
    Session.connect(context, address, effectiveNickname, password, filePort, channel, channelPassword, privilegeKey)
}

private fun connectFavorite(context: Context, f: Favorite) =
    connect(context, f.address, f.nickname.ifBlank { Settings(context).nickname }, f.password, f.filePort)

/** Explicit tap on a saved server in the account dialog. */
fun connectSavedServer(context: Context, f: Favorite) = connectFavorite(context, f)

private fun saveFavorites(context: Context, list: List<Favorite>) {
    Favorites.save(context, list)
    favorites.value = Favorites.load(context)
}

@Composable
private fun Connect(state: Session.UiState, favs: List<Favorite>, account: CloudAccountController.State, seed: Favorite? = null, onBack: () -> Unit = {}, onTarget: ((Favorite) -> Unit)? = null) {
    val ctx = LocalContext.current
    val settings = remember { Settings(ctx) }
    val names = rememberConnectionNames(ctx)
    ManualConnectScreen(
        if (account.hasAccount) account.username else seed?.nickname?.ifBlank { names.first } ?: names.first,
        if (account.hasAccount) account.username else "",
        seed?.address ?: "", seed?.let { f -> f.password.ifBlank { if (Favorites.same(settings.address, f.address)) settings.serverPassword else "" } } ?: "", state.error,
        onBack = onBack, signedIn = account.hasAccount, onConnect = { address, nickname, password, remember, favorite ->
                // Review pasted invitations before joining; never persist a whole URL containing secrets.
                if (JoinLink.isLink(address)) {
                    val link = JoinLink.parse(address)
                    if (link == null) android.widget.Toast.makeText(ctx, R.string.not_an_address, android.widget.Toast.LENGTH_LONG).show()
                    else pendingLink.value = link.copy(nickname = link.nickname.ifBlank { nickname })
                    return@ManualConnectScreen
                }
                settings.serverPassword = if (remember) password else ""
                if (favorite && favs.none { Favorites.same(it.address, address) && it.nickname == nickname }) saveFavorites(ctx, favs + Favorite(address, nickname = nickname, password = if (remember) password else ""))
                val target = Favorite(address, nickname = nickname, password = password)
                if (onTarget != null) onTarget(target)
                else if (state.phase == Session.Phase.IDLE) connectFavorite(ctx, target) else Session.switchTo { connectFavorite(ctx, target) }
        },
    )
}

/** The connection form stays mounted behind the account dialog, so observe login and logout immediately. */
@Composable
internal fun rememberConnectionNames(context: Context): Pair<String, String> {
    val settings = remember(context) { Settings(context) }
    val prefs = remember(context) { context.getSharedPreferences("settings", Context.MODE_PRIVATE) }
    var names by remember(context) { mutableStateOf(settings.nickname to settings.accountUsername) }
    androidx.compose.runtime.DisposableEffect(prefs) {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "nickname" || key == "account-username") names = settings.nickname to settings.accountUsername
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        names = settings.nickname to settings.accountUsername
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return names
}

private object SessionActions : ServerActions {
    override fun personDetails(person: ServerModel.Client) = Session.personDetails(person)
    override fun join(channel: ServerModel.Channel, password: String, remember: Boolean, quiet: Boolean) =
        Session.joinChannel(channel.id, password, remember, quiet)
    override fun savedPassword(channel: ServerModel.Channel) = Session.savedChannelPassword(channel.id)
    override fun passwordAsked() = Session.passwordAsked()
    override fun disconnect() = Session.disconnect()
    override fun toggleMic() {
        if (!Session.state.value.micAllowed) {
            if (MainActivity.current?.ensureMic() == true) Session.micPermission(MainActivity.current!!, true)
            return
        }
        Session.toggleMic()
    }
    override fun toggleSound() = Session.toggleSound()
    override fun openSettings() {}
    override fun send(key: Chat.Key, text: String) = Session.send(key, text)
    override fun openPrivate(person: ServerModel.Client) = Session.openPrivate(person)
    override fun closePrivate(uid: String) = Session.closePrivate(uid)
    override fun poke(person: ServerModel.Client, text: String) = Session.poke(person, text)
    override fun moveClient(person: ServerModel.Client, channelId: Int) = Session.moveClient(person, channelId)
    override fun kick(person: ServerModel.Client, fromServer: Boolean, reason: String) = Session.kick(person, fromServer, reason)
    override fun ban(person: ServerModel.Client, seconds: Long, reason: String) = Session.ban(person, seconds, reason)
    override fun createChannel(settings: io.github.korppujauho.pocketts.proto.ChannelEdits.Settings) = Session.createChannel(settings)
    override fun editChannel(channel: ServerModel.Channel, settings: io.github.korppujauho.pocketts.proto.ChannelEdits.Settings) =
        Session.editChannel(channel, settings)
    override fun reorderChannel(channel: ServerModel.Channel, after: Int) = Session.reorderChannel(channel, after)
    override fun deleteChannel(channel: ServerModel.Channel) = Session.deleteChannel(channel)
    override fun channelDescription(channel: ServerModel.Channel) = Session.channelDescription(channel.id)
    override fun listFiles(channel: ServerModel.Channel, path: String, done: (Result<List<io.github.korppujauho.pocketts.proto.FileTransfer.Entry>>) -> Unit) =
        Session.listFiles(channel.id, path, done)
    override fun downloadFile(channel: ServerModel.Channel, path: String, target: android.net.Uri) {
        val ctx = MainActivity.current?.applicationContext ?: return
        Session.downloadFile(ctx, channel.id, path, target)
    }
    override fun cancelDownload() = Session.cancelDownload()
    override fun downloadResultSeen() = Session.downloadResultSeen()
    override fun uploadFile(channel: ServerModel.Channel, path: String, source: android.net.Uri, size: Long, overwrite: Boolean) {
        val ctx = MainActivity.current?.applicationContext ?: return
        Session.uploadFile(ctx, channel.id, path, source, size, overwrite)
    }
    override fun deleteFile(channel: ServerModel.Channel, path: String, done: (String?) -> Unit) = Session.deleteFile(channel.id, path, done)
    override fun renameFile(channel: ServerModel.Channel, from: String, to: String, done: (String?) -> Unit) =
        Session.renameFile(channel.id, from, to, done)
    override fun createFolder(channel: ServerModel.Channel, path: String, done: (String?) -> Unit) = Session.createFolder(channel.id, path, done)
    override fun dismissPoke(id: Long) = Session.dismissPoke(id)
    override fun setVolume(person: ServerModel.Client, volume: Float) = Session.setVolume(person, volume)
    override fun setPtt(held: Boolean) = Session.setPtt(held)
    override fun sendPictures(uris: List<android.net.Uri>, text: String) {
        MainActivity.current?.let { Session.sendPictures(it.applicationContext, uris, text) }
    }
    override fun viewing(key: Chat.Key?) {
        Session.viewing = key
    }
    override fun setAway(away: Boolean, message: String) = Session.setAway(away, message)
    override fun switchTo(f: Favorite) {
        val ctx = MainActivity.current?.applicationContext ?: return
        Session.switchTo { connectFavorite(ctx, f) }
    }
    override fun watch(person: ServerModel.Client) {
        Session.watch(person)
    }
    override fun stopWatching() {
        Session.stopWatching()
    }
    override fun shareCamera(front: Boolean, quality: Int, sound: Boolean) {
        MainActivity.current?.shareCamera(front, quality, sound)
    }
    override fun shareScreen(sound: Boolean) {
        MainActivity.current?.shareScreen(sound)
    }
    override fun stopShare() {
        Session.stopShare()
    }
    override fun flipCamera() {
        Session.flipCamera()
    }
    override fun shareBothCameras(quality: Int, sound: Boolean) {
        MainActivity.current?.shareBothCameras(quality, sound)
    }
    override fun setRotateLock(on: Boolean) = Session.setRotateLock(on)
    override fun setWatchMuted(muted: Boolean) {
        Session.setWatchMuted(muted)
    }
    override fun dismissError() = Session.clearError()
    override fun setWatchVolume(volume: Float) {
        Session.setWatchVolume(volume)
    }
    override fun pictureInPicture() {
        MainActivity.current?.enterPip()
    }
    override fun addFavorite(f: Favorite) {
        val ctx = MainActivity.current?.applicationContext ?: return
        saveFavorites(ctx, favorites.value.map { if (f.auto) it.copy(auto = false) else it } + f)
    }
}

private class AppSettingsActions(private val context: Context) : SettingsActions {
    override fun save(voice: VoiceSettings, nickname: String) {
        // Turned on: Android's own page to allow it, if not allowed yet
        if (voice.overlayPtt && !Session.state.value.voice.overlayPtt && !TalkOverlay.allowed(context)) {
            MainActivity.current?.startActivity(
                android.content.Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:${context.packageName}"))
            )
        }
        Session.updateVoice(voice)
        if (nickname.isNotEmpty() && nickname != Settings(context).nickname) {
            Settings(context).nickname = nickname
            Session.setNickname(nickname)
        }
    }
    override fun language() = AppLanguage.current(context)
    override fun setLanguage(tag: String) {
        MainActivity.current?.let { AppLanguage.set(it, tag) }
    }
    override fun micLevel() = Session.micLevel()
    override fun micTest(on: Boolean) = Session.micTest(on)
    override fun playTestSound() = TestSound.play(Session.state.value.voice)
    override fun setAvatar(uri: android.net.Uri) = Session.setAvatar(context, uri)
    override fun removeAvatar() = Session.removeAvatar()
    override fun exportIdentity(uri: android.net.Uri): String? = try {
        // Into the file the person chose, as TeamSpeak apps save it (so they can open it too); nowhere else
        val s = Settings(context)
        val ini = s.identity().exportIni("PocketTS", s.nickname)
        context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(ini.toByteArray(Charsets.UTF_8)) }
            ?: throw java.io.IOException("no file")
        AppLog.log("Identity saved to a file")
        null
    } catch (e: Settings.IdentityUnreadable) {
        context.getString(R.string.identity_export_unreadable)
    } catch (e: Exception) {
        AppLog.log("Couldn't save the identity: ${e.javaClass.simpleName}")
        context.getString(R.string.file_not_written)
    }
    override fun importIdentity(text: String): String? = Settings(context).importIdentity(text)
    override fun importIdentityFile(uri: android.net.Uri): String? = try {
        val text = context.contentResolver.openInputStream(uri)?.use { input ->
            // An identity file is small: past 64 KB it isn't one
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(8192)
            while (out.size() < 64 * 1024) {
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
            }
            out.toString(Charsets.UTF_8.name())
        }
        if (text == null) context.getString(R.string.file_not_opened) else importIdentity(text)
    } catch (e: Exception) {
        AppLog.log("Couldn't read the identity file: ${e.javaClass.simpleName}")
        context.getString(R.string.file_not_opened)
    }
    override fun identityProblem(): String? = Settings(context).identityProblem()
    override fun forgetIdentity() = Settings(context).forgetIdentity()
    override fun securityLevel(): Int? = IdentityRaise.level(context)
    override fun clearChatHistory() = Session.clearChatHistory(context)
    override fun forgetChannelPasswords() = ChannelPasswords(context).clear()
}
