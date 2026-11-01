package io.github.korppujauho.pocketts.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.korppujauho.pocketts.*
import io.github.korppujauho.pocketts.R
import io.github.korppujauho.pocketts.proto.MatrixCloud
import io.github.korppujauho.pocketts.proto.MyTsAccount

enum class HubTab(val label: String, val icon: ImageVector) {
    SERVERS("服务器", Icons.Rounded.Dns), FRIENDS("好友", Icons.Rounded.PeopleAlt),
    MESSAGES("消息", Icons.AutoMirrored.Rounded.Chat), ME("我的", Icons.Rounded.Face)
}

@Composable
fun HubNavigation(selected: HubTab, onSelect: (HubTab) -> Unit) {
    NavigationBar(containerColor = Ts.header, tonalElevation = 0.dp, windowInsets = WindowInsets(0, 0, 0, 0)) {
        HubTab.entries.forEach { tab ->
            NavigationBarItem(selected = tab == selected, onClick = { onSelect(tab) },
                icon = { Icon(tab.icon, null) }, label = { Text(tab.label) },
                colors = NavigationBarItemDefaults.colors(selectedIconColor = Ts.brand, selectedTextColor = Ts.brand,
                    indicatorColor = Ts.accent.copy(alpha = .22f), unselectedIconColor = Ts.muted, unselectedTextColor = Ts.muted))
        }
    }
}

@Composable
fun HubTopBar(title: String, subtitle: String = "", onBack: (() -> Unit)? = null, actions: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回", tint = Ts.text) }
        Column(Modifier.weight(1f).padding(start = if (onBack == null) 8.dp else 0.dp)) {
            Text(title, color = Ts.bright, fontSize = 26.sp, lineHeight = 35.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle.isNotBlank()) Text(subtitle, color = Ts.muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        actions()
    }
}

@Composable
fun BrandHero(eyebrow: String, title: String, subtitle: String) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(26.dp))
        .background(Brush.linearGradient(listOf(Color(0xFF483078), Color(0xFF29213F)))).padding(20.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text(eyebrow, color = Color(0xFFE1D2FF), fontSize = 11.sp, letterSpacing = 2.sp)
            Text(title, color = Color.White, fontSize = 25.sp, lineHeight = 34.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(subtitle, color = Color(0xFFD5C9E8), fontSize = 13.sp)
        }
        Image(painterResource(R.drawable.huixiang_mascot), null, Modifier.size(80.dp))
    }
}

@Composable
private fun SectionLabel(title: String, hint: String = "") {
    Row(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = Ts.bright, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, modifier = Modifier.weight(1f))
        if (hint.isNotEmpty()) Text(hint, color = Ts.muted, fontSize = 12.sp)
    }
}

