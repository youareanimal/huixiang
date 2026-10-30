package io.github.korppujauho.pocketts.proto

import kotlin.test.*

class ChatOutboxTest {
    private val channel = Chat.Key.Channel(1)
    private val friend = Chat.Key.Private("friend=")

    @Test fun onlyAMatchingSuccessfulServerAcknowledgementMayClearADraft() {
        val outbox = ChatOutbox()
        val pending = outbox.begin(channel, "你好 👋")!!
        assertNull(outbox.begin(channel, "double tap"))
        assertNull(outbox.answer(Commands.parse("error id=0 return_code=unrelated")))
        assertTrue(outbox.state.accepted.isEmpty()); assertTrue(channel in outbox.state.sending)
        outbox.answer(Commands.parse("error id=2568 return_code=${pending.code}"))
        assertEquals("CHAT_SERVER_2568", outbox.state.errors[channel])
        assertTrue(outbox.state.accepted.isEmpty()); assertTrue(outbox.state.sending.isEmpty())
        val retry = outbox.begin(channel, "你好 👋")!!
        assertNotEquals(pending.code, retry.code)
        outbox.answer(Commands.parse("error id=0 return_code=${retry.code}"))
        assertEquals("你好 👋", outbox.state.accepted[channel]?.text)
        assertEquals(1L, outbox.state.sent)
        assertNull(outbox.answer(Commands.parse("error id=0 return_code=${retry.code}")))
    }

    @Test fun repliesForDifferentConversationsCanArriveInEitherOrder() {
        val outbox = ChatOutbox()
        val a = outbox.begin(channel, "channel text")!!
        val b = outbox.begin(friend, "private text")!!
        outbox.answer(Commands.parse("error id=0 return_code=${b.code}"))
        outbox.answer(Commands.parse("error id=0 return_code=${a.code}"))
        assertEquals("private text", outbox.state.accepted[friend]?.text)
        assertEquals("channel text", outbox.state.accepted[channel]?.text)
        assertEquals(2L, outbox.state.sent)
    }

    @Test fun disconnectLeavesUncertainMessagesUnacknowledgedAndDoesNotAutomaticallyResend() {
        val outbox = ChatOutbox()
        val pending = outbox.begin(friend, "keep this draft")!!
        assertEquals(listOf(pending), outbox.disconnected())
        assertTrue(outbox.state.sending.isEmpty()); assertTrue(outbox.state.accepted.isEmpty())
        assertEquals("CHAT_DELIVERY_UNCONFIRMED", outbox.state.errors[friend])
        assertNull(outbox.answer(Commands.parse("error id=0 return_code=${pending.code}")))
    }

    @Test fun oversizedUnicodeMessagesAreRejectedInsteadOfSilentlyTruncated() {
        val outbox = ChatOutbox()
        assertNull(outbox.begin(channel, "你".repeat(342)))
        assertEquals("CHAT_MESSAGE_TOO_LONG", outbox.state.errors[channel])
        assertEquals("你".repeat(341), outbox.begin(channel, "你".repeat(341))?.text)
    }
}
