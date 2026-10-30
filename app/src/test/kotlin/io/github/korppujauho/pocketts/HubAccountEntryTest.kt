package io.github.korppujauho.pocketts

import android.content.Context
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "zh-rCN-w360dp-h800dp-mdpi", sdk = [36])
class HubAccountEntryTest {
    private val compose = createAndroidComposeRule<MainActivity>()
    private val fixture = object : ExternalResource() {
        override fun before() {
            val app = RuntimeEnvironment.getApplication() as Context
            app.getSharedPreferences("hub", Context.MODE_PRIVATE).edit().clear().putBoolean("entered", true).commit()
            app.getSharedPreferences("recent-servers", Context.MODE_PRIVATE).edit().clear().commit()
            RecentServers.record(app, Favorite("fixture.invalid", "历史服务器", "回响访客"))
        }
    }
    @get:Rule val rules: RuleChain = RuleChain.outerRule(fixture).around(compose)

    private fun restored() = compose.waitUntil(5000) { compose.activity.accountModel.controller.state.value.restored }
    private fun signedIn() {
        compose.runOnIdle {
            val mutable = ReflectionHelpers.getField<MutableStateFlow<CloudAccountController.State>>(
                compose.activity.accountModel.controller, "mutable")
            mutable.value = CloudAccountController.State(hasAccount = true, username = "Alice")
        }
        compose.onNodeWithText("一起，回响").assertIsDisplayed()
    }

    @Test fun persistedLegacyLoginDoesNotSkipWelcomeAndGuestRequiresExplicitChoice() {
        restored()
        compose.onNodeWithText("先以访客进入").assertExists()
        compose.onNodeWithText("先以访客进入").performScrollTo().performClick()
        compose.onNodeWithText("一起，回响").assertIsDisplayed()
        compose.runOnIdle { assertTrue(ConnectionEntry(compose.activity).guestEntered) }
    }

    @Test fun loginRemovesGuestAndRecentServerCannotPrefillVisitorNickname() {
        restored()
        compose.onNodeWithText("先以访客进入").performScrollTo().performClick()
        signedIn()
        compose.onNodeWithText("先以访客进入").assertDoesNotExist()
        compose.runOnIdle { assertFalse(ConnectionEntry(compose.activity).guestEntered) }
        compose.onNodeWithText("历史服务器").performScrollTo().performClick()
        compose.onNodeWithText("使用当前账号：Alice").assertExists()
        compose.onNodeWithText("回响访客").assertDoesNotExist()
        compose.onNodeWithText("连接昵称").assertDoesNotExist()
        compose.runOnIdle { assertEquals(Session.Phase.IDLE, Session.state.value.phase) }
    }

    @Test fun logoutReturnsToWelcomeAndDoesNotKeepAnEarlierGuestAuthorization() {
        restored()
        compose.onNodeWithText("先以访客进入").performScrollTo().performClick()
        signedIn()
        compose.onNodeWithText("我的").performClick()
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("退出本机账号"))
        compose.onNodeWithText("退出本机账号").performClick()
        compose.onNodeWithText("退出账号").performClick()
        compose.waitUntil(5000) { !compose.activity.accountModel.controller.state.value.hasAccount }
        compose.onNodeWithText("先以访客进入").assertExists()
        compose.onNodeWithText("登录 TeamSpeak 账号").assertExists()
        compose.runOnIdle {
            assertFalse(ConnectionEntry(compose.activity).guestEntered)
            assertEquals(Session.Phase.IDLE, Session.state.value.phase)
        }
    }

    @Test fun switchingAccountDoesNotKeepAnOldConnectionAttemptUnderTheNewAccount() {
        restored()
        signedIn()
        compose.runOnIdle {
            ReflectionHelpers.getField<MutableStateFlow<Session.UiState>>(Session, "_state").value =
                Session.UiState(phase = Session.Phase.CONNECTING, address = "fixture.invalid")
            ReflectionHelpers.getField<MutableStateFlow<CloudAccountController.State>>(
                compose.activity.accountModel.controller, "mutable").value =
                CloudAccountController.State(hasAccount = true, username = "Bob")
        }
        compose.waitUntil(5000) { Session.state.value.phase == Session.Phase.IDLE }
        compose.onNodeWithText("Bob，今天去哪里聊？").assertExists()
        compose.runOnIdle { assertFalse(ConnectionEntry(compose.activity).guestEntered) }
    }
}
