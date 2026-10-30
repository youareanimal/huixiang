package io.github.korppujauho.pocketts.proto

import io.github.korppujauho.pocketts.proto.CloudProto.number
import io.github.korppujauho.pocketts.proto.CloudProto.string
import java.net.URI
import java.security.MessageDigest
import java.util.Base64

/** Account profile is independent of both Matrix chat and the current voice server. */
class MyTsProfile(private val http: CloudTransport = HttpsCloudTransport) {
    class Data(val username: String, val email: String, val avatarUrl: String) {
        override fun toString() = "MyTsProfile(redacted)"
    }
    fun read(session: MyTsAccount.Session): Data {
        val fields = MyTsAccount(http).rpc("user", "getAccountData", CloudProto.text(1, session.token) +
            CloudProto.integer(2, 1) + CloudProto.integer(2, 3))
        val rc = fields.firstOrNull { it.number == 1 }?.bytes?.let(CloudProto::read)
        if (rc?.number(1) != 1L) throw CloudFailure("PROFILE_${rc?.number(3) ?: 0}")
        val avatars = fields.firstOrNull { it.number == 5 }?.bytes?.let(CloudProto::read).orEmpty()
        val online = avatars.filter { it.number == 1 }.mapNotNull { it.bytes?.let(CloudProto::read) }
            .firstOrNull { it.number(1) == 2L }?.string(2).orEmpty()
        return Data(fields.string(2), fields.string(3), safeAvatarUrl(online).orEmpty())
    }

    /** Register only after the bounded image upload succeeds. Never change other status avatars. */
    fun updateOnlineAvatar(session: MyTsAccount.Session, png: ByteArray) {
        val signature = byteArrayOf(0x89.toByte(), 80, 78, 71, 13, 10, 26, 10)
        if (png.size !in 24..524288 || !png.take(8).toByteArray().contentEquals(signature)) throw CloudFailure("AVATAR_IMAGE")
        val dimensions = java.nio.ByteBuffer.wrap(png, 16, 8)
        if (dimensions.int !in 1..320 || dimensions.int !in 1..320) throw CloudFailure("AVATAR_IMAGE")
        // The official client uses SHA-256, then Base64 with '+', '/', '=' removed, as the object name.
        val name = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(png))
            .filterNot { it == '+' || it == '/' || it == '=' }
        val auth = MyTsAccount(http)
        val reply = auth.rpc("user", "requestAvatarSignedUrl", CloudProto.text(1, session.token) + CloudProto.text(2, name))
        val rc = reply.firstOrNull { it.number == 1 }?.bytes?.let(CloudProto::read)
        if (rc?.number(1) != 1L) throw CloudFailure("AVATAR_AUTH")
        val urls = reply.filter { it.number == 2 }.mapNotNull { it.bytes?.let(CloudProto::read) }
        if (urls.size != 1 || urls.single().string(1) != name) throw CloudFailure("AVATAR_REPLY")
        val url = safeAvatarUrl(urls.single().string(2)) ?: throw CloudFailure("AVATAR_HOST")
        // Matches the official Cloud_Sync upload: PUT with text/plain, no account auth header or redirects.
        val uploaded = http.request("PUT", url, mapOf("Content-Type" to "text/plain"), png)
        if (uploaded.status !in 200..299) throw CloudFailure("AVATAR_HTTP_${uploaded.status}")
        val result = auth.rpc("user", "requestUploadAvatar", CloudProto.text(1, session.token) +
            CloudProto.bytes(2, CloudProto.integer(1, 2) + CloudProto.text(2, name)))
        if (result.number(1) != 1L) throw CloudFailure("AVATAR_SAVE")
    }

    fun removeOnlineAvatar(session: MyTsAccount.Session) {
        val reply = MyTsAccount(http).rpc("user", "requestDeleteAvatar", CloudProto.text(1, session.token) + CloudProto.integer(2, 2))
        if (reply.number(1) != 1L) throw CloudFailure("AVATAR_DELETE")
    }
    companion object {
        /** Official account avatars use Google Cloud Storage; never accept arbitrary image/upload hosts. */
        fun safeAvatarUrl(value: String): String? = try {
            val uri = URI(value)
            value.takeIf { value.length <= 4096 && uri.scheme == "https" && uri.userInfo == null &&
                uri.fragment == null && (uri.port == -1 || uri.port == 443) && uri.path.orEmpty().length > 1 &&
                (uri.host == "storage.googleapis.com" || uri.host?.endsWith(".storage.googleapis.com") == true) }
        } catch (_: Exception) { null }
    }
}