@Composable
fun HubRow(title: String, subtitle: String, icon: ImageVector, onClick: () -> Unit, trailing: @Composable (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Ts.panel).clickable(onClick = onClick).padding(15.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(13.dp)) {
        Box(Modifier.size(43.dp).clip(RoundedCornerShape(15.dp)).background(Ts.accent.copy(alpha = .18f)), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = Ts.brand, modifier = Modifier.size(23.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, color = Ts.bright, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (subtitle.isNotBlank()) Text(subtitle, color = Ts.muted, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (trailing != null) trailing() else Icon(Icons.Rounded.ChevronRight, null, tint = Ts.muted)
    }
}

@Composable
fun HubEmpty(title: String, text: String, action: String? = null, onAction: () -> Unit = {}) {
    Column(Modifier.fillMaxWidth().padding(vertical = 22.dp), verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Image(painterResource(R.drawable.huixiang_mascot), null, Modifier.size(76.dp))
        Text(title, color = Ts.bright, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
        Text(text, color = Ts.muted, fontSize = 13.sp)
        if (action != null) PrimaryButton(action, onClick = onAction)
    }
}

/** User-readable failure first; technical codes are opt-in and never contain response bodies or secrets. */
@Composable
fun HubError(message: String, code: String, retry: (() -> Unit)? = null) {
    var details by rememberSaveable(code) { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Ts.amber.copy(alpha = .10f)).padding(14.dp)) {
        Text(message, color = Ts.amber, fontSize = 13.sp)
        Row {
            if (retry != null) TextButton(onClick = retry) { Text("重试", color = Ts.brand) }
            TextButton(onClick = { details = !details }) { Text(if (details) "收起详情" else "查看详情", color = Ts.muted) }
            if (details) TextButton(onClick = { clipboard.setText(AnnotatedString(code)) }) { Text("复制错误码", color = Ts.muted) }
        }
        if (details) Text(code, color = Ts.muted, fontSize = 12.sp)
    }
}

@Composable
fun ServerHub(account: CloudAccountController.State, favorites: List<Favorite>, recent: List<Favorite>,
    onAdd: () -> Unit, onJoin: (Favorite) -> Unit, onRecent: (Favorite) -> Unit, onEdit: (Favorite) -> Unit,
    onRefresh: () -> Unit, onLogin: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        HubTopBar("一起，回响", if (account.hasAccount) "${account.username}，今天去哪里聊？" else "你的声音，总有人回应") {
            IconButton(onClick = onRefresh, enabled = account.hasAccount && !account.bookmarksLoading) {
                Icon(Icons.Rounded.Refresh, "刷新服务器", tint = Ts.muted)
            }
            FilledIconButton(onClick = onAdd) { Icon(Icons.Rounded.Add, "添加服务器") }
        }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(start = 18.dp, end = 18.dp, bottom = 22.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item { BrandHero("FIND YOUR PEOPLE", "进个频道，开聊", "熟悉的服务器，一点就到。") }
            if (!account.hasAccount) item {
                HubRow("访客模式也能畅聊", "登录后同步服务器书签和好友", Icons.Rounded.Login, onLogin)
            }
            if (account.bookmarksLoading) item { LinearProgressIndicator(Modifier.fillMaxWidth(), color = Ts.brand, trackColor = Ts.panel) }
            account.bookmarkError?.let { code -> item {
                HubError(if (code.startsWith("BOOKMARK_KEY")) "账号已登录，书签暂未解锁。可到“我的”重新验证账号。" else "书签暂时没同步成功，已有服务器仍可使用。", code, onRefresh)
            } }
            if (recent.isNotEmpty()) {
                item { SectionLabel("最近一起聊过", "最近连接") }
                items(recent, key = { "recent:${it.address}:${it.nickname}" }) { f -> HubRow(f.label, f.address, Icons.Rounded.History, { onRecent(f) }) }
            }
            if (account.hasAccount) {
                item { SectionLabel("账号书签", "${account.bookmarks.size} 个") }
                items(account.bookmarks, key = { "cloud:${it.id}" }) { bookmark ->
                    HubRow(bookmark.name.ifBlank { bookmark.address }, bookmark.address, Icons.Rounded.CloudQueue,
                        { onJoin(Favorite(bookmark.address, bookmark.name, bookmark.nickname, bookmark.password)) })
                }
                if (account.bookmarksLoaded && account.bookmarks.isEmpty()) item { Text("还没有账号书签，试试添加一个本机服务器。", color = Ts.muted, fontSize = 13.sp) }
            }
            item { SectionLabel("我的收藏", "本机保存") }
            items(favorites) { f ->
                HubRow(f.label, f.address + if (f.auto) " · 自动连接" else "", Icons.Rounded.BookmarkBorder, { onJoin(f) },
                    trailing = { IconButton(onClick = { onEdit(f) }) { Icon(Icons.Rounded.MoreHoriz, "管理 ${f.label}", tint = Ts.muted) } })
            }
            if (favorites.isEmpty()) item { HubEmpty("把常去的地方留在这里", "添加服务器，或者粘贴好友发来的邀请。", "添加服务器", onAdd) }
        }
    }
}

@Composable
fun FriendsHub(state: CloudAccountController.State, onLogin: () -> Unit, onRefresh: () -> Unit, onContact: (MyTsAccount.Contact) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    Column(Modifier.fillMaxSize()) {
        HubTopBar("好友", "熟悉的人，不用重新找") {
            IconButton(onClick = onRefresh, enabled = state.hasAccount && !state.contactsLoading) { Icon(Icons.Rounded.Refresh, "刷新好友", tint = Ts.brand) }
        }
        if (!state.hasAccount) { Box(Modifier.padding(20.dp)) { HubEmpty("登录，和好友保持联系", "语音服务器依然可以用访客身份连接。", "登录账号", onLogin) }; return }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item { TsField(query, { query = it }, "搜索好友") }
            if (state.contactsLoading) item { LinearProgressIndicator(Modifier.fillMaxWidth(), color = Ts.brand, trackColor = Ts.panel) }
            state.contactError?.let { code -> item { HubError("好友暂时没加载成功，已缓存的好友仍会保留。", code, onRefresh) } }
            val shown = state.contacts.filter { it.displayName.contains(query, ignoreCase = true) || it.subtitle.contains(query, ignoreCase = true) }
            items(shown) { contact -> HubRow(contact.displayName, contact.subtitle.ifBlank { "TeamSpeak 好友" }, Icons.Rounded.PersonOutline, { onContact(contact) }) }
            if (shown.isEmpty() && !state.contactsLoading) item { HubEmpty(if (query.isBlank()) "好友会在这里相遇" else "没有找到这位好友", if (query.isBlank()) "添加的好友会自动出现在这里。" else "换个名字试试。") }
        }
    }
}

@Composable
fun MessagesHub(state: CloudAccountController.State, onLogin: () -> Unit, onConnect: () -> Unit, onRoom: (String) -> Unit,
    onDiagnose: () -> Unit = {}) {
    Column(Modifier.fillMaxSize()) {
        HubTopBar("消息", "好友云消息 · 与服务器聊天分开")
        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Ts.brand)
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (!state.hasAccount) item { HubEmpty("好聊的话题，接着聊", "登录账号后可按需连接云消息。", "登录账号", onLogin) }
            else if (!state.cloudConnected) {
                item { BrandHero("KEEP IN TOUCH", "聊点频道以外的", "云消息按需开启，语音随时在线。") }
                item { Text("可在好友页点击好友发消息，或在这里查看会话。暂时不连接，不影响登录、书签和语音服务器。", color = Ts.muted, fontSize = 14.sp) }
                item { PrimaryButton(if (state.busy) "正在连接…" else "连接云消息", !state.busy, Modifier.fillMaxWidth(), onConnect) }
            }
            state.cloudError?.let { code -> item { HubError(chatErrorMessage(code), code, onConnect) } }
            if (state.cloudError?.startsWith("CHAT_LOGIN_") == true) {
                item {
                    OutlinedButton(onClick = onDiagnose, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Rounded.Refresh, contentDescription = null, tint = Ts.brand)
                        Spacer(Modifier.width(8.dp))
                        Text("检测官方聊天登录接口（不发送密码）", color = Ts.brand)
                    }
                }
            }
            state.chatProbe?.let { probe -> item {
                Text(probe, color = Ts.muted, fontSize = 12.sp, modifier = Modifier.fillMaxWidth().padding(8.dp))
            } }
            if (state.cloudConnected) {
                item {
                    OutlinedButton(onClick = onConnect, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Rounded.Refresh, contentDescription = null, tint = Ts.brand)
                        Spacer(Modifier.width(8.dp))
                        Text("重新连接云消息", color = Ts.brand)
                    }
                }
                items(state.rooms, key = { it.id }) { room ->
                    HubRow(roomTitle(room, state.username, state.cloudUserId, state.contacts), if (!room.joined) "聊天邀请 · 点击查看" else if (room.encrypted) "加密会话 · 当前只读" else room.messages.lastOrNull()?.body ?: "还没有文字消息",
                        Icons.AutoMirrored.Rounded.Chat, { onRoom(room.id) }, trailing = {
                            if (room.unread > 0) Badge(containerColor = Ts.accent) { Text(room.unread.toString()) }
                            else Icon(Icons.Rounded.ChevronRight, null, tint = Ts.muted)
                        })
                }
                if (state.rooms.isEmpty()) item { HubEmpty("暂时没有会话", "到好友页点击好友，即可开始聊天。") }
            }
        }
    }
}

