package io.github.korppujauho.pocketts.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.InsertDriveFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.rounded.AudioFile
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.DriveFileRenameOutline
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.UploadFile
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.korppujauho.pocketts.R
import io.github.korppujauho.pocketts.Session
import io.github.korppujauho.pocketts.proto.FileTransfer
import io.github.korppujauho.pocketts.proto.ServerModel

/** Android's Save window for a file: its suggested name and type in, where the person chose out (null: cancelled). */
private class SaveAs : androidx.activity.result.contract.ActivityResultContract<Pair<String, String>, android.net.Uri?>() {
    override fun createIntent(context: android.content.Context, input: Pair<String, String>) =
        android.content.Intent(android.content.Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(android.content.Intent.CATEGORY_OPENABLE)
            .setType(input.second)
            .putExtra(android.content.Intent.EXTRA_TITLE, input.first)

    override fun parseResult(resultCode: Int, intent: android.content.Intent?) = intent?.data?.takeIf { resultCode == android.app.Activity.RESULT_OK }
}

private fun mimeOf(name: String) =
    android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase()) ?: "application/octet-stream"

private fun iconOf(e: FileTransfer.Entry): ImageVector = when {
    e.folder -> Icons.Rounded.Folder
    else -> when (mimeOf(e.name).substringBefore('/')) {
        "image" -> Icons.Rounded.Image
        "video" -> Icons.Rounded.Movie
        "audio" -> Icons.Rounded.AudioFile
        else -> Icons.AutoMirrored.Rounded.InsertDriveFile
    }
}

private fun inside(path: String, name: String) = if (path == "/") "/$name" else "$path/$name"

/** A name the server takes for a file or folder: something, and no path in it. */
private fun nameOk(name: String) = name.isNotBlank() && '/' !in name && name.trim() != "." && name.trim() != ".."

/** A picked file's name and size, as Android tells them (null: it can't). */
private fun pickedFile(context: android.content.Context, source: android.net.Uri): Pair<String, Long>? = try {
    val columns = arrayOf(android.provider.OpenableColumns.DISPLAY_NAME, android.provider.OpenableColumns.SIZE)
    context.contentResolver.query(source, columns, null, null, null)?.use { c ->
        val name = if (c.moveToFirst()) c.getString(0)?.replace('/', '_')?.takeIf { it.isNotBlank() } else null
        val size = if (name != null && !c.isNull(1)) c.getLong(1) else -1L
        if (name != null && size >= 0) name to size else null
    }
} catch (_: Exception) {
    null
}

/** A change to a channel's files, asked of the server. */
sealed interface FileChange {
    data class Delete(val path: String) : FileChange
    data class Rename(val from: String, val to: String) : FileChange
    data class NewFolder(val path: String) : FileChange
}

/** What the browser is asking the person before going on. */
sealed interface FileAsk {
    data object NewFolder : FileAsk
    data class Rename(val entry: FileTransfer.Entry) : FileAsk
    data class Delete(val entry: FileTransfer.Entry) : FileAsk
    data class ReplaceUpload(val source: android.net.Uri, val name: String, val size: Long) : FileAsk
    data class ReplaceRename(val entry: FileTransfer.Entry, val to: String) : FileAsk
}

/**
 * A channel's files, as the TeamSpeak app's file browser: folders first, then files, with their sizes and when they
 * were changed. Tapping a folder opens it (back goes up), tapping a file saves it where the person chooses; holding
 * one gives Download, Rename and Delete, and the bar has Upload and New folder. What may be seen and changed is the
 * server's to decide; a password channel needs its remembered password.
 */
