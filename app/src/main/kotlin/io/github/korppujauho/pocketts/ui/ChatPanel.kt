package io.github.korppujauho.pocketts.ui

import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.foundation.layout.ime
import androidx.compose.material.icons.filled.Close
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.collectAsState
import io.github.korppujauho.pocketts.ChatDraftStore
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.korppujauho.pocketts.Session
import io.github.korppujauho.pocketts.TsFile
import io.github.korppujauho.pocketts.proto.Pictures
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Link
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.core.content.FileProvider
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import coil3.compose.AsyncImage
import coil3.memory.MemoryCache
import io.github.korppujauho.pocketts.PictureSizes
import io.github.korppujauho.pocketts.R
import io.github.korppujauho.pocketts.proto.Chat
import io.github.korppujauho.pocketts.proto.ServerModel
import kotlin.math.abs
import io.github.korppujauho.pocketts.proto.MessageText
import io.github.korppujauho.pocketts.proto.ChatOutbox
import androidx.compose.ui.text.withLink
import java.util.Date

/** The chat as WebTS shows it: tabs (the channel, the server, private chats), the lines, and the message box. */
@Composable
fun ChatPanel(
    state: Session.UiState,
    view: Chat.Key,
    onView: (Chat.Key) -> Unit,
    onCloseTab: (String) -> Unit,
    onSend: (Chat.Key, String) -> Unit,
    onPicture: (List<Uri>, String) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier,
    onPerson: ((ServerModel.Client) -> Unit)? = null,
    draftStore: ChatDraftStore = Session.chatDrafts,
) {
    val drafts by draftStore.state.collectAsState()
    Panel(modifier, header = {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val channelKey = Chat.Key.Channel(state.ownChannel)
            ChatTab("#", state.ownChannelName.ifEmpty { stringResource(R.string.chat_channel) }, view is Chat.Key.Channel, channelKey in state.unread) { onView(channelKey) }
            for (t in state.privateTabs) {
                val key = Chat.Key.Private(t.uid)
                ChatTab("@", t.name, view == key, key in state.unread, onClose = { onCloseTab(t.uid) }) { onView(key) }
            }
        }
    }) {
        val key = if (view is Chat.Key.Channel) Chat.Key.Channel(state.ownChannel) else view
        val entries = state.chat[key].orEmpty()
        // Each conversation its own list, so switching tabs opens the other one at its newest line too
        androidx.compose.runtime.key(key) {
            Messages(entries, emptyText = when (key) {
                is Chat.Key.Channel -> stringResource(R.string.chat_empty_channel)
                is Chat.Key.Private -> stringResource(R.string.chat_empty_private)
            }, state, onPerson)
        }
        val target = when (key) {
            is Chat.Key.Channel -> state.ownChannelName.ifEmpty { null }
            is Chat.Key.Private -> state.privateTabs.firstOrNull { it.uid == key.uid }?.name
        }
        val placeholder = when {
            target != null -> stringResource(R.string.chat_message_to, target)
            key is Chat.Key.Channel -> stringResource(R.string.chat_message_to_channel)
            else -> stringResource(R.string.chat_message_to_them)
        }
        state.chatDelivery.errors[key]?.let { error ->
            Text(serverChatErrorMessage(error), color = Ts.amber, modifier = Modifier.padding(horizontal = 14.dp))
        }
        if (key in state.chatDelivery.sending) Text("正在发送…", color = Ts.muted, modifier = Modifier.padding(horizontal = 14.dp))
        androidx.compose.runtime.key(key) { MessageBox(
            placeholder, enabled = state.phase == Session.Phase.CONNECTED,
            pictures = key is Chat.Key.Channel, sendingPicture = state.sendingPicture, progress = state.sendingProgress,
            text = drafts[ChatDraftStore.Location(state.serverKey, key)]?.text.orEmpty(),
            onText = { draftStore.edit(state.serverKey, key, it) },
            sendingText = key in state.chatDelivery.sending,
            onPicture = onPicture,
        ) { onSend(key, it) } }
    }
}

