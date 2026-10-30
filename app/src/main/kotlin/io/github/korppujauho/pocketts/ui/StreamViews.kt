package io.github.korppujauho.pocketts.ui

import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.automirrored.rounded.ScreenShare
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.FlipCameraAndroid
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PictureInPicture
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.ScreenLockRotation
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.korppujauho.pocketts.StreamEngine
import io.github.korppujauho.pocketts.proto.Streams
import org.webrtc.EglBase
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack
import androidx.compose.ui.res.stringResource
import io.github.korppujauho.pocketts.R

/** WebTS's WATCH button (only for streams in your own channel, as in the desktop app). */
@Composable
fun WatchButton(onClick: () -> Unit) {
    Text(
        stringResource(R.string.stream_watch), color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(Ts.green).clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

/**
 * A video track drawn into an ordinary view (a TextureView): resized like the rest of the screen. A SurfaceView, drawn
 * in a layer of its own, kept its old size when the view changed size, so the picture came out cut and stretched.
 */
private class TextureVideoView(
    context: android.content.Context, eglContext: EglBase.Context, private val onShape: (Int, Int) -> Unit,
) : android.view.TextureView(context), android.view.TextureView.SurfaceTextureListener, org.webrtc.VideoSink {
    private val renderer = org.webrtc.EglRenderer("preview").apply { init(eglContext, EglBase.CONFIG_PLAIN, org.webrtc.GlRectDrawer()) }
    private var lastWidth = 0
    private var lastHeight = 0

    init {
        surfaceTextureListener = this
    }

    override fun onSurfaceTextureAvailable(st: android.graphics.SurfaceTexture, width: Int, height: Int) {
        renderer.setLayoutAspectRatio(width / height.toFloat())
        renderer.createEglSurface(st)
    }

    override fun onSurfaceTextureSizeChanged(st: android.graphics.SurfaceTexture, width: Int, height: Int) {
        renderer.setLayoutAspectRatio(width / height.toFloat())
    }

    override fun onSurfaceTextureDestroyed(st: android.graphics.SurfaceTexture): Boolean {
        val done = java.util.concurrent.CountDownLatch(1)
        renderer.releaseEglSurface { done.countDown() }
        done.await(2, java.util.concurrent.TimeUnit.SECONDS)
        return true
    }

    override fun onSurfaceTextureUpdated(st: android.graphics.SurfaceTexture) {}

    override fun onFrame(frame: org.webrtc.VideoFrame) {
        val w = frame.rotatedWidth
        val h = frame.rotatedHeight
        if (w != lastWidth || h != lastHeight) {
            lastWidth = w
            lastHeight = h
            io.github.korppujauho.pocketts.AppLog.log("Preview ${w}x$h")
            post { onShape(w, h) }
        }
        renderer.onFrame(frame)
    }

    fun release() = renderer.release()
}

/** Our own share's picture: the whole of it, in its own shape, turned by [turn] more for what's shown here only. */
@Composable
private fun PreviewVideo(track: VideoTrack, egl: EglBase, modifier: Modifier, turn: Int, onSize: (Int, Int) -> Unit) {
    var view by remember { mutableStateOf<TextureVideoView?>(null) }
    var shape by remember { mutableStateOf<Float?>(null) }
    val turnNow = androidx.compose.runtime.rememberUpdatedState(turn)
    val report = androidx.compose.runtime.rememberUpdatedState(onSize)
    Box(modifier, contentAlignment = Alignment.Center) {
        AndroidView(
            factory = { ctx ->
                TextureVideoView(ctx, egl.eglBaseContext) { w, h ->
                    shape = w.toFloat() / h
                    report.value(w, h)
                }.also { view = it }
            },
            modifier = shape?.let { Modifier.aspectRatio(it) } ?: Modifier.fillMaxSize(),
            onRelease = { it.release() },
        )
    }
    DisposableEffect(track, view) {
        val v = view
        // The same pictures with the extra turn as their label (the renderer turns them)
        val sink = v?.let {
            org.webrtc.VideoSink { f ->
                val t = turnNow.value
                if (t == 0) it.onFrame(f) else it.onFrame(org.webrtc.VideoFrame(f.buffer, (f.rotation + t) % 360, f.timestampNs))
            }
        }
        if (sink != null) track.addSink(sink)
        onDispose {
            if (sink != null) {
                try { track.removeSink(sink) } catch (_: Exception) {}
            }
        }
    }
}

/** A video track on screen. */
@Composable
fun VideoView(
    track: VideoTrack, egl: EglBase, modifier: Modifier = Modifier, mirror: Boolean = false, fill: Boolean = false,
    onSize: ((width: Int, height: Int) -> Unit)? = null,
    turn: Int = 0, // more turn (clockwise) for what's shown here only
) {
    var renderer by remember { mutableStateOf<SurfaceViewRenderer?>(null) }
    val turnNow = androidx.compose.runtime.rememberUpdatedState(turn)
    val report = androidx.compose.runtime.rememberUpdatedState(onSize)
    var shape by remember { mutableStateOf<Float?>(null) } // the picture's width / height, as shown
    Box(modifier, contentAlignment = Alignment.Center) {
    AndroidView(
        factory = { ctx ->
            SurfaceViewRenderer(ctx).apply {
                init(egl.eglBaseContext, object : RendererCommon.RendererEvents {
                    override fun onFirstFrameRendered() {}
                    override fun onFrameResolutionChanged(w: Int, h: Int, rotation: Int) {
                        io.github.korppujauho.pocketts.AppLog.log("Video ${w}x$h turned $rotation")
                        val (sw, sh) = if (rotation % 180 == 0) w to h else h to w
                        if (sw > 0 && sh > 0) shape = sw.toFloat() / sh
                        report.value?.invoke(sw, sh)
                    }
                })
                setScalingType(if (fill) RendererCommon.ScalingType.SCALE_ASPECT_FILL else RendererCommon.ScalingType.SCALE_ASPECT_FIT)
                // The picture is fitted while drawing: a hardware-scaled surface, cut to the view's shape when it's made,
                // stayed that shape when the view changed
                setEnableHardwareScaler(false)
                setMirror(mirror)
                renderer = this
            }
        },
        update = { it.setMirror(mirror) },
        modifier = shape.let { a -> if (fill || a == null) Modifier.fillMaxSize() else Modifier.aspectRatio(a) },
        onRelease = { it.release() },
    )
    }
    DisposableEffect(track, renderer) {
        val r = renderer
        // The same pictures with the extra turn as their label (the renderer turns them)
        val sink = r?.let {
            org.webrtc.VideoSink { f ->
                val t = turnNow.value
                if (t == 0) it.onFrame(f) else it.onFrame(org.webrtc.VideoFrame(f.buffer, (f.rotation + t) % 360, f.timestampNs))
            }
        }
        if (sink != null) track.addSink(sink)
        onDispose {
            if (sink != null) {
                try { track.removeSink(sink) } catch (_: Exception) {}
            }
        }
    }
    // (each renderer is let go when its view goes: onRelease)
}

private const val MIN_WATCH_HEIGHT = 96f // dp
private const val PULL_TO_SWITCH = 56f // dp dragged on past the top that makes the stream small inside the app

/**
 * Watching someone's stream: a box like the large preview of your own share, with its sound (on, off and how loud),
 * a small window over other apps, full screen, and close. Full screen turns the app the way the picture is: a wide
 * picture goes sideways, a tall one stays upright.
 *
 * The handle under the picture sets its height (remembered): at first at most 40 % of the screen, so the channels and
 * the chat stay in view, and never taller than the picture needs. Pulled on past the top it shrinks to a small
 * picture over the app that can be moved ([MiniPlayer]).
 */
@Composable
fun WatchCard(
    state: StreamEngine.State, title: String, egl: EglBase, onClose: () -> Unit,
    onMute: (Boolean) -> Unit = {}, onVolume: (Float) -> Unit = {}, onSmallWindow: (() -> Unit)? = null,
) {
    var aspect by remember { mutableStateOf(16f / 9f) }
    var full by remember { mutableStateOf(false) }
    var sound by remember { mutableStateOf(false) }
    val video = state.watchVideo
    val prefs = androidx.compose.ui.platform.LocalContext.current.let { remember { it.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE) } }
    var chosen by remember { mutableFloatStateOf(prefs.getFloat("watchHeight", 0f)) } // dp; 0: not chosen yet
    var pull by remember { mutableFloatStateOf(0f) } // dragged on past the top, dp (below 0)
    var mini by remember { mutableStateOf(false) }
    if (mini) {
        MiniPlayer(state, video, egl, aspect, onOpen = { mini = false }, onClose = onClose)
        if (full && video != null) FullScreenPlayer(state, video, title, egl, aspect, onMute) { full = false }
        return
    }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Ts.accent.copy(alpha = 0.12f))
            .border(1.dp, Ts.accent.copy(alpha = 0.8f), RoundedCornerShape(12.dp)).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val screen = LocalConfiguration.current.screenHeightDp.toFloat()
        val density = androidx.compose.ui.platform.LocalDensity.current.density
        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth()) {
            val natural = maxWidth.value / aspect
            val highest = maxOf(MIN_WATCH_HEIGHT, minOf(natural, screen * 0.75f))
            val height = (if (chosen > 0) chosen else minOf(natural, screen * 0.4f)).coerceIn(MIN_WATCH_HEIGHT, highest)
            val currentHeight by androidx.compose.runtime.rememberUpdatedState(height)
            Column {
                Box(
                    Modifier.fillMaxWidth().height(height.dp).clip(RoundedCornerShape(8.dp)).background(Color.Black)
                        .border(1.dp, Color(0x26FFFFFF), RoundedCornerShape(8.dp)),
                ) {
                    if (video != null && !full) {
                        VideoView(video, egl, Modifier.fillMaxSize(), onSize = { w, h -> aspect = w.toFloat() / h; StreamSize.set(w, h) })
                    }
                    if (state.watchStatus.isNotEmpty()) {
                        Text(
                            state.watchStatus, color = Color.White, fontSize = 14.sp,
                            modifier = Modifier.align(Alignment.Center).clip(RoundedCornerShape(8.dp)).background(Color(0x99000000))
                                .padding(horizontal = 14.dp, vertical = 8.dp),
                        )
                    }
                }
                // The handle: drag for the height; on past the top, small inside the app (its icon shows when letting go
                // would do it)
                var raw by remember { mutableFloatStateOf(0f) }
                val toSmall = pull < -PULL_TO_SWITCH
                val handleLabel = stringResource(R.string.stream_resize)
                Box(
                    Modifier.fillMaxWidth().height(22.dp)
                        .semantics { contentDescription = handleLabel }
                        .pointerInput(highest) {
                            detectVerticalDragGestures(
                                onDragStart = { raw = currentHeight },
                                onDragEnd = {
                                    if (pull < -PULL_TO_SWITCH) mini = true
                                    pull = 0f
                                    prefs.edit().putFloat("watchHeight", chosen).apply()
                                },
                                onDragCancel = { pull = 0f },
                            ) { change, dy ->
                                change.consume()
                                raw += dy / density
                                chosen = raw.coerceIn(MIN_WATCH_HEIGHT, highest)
                                pull = minOf(0f, raw - chosen)
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    when {
                        toSmall -> Icon(Icons.Filled.PictureInPicture, stringResource(R.string.stream_mini), tint = Ts.accent, modifier = Modifier.size(20.dp))
                        else -> Box(Modifier.size(width = 40.dp, height = 4.dp).clip(CircleShape).background(if (pull != 0f) Ts.accent else Ts.muted))
                    }
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LiveDot()
            Text(
                title, color = Ts.sender, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
            if (state.watchAudio != null) {
                RoundButton(
                    if (state.watchMuted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp, stringResource(R.string.sound),
                    if (state.watchMuted) Ts.red else Ts.text,
                ) { sound = !sound }
            }
            if (onSmallWindow != null) RoundButton(Icons.Filled.PictureInPictureAlt, stringResource(R.string.stream_small_window), Ts.text, onSmallWindow)
            RoundButton(Icons.Filled.Fullscreen, stringResource(R.string.stream_full_screen), Ts.text) { full = true }
            RoundButton(Icons.Filled.Close, stringResource(R.string.stream_stop_watching), Ts.text, onClose)
        }
        if (sound && state.watchAudio != null) SoundRow(state, onMute, onVolume)
    }
    if (full && video != null) FullScreenPlayer(state, video, title, egl, aspect, onMute) { full = false }
}

/**
 * A watched stream made small inside the app (its box pulled up past the top): the picture over everything, at first
 * in the top corner, moved by dragging it. A tap brings the box back; the ✕ stops watching.
 */
@Composable
private fun MiniPlayer(state: StreamEngine.State, video: VideoTrack?, egl: EglBase, aspect: Float, onOpen: () -> Unit, onClose: () -> Unit) {
    val density = androidx.compose.ui.platform.LocalDensity.current.density
    val config = LocalConfiguration.current
    val width = config.screenWidthDp * if (aspect >= 1f) 0.5f else 0.32f
    val height = width / aspect
    val screenW = config.screenWidthDp * density
    val screenH = config.screenHeightDp * density
    fun inside(x: Float, y: Float) = androidx.compose.ui.geometry.Offset(
        x.coerceIn(0f, maxOf(0f, screenW - width * density)), y.coerceIn(0f, maxOf(0f, screenH - height * density)),
    )
    var at by remember { mutableStateOf(inside(screenW - (width + 12) * density, 96 * density)) }
    val label = stringResource(R.string.stream_mini)
    androidx.compose.ui.window.Popup(
        popupPositionProvider = object : androidx.compose.ui.window.PopupPositionProvider {
            override fun calculatePosition(
                anchorBounds: androidx.compose.ui.unit.IntRect, windowSize: androidx.compose.ui.unit.IntSize,
                layoutDirection: androidx.compose.ui.unit.LayoutDirection, popupContentSize: androidx.compose.ui.unit.IntSize,
            ) = androidx.compose.ui.unit.IntOffset(
                at.x.toInt().coerceIn(0, maxOf(0, windowSize.width - popupContentSize.width)),
                at.y.toInt().coerceIn(0, maxOf(0, windowSize.height - popupContentSize.height)),
            )
        },
        properties = androidx.compose.ui.window.PopupProperties(focusable = false),
    ) {
        Box(
            Modifier.size(width.dp, height.dp).clip(RoundedCornerShape(10.dp)).background(Color.Black)
                .border(2.dp, Ts.accent, RoundedCornerShape(10.dp))
                .semantics { contentDescription = label }
                .pointerInput(Unit) {
                    detectDragGestures { change, drag ->
                        change.consume()
                        at = inside(at.x + drag.x, at.y + drag.y)
                    }
                }
                .pointerInput(Unit) { detectTapGestures(onTap = { onOpen() }) },
        ) {
            if (video != null) VideoView(video, egl, Modifier.fillMaxSize())
            if (state.watchStatus.isNotEmpty()) {
                Text(state.watchStatus, color = Color.White, fontSize = 11.sp, modifier = Modifier.align(Alignment.Center).padding(6.dp))
            }
            Box(
                Modifier.align(Alignment.TopEnd).padding(4.dp).size(26.dp).clip(CircleShape).background(Color(0x99000000)).clickable(onClick = onClose),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.Close, stringResource(R.string.stream_stop_watching), tint = Color.White, modifier = Modifier.size(16.dp))
            }
        }
    }
}

/** The stream's sound: on or off, and how loud. */
@Composable
private fun SoundRow(state: StreamEngine.State, onMute: (Boolean) -> Unit, onVolume: (Float) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        RoundButton(
            if (state.watchMuted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
            stringResource(if (state.watchMuted) R.string.stream_sound_on else R.string.stream_sound_off), if (state.watchMuted) Ts.red else Ts.text,
        ) { onMute(!state.watchMuted) }
        androidx.compose.material3.Slider(
            value = if (state.watchMuted) 0f else state.watchVolume, onValueChange = { onVolume(it); if (state.watchMuted) onMute(false) },
            modifier = Modifier.weight(1f),
            colors = androidx.compose.material3.SliderDefaults.colors(thumbColor = Ts.accent, activeTrackColor = Ts.accent),
        )
    }
}

/** The stream on the whole screen, the app turned the way the picture is; tap for the buttons. */
@Composable
private fun FullScreenPlayer(
    state: StreamEngine.State, video: VideoTrack, title: String, egl: EglBase, aspect: Float,
    onMute: (Boolean) -> Unit, onExit: () -> Unit,
) {
    val activity = androidx.activity.compose.LocalActivity.current
    DisposableEffect(Unit) {
        val before = activity?.requestedOrientation ?: android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        activity?.requestedOrientation = when {
            aspect > 1.05f -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            aspect < 0.95f -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            else -> before
        }
        onDispose { activity?.requestedOrientation = before }
    }
    var controls by remember { mutableStateOf(true) }
    Dialog(onDismissRequest = onExit, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        // No status or navigation bar (a swipe brings them back for a moment)
        val view = androidx.compose.ui.platform.LocalView.current
        DisposableEffect(view) {
            val window = (view.parent as? androidx.compose.ui.window.DialogWindowProvider)?.window
            if (window != null) {
                androidx.core.view.WindowCompat.getInsetsController(window, view).apply {
                    systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
                }
            }
            onDispose {}
        }
        Box(Modifier.fillMaxSize().background(Color.Black).clickable(
            interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null,
        ) { controls = !controls }) {
            VideoView(video, egl, Modifier.fillMaxSize())
            if (controls) {
                Row(
                    Modifier.fillMaxWidth().background(Color(0x66000000)).padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(title, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, maxLines = 1, modifier = Modifier.weight(1f))
                    if (state.watchAudio != null) {
                        RoundButton(
                            if (state.watchMuted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                            stringResource(if (state.watchMuted) R.string.stream_sound_on else R.string.stream_sound_off), if (state.watchMuted) Ts.red else Ts.text,
                        ) { onMute(!state.watchMuted) }
                    }
                    RoundButton(Icons.Filled.FullscreenExit, stringResource(R.string.stream_leave_full_screen), Ts.text, onExit)
                }
            }
        }
    }
}

/** WebTS's "Share" window: what (camera or screen), which camera, how good. */
@Composable
fun ShareDialog(
    onCamera: (front: Boolean, quality: Int, sound: Boolean) -> Unit, onScreen: (sound: Boolean) -> Unit, onDismiss: () -> Unit,
    onBoth: (quality: Int, sound: Boolean) -> Unit = { _, _ -> }, canBoth: Boolean = false, sound: Boolean = false,
    screenSound: Boolean = true,
    screenSoundBlocked: Boolean = false, // watching a stream: the phone's sound can't go with the screen now
) {
    var withSound by remember { mutableStateOf(sound) }
    var withScreenSound by remember { mutableStateOf(screenSound) }
    var screen by remember { mutableStateOf(false) }
    var camera by remember { mutableStateOf(0) } // front, back, both
    var quality by remember { mutableStateOf(720) }
    val qualities = listOf(480, 720, 1080)
    TsDialog(
        onDismiss = onDismiss, title = stringResource(if (screen) R.string.share_screen_title else R.string.share_camera_title),
        icon = if (screen) androidx.compose.material.icons.Icons.AutoMirrored.Rounded.ScreenShare else androidx.compose.material.icons.Icons.Rounded.Videocam, tint = ShareGreen,
        subtitle = stringResource(R.string.share_subtitle),
        buttons = {
            PillButton(stringResource(R.string.cancel), onClick = onDismiss)
            PillButton(stringResource(R.string.start), PillKind.PRIMARY) {
                when {
                    screen -> onScreen(withScreenSound && !screenSoundBlocked)
                    camera == 2 -> onBoth(quality, withSound)
                    else -> onCamera(camera == 0, quality, withSound)
                }
            }
        },
    ) {
            @Composable
            fun SoundChoice(label: String, on: Boolean, desc: String, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable(enabled = enabled) { onChange(!on) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    androidx.compose.runtime.CompositionLocalProvider(androidx.compose.material3.LocalMinimumInteractiveComponentSize provides 0.dp) {
                        androidx.compose.material3.Checkbox(
                            on, onChange, enabled = enabled,
                            colors = androidx.compose.material3.CheckboxDefaults.colors(
                                checkedColor = Ts.accent, uncheckedColor = Ts.muted,
                                disabledCheckedColor = Ts.muted.copy(alpha = 0.4f), disabledUncheckedColor = Ts.muted.copy(alpha = 0.4f),
                            ),
                        )
                    }
                    Text(label, color = if (enabled) Ts.text else Ts.muted, fontSize = 14.sp, modifier = Modifier.padding(start = 10.dp, top = 6.dp, bottom = 6.dp))
                }
                Text(desc, color = Ts.muted, fontSize = 13.sp)
            }
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.share_what), color = Ts.text, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Segments(listOf(stringResource(R.string.share_camera), stringResource(R.string.share_screen)), if (screen) 1 else 0) { screen = it == 1 }
                if (!screen) {
                    Text(stringResource(R.string.share_camera), color = Ts.text, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    Segments(listOf(stringResource(R.string.camera_front), stringResource(R.string.camera_back)) + if (canBoth) listOf(stringResource(R.string.camera_both)) else emptyList(), camera) { camera = it }
                    if (camera == 2) Text(stringResource(R.string.share_both_desc), color = Ts.muted, fontSize = 13.sp)
                    Text(stringResource(R.string.share_quality), color = Ts.text, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    Segments(listOf("480p", "720p", "1080p"), qualities.indexOf(quality)) { quality = qualities[it] }
                    Text(
                        when (quality) {
                            480 -> stringResource(R.string.share_quality_480)
                            720 -> stringResource(R.string.share_quality_720)
                            else -> stringResource(R.string.share_quality_1080)
                        },
                        color = Ts.muted, fontSize = 13.sp,
                    )
                    SoundChoice(
                        stringResource(R.string.share_sound), withSound,
                        stringResource(if (withSound) R.string.share_sound_on_desc else R.string.share_sound_off_desc),
                    ) { withSound = it }
                } else {
                    Text(stringResource(R.string.share_screen_desc), color = Ts.muted, fontSize = 13.sp)
                    SoundChoice(
                        stringResource(R.string.share_screen_sound), withScreenSound && !screenSoundBlocked,
                        stringResource(
                            when {
                                screenSoundBlocked -> R.string.share_screen_sound_watching
                                withScreenSound -> R.string.share_screen_sound_on_desc
                                else -> R.string.share_screen_sound_off_desc
                            },
                        ),
                        enabled = !screenSoundBlocked,
                    ) { withScreenSound = it }
                }
            }
    }
}

@Composable
internal fun Segments(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Pop.field).border(1.dp, Color(0x0AFFFFFF), RoundedCornerShape(14.dp)).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        options.forEachIndexed { i, o ->
            Text(
                o, color = if (i == selected) Color.White else Ts.muted, fontWeight = FontWeight.SemiBold, fontSize = 13.sp,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(if (i == selected) Ts.accent.copy(alpha = 0.9f) else Color.Transparent)
                    .clickable { onSelect(i) }.padding(vertical = 9.dp),
            )
        }
    }
}

/**
 * WebTS's share bar: a green box with your own picture small (tap: bigger, inside the box, at most half the screen;
 * tap again: small), a live dot with what's shared and how many watch, and round buttons to switch the camera, turn
 * or lock the picture, and Stop. [screenTurn] is how far the phone is turned from the screen's upright (clockwise):
 * the preview is turned back by it, so it's the right way up to you while the stream is upright for viewers.
 */
@Composable
fun ShareBar(
    state: StreamEngine.State, egl: EglBase, onFlip: () -> Unit, onStop: () -> Unit, onRotateLock: (Boolean) -> Unit = {},
    screenTurn: Int = 0,
    light: Boolean? = null, // the ring light, when it can be had (the front camera): on or off
    onLight: (Boolean) -> Unit = {},
) {
    val kind = state.sharing ?: return
    var big by remember { mutableStateOf(false) }
    var aspect by remember { mutableStateOf(9f / 16f) } // the picture's width / height, as shown
    val camera = kind == Streams.Kind.CAMERA
    // The preview is what the viewers see, the front camera too (not mirrored like a selfie camera), turned back
    // by the turn added for them
    val sent = if (state.rotateLocked) state.lockedTurn else screenTurn
    val previewTurn = (360 - sent) % 360
    val track = state.localVideo

    @Composable
    fun Preview(modifier: Modifier) {
        if (track == null) return
        Box(modifier.clip(RoundedCornerShape(8.dp)).background(Color.Black).border(1.dp, Color(0x26FFFFFF), RoundedCornerShape(8.dp))) {
            // Small and big alike, the whole picture in its own shape (the small box already has that shape)
            PreviewVideo(track, egl, Modifier.fillMaxSize(), previewTurn) { w, h -> aspect = w.toFloat() / h }
            // Over the picture (a video surface doesn't take taps itself)
            Box(Modifier.fillMaxSize().clickable { big = !big })
        }
    }

    @Composable
    fun Status(modifier: Modifier) {
        Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LiveDot()
            val what = when {
                !camera -> stringResource(R.string.share_screen)
                state.shareBoth -> stringResource(R.string.share_both_cameras)
                else -> stringResource(R.string.share_camera)
            }
            Text(what, color = ShareText, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 1)
            if (state.shareSound) {
                Icon(
                    if (state.soundPaused) Icons.Filled.MicOff else Icons.Filled.Mic,
                    contentDescription = stringResource(if (state.soundPaused) R.string.share_sound_quiet else R.string.share_sound_live),
                    tint = ShareText, modifier = Modifier.size(16.dp),
                )
            }
            if (!state.shareLive) {
                Text(stringResource(R.string.share_starting), color = ShareText, fontSize = 14.sp, maxLines = 1)
            } else if (state.sharePaused) {
                Icon(Icons.Filled.Pause, contentDescription = null, tint = ShareText, modifier = Modifier.size(16.dp))
                Text(stringResource(R.string.share_paused_call), color = ShareText, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            } else {
                Icon(Icons.Filled.Visibility, contentDescription = stringResource(R.string.share_watching), tint = ShareText, modifier = Modifier.size(16.dp))
                Text("${state.viewers}", color = ShareText, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 1)
            }
        }
    }

    @Composable
    fun Buttons() {
        if (camera) {
            RoundButton(Icons.Filled.FlipCameraAndroid, stringResource(if (state.shareBoth) R.string.share_swap_cameras else R.string.share_switch_camera), Ts.text, onFlip)
            RoundButton(
                if (state.rotateLocked) Icons.Filled.ScreenLockRotation else Icons.Filled.ScreenRotation,
                stringResource(if (state.rotateLocked) R.string.share_turn_locked else R.string.share_turn_follows),
                if (state.rotateLocked) Color(0xFFFBBF24) else Ts.text,
            ) { onRotateLock(!state.rotateLocked) }
            if (light != null) {
                RoundButton(
                    Icons.Filled.LightMode, stringResource(if (light) R.string.share_light_on else R.string.share_light_off),
                    if (light) Color(0xFFFBBF24) else Ts.text,
                ) { onLight(!light) }
            }
        }
        Text(
            stringResource(R.string.stop), color = Color(0xFFF87171), fontWeight = FontWeight.SemiBold, fontSize = 14.sp,
            modifier = Modifier.height(40.dp).clip(RoundedCornerShape(20.dp)).background(Ts.redButton.copy(alpha = 0.15f))
                .border(1.dp, Ts.redButton, RoundedCornerShape(20.dp)).clickable(onClick = onStop)
                .wrapContentHeight(Alignment.CenterVertically).padding(horizontal = 18.dp),
        )
    }

    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(ShareGreen.copy(alpha = 0.15f))
            .border(1.dp, ShareGreen, RoundedCornerShape(12.dp)).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (big) {
            val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.5f).dp
            androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth()) {
                Preview(Modifier.fillMaxWidth().height(minOf(maxWidth / aspect, maxHeight)))
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Status(Modifier.weight(1f))
                Buttons()
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Preview(Modifier.size(width = 72.dp, height = minOf(72.dp / aspect, 128.dp)))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { Status(Modifier.padding(end = 4.dp)) }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                        Buttons()
                    }
                }
            }
        }
    }
}

