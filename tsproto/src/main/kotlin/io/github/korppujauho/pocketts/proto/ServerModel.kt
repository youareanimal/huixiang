package io.github.korppujauho.pocketts.proto

/** What we know about the server: channels, who is where, who is talking. Fed with every command (on one thread). */
class ServerModel {
    private companion object {
        val BANNER = Regex("""^(https|ts3image)://\S{1,500}$""", RegexOption.IGNORE_CASE)
        val MYTS_AVATAR = Regex("""^(?:\d+,)?(https://storage\.googleapis\.com/ts-sys-myts-avatars/[A-Za-z0-9-]{1,64}/[0-9]{1,20})$""")
        val SPACER = Regex("""^\[([^\]]*)spacer[^\]]*](.*)$""")
        val LINES = mapOf(
            "---" to Spacer.Line.DASHED, "..." to Spacer.Line.DOTTED, "-.-" to Spacer.Line.DASHED,
            "___" to Spacer.Line.SOLID, "-.." to Spacer.Line.DOTTED, "" to Spacer.Line.SOLID,
        )
    }

    data class Channel(
        val id: Int,
        val parent: Int,
        val order: Int, // the channel this one comes after among its siblings (0 = first)
        val name: String,
        val topic: String = "",
        val isDefault: Boolean = false,
        val hasPassword: Boolean = false,
        val codec: Int = 4, // 4 = Opus Voice, 5 = Opus Music
        val codecQuality: Int = 6, // 0 - 10
        val neededTalkPower: Int = 0,
        val maxClients: Int = -1, // -1 = unlimited
        val permanent: Boolean = true,
        val semiPermanent: Boolean = false,
        val iconId: Long = 0,
        val description: String = "",
        val bannerUrl: String = "", // https:// or ts3image:// ("" = none)
        val bannerMode: Int = 0,
    ) {
        /** A spacer as TeamSpeak decides it: a top-level permanent channel named `[<kind>spacer<anything>]<text>`. */
        val spacer: Spacer? get() {
            if (parent != 0 || !permanent) return null
            val m = SPACER.matchEntire(name) ?: return null
            val kind = m.groupValues[1]
            val text = m.groupValues[2]
            val align = when (kind) { "c" -> Spacer.Align.CENTER; "r" -> Spacer.Align.RIGHT; else -> Spacer.Align.LEFT }
            val line = LINES[text]
            return Spacer(align, if (line != null) "" else text, repeat = kind == "*" && line == null, line = line)
        }

        /** The name without a spacer's [...spacer...] prefix. */
        val displayName: String get() = name.replace(Regex("""^\[.*spacer.*]""", RegexOption.IGNORE_CASE), "")
    }

    /** How a spacer channel is drawn: text placed left/centre/right (or repeated across), or a line. */
    data class Spacer(val align: Align, val text: String, val repeat: Boolean, val line: Line?) {
        enum class Align { LEFT, CENTER, RIGHT }
        enum class Line { SOLID, DASHED, DOTTED }
    }

    data class Client(
        val id: Int,
        val channel: Int,
        val nickname: String,
        val uid: String = "",
        val type: Int = 0, // 1 = ServerQuery (not shown)
        val away: Boolean = false,
        val awayMessage: String = "",
        val inputMuted: Boolean = false,
        val outputMuted: Boolean = false,
        val inputHardware: Boolean = true,
        val outputHardware: Boolean = true,
        val talkPower: Int = 0,
        val isTalker: Boolean = false,
        val prioritySpeaker: Boolean = false,
        val talking: Boolean = false,
        val serverGroups: List<Int> = emptyList(),
        val channelGroup: Int = 0,
        val iconId: Long = 0,
        val avatar: String = "", // the avatar's hash ("" = none)
        val avatarName: String = "", // the avatar file's name, when the server says it
        val myTeamSpeakAvatar: String = "", // a myTeamSpeak picture's address ("" = none)
        val streaming: Boolean = false, // sharing their screen or camera
        val description: String = "",
        val country: String = "",
        val platform: String = "",
        val version: String = "",
    )

    /** A server or channel group: its name and icon, and its place in the order. */
    data class Group(val id: Int, val name: String, val iconId: Long, val sortId: Int)

    /** A channel with its sub-channels and the people in it, in the server's order. */
    data class Node(val channel: Channel, val clients: List<Client>, val children: List<Node>)

    var serverName = ""
        private set
    var welcomeMessage = ""
        private set
    var ownClientId = 0
        private set

    /** Everything the server told about itself (virtualserver_*: welcome message, banner, version...). */
    val serverVars = HashMap<String, String>()

    val channels = LinkedHashMap<Int, Channel>()
    val serverGroups = HashMap<Int, Group>()
    /** Running streams: whose (client id) and its id. */
    val streams = HashMap<Int, String>()
    val channelGroups = HashMap<Int, Group>()
    val clients = LinkedHashMap<Int, Client>()

