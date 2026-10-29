package io.github.korppujauho.pocketts.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.korppujauho.pocketts.CloudAccountController
import io.github.korppujauho.pocketts.BuildConfig
import io.github.korppujauho.pocketts.R
import io.github.korppujauho.pocketts.proto.MatrixCloud
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun CloudAccountButton() {
    var open by rememberSaveable { mutableStateOf(false) }
    HeaderButton(Icons.Rounded.ChatBubbleOutline, "账号与服务器") { open = true }
    if (open) CloudAccountDialog { open = false }
}

@Composable
fun CloudAccountDialog(onAuthenticated: () -> Unit = {}, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val controller = remember { CloudAccountController(context) }
    val state by controller.state.collectAsState()
    var roomId by rememberSaveable { mutableStateOf<String?>(null) }
    var tab by rememberSaveable { mutableStateOf(0) }
    var login by remember { mutableStateOf(false) }
    var forget by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(controller::importAccount) }
    DisposableEffect(controller) { onDispose { controller.close() } }
    LaunchedEffect(state.cloudConnected) {
        if (state.cloudConnected) while (true) { delay(10_000); controller.refresh() }
    }
    LaunchedEffect(state.authRevision) { if (state.authRevision > 0) login = false }
    LaunchedEffect(state.openRoomId) {
        state.openRoomId?.let { roomId = it; controller.consumeOpenRoom(it) }
    }
    LaunchedEffect(state.hasAccount) { if (state.hasAccount) onAuthenticated() }
    val room = state.rooms.firstOrNull { it.id == roomId }
    Dialog(onDismissRequest = { if (roomId != null) roomId = null else onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(Ts.page).windowInsetsPadding(WindowInsets.safeDrawing).imePadding()) {
            Row(Modifier.fillMaxWidth().background(Ts.header).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { if (roomId != null) roomId = null else onDismiss() }) { Text(if (roomId != null) "返回" else "关闭", color = Ts.brand) }
                Text(if (room != null) roomTitle(room, state.username, state.cloudUserId, state.contacts) else "账号与服务器", color = Ts.bright,
                    fontWeight = FontWeight.Bold, maxLines = 1, modifier = Modifier.weight(1f))
                if (state.hasAccount) TextButton(enabled = !state.busy, onClick = controller::refreshFriends) { Text("刷新", color = Ts.brand) }
            }
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Ts.brand)
            state.error?.let { CloudError(it) }
            if (tab == 2) state.cloudError?.let { CloudError(it, cloud = true) }
            val visibleNotice = notice ?: state.notice
            visibleNotice?.let { text ->
                Text(text, color = Ts.amber, modifier = Modifier.padding(12.dp))
                TextButton(onClick = { notice = null; controller.dismissNotice() }) { Text("知道了") }
            }
            if (room != null) {
                CloudConversation(room, state.busy || state.sending, state.sent, onHistory = { controller.history(room.id) }, onSend = { controller.send(room.id, it) },
                    ownUserId = state.cloudUserId, lastSentRoomId = state.lastSentRoomId, lastSentText = state.lastSentText,
                    sendError = state.sendError.takeIf { state.sendErrorRoomId == room.id }, onJoin = { controller.acceptRoom(room.id) }, onRead = { controller.markRead(room.id, it) },
                    senderNames = state.contacts.filter { it.matrixId.isNotBlank() }.associate { it.matrixId to it.displayName })
            } else {
                CloudAccountHome(state, tab, onTab = { tab = it },
                    onImport = { importer.launch(arrayOf("*/*")) }, onLogin = controller::loginAccount,
                    onOpenLogin = { login = true }, onForget = { forget = true },
                    onConnectCloud = { controller.connectCloud() },
                    onServer = { io.github.korppujauho.pocketts.connectSavedServer(context, it); onDismiss() },
                    onRoom = { roomId = it }, onContact = { controller.openFriendChat(it) })
            }
        }
        if (forget) AlertDialog(onDismissRequest = { forget = false }, title = { Text("移除本机登录信息？") },
            text = { Text("清除这部手机保存的账号会话和云聊天凭据。本机收藏的服务器会保留。") },
            confirmButton = { TextButton(onClick = { controller.forget(); roomId = null; forget = false }) { Text("移除") } },
            dismissButton = { TextButton(onClick = { forget = false }) { Text("取消") } })
        if (login) MyTsLoginDialog(state.busy, onDismiss = { login = false }, onLogin = controller::loginAccount)
    }
}

