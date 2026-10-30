package io.github.korppujauho.pocketts.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.ui.draw.drawBehind
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import io.github.korppujauho.pocketts.R
import io.github.korppujauho.pocketts.proto.ChannelEdits
import io.github.korppujauho.pocketts.proto.ServerModel
import kotlinx.coroutines.delay

/** Edit mode's header (Settings › UI › Show admin functions, then holding a channel): Add and Done. */
@Composable
fun ChannelEditHeader(onAdd: () -> Unit, onDone: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            stringResource(R.string.channels_editing), color = Ts.title, fontWeight = FontWeight.SemiBold, fontSize = 15.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
        )
        HeaderPill(Icons.Filled.Add, stringResource(R.string.channels_add), Ts.text, onAdd)
        Spacer(Modifier.width(8.dp))
        HeaderPill(null, stringResource(R.string.channels_done), Color.White, onDone, Ts.accent)
    }
}

@Composable
private fun HeaderPill(icon: androidx.compose.ui.graphics.vector.ImageVector?, text: String, color: Color, onClick: () -> Unit, bg: Color = Color(0x14FFFFFF)) {
    Row(
        Modifier.clip(RoundedCornerShape(16.dp)).background(bg).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
        }
        Text(text, color = color, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
    }
}

/**
 * The channels in edit mode: they wiggle like a launcher's icons. Holding one and dragging moves it among the
 * channels next to it (its sub-channels go with it); tapping one opens its settings. People aren't listed, only
 * counted, so the tree is short.
 */
@Composable
fun ChannelEditList(
    items: List<ChannelItem>,
    server: String,
    onTap: (ServerModel.Channel) -> Unit,
    onReorder: (ServerModel.Channel, after: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val list = rememberLazyListState()
    var dragged by remember { mutableStateOf<Int?>(null) }
    var dragY by remember { mutableFloatStateOf(0f) }
    val edge = with(LocalDensity.current) { 48.dp.toPx() }

    // Where the dragged channel would go: (its siblings in order, how many come before it)
    fun target(): Pair<List<ChannelItem>, Int>? {
        val id = dragged ?: return null
        val item = items.firstOrNull { it.node.channel.id == id } ?: return null
        val info = list.layoutInfo.visibleItemsInfo.firstOrNull { it.key == id } ?: return null
        val center = info.offset + dragY + info.size / 2f
        val myIndex = items.indexOf(item)
        val siblings = items.filter { it.node.channel.parent == item.node.channel.parent && it.node.channel.id != id }
        val before = siblings.count { s ->
            val i = list.layoutInfo.visibleItemsInfo.firstOrNull { it.key == s.node.channel.id }
            if (i != null) i.offset + i.size / 2f < center else items.indexOf(s) < myIndex
        }
        return siblings to before
    }

    // Near the top or bottom while dragging: the list scrolls, and the channel stays under the finger
    LaunchedEffect(dragged) {
        while (dragged != null) {
            val info = list.layoutInfo.visibleItemsInfo.firstOrNull { it.key == dragged }
            if (info != null) {
                val top = info.offset + dragY
                val step = when {
                    top < list.layoutInfo.viewportStartOffset + edge -> -12f
                    top + info.size > list.layoutInfo.viewportEndOffset - edge -> 12f
                    else -> 0f
                }
                if (step != 0f) dragY += list.scrollBy(step)
            }
            delay(16)
        }
    }

    val wiggle = rememberInfiniteTransition(label = "wiggle")
    val angle by wiggle.animateFloat(-0.7f, 0.7f, infiniteRepeatable(tween(150), RepeatMode.Reverse), label = "angle")
    val (dropSiblings, dropBefore) = target() ?: (emptyList<ChannelItem>() to 0)
    // The line showing where it would land: above the next sibling, or under the last one's sub-channels
    val lineAbove = dropSiblings.getOrNull(dropBefore)?.node?.channel?.id
    val lineBelow = if (dragged != null && lineAbove == null) dropSiblings.lastOrNull()?.let { last ->
        val under = ChannelEdits.subtree(items.map { it.node.channel }, last.node.channel.id)
        items.lastOrNull { it.node.channel.id in under }?.node?.channel?.id
    } else null
    val accent = Ts.accent

    LazyColumn(modifier, state = list, contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item(key = "hint") {
            Text(stringResource(R.string.channels_edit_hint), color = Ts.muted, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 4.dp))
        }
        items(items, key = { it.node.channel.id }) { item ->
            val ch = item.node.channel
            val moving = dragged == ch.id
            val tilt = if (items.indexOf(item) % 2 == 0) angle else -angle
            val shape = RoundedCornerShape(8.dp)
            Box(
                Modifier.fillMaxWidth().padding(start = (item.depth * 15).dp).zIndex(if (moving) 1f else 0f)
                    .graphicsLayer {
                        rotationZ = if (moving) 0f else tilt
                        if (moving) {
                            translationY = dragY
                            scaleX = 1.03f
                            scaleY = 1.03f
                        }
                    }
                    .then(if (moving) Modifier.shadow(12.dp, shape) else Modifier)
                    .drawWithContent {
                        drawContent()
                        val y = when (ch.id) {
                            lineAbove -> -4.dp.toPx()
                            lineBelow -> size.height + 4.dp.toPx()
                            else -> null
                        }
                        if (y != null) drawLine(accent, Offset(0f, y), Offset(size.width, y), strokeWidth = 3.dp.toPx())
                    }
                    .clip(shape).background(if (moving) Color(0xFF2A2C31) else Ts.card).border(1.dp, Color(0x0DFFFFFF), shape)
                    .pointerInput(ch.id) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = { dragged = ch.id; dragY = 0f },
                            onDrag = { change, amount -> change.consume(); dragY += amount.y },
                            onDragEnd = {
                                target()?.let { (siblings, before) ->
                                    val after = if (before == 0) 0 else siblings[before - 1].node.channel.id
                                    if (after != ch.order) onReorder(ch, after)
                                }
                                dragged = null
                                dragY = 0f
                            },
                            onDragCancel = { dragged = null; dragY = 0f },
                        )
                    },
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { ChannelTitle(ch, server) { onTap(ch) } }
                    val people = item.node.clients.size
                    if (people > 0) {
                        Icon(Icons.Filled.Person, contentDescription = pluralStringResource(R.plurals.channel_people, people, people), tint = Ts.muted, modifier = Modifier.size(14.dp))
                        Text("$people", color = Ts.muted, fontSize = 12.sp, modifier = Modifier.padding(start = 2.dp, end = 12.dp))
                    }
                }
            }
        }
    }
}