private val ShareText = Color(0xFF4ADE80)
private val ShareGreen = Color(0xFF23A559)

/** WebTS's round share button. */
@Composable
private fun RoundButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, tint: Color, onClick: () -> Unit) {
    Box(
        Modifier.size(40.dp).clip(CircleShape).background(Ts.button).border(1.dp, Color(0x1AFFFFFF), CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(20.dp).upright())
    }
}

/** The red "live" dot, with a ring that grows and fades as in WebTS. */
@Composable
private fun LiveDot() {
    val pulse = androidx.compose.animation.core.rememberInfiniteTransition(label = "live")
    val t by pulse.animateFloat(
        0f, 1f,
        androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween<Float>(1600)),
        label = "ring",
    )
    Box(Modifier.size(9.dp).drawBehind {
        val ring = (t / 0.7f).coerceAtMost(1f)
        drawCircle(Color(0xFFEF4444).copy(alpha = 0.6f * (1f - ring)), radius = size.minDimension / 2 + 7.dp.toPx() * ring)
        drawCircle(Color(0xFFEF4444))
    })
}

/** The watched stream's picture size, for the small window's shape. */
object StreamSize {
    @Volatile var width = 16
        private set
    @Volatile var height = 9
        private set
    /** Called when it changes (from the picture's own thread). */
    @Volatile var onChange: (() -> Unit)? = null

    fun set(w: Int, h: Int) {
        if (w <= 0 || h <= 0 || (w == width && h == height)) return
        width = w
        height = h
        onChange?.invoke()
    }
}

/** The app as a small window over other apps: only the stream's picture. */
@Composable
fun PipPlayer(state: StreamEngine.State, egl: EglBase) {
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        state.watchVideo?.let { VideoView(it, egl, Modifier.fillMaxSize(), onSize = { w, h -> StreamSize.set(w, h) }) }
        val status = if (state.watching == 0) stringResource(R.string.stream_ended) else state.watchStatus
        if (status.isNotEmpty()) {
            Text(status, color = Color.White, fontSize = 12.sp, modifier = Modifier.align(Alignment.Center).padding(8.dp))
        }
    }
}