internal fun serverChatErrorMessage(code: String): String = when (code) {
    "CHAT_MESSAGE_TOO_LONG" -> "消息过长，请分段发送（每条最多 ${ChatOutbox.MAX_BYTES} 字节）。草稿已保留。"
    "CHAT_RECIPIENT_LEFT" -> "对方已离开服务器，暂时无法发送。草稿已保留。"
    "CHAT_CHANNEL_CHANGED" -> "当前频道已变化，请确认频道后重新发送。草稿已保留。"
    "CHAT_DELIVERY_UNCONFIRMED" -> "连接已中断，消息是否送达尚未确认。请先查看聊天记录，再决定是否重发。"
    "CHAT_SERVER_2568" -> "服务器拒绝发送：没有聊天权限。草稿已保留。"
    else -> "消息未能发送${if (code.startsWith("CHAT_SERVER_")) "（服务器错误 ${code.removePrefix("CHAT_SERVER_")}）" else ""}。草稿已保留。"
}

@Composable
private fun ChatTab(prefix: String, name: String, active: Boolean, unread: Boolean, onClose: (() -> Unit)? = null, onClick: () -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(6.dp)).background(if (active) Color(0x12FFFFFF) else Color.Transparent)
            .clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (prefix.isNotEmpty()) Text(prefix, color = Ts.muted, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
        Text(name, color = if (active) Ts.bright else Color(0xFF94A3B8), fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 1)
        if (unread) Box(Modifier.size(8.dp).clip(CircleShape).background(Ts.red))
        if (onClose != null) Icon(
            androidx.compose.material.icons.Icons.Filled.Close, contentDescription = stringResource(R.string.close), tint = Ts.muted,
            modifier = Modifier.clip(CircleShape).clickable(onClick = onClose).padding(2.dp).size(16.dp),
        )
    }
}

@Composable
private fun ColumnScope.Messages(entries: List<Chat.Entry>, emptyText: String, state: Session.UiState,
    onPerson: ((ServerModel.Client) -> Unit)?) {
    var viewing by remember { mutableStateOf<Viewing?>(null) }
    viewing?.let { PictureViewer(it.model, it.shown) { viewing = null } }
    val open = remember { { v: Viewing -> viewing = v } }
    // Opened at the newest line straight away; only lines coming in while it's open scroll there
    val list = rememberLazyListState(initialFirstVisibleItemIndex = (entries.size - 1).coerceAtLeast(0))
    var opened by remember { mutableStateOf(false) }
    LaunchedEffect(entries.size) {
        if (entries.isEmpty()) return@LaunchedEffect
        if (opened) list.animateScrollToItem(entries.size - 1) else list.scrollToItem(entries.size - 1)
        opened = true
    }
    if (entries.isEmpty()) {
        Box(Modifier.fillMaxWidth().weight(1f).padding(top = 40.dp), contentAlignment = Alignment.TopCenter) {
            Text(emptyText, color = Ts.muted, fontSize = 14.sp)
        }
        return
    }
    val context = LocalContext.current
    val timeFormat = remember { android.text.format.DateFormat.getTimeFormat(context) }
    LazyColumn(
        Modifier.fillMaxWidth().weight(1f),
        state = list,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(entries, key = { it.id }) { e ->
            val time = lineTime(e.time, chatNow(), timeFormat.format(Date(e.time)))
            if (e.event != null) {
                EventLine(time, e.event!!)
            } else {
                // A line from before has no client id: our own is ours today too. The line gets only what it shows,
                // so it isn't drawn again each time someone starts or stops talking.
                val sender = state.clientByUid(e.senderUid) ?: state.client(if (e.outgoing && e.senderId == 0) state.ownId else e.senderId)
                MessageLine(time, e, sender, state.serverKey, open, onPerson)
            }
        }
    }
}

/** A picture opened on the whole screen, and what the chat showed of it (shown until the full one has loaded). */
private class Viewing(val model: Any, val shown: MemoryCache.Key?)

private val PICTURE_LINK = Regex("""\.(jpe?g|gif|png|webp)(\?.*)?$""", RegexOption.IGNORE_CASE)

/** Pictures sent together at most (one message each). */
private const val MAX_PICTURES = 10

/**
 * A picture in the chat, only as big as the chat shows it (a tap opens the whole picture). Its place is kept for it
 * from its size the time before, so the chat doesn't jump when it loads.
 */
