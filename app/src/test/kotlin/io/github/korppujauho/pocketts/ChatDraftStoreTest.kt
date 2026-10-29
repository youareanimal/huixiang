package io.github.korppujauho.pocketts

import io.github.korppujauho.pocketts.proto.Chat
import org.junit.Assert.*
import org.junit.Test

class ChatDraftStoreTest {
    @Test fun receiptOnlyClearsItsServerAndConversationAndIsNotReplayed() {
        val store = ChatDraftStore()
        val channel = Chat.Key.Channel(1)
        val peer = Chat.Key.Private("friend=")
        store.edit("one", channel, "same")
        store.edit("two", channel, "same")
        store.edit("one", peer, "same")
        val sent = store.capture("one", channel, "same")!!
        store.accepted(sent)
        assertNull(store.capture("one", channel, "same"))
        assertNotNull(store.capture("two", channel, "same"))
        assertNotNull(store.capture("one", peer, "same"))
        store.edit("one", channel, "same")
        store.accepted(sent)
        assertNotNull(store.capture("one", channel, "same"))
    }

    @Test fun clearingAndRetypingTheSameTextCreatesANewDraftRevision() {
        val store = ChatDraftStore()
        val channel = Chat.Key.Channel(1)
        store.edit("one", channel, "text")
        val sent = store.capture("one", channel, "text")!!
        assertNull(store.capture("one", channel, "different"))
        store.edit("one", channel, "")
        assertTrue(store.state.value.isEmpty())
        store.edit("one", channel, "text")
        store.accepted(sent)
        assertNotEquals(sent, store.capture("one", channel, "text"))
        assertEquals("text", store.state.value.values.single().text)
    }
}
