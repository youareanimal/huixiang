package io.github.korppujauho.pocketts

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.roborazziSystemPropertyOutputDirectory
import io.github.korppujauho.pocketts.proto.*
import io.github.korppujauho.pocketts.ui.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "zh-rCN-w360dp-h800dp-mdpi", sdk = [36])
class HubScreenshotTest {
    private val account = CloudAccountController.State(hasAccount = true, username = "小回音", email = "echo@example.com", bookmarksLoaded = true,
        bookmarks = listOf(MyTsBookmarks.Bookmark("demo", "周末放空基地", "voice.example.com", "", "")),
        contactsLoaded = true, contacts = listOf(MyTsAccount.Contact("", "@demo:chat-2.tmspk.net", "", "阿青"), MyTsAccount.Contact("", "@sample:chat-2.tmspk.net", "", "小夏")))

    @OptIn(com.github.takahirom.roborazzi.ExperimentalRoborazziApi::class)
    private fun shot(name: String, tab: HubTab? = null, body: @Composable () -> Unit) =
        captureRoboImage("${roborazziSystemPropertyOutputDirectory()}/huixiang-04-$name.png") {
            PocketTsTheme { Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f)) { body() }
                if (tab != null) HubNavigation(tab) {}
            } }
        }

    @Test fun welcome() = shot("welcome") { EntryScreen({}, {}) }
    @Test fun servers() = shot("servers", HubTab.SERVERS) {
        ServerHub(account, listOf(Favorite("ts.example.com", "朋友们的深夜电台")), emptyList(), {}, {}, {}, {}, {}, {})
    }
    @Test fun friends() = shot("friends", HubTab.FRIENDS) { FriendsHub(account, {}, {}, {}) }
    @Test fun messages() = shot("messages", HubTab.MESSAGES) { MessagesHub(account.copy(cloudError = "HTTP_503"), {}, {}, {}) }
    @Test fun me() = shot("me", HubTab.ME) { MeHub(account, VoiceSettings(), {}, {}, {}, {}, {}) }
    @Test fun voice() = shot("voice", HubTab.SERVERS) { ServerScreen(Example.state(), Example.noActions, onBack = {}) }
}
