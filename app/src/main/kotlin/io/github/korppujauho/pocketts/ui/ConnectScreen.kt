package io.github.korppujauho.pocketts.ui

import androidx.compose.runtime.collectAsState
import androidx.compose.material.icons.rounded.Star
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import io.github.korppujauho.pocketts.Favorite
import io.github.korppujauho.pocketts.R

/** What the connect screen can ask for. */
interface ConnectActions {
    fun connect(address: String, nickname: String, password: String, remember: Boolean, favorite: Boolean)
    fun connectFavorite(f: Favorite)
    fun saveFavorite(old: Favorite?, new: Favorite)
    fun removeFavorite(f: Favorite)
    fun toggleAuto(f: Favorite)
}

/** WebTS's connect window: the favourites (one tap connects), then an address to type. */
@Composable
fun ConnectScreen(
    nickname: String,
    address: String,
    password: String,
    favorites: List<Favorite>,
    error: String?,
    actions: ConnectActions,
    voice: io.github.korppujauho.pocketts.VoiceSettings = io.github.korppujauho.pocketts.VoiceSettings(),
    settingsActions: SettingsActions? = null,
    onDismissError: () -> Unit = {},
    accountUsername: String = "",
) {
    var settings by remember { mutableStateOf(false) }
    var nick by rememberSaveable(nickname, accountUsername) { mutableStateOf(nickname) }
    var addr by rememberSaveable { mutableStateOf(address) }
    var pw by remember { mutableStateOf(password) }
    var help by rememberSaveable { mutableStateOf(false) }
    var remember by remember { mutableStateOf(password.isNotEmpty()) }
    var favorite by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Favorite?>(null) }
    val known = favorites.any { io.github.korppujauho.pocketts.Favorites.same(it.address, addr) }
    val effectiveNickname = nick.ifBlank { accountUsername }

    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).imePadding()) {
        AppHeader { if (settingsActions != null) SettingsGear { settings = true } }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(15.dp),
            verticalArrangement = Arrangement.spacedBy(15.dp),
        ) {
            Column(Modifier.padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.welcome_title), color = Ts.bright, fontWeight = FontWeight.Bold, fontSize = 26.sp)
                Text(stringResource(R.string.welcome_description), color = Ts.text, fontSize = 14.sp)
                TextButton(onClick = { help = true }) { Text(stringResource(R.string.connection_help), color = Ts.brand) }
            }
            if (favorites.isNotEmpty()) {
                Panel(Modifier.fillMaxWidth(), header = {
                    Text(stringResource(R.string.favourites), color = Ts.title, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                }) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (f in favorites) FavoriteRow(f, actions, onEdit = { editing = f })
                    }
                }
            }
            Panel(Modifier.fillMaxWidth(), header = {
                Text(stringResource(R.string.connect_title), color = Ts.title, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            }) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    TsField(nick, { nick = it }, stringResource(R.string.nickname))
                    if (accountUsername.isNotBlank()) Text(stringResource(R.string.account_nickname_hint, accountUsername), color = Ts.muted, fontSize = 12.sp)
                    TsField(addr, { addr = it }, stringResource(R.string.server_address), placeholder = "ts.example.com", keyboard = KeyboardType.Uri)
                    TsField(pw, { pw = it }, stringResource(R.string.server_password), password = true, imeAction = ImeAction.Go)
                    CheckRow(stringResource(R.string.remember_password), remember) { remember = it }
                    // Always there; ticked (and fixed) for a server that already is a favourite
                    CheckRow(stringResource(if (known) R.string.favourites_in else R.string.favourites_save), favorite || known, enabled = !known) { favorite = it }
                    error?.let { WarningBanner(it, onDismissError, inset = 0.dp) }
                    UpdateCard()
                    PrimaryButton(
                        stringResource(R.string.connect), enabled = addr.isNotBlank() && (effectiveNickname.isNotBlank() || io.github.korppujauho.pocketts.proto.JoinLink.isLink(addr)), modifier = Modifier.fillMaxWidth(),
                    ) { actions.connect(addr.trim(), effectiveNickname.trim(), pw, remember, favorite && !known) }
                }
            }
            Text(stringResource(R.string.fork_attribution), color = Ts.muted, fontSize = 12.sp)
        }
    }

    if (help) AlertDialog(
        onDismissRequest = { help = false },
        title = { Text(stringResource(R.string.connection_help)) },
        text = { Text(stringResource(R.string.connection_help_body)) },
        confirmButton = { TextButton(onClick = { help = false }) { Text(stringResource(R.string.close)) } },
    )

    if (settings && settingsActions != null) {
        SettingsDialog(voice, nick, settingsActions, onDismiss = { settings = false })
    }
    editing?.let { f ->
        FavoriteDialog(f, onSave = { actions.saveFavorite(f, it); editing = null }, onDelete = { actions.removeFavorite(f); editing = null }) {
            editing = null
        }
    }
}

