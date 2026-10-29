package io.github.korppujauho.pocketts.proto

/**
 * The chat as WebTS and the TeamSpeak app keep it: a conversation per channel and one per person (private, kept by
 * their identity so a reconnect continues it), with join and leave lines in the channel's. Messages to the whole
 * server are left out, as the TeamSpeak 6 app has no server chat.
 *
 * [handle] must see each command before [ServerModel.apply] does: a move's line needs where the person was.
 */
class Chat(private val keep: Int = 300) {

    sealed interface Key {
        data class Channel(val id: Int) : Key
        data class Private(val uid: String) : Key
    }

    /** A line's pieces: plain text, or a name or channel shown bold. */
    data class Part(val text: String, val bold: Boolean = false)

    data class Entry(
        val id: Long,
        val time: Long,
        val senderId: Int = 0,
        val senderName: String = "",
        val senderUid: String = "",
        val text: String = "", // a message
        val event: List<Part>? = null, // or a join/leave line
        val outgoing: Boolean = false,
    )

    data class Poke(val id: Long, val time: Long, val fromId: Int, val fromName: String, val fromUid: String, val text: String)

    private var nextId = 1L
    private val conversations = LinkedHashMap<Key, ArrayDeque<Entry>>()
    /** Private conversations: their partner's latest name. */
    val privateNames = LinkedHashMap<String, String>()
    // Private messages we sent, oldest first, until the server echoes them back (it doesn't say to whom)
    private data class PrivateSend(val clientId: Int, val uid: String, val code: String)
    private val sentPrivate = ArrayDeque<PrivateSend>()

    var now: () -> Long = { System.currentTimeMillis() }

    fun entries(key: Key): List<Entry> = conversations[key]?.toList() ?: emptyList()
    fun keys(): Set<Key> = conversations.keys

    fun clear() {
        conversations.clear(); privateNames.clear(); sentPrivate.clear()
    }

    /** A private conversation's tab closed: its lines stay (in the history too), and come back when it opens again. */
    fun close(uid: String) {
        privateNames.remove(uid)
    }

    /** The lines kept from before ([ChatHistory]), oldest first and ahead of anything new; they get ids here. */
    fun restore(history: Map<out Key, List<Entry>>) {
        for ((key, lines) in history) {
            val list = conversations.getOrPut(key) { ArrayDeque() }
            list.addAll(0, lines.map { it.copy(id = nextId++, senderId = 0) }) // today's ids are someone else's
            while (list.size > keep) list.removeFirst()
        }
    }

    /** Forgets every line (the history was cleared); open private tabs stay, empty. */
    fun clearLines() {
        conversations.clear()
        for (uid in privateNames.keys) conversations[Key.Private(uid)] = ArrayDeque()
    }

    /** Opens (or keeps) a private conversation with someone. */
    fun open(uid: String, name: String) {
        privateNames[uid] = name
        conversations.getOrPut(Key.Private(uid)) { ArrayDeque() }
    }

    /** Before a private message goes out: whom it's for (the server's echo doesn't tell). */
    fun sendingPrivate(clientId: Int, uid: String, code: String = "") {
        sentPrivate.addLast(PrivateSend(clientId, uid, code))
        while (sentPrivate.size > 20) sentPrivate.removeFirst()
    }

    fun cancelPrivateSend(code: String) {
        sentPrivate.removeAll { it.code == code }
    }

    private fun add(key: Key, entry: Entry): Key {
        val list = conversations.getOrPut(key) { ArrayDeque() }
        list.addLast(entry)
        while (list.size > keep) list.removeFirst()
        return key
    }

    private fun event(cid: Int, parts: List<Part>?): Key? =
        if (parts == null || cid <= 0) null else add(Key.Channel(cid), Entry(nextId++, now(), event = parts))

    /** The results of one command: where lines went, and a poke if it was one. */
    data class Result(val changed: Set<Key>, val poke: Poke? = null, val message: Pair<Key, Entry>? = null)