/**
 * A new channel's settings, or a channel's own ([channel]). [description] is the channel's description as it comes
 * from the server (asked for when this opens), shown unless something was already typed.
 */
@Composable
fun ChannelDialog(
    channel: ServerModel.Channel?,
    start: ChannelEdits.Settings,
    tree: List<ServerModel.Node>,
    description: String,
    onSave: (ChannelEdits.Settings) -> Unit,
    onDelete: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    var s by remember { mutableStateOf(start) }
    var typedDescription by remember { mutableStateOf(false) }
    LaunchedEffect(description) { if (!typedDescription && channel != null) s = s.copy(description = description) }
    var maxText by remember { mutableStateOf(start.maxClients.takeIf { it >= 0 }?.toString() ?: "") }
    var choosingParent by remember { mutableStateOf(false) }
    // A password turned on for a channel without one needs the password itself
    val needsPassword = s.password && s.newPassword.isEmpty() && (channel == null || !channel.hasPassword)
    val maxOk = s.maxClients < 0 || maxText.toIntOrNull() != null
    val ok = s.name.isNotBlank() && !needsPassword && maxOk

    TsDialog(
        onDismiss = onDismiss,
        title = channel?.displayName?.ifBlank { channel.name } ?: stringResource(R.string.channel_new_title),
        icon = Icons.Rounded.Tune,
        subtitle = stringResource(if (channel == null) R.string.channel_new_subtitle else R.string.channel_edit_subtitle),
        buttons = {
            if (onDelete != null) {
                PillButton(stringResource(R.string.delete), PillKind.DANGER_TEXT, onClick = onDelete)
                Spacer(Modifier.weight(1f))
            }
            PillButton(stringResource(R.string.cancel), onClick = onDismiss)
            PillButton(stringResource(if (channel == null) R.string.channel_create else R.string.save), PillKind.PRIMARY, enabled = ok) {
                onSave(s.copy(maxClients = if (s.maxClients < 0) -1 else maxText.toInt()))
            }
        },
    ) {
        TsField(s.name, { s = s.copy(name = it) }, stringResource(R.string.channel_name))
        TsField(
            s.description, { typedDescription = true; s = s.copy(description = it) }, stringResource(R.string.channel_description),
            singleLine = false,
        )

        Label(stringResource(R.string.channel_kind))
        // One under another: three long names don't fit side by side on a phone
        for ((kind, label) in listOf(
            ChannelEdits.Kind.PERMANENT to R.string.channel_kind_permanent,
            ChannelEdits.Kind.SEMI_PERMANENT to R.string.channel_kind_semi,
            ChannelEdits.Kind.TEMPORARY to R.string.channel_kind_temporary,
        )) Radio(stringResource(label), s.kind == kind) { s = s.copy(kind = kind) }
        Text(
            stringResource(
                when (s.kind) {
                    ChannelEdits.Kind.PERMANENT -> R.string.channel_kind_permanent_desc
                    ChannelEdits.Kind.SEMI_PERMANENT -> R.string.channel_kind_semi_desc
                    ChannelEdits.Kind.TEMPORARY -> R.string.channel_kind_temporary_desc
                },
            ),
            color = Ts.muted, fontSize = 13.sp,
        )

        Check(stringResource(R.string.channel_password_on), s.password) { s = s.copy(password = it) }
        if (s.password) {
            TsField(
                s.newPassword, { s = s.copy(newPassword = it) }, stringResource(R.string.channel_password), password = true,
                placeholder = if (channel?.hasPassword == true) stringResource(R.string.channel_password_keep) else null,
            )
        }

        Label(stringResource(R.string.channel_codec))
        Segments(listOf(stringResource(R.string.channel_codec_voice), stringResource(R.string.channel_codec_music)), if (s.codec == 5) 1 else 0) {
            s = s.copy(codec = if (it == 1) 5 else 4)
        }
        Text(stringResource(R.string.channel_quality, s.quality), color = Ts.text, fontSize = 14.sp)
        TsSlider(s.quality.toFloat(), 0f..10f, 9) { s = s.copy(quality = Math.round(it)) }

        Check(stringResource(R.string.channel_unlimited), s.maxClients < 0) { s = s.copy(maxClients = if (it) -1 else 0) }
        if (s.maxClients >= 0) {
            TsField(maxText, { t -> maxText = t.filter { it.isDigit() }.take(5) }, stringResource(R.string.channel_max_users), keyboard = KeyboardType.Number)
        }

        // Where it is in the tree: tap to choose another place (in a window of its own; not inside itself)
        val parentName = if (s.parent == 0) stringResource(R.string.channel_top_level)
        else flattenTree(tree).firstOrNull { it.node.channel.id == s.parent }?.node?.channel?.let { it.displayName.ifBlank { it.name } } ?: "?"
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { choosingParent = true }.padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.channel_parent), color = Ts.muted, fontSize = 14.sp)
            Spacer(Modifier.width(10.dp))
            Text(parentName, color = Ts.text, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text(stringResource(R.string.channel_parent_change), color = Ts.accent, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        }
        if (choosingParent) {
            LocationDialog(
                tree, s.parent, channel, s.name.trim().ifEmpty { stringResource(R.string.channel_new_title) },
                onPick = { s = s.copy(parent = it); choosingParent = false },
                onDismiss = { choosingParent = false },
            )
        }
    }
}

