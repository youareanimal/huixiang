package io.github.korppujauho.pocketts.proto

import java.net.URLEncoder

/** Official TeamSpeak cloud rooms over Matrix. Encrypted rooms are explicitly read-only in this first version. */
class MatrixCloud(private val domain: String, private val http: CloudTransport = HttpsCloudTransport) {
    init { require(CloudAccountTransfer.officialDomain(domain)) }
    class Session(val userId: String, val token: String, val deviceId: String) {
        override fun toString() = "MatrixSession(redacted)"
    }
    data class Message(val id: String, val sender: String, val body: String, val timestamp: Long, val encrypted: Boolean = false)
    data class Room(
        val id: String, val name: String, val encrypted: Boolean, val messages: List<Message>,
        val members: Map<String, String>, val unread: Int = 0,
        val joined: Boolean = true, val directPeer: String = "", val hasMoreHistory: Boolean = false,
    )
    data class Snapshot(val rooms: List<Room>, val directRooms: Map<String, List<String>>, val cursor: String)
    private val rooms = linkedMapOf<String, Room>()
    private val historyTokens = mutableMapOf<String, String>()
    private val exhaustedHistory = mutableSetOf<String>()
    private var directRooms = emptyMap<String, List<String>>()
    private var directData = emptyMap<String, Any?>()
    private val createdRooms = mutableMapOf<String, String>()
    private val uncertainCreations = mutableSetOf<String>()
    private var serverUrl = "https://$domain"

    /** Explicit connection setup; the Matrix user ID and account domain are never rewritten. */
    fun discoverServer() {
        serverUrl = MatrixServerDiscovery.resolve(domain, http)
    }

    fun login(account: CloudAccountTransfer, deviceId: String): Session {
        require(account.domain == domain && deviceId.matches(Regex("[A-Za-z0-9_-]{8,100}")))
        // A 5xx response does not prove that the server did not process a login.
        // Do not create multiple device sessions through automatic POST retries.
        val data = request("POST", "/login", mapOf(
            "type" to "m.login.password", "identifier" to mapOf("type" to "m.id.user", "user" to account.userId),
            "password" to account.password, "device_id" to deviceId, "initial_device_display_name" to "回响 Android",
        ))
        val user = data["user_id"] as? String ?: throw CloudFailure("MATRIX_INVALID_REPLY")
        val token = data["access_token"] as? String ?: throw CloudFailure("MATRIX_INVALID_REPLY")
        val device = data["device_id"] as? String ?: throw CloudFailure("MATRIX_INVALID_REPLY")
        if (user != account.userId || token.isBlank() || device != deviceId) throw CloudFailure("MATRIX_WRONG_ACCOUNT")
        return Session(user, token, device)
    }

    fun sync(session: Session, since: String = ""): Snapshot {
        val filter = Json.write(mapOf("room" to mapOf("timeline" to mapOf("limit" to 30), "state" to mapOf("lazy_load_members" to true))))
        val path = "/sync?timeout=0&set_presence=offline&filter=${quote(filter)}" + if (since.isEmpty()) "" else "&since=${quote(since)}"
        val data = request("GET", path, token = session.token)
        for (event in data["account_data"].obj()["events"].list()) {
            val e = event.obj()
            if (e["type"] == "m.direct") readDirectData(e["content"])
        }
        val updates = data["rooms"].obj()
        for (id in updates["leave"].obj().keys.filterIsInstance<String>()) {
            rooms.remove(id); historyTokens.remove(id); exhaustedHistory.remove(id)
        }
        for ((key, value) in updates["invite"].obj()) {
            val id = key as? String ?: continue
            val events = value.obj()["invite_state"].obj()["events"].list()
            val invite = events.map { it.obj() }.firstOrNull {
                it["type"] == "m.room.member" && it["state_key"] == session.userId && it["content"].obj()["membership"] == "invite"
            }
            val peer = if (invite?.get("content").obj()["is_direct"] == true) invite?.get("sender") as? String ?: "" else ""
            rooms[id] = roomFromState(id, events, rooms[id]).copy(joined = false, directPeer = peer)
        }
        for ((key, value) in updates["join"].obj()) {
            val id = key as? String ?: continue
            val room = value.obj()
            val historyToken = room["timeline"].obj()["prev_batch"] as? String
            if (!historyToken.isNullOrBlank() && id !in exhaustedHistory && !historyTokens.containsKey(id)) historyTokens[id] = historyToken
            val previous = rooms[id] ?: Room(id, "", false, emptyList(), emptyMap())
            val state = room["state"].obj()["events"].list() + room["timeline"].obj()["events"].list()
            val members = previous.members.toMutableMap()
            var name = previous.name
            var encrypted = previous.encrypted
            for (event in state) {
                val e = event.obj(); val content = e["content"].obj()
                when (e["type"]) {
                    "m.room.name" -> name = content["name"] as? String ?: name
                    "m.room.encryption" -> encrypted = true
                    "m.room.member" -> {
                        val member = e["state_key"] as? String ?: continue
                        if (content["membership"] == "leave" || content["membership"] == "ban") members.remove(member)
                        else members[member] = content["displayname"] as? String ?: member
                    }
                }
            }
            val incoming = room["timeline"].obj()["events"].list().mapNotNull(::message)
            encrypted = encrypted || incoming.any { it.encrypted }
            val messages = mergeMessages(previous.messages, incoming)
            val unread = (room["unread_notifications"].obj()["notification_count"] as? Number)?.toInt() ?: previous.unread
            val peer = previous.directPeer.ifBlank { directRooms.entries.firstOrNull { id in it.value }?.key.orEmpty() }
            rooms[id] = Room(id, name, encrypted, messages, members, unread, joined = true, directPeer = peer,
                hasMoreHistory = id in historyTokens && messages.size < MESSAGE_LIMIT)
        }
        val cursor = data["next_batch"] as? String ?: throw CloudFailure("MATRIX_INVALID_REPLY")
        return Snapshot(rooms.values.sortedByDescending { it.messages.lastOrNull()?.timestamp ?: 0 }, directRooms, cursor)
    }