    fun handle(cmd: Command, model: ServerModel): Result {
        val me = model.ownClientId
        val here = model.ownClient?.channel ?: 0
        fun label(cid: Int) = channelLabel(model.channels[cid]?.name)
        val changed = LinkedHashSet<Key>()

        when (cmd.name) {
            "notifytextmessage" -> {
                val g = cmd.params
                val mode = g["targetmode"]?.toIntOrNull() ?: 2
                val from = g["invokerid"]?.toIntOrNull() ?: 0
                val fromName = g["invokername"] ?: "System"
                val fromUid = g["invokeruid"].orEmpty()
                val outgoing = from == me
                val key = when (mode) {
                    1 -> {
                        val uid = if (outgoing) {
                            val target = g["target"]?.toIntOrNull()
                            val index = if (target == null) 0 else sentPrivate.indexOfFirst { it.clientId == target }
                            val sent = if (index in sentPrivate.indices) sentPrivate.removeAt(index) else null
                            target?.let { model.clients[it]?.uid } ?: sent?.uid
                        } else {
                            fromUid.ifEmpty { model.clients[from]?.uid.orEmpty() }
                        }
                        if (uid.isNullOrEmpty()) return Result(emptySet())
                        if (!outgoing) privateNames[uid] = fromName
                        else if (uid !in privateNames) privateNames[uid] = model.clients.values.firstOrNull { it.uid == uid }?.nickname ?: "Private chat"
                        Key.Private(uid)
                    }
                    3 -> return Result(emptySet()) // to the whole server: no such chat any more
                    else -> if (here > 0) Key.Channel(here) else return Result(emptySet())
                }
                val entry = Entry(nextId++, now(), from, fromName, fromUid, text = g["msg"].orEmpty(), outgoing = outgoing)
                add(key, entry)
                return Result(setOf(key), message = key to entry)
            }

            "notifyclientpoke" -> {
                val g = cmd.params
                return Result(
                    emptySet(),
                    poke = Poke(nextId++, now(), g["invokerid"]?.toIntOrNull() ?: 0, g["invokername"] ?: "Someone", g["invokeruid"].orEmpty(), g["msg"].orEmpty()),
                )
            }

            "notifycliententerview" -> {
                var target = 0
                var reason = 0
                for (g in cmd.groups) {
                    target = g["ctid"]?.toIntOrNull() ?: target
                    reason = g["reasonid"]?.toIntOrNull() ?: reason
                    val id = g["clid"]?.toIntOrNull() ?: continue
                    if (id == me || g["client_type"] == "1" || here <= 0 || target != here) continue
                    val cfid = g["cfid"]?.toIntOrNull() ?: 0
                    val who = g["client_nickname"] ?: "Client $id"
                    event(here, enterEvent(who, if (cfid > 0) label(cfid) else null))?.let { changed.add(it) }
                }
            }

            "notifyclientleftview" -> {
                var target = 0
                var reason = 0
                var invoker = ""
                var why = ""
                for (g in cmd.groups) {
                    target = g["ctid"]?.toIntOrNull() ?: target
                    reason = g["reasonid"]?.toIntOrNull() ?: reason
                    g["invokername"]?.let { invoker = it }
                    g["reasonmsg"]?.let { why = it }
                    val id = g["clid"]?.toIntOrNull() ?: continue
                    val gone = model.clients[id]
                    if (id == me) {
                        event(here, leaveEvent("", true, reason, invoker, why))?.let { changed.add(it) }
                    } else if (gone != null && gone.channel == here && gone.type == 0) {
                        val parts = if (target > 0 && reason in listOf(0, 1, 4)) {
                            moveEvent(Move(gone.nickname, self = false, intoHere = false, reason, label(gone.channel), label(target), invoker, why))
                        } else {
                            leaveEvent(gone.nickname, false, reason, invoker, why)
                        }
                        event(here, parts)?.let { changed.add(it) }
                    }
                }
            }

            "notifyclientmoved" -> {
                var target = 0
                var reason = 0
                var invoker = ""
                var why = ""
                for (g in cmd.groups) {
                    target = g["ctid"]?.toIntOrNull() ?: target
                    reason = g["reasonid"]?.toIntOrNull() ?: reason
                    g["invokername"]?.let { invoker = it }
                    g["reasonmsg"]?.let { why = it }
                    val id = g["clid"]?.toIntOrNull() ?: continue
                    val mover = model.clients[id] ?: continue
                    val m = Move(mover.nickname, false, false, reason, label(mover.channel), label(target), invoker, why)
                    if (id == me) {
                        // Our own move goes into the new channel's chat
                        event(target, moveEvent(m.copy(self = true)))?.let { changed.add(it) }
                        continue
                    }
                    val parts = when {
                        mover.type != 0 -> null
                        target == here -> moveEvent(m.copy(intoHere = true))
                        mover.channel == here -> moveEvent(m)
                        else -> null
                    }
                    event(here, parts)?.let { changed.add(it) }
                }
            }
        }
        return Result(changed)
    }

    data class Move(
        val who: String, val self: Boolean, val intoHere: Boolean, val reason: Int,
        val from: String, val to: String, val invoker: String = "", val reasonMsg: String = "",
    )