    val ownClient: Client? get() = clients[ownClientId]
    val ownChannel: Channel? get() = ownClient?.let { channels[it.channel] }

    fun clear() {
        serverName = ""; welcomeMessage = ""; ownClientId = 0
        serverVars.clear(); channels.clear(); clients.clear(); serverGroups.clear(); channelGroups.clear(); streams.clear()
    }

    /** Returns true when something shown changed. */
    fun apply(cmd: Command): Boolean {
        Regex("^(sgid|cgid)=(\\d+)$").matchEntire(cmd.name)?.let { m ->
            val first = cmd.groups.first() + (m.groupValues[1] to m.groupValues[2])
            readGroups(if (m.groupValues[1] == "sgid") serverGroups else channelGroups, m.groupValues[1], Command(cmd.name, listOf(first) + cmd.groups.drop(1)))
            return true
        }
        when (cmd.name) {
            "initserver" -> {
                ownClientId = cmd["aclid"]?.toIntOrNull() ?: 0
                serverName = cmd["virtualserver_name"].orEmpty()
                welcomeMessage = cmd["virtualserver_welcomemessage"].orEmpty()
                cmd.params.filterKeys { it.startsWith("virtualserver_") }.forEach { (k, v) -> serverVars[k] = v }
                cmd["virtualserver_uptime"]?.let { serverVars["received_at"] = System.currentTimeMillis().toString() }
            }
            "notifyservergrouplist", "servergrouplist" -> readGroups(serverGroups, "sgid", cmd)
            "notifychannelgrouplist", "channelgrouplist" -> readGroups(channelGroups, "cgid", cmd)
            "notifyservergroupclientadded", "notifyservergroupclientdeleted" -> for (g in cmd.groups) {
                val id = g["clid"]?.toIntOrNull() ?: continue
                val sgid = g["sgid"]?.toIntOrNull() ?: continue
                val c = clients[id] ?: continue
                val others = c.serverGroups.filter { it != sgid }
                clients[id] = c.copy(serverGroups = if (cmd.name == "notifyservergroupclientadded") others + sgid else others)
            }
            "notifystreaminfo", "notifystreamstarted" -> {
                val id = cmd["id"] ?: cmd["stream_id"] ?: return false
                val clid = cmd["clid"]?.toIntOrNull() ?: return false
                streams[clid] = id
                clients[clid]?.let { clients[clid] = it.copy(streaming = true) }
            }
            "notifystreamstopped" -> {
                val clid = cmd["clid"]?.toIntOrNull() ?: streams.entries.firstOrNull { it.value == cmd["id"] }?.key ?: return false
                streams.remove(clid)
                clients[clid]?.let { clients[clid] = it.copy(streaming = false) }
            }
            "notifyclientchannelgroupchanged" -> for (g in cmd.groups) {
                val id = g["clid"]?.toIntOrNull() ?: continue
                val c = clients[id] ?: continue
                clients[id] = c.copy(channelGroup = g["cgid"]?.toIntOrNull() ?: c.channelGroup)
            }
            "notifyserverupdated" -> {
                cmd["virtualserver_name"]?.let { serverName = it }
                cmd["virtualserver_welcomemessage"]?.let { welcomeMessage = it }
                cmd.params.filterKeys { it.startsWith("virtualserver_") }.forEach { (k, v) -> serverVars[k] = v }
                cmd["virtualserver_uptime"]?.let { serverVars["received_at"] = System.currentTimeMillis().toString() }
            }
            "channellist" -> for (g in cmd.groups) {
                val id = g["cid"]?.toIntOrNull() ?: continue
                channels[id] = channelFrom(g, channels[id] ?: Channel(id, 0, 0, "Channel $id"))
            }
            // A new channel: the one that came after its place now comes after it (the server doesn't say so)
            "notifychannelcreated" -> for (g in cmd.groups) {
                val id = g["cid"]?.toIntOrNull() ?: continue
                val ch = channelFrom(g, channels[id] ?: Channel(id, 0, 0, "Channel $id"))
                channels[id] = ch
                link(ch)
            }
            "notifychanneledited" -> {
                val id = cmd["cid"]?.toIntOrNull() ?: return false
                val old = channels[id] ?: return false
                place(old, channelFrom(cmd.params, old))
            }
            "notifychannelmoved" -> {
                val id = cmd["cid"]?.toIntOrNull() ?: return false
                val old = channels[id] ?: return false
                place(old, old.copy(parent = cmd["cpid"]?.toIntOrNull() ?: old.parent, order = cmd["order"]?.toIntOrNull() ?: old.order))
            }
            // Its sub-channels go too (the server lists them, deepest first)
            "notifychanneldeleted" -> for (g in cmd.groups) {
                val id = g["cid"]?.toIntOrNull() ?: continue
                channels[id]?.let { unlink(it) }
                channels.remove(id)
                removeOrphans()
            }
            "notifycliententerview" -> {
                // In a list, the target channel may be given only once, in the first entry.
                var target = 0
                for (g in cmd.groups) {
                    target = g["ctid"]?.toIntOrNull() ?: target
                    val id = g["clid"]?.toIntOrNull() ?: continue
                    clients[id] = clientFrom(g, Client(id, target, "Client $id")).copy(channel = target)
                }
            }
            "notifyclientleftview" -> for (g in cmd.groups) g["clid"]?.toIntOrNull()?.let { clients.remove(it); streams.remove(it) }
            "notifyclientmoved" -> {
                var target = 0
                for (g in cmd.groups) {
                    target = g["ctid"]?.toIntOrNull() ?: target
                    val id = g["clid"]?.toIntOrNull() ?: continue
                    clients[id]?.let { clients[id] = it.copy(channel = target, talking = false) }
                }
            }
            "notifyclientupdated" -> for (g in cmd.groups) {
                val id = g["clid"]?.toIntOrNull() ?: continue
                clients[id]?.let { clients[id] = clientFrom(g, it) }
            }
            "notifytalkstatuschange" -> {
                val id = cmd["clid"]?.toIntOrNull() ?: return false
                clients[id]?.let { clients[id] = it.copy(talking = cmd["status"] == "1") }
            }
            else -> return false
        }
        return true
    }