@Composable
fun CloudAccountHome(
    state: CloudAccountController.State, tab: Int,
    onTab: (Int) -> Unit, onImport: () -> Unit, onLogin: (String, String, String) -> Unit,
    onOpenLogin: () -> Unit, onForget: () -> Unit,
    onRoom: (String) -> Unit, onContact: (io.github.korppujauho.pocketts.proto.MyTsAccount.Contact) -> Unit,
    onConnectCloud: () -> Unit = {}, onServer: (io.github.korppujauho.pocketts.Favorite) -> Unit = {},
) {
    if (!state.hasAccount) {
        AccountPasswordForm(state.busy, onLogin, onImport)
        return
    }
    Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(state.username.ifBlank { "TeamSpeak 账号" }, color = Ts.bright, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text("TeamSpeak 账号已登录 · " + if (state.cloudConnected) "云聊天已连接" else "云聊天未连接", color = Ts.muted, fontSize = 12.sp)
        Text("${stringResource(R.string.app_name)} ${BuildConfig.VERSION_NAME}", color = Ts.muted, fontSize = 11.sp)
        Row {
            TextButton(enabled = !state.busy, onClick = onOpenLogin) { Text("重新登录", color = Ts.brand) }
            TextButton(enabled = !state.busy, onClick = onImport) { Text("导入迁移", color = Ts.muted) }
            TextButton(enabled = !state.busy, onClick = onForget) { Text("移除", color = Ts.muted) }
        }
    }
    TabRow(selectedTabIndex = tab, containerColor = Ts.header, contentColor = Ts.brand) {
        Tab(selected = tab == 0, onClick = { onTab(0) }, text = { Text("服务器 ${state.bookmarks.size + state.localServers.size}") })
        Tab(selected = tab == 1, onClick = { onTab(1) }, text = { Text("好友 ${state.contacts.size}") })
        Tab(selected = tab == 2, onClick = { onTab(2) }, text = { Text("云聊天") })
    }
    if (tab == 2 && !state.cloudConnected) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("云聊天按需连接，不影响账号登录或连接自己的语音服务器。", color = Ts.text)
            PrimaryButton(if (state.busy) "正在连接…" else "连接云聊天", enabled = !state.busy) { onConnectCloud() }
        }
        return
    }
    if (tab == 1 && state.contactError != null) {
        Text("好友暂不可用（${state.contactError}）。可刷新或重新登录，账号登录状态已保留。", color = Ts.amber, modifier = Modifier.padding(16.dp), fontSize = 13.sp)
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (tab == 0) {
            item { Text("账号服务器书签", color = Ts.bright, modifier = Modifier.padding(8.dp), fontWeight = FontWeight.SemiBold) }
            state.bookmarkError?.let { code -> item {
                val message = when (code) {
                    "BOOKMARK_KEY_REQUIRED" -> "本机保存的旧登录没有书签密钥。请在本版本重新登录一次；若仍失败，将显示具体错误。"
                    "BOOKMARK_KEY_MISSING" -> "账号已登录，但登录接口没有返回书签密钥（BOOKMARK_KEY_MISSING）。不是书签列表为空。"
                    "BOOKMARK_KEY_FORMAT" -> "上次登录的书签密钥格式未被旧版支持。此版本已增加兼容处理，请重新登录以重试。"
                    "BOOKMARK_KEY_AUTH" -> "账号已登录，但书签密钥解锁校验未通过（BOOKMARK_KEY_AUTH）。请保留此错误代码，便于核对账号同步密钥。"
                    "BOOKMARK_KDF_UNAVAILABLE" -> "账号已登录，但手机无法派生书签解密密钥（BOOKMARK_KDF_UNAVAILABLE）。需要检查加密服务兼容性。"
                    "BOOKMARK_CRYPTO_UNAVAILABLE" -> "账号已登录，但手机加密服务无法完成书签解锁（BOOKMARK_CRYPTO_UNAVAILABLE）。需要检查设备兼容性。"
                    "BOOKMARK_CHECKSUM" -> "服务器书签完整性校验失败，已保留上次读取的书签。"
                    "BOOKMARK_SYNC_102" -> "账号会话已过期，请重新登录以刷新服务器书签。"
                    else -> if (code.startsWith("BOOKMARK_KEY_FORMAT_"))
                        "账号已登录，但书签密钥格式暂不支持（$code）。错误码只包含长度和版本信息，请保留用于排查。"
                        else "服务器书签暂时同步失败（$code）。账号仍保持登录，可点右上角刷新重试。"
                }
                Text(message, color = Ts.amber, modifier = Modifier.padding(8.dp), fontSize = 13.sp)
            } }
            if (!state.bookmarksLoaded && state.bookmarkError == null) item {
                Text("点击右上角「刷新」同步账号中的服务器书签，无需连接云聊天。", color = Ts.muted, modifier = Modifier.padding(8.dp))
            }
            if (state.bookmarksLoaded && state.bookmarks.isEmpty()) item {
                Text("账号中暂无服务器书签。", color = Ts.muted, modifier = Modifier.padding(8.dp))
            }
            items(state.bookmarks, key = { "account:${it.id}" }) { bookmark ->
                CloudRow(bookmark.name.ifBlank { bookmark.address }, bookmark.address, "连接") {
                    onServer(io.github.korppujauho.pocketts.Favorite(bookmark.address, bookmark.name, bookmark.nickname, bookmark.password))
                }
            }
            item { Text("本机收藏", color = Ts.bright, modifier = Modifier.padding(8.dp), fontWeight = FontWeight.SemiBold) }
            if (state.localServers.isEmpty()) item { Text("暂无本机收藏。可在连接页勾选「保存到收藏」。", color = Ts.muted, modifier = Modifier.padding(8.dp)) }
            items(state.localServers) { server ->
                CloudRow(server.label, server.address, "连接") { onServer(server) }
            }
        } else if (tab == 2) {
            if (state.rooms.isEmpty()) item { Text("暂未同步到云端会话，请刷新或检查网络。", color = Ts.muted, modifier = Modifier.padding(16.dp)) }
            items(state.rooms, key = { it.id }) { room ->
                CloudRow(roomTitle(room, state.username, state.cloudUserId, state.contacts), if (!room.joined) "聊天邀请" else if (room.encrypted) "加密会话 · 暂不支持发送" else room.messages.lastOrNull()?.body ?: "暂无文字消息",
                    if (room.unread > 0) room.unread.toString() else null) { onRoom(room.id) }
            }
        } else {
            if (state.contacts.isEmpty() && state.contactError == null) item {
                Text(if (state.contactsLoading || !state.contactsLoaded) "正在加载好友…" else "暂无好友。",
                    color = Ts.muted, modifier = Modifier.padding(16.dp))
            }
            items(state.contacts, key = { it.matrixId.ifEmpty { it.identifier } }) { contact ->
                CloudRow(contact.displayName, contact.subtitle, null) { onContact(contact) }
            }
        }
    }
}

