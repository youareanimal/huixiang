package io.github.korppujauho.pocketts

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
class ConnectHeadlineScreenshotTest {
    @OptIn(com.github.takahirom.roborazzi.ExperimentalRoborazziApi::class)
    @Test fun connectionScreen() = captureRoboImage("${roborazziSystemPropertyOutputDirectory()}/huixiang-051-connect.png") {
        PocketTsTheme { ManualConnectScreen("小回音", "小回音", "", "", null, {}, onConnect = { _,_,_,_,_ -> }) }
    }
}
