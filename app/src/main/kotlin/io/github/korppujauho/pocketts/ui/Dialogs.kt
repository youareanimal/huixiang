package io.github.korppujauho.pocketts.ui

import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.automirrored.rounded.VolumeDown
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.korppujauho.pocketts.R
import io.github.korppujauho.pocketts.Session
import androidx.compose.ui.res.stringResource
import io.github.korppujauho.pocketts.proto.Chat
import io.github.korppujauho.pocketts.proto.ServerModel
import java.util.Date
import kotlin.math.roundToInt

/** WebTS's person menu: who it is, how loud they are for you, poke, chat; for admins also move, kick and ban. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PersonSheet(
    person: ServerModel.Client,
    server: String,
    isSelf: Boolean,
    volume: Float,
    onVolume: (Float) -> Unit,
    onPoke: () -> Unit,
    onChat: () -> Unit,
    onDismiss: () -> Unit,
    away: Boolean = false,
    awayMessage: String = "",
    onAway: (Boolean, String) -> Unit = { _, _ -> },
    admin: Boolean = false,
    onMove: () -> Unit = {},
    onKick: () -> Unit = {},
    onBan: () -> Unit = {},
    channelName: String = "",
    serverGroups: List<String> = emptyList(),
    channelGroup: String = "",
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss, containerColor = Pop.bottom,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp), dragHandle = { SheetHandle() },
        // Open all the way (with the admin row it's taller than half the screen)
        sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        // Above the phone's navigation buttons, and scrolling on a short screen
        Column(
            Modifier.fillMaxWidth().verticalScroll(androidx.compose.foundation.rememberScrollState()).navigationBarsPadding()
                .padding(horizontal = 20.dp).padding(top = 8.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Who: the picture with a ring, the name, the description and whether they're away
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.border(2.dp, if (isSelf) Ts.youBorder else Ts.accent.copy(alpha = 0.6f), CircleShape).padding(4.dp)) {
                    PersonAvatar(person, server, 68)
                }
                Spacer(Modifier.size(16.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(person.nickname, color = Ts.bright, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, maxLines = 2)
                    if (person.description.isNotEmpty()) Text(person.description, color = Ts.muted, fontSize = 14.sp, maxLines = 3)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (isSelf) Chip(stringResource(R.string.person_you), Ts.you)
                        if (person.away) Chip(if (person.awayMessage.isNotEmpty()) stringResource(R.string.person_away_message, person.awayMessage) else stringResource(R.string.person_away), Ts.afk)
                    }
                }
            }
            PersonDetails(person, isSelf, channelName, serverGroups, channelGroup)
            if (isSelf) {
                // Away, as the TeamSpeak app sets it: others see AFK and the message
                var msg by remember { mutableStateOf(awayMessage) }
                PopCard {
                    Text(stringResource(R.string.person_away), color = Ts.text, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    Spacer(Modifier.height(8.dp))
                    TsField(msg, { msg = it.take(80) }, stringResource(R.string.away_message_field))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (away) {
                        PillButton(stringResource(R.string.away_back), modifier = Modifier.weight(1f)) { onAway(false, ""); onDismiss() }
                        PillButton(stringResource(R.string.away_update), PillKind.PRIMARY, modifier = Modifier.weight(1f)) { onAway(true, msg.trim()); onDismiss() }
                    } else {
                        PillButton(stringResource(R.string.away_set), PillKind.PRIMARY, modifier = Modifier.weight(1f)) { onAway(true, msg.trim()); onDismiss() }
                    }
                }
                return@Column
            }
            var v by remember { mutableFloatStateOf(volume) }
            PopCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.person_volume), color = Ts.text, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, modifier = Modifier.weight(1f))
                    ValueBadge("${(v * 100).roundToInt()}%")
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.AutoMirrored.Rounded.VolumeDown, contentDescription = null, tint = Ts.muted, modifier = Modifier.size(20.dp))
                    Slider(
                        value = v, onValueChange = { v = (it * 20).roundToInt() / 20f }, onValueChangeFinished = { onVolume(v) },
                        valueRange = 0f..2f, modifier = Modifier.weight(1f).padding(horizontal = 6.dp),
                        colors = SliderDefaults.colors(thumbColor = Ts.accent, activeTrackColor = Ts.accent, inactiveTrackColor = Ts.card),
                    )
                    androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.AutoMirrored.Rounded.VolumeUp, contentDescription = null, tint = Ts.muted, modifier = Modifier.size(20.dp))
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ActionTile(androidx.compose.material.icons.Icons.Rounded.NotificationsActive, stringResource(R.string.poke), Modifier.weight(1f), onClick = onPoke)
                ActionTile(androidx.compose.material.icons.Icons.AutoMirrored.Rounded.Chat, stringResource(R.string.chat), Modifier.weight(1f), onClick = onChat)
            }
            if (admin) AdminTiles(onMove, onKick, onBan)
        }
    }
}

/** Only information supplied by this server; account email is visible solely on our own sheet. */
@Composable
internal fun PersonDetails(person: ServerModel.Client, isSelf: Boolean, channelName: String,
    serverGroups: List<String>, channelGroup: String) {
    val account = LocalVoiceAccountProfile.current
    PopCard {
        Text("用户资料", color = Ts.bright, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        androidx.compose.foundation.text.selection.SelectionContainer {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 12.dp)) {
                fun value(text: String) = text.ifBlank { "服务器未提供" }
                if (isSelf && person.id == account.clientId && account.username.isNotBlank()) {
                    Text("当前账号：${account.username}", color = Ts.text, fontSize = 13.sp)
                    if (account.email.isNotBlank()) Text("账号邮箱：${account.email}", color = Ts.text, fontSize = 13.sp)
                }
                Text("昵称：${person.nickname}", color = Ts.text, fontSize = 13.sp)
                Text("唯一身份 UID：${value(person.uid)}", color = Ts.text, fontSize = 13.sp)
                Text("当前频道：${value(channelName)}", color = Ts.text, fontSize = 13.sp)
                Text("服务器身份组：${value(serverGroups.joinToString("、"))}", color = Ts.text, fontSize = 13.sp)
                Text("频道身份组：${value(channelGroup)}", color = Ts.text, fontSize = 13.sp)
                Text("客户端：${value(listOf(person.platform, person.version).filter { it.isNotBlank() }.joinToString(" · "))}", color = Ts.text, fontSize = 13.sp)
                Text("国家/地区：${value(person.country)}", color = Ts.text, fontSize = 13.sp)
                Text("状态：${listOfNotNull(if (person.away) "离开" else "在线", "麦克风已静音".takeIf { person.inputMuted }, "声音已静音".takeIf { person.outputMuted }).joinToString(" · ")}", color = Ts.text, fontSize = 13.sp)
            }
        }
    }
}

