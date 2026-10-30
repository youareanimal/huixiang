package io.github.korppujauho.pocketts

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import io.github.korppujauho.pocketts.proto.MatrixCloud
import io.github.korppujauho.pocketts.proto.MyTsAccount
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
class CloudConversationUiTest {
    @get:Rule val compose = createComposeRule()
    private val room = MatrixCloud.Room("!a:chat-2.tmspk.net", "", false, emptyList(), emptyMap())

    @Test fun failedSendPreservesDraftAndOnlyTheMatchingRoomAndBodyAcknowledgementClearsIt() {
        var sent by mutableIntStateOf(0)
        var ackRoom by mutableStateOf<String?>(null)
        var ackText by mutableStateOf<String?>(null)
        var failure by mutableStateOf<String?>(null)
        var captured = ""
        compose.setContent { PocketTsTheme { Column(Modifier.fillMaxSize()) {
            CloudConversation(room, false, sent, {}, { captured = it }, lastSentRoomId = ackRoom,
                lastSentText = ackText, sendError = failure)
        } } }
        compose.onNode(hasSetTextAction()).performTextInput("你好 👋")
        compose.onNodeWithText("发送").performClick()
        assertEquals("你好 👋", captured)
        compose.runOnIdle { failure = "NETWORK" }
        compose.onNode(hasSetTextAction()).assertTextContains("你好 👋")
        compose.runOnIdle { sent = 1; ackRoom = "!other:chat-2.tmspk.net"; ackText = "你好 👋" }
        compose.onNode(hasSetTextAction()).assertTextContains("你好 👋")
        compose.runOnIdle { ackRoom = room.id; failure = null }
        compose.onNode(hasSetTextAction()).assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
        compose.onNode(hasSetTextAction()).performTextInput("新草稿")
        compose.runOnIdle { sent = 2; ackText = "先前发出的内容" }
        compose.onNode(hasSetTextAction()).assertTextContains("新草稿")
    }

    @Test fun invitedAndEncryptedConversationsCannotSendAndJoiningRequiresExplicitClick() {
        var value by mutableStateOf(room.copy(joined = false))
        var joins = 0; var sends = 0
        compose.setContent { PocketTsTheme { Column(Modifier.fillMaxSize()) {
            CloudConversation(value, false, 0, {}, { sends++ }, onJoin = { joins++ })
        } } }
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.EditableText)).assertIsNotEnabled()
        assertEquals(0, joins)
        compose.onNodeWithText("接受邀请并聊天").performClick()
        assertEquals(1, joins)
        compose.runOnIdle { value = room }
        compose.onNode(hasSetTextAction()).performTextInput("private text")
        compose.runOnIdle { value = room.copy(encrypted = true) }
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.EditableText)).assertIsNotEnabled()
        compose.onNodeWithText("发送").assertIsNotEnabled()
        assertEquals(0, sends)
    }

    @Test fun friendNamesReplaceOpaqueIdsAndEncryptedMessagesDoNotEmitReadReceipts() {
        val peer = "@opaque:chat-2.tmspk.net"
        val friend = MyTsAccount.Contact("", peer, "阿青")
        val value = room.copy(directPeer = peer, members = mapOf(peer to peer))
        assertEquals("阿青", roomTitle(value, "Alice", "@self:chat-2.tmspk.net", listOf(friend)))
        var encrypted by mutableStateOf(true)
        var reads = 0
        compose.setContent { PocketTsTheme { Column(Modifier.fillMaxSize()) {
            CloudConversation(value.copy(encrypted = encrypted, messages = listOf(MatrixCloud.Message("event", peer, "body", 1))),
                false, 0, {}, {}, senderNames = mapOf(peer to "阿青"), onRead = { reads++ })
        } } }
        compose.onNodeWithText("阿青").assertExists()
        assertEquals(0, reads)
        compose.runOnIdle { encrypted = false }
        compose.onNodeWithText("body").assertIsDisplayed()
        compose.waitForIdle()
        assertTrue("A visible unencrypted message should be read", reads >= 1)
    }

    @Test fun readingRequiresTheLatestMessageToBeVisibleAndTheScreenToBeResumed() {
        val owner = object : androidx.lifecycle.LifecycleOwner {
            val registry = androidx.lifecycle.LifecycleRegistry.createUnsafe(this)
            override val lifecycle get() = registry
        }
        owner.registry.currentState = androidx.lifecycle.Lifecycle.State.CREATED
        var messages by mutableStateOf((1..60).map { MatrixCloud.Message("event$it", "peer", "message $it", it.toLong()) })
        var reads = 0
        compose.setContent { CompositionLocalProvider(androidx.lifecycle.compose.LocalLifecycleOwner provides owner) {
            PocketTsTheme { Column(Modifier.fillMaxSize()) {
                CloudConversation(room.copy(messages = messages), false, 0, {}, {}, onRead = { reads++ })
            } }
        } }
        compose.waitForIdle(); assertEquals(0, reads)
        compose.runOnIdle { owner.registry.currentState = androidx.lifecycle.Lifecycle.State.RESUMED }
        compose.onNodeWithText("message 60").assertIsDisplayed()
        compose.waitForIdle(); assertTrue("Resumed latest message should be read", reads > 0)
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(0)
        val before = reads
        compose.runOnIdle { messages = messages + MatrixCloud.Message("new", "peer", "new message", 61) }
        compose.onNodeWithText("new message").assertDoesNotExist()
        assertEquals(before, reads)
        compose.runOnIdle { owner.registry.currentState = androidx.lifecycle.Lifecycle.State.CREATED }
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(messages.size)
        assertEquals(before, reads)
        compose.runOnIdle { owner.registry.currentState = androidx.lifecycle.Lifecycle.State.RESUMED }
        compose.onNodeWithText("new message").assertIsDisplayed()
        compose.waitForIdle(); assertTrue("Resume should mark the visible message read", reads > before)
    }
}
