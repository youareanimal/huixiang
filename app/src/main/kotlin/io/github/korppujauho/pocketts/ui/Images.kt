package io.github.korppujauho.pocketts.ui

import androidx.compose.material.icons.filled.Close
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Share
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import io.github.korppujauho.pocketts.SavedPictures
import io.github.korppujauho.pocketts.R
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import coil3.compose.AsyncImage
import coil3.memory.MemoryCache
import coil3.request.ImageRequest
import coil3.size.Precision
import io.github.korppujauho.pocketts.TsFile
import io.github.korppujauho.pocketts.proto.ServerModel

/**
 * A person's picture as the TeamSpeak app shows it: their avatar on the server, else their myTeamSpeak picture, over
 * a grey default figure (which stays for people without one, or while it loads).
 */
@Composable
fun PersonAvatar(person: ServerModel.Client?, server: String, size: Int = 24, talking: Boolean = false) {
    val account = LocalVoiceAccountProfile.current
    val models = remember(person, server, account) { personAvatarModels(person, server, account) }
    var source by remember(models) { mutableIntStateOf(0) }
    val ring = if (talking) Modifier.border(2.dp, Ts.green, CircleShape) else Modifier
    Box(
        Modifier.size(size.dp).then(ring).clip(CircleShape).background(Color(0xFF3F4147)),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Icon(Icons.Filled.Person, contentDescription = null, tint = Color(0xFF8E9297), modifier = Modifier.size((size * 0.95).dp))
        val model = models.getOrNull(source)
        if (model != null) {
            androidx.compose.runtime.key(model) {
                AsyncImage(model, contentDescription = "${person?.nickname.orEmpty()}的头像", contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(), onError = { if (models.getOrNull(source) == model) source++ })
            }
        }
    }
}

/** TeamSpeak's built-in group icons (under 1000), drawn as WebTS does: shields by colour. */
private val BUILTIN = mapOf(100L to Color(0xFF22C55E), 200L to Color(0xFF3B82F6), 300L to Color(0xFFEF4444), 500L to Color(0xFF94A3B8), 600L to Color(0xFFEAB308))

/** An icon from the server (a group's, a channel's, a person's, the server's). */
@Composable
fun TsIcon(id: Long, server: String, size: Dp = 16.dp) {
    if (id <= 0) return
    if (id < 1000) {
        BUILTIN[id]?.let { Icon(Icons.Filled.Shield, contentDescription = null, tint = it, modifier = Modifier.size(size)) }
        return
    }
    val file = TsFile.icon(server, id) ?: return
    AsyncImage(file, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.size(size))
}

/**
 * A picture on the whole screen: pinch to zoom, tap to close. The whole picture (up to 4096 px a side), not the
 * smaller one the chat shows, which [shown] (its memory cache key) shows until this one has loaded.
 */
@Composable
fun PictureViewer(model: Any, shown: MemoryCache.Key? = null, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val resources = androidx.compose.ui.platform.LocalResources.current
    val request = remember(model, shown) {
        ImageRequest.Builder(context).data(model).size(4096).precision(Precision.INEXACT).placeholderMemoryCacheKey(shown).build()
    }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            Modifier.fillMaxSize().background(Color(0xF2000000))
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        scale = (scale * zoom).coerceIn(1f, 6f)
                        offset = if (scale == 1f) Offset.Zero else offset + pan
                    }
                }
                .clickable(onClick = onDismiss),
            contentAlignment = Alignment.Center,
        ) {
            AsyncImage(
                request, contentDescription = null, contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().graphicsLayer {
                    scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y
                },
            )
            Row(Modifier.align(Alignment.TopEnd).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // Save and share wait for the picture itself (it may still be downloading)
                val scope = rememberCoroutineScope()
                var busy by remember { mutableStateOf(false) }
                fun withPicture(done: String?, use: (SavedPictures.Picture) -> Unit) {
                    if (busy) return
                    busy = true
                    scope.launch {
                        val result = runCatching { withContext(Dispatchers.IO) { SavedPictures.load(context, model) } }
                        busy = false
                        val message = result.mapCatching { use(it); done }.getOrElse { resources.getString(R.string.picture_failed, it.message) }
                        if (message != null) Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                    }
                }
                ViewerButton(Icons.Filled.Download, stringResource(R.string.picture_save), busy) {
                    withPicture(resources.getString(R.string.picture_saved, "Pictures/${SavedPictures.FOLDER}")) { SavedPictures.saveToGallery(context, it) }
                }
                ViewerButton(Icons.Filled.Share, stringResource(R.string.share), busy) {
                    withPicture(null) { SavedPictures.share(context, it) }
                }
                ViewerButton(Icons.Filled.Close, stringResource(R.string.close), false, onDismiss)
            }
        }
    }
}

@Composable
private fun ViewerButton(icon: ImageVector, description: String, busy: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(44.dp).clip(CircleShape).background(Color(0x66000000)).clickable(enabled = !busy, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = description, tint = if (busy) Color(0x80FFFFFF) else Color.White, modifier = Modifier.size(24.dp))
    }
}
