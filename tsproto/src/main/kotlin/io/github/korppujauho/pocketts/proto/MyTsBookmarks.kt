package io.github.korppujauho.pocketts.proto

import io.github.korppujauho.pocketts.proto.CloudProto.number
import io.github.korppujauho.pocketts.proto.CloudProto.string
import java.security.MessageDigest
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.AEADBadTagException
import javax.crypto.BadPaddingException
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** Read-only account bookmark synchronization, independent of Matrix and voice identity management. */
class MyTsBookmarks(private val http: CloudTransport = HttpsCloudTransport) {
    class Bookmark(val id: String, val name: String, val address: String, val nickname: String, val password: String) {
        override fun toString() = "MyTsBookmark(redacted)"
    }

    fun read(session: MyTsAccount.Session): List<Bookmark> {
        val key = session.syncKey ?: throw CloudFailure(session.bookmarkKeyError ?: "BOOKMARK_KEY_REQUIRED")
        val name = "requestServerItems".toByteArray(Charsets.US_ASCII)
        val payload = CloudProto.text(1, session.token) +
            CloudProto.bytes(2, CloudProto.integer(1, 0)) + CloudProto.integer(4, 1)
        val reply = http.request("POST", "https://clientapi.myteamspeak.com/synchronization",
            mapOf("Content-Type" to "application/ts3cloud"), CloudProto.varint(name.size.toLong()) + name + payload)
        if (reply.status !in 200..299) throw CloudFailure("HTTP_${reply.status}")
        return try {
            val fields = CloudProto.read(reply.body)
            if (fields.number(1) !in setOf(300L, 301L)) throw CloudFailure("BOOKMARK_SYNC_${fields.number(1) ?: 0}")
            val result = mutableListOf<Bookmark>()
            for (itemClass in fields.filter { it.number == 2 }) {
                val group = CloudProto.read(itemClass.bytes ?: throw CloudFailure("BOOKMARK_INVALID_REPLY"))
                if ((group.number(1) ?: 0) != 0L) continue
                for (item in group.filter { it.number == 3 }) {
                    val detail = CloudProto.read(item.bytes ?: throw CloudFailure("BOOKMARK_INVALID_REPLY"))
                    if (detail.number(4) == 1L) continue
                    val blob = detail.firstOrNull { it.number == 3 }?.bytes ?: throw CloudFailure("BOOKMARK_INVALID_REPLY")
                    val plain = openItem(blob, key)
                    try {
                        val data = CloudProto.read(plain)
                        val id = data.string(2)
                        if (id.isBlank() || id != detail.string(1)) throw CloudFailure("BOOKMARK_INVALID_REPLY")
                        val bookmark = data.firstOrNull { it.number == 16 }?.bytes ?: continue
                        val values = CloudProto.read(bookmark)
                        val host = values.string(2).trim()
                        if (host.isEmpty()) continue // Folder or an unfinished bookmark.
                        if (host.length > 512 || host.any { it.isWhitespace() || it in "/?#@" }) throw CloudFailure("BOOKMARK_INVALID_REPLY")
                        val typed = ServerAddress.parse(host) ?: throw CloudFailure("BOOKMARK_INVALID_REPLY")
                        val port = typed.port ?: (values.number(3) ?: 9987).toInt()
                        if (port !in 1..65535) throw CloudFailure("BOOKMARK_INVALID_REPLY")
                        val hostname = if (':' in typed.host) "[${typed.host}]" else typed.host
                        val address = if (port == 9987) hostname else "$hostname:$port"
                        val title = values.string(1); val nickname = values.string(4); val password = values.string(7)
                        if (title.length > 1024 || nickname.length > 1024 || password.length > 4096) throw CloudFailure("BOOKMARK_INVALID_REPLY")
                        result += Bookmark(id, title, address, nickname, password)
                    } finally { plain.fill(0) }
                }
            }
            if (result.map { it.id }.distinct().size != result.size) throw CloudFailure("BOOKMARK_INVALID_REPLY")
            result
        } catch (e: CloudFailure) { throw e }
        catch (_: Exception) { throw CloudFailure("BOOKMARK_INVALID_REPLY") }
    }

    companion object {
        fun encryptionPassword(email: String, password: String): ByteArray {
            val salt = (email.lowercase(Locale.ROOT) + "ts3Encryption" + password).toByteArray(Charsets.UTF_8)
            val spec = PBEKeySpec(password.toCharArray(), salt, 10_000, 256)
            return try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA512").generateSecret(spec).encoded }
            finally { spec.clearPassword(); salt.fill(0) }
        }

        /** Versions 1 and 2: version byte, GCM tag, 12-byte IV, encrypted 32-byte sync key.
         * Version 2 uses the already-derived encryption password directly, verified against the official reader.
         */
        fun unlockKey(wrapped: ByteArray, passwordKey: ByteArray): ByteArray {
            val version = wrapped.firstOrNull()?.toInt()?.and(255) ?: -1
            if (wrapped.size != 61 || version !in 1..2 || passwordKey.size != 32)
                // Only public envelope dimensions are reported; never include key bytes or ciphertext.
                throw CloudFailure("BOOKMARK_KEY_FORMAT_B${wrapped.size}_V${version}_K${passwordKey.size}")
            return try {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(passwordKey, "AES"), GCMParameterSpec(128, wrapped.copyOfRange(17, 29)))
                cipher.doFinal(wrapped.copyOfRange(29, wrapped.size) + wrapped.copyOfRange(1, 17))
            } catch (_: AEADBadTagException) { throw CloudFailure("BOOKMARK_KEY_AUTH") }
            catch (_: BadPaddingException) { throw CloudFailure("BOOKMARK_KEY_AUTH") }
            catch (_: Exception) { throw CloudFailure("BOOKMARK_CRYPTO_UNAVAILABLE") }
        }

        /** IV + AES-256 CTR with a little-endian counter + SHA-512 of the unencrypted serialized item. */
        fun openItem(blob: ByteArray, key: ByteArray): ByteArray {
            if (blob.size !in 81..1_048_576 || key.size != 32) throw CloudFailure("BOOKMARK_INVALID_REPLY")
            val counter = blob.copyOfRange(0, 16)
            val plain = ByteArray(blob.size - 80)
            val aes = Cipher.getInstance("AES/ECB/NoPadding")
            aes.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
            for (offset in plain.indices step 16) {
                val block = aes.doFinal(counter)
                for (j in 0 until minOf(16, plain.size - offset)) plain[offset + j] = (blob[16 + offset + j].toInt() xor block[j].toInt()).toByte()
                block.fill(0)
                for (j in counter.indices) { counter[j] = (counter[j] + 1).toByte(); if (counter[j] != 0.toByte()) break }
            }
            counter.fill(0)
            if (!MessageDigest.isEqual(Crypto.sha512(plain), blob.copyOfRange(blob.size - 64, blob.size))) {
                plain.fill(0)
                throw CloudFailure("BOOKMARK_CHECKSUM")
            }
            return plain
        }
    }
}
