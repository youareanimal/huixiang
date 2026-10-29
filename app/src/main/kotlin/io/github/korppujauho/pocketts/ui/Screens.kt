package io.github.korppujauho.pocketts.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.ScreenShare
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import io.github.korppujauho.pocketts.R

/**
 * How far the round buttons' icons are turned (degrees, clockwise): while you share the camera with the screen
 * locked upright and hold the phone sideways, they turn to stay upright to you, as in a camera app. The buttons stay
 * where they are.
 */
val LocalIconTurn = androidx.compose.runtime.compositionLocalOf { 0f }

/** An icon kept upright to you (see [LocalIconTurn]). */
fun Modifier.upright(): Modifier = composed { rotate(LocalIconTurn.current) }

/** WebTS's header: the logo and name on the left, an action on the right. */
@Composable
fun AppHeader(onBack: (() -> Unit)? = null, right: @Composable () -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().height(56.dp).background(Ts.header).padding(start = 8.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) androidx.compose.material3.IconButton(onClick = onBack) {
            Icon(androidx.compose.material.icons.Icons.AutoMirrored.Filled.ArrowBack, "返回服务器", tint = Ts.text)
        }
        // Compose reads the raster mascot directly; launcher insets are applied only by Android's launcher.
        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
            androidx.compose.foundation.Image(
                androidx.compose.ui.res.painterResource(io.github.korppujauho.pocketts.R.drawable.huixiang_mascot),
                contentDescription = null, modifier = Modifier.requiredSize(32.dp),
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.app_name), color = Ts.bright, fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.weight(1f))
        right()
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(Ts.hairline))
}

/** The settings gear in the header's corner. */
@Composable
fun SettingsGear(onClick: () -> Unit) {
    HeaderButton(Icons.Filled.Settings, stringResource(R.string.settings), onClick)
}

/** A small icon button for the header. */
@Composable
fun HeaderButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    androidx.compose.material3.IconButton(onClick = onClick, modifier = Modifier.size(40.dp)) {
        Icon(icon, contentDescription = label, tint = Ts.text, modifier = Modifier.size(22.dp))
    }
}

/**
 * A warning (a refused password, a share that couldn't start): on a background of its own, closed with its button
 * or by itself after a few seconds.
 */
@Composable
fun WarningBanner(text: String, onDismiss: () -> Unit, modifier: Modifier = Modifier, inset: androidx.compose.ui.unit.Dp = 15.dp) {
    androidx.compose.runtime.LaunchedEffect(text) {
        kotlinx.coroutines.delay(6000)
        onDismiss()
    }
    val shape = RoundedCornerShape(10.dp)
    Row(
        modifier.fillMaxWidth().padding(horizontal = inset).shadow(8.dp, shape).clip(shape).background(Color(0xFF3A1D20))
            .border(1.dp, Ts.red.copy(alpha = 0.6f), shape).padding(start = 12.dp, top = 4.dp, bottom = 4.dp, end = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Warning, contentDescription = null, tint = Color(0xFFF87171), modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Text(text, color = Color(0xFFFECACA), fontSize = 14.sp, modifier = Modifier.weight(1f).padding(vertical = 8.dp))
        androidx.compose.material3.IconButton(onClick = onDismiss, modifier = Modifier.size(40.dp)) {
            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.close), tint = Color(0xFFFECACA), modifier = Modifier.size(20.dp))
        }
    }
}

/** WebTS's red "exit" style button. */
@Composable
fun ExitButton(text: String, onClick: () -> Unit) {
    Text(
        text, color = Ts.red, fontWeight = FontWeight.SemiBold, fontSize = 14.sp,
        modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(Ts.red.copy(alpha = 0.15f))
            .border(1.dp, Ts.red.copy(alpha = 0.4f), RoundedCornerShape(6.dp))
            .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 5.dp),
    )
}

/** A round control button; red when [off]. */
@Composable
fun ControlButton(icon: ImageVector, label: String, off: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    val bg = if (off) Ts.redButton else Ts.button
    Box(
        Modifier.size(48.dp).clip(CircleShape).background(bg)
            .border(1.dp, if (off) Ts.redButton else Ts.buttonBorder, CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = label, tint = if (off) Color.White else Ts.text.copy(alpha = if (enabled) 1f else 0.4f), modifier = Modifier.upright())
    }
}

/** Calls [onHold] with true while pressed, false when let go (push-to-talk). */
fun Modifier.holdToTalk(onHold: (Boolean) -> Unit): Modifier = pointerInput(Unit) {
    detectTapGestures(onPress = {
        onHold(true)
        try {
            tryAwaitRelease()
        } finally {
            onHold(false)
        }
    })
}

