package io.github.korppujauho.pocketts.proto

/** Server acknowledgements decide when a text draft may be cleared. Used on the connection thread. */
class ChatOutbox {
    data class Pending(val code: String, val key: Chat.Key, val text: String)
    data class Accepted(val code: String, val text: String)
    data class State(
        val sending: Set<Chat.Key> = emptySet(), val errors: Map<Chat.Key, String> = emptyMap(),
        val sent: Long = 0, val accepted: Map<Chat.Key, Accepted> = emptyMap(),
    )
    private val pending = linkedMapOf<String, Pending>()
    private var sequence = 0L
    var state = State()
        private set

    fun begin(key: Chat.Key, text: String): Pending? {
        if (text.isBlank() || key in state.sending) return null
        if (text.toByteArray(Charsets.UTF_8).size > MAX_BYTES) {
            reject(key, "CHAT_MESSAGE_TOO_LONG")
            return null
        }
        val item = Pending("chat-${sequence++}", key, text)
        pending[item.code] = item
        state = state.copy(sending = pending.values.map { it.key }.toSet(), errors = state.errors - key)
        return item
    }

    fun reject(key: Chat.Key, error: String) {
        state = state.copy(errors = state.errors + (key to error))
    }

    /** Returns the matched send, including a refusal; unrelated acknowledgements cannot clear a draft. */
    fun answer(command: Command): Pending? {
        if (command.name != "error") return null
        val code = command["return_code"] ?: return null
        val item = pending.remove(code) ?: return null
        state = if (command["id"] == "0") state.copy(
            sending = pending.values.map { it.key }.toSet(), errors = state.errors - item.key,
            sent = state.sent + 1, accepted = state.accepted + (item.key to Accepted(item.code, item.text)),
        ) else state.copy(sending = pending.values.map { it.key }.toSet(),
            errors = state.errors + (item.key to "CHAT_SERVER_${command["id"]?.toIntOrNull() ?: -1}"))
        return item
    }

    fun disconnected(): List<Pending> {
        val interrupted = pending.values.toList()
        state = state.copy(sending = emptySet(), errors = state.errors + pending.values.associate { it.key to "CHAT_DELIVERY_UNCONFIRMED" })
        pending.clear()
        return interrupted
    }

    fun clear() { pending.clear(); state = State() }

    companion object { const val MAX_BYTES = 1024 }
}