@Composable
fun MeHub(state: CloudAccountController.State, voice: VoiceSettings, onLogin: () -> Unit, onLogout: () -> Unit,
    onSettings: (Int) -> Unit, onImport: () -> Unit, onAbout: () -> Unit,
    onRefreshProfile: () -> Unit = {}, onAvatar: () -> Unit = {}) {
    Column(Modifier.fillMaxSize()) {
        HubTopBar("我的", "按你的方式，尽情开聊")
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                if (state.hasAccount) AccountProfileCard(state, onAvatar, onRefreshProfile)
                else BrandHero("HEY, ECHO!", "你好，访客", "登录，带上你的书签和好友。")
            }
            if (state.hasAccount) item { HubRow("修改头像", "账号头像 · 当前服务器头像", Icons.Rounded.AddAPhoto, onAvatar) }
            item { HubRow(if (state.hasAccount) "切换或重新验证账号" else "登录 TeamSpeak 账号", "账号登录与语音身份独立", Icons.Rounded.AccountCircle, onLogin) }
            state.error?.let { code -> item { HubError("账号操作未完成，可重新尝试。", code) } }
            item { SectionLabel("把声音调成你的样子") }
            item { HubRow("发言与录音", if (voice.activation == VoiceSettings.Activation.PTT) "当前：按住说话" else "当前：语音激活", Icons.Rounded.MicNone, { onSettings(2) }) }
            item { HubRow("声音与播放设备", "音量、音频输出与播放测试", Icons.AutoMirrored.Rounded.VolumeUp, { onSettings(1) }) }
            item { HubRow("个人资料与后台运行", "连接昵称、通知和后台设置", Icons.Rounded.Tune, { onSettings(0) }) }
            item { HubRow("界面与语言", "显示偏好、操作布局和语言", Icons.Rounded.Palette, { onSettings(3) }) }
            item { HubRow("身份与高级设置", "语音身份、频道密码及高级选项", Icons.Rounded.Fingerprint, { onSettings(4) }) }
            item { SectionLabel("其他") }
            item { HubRow("导入迁移配置", ".tsaccount · 仅用于迁移或恢复", Icons.Rounded.FileOpen, onImport) }
            item { HubRow("关于回响", "${BuildConfig.VERSION_NAME} · 让声音有回应", Icons.Rounded.Info, onAbout) }
            if (state.hasAccount) item { TextButton(onClick = onLogout, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text("退出本机账号", color = Ts.red) } }
        }
    }
}