    fun history(session: Session, roomId: String, from: String = ""): List<Message> {
        require(rooms.containsKey(roomId))
        if (rooms.getValue(roomId).messages.size >= MESSAGE_LIMIT) throw CloudFailure("HISTORY_EXHAUSTED")
        val token = from.ifEmpty { historyTokens[roomId].orEmpty() }
        if (token.isEmpty()) throw CloudFailure("HISTORY_EXHAUSTED")
        val r = request("GET", "/rooms/${quote(roomId)}/messages?dir=b&limit=50&from=${quote(token)}", token = session.token)
        val next = r["end"] as? String
        if (next.isNullOrBlank() || next == token) { historyTokens.remove(roomId); exhaustedHistory.add(roomId) }
        else historyTokens[roomId] = next
        val page = r["chunk"].list().mapNotNull(::message)
        val room = rooms.getValue(roomId)
        val messages = mergeMessages(page, room.messages)
        rooms[roomId] = room.copy(encrypted = room.encrypted || page.any { it.encrypted }, messages = messages,
            hasMoreHistory = roomId in historyTokens && messages.size < MESSAGE_LIMIT)
        return rooms.getValue(roomId).messages
    }

    /** The caller retains transactionId for retry, preventing duplicate messages on an uncertain network outcome. */
    fun sendText(session: Session, roomId: String, body: String, transactionId: String) {
        val room = rooms[roomId] ?: throw CloudFailure("UNKNOWN_ROOM")
        if (room.encrypted) throw CloudFailure("ENCRYPTED_ROOM")
        if (!room.joined) throw CloudFailure("ROOM_NOT_JOINED")
        require(body.isNotBlank() && body.length <= 4096 && transactionId.matches(Regex("[A-Za-z0-9_-]{8,100}")))
        // Refresh membership/encryption immediately before sending, rather than trusting a stale sync cache.
        val latest = refreshRoom(session, roomId, room.directPeer)
        if (latest.encrypted) throw CloudFailure("ENCRYPTED_ROOM")
        val sent = request("PUT", "/rooms/${quote(roomId)}/send/m.room.message/${quote(transactionId)}",
            mapOf("msgtype" to "m.text", "body" to body), session.token)
        val id = sent["event_id"] as? String ?: throw CloudFailure("MATRIX_INVALID_REPLY")
        if (id.isBlank() || id.length > 1024) throw CloudFailure("MATRIX_INVALID_REPLY")
        rooms[roomId] = latest.copy(messages = mergeMessages(latest.messages,
            listOf(Message(id, session.userId, body, System.currentTimeMillis()))))
    }

    fun current() = rooms.values.toList()
    fun currentDirectRooms() = directRooms

