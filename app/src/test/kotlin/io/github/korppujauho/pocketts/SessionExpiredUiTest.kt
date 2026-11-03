package io.github.korppujauho.pocketts

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import io.github.korppujauho.pocketts.ui.FriendsHub
import io.github.korppujauho.pocketts.ui.MeHub
import io.github.korppujauho.pocketts.ui.PocketTsTheme
import io.github.korppujauho.pocketts.ui.ServerHub
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
class SessionExpiredUiTest {
    @get:Rule val compose = createComposeRule()
    private val expired = CloudAccountController.State(
        hasAccount = true, username = "Alice", sessionExpired = true,
        bookmarkError = "BOOKMARK_SYNC_102", contactError = "CONTACTS_3",
    )

    @Test fun homeOffersReloginInsteadOfRetryingAnExpiredSession() {
        var logins = 0
        var refreshes = 0
        compose.setContent { PocketTsTheme {
            ServerHub(expired, emptyList(), emptyList(), onAdd = {}, onJoin = {}, onRecent = {}, onEdit = {},
                onRefresh = { refreshes++ }, onLogin = { logins++ })
        } }
        compose.onNodeWithText("myTeamSpeak 会话已过期，账号书签和好友需要重新登录后刷新；已有服务器仍可使用。")
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("重新登录").performScrollTo().performClick()
        assertEquals(1, logins)
        assertEquals(0, refreshes)
    }

    @Test fun friendsOffersReloginInsteadOfRetryingAnExpiredSession() {
        var logins = 0
        var refreshes = 0
        compose.setContent { PocketTsTheme {
            FriendsHub(expired, onLogin = { logins++ }, onRefresh = { refreshes++ }, onContact = {})
        } }
        compose.onNodeWithText("myTeamSpeak 会话已过期，好友列表需要重新登录后刷新。")
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("重新登录").performScrollTo().performClick()
        assertEquals(1, logins)
        assertEquals(0, refreshes)
    }

    @Test fun accountCardShowsAnExpiredLoginInsteadOfTheGreenSignedInLabel() {
        compose.setContent { PocketTsTheme { MeHub(expired, VoiceSettings(), {}, {}, {}, {}, {}) } }
        compose.onNodeWithText("登录已过期，请重新验证").assertIsDisplayed()
        compose.onNodeWithText("账号已登录").assertDoesNotExist()
        compose.onNodeWithText("书签、好友和账号资料需要重新登录后刷新。").performScrollTo().assertIsDisplayed()
    }
}