@Composable
private fun ChatPicture(model: Any, sizeKey: String, description: String?, onOpen: (Viewing) -> Unit) {
    var size by remember(sizeKey) { mutableStateOf(PictureSizes.get(sizeKey)) }
    var shown by remember(sizeKey) { mutableStateOf<MemoryCache.Key?>(null) }
    var loaded by remember(sizeKey) { mutableStateOf(false) }
    var failed by remember(sizeKey) { mutableStateOf(false) }
    if (failed) return
    val known = size
    // The first time a 4:3 place; a small picture isn't made bigger than it is
    val ratio = known?.let { it.width.toFloat() / it.height } ?: (4f / 3f)
    val maxHeight = known?.let { minOf(260.dp, with(LocalDensity.current) { it.height.toDp() }) } ?: 260.dp
    AsyncImage(
        model, contentDescription = description, contentScale = ContentScale.Fit,
        onSuccess = { s ->
            loaded = true
            shown = s.result.memoryCacheKey
            val w = s.result.image.width
            val h = s.result.image.height
            // Kept when the shape is new (a few pixels' difference from being made smaller doesn't count)
            if (w > 0 && h > 0 && (known == null || abs(w.toFloat() / h - ratio) > 0.02f)) {
                PictureSizes.put(sizeKey, w, h)
                size = PictureSizes.get(sizeKey)
            }
        },
        onError = { failed = true },
        modifier = Modifier.heightIn(max = maxHeight).aspectRatio(ratio, matchHeightConstraintsFirst = true)
            .clip(RoundedCornerShape(6.dp)).then(if (loaded) Modifier else Modifier.background(Ts.card))
            .clickable { onOpen(Viewing(model, shown)) },
    )
}

/** Now, for the lines' days (the screenshots set their own). */
var chatNow: () -> Long = { System.currentTimeMillis() }

/**
 * A line's time as WebTS and the TeamSpeak app show it, so lines from the days before read as such: [clock] (the time
 * in the phone's format) today, "21:36 - Mon" in the six days before, "21:36 - 6 Oct" earlier.
 */
fun lineTime(
    time: Long, now: Long, clock: String,
    zone: java.time.ZoneId = java.time.ZoneId.systemDefault(), locale: java.util.Locale = java.util.Locale.getDefault(),
): String {
    val day = java.time.Instant.ofEpochMilli(time).atZone(zone).toLocalDate()
    val today = java.time.Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val pattern = when {
        !day.isBefore(today) -> return clock
        !day.isBefore(today.minusDays(6)) -> "EEE"
        else -> "d MMM"
    }
    return "$clock - ${day.format(java.time.format.DateTimeFormatter.ofPattern(pattern, locale))}"
}

@Composable
private fun EventLine(time: String, parts: List<Chat.Part>) {
    Text(
        buildAnnotatedString {
            withStyle(SpanStyle(color = Ts.faint, fontSize = 12.sp)) { append(time) }
            append("  ")
            for (p in parts) {
                if (p.bold) withStyle(SpanStyle(color = Ts.text, fontWeight = FontWeight.SemiBold)) { append(p.text) }
                else append(p.text)
            }
        },
        color = Ts.event, fontSize = 13.5.sp,
        modifier = Modifier.padding(start = 12.dp, end = 10.dp),
    )
}