@Composable
private fun AccountPasswordForm(busy: Boolean, onLogin: (String, String, String) -> Unit, onImport: () -> Unit) {
    var email by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var otp by rememberSaveable { mutableStateOf("") }
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("登录 TeamSpeak 账号", color = Ts.bright, fontSize = 25.sp, lineHeight = 33.sp, fontWeight = FontWeight.Bold)
        Text("使用 myTeamSpeak 邮箱和密码。认证成功后即可查看用户名；登录过程不连接云聊天服务器。", color = Ts.text)
        TsField(email, { email = it }, "myTeamSpeak 邮箱", keyboard = KeyboardType.Email)
        TsField(password, { password = it }, "密码", password = true)
        TsField(otp, { otp = it.filter(Char::isDigit).take(6) }, "两步验证码（如已开启）", keyboard = KeyboardType.Number)
        PrimaryButton(
            if (busy) "正在登录…" else "登录账号",
            enabled = !busy && email.isNotBlank() && password.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) { onLogin(email.trim(), password, otp) }
        Text("密码只提交给 TeamSpeak 官方服务，不会保存在手机上。登录会话由 Android Keystore 加密保存。", color = Ts.muted, fontSize = 13.sp)
        HorizontalDivider(color = Ts.card)
        Text("已有电脑端导出的聊天配置？", color = Ts.text, fontWeight = FontWeight.SemiBold)
        OutlinedButton(enabled = !busy, onClick = onImport, modifier = Modifier.fillMaxWidth()) {
            Text("导入 .tsaccount（迁移或恢复）", color = Ts.brand)
        }
        Text("登录后自动读取账号的服务器书签，并默认使用账号用户名作为昵称。好友可手动刷新，云聊天另行连接。", color = Ts.muted, fontSize = 13.sp)
    }
}

@Composable
private fun CloudRow(title: String, subtitle: String, badge: String?, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Ts.card).clickable(onClick = onClick).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(title, color = Ts.bright, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Text(subtitle, color = Ts.muted, maxLines = 2, fontSize = 13.sp)
        }
        badge?.let { Text(it, color = Ts.brand, modifier = Modifier.padding(start = 8.dp)) }
    }
}