/** A small rounded label in its colour. */
@Composable
private fun Chip(text: String, color: Color) {
    Text(
        text, color = color, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
        modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(color.copy(alpha = 0.14f)).padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

@Composable
fun ValueBadge(text: String) {
    Text(
        text, color = Ts.text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(Ts.accent.copy(alpha = 0.16f)).padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

/** Writing a poke. */
@Composable
fun PokeDialog(person: ServerModel.Client, onSend: (String) -> Unit, onDismiss: () -> Unit) {
    var msg by remember { mutableStateOf("") }
    TsDialog(
        onDismiss = onDismiss, title = stringResource(R.string.poke_title, person.nickname), icon = androidx.compose.material.icons.Icons.Rounded.NotificationsActive,
        subtitle = stringResource(R.string.poke_subtitle),
        buttons = {
            PillButton(stringResource(R.string.cancel), onClick = onDismiss)
            PillButton(stringResource(R.string.poke), PillKind.PRIMARY) { onSend(msg) }
        },
    ) {
        TsField(msg, { msg = it.take(100) }, stringResource(R.string.poke_message_field))
    }
}

/** Pokes received: a card each until dismissed, the newest last. */
@Composable
fun PokeCards(pokes: List<Chat.Poke>, onDismiss: (Long) -> Unit, modifier: Modifier = Modifier) {
    if (pokes.isEmpty()) return
    val context = LocalContext.current
    val timeFormat = remember { android.text.format.DateFormat.getTimeFormat(context) }
    Column(modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (p in pokes) {
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Ts.card)
                    .border(1.dp, Ts.accent.copy(alpha = 0.6f), RoundedCornerShape(10.dp)).padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(stringResource(R.string.poked_you, p.fromName), color = Ts.title, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                if (p.text.isNotEmpty()) Text(formatted(p.text), color = Ts.text, fontSize = 14.sp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(timeFormat.format(Date(p.time)), color = Ts.faint, fontSize = 12.sp, modifier = Modifier.weight(1f))
                    SecondaryButton(stringResource(R.string.dismiss)) { onDismiss(p.id) }
                }
            }
        }
    }
}

/** The server's info, like the desktop app: welcome message, users, channels, age, website, version. */
@Composable
fun ServerInfoDialog(state: Session.UiState, onDismiss: () -> Unit) {
    val v = state.server
    val uri = LocalUriHandler.current
    TsDialog(
        onDismiss = onDismiss, title = state.serverName.ifEmpty { stringResource(R.string.info_title) }, icon = androidx.compose.material.icons.Icons.Rounded.Dns,
        subtitle = state.address,
        buttons = { PillButton(stringResource(R.string.close), PillKind.PRIMARY, onClick = onDismiss) },
    ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // The server's banner: a web address, or a picture in the server's own files (as channel banners)
                val bannerUrl = v["virtualserver_hostbanner_gfx_url"].orEmpty().trim()
                val banner: Any? = when {
                    bannerUrl.startsWith("https://", ignoreCase = true) -> bannerUrl
                    bannerUrl.startsWith("ts3image://", ignoreCase = true) ->
                        io.github.korppujauho.pocketts.proto.Pictures.channelPicture(bannerUrl)?.let { io.github.korppujauho.pocketts.TsFile.picture(state.serverKey, it) }
                    else -> null
                }
                if (banner != null) {
                    val link = v["virtualserver_hostbanner_url"].orEmpty().takeIf { it.startsWith("https://", ignoreCase = true) }
                    coil3.compose.AsyncImage(
                        banner, contentDescription = null, contentScale = androidx.compose.ui.layout.ContentScale.FillWidth,
                        modifier = Modifier.fillMaxWidth().heightIn(max = 200.dp).clip(RoundedCornerShape(10.dp))
                            .then(if (link != null) Modifier.clickable { uri.openUri(link) } else Modifier),
                    )
                }
                val welcome = v["virtualserver_welcomemessage"].orEmpty().trim()
                if (welcome.isNotEmpty()) InfoBox { Text(formatted(welcome), color = Ts.text, fontSize = 14.sp) }
                val max = v["virtualserver_maxclients"]?.toIntOrNull() ?: 0
                val res = androidx.compose.ui.platform.LocalResources.current
                val stats = buildList {
                    add(res.getString(R.string.info_users) to if (max > 0) "${state.clientCount} / $max" else "${state.clientCount}")
                    add(res.getString(R.string.info_channels) to "${state.channelCount}")
                    v["virtualserver_uptime"]?.toLongOrNull()?.let {
                        val since = v["received_at"]?.toLongOrNull()?.let { at -> System.currentTimeMillis() - at } ?: 0
                        add(res.getString(R.string.info_uptime) to span(res, it * 1000 + since))
                    }
                    v["virtualserver_created"]?.toLongOrNull()?.takeIf { it > 0 }?.let {
                        add(res.getString(R.string.info_created) to res.getString(R.string.info_ago, span(res, System.currentTimeMillis() - it * 1000)))
                    }
                }
                for (row in stats.chunked(2)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        for ((k, value) in row) {
                            InfoBox(Modifier.weight(1f)) {
                                Text(k, color = Ts.muted, fontSize = 12.sp)
                                Text(value, color = Ts.text, fontSize = 15.sp)
                            }
                        }
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
                val site = v["virtualserver_hostbutton_url"].orEmpty()
                if (site.startsWith("http://") || site.startsWith("https://")) {
                    InfoBox(Modifier.clickable { uri.openUri(site) }) {
                        Text(v["virtualserver_hostbutton_tooltip"].orEmpty().ifEmpty { site }, color = Ts.text, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        Text(site, color = Ts.muted, fontSize = 12.sp)
                    }
                }
                val version = listOf(
                    v["virtualserver_version"].orEmpty().replace(Regex("""\s*\[Build:[^\]]*]""", RegexOption.IGNORE_CASE), ""),
                    v["virtualserver_platform"].orEmpty(),
                ).filter { it.isNotEmpty() }.joinToString(" • ")
                if (version.isNotEmpty()) Text(version, color = Ts.muted, fontSize = 12.sp)
            }
    }
}

/**
 * A channel's info (holding it in the list): its topic, who and how many are in it, its type, voice and password, and
 * its description (asked of the server when this opens). Join, and with the admin functions on, Edit (its settings)
 * and Arrange (the channel edit mode).
 */
@Composable
fun ChannelInfoDialog(
    ch: io.github.korppujauho.pocketts.proto.ServerModel.Channel,
    people: Int,
    here: Boolean,
    onJoin: (() -> Unit)?,
    onEdit: (() -> Unit)?,
    onArrange: (() -> Unit)?,
    onDismiss: () -> Unit,
    onFiles: (() -> Unit)? = null,
) {
    // The description comes a moment after asking; empty after that, there's none
    var waited by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(ch.id) {
        kotlinx.coroutines.delay(2000)
        waited = true
    }
    val res = androidx.compose.ui.platform.LocalResources.current
    TsDialog(
        onDismiss = onDismiss, title = ch.displayName.ifEmpty { ch.name }, icon = androidx.compose.material.icons.Icons.Rounded.Forum,
        subtitle = ch.topic.trim().ifEmpty { null },
        buttons = {
            PillButton(stringResource(R.string.close), if (onJoin == null) PillKind.PRIMARY else PillKind.SECONDARY, onClick = onDismiss)
            if (onJoin != null) PillButton(stringResource(R.string.join), PillKind.PRIMARY, onClick = onJoin)
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (here) Text(stringResource(R.string.channel_info_here), color = Ts.accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            val stats = buildList {
                add(res.getString(R.string.info_users) to if (ch.maxClients >= 0) "$people / ${ch.maxClients}" else "$people")
                add(
                    res.getString(R.string.channel_kind) to res.getString(
                        when {
                            ch.permanent -> R.string.channel_kind_permanent
                            ch.semiPermanent -> R.string.channel_kind_semi
                            else -> R.string.channel_kind_temporary
                        },
                    ),
                )
                if (ch.neededTalkPower > 0) add(res.getString(R.string.channel_info_talk_power) to "${ch.neededTalkPower}")
            }
            for (row in stats.chunked(2)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    for ((k, value) in row) {
                        InfoBox(Modifier.weight(1f)) {
                            Text(k, color = Ts.muted, fontSize = 12.sp)
                            Text(value, color = Ts.text, fontSize = 15.sp)
                        }
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
            // The voice on a line of its own: codec and quality don't fit half the width
            InfoBox {
                Text(stringResource(R.string.channel_codec), color = Ts.muted, fontSize = 12.sp)
                Text(
                    stringResource(if (ch.codec == 5) R.string.channel_codec_music else R.string.channel_codec_voice) + " · " +
                        stringResource(R.string.channel_quality, ch.codecQuality),
                    color = Ts.text, fontSize = 15.sp,
                )
            }
            val notes = listOfNotNull(
                if (ch.hasPassword) stringResource(R.string.channel_password_subtitle) else null,
                if (ch.isDefault) stringResource(R.string.channel_info_default) else null,
            )
            if (notes.isNotEmpty()) Text(notes.joinToString(" · "), color = Ts.muted, fontSize = 13.sp)
            val description = ch.description.trim()
            InfoBox {
                Text(stringResource(R.string.channel_description), color = Ts.muted, fontSize = 12.sp)
                when {
                    description.isNotEmpty() -> Text(formatted(description), color = Ts.text, fontSize = 14.sp)
                    waited -> Text(stringResource(R.string.channel_info_no_description), color = Ts.faint, fontSize = 14.sp)
                    else -> Text(stringResource(R.string.channel_info_loading), color = Ts.faint, fontSize = 14.sp)
                }
            }
            if (onFiles != null) {
                PillButton(stringResource(R.string.files_title), modifier = Modifier.fillMaxWidth(), onClick = onFiles)
            }
            if (onEdit != null || onArrange != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (onEdit != null) PillButton(stringResource(R.string.edit), modifier = Modifier.weight(1f), onClick = onEdit)
                    if (onArrange != null) PillButton(stringResource(R.string.channel_info_arrange), modifier = Modifier.weight(1f), onClick = onArrange)
                }
            }
        }
    }
}

@Composable
private fun InfoBox(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Pop.field).padding(horizontal = 14.dp, vertical = 10.dp)) {
        content()
    }
}

/** "5 minutes", "3 hours", "12 days", "4 months", "2 years", in the phone's language. */
fun span(res: android.content.res.Resources, ms: Long): String {
    fun n(id: Int, count: Long) = res.getQuantityString(id, count.toInt(), count.toInt())
    val min = maxOf(0, ms / 60000)
    if (min < 60) return n(R.plurals.span_minutes, min)
    val hours = min / 60
    if (hours < 24) return n(R.plurals.span_hours, hours)
    val days = hours / 24
    if (days < 60) return n(R.plurals.span_days, days)
    val months = (days / 30.44).toLong()
    if (months < 24) return n(R.plurals.span_months, months)
    return n(R.plurals.span_years, (days / 365.25).toLong())
}

/**
 * A TeamSpeak link opened (from a web page or another app): it connects only when the person says so. The server's
 * address and the channel are shown; a privilege key in the link (it gives that server's rights, such as admin) is
 * used only when ticked here.
 */
@Composable
fun LinkDialog(
    link: io.github.korppujauho.pocketts.proto.JoinLink,
    nickname: String,
    leaving: String?, // the server we're on now, when connected (it's left first)
    onConnect: (useKey: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var useKey by remember { mutableStateOf(false) }
    TsDialog(
        onDismiss = onDismiss, title = stringResource(R.string.link_title, link.address),
        icon = androidx.compose.material.icons.Icons.Rounded.Dns, subtitle = stringResource(R.string.link_subtitle),
        buttons = {
            PillButton(stringResource(R.string.cancel), onClick = onDismiss)
            PillButton(stringResource(R.string.connect), PillKind.PRIMARY) { onConnect(useKey && link.token.isNotEmpty()) }
        },
    ) {
        PopCard {
            if (link.channel.isNotEmpty()) InfoLine(stringResource(R.string.link_channel), link.channel.removePrefix("/").replace("/", " › "))
            InfoLine(stringResource(R.string.nickname), nickname)
            if (link.password.isNotEmpty()) InfoLine(stringResource(R.string.link_password), stringResource(R.string.link_password_given))
        }
        if (leaving != null) Text(stringResource(R.string.link_leaving, leaving), color = Ts.text, fontSize = 14.sp)
        if (link.token.isNotEmpty()) {
            CheckRow(stringResource(R.string.link_use_key), useKey) { useKey = it }
            Text(stringResource(R.string.link_use_key_desc), color = Ts.muted, fontSize = 13.sp)
        }
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Ts.muted, fontSize = 14.sp, modifier = Modifier.padding(end = 12.dp))
        Text(value, color = Ts.text, fontSize = 14.sp, textAlign = TextAlign.End, modifier = Modifier.weight(1f), maxLines = 2)
    }
}