    private fun readGroups(into: MutableMap<Int, Group>, idKey: String, cmd: Command) {
        for (g in cmd.groups) {
            val id = g[idKey]?.toIntOrNull() ?: continue
            if (id <= 0) continue
            into[id] = Group(id, g["name"].orEmpty(), g["iconid"]?.let { unsignedId(it) } ?: 0, g["sortid"]?.toIntOrNull() ?: 0)
        }
    }

    /**
     * A person's badges as the TeamSpeak app shows them: their channel group's icon, their server groups' icons (in the
     * server's order), then their own icon. Icon ids; under 1000 are TeamSpeak's built-in ones.
     */
    fun badges(c: Client): List<Long> {
        val out = ArrayList<Long>()
        channelGroups[c.channelGroup]?.iconId?.takeIf { it > 0 }?.let { out.add(it) }
        c.serverGroups.mapNotNull { serverGroups[it] }.sortedWith(compareBy<Group> { it.sortId }.thenBy { it.id })
            .forEach { if (it.iconId > 0) out.add(it.iconId) }
        if (c.iconId > 0) out.add(c.iconId)
        return out.distinct()
    }

    /** Talking as seen from the voice itself (some servers send no talk status). */
    fun setTalking(clientId: Int, talking: Boolean): Boolean {
        val c = clients[clientId] ?: return false
        if (c.talking == talking) return false
        clients[clientId] = c.copy(talking = talking)
        return true
    }

    /** Whether [client] (by default we) can be heard in their channel. */
    fun canTalk(client: Client? = ownClient): Boolean {
        val c = client ?: return false
        val ch = channels[c.channel] ?: return true
        return c.isTalker || c.talkPower >= ch.neededTalkPower
    }

    fun tree(): List<Node> {
        val byParent = channels.values.groupBy { it.parent }
        val byChannel = clients.values.filter { it.type == 0 }.groupBy { it.channel }
        fun build(parent: Int, depth: Int): List<Node> {
            if (depth > 64) return emptyList()
            return ordered(byParent[parent].orEmpty()).map { ch ->
                Node(ch, byChannel[ch.id].orEmpty().sortedWith(clientOrder), build(ch.id, depth + 1))
            }
        }
        return build(0, 0)
    }

    private val clientOrder = compareByDescending<Client> { it.talkPower }.thenBy { it.nickname.lowercase() }

    // Channels name the one they come after. The server tells only about the channel that moved, so its neighbours
    // follow here: the one after its old place now follows what it followed, and the one at its new place follows it.
    private fun unlink(ch: Channel) {
        channels.values.filter { it.id != ch.id && it.parent == ch.parent && it.order == ch.id }
            .forEach { channels[it.id] = it.copy(order = ch.order) }
    }

    private fun link(ch: Channel) {
        channels.values.filter { it.id != ch.id && it.parent == ch.parent && it.order == ch.order }
            .forEach { channels[it.id] = it.copy(order = ch.id) }
    }

    private fun place(old: Channel, new: Channel) {
        if (old.parent == new.parent && old.order == new.order) {
            channels[new.id] = new
            return
        }
        unlink(old)
        channels[new.id] = new
        link(new)
    }

    private fun removeOrphans() {
        while (true) {
            val orphans = channels.values.filter { it.parent != 0 && !channels.containsKey(it.parent) }
            if (orphans.isEmpty()) return
            orphans.forEach { channels.remove(it.id) }
        }
    }

