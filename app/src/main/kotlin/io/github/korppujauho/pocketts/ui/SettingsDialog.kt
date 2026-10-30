package io.github.korppujauho.pocketts.ui

import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.filled.Close
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.korppujauho.pocketts.VoiceSettings
import io.github.korppujauho.pocketts.VoiceSettings.Activation
import kotlinx.coroutines.delay
import kotlin.math.roundToInt
import androidx.compose.ui.res.stringResource
import io.github.korppujauho.pocketts.R

/** What the settings window needs besides the settings themselves. */
interface SettingsActions {
    fun save(voice: VoiceSettings, nickname: String)
    /** The microphone's level (RMS 0 - 1) and whether it counts as "on". */
    fun micLevel(): Pair<Float, Boolean>
    fun micTest(on: Boolean)
    fun playTestSound()
    fun setAvatar(uri: android.net.Uri) {}
    fun removeAvatar() {}
    /** Saves the identity into the file the person chose (TeamSpeak's .ini format); null when it worked, else why not. */
    fun exportIdentity(uri: android.net.Uri): String? = null
    /** Takes an identity exported from a TeamSpeak app; null when it worked, else why not. */
    fun importIdentity(text: String): String? = null
    /** The same from a file a TeamSpeak app saved (its .ini export). */
    fun importIdentityFile(uri: android.net.Uri): String? = null
    /** Why the saved identity can't be used, when it can't. */
    fun identityProblem(): String? = null
    /** Lets go of the saved identity: a new one is made at the next connect. */
    fun forgetIdentity() {}
    /** The identity's security level (null when it can't be read). */
    fun securityLevel(): Int? = null
    /** Forgets every server's chat history, and the lines on screen. */
    fun clearChatHistory() {}
    /** Forgets every remembered channel password. */
    fun forgetChannelPasswords() {}
    /** The app's language as a tag ("" for the phone's), and switching it (the screen is made again). */
    fun language(): String = ""
    fun setLanguage(tag: String) {}
}

/** Who we are on the server, for the profile picture in Settings. */
data class SelfInfo(val person: io.github.korppujauho.pocketts.proto.ServerModel.Client?, val server: String, val avatarStatus: String?)

/** WebTS's Settings: General, Playback and Capture, saved with "Save & Apply". */
@Composable
fun SettingsDialog(
    voice: VoiceSettings,
    nickname: String,
    actions: SettingsActions,
    onDismiss: () -> Unit,
    startTab: Int = 0,
    self: SelfInfo? = null,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        // Nearly the whole screen: more settings in view, less scrolling
        SettingsContent(
            voice, nickname, actions, onDismiss, startTab,
            Modifier.fillMaxSize().windowInsetsPadding(androidx.compose.foundation.layout.WindowInsets.safeDrawing).padding(8.dp), self,
        )
    }
}

