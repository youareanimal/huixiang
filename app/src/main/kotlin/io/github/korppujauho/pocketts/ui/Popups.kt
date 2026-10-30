package io.github.korppujauho.pocketts.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/** The popups' own colours: a card a little lighter than the page, fading down, with a faint edge. */
object Pop {
    val top = Color(0xFF31333A)
    val bottom = Color(0xFF24252A)
    val edge = Color(0x1AFFFFFF)
    val field = Color(0x0DFFFFFF) // boxes inside a popup
    val handle = Color(0x40FFFFFF)
    val background = Brush.verticalGradient(listOf(top, bottom))
}

/**
 * A popup in the app's style: a rounded card over the dimmed screen, an icon in a soft badge, the title (and a line
 * under it), the contents (scrolling when long), and pill buttons along the bottom, the main one filled.
 */
@Composable
fun TsDialog(
    onDismiss: () -> Unit,
    title: String,
    icon: ImageVector? = null,
    subtitle: String? = null,
    tint: Color = Ts.accent,
    buttons: @Composable RowScope.() -> Unit,
    content: @Composable ColumnScope.() -> Unit = {},
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val shape = RoundedCornerShape(24.dp)
        Column(
            Modifier.padding(horizontal = 18.dp, vertical = 24.dp).widthIn(max = 480.dp).fillMaxWidth()
                .shadow(24.dp, shape).clip(shape).background(Pop.background).border(1.dp, Pop.edge, shape)
                .padding(start = 22.dp, end = 22.dp, top = 22.dp, bottom = 18.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (icon != null) {
                    IconBadge(icon, tint)
                    Spacer(Modifier.width(14.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(title, color = Ts.bright, fontWeight = FontWeight.SemiBold, fontSize = 19.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (!subtitle.isNullOrEmpty()) {
                        Text(subtitle, color = Ts.muted, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            Column(
                Modifier.weight(1f, fill = false).padding(top = 20.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                content = content,
            )
            Row(
                Modifier.fillMaxWidth().padding(top = 22.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
                content = buttons,
            )
        }
    }
}

/** An icon on a soft rounded square of its colour. */
@Composable
fun IconBadge(icon: ImageVector, tint: Color = Ts.accent, size: Int = 44) {
    Box(
        Modifier.size(size.dp).clip(RoundedCornerShape((size / 3).dp)).background(tint.copy(alpha = 0.16f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size((size * 0.55f).dp))
    }
}

enum class PillKind { PRIMARY, SECONDARY, DANGER, DANGER_TEXT }

/** A popup's button: a pill, filled for the main action, outlined for the rest, red for removing things. */
@Composable
fun PillButton(text: String, kind: PillKind = PillKind.SECONDARY, enabled: Boolean = true, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(22.dp)
    val (bg, fg) = when (kind) {
        PillKind.PRIMARY -> Ts.accent to Color.White
        PillKind.SECONDARY -> Color(0x0FFFFFFF) to Ts.text
        PillKind.DANGER -> Ts.redButton to Color.White
        PillKind.DANGER_TEXT -> Color.Transparent to Color(0xFFF87171)
    }
    Box(
        modifier.height(44.dp).clip(shape).background(if (enabled) bg else bg.copy(alpha = bg.alpha * 0.4f))
            .then(if (kind == PillKind.SECONDARY) Modifier.border(1.dp, Pop.edge, shape) else Modifier)
            .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 20.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = if (enabled) fg else fg.copy(alpha = 0.5f), fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 1)
    }
}

/** A soft box inside a popup or sheet, for a group of things (a slider, some numbers). */
@Composable
fun PopCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Pop.field).border(1.dp, Color(0x0AFFFFFF), RoundedCornerShape(16.dp))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        content = content,
    )
}

/** A big square-ish action in a sheet: its icon over its name. */
@Composable
fun ActionTile(icon: ImageVector, label: String, modifier: Modifier = Modifier, tint: Color = Ts.accent, onClick: () -> Unit) {
    Column(
        modifier.height(76.dp).clip(RoundedCornerShape(18.dp)).background(Pop.field).border(1.dp, Color(0x0AFFFFFF), RoundedCornerShape(18.dp))
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
        Spacer(Modifier.height(6.dp))
        Text(label, color = Ts.text, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
    }
}

/** A bottom sheet's handle. */
@Composable
fun SheetHandle() {
    Box(Modifier.padding(top = 10.dp, bottom = 4.dp).size(width = 40.dp, height = 4.dp).clip(CircleShape).background(Pop.handle))
}