@Composable
private fun Label(text: String) = Text(text, color = Ts.text, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)

/**
 * Choosing where a channel goes: the channels as bars, as in the list. Tapping one picks it, and a dashed bar named
 * like the channel shows where it will land (last inside the one picked). A channel that's being moved, and what's
 * under it, are dimmed: it can't go inside itself. Nothing changes until Done.
 */
@Composable
internal fun LocationDialog(
    tree: List<ServerModel.Node>,
    start: Int,
    moving: ServerModel.Channel?,
    name: String,
    onPick: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var parent by remember { androidx.compose.runtime.mutableIntStateOf(start) }
    val rows = remember(tree) { flattenTree(tree) }
    val all = remember(rows) { rows.map { it.node.channel } }
    val moved = remember(all, moving) { moving?.let { ChannelEdits.subtree(all, it.id) }.orEmpty() }
    // The preview's place: after everything already inside the chosen channel (or at the end of the top level)
    val inside = if (parent == 0) all.map { it.id }.toSet() else ChannelEdits.subtree(all, parent)
    val previewAfter = rows.indexOfLast { it.node.channel.id in inside && it.node.channel.id !in moved }
    val previewDepth = if (parent == 0) 0 else (rows.firstOrNull { it.node.channel.id == parent }?.depth ?: -1) + 1
    TsDialog(
        onDismiss = onDismiss, title = stringResource(R.string.channel_parent_title), icon = Icons.Rounded.AccountTree,
        buttons = {
            PillButton(stringResource(R.string.cancel), onClick = onDismiss)
            PillButton(stringResource(R.string.channels_done), PillKind.PRIMARY) { onPick(parent) }
        },
    ) {
        Column(Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            LocationBar(stringResource(R.string.channel_top_level), 0, top = true, selected = parent == 0) { parent = 0 }
            if (previewAfter < 0) PreviewBar(name, previewDepth)
            rows.forEachIndexed { i, row ->
                val c = row.node.channel
                // A spacer (a divider) can't hold channels: shown dimmed, as its text or a line
                val spacer = c.spacer
                val dim = c.id in moved || spacer != null
                LocationBar(
                    if (spacer != null) spacer.text.ifBlank { "———" } else c.displayName.ifBlank { c.name }, row.depth,
                    selected = c.id == parent, dim = dim, icon = spacer == null,
                    onClick = if (dim) null else ({ parent = c.id }),
                )
                if (i == previewAfter) PreviewBar(name, previewDepth)
            }
        }
    }
}

