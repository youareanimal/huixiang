package io.github.korppujauho.pocketts.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import io.github.korppujauho.pocketts.CloudAccountController

@Composable
fun AccountAvatarScreen(state: CloudAccountController.State, self: SelfInfo?, onBack: () -> Unit,
    onUpload: (Uri) -> Unit, onRemove: () -> Unit, onServerAvatar: () -> Unit, onRefresh: () -> Unit) {
    var selected by remember { mutableStateOf<Uri?>(null) }
    var removing by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(PickVisualMedia()) { selected = it }
    val busy = state.avatarBusy || state.busy
    Column(Modifier.fillMaxSize()) {
        HubTopBar("换个头像，亮个相", "账号与语音服务器分别设置", onBack)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("账号头像 · 在线", color = Ts.bright, fontSize = 21.sp)
            Box(Modifier.size(104.dp).clip(CircleShape).background(Ts.card), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Person, null, Modifier.size(70.dp), tint = Ts.muted)
                if (state.avatarUrl.isNotBlank()) AsyncImage(state.avatarUrl, "当前账号在线头像", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            }
            Desc("同步到 TeamSpeak 账号，仅修改在线状态头像。离开、勿扰和离线头像保持原样。图片会转换为静态 PNG。")
            Button(onClick = { picker.launch(PickVisualMediaRequest(PickVisualMedia.ImageOnly)) }, enabled = state.hasAccount && !busy) { Text("从相册选择账号头像") }
            TextButton(onClick = onRefresh, enabled = !busy && !state.profileLoading) { Text(if (state.profileLoading) "正在刷新…" else "刷新账号头像") }
            if (state.avatarUrl.isNotBlank()) TextButton(onClick = { removing = true }, enabled = !busy) { Text("移除账号在线头像", color = Ts.red) }
            if (busy) Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(20.dp), color = Ts.brand, strokeWidth = 2.dp)
                Text("正在保存头像…", color = Ts.text)
            }
            state.error?.let { HubError(when {
                it == "AVATAR_IMAGE" -> "图片无法读取，或文件过大。请换一张图片再试。"
                it == "NETWORK" -> "暂时无法确认保存结果，请先刷新账号头像，再决定是否重试。"
                it.startsWith("AVATAR_HTTP_") -> "图片上传失败，原头像仍保留。可重新选择图片重试。"
                else -> "头像未能保存，可稍后重试。"
            }, it) }
            if (state.notice?.startsWith("账号在线头像") == true) Text(state.notice, color = Ts.green)
            if (state.profileError != null) HubError("资料暂未刷新，可重新获取头像。", state.profileError, onRefresh)
            HorizontalDivider(color = Ts.muted.copy(alpha = .2f))
            Text("当前服务器头像", color = Ts.bright, fontSize = 21.sp)
            if (self?.person != null) {
                PersonAvatar(self.person, self.server, 72)
                Desc("仅对当前连接的语音服务器生效。")
                OutlinedButton(onClick = onServerAvatar) { Text("管理服务器头像") }
            } else Desc("进入语音服务器后，可在这里修改该服务器中的头像。")
        }
    }
    selected?.let { uri -> AlertDialog(onDismissRequest = { selected = null }, title = { Text("使用这张账号头像？") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            AsyncImage(uri, "待上传头像预览", Modifier.size(160.dp).clip(CircleShape), contentScale = ContentScale.Crop)
            Text("保存后会更新 TeamSpeak 账号的在线头像。图片将缩小至 320 像素以内。")
        } }, confirmButton = { TextButton(onClick = { selected = null; onUpload(uri) }, enabled = !busy) { Text("确认上传") } },
        dismissButton = { TextButton(onClick = { selected = null }) { Text("取消") } }) }
    if (removing) AlertDialog(onDismissRequest = { removing = false }, title = { Text("移除账号在线头像？") },
        text = { Text("其他状态头像及服务器头像保持原样。") },
        confirmButton = { TextButton(onClick = { removing = false; onRemove() }, enabled = !busy) { Text("移除", color = Ts.red) } },
        dismissButton = { TextButton(onClick = { removing = false }) { Text("取消") } })
}