@Composable
fun SettingsContent(
    voice: VoiceSettings,
    nickname: String,
    actions: SettingsActions,
    onDismiss: () -> Unit,
    startTab: Int = 0,
    modifier: Modifier = Modifier,
    self: SelfInfo? = null,
    singleSection: Boolean = false,
) {
    var tab by rememberSaveable { mutableStateOf(startTab) }
    var v by remember { mutableStateOf(voice) }
    var nick by remember { mutableStateOf(nickname) }
    val savedLanguage = remember { actions.language() }
    var language by rememberSaveable { mutableStateOf(savedLanguage) }

    // While the settings are open the microphone is measured, nothing is sent
    DisposableEffect(tab) {
        if (tab == 2) actions.micTest(true)
        onDispose { actions.micTest(false) }
    }

    run {
        Column(
            modifier.clip(RoundedCornerShape(12.dp)).background(Ts.panel),
        ) {
            if (!singleSection) {
            Row(Modifier.fillMaxWidth().background(Ts.card).padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.settings), color = Ts.bright, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, modifier = Modifier.weight(1f))
                androidx.compose.material3.Icon(
                    androidx.compose.material.icons.Icons.Filled.Close, contentDescription = stringResource(R.string.close), tint = Ts.muted,
                    modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable(onClick = onDismiss).padding(4.dp),
                )
            }
            // Scrolls sideways if a narrow screen can't fit them all
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 10.dp).padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                listOf(R.string.settings_general, R.string.settings_playback, R.string.settings_capture, R.string.settings_ui, R.string.advanced).map { stringResource(it) }.forEachIndexed { i, name ->
                    val active = i == tab
                    Text(
                        name, color = if (active) Ts.bright else Color(0xFF94A3B8), fontWeight = FontWeight.SemiBold, fontSize = 14.sp,
                        maxLines = 1, softWrap = false,
                        modifier = Modifier.clickable { tab = i }
                            .drawBehind { if (active) drawLine(Ts.accent, Offset(0f, size.height), Offset(size.width, size.height), 2.dp.toPx()) }
                            .padding(horizontal = 8.dp, vertical = 8.dp),
                    )
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(Ts.card))
            }
            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                when (tab) {
                    0 -> {
                        if (self != null) ProfilePicture(self, actions)
                        General(v, nick, { nick = it }) { v = it }
                        ChatHistory(actions)
                    }
                    1 -> Playback(v, actions) { v = it }
                    2 -> Capture(v, actions) { v = it }
                    3 -> {
                        Ui(v) { v = it }
                        Language(language) { language = it }
                    }
                    else -> {
                        Advanced(v) { v = it }
                        ChannelPasswordsGroup(actions)
                        Identity(actions)
                    }
                }
            }
            Row(Modifier.fillMaxWidth().background(Ts.card).padding(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.End) {
                PrimaryButton(stringResource(R.string.settings_save)) {
                    actions.save(v, nick.trim())
                    onDismiss()
                    // Last: the screen is made again in the new language
                    if (language != savedLanguage) actions.setLanguage(language)
                }
            }
        }
    }
}

@Composable
private fun Group(label: String? = null, badge: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (label != null) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(label, color = Ts.text, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            if (badge != null) ValueBadge(badge)
        }
        content()
    }
}

@Composable
fun Desc(text: String) = Text(text, color = Ts.muted, fontSize = 12.5.sp, lineHeight = 16.sp)

@Composable
internal fun Check(text: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.runtime.CompositionLocalProvider(androidx.compose.material3.LocalMinimumInteractiveComponentSize provides 0.dp) {
            Checkbox(checked, onChange, colors = CheckboxDefaults.colors(checkedColor = Ts.accent, uncheckedColor = Ts.muted))
        }
        Text(text, color = Ts.text, fontSize = 14.sp, modifier = Modifier.padding(start = 10.dp))
    }
}

@Composable
internal fun Radio(text: String, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.runtime.CompositionLocalProvider(androidx.compose.material3.LocalMinimumInteractiveComponentSize provides 0.dp) {
            RadioButton(selected, onClick, colors = RadioButtonDefaults.colors(selectedColor = Ts.accent, unselectedColor = Ts.muted))
        }
        Text(text, color = Ts.text, fontSize = 14.sp, modifier = Modifier.padding(start = 10.dp))
    }
}

@Composable
internal fun TsSlider(value: Float, range: ClosedFloatingPointRange<Float>, steps: Int, onChange: (Float) -> Unit) {
    Slider(
        value, onChange, valueRange = range, // no tick marks; values are rounded by the caller
        modifier = Modifier.height(32.dp),
        colors = SliderDefaults.colors(thumbColor = Ts.accent, activeTrackColor = Ts.accent, inactiveTrackColor = Ts.card),
    )
}

@Composable
private fun RangeEnds(left: String, right: String) {
    Row(Modifier.fillMaxWidth().offset(y = (-6).dp)) {
        Text(left, color = Ts.muted, fontSize = 12.sp, modifier = Modifier.weight(1f))
        Text(right, color = Ts.muted, fontSize = 12.sp)
    }
}

