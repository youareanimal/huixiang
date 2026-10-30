package io.github.korppujauho.pocketts

import android.content.Context
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import io.github.korppujauho.pocketts.ui.ConnectActions
import io.github.korppujauho.pocketts.ui.ConnectScreen
import io.github.korppujauho.pocketts.ui.PocketTsTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "en-w411dp-h891dp-xxhdpi", sdk = [36])
class AccountNicknameUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun loginUpdatesAnAlreadyOpenFormAndClearingTheFieldUsesTheAccountName() {
        val context = RuntimeEnvironment.getApplication() as Context
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().clear().commit()
        val settings = Settings(context)
        var connectedAs = ""
        val actions = object : ConnectActions {
            override fun connect(address: String, nickname: String, password: String, remember: Boolean, favorite: Boolean) { connectedAs = nickname }
            override fun connectFavorite(f: Favorite) {}
            override fun saveFavorite(old: Favorite?, new: Favorite) {}
            override fun removeFavorite(f: Favorite) {}
            override fun toggleAuto(f: Favorite) {}
        }
        compose.setContent {
            val names = rememberConnectionNames(context)
            PocketTsTheme {
                ConnectScreen(names.first, "ts.example.com", "", emptyList(), null, actions, accountUsername = names.second)
            }
        }
        compose.runOnIdle { settings.applyAccountUsername("Alice", overwrite = true) }
        val nickname = compose.onAllNodes(hasSetTextAction()).onFirst()
        nickname.assertTextContains("Alice")
        compose.onNodeWithText("ts.example.com").assertExists()
        nickname.performTextClearance()
        compose.onNodeWithText("Connect", substring = false).performScrollTo().assertIsEnabled().performClick()
        assertEquals("Alice", connectedAs)
        nickname.performTextReplacement("Custom alias")
        compose.onNodeWithText("Connect", substring = false).performScrollTo().performClick()
        assertEquals("Custom alias", connectedAs)
    }
}
