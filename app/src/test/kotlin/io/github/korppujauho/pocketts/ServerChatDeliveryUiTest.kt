package io.github.korppujauho.pocketts

import androidx.compose.runtime.*
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import io.github.korppujauho.pocketts.proto.Chat
import io.github.korppujauho.pocketts.proto.ChatOutbox
import io.github.korppujauho.pocketts.proto.Commands
import io.github.korppujauho.pocketts.ui.ChatPanel
import io.github.korppujauho.pocketts.ui.PocketTsTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
    private val drafts = ChatDraftStore()
    private val outbox = ChatOutbox()
    private var state by mutableStateOf(fixture)
    private var view by mutableStateOf<Chat.Key>(channel)
    private var showingChat by mutableStateOf(true)
    private val sends = mutableListOf<ChatOutbox.Pending>()
    private val pendingDrafts = mutableMapOf<String, ChatDraftStore.Draft>()

    private fun show() = compose.setContent { PocketTsTheme {
        if (showingChat) ChatPanel(state, view, { view = it }, {}, { key, text ->
            val item = outbox.begin(key, text)
            if (item != null) {
                sends += item
                drafts.capture(state.serverKey, key, text)?.let { pendingDrafts[item.code] = it }
            }
            state = state.copy(chatDelivery = outbox.state)
        }, modifier = Modifier.fillMaxSize(), draftStore = drafts)
        else Text("频道列表")
    } }
    private fun input() = compose.onNode(hasSetTextAction())
    private fun send() = compose.onNodeWithContentDescription(RuntimeEnvironment.getApplication().getString(R.string.chat_send)).performClick()
    private fun answer(id: Int) = compose.runOnIdle {
        val item = outbox.answer(Commands.parse("error id=$id return_code=${sends.last().code}"))!!
        val draft = pendingDrafts.remove(item.code)
        if (id == 0 && draft != null) drafts.accepted(draft)
        state = state.copy(chatDelivery = outbox.state)
    }
    private fun leaveChat() {
        compose.runOnIdle { showingChat = false }
        compose.onNodeWithText("频道列表").assertExists()
        input().assertDoesNotExist()
    }
    private fun returnToChat() = compose.runOnIdle { showingChat = true }

    @Test fun refusedSendSurvivesChannelListAndCanBeResent() {
        show()
        input().performTextInput("你好 👋")
        send()
        input().performImeAction()
        assertEquals(1, sends.size)
        answer(2568)
        leaveChat()
        returnToChat()
        input().assertTextContains("你好 👋")
        leaveChat()
        compose.runOnIdle {
            state = Session.UiState(address = fixture.address)
            outbox.clear()
            pendingDrafts.clear()
        }
        compose.runOnIdle { state = fixture }
        returnToChat()
        input().assertTextContains("你好 👋")
        send()
        assertEquals(2, sends.size)
        assertEquals(channel, sends.last().key)
        assertEquals("你好 👋", sends.last().text)
        answer(0)
        input().assertTextEquals("")
    }

    @Test fun unknownPrivateSendSurvivesPageRemovalAndFreshConnectionAndCanBeResent() {
        view = friend
        show()
        input().performTextInput("private draft")
        send()
        compose.runOnIdle {
            outbox.disconnected()
            pendingDrafts.clear()
            state = state.copy(chatDelivery = outbox.state)
        }
        leaveChat()
        returnToChat()
        input().assertTextContains("private draft")
        leaveChat()
        compose.runOnIdle {
            // Explicit disconnect destroys connection UI state; a fresh connection resets its outbox.
            state = Session.UiState(address = fixture.address)
            outbox.clear()
        }
        compose.runOnIdle { state = fixture; view = friend }
        returnToChat()
        input().assertTextContains("private draft")
        assertEquals(1, sends.size) // Reconnection never automatically resends an uncertain message.
        send()
        assertEquals(2, sends.size)
        assertEquals(friend, sends.last().key)
        assertEquals("private draft", sends.last().text)
        answer(0)
        input().assertTextEquals("")
    }

    @Test fun successfulReceiptWhilePageIsAbsentCannotClearAReenteredDraft() {
        show()
        input().performTextInput("same text")
        send()
        leaveChat()
        answer(0)
        returnToChat()
        input().assertTextEquals("")
        input().performTextInput("same text")
        leaveChat()
        returnToChat()
        input().assertTextContains("same text")
    }

    @Test fun newerEditSurvivesReceiptForAnEarlierRevisionEvenWhenTextMatchesAgain() {
        show()
        input().performTextInput("original")
        send()
        input().performTextReplacement("edited")
        input().performTextReplacement("original")
        answer(0)
        input().assertTextContains("original")
        assertNotNull(drafts.capture(fixture.address, channel, "original"))
    }

    @Test fun draftsAreIsolatedByServerAndConversationAfterPageRemoval() {
        show()
        input().performTextInput("channel draft")
        compose.runOnIdle { view = friend }
        input().assertTextEquals("")
        input().performTextInput("private draft")
        leaveChat()
        compose.runOnIdle { state = fixture.copy(address = "other.invalid") }
        returnToChat()
        input().assertTextEquals("")
        input().performTextInput("other server private")
        compose.runOnIdle { view = channel }
        input().assertTextEquals("")
        input().performTextInput("other server channel")
        leaveChat()
        compose.runOnIdle { state = fixture }
        returnToChat()
        input().assertTextContains("channel draft")
        compose.runOnIdle { view = friend }
        input().assertTextContains("private draft")
        compose.runOnIdle { state = fixture.copy(address = "other.invalid") }
        input().assertTextContains("other server private")
        compose.runOnIdle { view = channel }
        input().assertTextContains("other server channel")
    }
}
