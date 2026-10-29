package io.github.korppujauho.pocketts.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.korppujauho.pocketts.AppUpdater
import io.github.korppujauho.pocketts.BuildConfig
import io.github.korppujauho.pocketts.UpdateFeed
import java.util.Locale

@Composable
fun AppUpdateDialog(state: AppUpdater.State, onDismiss: () -> Unit, onCheck: () -> Unit,
    onDownload: () -> Unit, onCancel: () -> Unit, onInstall: () -> Unit, onAutomatic: (Boolean) -> Unit,
    onSource: (UpdateFeed.Source) -> Unit = {}) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text("关于回响") },
        text = { Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("回响 ${BuildConfig.VERSION_NAME}", color = Ts.bright)
            Desc("让声音有回应。基于 PocketTS，采用 GPL-3.0 许可，与 TeamSpeak 官方无关联。")
            HorizontalDivider(color = Ts.hairline)
            Text("在线更新", color = Ts.bright)
            Text("优先下载源：${state.source.label}", color = Ts.text)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                UpdateFeed.Source.entries.forEach { source ->
                    FilterChip(selected = state.source == source, onClick = { onSource(source) },
                        enabled = !state.checking && !state.downloading, label = { Text(source.label) })
                }
            }
            Desc("切换后点击检查更新。连接失败会尝试备用源，安装包仍校验哈希和签名。")
            state.activeSource?.let { Text("当前来源：${it.label}", color = Ts.brand) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("自动检查新版本", color = Ts.text)
                Switch(state.automatic, onCheckedChange = onAutomatic)
            }
            Desc("开启后，每天最多检查一次。下载与安装由你确认。")
            if (state.checking) {
                CircularProgressIndicator(Modifier.size(22.dp), color = Ts.brand, strokeWidth = 2.dp)
                Text("正在检查更新…", color = Ts.muted)
            }
            state.release?.let { release ->
                Text("发现新版本 ${release.name}", color = Ts.brand)
                Desc("安装包 ${String.format(Locale.ROOT, "%.1f", release.artifact.bytes / 1048576.0)} MB · 自动匹配手机架构")
                if (release.notes.isNotBlank()) Text(release.notes, color = Ts.text)
                when {
                    state.downloading -> {
                        val progress = (state.received.toFloat() / release.artifact.bytes).coerceIn(0f, 1f)
                        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth(), color = Ts.brand)
                        Text("正在下载 ${(progress * 100).toInt()}%", color = Ts.text)
                        TextButton(onClick = onCancel) { Text("取消下载") }
                    }
                    state.ready -> {
                        Text("下载完成，可以安装", color = Ts.green)
                        Button(onClick = onInstall, enabled = !state.checking) { Text("安装新版") }
                        Desc("安装时可能需要允许回响安装应用。升级保留现有数据，但会关闭应用并中断当前语音。")
                    }
                    else -> Button(onClick = onDownload, enabled = !state.checking) { Text("下载新版") }
                }
            }
            if (state.checked && state.release == null && !state.checking && state.error == null) Text("已是最新版本", color = Ts.green)
            state.error?.let { Text(it, color = Ts.red) }
        } },
        confirmButton = { TextButton(onClick = onDismiss) { Text(if (state.downloading) "后台下载" else "关闭") } },
        dismissButton = { TextButton(onClick = onCheck, enabled = !state.checking && !state.downloading) { Text("检查更新") } })
}

@Composable
fun AppUpdateBanner(state: AppUpdater.State, onOpen: () -> Unit, onDismiss: () -> Unit) {
    val release = state.release ?: return
    if (state.dismissed == release.code) return
    Surface(color = Ts.card, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = onOpen) { Text(if (state.ready) "新版已下载，点此安装" else "发现新版 ${release.name} · 查看", color = Ts.brand) }
            TextButton(onClick = onDismiss) { Text("稍后", color = Ts.muted) }
        }
    }
}
