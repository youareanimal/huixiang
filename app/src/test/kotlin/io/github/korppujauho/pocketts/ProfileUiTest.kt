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
class ProfileUiTest {
    @get:Rule val compose = createComposeRule()
    private val account = CloudAccountController.State(hasAccount = true, username = "小回音", email = "echo@example.com")
    @Test fun myPageDisplaysEmailAndBothAvatarEntrypointsOpenTheManager() {
        var opened = 0; var refreshed = 0
        compose.setContent { PocketTsTheme { MeHub(account, VoiceSettings(), {}, {}, {}, {}, {}, { refreshed++ }, { opened++ }) } }
        compose.onNodeWithText("echo@example.com").assertIsDisplayed()
        compose.onNodeWithContentDescription("修改账号头像").performClick()
        compose.onNodeWithText("修改头像").performClick()
        assertEquals(2, opened)
        compose.onNodeWithContentDescription("刷新账号资料").performClick()
        assertEquals(1, refreshed)
    }
    @Test fun noAccountImageHasNoRemovalActionAndNoServerConnectionExplainsTheRequirement() {
        var removed = 0
        compose.setContent { PocketTsTheme { AccountAvatarScreen(account, null, {}, {}, { removed++ }, {}, {}) } }
        compose.onNodeWithText("账号头像 · 在线").assertIsDisplayed()
        compose.onNodeWithText("移除账号在线头像").assertDoesNotExist()
        compose.onNodeWithText("进入语音服务器后，可在这里修改该服务器中的头像。").assertExists()
        assertEquals(0, removed)
    }
    @Test fun removalRequiresConfirmationAndCancellationDoesNotModifyTheAccount() {
        var removed = 0
        compose.setContent { PocketTsTheme { AccountAvatarScreen(account.copy(avatarUrl = "https://storage.googleapis.com/fixture/avatar"), null, {}, {}, { removed++ }, {}, {}) } }
        compose.onNodeWithText("移除账号在线头像").performClick()
        compose.onNodeWithText("取消").performClick()
        assertEquals(0, removed)
        compose.onNodeWithText("移除账号在线头像").performClick()
        compose.onNodeWithText("移除", substring = false).performClick()
        assertEquals(1, removed)
    }
}
