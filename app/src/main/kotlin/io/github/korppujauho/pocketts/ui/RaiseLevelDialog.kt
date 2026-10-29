package io.github.korppujauho.pocketts.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.korppujauho.pocketts.IdentityRaise
import io.github.korppujauho.pocketts.R
import androidx.compose.ui.res.stringResource
import io.github.korppujauho.pocketts.proto.Identity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The highest level offered by hand: past this, raising takes days even on a fast phone. */
private const val MAX_LEVEL = 40

/**
 * Raising the identity's security level, with its progress and Stop. With [needed] (a server's), it aims there;
 * without, the level is chosen first. The dialog stays open while it works, and the screen stays on.
 */
@Composable
fun RaiseLevelDialog(current: Int, needed: Int? = null, onReached: () -> Unit = {}, onClose: () -> Unit) {
    val context = LocalContext.current
    val res = androidx.compose.ui.platform.LocalResources.current
    val progress by IdentityRaise.progress.collectAsState()
    var target by remember { mutableIntStateOf(needed ?: maxOf(current + 1, 24).coerceAtMost(MAX_LEVEL)) }
    var rate by remember { mutableStateOf<Double?>(null) }
    LaunchedEffect(Unit) { rate = withContext(Dispatchers.Default) { IdentityRaise.triesPerSecond() } }
    DisposableEffect(Unit) { onDispose { IdentityRaise.clear() } }
    val p = progress
    val running = p?.running == true
    if (running) {
        val view = LocalView.current
        DisposableEffect(view) {
            view.keepScreenOn = true
            onDispose { view.keepScreenOn = false }
        }
    }
    val close = { IdentityRaise.clear(); onClose() }
    TsDialog(
        // Not closed by a tap outside while it works: Stop first
        onDismiss = { if (!running) close() },
        title = if (needed != null) stringResource(R.string.level_needed_title, needed) else stringResource(R.string.level_raise_title),
        icon = Icons.Rounded.Shield,
        subtitle = if (needed != null) stringResource(R.string.level_needed_subtitle, current) else stringResource(R.string.level_yours, current),
        buttons = {
            when {
                running -> PillButton(stringResource(R.string.stop), PillKind.DANGER) { IdentityRaise.stop() }
                p != null -> PillButton(stringResource(R.string.close), PillKind.PRIMARY) { close() }
                else -> {
                    PillButton(stringResource(R.string.cancel)) { close() }
                    PillButton(stringResource(R.string.level_raise), PillKind.PRIMARY, enabled = target > current) {
                        IdentityRaise.start(context, target) { reached -> if (reached && needed != null) onReached() }
                    }
                }
            }
        },
    ) {
        when {
            p == null -> {
                if (needed == null) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                        PillButton("−", enabled = target > current + 1) { target-- }
                        Text(
                            "$target", color = Ts.bright, fontWeight = FontWeight.SemiBold, fontSize = 28.sp, lineHeight = 36.sp,
                            textAlign = TextAlign.Center, modifier = Modifier.width(88.dp),
                        )
                        PillButton("+", enabled = target < MAX_LEVEL) { target++ }
                    }
                }
                Desc(
                    rate?.let { stringResource(R.string.level_estimate, duration(res, Identity.secondsFor(target, it))) }
                        ?: stringResource(R.string.level_measuring)
                )
                Desc(stringResource(R.string.level_desc_raise))
            }
            running -> {
                Text(stringResource(R.string.level_progress, p.level, p.target), color = Ts.bright, fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
                LinearProgressIndicator(Modifier.fillMaxWidth(), color = Ts.accent, trackColor = Ts.card)
                Desc(stringResource(R.string.level_running_desc))
            }
            p.level >= p.target -> Desc(
                stringResource(if (needed != null) R.string.level_done_connecting else R.string.level_done, p.level)
            )
            else -> Desc(stringResource(R.string.level_stopped, p.level))
        }
    }
}

/** A rough time in words: "about 3 minutes", in the phone's language. */
private fun duration(res: android.content.res.Resources, seconds: Double): String {
    fun about(id: Int, n: Int) = n.coerceAtLeast(1).let { res.getQuantityString(id, it, it) }
    return when {
        seconds < 60 -> res.getString(R.string.about_under_minute)
        seconds < 3600 -> about(R.plurals.about_minutes, (seconds / 60).toInt())
        seconds < 48 * 3600 -> about(R.plurals.about_hours, (seconds / 3600).toInt())
        else -> about(R.plurals.about_days, (seconds / 86_400).toInt())
    }
}
