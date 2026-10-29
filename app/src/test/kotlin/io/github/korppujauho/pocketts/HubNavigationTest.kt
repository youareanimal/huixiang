package io.github.korppujauho.pocketts

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "zh-rCN-w360dp-h800dp-mdpi", sdk = [36])
class HubNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private fun restored() = compose.waitUntil(5000) { compose.activity.accountModel.controller.state.value.restored }

    @Test fun visitorCanNavigateAllTabsAndReturnFromGroupedSettingsWithoutStartingVoiceOrChat() {
        restored()
        compose.onNodeWithText("先以访客进入").performScrollTo().performClick()
        compose.onNodeWithText("一起，回响").assertIsDisplayed()
        compose.onNodeWithText("好友").performClick()
        compose.onNodeWithText("登录，和好友保持联系").assertIsDisplayed()
        compose.onNodeWithText("消息").performClick()
        compose.onNodeWithText("好聊的话题，接着聊").assertIsDisplayed()
        compose.onNodeWithText("我的").performClick()
        compose.onNodeWithText("你好，访客").assertExists()
        compose.onNodeWithText("界面与语言").performScrollTo().performClick()
        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNodeWithText("你好，访客").assertExists()
        compose.runOnIdle {
            assertEquals(Session.Phase.IDLE, Session.state.value.phase)
            assertEquals(false, compose.activity.accountModel.controller.state.value.cloudConnected)
        }
    }

    @Test fun cancellingLoginReturnsToWelcomeAndPasswordDoesNotSurviveReopening() {
        restored()
        compose.onNodeWithText("登录 TeamSpeak 账号").performScrollTo().performClick()
        compose.onNodeWithText("密码").performTextInput("fixture-password")
        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNodeWithText("先以访客进入").assertExists()
        compose.onNodeWithText("登录 TeamSpeak 账号").performScrollTo().performClick()
        compose.onNodeWithText("fixture-password").assertDoesNotExist()
        compose.onNodeWithText("登录，开启回响").assertIsNotEnabled()
    }

    @Test fun eyeRevealsAndHidesTheSamePasswordAndBackgroundingMasksItAgain() {
        restored()
        compose.onNodeWithText("登录 TeamSpeak 账号").performScrollTo().performClick()
        fun assertRenderedPassword(hidden: Boolean) {
            val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            compose.onNodeWithText("密码").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertEquals(if (hidden) "•".repeat("fixture-password".length) else "fixture-password", layouts.single().layoutInput.text.text)
        }
        compose.onNodeWithText("密码").performTextInput("fixture-password")
        assertRenderedPassword(true)
        compose.onNodeWithContentDescription("显示密码").performClick()
        assertRenderedPassword(false)
        compose.onNodeWithContentDescription("隐藏密码").performClick()
        assertRenderedPassword(true)
        compose.onNodeWithContentDescription("显示密码").performClick()
        compose.runOnIdle {
            (compose.activity.lifecycle as androidx.lifecycle.LifecycleRegistry).handleLifecycleEvent(androidx.lifecycle.Lifecycle.Event.ON_PAUSE)
            (compose.activity.lifecycle as androidx.lifecycle.LifecycleRegistry).handleLifecycleEvent(androidx.lifecycle.Lifecycle.Event.ON_RESUME)
        }
        assertRenderedPassword(true)
        compose.onNodeWithContentDescription("显示密码").performClick()
        assertRenderedPassword(false)
        compose.onNodeWithText("fixture-password").assertExists()
    }
}