@Composable
private fun AccountProfileCard(state: CloudAccountController.State, onAvatar: () -> Unit, onRefresh: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(26.dp)).background(Ts.card).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(Modifier.size(76.dp).clip(CircleShape).background(Ts.accent.copy(alpha = .2f)).clickable(onClick = onAvatar)) {
                Image(painterResource(R.drawable.huixiang_mascot), if (state.avatarUrl.isBlank()) "修改账号头像" else null, Modifier.fillMaxSize().padding(7.dp))
                if (state.avatarUrl.isNotBlank()) coil3.compose.AsyncImage(state.avatarUrl, "修改账号头像", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("MY TEAMSPEAK", color = Ts.brand, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Text(state.username.ifBlank { "TeamSpeak 用户" }, color = Ts.bright, fontSize = 23.sp, fontWeight = FontWeight.Bold,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("账号已登录", color = Ts.green, fontSize = 12.sp)
            }
        }
        HorizontalDivider(color = Ts.muted.copy(alpha = .15f))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("账号邮箱", color = Ts.muted, fontSize = 11.sp)
                Text(state.email.ifBlank { if (state.profileLoading) "正在获取…" else "暂未获取，可刷新重试" }, color = Ts.text, fontSize = 14.sp,
                    modifier = Modifier.clickable(enabled = state.email.isNotBlank()) { clipboard.setText(AnnotatedString(state.email)) })
            }
            IconButton(onClick = onRefresh, enabled = !state.profileLoading && !state.avatarBusy) {
                if (state.profileLoading) CircularProgressIndicator(Modifier.size(18.dp), color = Ts.brand, strokeWidth = 2.dp)
                else Icon(Icons.Rounded.Refresh, "刷新账号资料", tint = Ts.brand)
            }
        }
        if (state.profileError != null) Text("账号资料暂未更新，已保留本机资料。可点刷新重试。", color = Ts.muted, fontSize = 12.sp)
    }
}

