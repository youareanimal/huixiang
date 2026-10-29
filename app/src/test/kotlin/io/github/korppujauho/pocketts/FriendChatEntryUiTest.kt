package io.github.korppujauho.pocketts

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import io.github.korppujauho.pocketts.proto.MatrixCloud
import io.github.korppujauho.pocketts.proto.MyTsAccount
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "zh-rCN-w360dp-h800dp-mdpi", sdk = [36])
class FriendChatEntryUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun friendActionStartsCloudChatAndTheAcknowledgedRoomOpensInTheConversation() {
        val friend = MyTsAccount.Contact("fixture", "@friend:chat-2.tmspk.net", "阿青")
        val controller = compose.activity.accountModel.controller
        compose.waitUntil(5000) { controller.state.value.restored && !controller.state.value.busy }
        val mutable = ReflectionHelpers.getField<MutableStateFlow<CloudAccountController.State>>(controller, "mutable")
        compose.runOnIdle { mutable.value = CloudAccountController.State(hasAccount = true, username = "Alice", contacts = listOf(friend), contactsLoaded = true) }
        compose.onNodeWithText("好友").performClick()
        compose.onNodeWithText("阿青").performClick()
        compose.onNodeWithText("发消息").performClick()
        // This UI fixture has no credentials: the action must fail locally without a real account request.
        compose.waitUntil(5000) { controller.state.value.cloudError == "MYTS_103" }
        compose.runOnIdle { mutable.value = mutable.value.copy(cloudConnected = true, cloudUserId = "@self:chat-2.tmspk.net", cloudError = null,
            rooms = listOf(MatrixCloud.Room("!room:chat-2.tmspk.net", "", false, emptyList(), emptyMap(), directPeer = friend.matrixId)),
            openRoomId = "!room:chat-2.tmspk.net") }
        compose.onNodeWithText("发送云端消息…").assertExists()
        compose.onNodeWithText("阿青").assertExists()
    }
}