@Composable
private fun MessageLine(time: String, e: Chat.Entry, sender: ServerModel.Client?, server: String, onPicture: (Viewing) -> Unit,
    onPerson: ((ServerModel.Client) -> Unit)?) {
    // A file from the TeamSpeak app (through myTeamSpeak): one short line instead of its note
    val tsFile = remember(e.text) { Pictures.teamSpeakFile(e.text) }
    val (pictures, rest) = remember(e.text) { if (tsFile != null) emptyList<Pictures.ChannelPicture>() to "" else Pictures.channelPictures(e.text) }
    val text = remember(rest) { formatted(rest) }
    val uri = LocalUriHandler.current
    val system = stringResource(R.string.chat_system)
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(Color(0x05FFFFFF))
            .drawBehind { drawLine(Ts.accent, Offset(1f, 0f), Offset(1f, size.height), strokeWidth = 2.dp.toPx()) }
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(color = Ts.faint, fontSize = 12.sp)) { append(time) }
                append("  ")
                appendInlineContent("avatar", "•")
                append(" ")
                withStyle(SpanStyle(color = Ts.sender, fontWeight = FontWeight.Bold)) {
                    if (sender != null && onPerson != null) {
                        withLink(androidx.compose.ui.text.LinkAnnotation.Clickable("person", linkInteractionListener = { onPerson(sender) })) {
                            append(e.senderName.ifEmpty { system } + ":")
                        }
                    } else append(e.senderName.ifEmpty { system } + ":")
                }
                // With pictures, the words go under them (as in the TeamSpeak app)
                if (pictures.isEmpty()) {
                    append("  ")
                    append(text)
                }
            },
            inlineContent = mapOf(
                "avatar" to InlineTextContent(Placeholder(20.sp, 20.sp, PlaceholderVerticalAlign.TextCenter)) {
                    Box(Modifier.size(20.dp).then(if (sender != null && onPerson != null) Modifier.clickable { onPerson(sender) } else Modifier)) {
                        PersonAvatar(sender, server, 20)
                    }
                },
            ),
            color = Ts.text, fontSize = 15.sp,
        )
        if (tsFile != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(
                    if (tsFile.isImage) Icons.Filled.Image else Icons.Filled.AttachFile, contentDescription = null,
                    tint = Ts.muted, modifier = Modifier.size(16.dp),
                )
                Text(tsFile.text, color = Ts.muted, fontSize = 13.sp, fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)
            }
        }
        // Pictures from the channel's files, as the TeamSpeak app shows them
        for (p in pictures) {
            val file = TsFile.picture(server, p)
            ChatPicture(file, file.key, p.name, onPicture)
        }
        if (pictures.isNotEmpty() && rest.isNotEmpty()) Text(text, color = Ts.text, fontSize = 15.sp)
        // A preview of the first link: a direct picture, else a small "Open" card
        val link = remember(rest) { MessageText.parse(rest).firstNotNullOfOrNull { it.link } }
        if (link != null) {
            if (PICTURE_LINK.containsMatchIn(link)) {
                ChatPicture(link, link, null, onPicture)
            } else {
                val host = remember(link) { runCatching { java.net.URI(link).host }.getOrNull() }
                if (host != null) {
                    Row(
                        Modifier.clip(RoundedCornerShape(6.dp)).background(Ts.card).clickable { uri.openUri(link) }
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(Icons.Filled.Link, contentDescription = null, tint = Ts.muted, modifier = Modifier.size(16.dp))
                        Text(stringResource(R.string.chat_link_open, host), color = Ts.text, fontSize = 13.sp)
                    }
                }
            }
        }
    }
}

/** A message's styles and links (tapping a link opens it in the browser). */
fun formatted(text: String): AnnotatedString = buildAnnotatedString {
    for (s in MessageText.parse(text)) {
        val style = SpanStyle(
            fontWeight = if (s.bold) FontWeight.Bold else null,
            fontStyle = if (s.italic) FontStyle.Italic else null,
            textDecoration = when {
                s.underline && s.strike -> TextDecoration.combine(listOf(TextDecoration.Underline, TextDecoration.LineThrough))
                s.underline -> TextDecoration.Underline
                s.strike -> TextDecoration.LineThrough
                else -> null
            },
        )
        val link = s.link
        if (link != null) {
            withLink(LinkAnnotation.Url(link, TextLinkStyles(SpanStyle(color = Ts.talking, textDecoration = TextDecoration.Underline)))) {
                withStyle(style) { append(s.text) }
            }
        } else {
            withStyle(style) { append(s.text) }
        }
    }
}