    /** Siblings follow each other: each names the one it comes after. */
    private fun ordered(siblings: List<Channel>): List<Channel> {
        val after = siblings.associateBy { it.order }
        val out = ArrayList<Channel>(siblings.size)
        val seen = HashSet<Int>()
        var cur = after[0]
        while (cur != null && seen.add(cur.id)) {
            out.add(cur)
            cur = after[cur.id]
        }
        siblings.filter { it.id !in seen }.sortedBy { it.id }.forEach { out.add(it) } // anything left over
        return out
    }

    private fun channelFrom(g: Map<String, String>, old: Channel) = old.copy(
        parent = g["cpid"]?.toIntOrNull() ?: g["pid"]?.toIntOrNull() ?: old.parent,
        order = g["channel_order"]?.toIntOrNull() ?: old.order,
        name = g["channel_name"] ?: old.name,
        topic = g["channel_topic"] ?: old.topic,
        isDefault = g["channel_flag_default"]?.let { it == "1" } ?: old.isDefault,
        hasPassword = g["channel_flag_password"]?.let { it == "1" } ?: old.hasPassword,
        codec = g["channel_codec"]?.toIntOrNull() ?: old.codec,
        codecQuality = g["channel_codec_quality"]?.toIntOrNull() ?: old.codecQuality,
        neededTalkPower = g["channel_needed_talk_power"]?.toIntOrNull() ?: old.neededTalkPower,
        maxClients = if (g["channel_flag_maxclients_unlimited"] == "1") -1 else g["channel_maxclients"]?.toIntOrNull() ?: old.maxClients,
        permanent = g["channel_flag_permanent"]?.let { it == "1" } ?: old.permanent,
        semiPermanent = g["channel_flag_semi_permanent"]?.let { it == "1" } ?: old.semiPermanent,
        iconId = g["channel_icon_id"]?.let { unsignedId(it) } ?: old.iconId,
        description = g["channel_description"] ?: old.description,
        bannerUrl = g["channel_banner_gfx_url"]?.let { v -> v.takeIf { BANNER.matches(it) }.orEmpty() } ?: old.bannerUrl,
        bannerMode = g["channel_banner_mode"]?.toIntOrNull() ?: old.bannerMode,
    )

    /** Icon ids come as signed or unsigned 32-bit numbers; always unsigned here. */
    private fun unsignedId(s: String): Long? = s.toLongOrNull()?.let { if (it < 0) it + (1L shl 32) else it }

    private fun clientFrom(g: Map<String, String>, old: Client) = old.copy(
        nickname = g["client_nickname"] ?: old.nickname,
        uid = g["client_unique_identifier"] ?: old.uid,
        type = g["client_type"]?.toIntOrNull() ?: old.type,
        away = g["client_away"]?.let { it == "1" } ?: old.away,
        awayMessage = g["client_away_message"] ?: old.awayMessage,
        inputMuted = g["client_input_muted"]?.let { it == "1" } ?: old.inputMuted,
        outputMuted = g["client_output_muted"]?.let { it == "1" } ?: old.outputMuted,
        inputHardware = g["client_input_hardware"]?.let { it == "1" } ?: old.inputHardware,
        outputHardware = g["client_output_hardware"]?.let { it == "1" } ?: old.outputHardware,
        talkPower = g["client_talk_power"]?.toIntOrNull() ?: old.talkPower,
        isTalker = g["client_is_talker"]?.let { it == "1" } ?: old.isTalker,
        prioritySpeaker = g["client_is_priority_speaker"]?.let { it == "1" } ?: old.prioritySpeaker,
        streaming = g["client_is_streaming"]?.let { it == "1" } ?: old.streaming,
        serverGroups = g["client_servergroups"]?.split(',')?.mapNotNull { it.trim().toIntOrNull() } ?: old.serverGroups,
        channelGroup = g["client_channel_group_id"]?.toIntOrNull() ?: old.channelGroup,
        iconId = g["client_icon_id"]?.let { unsignedId(it) } ?: old.iconId,
        avatar = g["client_flag_avatar"] ?: old.avatar,
        description = g["client_description"] ?: old.description,
        country = g["client_country"] ?: old.country,
        platform = g["client_platform"] ?: old.platform,
        version = g["client_version"] ?: old.version,
        avatarName = g["client_base64HashClientUID"]?.takeIf { Regex("^[a-p]{32,128}$").matches(it) } ?: old.avatarName,
        // Only the one place myTeamSpeak keeps pictures is accepted
        myTeamSpeakAvatar = g["client_myteamspeak_avatar"]?.let { MYTS_AVATAR.matchEntire(it.trim())?.groupValues?.get(1).orEmpty() } ?: old.myTeamSpeakAvatar,
    )
}
