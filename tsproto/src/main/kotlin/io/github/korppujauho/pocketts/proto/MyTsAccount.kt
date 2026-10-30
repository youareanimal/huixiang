package io.github.korppujauho.pocketts.proto

import io.github.korppujauho.pocketts.proto.CloudProto.string
import io.github.korppujauho.pocketts.proto.CloudProto.number
import java.util.Base64
import java.util.Locale
import java.net.URLEncoder
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** Account authentication and contact listing, independently implemented from observed wire fields. */
class MyTsAccount(private val http: CloudTransport = HttpsCloudTransport) {
    class Session(val token: String, val username: String, val syncKey: ByteArray? = null,
        internal val wrappedSyncKey: ByteArray? = null, val bookmarkKeyError: String? = null, val email: String = "",
        val voiceMaterial: MyTsVoiceMaterial? = null, val voiceMaterialError: String? = null) {
        override fun toString() = "MyTsSession(redacted)"
    }
    class ChatCredentials(val domain: String, val username: String, val password: String) {
        val userId: String
            get() = when {
                username.startsWith("@") && ':' in username -> username
                username.startsWith("@") -> "$username:$domain"
                else -> "@$username:$domain"
            }

        override fun toString() = "ChatCredentials(redacted)"
    }
    data class Contact(val identifier: String, val matrixId: String, val note: String, val profileName: String = "") {
        val displayName: String
            get() = readableName(note) ?: readableName(profileName) ?: readableName(identifier) ?: "TeamSpeak 好友"
        val subtitle: String
            get() = readableName(identifier)?.takeUnless { it == displayName }.orEmpty()
        val needsProfileName: Boolean
            get() = readableName(note) == null && matrixId.isNotBlank()
        override fun toString() = "MyTsContact(redacted)"

        companion object {
            /** The official note may be Base64 JSON containing ownMatrixId, not a user-written alias. */
            private fun readableName(value: String): String? {
                val text = value.trim()
                if (text.isEmpty() || text.length > 512 || text.any { it.isISOControl() } ||
                    text.startsWith('{') || text.startsWith('[')) return null
                for (decoder in listOf(Base64.getDecoder(), Base64.getUrlDecoder())) {
                    val decoded = try { decoder.decode(text).toString(Charsets.UTF_8).trimStart() } catch (_: Exception) { null }
                    if (decoded?.startsWith('{') == true || decoded?.startsWith('[') == true) return null
                }
                return text
            }
        }
    }

    fun login(email: String, password: String, otp: String = "", deviceId: String = ""): Session {
        val result = loginDigest(email.trim(), passwordDigest(email.trim(), password), otp, deviceId)
        val wrapped = result.wrappedSyncKey
        if (wrapped == null || wrapped.isEmpty())
            return Session(result.token, result.username, bookmarkKeyError = "BOOKMARK_KEY_MISSING", email = email.trim(),
                voiceMaterial = result.voiceMaterial, voiceMaterialError = result.voiceMaterialError)
        var passwordKey: ByteArray? = null
        return try {
            passwordKey = try { MyTsBookmarks.encryptionPassword(email.trim(), password) }
                catch (_: Exception) { throw CloudFailure("BOOKMARK_KDF_UNAVAILABLE") }
            Session(result.token, result.username, MyTsBookmarks.unlockKey(wrapped, passwordKey), email = email.trim(),
                voiceMaterial = result.voiceMaterial, voiceMaterialError = result.voiceMaterialError)
        } catch (e: CloudFailure) {
            // Preserve the exact safe error instead of asking for another login after every failure.
            Session(result.token, result.username, bookmarkKeyError = e.code, email = email.trim(),
                voiceMaterial = result.voiceMaterial, voiceMaterialError = result.voiceMaterialError)
        } finally { passwordKey?.fill(0) }
    }

    fun loginDigest(email: String, digest: String, otp: String = "", deviceId: String = ""): Session {
        val body = CloudProto.text(1, email) + CloudProto.text(2, digest) +
            CloudProto.integer(3, 0) + CloudProto.integer(4, 0) + CloudProto.integer(5, 1) +
            CloudProto.text(6, otp) + CloudProto.text(7, deviceId) + CloudProto.text(9, "回响 Android")
        val fields = rpc("authentication", "login", body)
        val code = fields.number(5)
        if (code != 200L) throw CloudFailure("MYTS_${code ?: 0}")
        val token = fields.string(2)
        if (token.isBlank()) throw CloudFailure("MYTS_NO_SESSION")
        var materialError: String? = null
        val material = try { MyTsVoiceMaterial.fromLogin(fields) } catch (_: Exception) {
            // Optional voice material must not turn a valid account login into an authentication failure.
            materialError = "MYTS_VOICE_MATERIAL_INVALID"
            null
        }
        return Session(token, fields.string(8), wrappedSyncKey = fields.firstOrNull { it.number == 1 }?.bytes,
            email = email.trim(), voiceMaterial = material, voiceMaterialError = materialError)
    }

    fun valid(session: Session): Boolean =
        rpc("authentication", "session", CloudProto.text(1, session.token)).number(1) == 102L

