package io.github.korppujauho.pocketts.ui

import androidx.compose.ui.layout.layout
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.HeadsetOff
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.outlined.MicExternalOff
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.korppujauho.pocketts.proto.ServerModel
import io.github.korppujauho.pocketts.proto.Pictures
import io.github.korppujauho.pocketts.TsFile
import io.github.korppujauho.pocketts.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale

/** One channel box in the list: the channel, its people, and how deep it is. */
data class ChannelItem(val node: ServerModel.Node, val depth: Int)

fun flattenTree(nodes: List<ServerModel.Node>, depth: Int = 0, out: MutableList<ChannelItem> = mutableListOf()): List<ChannelItem> {
    for (n in nodes) {
        out.add(ChannelItem(n, depth))
        flattenTree(n.children, depth + 1, out)
    }
    return out
}

/** What the tree needs to know about the people in it, beyond the server's data. */
data class TreeContext(
    val ownId: Int,
    val ownChannel: Int,
    val talking: Set<Int>,
    val server: String = "",
    val badges: Map<Int, List<Long>> = emptyMap(),
    val onWatch: ((ServerModel.Client) -> Unit)? = null,
    val canTalk: (ServerModel.Client) -> Boolean,
)

/** The channel tree as WebTS draws it: a box per channel with its people inside, sub-channels indented. */
@Composable
fun ChannelTree(
    items: List<ChannelItem>,
    ctx: TreeContext,
    onChannel: (ServerModel.Channel) -> Unit,
    onPerson: (ServerModel.Client) -> Unit,
    modifier: Modifier = Modifier,
    onLongChannel: ((ServerModel.Channel) -> Unit)? = null, // holding a channel (admin functions: edit mode)
) {
    LazyColumn(
        modifier,
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(items, key = { it.node.channel.id }) { item ->
            ChannelBox(item, ctx, onChannel, onPerson, onLongChannel)
        }
    }
}

@Composable
private fun ChannelBox(
    item: ChannelItem,
    ctx: TreeContext,
    onChannel: (ServerModel.Channel) -> Unit,
    onPerson: (ServerModel.Client) -> Unit,
    onLongChannel: ((ServerModel.Channel) -> Unit)?,
) {
    val ch = item.node.channel
    val own = ch.id == ctx.ownChannel
    val shape = RoundedCornerShape(18.dp)
    var box = Modifier.fillMaxWidth()
    if (item.depth > 0) {
        box = box.padding(start = (item.depth * 15).dp)
    }
    box = if (own) {
        box.shadow(6.dp, shape, ambientColor = Ts.accent.copy(alpha = 0.3f), spotColor = Ts.accent.copy(alpha = 0.3f))
            .border(1.dp, Ts.accent.copy(alpha = 0.5f), shape)
    } else {
        box.border(1.dp, Color(0x05FFFFFF), shape)
    }
    val sub = item.depth > 0
    Column(
        box.clip(shape).background(Ts.card).drawBehind {
            if (sub) drawRect(Color(0x0DFFFFFF), size = Size(2.dp.toPx(), size.height)) // a sub-channel's edge
        },
    ) {
        ChannelTitle(ch, ctx.server, onLongClick = onLongChannel?.let { { it(ch) } }) { onChannel(ch) }
        for (c in item.node.clients) PersonRow(c, ctx) { onPerson(c) }
    }
}

@Composable
internal fun ChannelTitle(ch: ServerModel.Channel, server: String, onLongClick: (() -> Unit)? = null, onClick: () -> Unit) {
    val spacer = ch.spacer
    val banner: Any? = ch.bannerUrl.takeIf { it.isNotEmpty() }?.let { url ->
        if (url.startsWith("https://", ignoreCase = true)) url
        else Pictures.channelPicture(url)?.let { TsFile.picture(server, it) }
    }
    Box(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        if (banner != null) Banner(banner, spacer?.align)
        ChannelTitleRow(ch, spacer, server, onLongClick, onClick)
    }
}

/**
 * A channel's banner: the picture filling the row from the left, in its own proportions (never stretched), so it's
 * as tall as the row and whatever doesn't fit on the right is cut off. It fades out at the row's right end, with a
 * shade behind the name.
 */
