package io.github.korppujauho.pocketts.proto

import java.security.MessageDigest
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Base64

/**
 * Pictures, icons and avatars in the server's files, named as TeamSpeak and WebTS name them.
 *
 * - Icons (channels, groups, people, the server) are "/icon_<id>" in the server's own files (channel 0). Ids under
 *   1000 are TeamSpeak's built-in icons: no file exists, the app draws them.
 * - Avatars are "/avatar_<name>" there, the name being the identity's bytes written as letters a-p.
 * - A picture in the chat is a file in a channel's files, sent as `[img]ts3image://<name>?channel=<cid>&path=<folder>[/img]`.
 */
object Pictures {
    const val BUILTIN_ICON_MAX = 999L
    const val ICON_MAX_BYTES = 1024L * 1024
    const val AVATAR_MAX_BYTES = 4L * 1024 * 1024
    const val PICTURE_MAX_BYTES = 16L * 1024 * 1024
    const val PICTURE_UPLOAD_MAX_BYTES = 15L * 1024 * 1024
    const val PICTURE_FOLDER = "/PocketTS" // where the pictures sent from the app go in a channel's files
    const val AVATAR_UPLOAD_NAME = "/avatar"
    const val AVATAR_MAX_SIDE = 320

    fun iconFileName(id: Long): String? = if (id in (BUILTIN_ICON_MAX + 1)..0xffffffffL) "/icon_$id" else null