/** WebTS's profile picture: the picture as others see it, Change… and Remove. */
@Composable
private fun ProfilePicture(self: SelfInfo, actions: SettingsActions) {
    val pick = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia(),
    ) { uri -> if (uri != null) actions.setAvatar(uri) }
    Group(stringResource(R.string.avatar_title)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            PersonAvatar(self.person, self.server, 88)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SecondaryButton(stringResource(R.string.avatar_change)) {
                    pick.launch(androidx.activity.result.PickVisualMediaRequest(androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly))
                }
                if (self.person?.avatar?.isNotEmpty() == true) SecondaryButton(stringResource(R.string.remove)) { actions.removeAvatar() }
            }
        }
        self.avatarStatus?.let { Desc(it) }
        Desc(stringResource(R.string.avatar_desc))
    }
}

@Composable
private fun Identity(actions: SettingsActions) {
    var importing by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var problem by remember { mutableStateOf(actions.identityProblem()) }
    val imported = stringResource(R.string.identity_imported)
    val saved = stringResource(R.string.identity_saved)
    val wiped = stringResource(R.string.identity_wiped)
    val done = { error: String? ->
        status = error ?: imported
        if (error == null) problem = null
        importing = false
    }
    val pickFile = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) done(actions.importIdentityFile(uri)) }
    var warning by remember { mutableStateOf(false) }
    var wiping by remember { mutableStateOf(false) }
    val saveFile = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri -> if (uri != null) status = actions.exportIdentity(uri) ?: saved }
    Group(stringResource(R.string.identity_title)) {
        Desc(stringResource(R.string.identity_desc))
        problem?.let { Desc(it) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SecondaryButton(stringResource(R.string.identity_wipe)) { wiping = true }
            if (problem == null) SecondaryButton(stringResource(R.string.identity_export)) { warning = true }
            SecondaryButton(stringResource(R.string.identity_import)) { importing = true }
        }
        status?.let { Desc(it) }
    }
    // The security level: some servers need a higher one than a new identity has
    var level by remember { mutableStateOf(actions.securityLevel()) }
    var raising by remember { mutableStateOf(false) }
    level?.let { current ->
        Group(stringResource(R.string.level_title), badge = "$current") {
            Desc(stringResource(R.string.level_desc))
            SecondaryButton(stringResource(R.string.level_raise_button)) { raising = true }
        }
        if (raising) RaiseLevelDialog(current, onClose = { raising = false; level = actions.securityLevel() })
    }
    // Export: what the file means first, then a file of the person's choosing (not shared to an app)
    if (warning) {
        TsDialog(
            onDismiss = { warning = false }, title = stringResource(R.string.identity_export_title), icon = androidx.compose.material.icons.Icons.Rounded.Warning,
            tint = Ts.amber, subtitle = stringResource(R.string.identity_export_subtitle),
            buttons = {
                PillButton(stringResource(R.string.cancel)) { warning = false }
                PillButton(stringResource(R.string.identity_save_file), PillKind.PRIMARY) {
                    warning = false
                    saveFile.launch("pocketts-identity.ini")
                }
            },
        ) {
            Desc(stringResource(R.string.identity_export_what))
            Desc(stringResource(R.string.identity_export_where))
        }
    }
    // Wipe: what's lost first, with a way to keep a copy
    if (wiping) {
        TsDialog(
            onDismiss = { wiping = false }, title = stringResource(R.string.identity_wipe_title), icon = androidx.compose.material.icons.Icons.Rounded.Warning,
            tint = Ts.red, subtitle = stringResource(R.string.cant_be_undone),
            buttons = {
                PillButton(stringResource(R.string.cancel)) { wiping = false }
                PillButton(stringResource(R.string.identity_wipe), PillKind.DANGER) {
                    actions.forgetIdentity()
                    problem = null
                    wiping = false
                    status = wiped
                }
            },
        ) {
            Desc(stringResource(R.string.identity_wipe_what))
            Desc(stringResource(R.string.identity_wipe_back))
        }
    }
    if (importing) {
        var text by remember { mutableStateOf("") }
        TsDialog(
            onDismiss = { importing = false }, title = stringResource(R.string.identity_import_title), icon = androidx.compose.material.icons.Icons.Rounded.Key,
            subtitle = stringResource(R.string.identity_import_subtitle),
            buttons = {
                PillButton(stringResource(R.string.cancel)) { importing = false }
                PillButton(stringResource(R.string.identity_import), PillKind.PRIMARY, enabled = text.isNotBlank()) { done(actions.importIdentity(text)) }
            },
        ) {
            Desc(stringResource(R.string.identity_import_desc))
            // Hidden like a password while it's on screen
            TsField(text, { text = it }, stringResource(R.string.identity_field), password = true)
            PillButton(stringResource(R.string.identity_choose_file), modifier = Modifier.fillMaxWidth()) { pickFile.launch(arrayOf("*/*")) }
        }
    }
}