@Composable
fun FileBrowser(
    channel: ServerModel.Channel,
    download: Session.FileDownload?,
    downloadResult: String?,
    list: (path: String, done: (Result<List<FileTransfer.Entry>>) -> Unit) -> Unit,
    onDownload: (path: String, target: android.net.Uri) -> Unit,
    onCancelDownload: () -> Unit,
    onResultSeen: () -> Unit,
    onDismiss: () -> Unit,
    onUpload: (path: String, source: android.net.Uri, size: Long, overwrite: Boolean) -> Unit = { _, _, _, _ -> },
    onChange: (change: FileChange, done: (String?) -> Unit) -> Unit = { _, _ -> },
    startPath: String = "/",
    startAsk: FileAsk? = null, // for the screenshots: a question already open
) {
    val context = LocalContext.current
    var path by rememberSaveable { mutableStateOf(startPath) }
    var reload by remember { mutableIntStateOf(0) }
    var entries by remember { mutableStateOf<List<FileTransfer.Entry>?>(null) }
    var loadedFor by remember { mutableStateOf<String?>(null) } // the folder [entries] are of
    var problem by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) } // how a change went, when it didn't
    var working by remember { mutableStateOf(false) } // a change on its way to the server
    var menuFor by remember { mutableStateOf<FileTransfer.Entry?>(null) }
    var ask by remember { mutableStateOf(startAsk) }
    var saving by remember { mutableStateOf<String?>(null) } // the file whose place is being chosen
    val save = rememberLauncherForActivityResult(SaveAs()) { target ->
        val p = saving
        saving = null
        if (target != null && p != null) onDownload(p, target)
    }
    fun named(name: String) = entries?.firstOrNull { it.name == name }
    val pick = rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { source ->
        if (source == null) return@rememberLauncherForActivityResult
        val (name, size) = pickedFile(context, source) ?: run {
            notice = context.getString(R.string.files_unreadable)
            return@rememberLauncherForActivityResult
        }
        val there = named(name)
        when {
            there == null -> onUpload(inside(path, name), source, size, false)
            there.folder -> notice = context.getString(R.string.files_exists)
            else -> ask = FileAsk.ReplaceUpload(source, name, size)
        }
    }
    fun change(c: FileChange) {
        working = true
        onChange(c) { problem ->
            working = false
            if (problem != null) notice = problem
            reload++
        }
    }
    LaunchedEffect(path, reload) {
        val asked = path
        // Another folder starts empty; the same one keeps what it shows until the new list is in
        if (loadedFor != asked) entries = null
        problem = null
        list(asked) { result ->
            // An answer for a folder no longer open is dropped
            if (path != asked) return@list
            loadedFor = asked
            result.onSuccess { found -> entries = found.sortedWith(compareBy({ !it.folder }, { it.name.lowercase() })) }
                .onFailure { problem = it.message.orEmpty() }
        }
    }
    LaunchedEffect(downloadResult) {
        if (downloadResult != null) {
            kotlinx.coroutines.delay(5000)
            onResultSeen()
        }
    }
    LaunchedEffect(notice) {
        if (notice != null) {
            kotlinx.coroutines.delay(5000)
            notice = null
        }
    }
    // An upload that ended (sent, stopped or failed) may have changed the folder
    var uploading by remember { mutableStateOf(false) }
    LaunchedEffect(download) {
        if (download?.upload == true) uploading = true
        else if (download == null && uploading) {
            uploading = false
            reload++
        }
    }
    fun up() {
        path = path.substringBeforeLast('/').ifEmpty { "/" }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        // Back goes up a folder, and closes at the top (inside the window: it has its own back)
        BackHandler { if (path != "/") up() else onDismiss() }
        Column(
            Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(8.dp)
                .clip(RoundedCornerShape(12.dp)).background(Ts.panel),
        ) {
            Row(Modifier.fillMaxWidth().background(Ts.card).padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.files_title), color = Ts.bright, fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
                    Text(channel.displayName.ifEmpty { channel.name }, color = Ts.muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Icon(
                    Icons.Rounded.Refresh, contentDescription = stringResource(R.string.files_refresh), tint = Ts.muted,
                    modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable { reload++ }.padding(6.dp),
                )
                Icon(
                    Icons.Filled.Close, contentDescription = stringResource(R.string.close), tint = Ts.muted,
                    modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable(onClick = onDismiss).padding(6.dp),
                )
            }
            // Where we are; the arrow goes up a folder
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                if (path != "/") {
                    Icon(
                        Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.files_up), tint = Ts.text,
                        modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable { up() }.padding(8.dp),
                    )
                } else {
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    path, color = Ts.text, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(start = 4.dp),
                )
                // One file on its way at a time, and nothing new while a change is
                val free = download == null && !working && problem == null && entries != null
                BarButton(Icons.Rounded.CreateNewFolder, stringResource(R.string.files_new_folder), free) { ask = FileAsk.NewFolder }
                BarButton(Icons.Rounded.UploadFile, stringResource(R.string.files_upload), free) { pick.launch(arrayOf("*/*")) }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                val shown = entries
                when {
                    problem != null -> Message(problem.orEmpty())
                    shown == null -> CircularProgressIndicator(color = Ts.accent, modifier = Modifier.align(Alignment.Center))
                    shown.isEmpty() -> Message(stringResource(R.string.files_empty))
                    else -> LazyColumn(Modifier.fillMaxSize()) {
                        items(shown, key = { (if (it.folder) "d:" else "f:") + it.name }) { e ->
                            fun saveIt() {
                                saving = inside(path, e.name)
                                save.launch(e.name to mimeOf(e.name))
                            }
                            FileRow(
                                e, menuOpen = menuFor == e,
                                onLongClick = { if (!working) menuFor = e },
                                onCloseMenu = { menuFor = null },
                                onClick = {
                                    if (e.folder) path = inside(path, e.name)
                                    else if (download == null) saveIt()
                                },
                            ) {
                                if (!e.folder && download == null) MenuItem(stringResource(R.string.files_download)) { menuFor = null; saveIt() }
                                MenuItem(stringResource(R.string.files_rename)) { menuFor = null; ask = FileAsk.Rename(e) }
                                MenuItem(stringResource(R.string.delete), Color(0xFFF87171)) { menuFor = null; ask = FileAsk.Delete(e) }
                            }
                        }
                    }
                }
            }
            // A download under way, or how the last one went
            if (download != null) {
                Column(Modifier.fillMaxWidth().background(Ts.card).padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(if (download.upload) R.string.files_uploading else R.string.files_downloading, download.name),
                            color = Ts.text, fontSize = 14.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                        )
                        PillButton(stringResource(R.string.cancel), onClick = onCancelDownload)
                    }
                    if (download.size > 0) {
                        LinearProgressIndicator(
                            progress = { download.done.toFloat() / download.size }, color = Ts.accent, trackColor = Ts.panel,
                            modifier = Modifier.fillMaxWidth().height(4.dp),
                        )
                    } else {
                        LinearProgressIndicator(color = Ts.accent, trackColor = Ts.panel, modifier = Modifier.fillMaxWidth().height(4.dp))
                    }
                }
            } else if (downloadResult != null || notice != null) {
                Text(
                    notice ?: downloadResult.orEmpty(), color = Ts.text, fontSize = 14.sp,
                    modifier = Modifier.fillMaxWidth().background(Ts.card)
                        .clickable { if (notice != null) notice = null else onResultSeen() }.padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }
        }
    }
    when (val a = ask) {
        null -> Unit
        FileAsk.NewFolder -> NameDialog(stringResource(R.string.files_new_folder), Icons.Rounded.CreateNewFolder, "", stringResource(R.string.files_create), onDismiss = { ask = null }) { name ->
            ask = null
            if (named(name) != null) notice = context.getString(R.string.files_exists)
            else change(FileChange.NewFolder(inside(path, name)))
        }
        is FileAsk.Rename -> NameDialog(stringResource(R.string.files_rename_title, a.entry.name), Icons.Rounded.DriveFileRenameOutline, a.entry.name, stringResource(R.string.files_rename), onDismiss = { ask = null }) { name ->
            ask = null
            val there = named(name)
            when {
                name == a.entry.name -> Unit
                there == null -> change(FileChange.Rename(inside(path, a.entry.name), inside(path, name)))
                // The server would put it over the other one without asking: only a file over a file, and asked first
                there.folder || a.entry.folder -> notice = context.getString(R.string.files_exists)
                else -> ask = FileAsk.ReplaceRename(a.entry, name)
            }
        }
        is FileAsk.Delete -> TsDialog(
            onDismiss = { ask = null }, title = stringResource(R.string.files_delete_title, a.entry.name), icon = Icons.Rounded.Delete, tint = Color(0xFFF87171),
            buttons = {
                PillButton(stringResource(R.string.cancel), onClick = { ask = null })
                PillButton(stringResource(R.string.delete), PillKind.DANGER) {
                    ask = null
                    change(FileChange.Delete(inside(path, a.entry.name)))
                }
            },
        ) {
            Text(stringResource(if (a.entry.folder) R.string.files_delete_folder else R.string.files_delete_file), color = Ts.text, fontSize = 15.sp)
        }
        is FileAsk.ReplaceUpload, is FileAsk.ReplaceRename -> {
            val name = if (a is FileAsk.ReplaceUpload) a.name else (a as FileAsk.ReplaceRename).to
            TsDialog(
                onDismiss = { ask = null }, title = stringResource(R.string.files_replace_title, name), icon = Icons.Rounded.SwapHoriz,
                buttons = {
                    PillButton(stringResource(R.string.cancel), onClick = { ask = null })
                    PillButton(stringResource(R.string.files_replace), PillKind.DANGER) {
                        ask = null
                        when (a) {
                            is FileAsk.ReplaceUpload -> onUpload(inside(path, a.name), a.source, a.size, true)
                            is FileAsk.ReplaceRename -> change(FileChange.Rename(inside(path, a.entry.name), inside(path, a.to)))
                        }
                    }
                },
            ) {
                Text(stringResource(R.string.files_replace_text), color = Ts.text, fontSize = 15.sp)
            }
        }
    }
}