/** WebTS's "Hold to talk" button (in the controls bar in push-to-talk mode). */
@Composable
fun PttButton(held: Boolean, onHold: (Boolean) -> Unit) {
    val color = if (held) Ts.talking else Ts.text
    Box(
        Modifier.width(112.dp).height(52.dp).clip(RoundedCornerShape(18.dp)).background(if (held) Ts.talking.copy(alpha = 0.2f) else Ts.accent)
            .holdToTalk(onHold),
        contentAlignment = Alignment.Center,
    ) {
        Text(if (held) "正在发言" else stringResource(R.string.hold_to_talk), color = if (held) Ts.talking else Color.White, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
    }
}

/** The floating push-to-talk button: round, at the bottom right, bigger while held. */
@Composable
fun FloatingPtt(held: Boolean, onHold: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val size = if (held) 66.dp else 60.dp
    Box(
        modifier.size(size).shadow(10.dp, CircleShape).clip(CircleShape)
            .background(if (held) Color(0xEB0E7490) else Color(0xC71E1F22))
            .border(1.dp, if (held) Ts.talking else Color(0x24FFFFFF), CircleShape).holdToTalk(onHold),
        contentAlignment = Alignment.Center,
    ) {
        // The same icon as the push-to-talk button in the bar: the microphone there means muting
        Icon(Icons.Filled.RecordVoiceOver, contentDescription = stringResource(R.string.hold_to_talk), tint = if (held) Color(0xFFE0F2FE) else Ts.text, modifier = Modifier.size(28.dp).upright())
    }
}

/** The bar with the microphone, sound and settings buttons (and "Hold to talk" in push-to-talk mode). */
@Composable
fun ControlsBar(
    micOff: Boolean, soundOff: Boolean, onMic: () -> Unit, onSound: () -> Unit,
    ptt: (@Composable () -> Unit)? = null,
    sharing: Boolean = false,
    onShare: (() -> Unit)? = null,
) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Ts.card)
            .border(1.dp, Ts.hairline, RoundedCornerShape(12.dp)).padding(12.dp),
        horizontalArrangement = Arrangement.SpaceAround,
    ) {
        ControlButton(if (micOff) Icons.Filled.MicOff else Icons.Filled.Mic, stringResource(R.string.microphone), off = micOff, onClick = onMic)
        ControlButton(
            if (soundOff) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
            stringResource(R.string.sound), off = soundOff, onClick = onSound,
        )
        if (onShare != null) {
            Box(
                Modifier.size(48.dp).clip(CircleShape).background(if (sharing) Color(0xFF23A559) else Ts.button)
                    .border(1.dp, if (sharing) Color(0xFF23A559) else Ts.buttonBorder, CircleShape).clickable(onClick = onShare),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.ScreenShare, contentDescription = stringResource(R.string.share), tint = if (sharing) Color.White else Ts.text, modifier = Modifier.upright())
            }
        }
        // Push-to-talk last: on the right, where a thumb reaches it most easily
        ptt?.invoke()
    }
}

/** A WebTS box: a header strip and the contents below it. */
@Composable
fun Panel(
    modifier: Modifier = Modifier,
    header: @Composable () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier.clip(RoundedCornerShape(22.dp)).background(Ts.panel),
    ) {
        Row(Modifier.fillMaxWidth().background(Ts.card).padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            header()
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Ts.hairline))
        content()
    }
}

@Composable
fun Loading(text: String) {
    Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        CircularProgressIndicator(color = Ts.accent)
        Spacer(Modifier.height(16.dp))
        Text(text, color = Ts.muted, fontSize = 14.sp)
    }
}

@Composable
fun TsField(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    password: Boolean = false,
    keyboard: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Next,
    singleLine: Boolean = true,
) {
    var revealed by androidx.compose.runtime.remember(password) { androidx.compose.runtime.mutableStateOf(false) }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    if (password) androidx.compose.runtime.DisposableEffect(lifecycle) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_PAUSE || event == androidx.lifecycle.Lifecycle.Event.ON_STOP) revealed = false
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    OutlinedTextField(
        value = value, onValueChange = onChange, singleLine = singleLine,
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it, color = Ts.faint) } },
        visualTransformation = if (password && !revealed) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        trailingIcon = if (password) ({
            androidx.compose.material3.IconButton(onClick = { revealed = !revealed }) {
                Icon(if (revealed) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                    contentDescription = if (revealed) "隐藏密码" else "显示密码", tint = Ts.brand)
            }
        }) else null,
        keyboardOptions = KeyboardOptions(keyboardType = if (password) KeyboardType.Password else keyboard, imeAction = imeAction),
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = Ts.card, unfocusedContainerColor = Ts.card,
            focusedBorderColor = Ts.accent, unfocusedBorderColor = Color(0x14FFFFFF),
            focusedTextColor = Ts.bright, unfocusedTextColor = Ts.bright,
            focusedLabelColor = Ts.accent, unfocusedLabelColor = Ts.muted, cursorColor = Ts.accent,
        ),
        shape = RoundedCornerShape(18.dp),
        modifier = modifier.fillMaxWidth().onFocusChanged { if (!it.isFocused) revealed = false },
    )
}

/** WebTS's blue main button. */
@Composable
fun PrimaryButton(text: String, enabled: Boolean = true, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier.clip(RoundedCornerShape(18.dp)).background(if (enabled) Ts.accent else Ts.accent.copy(alpha = 0.4f))
            .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 22.dp, vertical = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = Color.White.copy(alpha = if (enabled) 1f else 0.6f), fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
    }
}

@Composable
fun SecondaryButton(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier.clip(RoundedCornerShape(6.dp)).background(Ts.card)
            .border(1.dp, Color(0x1FFFFFFF), RoundedCornerShape(6.dp))
            .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = Ts.text, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
    }
}

/** The server name in the panel header: tapping it opens the server info. */
@Composable
fun ServerName(name: String, onClick: () -> Unit) {
    Text(
        name, color = Ts.title, fontWeight = FontWeight.SemiBold, fontSize = 15.sp,
        maxLines = 1, overflow = TextOverflow.Ellipsis,
        modifier = Modifier.clickable(onClick = onClick),
    )
}