@Composable
private fun ChannelPasswordsGroup(actions: SettingsActions) {
    var forgotten by remember { mutableStateOf(false) }
    Group(stringResource(R.string.passwords_title)) {
        Desc(stringResource(R.string.passwords_desc))
        SecondaryButton(stringResource(R.string.passwords_forget)) {
            actions.forgetChannelPasswords()
            forgotten = true
        }
        if (forgotten) Desc(stringResource(R.string.passwords_forgotten))
    }
}

@Composable
private fun ChatHistory(actions: SettingsActions) {
    var asking by remember { mutableStateOf(false) }
    var cleared by remember { mutableStateOf(false) }
    val rules = io.github.korppujauho.pocketts.proto.ChatHistory
    Group(stringResource(R.string.history_title)) {
        Desc(
            stringResource(
                R.string.history_desc,
                androidx.compose.ui.res.pluralStringResource(R.plurals.history_days, rules.DAYS, rules.DAYS),
                androidx.compose.ui.res.pluralStringResource(R.plurals.history_lines, rules.LINES, rules.LINES),
            )
        )
        SecondaryButton(stringResource(R.string.history_clear)) { asking = true }
        if (cleared) Desc(stringResource(R.string.history_cleared))
    }
    if (asking) {
        TsDialog(
            onDismiss = { asking = false }, title = stringResource(R.string.history_clear_title), icon = androidx.compose.material.icons.Icons.Rounded.DeleteOutline, tint = Ts.red,
            buttons = {
                PillButton(stringResource(R.string.cancel)) { asking = false }
                PillButton(stringResource(R.string.history_clear_button), PillKind.DANGER) {
                    actions.clearChatHistory()
                    cleared = true
                    asking = false
                }
            },
        ) {
            Desc(stringResource(R.string.history_clear_desc))
        }
    }
}

@Composable
private fun General(v: VoiceSettings, nick: String, onNick: (String) -> Unit, onChange: (VoiceSettings) -> Unit) {
    Group(stringResource(R.string.nickname)) {
        TsField(nick, onNick, stringResource(R.string.nickname))
    }
    Group {
        Check(stringResource(R.string.general_floating_ptt), v.floatingPtt) { onChange(v.copy(floatingPtt = it)) }
        Desc(stringResource(R.string.general_floating_ptt_desc))
    }
    Group {
        Check(stringResource(R.string.general_overlay_ptt), v.overlayPtt) { onChange(v.copy(overlayPtt = it)) }
        Desc(stringResource(R.string.general_overlay_ptt_desc))
    }
    Group {
        Check(stringResource(R.string.general_volume_ptt), v.volumeKeysPtt) { onChange(v.copy(volumeKeysPtt = it)) }
        Desc(stringResource(R.string.general_volume_ptt_desc))
    }
    Group {
        Check(stringResource(R.string.general_headset_ptt), v.headsetPtt) { onChange(v.copy(headsetPtt = it)) }
        Desc(stringResource(R.string.general_headset_ptt_desc))
    }
    Group {
        Check(stringResource(R.string.general_call_mute), v.muteOnCalls) { onChange(v.copy(muteOnCalls = it)) }
        Desc(stringResource(R.string.general_call_mute_desc))
    }
    if (io.github.korppujauho.pocketts.UpdateCheck.enabled) Group {
        Check(stringResource(R.string.general_updates), v.checkUpdates) { onChange(v.copy(checkUpdates = it)) }
        Desc(stringResource(R.string.general_updates_desc))
    }
    Group {
        Check(stringResource(R.string.general_screen_on), v.keepScreenOn) { onChange(v.copy(keepScreenOn = it)) }
        Desc(stringResource(R.string.general_screen_on_desc))
    }
    Group {
        Check(stringResource(R.string.general_spacers), v.joinSpacers) { onChange(v.copy(joinSpacers = it)) }
        Desc(stringResource(R.string.general_spacers_desc))
    }
}