@Composable
private fun MessageBox(
    placeholder: String,
    enabled: Boolean,
    pictures: Boolean,
    sendingPicture: Boolean,
    progress: Pair<Int, Int>?,
    text: String,
    onText: (String) -> Unit,
    sendingText: Boolean,
    onPicture: (List<Uri>, String) -> Unit,
    onSend: (String) -> Unit,
) {
    var pending by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var menu by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val resources = androidx.compose.ui.platform.LocalResources.current
    // More can be added to those already chosen, up to MAX_PICTURES
    fun add(uris: List<Uri>) {
        val all = (pending + uris).distinct()
        if (all.size > MAX_PICTURES) {
            android.widget.Toast.makeText(context, resources.getQuantityString(R.plurals.chat_pictures_max, MAX_PICTURES, MAX_PICTURES), android.widget.Toast.LENGTH_SHORT).show()
        }
        pending = all.take(MAX_PICTURES)
    }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(MAX_PICTURES)) { uris -> add(uris) }
    var photoUri by remember { mutableStateOf<Uri?>(null) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok -> if (ok) photoUri?.let { add(listOf(it)) } }
    fun takePhoto() {
        val dir = java.io.File(context.cacheDir, "photos").apply { mkdirs() }
        val file = java.io.File(dir, "photo-${System.currentTimeMillis()}.jpg")
        photoUri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
        camera.launch(photoUri!!)
    }
    // The camera app may only be opened once the camera is allowed (the app also uses it for streams)
    val askCamera = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) takePhoto()
        else android.widget.Toast.makeText(context, resources.getString(R.string.chat_camera_needed), android.widget.Toast.LENGTH_LONG).show()
    }
    if (progress != null) {
        Text(
            stringResource(R.string.chat_sending_picture, progress.first, progress.second), color = Ts.muted, fontSize = 13.sp,
            modifier = Modifier.fillMaxWidth().background(Ts.panel).padding(start = 14.dp, top = 10.dp),
        )
    }
    if (pending.isNotEmpty()) {
        // Smaller while the keyboard is open, so the text stays in view
        val keyboard = androidx.compose.foundation.layout.WindowInsets.ime.getBottom(androidx.compose.ui.platform.LocalDensity.current) > 0
        Row(
            Modifier.fillMaxWidth().background(Ts.panel).horizontalScroll(rememberScrollState()).padding(start = 12.dp, top = 12.dp, end = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for (uri in pending) {
                Box(Modifier.size(if (keyboard) 56.dp else 84.dp)) {
                    AsyncImage(
                        uri, contentDescription = stringResource(R.string.chat_attached_picture), contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(10.dp)).border(1.dp, Pop.edge, RoundedCornerShape(10.dp)),
                    )
                    Box(
                        Modifier.align(Alignment.TopEnd).padding(4.dp).size(24.dp).clip(CircleShape).background(Color(0xCC000000))
                            .clickable { pending = pending - uri },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(androidx.compose.material.icons.Icons.Filled.Close, contentDescription = stringResource(R.string.chat_remove_picture), tint = Color.White, modifier = Modifier.size(16.dp))
                    }
                }
            }
        }
    }
    val canSend = enabled && !sendingText && (text.isNotBlank() || (pending.isNotEmpty() && !sendingPicture))
    fun send() {
        if (!canSend) return
        val chosen = pending
        if (chosen.isNotEmpty()) {
            if (sendingPicture) return
            onPicture(chosen, text)
            pending = emptyList()
            onText("")
        } else if (text.isNotBlank()) {
            onSend(text)
        }
    }
    Row(
        Modifier.fillMaxWidth().background(Ts.panel).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (pictures) Box {
            Box(
                Modifier.size(46.dp).clip(RoundedCornerShape(8.dp)).background(Ts.input)
                    .clickable(enabled = enabled && !sendingPicture && !sendingText) { menu = true },
                contentAlignment = Alignment.Center,
            ) {
                if (sendingPicture) CircularProgressIndicator(color = Ts.accent, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                else Icon(Icons.Filled.Image, contentDescription = stringResource(R.string.chat_send_picture), tint = Ts.text)
            }
            DropdownMenu(menu, onDismissRequest = { menu = false }, containerColor = Ts.card) {
                DropdownMenuItem(text = { Text(stringResource(R.string.chat_choose_pictures), color = Ts.text) }, onClick = {
                    menu = false
                    pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                })
                DropdownMenuItem(text = { Text(stringResource(R.string.chat_take_photo), color = Ts.text) }, onClick = {
                    menu = false
                    val allowed = androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.CAMERA) ==
                        android.content.pm.PackageManager.PERMISSION_GRANTED
                    if (allowed) takePhoto() else askCamera.launch(android.Manifest.permission.CAMERA)
                })
            }
        }
        Box(
            Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).background(Ts.input).padding(horizontal = 16.dp, vertical = 13.dp),
        ) {
            if (text.isEmpty()) Text(placeholder, color = Color(0xFF8E9297), fontSize = 15.sp, maxLines = 1)
            BasicTextField(
                value = text, onValueChange = onText, enabled = enabled,
                textStyle = TextStyle(color = Color.White, fontSize = 15.sp),
                cursorBrush = SolidColor(Ts.accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { send() }),
                maxLines = 5,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Box(
            Modifier.size(46.dp).clip(RoundedCornerShape(8.dp)).background(if (canSend) Ts.accent else Ts.input)
                .clickable(enabled = canSend) { send() },
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = stringResource(R.string.chat_send), tint = if (canSend) Color.White else Ts.muted)
        }
    }
}
