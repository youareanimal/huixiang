package io.github.korppujauho.pocketts

import io.github.korppujauho.pocketts.proto.Chat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Session-owned text drafts. Page removal and connection resets never clear this store. */
class ChatDraftStore {
    data class Location(val server: String, val conversation: Chat.Key)
    data class Draft(val location: Location, val text: String, val revision: Long)

    private val mutable = MutableStateFlow<Map<Location, Draft>>(emptyMap())
    val state = mutable.asStateFlow()
    private var revision = 0L

    @Synchronized
    fun edit(server: String, conversation: Chat.Key, text: String) {
        val location = Location(server, conversation)
        if (mutable.value[location]?.text == text) return
        mutable.value = if (text.isEmpty()) mutable.value - location else
            mutable.value + (location to Draft(location, text, ++revision))
    }

    /** Capture when Send is pressed, before the connection thread can receive further edits. */
    @Synchronized
    fun capture(server: String, conversation: Chat.Key, text: String): Draft? =
        mutable.value[Location(server, conversation)]?.takeIf { it.text == text }

    /** Only an acknowledgement of this exact revision may clear it, even while its page is absent. */
    @Synchronized
    fun accepted(sent: Draft) {
        if (mutable.value[sent.location] == sent) mutable.value = mutable.value - sent.location
    }
}