/** How the app looks and what it offers. */
@Composable
private fun Ui(v: VoiceSettings, onChange: (VoiceSettings) -> Unit) {
    Group {
        Check(stringResource(R.string.ui_admin), v.adminTools) { onChange(v.copy(adminTools = it)) }
        Desc(stringResource(R.string.ui_admin_desc))
    }
    Group {
        Check(stringResource(R.string.ui_controls_bottom), v.controlsAtBottom) { onChange(v.copy(controlsAtBottom = it)) }
        Desc(stringResource(R.string.ui_controls_bottom_desc))
    }
}

/** The app's language: the phone's, or one of the translations by its own name. */
@Composable
private fun Language(tag: String, onChange: (String) -> Unit) {
    Group(stringResource(R.string.ui_language)) {
        Radio(stringResource(R.string.ui_language_phone), tag.isEmpty()) { onChange("") }
        for ((t, name) in io.github.korppujauho.pocketts.AppLanguage.choices) Radio(name, tag == t) { onChange(t) }
    }
}

@Composable
private fun Advanced(v: VoiceSettings, onChange: (VoiceSettings) -> Unit) {
    Group(stringResource(R.string.relay_title)) {
        Desc(stringResource(R.string.relay_desc))
        TsField(v.turnUrl, { onChange(v.copy(turnUrl = it)) }, stringResource(R.string.relay_address), placeholder = "turn:relay.example.com:3478")
        TsField(v.turnUser, { onChange(v.copy(turnUser = it)) }, stringResource(R.string.relay_user))
        TsField(v.turnPassword, { onChange(v.copy(turnPassword = it)) }, stringResource(R.string.relay_password), password = true)
        Check(stringResource(R.string.relay_only), v.relayOnly) { onChange(v.copy(relayOnly = it)) }
    }
    Group {
        Desc(stringResource(R.string.advanced_file_port))
    }
}

@Composable
private fun Playback(v: VoiceSettings, actions: SettingsActions, onChange: (VoiceSettings) -> Unit) {
    Group(stringResource(R.string.playback_voice_volume), "${if (v.voiceVolumeDb > 0) "+" else ""}${v.voiceVolumeDb} dB") {
        TsSlider(v.voiceVolumeDb.toFloat(), -20f..12f, 31) { onChange(v.copy(voiceVolumeDb = it.roundToInt())) }
        RangeEnds(stringResource(R.string.quiet), stringResource(R.string.loud))
    }
    Group {
        SecondaryButton(stringResource(R.string.playback_test_sound)) { actions.playTestSound() }
    }
    Group {
        Check(stringResource(R.string.playback_event_sounds), v.uiSounds) { onChange(v.copy(uiSounds = it)) }
        Desc(stringResource(R.string.playback_event_sounds_desc))
    }
    if (v.uiSounds) {
        Group(stringResource(R.string.playback_sound_volume), "${(v.uiSoundVolume * 100).roundToInt()}%") {
            TsSlider(v.uiSoundVolume, 0f..1f, 19) { onChange(v.copy(uiSoundVolume = (it * 20).roundToInt() / 20f)) }
            SecondaryButton(stringResource(R.string.playback_play_sound)) { io.github.korppujauho.pocketts.UiSounds.play(io.github.korppujauho.pocketts.UiSounds.Cue.MESSAGE_IN, v) }
        }
    }
    Desc(stringResource(if (v.echoCancellation) R.string.playback_call_volume else R.string.playback_media_volume))
}