    /** Reuse joined/invited DMs. Only an explicit user action may create a private room and invite a friend. */
    fun openDirect(session: Session, peer: String): Room {
        if (!validPeer(peer) || peer == session.userId) throw CloudFailure("CONTACT_NO_CHAT")
        val data = try { request("GET", "/user/${quote(session.userId)}/account_data/m.direct", token = session.token) }
            catch (e: CloudFailure) { if (e.code == "M_NOT_FOUND") emptyMap<String, Any?>() else throw e }
        readDirectData(data)
        val candidates = (listOfNotNull(createdRooms[peer]) + directRooms[peer].orEmpty() +
            rooms.values.filter { it.directPeer == peer || (peer in it.members && it.members.keys.all { m -> m == peer || m == session.userId }) }.map { it.id }).distinct()
        for (id in candidates) {
            val known = rooms[id]
            if (known?.joined == false) joinRoom(session, id, peer)
            val room = try { refreshRoom(session, id, peer) } catch (e: CloudFailure) {
                if (id == createdRooms[peer]) throw e
                if (e.code in setOf("NOT_DIRECT_ROOM", "M_NOT_FOUND")) continue else throw e
            }
            saveDirect(session, peer, room.id)
            uncertainCreations.remove(peer)
            return room
        }
        if (peer in uncertainCreations) throw CloudFailure("CHAT_CREATE_UNCERTAIN")
        uncertainCreations.add(peer)
        val result = try { request("POST", "/createRoom", mapOf(
            "visibility" to "private", "preset" to "trusted_private_chat", "is_direct" to true, "invite" to listOf(peer),
        ), session.token) } catch (e: CloudFailure) {
            if (e.code in setOf("M_FORBIDDEN", "M_LIMIT_EXCEEDED", "M_UNKNOWN_TOKEN", "M_BAD_JSON", "M_INVALID_PARAM")) uncertainCreations.remove(peer)
            throw e
        }
        val id = result["room_id"] as? String ?: throw CloudFailure("MATRIX_INVALID_REPLY")
        if (!validRoom(id)) throw CloudFailure("MATRIX_INVALID_REPLY")
        createdRooms[peer] = id // Keep the acknowledged room even if later state/account-data requests fail.
        uncertainCreations.remove(peer)
        val room = refreshRoom(session, id, peer)
        saveDirect(session, peer, id)
        return room
    }

    fun joinRoom(session: Session, roomId: String, peer: String = rooms[roomId]?.directPeer.orEmpty()): Room {
        if (!validRoom(roomId) || rooms[roomId]?.joined != false) throw CloudFailure("UNKNOWN_ROOM")
        val reply = request("POST", "/join/${quote(roomId)}", emptyMap(), session.token)
        if (reply["room_id"] != roomId) throw CloudFailure("MATRIX_INVALID_REPLY")
        val room = refreshRoom(session, roomId, peer)
        if (peer.isNotBlank()) {
            val data = try { request("GET", "/user/${quote(session.userId)}/account_data/m.direct", token = session.token) }
                catch (e: CloudFailure) { if (e.code == "M_NOT_FOUND") emptyMap<String, Any?>() else throw e }
            readDirectData(data); saveDirect(session, peer, roomId)
        }
        return room
    }

    fun markRead(session: Session, roomId: String, eventId: String) {
        val room = rooms[roomId] ?: throw CloudFailure("UNKNOWN_ROOM")
        if (!room.joined || room.encrypted || room.messages.none { it.id == eventId }) return
        request("POST", "/rooms/${quote(roomId)}/receipt/m.read/${quote(eventId)}", emptyMap(), session.token)
        rooms[roomId] = room.copy(unread = 0)
    }