internal fun roomTitle(room: MatrixCloud.Room, ownName: String, ownUserId: String = "",
    contacts: List<io.github.korppujauho.pocketts.proto.MyTsAccount.Contact> = emptyList()): String {
    val peer = room.directPeer.ifBlank { if (room.members.size == 2) room.members.keys.firstOrNull {
        it != ownUserId && room.members[it] != ownName
    }.orEmpty() else "" }
    contacts.firstOrNull { it.matrixId == peer }?.let { return it.displayName }
    return room.name.ifBlank {
        room.members.filter { it.key != ownUserId && it.value != ownName }.map { (id, name) ->
            name.takeUnless { it.isBlank() || it == id } ?: "TeamSpeak 用户"
        }.joinToString("、").take(90).ifBlank { "好友聊天" }
    }
}

internal fun chatErrorMessage(code: String): String = when (code) {
    "CONTACT_NO_CHAT", "CONTACT_UNAVAILABLE" -> "这位好友暂时无法发起聊天，请刷新好友列表后重试。"
    "NOT_DIRECT_ROOM" -> "这不是两人私聊，已停止发送，请重新打开好友会话。"
    "ROOM_NOT_JOINED" -> "请先接受邀请，再开始聊天。"
    "CHAT_CREATE_UNCERTAIN" -> "会话创建结果尚未确认，请刷新会话列表后重试，避免重复创建。"
    "ENCRYPTED_ROOM" -> "此会话已开启端到端加密，当前版本不能解密或发送。"
    "M_UNKNOWN_TOKEN", "MYTS_103", "MYTS_CHAT_5" -> "聊天登录已过期，请重新连接云消息；必要时重新验证账号。"
    "M_FORBIDDEN" -> "服务器拒绝了聊天操作，请确认会话权限或重新连接云消息。"
    "M_LIMIT_EXCEEDED" -> "操作较频繁，请稍后重试。"
    "HISTORY_EXHAUSTED" -> "暂无可加载的更早消息。"
    "HTTP_502", "HTTP_503", "HTTP_504", "NETWORK" -> "云聊天暂时连不上，请稍后重试；账号和语音连接不受影响。"
    else -> "聊天操作暂未完成，请重试。"
}

@Composable
fun ColumnScope.CloudConversation(room: MatrixCloud.Room, busy: Boolean, sent: Int, onHistory: () -> Unit, onSend: (String) -> Unit,
    ownUserId: String = "", lastSentRoomId: String? = null, lastSentText: String? = null,
    sendError: String? = null, onJoin: (() -> Unit)? = null, onRead: (String) -> Unit = {},
    senderNames: Map<String, String> = emptyMap()) {
    var draft by remember(room.id) { mutableStateOf("") }
    val scroll = key(room.id) { rememberLazyListState() }
    val nearBottom by remember(scroll) { derivedStateOf {
        scroll.layoutInfo.visibleItemsInfo.lastOrNull()?.index?.let { it >= scroll.layoutInfo.totalItemsCount - 2 } ?: true
    } }
    val latestVisible by remember(scroll) { derivedStateOf {
        scroll.layoutInfo.visibleItemsInfo.lastOrNull()?.index?.let { it == scroll.layoutInfo.totalItemsCount - 1 } ?: false
    } }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    var resumed by remember(lifecycle) { mutableStateOf(lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) }
    DisposableEffect(lifecycle) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, _ ->
            resumed = lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(sent, lastSentRoomId, lastSentText) {
        if (sent > 0 && lastSentRoomId == room.id && draft == lastSentText) draft = ""
    }
    LaunchedEffect(room.messages.lastOrNull()?.id) {
        if (room.messages.isNotEmpty() && nearBottom) scroll.animateScrollToItem(room.messages.lastIndex + 1)
    }
    LaunchedEffect(room.id, room.messages.lastOrNull()?.id, latestVisible, resumed, room.joined, room.encrypted) {
        if (resumed && latestVisible && room.joined && !room.encrypted) room.messages.lastOrNull()?.id?.let(onRead)
    }
    if (!room.joined) {
        Text("你收到了一条聊天邀请。接受后才能查看完整消息和回复。", color = Ts.text, modifier = Modifier.padding(16.dp))
        if (onJoin != null) PrimaryButton("接受邀请并聊天", !busy, Modifier.padding(horizontal = 16.dp), onJoin)
    }
    if (room.encrypted) Text("此会话已加密，当前无法解密或发送消息。", color = Ts.amber, modifier = Modifier.padding(12.dp))
    LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = scroll, contentPadding = PaddingValues(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { TextButton(enabled = !busy && room.joined && room.hasMoreHistory && room.messages.size < MatrixCloud.MESSAGE_LIMIT, onClick = onHistory) {
            Text(if (room.messages.size >= MatrixCloud.MESSAGE_LIMIT) "本次最多显示 ${MatrixCloud.MESSAGE_LIMIT} 条消息" else "加载更早消息", color = Ts.brand)
        } }
        items(room.messages, key = { it.id }) { message ->
            val own = ownUserId.isNotBlank() && message.sender == ownUserId
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp, 20.dp, 20.dp, 5.dp)).background(if (own) Ts.accent.copy(alpha = .14f) else Ts.panel).padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(Modifier.fillMaxWidth()) {
                    Text(if (own) "我" else senderNames[message.sender] ?: room.members[message.sender]?.takeUnless { it == message.sender } ?: "TeamSpeak 用户",
                        color = Ts.brand, fontSize = 12.sp, modifier = Modifier.weight(1f))
                    Text(SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(message.timestamp)), color = Ts.muted, fontSize = 11.sp)
                }
                androidx.compose.foundation.text.selection.SelectionContainer { Text(message.body, color = Ts.text) }
            }
        }
    }
    sendError?.let { Text(chatErrorMessage(it) + " 草稿已保留，可再次点击发送。", color = Ts.amber, modifier = Modifier.padding(horizontal = 16.dp)) }
    Row(Modifier.fillMaxWidth().background(Ts.header).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(draft, { if (it.length <= 4096) draft = it }, enabled = !room.encrypted && room.joined,
            modifier = Modifier.weight(1f), placeholder = { Text("发送云端消息…") }, maxLines = 4)
        PrimaryButton(if (busy) "请稍候…" else "发送", enabled = draft.isNotBlank() && !busy && !room.encrypted && room.joined) { onSend(draft) }
    }
}