@Composable
private fun LocationBar(
    name: String, depth: Int, top: Boolean = false, selected: Boolean, dim: Boolean = false, icon: Boolean = true, onClick: (() -> Unit)?,
) {
    val shape = RoundedCornerShape(8.dp)
    Row(
        Modifier.fillMaxWidth().padding(start = (depth * 15).dp).clip(shape)
            .background(if (selected) Ts.accent.copy(alpha = 0.16f) else Ts.card)
            .border(1.dp, if (selected) Ts.accent else Color(0x0DFFFFFF), shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon) {
            Icon(
                if (top) Icons.Filled.Dns else Icons.Filled.MusicNote, contentDescription = null,
                tint = if (dim) Ts.faint else Ts.accent, modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(8.dp))
        }
        Text(
            name, color = if (dim) Ts.faint else Ts.title, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

// The channel in its new place: a dashed bar in the accent colour
@Composable
private fun PreviewBar(name: String, depth: Int) {
    val accent = Ts.accent
    Row(
        Modifier.fillMaxWidth().padding(start = (depth * 15).dp).clip(RoundedCornerShape(8.dp)).background(accent.copy(alpha = 0.08f))
            .drawBehind {
                drawRoundRect(
                    accent, cornerRadius = androidx.compose.ui.geometry.CornerRadius(8.dp.toPx()),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(
                        width = 1.5.dp.toPx(), pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(8.dp.toPx(), 5.dp.toPx())),
                    ),
                )
            }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.MusicNote, contentDescription = null, tint = accent, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        Text(name, color = accent, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Deleting a channel: always asked, saying what else goes with it. */
@Composable
fun DeleteChannelDialog(channel: ServerModel.Channel, subChannels: Int, people: Int, onDelete: () -> Unit, onDismiss: () -> Unit) {
    TsDialog(
        onDismiss = onDismiss, title = stringResource(R.string.channel_delete_title, channel.displayName.ifBlank { channel.name }),
        icon = Icons.Rounded.DeleteForever, tint = Ts.red, subtitle = stringResource(R.string.channel_delete_subtitle),
        buttons = {
            PillButton(stringResource(R.string.cancel), onClick = onDismiss)
            PillButton(stringResource(R.string.delete), PillKind.DANGER, onClick = onDelete)
        },
    ) {
        if (subChannels > 0) Text(pluralStringResource(R.plurals.channel_delete_subs, subChannels, subChannels), color = Ts.text, fontSize = 14.sp)
        Text(
            if (people > 0) pluralStringResource(R.plurals.channel_delete_people, people, people) else stringResource(R.string.channel_delete_empty),
            color = Ts.text, fontSize = 14.sp,
        )
    }
}