    /**
     * How the chat words its event lines, in the app's language. Each is a sentence with the names as `%1$s` and
     * `%2$s` (shown bold), so a language can put them where it wants. English is built in; the app passes its own.
     */
    data class Words(
        val connected: String = "%1\$s connected",
        val joinedFrom: String = "%1\$s joined coming from %2\$s",
        val youMovedTo: String = "You were moved to %1\$s",
        val youKickedFrom: String = "You were kicked from %1\$s",
        val movedHereFrom: String = "%1\$s was moved here from %2\$s",
        val kickedFrom: String = "%1\$s was kicked from %2\$s",
        val movedTo: String = "%1\$s was moved to %2\$s",
        val kickedFromChannel: String = "%1\$s was kicked from the channel",
        val leftTo: String = "%1\$s left to %2\$s",
        val youKickedFromServer: String = "You were kicked from the server",
        val youBanned: String = "You were banned",
        val lostConnection: String = "%1\$s lost connection",
        val kickedFromServer: String = "%1\$s was kicked from the server",
        val banned: String = "%1\$s was banned",
        val disconnected: String = "%1\$s disconnected",
        val by: String = " by %1\$s", // who did it, after the line
        val because: String = " (%1\$s)", // the reason they gave, after the line
        val anotherChannel: String = "another channel", // a channel without a name
    )

    companion object {
        private val SPACER = Regex("""^\[[^\]]*spacer[^\]]*](.*)$""", RegexOption.IGNORE_CASE)
        private val SLOT = Regex("%(\\d)\\\$s")

        /** The words lines are made with (set by the app for its language). */
        @Volatile var words = Words()

        /** A channel's name as the tree shows it (a spacer's tag left out). */
        fun channelLabel(name: String?): String {
            val raw = name.orEmpty()
            val m = SPACER.matchEntire(raw)
            return (m?.groupValues?.get(1)?.trim() ?: raw).ifEmpty { raw }.ifEmpty { words.anotherChannel }
        }

        // A sentence with its names filled in: the names bold, the rest plain
        private fun line(template: String, vararg names: String): List<Part> {
            val out = ArrayList<Part>()
            var at = 0
            for (m in SLOT.findAll(template)) {
                if (m.range.first > at) out.add(Part(template.substring(at, m.range.first)))
                names.getOrNull(m.groupValues[1].toInt() - 1)?.let { out.add(Part(it, bold = true)) }
                at = m.range.last + 1
            }
            if (at < template.length) out.add(Part(template.substring(at)))
            return out
        }

        private fun by(invoker: String) = if (invoker.isNotEmpty()) line(words.by, invoker) else emptyList()
        private fun why(msg: String) = if (msg.isNotEmpty()) listOf(Part(words.because.replace("%1\$s", msg))) else emptyList()

        /** Someone appeared in our channel: connected, or came over from a channel we don't see. */
        fun enterEvent(who: String, from: String?): List<Part> =
            if (from != null) line(words.joinedFrom, who, from) else line(words.connected, who)

        /** A move between channels; null when it needs no line (our own switch: we know). */
        fun moveEvent(m: Move): List<Part>? {
            val w = words
            if (m.self) {
                return when (m.reason) {
                    1 -> line(w.youMovedTo, m.to) + by(m.invoker)
                    4 -> line(w.youKickedFrom, m.from) + by(m.invoker) + why(m.reasonMsg)
                    else -> null
                }
            }
            if (m.intoHere) {
                return when (m.reason) {
                    1 -> line(w.movedHereFrom, m.who, m.from) + by(m.invoker)
                    4 -> line(w.kickedFrom, m.who, m.from) + by(m.invoker) + why(m.reasonMsg)
                    else -> line(w.joinedFrom, m.who, m.from)
                }
            }
            return when (m.reason) {
                1 -> line(w.movedTo, m.who, m.to) + by(m.invoker)
                4 -> line(w.kickedFromChannel, m.who) + by(m.invoker) + why(m.reasonMsg)
                else -> line(w.leftTo, m.who, m.to)
            }
        }

        /** Someone left the server (3 lost, 5 kicked, 6 banned, 8 left, 11 shut down); null when no line. */
        fun leaveEvent(who: String, self: Boolean, reason: Int, invoker: String = "", reasonMsg: String = ""): List<Part>? {
            val w = words
            if (self) {
                return when (reason) {
                    5 -> line(w.youKickedFromServer) + by(invoker) + why(reasonMsg)
                    6 -> line(w.youBanned) + by(invoker) + why(reasonMsg)
                    else -> null
                }
            }
            return when (reason) {
                3 -> line(w.lostConnection, who)
                5 -> line(w.kickedFromServer, who) + by(invoker) + why(reasonMsg)
                6 -> line(w.banned, who) + by(invoker) + why(reasonMsg)
                11 -> null
                else -> line(w.disconnected, who) + why(reasonMsg)
            }
        }
    }
}
