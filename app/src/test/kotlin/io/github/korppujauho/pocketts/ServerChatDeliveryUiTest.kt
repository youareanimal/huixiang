package io.github.korppujauho.pocketts

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import io.github.korppujauho.pocketts.proto.Chat
import io.github.korppujauho.pocketts.proto.ChatOutbox
import io.github.korppujauho.pocketts.ui.ChatPanel
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
@Config(qualifiers = "zh-rCN-w360dp-h800dp-mdpi", sdk = [36])
class ServerChatDeliveryUiTest {
    @get:Rule val compose = createComposeRule()
    private val channel = Chat.Key.Channel(1)
    private val friend = Chat.Key.Private("friend=")
    private val fixture = Session.UiState(phase = Session.Phase.CONNECTED, address = "fixture.invalid", ownChannel = 1,
        privateTabs = listOf(Session.PrivateTab("friend=", "Friend")))
    private fun send() = compose.onNodeWithContentDescription(RuntimeEnvironment.getApplication().getString(R.string.chat_send)).performClick()

    @Test fun failurePreservesTextAndOnlyTheMatchingAcknowledgementClearsIt() {
        var state by mutableStateOf(fixture)
        var sends = 0
        compose.setContent { PocketTsTheme {
            ChatPanel(state, channel, {}, {}, { _, _ -> sends++ }, modifier = Modifier.fillMaxSize())
        } }
        compose.onNode(hasSetTextAction()).performTextInput("你好 👋")
        send()
        assertEquals(1, sends)
        compose.onNode(hasSetTextAction()).assertTextContains("你好 👋")
        compose.runOnIdle { state = state.copy(chatDelivery = ChatOutbox.State(sending = setOf(channel))) }
        compose.onNode(hasSetTextAction()).performImeAction()
        assertEquals(1, sends)
        compose.runOnIdle { state = state.copy(chatDelivery = ChatOutbox.State(errors = mapOf(channel to "CHAT_SERVER_2568"))) }
        compose.onNode(hasSetTextAction()).assertTextContains("你好 👋")
        compose.runOnIdle { state = state.copy(chatDelivery = ChatOutbox.State(sent = 1,
            accepted = mapOf(friend to ChatOutbox.Accepted("chat-1", "你好 👋")))) }
        compose.onNode(hasSetTextAction()).assertTextContains("你好 👋")
        compose.runOnIdle { state = state.copy(chatDelivery = ChatOutbox.State(sent = 2,
            accepted = mapOf(channel to ChatOutbox.Accepted("chat-2", "你好 👋")))) }
        compose.onNode(hasSetTextAction()).assertTextEquals("")
        // A newer draft must survive an acknowledgement for earlier text.
        compose.onNode(hasSetTextAction()).performTextInput("new draft")
        compose.runOnIdle { state = state.copy(chatDelivery = ChatOutbox.State(sent = 3,
            accepted = mapOf(channel to ChatOutbox.Accepted("chat-3", "earlier text")))) }
        compose.onNode(hasSetTextAction()).assertTextContains("new draft")
    }

    @Test fun switchingBetweenChannelAndPrivateChatKeepsEachDraftWithItsRecipient() {
        var view by mutableStateOf<Chat.Key>(channel)
        compose.setContent { PocketTsTheme {
            ChatPanel(fixture, view, { view = it }, {}, { _, _ -> }, modifier = Modifier.fillMaxSize())
        } }
        compose.onNode(hasSetTextAction()).performTextInput("channel draft")
        compose.runOnIdle { view = friend }
        compose.onNode(hasSetTextAction()).assertTextEquals("")
        compose.onNode(hasSetTextAction()).performTextInput("private draft")
        compose.runOnIdle { view = channel }
        compose.onNode(hasSetTextAction()).assertTextContains("channel draft")
        compose.runOnIdle { view = friend }
        compose.onNode(hasSetTextAction()).assertTextContains("private draft")
    }
}
