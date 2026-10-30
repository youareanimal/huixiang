package io.github.korppujauho.pocketts.proto

import io.github.korppujauho.pocketts.proto.Chat.Entry
import io.github.korppujauho.pocketts.proto.Chat.Key

/**
 * The chat kept between connections, as WebTS's chat history keeps it: per server, each channel's chat and each private
 * chat (by the other person's identity) with the join and leave lines, at most [DAYS] days and [LINES] lines per
 * conversation, shown again at the next connect. Pokes aren't kept, and there's no chat for the whole server.
 */
object ChatHistory {
    const val DAYS = 90
    const val LINES = 300
    const val CONVERSATIONS = 100 // per server: the ones quiet the longest go first
    private const val DAY = 86_400_000L

    /**
     * Which server a history belongs to: the address as typed, without what doesn't change the server (case, a
     * ts3server:// prefix, the default port written out). Null when it isn't an address.
     */
    fun serverKey(address: String): String? {
        val typed = ServerAddress.parse(address) ?: return null
        val host = typed.host.trimEnd('.').lowercase()
        val port = typed.port?.takeIf { it != ServerAddress.DEFAULT_PORT }
        return when {
            port == null -> host
            ':' in host -> "[$host]:$port"
            else -> "$host:$port"
        }
    }

    /** Lines older than [days] go, then each conversation keeps its newest [lines], and at most [conversations] stay. */
    fun trim(
        history: Map<out Key, List<Entry>>, now: Long,
        days: Int = DAYS, lines: Int = LINES, conversations: Int = CONVERSATIONS,
    ): Map<Key, List<Entry>> {
        val from = now - days * DAY
        return history.mapValues { (_, list) -> list.filter { it.time >= from }.takeLast(lines) }
            .filterValues { it.isNotEmpty() }
            .entries.sortedByDescending { it.value.last().time }
            .take(conversations)
            .associate { it.key to it.value }
    }

    /** The history as text (JSON), for the file. Who sent a line is kept by name and identity, not by today's id. */
    fun encode(history: Map<out Key, List<Entry>>): String = Json.write(
        mapOf(
            "version" to 1,
            "conversations" to history.map { (key, list) ->
                val which = when (key) {
                    is Key.Channel -> "channel" to key.id
                    is Key.Private -> "private" to key.uid
                }
                mapOf(which, "lines" to list.map { e ->
                    buildMap {
                        put("time", e.time)
                        if (e.event != null) {
                            put("event", e.event.map { mapOf("text" to it.text, "bold" to it.bold) })
                        } else {
                            put("name", e.senderName)
                            put("uid", e.senderUid)
                            put("text", e.text)
                            if (e.outgoing) put("outgoing", true)
                        }
                    }
                })
            },
        )
    )

    /** What [encode] wrote (the lines' ids are 0: the chat gives them its own). Throws when it isn't a history. */
    fun decode(text: String): Map<Key, List<Entry>> {
        val root = Json.parse(text) as? Map<*, *> ?: throw IllegalArgumentException("Not a chat history")
        require(root["version"] == 1L) { "Unknown chat history version" }
        val out = LinkedHashMap<Key, List<Entry>>()
        for (c in root["conversations"] as? List<*> ?: emptyList<Any>()) {
            c as? Map<*, *> ?: continue
            val channel = (c["channel"] as? Long)?.toInt()
            val uid = c["private"] as? String
            val key = when {
                channel != null && channel > 0 -> Key.Channel(channel)
                !uid.isNullOrEmpty() -> Key.Private(uid)
                else -> continue
            }
            val lines = (c["lines"] as? List<*> ?: emptyList<Any>()).mapNotNull { l ->
                l as? Map<*, *> ?: return@mapNotNull null
                val time = l["time"] as? Long ?: return@mapNotNull null
                val event = (l["event"] as? List<*>)?.mapNotNull { p ->
                    (p as? Map<*, *>)?.let { Chat.Part(it["text"] as? String ?: "", it["bold"] == true) }
                }
                if (event != null) {
                    Entry(0, time, event = event)
                } else {
                    Entry(
                        0, time, senderName = l["name"] as? String ?: "", senderUid = l["uid"] as? String ?: "",
                        text = l["text"] as? String ?: "", outgoing = l["outgoing"] == true,
                    )
                }
            }
            if (lines.isNotEmpty()) out[key] = lines
        }
        return out
    }
}