    private fun letters(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xff
            sb.append('a' + (v shr 4)).append('a' + (v and 15))
        }
        return sb.toString()
    }

    /** The possible files of someone's avatar, best guess first (a name the server gave wins). */
    fun avatarFileNames(uid: String, serverName: String = ""): List<String> {
        val names = LinkedHashSet<String>()
        if (Regex("^[a-p]{32,128}$").matches(serverName)) names.add("/avatar_$serverName")
        if (Regex("^[A-Za-z0-9+/]{20,88}={0,2}$").matches(uid)) {
            val raw = Base64.getDecoder().decode(uid)
            val sha = MessageDigest.getInstance("SHA-256")
            if (raw.size >= 16) names.add("/avatar_${letters(raw)}")
            names.add("/avatar_${letters(sha.digest(uid.toByteArray()))}")
            if (raw.size >= 16) names.add("/avatar_${letters(sha.digest(raw))}")
        }
        return names.toList()
    }

    /** What an avatar's flag is: its MD5, in hex. */
    fun avatarFlag(data: ByteArray): String = MessageDigest.getInstance("MD5").digest(data).joinToString("") { "%02x".format(it) }

    enum class Type(val ext: String, val mime: String) {
        JPG("jpg", "image/jpeg"), PNG("png", "image/png"), GIF("gif", "image/gif"), WEBP("webp", "image/webp"), SVG("svg", "image/svg+xml")
    }

    /** What the bytes really are (never the name): JPEG, PNG, GIF, WebP, or (for icons) SVG. */
    fun type(b: ByteArray, svgAllowed: Boolean = false): Type? {
        fun at(i: Int, vararg bytes: Int) = b.size >= i + bytes.size && bytes.indices.all { (b[i + it].toInt() and 0xff) == bytes[it] }
        fun ascii(i: Int, s: String) = at(i, *s.map { it.code }.toIntArray())
        return when {
            at(0, 0xff, 0xd8, 0xff) -> Type.JPG
            at(0, 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a) -> Type.PNG
            ascii(0, "GIF87a") || ascii(0, "GIF89a") -> Type.GIF
            ascii(0, "RIFF") && ascii(8, "WEBP") -> Type.WEBP
            svgAllowed -> {
                val head = String(b, 0, minOf(b.size, 4096), Charsets.UTF_8).trimStart('﻿').trimStart()
                if (Regex("^(<\\?xml|<svg|<!--|<!DOCTYPE svg)", RegexOption.IGNORE_CASE).containsMatchIn(head) &&
                    Regex("<svg[\\s>]", RegexOption.IGNORE_CASE).containsMatchIn(head)
                ) Type.SVG else null
            }
            else -> null
        }
    }

    /** A picture in a channel's files. */
    data class ChannelPicture(val cid: Int, val folder: String, val name: String) {
        val path: String get() = "${folder.trimEnd('/')}/$name"
    }

    private val IMG = Regex("""\[img]\s*ts3image://([^\[\]?]+)\?([^\[\]]*)\s*\[/img]""", RegexOption.IGNORE_CASE)

    /** A file sent from the TeamSpeak app through myTeamSpeak: its name, and whether it's a picture. */
    data class TeamSpeakFile(val name: String, val isImage: Boolean) {
        /** The one line shown instead of the note. */
        val text: String get() = if (isImage) "TeamSpeak images aren't supported in PocketTS" else "TeamSpeak files aren't supported in PocketTS"
    }

    /**
     * A file sent from the TeamSpeak app (such as a picture pasted in its chat) arrives as a small note,
     * {"msg_type":"ts.file.myts","file_name":…}. The file itself is on myTeamSpeak, which the app can't reach, so the
     * note is shown as one short line. Null for an ordinary message.
     */
    fun teamSpeakFile(msg: String): TeamSpeakFile? {
        val text = msg.trim()
        if (!text.startsWith("{") || !text.contains("\"ts.file")) return null
        val note = try {
            Json.parse(text) as? Map<*, *>
        } catch (e: Exception) {
            null
        } ?: return null
        if ((note["msg_type"] as? String)?.startsWith("ts.file") != true) return null
        val name = listOf(note["file_name"], note["body"]).firstNotNullOfOrNull { (it as? String)?.trim()?.ifEmpty { null } }.orEmpty()
        return TeamSpeakFile(name, Regex("""\.(jpe?g|png|gif|webp|bmp|heic|avif)$""", RegexOption.IGNORE_CASE).containsMatchIn(name))
    }

    /** The channel pictures in a message, and the message's text without them. */
    fun channelPictures(msg: String): Pair<List<ChannelPicture>, String> {
        val found = ArrayList<ChannelPicture>()
        val rest = IMG.replace(msg) { m ->
            val name = decode(m.groupValues[1].trim())
            val q = m.groupValues[2].split('&').mapNotNull { kv ->
                kv.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to decode(it[1]) }
            }.toMap()
            val cid = q["channel"]?.toIntOrNull()
            val folder = q["path"]?.ifEmpty { "/" } ?: "/"
            if (cid != null && cid > 0 && validName(name) && validFolder(folder)) {
                found.add(ChannelPicture(cid, folder, name))
                ""
            } else m.value
        }
        return found to rest.trim()
    }

    /** A banner or picture address: ts3image:// in a channel's files. */
    fun channelPicture(url: String): ChannelPicture? = channelPictures("[img]$url[/img]").first.firstOrNull()

    private fun decode(s: String) = try { java.net.URLDecoder.decode(s, "UTF-8") } catch (_: Exception) { s }

    private fun validName(n: String) = n.isNotEmpty() && n.length <= 150 && '/' !in n && '\\' !in n && n != "." && n != ".." &&
        n.none { it.code < 0x20 || it.code == 0x7f }

    private fun validFolder(f: String) = f.length <= 200 && f.startsWith("/") && '\\' !in f &&
        f.split('/').none { it == "." || it == ".." } && f.none { it.code < 0x20 || it.code == 0x7f }

    /** The chat line the TeamSpeak app shows as the picture. */
    fun pictureMessage(name: String, cid: Int, folder: String = PICTURE_FOLDER) = "[img]ts3image://$name?channel=$cid&path=$folder[/img]"

    /** A new picture's file name: the local time and a random part, e.g. 2026-10-12_18-35-01_a7k3f9.jpg. */
    fun pictureName(type: Type, random: String, now: LocalDateTime = LocalDateTime.now()): String =
        "${now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"))}_${random.filter { it.isLetterOrDigit() }.lowercase()}.${type.ext}"
}
