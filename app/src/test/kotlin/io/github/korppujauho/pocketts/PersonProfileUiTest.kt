package io.github.korppujauho.pocketts

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import io.github.korppujauho.pocketts.proto.ServerModel
import io.github.korppujauho.pocketts.ui.*
import org.junit.Rule
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "zh-rCN-w360dp-h800dp-mdpi", sdk = [36])
class PersonProfileUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun tappingUserShowsLiveProfileAndReusedClientIdCannotKeepTheOldSheetOpen() {
        val own = ServerModel.Client(1, 1, "Alice", uid = "own-uid")
        val friend = ServerModel.Client(2, 1, "好友", uid = "friend-uid", serverGroups = listOf(6), channelGroup = 8,
            country = "CN", platform = "Android", version = "6.0")
        val channel = ServerModel.Channel(1, 0, 0, "一起聊天")
        var state by mutableStateOf(Session.UiState(phase = Session.Phase.CONNECTED, ownId = 1, ownChannel = 1,
            tree = listOf(ServerModel.Node(channel, listOf(own, friend), emptyList())),
            serverGroupNames = mapOf(6 to "朋友"), channelGroupNames = mapOf(8 to "普通成员")))
        val requested = mutableListOf<String>()
        val actions = object : ServerActions by Example.noActions {
            override fun personDetails(person: ServerModel.Client) { requested += person.uid }
        }
        compose.setContent { PocketTsTheme { ServerScreen(state, actions) } }
        compose.onNodeWithText("好友").performClick()
        compose.onNodeWithText("用户资料").assertExists()
        compose.onNodeWithText("唯一身份 UID：friend-uid").assertExists()
        compose.onNodeWithText("当前频道：一起聊天").assertExists()
        compose.onNodeWithText("服务器身份组：朋友").assertExists()
        compose.onNodeWithText("客户端：Android · 6.0").assertExists()
        assertEquals(listOf("friend-uid"), requested)
        compose.runOnIdle { state = state.copy(tree = listOf(ServerModel.Node(channel,
            listOf(own, friend.copy(country = "JP")), emptyList()))) }
        compose.onNodeWithText("国家/地区：JP").assertExists()
        compose.runOnIdle { state = state.copy(tree = listOf(ServerModel.Node(channel,
            listOf(own, friend.copy(uid = "different-person")), emptyList()))) }
        compose.onNodeWithText("用户资料").assertDoesNotExist()
    }

    @Test fun ownEmailNeverAppearsOnAnotherUsersProfileAndUnknownServerFieldsStayUnknown() {
        var self by mutableStateOf(true)
        compose.setContent {
            PocketTsTheme { CompositionLocalProvider(LocalVoiceAccountProfile provides
                VoiceAccountProfile(1, "Alice", "private@example.com")) {
                PersonDetails(ServerModel.Client(if (self) 1 else 2, 1, if (self) "Alice" else "Friend"), self, "", emptyList(), "")
            } }
        }
        compose.onNodeWithText("账号邮箱：private@example.com").assertExists()
        compose.runOnIdle { self = false }
        compose.onNodeWithText("账号邮箱：private@example.com").assertDoesNotExist()
        compose.onNodeWithText("当前账号：Alice").assertDoesNotExist()
        compose.onNodeWithText("客户端：服务器未提供").assertExists()
    }
}
