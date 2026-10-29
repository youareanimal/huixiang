package io.github.korppujauho.pocketts

import androidx.compose.runtime.Composable
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.roborazziSystemPropertyOutputDirectory
import io.github.korppujauho.pocketts.ui.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "zh-rCN-w360dp-h800dp-mdpi", sdk = [36])
class ProfileScreenshotTest {
    private val account = CloudAccountController.State(hasAccount = true, username = "小回音", email = "echo@example.com")
    @OptIn(com.github.takahirom.roborazzi.ExperimentalRoborazziApi::class)
    private fun shot(name: String, body: @Composable () -> Unit) =
        captureRoboImage("${roborazziSystemPropertyOutputDirectory()}/huixiang-041-$name.png") { PocketTsTheme(body) }
    @Test fun me() = shot("me") { MeHub(account, VoiceSettings(), {}, {}, {}, {}, {}) }
    @Test fun avatar() = shot("avatar") { AccountAvatarScreen(account, null, {}, {}, {}, {}, {}) }
    @Test fun login() = shot("login") { HubLogin(CloudAccountController.State(), {}, { _, _, _ -> }) }
}