@Composable
private fun androidx.compose.foundation.layout.BoxScope.Banner(model: Any, align: ServerModel.Spacer.Align?) {
    // At its own size: loaded whole (not to the size it's drawn at, which waits for a drawing)
    val context = coil3.compose.LocalPlatformContext.current
    val request = androidx.compose.runtime.remember(model) {
        coil3.request.ImageRequest.Builder(context).data(model).size(coil3.size.Size.ORIGINAL).build()
    }
    val painter = coil3.compose.rememberAsyncImagePainter(request)
    val state by painter.state.collectAsState()
    val picture = (state as? coil3.compose.AsyncImagePainter.State.Success)?.painter ?: return
    BannerPicture(picture, align)
}

/** The banner once loaded, over the whole of the channel's title row. */
@Composable
internal fun androidx.compose.foundation.layout.BoxScope.BannerPicture(picture: androidx.compose.ui.graphics.painter.Painter, align: ServerModel.Spacer.Align?) {
    val natural = picture.intrinsicSize
    if (!(natural.width > 0f && natural.height > 0f)) return
    val aspect = natural.width / natural.height
    Box(Modifier.matchParentSize()) {
        Box(
            Modifier.matchParentSize().clipToBounds()
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawContent()
                    val fade = (1f - 48.dp.toPx() / size.width).coerceIn(0f, 1f)
                    drawRect(Brush.horizontalGradient(0f to Color.Black, fade to Color.Black, 1f to Color.Transparent), blendMode = BlendMode.DstIn)
                },
        ) {
            androidx.compose.foundation.Image(
                picture, contentDescription = null, contentScale = ContentScale.FillBounds,
                // Covers the row: as tall as it (or as wide, for a picture taller than the row's shape), the rest cut
                // off. Worked out while laying out, as the row's height comes from its name (no SubcomposeLayout here:
                // the row is measured by its intrinsic height, which that can't answer)
                modifier = Modifier.layout { measurable, c ->
                    if (!c.hasBoundedWidth || !c.hasBoundedHeight) return@layout layout(0, 0) {}
                    var h = c.maxHeight
                    var w = (h * aspect).toInt()
                    if (w < c.maxWidth) {
                        w = c.maxWidth
                        h = (w / aspect).toInt()
                    }
                    val placeable = measurable.measure(androidx.compose.ui.unit.Constraints.fixed(maxOf(w, 1), maxOf(h, 1)))
                    layout(c.maxWidth, c.maxHeight) { placeable.place(0, (c.maxHeight - placeable.height) / 2) }
                },
            )
        }
        Box(
            Modifier.matchParentSize().drawBehind {
                val dark = Ts.card.copy(alpha = 0.9f)
                val mid = Ts.card.copy(alpha = 0.55f)
                val brush = when (align) {
                    ServerModel.Spacer.Align.CENTER -> Brush.horizontalGradient(
                        0f to Color.Transparent, 0.4f to Ts.card.copy(alpha = 0.75f), 0.6f to Ts.card.copy(alpha = 0.75f), 1f to Color.Transparent,
                    )
                    ServerModel.Spacer.Align.RIGHT -> Brush.horizontalGradient(
                        0f to Color.Transparent, 0.5f to mid, 1f to dark, startX = this.size.width - 220.dp.toPx(), endX = this.size.width,
                    )
                    else -> Brush.horizontalGradient(0f to dark, 0.5f to mid, 1f to Color.Transparent, startX = 0f, endX = 220.dp.toPx())
                }
                drawRect(brush)
            },
        )
    }
}