/** A name for a new folder, or a new name for a file or folder. */
@Composable
private fun NameDialog(title: String, icon: ImageVector, start: String, action: String, onDismiss: () -> Unit, onDone: (String) -> Unit) {
    var name by remember { mutableStateOf(start) }
    val ok = nameOk(name)
    TsDialog(
        onDismiss = onDismiss, title = title, icon = icon,
        buttons = {
            PillButton(stringResource(R.string.cancel), onClick = onDismiss)
            PillButton(action, PillKind.PRIMARY, enabled = ok) { onDone(name.trim()) }
        },
    ) {
        TsField(name, { name = it.take(200) }, stringResource(R.string.files_name), imeAction = ImeAction.Done)
    }
}

@Composable
private fun BarButton(icon: ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
    Icon(
        icon, contentDescription = label, tint = if (enabled) Ts.text else Ts.muted.copy(alpha = 0.4f),
        modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable(enabled = enabled, onClick = onClick).padding(8.dp),
    )
}

@Composable
private fun MenuItem(text: String, color: Color = Ts.text, onClick: () -> Unit) {
    DropdownMenuItem(text = { Text(text, color = color) }, onClick = onClick)
}

@Composable
private fun androidx.compose.foundation.layout.BoxScope.Message(text: String) {
    Text(text, color = Ts.muted, fontSize = 14.sp, modifier = Modifier.align(Alignment.Center).padding(24.dp))
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileRow(
    e: FileTransfer.Entry,
    menuOpen: Boolean,
    onLongClick: () -> Unit,
    onCloseMenu: () -> Unit,
    onClick: () -> Unit,
    menu: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    val context = LocalContext.current
    val time = remember(e.time) {
        if (e.time > 0) java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT).format(java.util.Date(e.time)) else ""
    }
    val details = if (e.folder) time else listOf(android.text.format.Formatter.formatShortFileSize(context, e.size), time).filter { it.isNotEmpty() }.joinToString(" · ")
    // The menu opens under the held row
    Box {
        Row(
            Modifier.fillMaxWidth().combinedClickable(onLongClick = onLongClick, onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Icon(iconOf(e), contentDescription = if (e.folder) stringResource(R.string.files_folder) else null, tint = if (e.folder) Ts.accent else Ts.muted, modifier = Modifier.size(26.dp))
            Column(Modifier.weight(1f)) {
                Text(e.name, color = Ts.text, fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (details.isNotEmpty()) Text(details, color = Ts.muted, fontSize = 12.sp)
            }
            if (!e.folder) Icon(Icons.Rounded.Download, contentDescription = stringResource(R.string.files_download), tint = Ts.muted, modifier = Modifier.size(22.dp))
        }
        DropdownMenu(menuOpen, onDismissRequest = onCloseMenu, containerColor = Ts.card, offset = DpOffset(56.dp, 0.dp), content = menu)
    }
}
