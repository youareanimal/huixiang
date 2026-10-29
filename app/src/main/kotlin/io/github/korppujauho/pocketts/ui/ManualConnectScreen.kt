package io.github.korppujauho.pocketts.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.korppujauho.pocketts.proto.JoinLink

/** The server directory stays simple; connection details have their own route. */
@Composable
fun ManualConnectScreen(nickname: String, accountUsername: String, address: String, password: String,
    error: String?, onBack: () -> Unit, signedIn: Boolean = accountUsername.isNotBlank(),
    onConnect: (String, String, String, Boolean, Boolean) -> Unit) {
    var addr by rememberSaveable(address) { mutableStateOf(address) }
    var nick by rememberSaveable(nickname, accountUsername) { mutableStateOf(nickname) }
    var pw by remember(password) { mutableStateOf(password) }
    var rememberPassword by remember { mutableStateOf(password.isNotBlank()) }
    var favorite by rememberSaveable { mutableStateOf(true) }
    val effectiveNick = if (signedIn) accountUsername else nick
    Column(Modifier.fillMaxSize().background(Ts.page).imePadding()) {
        HubTopBar("下一个频道见", "添加服务器 / 粘贴邀请", onBack)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            // A headline must bring its own line height: the body default is 24sp, which overlaps 29sp lines
            Text("输入地址，\n就能一起开聊。", color = Ts.bright, fontSize = 29.sp, lineHeight = 38.sp, fontWeight = FontWeight.Bold)
            TsField(addr, { addr = it }, "服务器地址或邀请链接", placeholder = "ts.example.com:9987", keyboard = KeyboardType.Uri)
            if (!signedIn) TsField(nick, { nick = it }, "连接昵称")
            else Text(if (accountUsername.isBlank()) "正在读取当前账号信息…" else "使用当前账号：$accountUsername", color = Ts.brand)
            TsField(pw, { pw = it }, "服务器密码（如有）", password = true)
            Text("这里填写服务器密码，账号密码只在登录页使用。", color = Ts.muted, fontSize = 12.sp)
            Row(Modifier.fillMaxWidth()) { Checkbox(favorite, { favorite = it }); Text("保存到本机收藏", color = Ts.text, modifier = Modifier.padding(top = 12.dp)) }
            Row(Modifier.fillMaxWidth()) { Checkbox(rememberPassword, { rememberPassword = it }); Text("在本机记住服务器密码", color = Ts.text, modifier = Modifier.padding(top = 12.dp)) }
            if (error != null) HubError("连接暂未成功，检查地址、密码或网络后重试。", error)
            PrimaryButton(if (JoinLink.isLink(addr)) "查看邀请，确认加入" else "连接服务器",
                addr.isNotBlank() && (effectiveNick.isNotBlank() || JoinLink.isLink(addr)), Modifier.fillMaxWidth()) {
                onConnect(addr.trim(), effectiveNick.trim(), pw, rememberPassword, favorite)
            }
        }
    }
}