    /** Optional public metadata fetch; a badge service failure cannot interrupt voice or discard the avatar. */
    fun voiceData(session: Session): MyTsVoiceData? {
        val badges = try {
            val fields = rpc("user", "getSignedBadges", CloudProto.text(1, session.token))
            if ((fields.number(2) ?: 0L) == 0L) fields.singleOrNull { it.number == 1 }?.bytes else null
        } catch (_: Exception) { null }
        return try { MyTsVoiceData.fromSession(session, badges) }
            catch (_: Exception) { MyTsVoiceData.fromSession(session) }
    }

    fun contacts(session: Session): List<Contact> {
        val fields = rpc("tschat", "getContactList", CloudProto.text(1, session.token))
        val status = fields.firstOrNull { it.number == 2 }?.bytes?.let { CloudProto.read(it).number(1) }
        if (status != 1L) throw CloudFailure("CONTACTS_${status ?: 0}")
        return fields.filter { it.number == 1 }.mapNotNull { field ->
            field.bytes?.let { CloudProto.read(it) }?.let { Contact(it.string(1), it.string(3), it.string(2)) }
        }
    }

    /** Public display-name lookup only: no Matrix login, access token, sync, or chat session is created. */
    fun withContactProfile(contact: Contact): Contact {
        if (!contact.needsProfileName) return contact
        val matrixId = contact.matrixId
        if (!matrixId.startsWith('@') || matrixId.indexOf(':') < 2 || matrixId.length > 512 ||
            matrixId.any { it.isWhitespace() || it.isISOControl() }) return contact
        val domain = matrixId.substringAfter(':')
        if (!CloudAccountTransfer.officialDomain(domain)) return contact
        return try {
            val encoded = URLEncoder.encode(matrixId, Charsets.UTF_8.name()).replace("+", "%20")
            val reply = http.request("GET", "https://$domain/_matrix/client/r0/profile/$encoded/displayname",
                mapOf("User-Agent" to "Go-http-client/1.1"), null)
            if (reply.status != 200) return contact
            val data = Json.parse(reply.body.toString(Charsets.UTF_8)) as? Map<*, *> ?: return contact
            val name = data["displayname"] as? String ?: return contact
            contact.copy(profileName = name)
        } catch (_: Exception) { contact }
    }

    /**
     * Returns the separate Matrix credentials belonging to this myTeamSpeak account. For an account that already
     * has cloud chat, TeamSpeak's createAccount RPC returns the existing central credentials.
     */
    fun chatCredentials(session: Session): ChatCredentials {
        if (session.token.isBlank()) throw CloudFailure("MYTS_NO_SESSION")
        val fields = rpc("tschat", "createAccount", CloudProto.text(1, session.token))
        val status = fields.firstOrNull { it.number == 1 }?.bytes?.let {
            try { CloudProto.read(it).number(1) } catch (_: Exception) { null }
        }
        if (status != 1L) throw CloudFailure("MYTS_CHAT_${status ?: 0}")
        val credentials = fields.firstOrNull { it.number == 2 }?.bytes?.let {
            try { CloudProto.read(it) } catch (_: Exception) { null }
        } ?: throw CloudFailure("MYTS_CHAT_NO_CREDENTIALS")
        val domain = credentials.string(1)
        val username = credentials.string(2)
        val password = credentials.string(3)
        if (!CloudAccountTransfer.officialDomain(domain) || username.isBlank() ||
            username.length > 512 || username.any { it.isWhitespace() } ||
            password.isBlank() || password.length > 4096) throw CloudFailure("MYTS_CHAT_INVALID_CREDENTIALS")
        val result = ChatCredentials(domain, username, password)
        if (!result.userId.startsWith('@') || !result.userId.endsWith(":$domain") ||
            result.userId.any { it.isWhitespace() || it == '/' || it == '?' || it == '#' })
            throw CloudFailure("MYTS_CHAT_INVALID_CREDENTIALS")
        return result
    }

    /** Revoke only a session created by this client; never use on a session imported from another device. */
    fun revokeOwnSession(session: Session) {
        if (rpc("authentication", "deleteSession", CloudProto.text(1, session.token)).number(1) != 204L)
            throw CloudFailure("MYTS_LOGOUT")
    }

    internal fun rpc(endpoint: String, method: String, payload: ByteArray): List<CloudProto.Field> {
        val name = method.toByteArray(Charsets.US_ASCII)
        val r = http.request("POST", "https://clientapi.myteamspeak.com/$endpoint",
            mapOf("Content-Type" to "application/ts3cloud"), CloudProto.varint(name.size.toLong()) + name + payload)
        if (r.status !in 200..299) throw CloudFailure("HTTP_${r.status}")
        return try { CloudProto.read(r.body) } catch (_: Exception) { throw CloudFailure("MYTS_INVALID_REPLY") }
    }

    companion object {
        /** Matches the algorithm in the official myTeamSpeak website's public password helper. */
        fun passwordDigest(email: String, password: String): String {
            val salt = (email.lowercase(Locale.ROOT) + "ts3Login" + password).toByteArray(Charsets.UTF_8)
            val spec = PBEKeySpec(password.toCharArray(), salt, 10_000, 384)
            return try { Base64.getEncoder().encodeToString(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA512").generateSecret(spec).encoded) }
            finally { spec.clearPassword(); salt.fill(0) }
        }
    }
}