@Composable
fun HubLogin(state: CloudAccountController.State, onBack: () -> Unit, onLogin: (String, String, String) -> Unit) {
    var email by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var otp by rememberSaveable { mutableStateOf("") }
    var otpOpen by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(state.error) { if (state.error == "MYTS_208" || state.error == "MYTS_209") otpOpen = true }
    Column(Modifier.fillMaxSize().imePadding()) {
        HubTopBar("和好友，再次相遇", "登录 TeamSpeak 账号", onBack)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(22.dp), verticalArrangement = Arrangement.spacedBy(17.dp)) {
            Image(painterResource(R.drawable.huixiang_mascot), null, Modifier.size(96.dp))
            Text("你的书签和好友\n会一起回来。", color = Ts.bright, fontSize = 30.sp, lineHeight = 41.sp, fontWeight = FontWeight.Bold)
            TsField(email, { email = it }, "myTeamSpeak 邮箱", keyboard = KeyboardType.Email)
            TsField(password, { password = it }, "密码", password = true)
            TextButton(onClick = { otpOpen = !otpOpen }) { Text(if (otpOpen) "收起两步验证码" else "我开启了两步验证", color = Ts.brand) }
            if (otpOpen) TsField(otp, { otp = it.filter(Char::isDigit).take(6) }, "六位验证码", keyboard = KeyboardType.Number)
            state.error?.let { code -> HubError(when (code) {
                "MYTS_202" -> "邮箱或密码不正确，再检查一下。"
                "MYTS_208", "MYTS_209" -> "请填写有效的两步验证码。"
                else -> "暂时没能登录，检查网络后再试一次。"
            }, code) }
            PrimaryButton(if (state.busy) "正在登录…" else "登录，开启回响", !state.busy && email.isNotBlank() && password.isNotBlank(), Modifier.fillMaxWidth()) { onLogin(email.trim(), password, if (otpOpen) otp else "") }
            Text("邮箱密码仅用于当次官方账号认证，不保存在手机上。登录后即可使用书签和好友，云消息由你单独开启。", color = Ts.muted, fontSize = 12.sp)
        }
    }
}

@Composable
fun VoiceMiniBar(state: Session.UiState, onOpen: () -> Unit, onMic: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 5.dp).clip(RoundedCornerShape(20.dp))
        .background(Ts.talking.copy(alpha = .12f)).clickable(onClick = onOpen).padding(start = 14.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(Icons.Rounded.GraphicEq, null, tint = Ts.talking)
        Column(Modifier.weight(1f)) {
            Text(if (state.phase == Session.Phase.CONNECTING) "正在连接…" else state.ownChannelName.ifBlank { "语音已连接" }, color = Ts.talking, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Text(state.serverName.ifBlank { state.address }, color = Ts.muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        IconButton(onClick = onMic) { Icon(if (state.inputMuted || !state.micAllowed) Icons.Rounded.MicOff else Icons.Rounded.Mic, "切换麦克风静音", tint = Ts.talking) }
        Icon(Icons.Rounded.ExpandLess, null, tint = Ts.talking)
    }
}