@Composable
private fun ChannelTitleRow(ch: ServerModel.Channel, spacer: ServerModel.Spacer?, server: String, onLongClick: (() -> Unit)?, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().combinedClickable(onLongClick = onLongClick, onClick = onClick).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (spacer != null) {
            SpacerTitle(spacer)
            return@Row
        }
        // The channel's own icon from the server, else the plain note
        if (ch.iconId > 0) TsIcon(ch.iconId, server, 18.dp)
        else Icon(Icons.Filled.MusicNote, contentDescription = null, tint = Ts.accent, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(
            ch.displayName, color = Ts.title, fontWeight = FontWeight.SemiBold, fontSize = 14.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
        )
        if (ch.hasPassword) Icon(Icons.Filled.Lock, contentDescription = stringResource(R.string.channel_has_password), tint = Ts.muted, modifier = Modifier.size(14.dp))
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.SpacerTitle(s: ServerModel.Spacer) {
    val line = s.line
    if (line != null) {
        Canvas(Modifier.weight(1f).height(14.dp)) {
            val y = size.height / 2
            val effect = when (line) {
                ServerModel.Spacer.Line.DASHED -> PathEffect.dashPathEffect(floatArrayOf(8f, 6f))
                ServerModel.Spacer.Line.DOTTED -> PathEffect.dashPathEffect(floatArrayOf(2f, 5f))
                ServerModel.Spacer.Line.SOLID -> null
            }
            drawLine(
                Color(0x73_94A3B8), Offset(0f, y), Offset(size.width, y), strokeWidth = 2.dp.toPx(),
                pathEffect = effect, cap = StrokeCap.Butt,
            )
        }
        return
    }
    val text = if (s.repeat) s.text.repeat((160 / maxOf(1, s.text.length)) + 1) else s.text
    Text(
        text,
        color = if (s.repeat) Ts.spacerRepeat else Ts.spacer,
        fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1,
        overflow = if (s.repeat) TextOverflow.Clip else TextOverflow.Ellipsis,
        textAlign = when (s.align) {
            ServerModel.Spacer.Align.LEFT -> TextAlign.Start
            ServerModel.Spacer.Align.CENTER -> TextAlign.Center
            ServerModel.Spacer.Align.RIGHT -> TextAlign.End
        },
        softWrap = false,
        modifier = Modifier.weight(1f),
    )
}

@Composable
private fun PersonRow(c: ServerModel.Client, ctx: TreeContext, onClick: () -> Unit) {
    val talking = c.id in ctx.talking
    val self = c.id == ctx.ownId
    val bg = if (talking) Ts.talking.copy(alpha = 0.1f) else Ts.row
    Row(
        Modifier.fillMaxWidth().padding(vertical = 1.dp).background(bg).clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(2.dp).height(34.dp).background(if (talking) Ts.talking else Color.Transparent))
        Spacer(Modifier.width(25.dp))
        PersonAvatar(c, ctx.server, 24, talking)
        Spacer(Modifier.width(8.dp))
        Text(
            c.nickname,
            color = if (talking) Ts.talking else Ts.username,
            fontWeight = FontWeight.Medium, fontSize = 13.5.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(vertical = 4.dp),
        )
        Row(
            Modifier.padding(end = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            when {
                c.outputMuted || !c.outputHardware ->
                    Icon(Icons.Filled.HeadsetOff, stringResource(R.string.person_sound_off), tint = Ts.red, modifier = Modifier.size(14.dp))
                c.inputMuted || !c.inputHardware ->
                    Icon(Icons.Filled.MicOff, stringResource(R.string.person_mic_off), tint = Ts.red, modifier = Modifier.size(14.dp))
                !ctx.canTalk(c) ->
                    Icon(Icons.Outlined.MicExternalOff, stringResource(R.string.person_cant_talk), tint = Ts.amber, modifier = Modifier.size(15.dp))
            }
            if (c.streaming) {
                // Like the desktop app: the mark in every channel, WATCH only in your own
                if (!self && c.channel == ctx.ownChannel && ctx.onWatch != null) WatchButton { ctx.onWatch.invoke(c) }
                Icon(Icons.Filled.Videocam, stringResource(R.string.person_streaming), tint = Ts.green, modifier = Modifier.size(15.dp))
            }
            if (c.away) Tag(stringResource(R.string.tag_afk), Ts.afk, Ts.afkBg, Ts.afkBorder)
            if (self) Tag(stringResource(R.string.tag_you), Ts.you, Ts.afkBg.copy(alpha = 0f), Ts.youBorder)
            for (icon in ctx.badges[c.id].orEmpty()) TsIcon(icon, ctx.server) // their groups' icons and their own
        }
    }
}

@Composable
fun Avatar(talking: Boolean, size: Int = 24) {
    val ring = if (talking) Modifier.border(2.dp, Ts.green, CircleShape) else Modifier
    Box(
        Modifier.size(size.dp).then(ring).clip(CircleShape).background(Color(0xFF3F4147)),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Icon(Icons.Filled.Person, contentDescription = null, tint = Color(0xFF8E9297), modifier = Modifier.size((size * 0.95).dp))
    }
}

@Composable
fun Tag(text: String, color: Color, bg: Color, border: Color) {
    Text(
        text, color = color, fontSize = 10.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(bg)
            .border(1.dp, border, RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 1.dp),
    )
}
