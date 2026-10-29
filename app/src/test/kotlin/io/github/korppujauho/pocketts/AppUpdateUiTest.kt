package io.github.korppujauho.pocketts

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import io.github.korppujauho.pocketts.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "zh-rCN-w360dp-h800dp-mdpi", sdk = [36])
class AppUpdateUiTest {
    @get:Rule val compose = createComposeRule()
    private val release = UpdateFeed.Release(12, "0.4.3-preview", "修复语音问题", UpdateFeed.Artifact("arm64-v8a", "", 20_000_000, ""))
    @Test fun updateIsDownloadedOnlyAfterTheUserChoosesIt() {
        var downloads = 0; var installs = 0
        compose.setContent { PocketTsTheme { AppUpdateDialog(AppUpdater.State(release = release), {}, {}, { downloads++ }, {}, { installs++ }, {}) } }
        assertEquals(0, downloads); assertEquals(0, installs)
        compose.onNodeWithText("发现新版本 0.4.3-preview").assertExists()
        compose.onNodeWithText("下载新版").performScrollTo().performClick()
        assertEquals(1, downloads); assertEquals(0, installs)
    }
    @Test fun completedDownloadHasAnExplicitInstallAction() {
        var installs = 0
        compose.setContent { PocketTsTheme { AppUpdateDialog(AppUpdater.State(release = release, ready = true), {}, {}, {}, {}, { installs++ }, {}) } }
        compose.onNodeWithText("安装新版").performScrollTo().performClick()
        assertEquals(1, installs)
        compose.onNodeWithText("下载新版").assertDoesNotExist()
    }
    @Test fun aDismissedVersionDoesNotKeepShowingTheStartupBanner() {
        compose.setContent { PocketTsTheme { AppUpdateBanner(AppUpdater.State(release = release, dismissed = 12), {}, {}) } }
        compose.onNodeWithText("发现新版", substring = true).assertDoesNotExist()
    }
}