    private fun readDirectData(value: Any?) {
        val data = value as? Map<*, *> ?: throw CloudFailure("MATRIX_INVALID_REPLY")
        directData = data.entries.associate { (k, v) -> (k as? String ?: throw CloudFailure("MATRIX_INVALID_REPLY")) to v }
        directRooms = directData.mapValues { it.value.list().filterIsInstance<String>() }
    }
    private fun saveDirect(session: Session, peer: String, roomId: String) {
        if (roomId in directRooms[peer].orEmpty()) return
        val updated = directData + (peer to (directRooms[peer].orEmpty() + roomId).distinct())
        request("PUT", "/user/${quote(session.userId)}/account_data/m.direct", updated, session.token)
        readDirectData(updated)
    }
    private fun refreshRoom(session: Session, roomId: String, peer: String): Room {
        if (!validRoom(roomId)) throw CloudFailure("MATRIX_INVALID_REPLY")
        val events = requestValue("GET", "/rooms/${quote(roomId)}/state", token = session.token) as? List<*>
            ?: throw CloudFailure("MATRIX_INVALID_REPLY")
        val room = roomFromState(roomId, events, rooms[roomId]).copy(joined = true, directPeer = peer)
        rooms[roomId] = room
        val ownMember = events.map { it.obj() }.firstOrNull { it["type"] == "m.room.member" && it["state_key"] == session.userId }
        if (ownMember?.get("content").obj()["membership"] != "join") throw CloudFailure("ROOM_NOT_JOINED")
        if (peer.isNotBlank() && (peer !in room.members || room.members.keys.any { it != session.userId && it != peer }))
            throw CloudFailure("NOT_DIRECT_ROOM")
        return room
    }
    private fun roomFromState(id: String, events: List<*>, previous: Room?): Room {
        var name = previous?.name.orEmpty()
        var encrypted = previous?.encrypted ?: false
        val members = linkedMapOf<String, String>()
        for (value in events) {
            val e = value.obj(); val content = e["content"].obj()
            when (e["type"]) {
                "m.room.name" -> name = content["name"] as? String ?: name
                "m.room.encryption" -> encrypted = true
                "m.room.member" -> if (content["membership"] in listOf("join", "invite")) {
                    val member = e["state_key"] as? String ?: continue
                    members[member] = content["displayname"] as? String ?: member
                }
            }
        }
        return (previous ?: Room(id, "", false, emptyList(), emptyMap())).copy(name = name, encrypted = encrypted, members = members)
    }

    private fun request(method: String, path: String, body: Map<String, Any?>? = null, token: String? = null): Map<*, *> =
        requestValue(method, path, body, token) as? Map<*, *> ?: throw CloudFailure("MATRIX_INVALID_REPLY")

    private fun requestValue(method: String, path: String, body: Map<String, Any?>? = null, token: String? = null): Any {
        val headers = mutableMapOf("Content-Type" to "application/json")
        if (token != null) headers["Authorization"] = "Bearer $token"
        val r = http.request(method, "$serverUrl/_matrix/client/r0$path", headers, body?.let { Json.write(it).toByteArray() })
        val data = try { Json.parse(r.body.toString(Charsets.UTF_8)) } catch (_: Exception) { null }
        if (r.status !in 200..299) {
            val error = data.obj()["errcode"] as? String
            throw CloudFailure(if (error in listOf("M_UNKNOWN_TOKEN", "M_FORBIDDEN", "M_LIMIT_EXCEEDED", "M_NOT_FOUND", "M_BAD_JSON", "M_INVALID_PARAM")) error!! else "HTTP_${r.status}")
        }
        return data ?: throw CloudFailure("MATRIX_INVALID_REPLY")
    }

    private fun message(value: Any?): Message? {
        val e = value.obj(); val content = e["content"].obj()
        val encrypted = e["type"] == "m.room.encrypted"
        if (e["type"] != "m.room.message" && !encrypted) return null
        // Editing, redaction and media are not misrepresented as ordinary text.
        if (content["m.relates_to"].obj()["rel_type"] == "m.replace") return null
        val id = e["event_id"] as? String ?: return null
        val body = if (encrypted) "[加密消息]" else content["body"] as? String ?: "[非文字消息]"
        return Message(id, e["sender"] as? String ?: "", body.take(16_384), (e["origin_server_ts"] as? Number)?.toLong() ?: 0, encrypted)
    }

    companion object {
        const val MESSAGE_LIMIT = 500
        private fun validPeer(id: String) = id.length in 4..512 && id.startsWith('@') && id.indexOf(':') > 1 &&
            id.none { it.isWhitespace() || it.isISOControl() || it in "/?#\\" } && CloudAccountTransfer.officialDomain(id.substringAfter(':'))
        private fun validRoom(id: String) = id.length in 4..1024 && id.startsWith('!') && ':' in id &&
            id.none { it.isWhitespace() || it.isISOControl() }
        private fun mergeMessages(old: List<Message>, incoming: List<Message>) =
            (old + incoming).associateBy { it.id }.values.sortedBy { it.timestamp }.takeLast(MESSAGE_LIMIT)
        private fun quote(value: String) = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
        private fun Any?.obj() = this as? Map<*, *> ?: emptyMap<Any?, Any?>()
        private fun Any?.list() = this as? List<*> ?: emptyList<Any?>()
    }
}
