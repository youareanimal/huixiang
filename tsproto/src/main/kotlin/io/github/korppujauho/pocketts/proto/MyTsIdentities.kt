package io.github.korppujauho.pocketts.proto

import io.github.korppujauho.pocketts.proto.CloudProto.number
import io.github.korppujauho.pocketts.proto.CloudProto.string

/** Read-only access to the account's synchronized TeamSpeak server identities.
 * Wire fields cross-checked against the MIT-licensed TeamSpeak-Android-TS3 project:
 * https://github.com/NotMurPh/TeamSpeak-Android-TS3/blob/main/android/app/src/main/java/com/tsrewrite/client/MyTeamSpeakCloud.kt
 */
class MyTsIdentities(private val http: CloudTransport = HttpsCloudTransport) {
    class Entry(val id: String, val name: String, val nickname: String, val legacyUid: String, val ts6Uid: String, val identity: Identity) {
        override fun toString() = "MyTsIdentity(redacted)"
    }

    fun read(session: MyTsAccount.Session): List<Entry> {
        val key = session.syncKey ?: throw CloudFailure(session.bookmarkKeyError ?: "IDENTITY_KEY_REQUIRED")
        val nil = "00000000-0000-0000-0000-000000000000"
        val method = "requestServerItems".toByteArray(Charsets.US_ASCII)
        val payload = CloudProto.text(1, session.token) +
            CloudProto.bytes(2, CloudProto.integer(1, 1) + CloudProto.text(2, nil)) +
            CloudProto.text(3, nil) + CloudProto.integer(4, 1)
        val reply = http.request("POST", "https://clientapi.myteamspeak.com/synchronization",
            mapOf("Content-Type" to "application/ts3cloud"), CloudProto.varint(method.size.toLong()) + method + payload)
        if (reply.status !in 200..299) throw CloudFailure("IDENTITY_HTTP_${reply.status}")
        try {
            val response = CloudProto.read(reply.body)
            if (response.number(1) !in setOf(300L, 301L, 302L)) throw CloudFailure("IDENTITY_SYNC_${response.number(1) ?: 0}")
            val groups = response.filter { it.number == 2 }.map { CloudProto.read(it.bytes ?: throw CloudFailure("IDENTITY_INVALID_REPLY")) }
                .filter { it.number(1) == 1L }
            if (groups.size != 1) throw CloudFailure("IDENTITY_INVALID_REPLY")
            val details = groups.single().filter { it.number == 3 }
            if (details.size > 4096) throw CloudFailure("IDENTITY_INVALID_REPLY")
            val result = mutableListOf<Entry>()
            for (record in details) {
                val detail = CloudProto.read(record.bytes ?: throw CloudFailure("IDENTITY_INVALID_REPLY"))
                if ((detail.number(4) ?: 0L) != 0L) continue
                val id = detail.string(1)
                if (id.isBlank() || id.length > 128) throw CloudFailure("IDENTITY_INVALID_REPLY")
                val blob = detail.firstOrNull { it.number == 3 }?.bytes ?: throw CloudFailure("IDENTITY_INVALID_REPLY")
                val plain = MyTsBookmarks.openItem(blob, key)
                try {
                    val item = CloudProto.read(plain)
                    if (item.number(6) != 1L || item.string(2) != id) throw CloudFailure("IDENTITY_INVALID_REPLY")
                    val inner = item.firstOrNull { it.number == 17 }?.bytes ?: throw CloudFailure("IDENTITY_INVALID_REPLY")
                    val values = CloudProto.read(inner)
                    val encoded = values.string(1)
                    val name = values.string(2)
                    val nickname = values.string(3)
                    if (encoded.isBlank() || encoded.length > 4096 || name.length > 1024 || nickname.length > 1024)
                        throw CloudFailure("IDENTITY_INVALID_REPLY")
                    val identity = Identity.import(encoded)
                    val ts6Uid = java.util.Base64.getEncoder().encodeToString(Crypto.sha256(identity.publicKeyString.toByteArray(Charsets.US_ASCII)))
                    result += Entry(id, name, nickname, identity.uid, ts6Uid, identity)
                } finally { plain.fill(0) }
            }
            if (result.map { it.id }.distinct().size != result.size) throw CloudFailure("IDENTITY_INVALID_REPLY")
            return result
        } catch (e: CloudFailure) { throw e }
        catch (_: Exception) { throw CloudFailure("IDENTITY_INVALID_REPLY") }
    }
}