@Composable
private fun FavoriteRow(f: Favorite, actions: ConnectActions, onEdit: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Column(
            Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).background(Ts.card)
                .border(1.dp, Color(0x1FFFFFFF), RoundedCornerShape(8.dp))
                .clickable { actions.connectFavorite(f) }.padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Text(f.label, color = Ts.text, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        FavButton(androidx.compose.material.icons.Icons.Rounded.Edit, stringResource(R.string.edit), onEdit)
        FavButton(androidx.compose.material.icons.Icons.Rounded.Close, stringResource(R.string.remove)) { actions.removeFavorite(f) }
    }
}

@Composable
private fun FavButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    androidx.compose.foundation.layout.Box(
        Modifier.size(36.dp).clip(RoundedCornerShape(8.dp)).background(Ts.card)
            .border(1.dp, Ts.buttonBorder, RoundedCornerShape(8.dp)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.material3.Icon(icon, contentDescription = label, tint = Ts.muted, modifier = Modifier.size(18.dp))
    }
}

@Composable
fun CheckRow(text: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(enabled = enabled) { onChange(!checked) }, verticalAlignment = Alignment.CenterVertically) {
        Checkbox(
            checked, onChange, enabled = enabled,
            colors = CheckboxDefaults.colors(checkedColor = Ts.accent, uncheckedColor = Ts.muted, disabledCheckedColor = Ts.accent.copy(alpha = 0.5f)),
        )
        Text(text, color = if (enabled) Ts.text else Ts.muted, fontSize = 14.sp)
    }
}

/** WebTS's "Edit favourite": its name, address, the nickname used there, its password, connect automatically. */
@Composable
fun FavoriteDialog(f: Favorite, onSave: (Favorite) -> Unit, onDelete: (() -> Unit)?, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(f.name) }
    var address by remember { mutableStateOf(f.address) }
    var nick by remember { mutableStateOf(f.nickname) }
    var pw by remember { mutableStateOf(f.password) }
    var auto by remember { mutableStateOf(f.auto) }
    var filePort by remember { mutableStateOf(f.filePort?.toString().orEmpty()) }
    var advanced by remember { mutableStateOf(f.filePort != null) }
    TsDialog(
        onDismiss = onDismiss, title = stringResource(if (onDelete != null) R.string.favourite_edit else R.string.favourite_add),
        icon = androidx.compose.material.icons.Icons.Rounded.Star, tint = Ts.amber, subtitle = f.address.takeIf { onDelete != null },
        // Delete on the left of the same row as Cancel and Save
        buttons = {
            if (onDelete != null) PillButton(stringResource(R.string.delete), PillKind.DANGER_TEXT, onClick = onDelete)
            Spacer(Modifier.weight(1f))
            PillButton(stringResource(R.string.cancel), onClick = onDismiss)
            PillButton(stringResource(R.string.save), PillKind.PRIMARY, enabled = address.isNotBlank()) {
                onSave(Favorite(address.trim(), name.trim(), nick.trim(), pw, auto, filePort.toIntOrNull()?.takeIf { it in 1..65535 }))
            }
        },
    ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                TsField(name, { name = it }, stringResource(R.string.favourite_name), placeholder = address)
                TsField(address, { address = it }, stringResource(R.string.server_address), keyboard = KeyboardType.Uri)
                TsField(nick, { nick = it }, stringResource(R.string.favourite_nickname))
                TsField(pw, { pw = it }, stringResource(R.string.server_password), password = true)
                CheckRow(stringResource(R.string.favourite_auto), auto) { auto = it }
                // Advanced: folded away unless used
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).clickable { advanced = !advanced }.padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(R.string.advanced), color = Ts.text, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, modifier = Modifier.weight(1f))
                    androidx.compose.material3.Icon(
                        if (advanced) androidx.compose.material.icons.Icons.Rounded.ExpandLess else androidx.compose.material.icons.Icons.Rounded.ExpandMore,
                        contentDescription = null, tint = Ts.muted,
                    )
                }
                if (advanced) {
                    TsField(
                        filePort, { v -> filePort = v.filter { it.isDigit() }.take(5) }, stringResource(R.string.favourite_file_port),
                        placeholder = stringResource(R.string.favourite_file_port_hint), keyboard = KeyboardType.Number,
                    )
                    Text(stringResource(R.string.favourite_file_port_desc), color = Ts.muted, fontSize = 12.sp)
                }
            }
    }
}

/** A newer PocketTS on GitHub: Download opens its release page, Not now hides it until a later one. */
@Composable
private fun UpdateCard() {
    val update by io.github.korppujauho.pocketts.UpdateCheck.available.collectAsState()
    val r = update ?: return
    val context = androidx.compose.ui.platform.LocalContext.current
    val uri = androidx.compose.ui.platform.LocalUriHandler.current
    PopCard {
        Text(stringResource(R.string.update_title, r.version), color = Ts.bright, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
        Text(stringResource(R.string.update_text, io.github.korppujauho.pocketts.BuildConfig.VERSION_NAME), color = Ts.muted, fontSize = 13.sp)
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            PillButton(stringResource(R.string.not_now)) { io.github.korppujauho.pocketts.UpdateCheck.dismiss(context) }
            PillButton(stringResource(R.string.download), PillKind.PRIMARY) { uri.openUri(r.url) }
        }
    }
}