@Composable
private fun MyTsLoginDialog(busy: Boolean, onDismiss: () -> Unit, onLogin: (String, String, String) -> Unit) {
    var email by remember { mutableStateOf("") }; var password by remember { mutableStateOf("") }; var otp by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() }, title = { Text("重新登录 myTeamSpeak") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("重新验证或切换账号，只进行账号认证。云聊天需要另行连接，密码不会保存在手机上。", fontSize = 13.sp)
            TsField(email, { email = it }, "邮箱", keyboard = KeyboardType.Email)
            TsField(password, { password = it }, "密码", password = true)
            TsField(otp, { otp = it.filter(Char::isDigit).take(6) }, "两步验证码（如已开启）", keyboard = KeyboardType.Number)
        } },
        confirmButton = { TextButton(enabled = !busy && email.isNotBlank() && password.isNotBlank(), onClick = { onLogin(email.trim(), password, otp) }) { Text(if (busy) "正在登录…" else "登录") } },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("取消") } })
}

@Composable
private fun CloudError(code: String, cloud: Boolean = false) {
    val text = when (code) {
        "MYTS_202" -> "邮箱或密码不正确。"
        "MYTS_208" -> "账号需要两步验证码，请填写后重试。"
        "MYTS_209" -> "两步验证码不正确，请重试。"
        "MYTS_103" -> "账号会话已过期，请重新登录。"
        "MYTS_CHAT_5" -> "TeamSpeak 登录已过期，请重新登录。"
        "MYTS_CHAT_6" -> "这个账号暂时不能启用 TeamSpeak 云聊天。"
        "MYTS_CHAT_NO_CREDENTIALS" -> "TeamSpeak 没有返回云聊天凭据，请稍后重试。"
        "MYTS_CHAT_INVALID_CREDENTIALS" -> "TeamSpeak 返回的云聊天凭据无效。"
        "INVALID_TRANSFER" -> "配置文件无效，请选择专用的 .tsaccount 文件。"
        "LOCAL_STORE" -> "无法安全保存或读取登录信息，请重试。"
        "M_FORBIDDEN" -> "云聊天拒绝了这组凭据，请重新登录。"
        "M_UNKNOWN_TOKEN" -> "聊天会话已过期，请重新登录。"
        "M_LIMIT_EXCEEDED" -> "请求过于频繁，请稍后重试。"
        "ENCRYPTED_ROOM" -> "此会话已加密，当前版本禁止发送未加密消息。"
        else -> if (cloud) "云聊天连接失败（$code）。TeamSpeak 账号仍保持登录，可稍后重试。" else "暂时无法完成操作，请检查网络后重试（$code）。"
    }
    Text(text, color = Ts.amber, modifier = Modifier.padding(12.dp), fontSize = 13.sp)
}
