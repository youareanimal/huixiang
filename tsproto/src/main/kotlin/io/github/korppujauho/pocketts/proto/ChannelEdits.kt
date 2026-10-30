package io.github.korppujauho.pocketts.proto

/**
 * The commands that add, change, reorder and delete channels (the server decides who may), as checked against a
 * TeamSpeak 6 server: the order is a channel property ("comes after"), a new parent is a `channelmove`, and a
 * password goes hashed, like a join's.
 */
object ChannelEdits {
    enum class Kind { PERMANENT, SEMI_PERMANENT, TEMPORARY }

    /** A channel as the settings window shows it. */
    data class Settings(
        val name: String,
        val description: String = "",
        val kind: Kind = Kind.PERMANENT,
        val password: Boolean = false,
        val newPassword: String = "", // "" with [password] on an existing channel: its password stays
        val codec: Int = 4, // 4 = Opus Voice, 5 = Opus Music
        val quality: Int = 6, // 0 - 10
        val maxClients: Int = -1, // -1 = unlimited
        val parent: Int = 0,
        val after: Int = 0, // the channel it comes after (0 = first): a new one's place, or a moved one's in its new parent
    )

    /** A command to send: its name and parameters. */
    data class Edit(val name: String, val params: List<Pair<String, Any?>>)

    fun kindOf(ch: ServerModel.Channel) = when {
        ch.permanent -> Kind.PERMANENT
        ch.semiPermanent -> Kind.SEMI_PERMANENT
        else -> Kind.TEMPORARY
    }

    /** How an existing channel shows in the settings window. */
    fun settingsOf(ch: ServerModel.Channel) = Settings(
        name = ch.name, description = ch.description, kind = kindOf(ch), password = ch.hasPassword,
        codec = ch.codec, quality = ch.codecQuality, maxClients = ch.maxClients, parent = ch.parent, after = ch.order,
    )

    fun create(s: Settings) = Edit(
        "channelcreate",
        listOf(
            "channel_name" to s.name.trim(),
            "cpid" to s.parent.takeIf { it != 0 },
            "channel_order" to s.after,
            "channel_description" to s.description.trim().ifEmpty { null },
            "channel_codec" to s.codec,
            "channel_codec_quality" to s.quality,
            "channel_password" to s.newPassword.takeIf { s.password && it.isNotEmpty() }?.let { Crypto.hashPassword(it) },
        ) + flags(s.kind) + maxClients(s.maxClients),
    )

    /**
     * What to send to make [ch] like [s]: a `channeledit` with what changed, and a `channelmove` for a new parent (where
     * it goes after [Settings.after] there).
     */
    fun edit(ch: ServerModel.Channel, s: Settings): List<Edit> {
        val out = ArrayList<Edit>()
        val p = ArrayList<Pair<String, Any?>>()
        if (s.name.trim() != ch.name) p += "channel_name" to s.name.trim()
        if (s.description.trim() != ch.description.trim()) p += "channel_description" to s.description.trim()
        if (s.kind != kindOf(ch)) p += flags(s.kind, both = true)
        when {
            !s.password && ch.hasPassword -> p += "channel_password" to ""
            s.password && s.newPassword.isNotEmpty() -> p += "channel_password" to Crypto.hashPassword(s.newPassword)
        }
        if (s.codec != ch.codec) p += "channel_codec" to s.codec
        if (s.quality != ch.codecQuality) p += "channel_codec_quality" to s.quality
        if (s.maxClients != ch.maxClients) p += maxClients(s.maxClients)
        if (p.isNotEmpty()) out += Edit("channeledit", listOf("cid" to ch.id) + p)
        // Into another parent: first there (a channelmove can't stay in the same parent)
        if (s.parent != ch.parent) out += Edit("channelmove", listOf("cid" to ch.id, "cpid" to s.parent, "order" to s.after))
        return out
    }

    /** [ch] to come after [after] (0 = first) among its siblings. */
    fun reorder(ch: ServerModel.Channel, after: Int) = Edit("channeledit", listOf("cid" to ch.id, "channel_order" to after))

    /** Deleted, with its sub-channels; the people in them go to the default channel. */
    fun delete(ch: ServerModel.Channel) = Edit("channeldelete", listOf("cid" to ch.id, "force" to 1))

    /** Asks the server for a channel's description (it comes as an edit). */
    fun description(id: Int) = Edit("channelgetdescription", listOf("cid" to id))

    private fun flags(kind: Kind, both: Boolean = false): List<Pair<String, Any?>> = when (kind) {
        Kind.PERMANENT -> listOf("channel_flag_permanent" to 1) + if (both) listOf("channel_flag_semi_permanent" to 0) else emptyList()
        Kind.SEMI_PERMANENT -> listOf("channel_flag_semi_permanent" to 1) + if (both) listOf("channel_flag_permanent" to 0) else emptyList()
        Kind.TEMPORARY -> if (both) listOf("channel_flag_permanent" to 0, "channel_flag_semi_permanent" to 0) else emptyList()
    }

    private fun maxClients(n: Int): List<Pair<String, Any?>> =
        if (n < 0) listOf("channel_flag_maxclients_unlimited" to 1)
        else listOf("channel_flag_maxclients_unlimited" to 0, "channel_maxclients" to n)

    /** The channel and everything under it. */
    fun subtree(model: Collection<ServerModel.Channel>, id: Int): Set<Int> {
        val out = linkedSetOf(id)
        var grew = true
        while (grew) {
            grew = false
            for (c in model) if (c.parent in out && out.add(c.id)) grew = true
        }
        return out
    }
}
