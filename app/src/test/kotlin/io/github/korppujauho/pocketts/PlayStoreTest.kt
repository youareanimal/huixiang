package io.github.korppujauho.pocketts

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.roborazziSystemPropertyOutputDirectory
import io.github.korppujauho.pocketts.ui.ConnectScreen
import io.github.korppujauho.pocketts.ui.PocketTsTheme
import io.github.korppujauho.pocketts.ui.ServerScreen
import io.github.korppujauho.pocketts.ui.SettingsContent
import io.github.korppujauho.pocketts.ui.View
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The pictures for the Google Play store page (scripts/dev.sh screenshots → docs/screenshots/play/): phone
 * screenshots in Play's 9:16 shape (1215 × 2160), the 512 × 512 icon and the 1024 × 500 feature graphic; and GitHub's
 * 1280 × 640 social preview (docs/screenshots/social-preview.png).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w405dp-h720dp-xxhdpi", sdk = [36])
class PlayStoreTest {
    init {
        io.github.korppujauho.pocketts.ui.chatNow = { Example.T0 + 600_000 }
    }

    private fun path(name: String) = "${roborazziSystemPropertyOutputDirectory()}/play/$name.png"

    private fun shot(name: String, content: @Composable () -> Unit) =
        captureRoboImage(path(name)) { PocketTsTheme { content() } }

    @get:Rule
    val compose = androidx.compose.ui.test.junit4.createComposeRule()

    @Test
    fun channels() = shot("1-channels") { ServerScreen(Example.state(), Example.noActions) }

    @Test
    fun chat() = shot("2-chat") { ServerScreen(Example.chatState(), Example.noActions, startView = View.CHAT) }

    // The person menu opens in a window of its own over the channels: the whole screen is captured
    @OptIn(com.github.takahirom.roborazzi.ExperimentalRoborazziApi::class)
    @Test
    fun person() {
        compose.setContent {
            PocketTsTheme {
                val s = Example.state()
                ServerScreen(s, Example.noActions)
                io.github.korppujauho.pocketts.ui.PersonSheet(s.client(2)!!, s.serverKey, isSelf = false, volume = 1f, onVolume = {}, onPoke = {}, onChat = {}, onDismiss = {})
            }
        }
        compose.waitForIdle()
        com.github.takahirom.roborazzi.captureScreenRoboImage(path("3-person"))
    }

    @Test
    fun settings() = shot("4-settings") {
        SettingsContent(VoiceSettings(), "Jamie", Example.noSettings, {}, startTab = 2, modifier = Modifier.fillMaxSize())
    }

    @Test
    fun connect() = shot("5-connect") {
        ConnectScreen(
            "Jamie", "ts.example.com", "", listOf(
                Favorite("ts.example.com", "Friends", auto = true),
                Favorite("voice.example.org:9988"),
                Favorite("192.0.2.10:9987", "Test server"),
            ), null, Example.noConnect, settingsActions = Example.noSettings,
        )
    }

    // Play cuts the icon itself (rounded corners): the whole square, the pocket as large as a launcher shows it
    @Test
    @Config(qualifiers = "w512dp-h512dp-mdpi")
    fun icon() = captureRoboImage(path("icon")) {
        Box(Modifier.size(512.dp).background(IconBackground), contentAlignment = Alignment.Center) {
            Image(androidDrawablePainter(R.drawable.ic_launcher_foreground, 512.dp * 108 / 72), null, Modifier.requiredSize(512.dp * 108 / 72))
        }
    }

    @Test
    @Config(qualifiers = "w1024dp-h500dp-mdpi")
    fun featureGraphic() = captureRoboImage(path("feature-graphic")) {
        Box(
            Modifier.size(1024.dp, 500.dp).background(
                Brush.linearGradient(listOf(Color(0xFF111318), Color(0xFF1B2A4A)), Offset.Zero, Offset(1024f, 500f)),
            ),
        ) {
            Row(Modifier.fillMaxSize().padding(horizontal = 96.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(220.dp).clip(RoundedCornerShape(48.dp)).background(IconBackground), contentAlignment = Alignment.Center) {
                    Image(androidDrawablePainter(R.drawable.ic_launcher_foreground, 220.dp * 108 / 72), null, Modifier.requiredSize(220.dp * 108 / 72))
                }
                Spacer(Modifier.width(64.dp))
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("PocketTS", color = Color.White, fontSize = 88.sp, fontWeight = FontWeight.Bold)
                    Text("Voice chat for TeamSpeak servers", color = Color(0xFFB8C4DA), fontSize = 34.sp)
                }
            }
        }
    }

    // A phone screen: the real app at its 405 × 720 size, shrunk into a rounded frame
    @Composable
    private fun Phone(scale: Float, content: @Composable () -> Unit) {
        Box(
            Modifier.size((405 * scale).dp, (720 * scale).dp).clip(RoundedCornerShape(28.dp))
                .border(3.dp, Color(0x33FFFFFF), RoundedCornerShape(28.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.requiredSize(405.dp, 720.dp).scale(scale)) { PocketTsTheme { content() } }
        }
    }

    // GitHub's social preview (repository Settings › General › Social preview): the name on the left, the channels
    // and the chat on the right. In docs/screenshots/, not with Play's pictures.
    @Test
    @Config(qualifiers = "w1280dp-h640dp-mdpi")
    fun socialPreview() = captureRoboImage("${roborazziSystemPropertyOutputDirectory()}/social-preview.png") {
        Box(
            Modifier.size(1280.dp, 640.dp).background(
                Brush.linearGradient(listOf(Color(0xFF111318), Color(0xFF1B2A4A)), Offset.Zero, Offset(1280f, 640f)),
            ),
        ) {
            Column(Modifier.padding(start = 96.dp).align(Alignment.CenterStart), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Box(Modifier.size(150.dp).clip(RoundedCornerShape(33.dp)).background(IconBackground), contentAlignment = Alignment.Center) {
                    Image(androidDrawablePainter(R.drawable.ic_launcher_foreground, 150.dp * 108 / 72), null, Modifier.requiredSize(150.dp * 108 / 72))
                }
                Spacer(Modifier.size(6.dp))
                Text("PocketTS", color = Color.White, fontSize = 92.sp, fontWeight = FontWeight.Bold)
                Text("Voice chat for TeamSpeak servers", color = Color(0xFFB8C4DA), fontSize = 34.sp)
                Text("Android · Free and open source", color = Color(0xFF7F8BA3), fontSize = 26.sp)
            }
            Box(Modifier.align(Alignment.CenterEnd).padding(end = 70.dp)) {
                Box(Modifier.offset(x = (-230).dp, y = 40.dp)) {
                    Phone(0.74f) { ServerScreen(Example.chatState(), Example.noActions, startView = View.CHAT) }
                }
                Phone(0.80f) { ServerScreen(Example.state(), Example.noActions) }
            }
        }
    }

    private companion object {
        val IconBackground = Color(0xFF24182F) // the launcher icon's background
    }
}