@Composable
private fun Capture(v: VoiceSettings, actions: SettingsActions, onChange: (VoiceSettings) -> Unit) {
    Group(stringResource(R.string.capture_mic_volume), "${(v.micVolume * 100).roundToInt()}%") {
        TsSlider(v.micVolume, 0f..3f, 29) { onChange(v.copy(micVolume = (it * 10).roundToInt() / 10f)) }
    }
    Group(stringResource(R.string.capture_activation)) {
        Radio(stringResource(R.string.capture_vad), v.activation == Activation.VAD) { onChange(v.copy(activation = Activation.VAD)) }
        Radio(stringResource(R.string.capture_ptt), v.activation == Activation.PTT) { onChange(v.copy(activation = Activation.PTT)) }
        Radio(stringResource(R.string.capture_continuous), v.activation == Activation.CONTINUOUS) { onChange(v.copy(activation = Activation.CONTINUOUS)) }
    }
    if (v.activation == Activation.PTT) Desc(stringResource(R.string.capture_ptt_desc))
    Group(stringResource(R.string.capture_level)) {
        LevelMeter(actions, if (v.activation == Activation.VAD) v.vadThreshold else null)
        Desc(stringResource(R.string.capture_level_desc))
    }
    if (v.activation == Activation.VAD) {
        Group(stringResource(R.string.capture_vad_level), "%.1f%%".format(v.vadThreshold * 100)) {
            TsSlider(v.vadThreshold, 0f..0.1f, 99) { onChange(v.copy(vadThreshold = (it * 1000).roundToInt() / 1000f)) }
            Desc(stringResource(R.string.capture_vad_level_desc))
        }
        Group(stringResource(R.string.capture_vad_delay), "%.1f s".format(v.vadTailMs / 1000f)) {
            TsSlider(v.vadTailMs / 1000f, 0.1f..2f, 18) { onChange(v.copy(vadTailMs = (it * 10).roundToInt() * 100)) }
        }
    }
    Group(stringResource(R.string.capture_processing)) {
        Check(stringResource(R.string.capture_noise), v.noiseSuppression) { onChange(v.copy(noiseSuppression = it)) }
        Check(stringResource(R.string.capture_echo), v.echoCancellation) { onChange(v.copy(echoCancellation = it)) }
        Check(stringResource(R.string.capture_agc), v.autoGainControl) { onChange(v.copy(autoGainControl = it)) }
        Desc(stringResource(R.string.capture_echo_desc))
    }
}

/** WebTS's level meter: green while "on", with the voice activation threshold as a red line (0 - 10 % RMS). */
@Composable
private fun LevelMeter(actions: SettingsActions, threshold: Float?) {
    var level by remember { mutableFloatStateOf(0f) }
    var on by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        while (true) {
            val (l, a) = actions.micLevel()
            level = l
            on = a
            delay(60)
        }
    }
    var width by remember { mutableStateOf(0) }
    val density = LocalDensity.current
    Box(
        Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(Color(0xFF111214)).onSizeChanged { width = it.width },
    ) {
        val fraction = (level / 0.1f).coerceIn(0f, 1f)
        Box(Modifier.fillMaxHeight().fillMaxWidth(fraction).background(if (on) Ts.green else Color(0xFF64748B)))
        if (threshold != null && width > 0) {
            val x = with(density) { (width * (threshold / 0.1f).coerceIn(0f, 1f)).toDp() }
            Box(Modifier.offset(x = x).width(2.dp).fillMaxSize().background(Ts.red))
        }
    }
}
