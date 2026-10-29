package io.github.korppujauho.pocketts

import android.content.SharedPreferences
import io.github.korppujauho.pocketts.proto.CloudAccountTransfer
import io.github.korppujauho.pocketts.proto.CloudFailure
import io.github.korppujauho.pocketts.proto.Json
import io.github.korppujauho.pocketts.proto.MatrixCloud
import io.github.korppujauho.pocketts.proto.MyTsAccount
import io.github.korppujauho.pocketts.proto.MyTsBookmarks
import io.github.korppujauho.pocketts.proto.MyTsProfile
import java.util.Base64

/** Both secrets are sealed before a replacement is committed; encryption failures never save plaintext. */
class CloudAccountStore(private val prefs: SharedPreferences, private val box: () -> SecretBox = { Secrets.box }) {
    fun identity(): MyTsAccount.Session? {
        val stored = prefs.getString("myts-identity", null) ?: return null
        if (!SecretBox.isSealed(stored)) throw CloudFailure("LOCAL_STORE")
        val data = Json.parse(box().open(stored, "cloud/identity")) as? Map<*, *> ?: throw CloudFailure("LOCAL_STORE")
        val token = data["token"] as? String ?: throw CloudFailure("LOCAL_STORE")
        val username = data["username"] as? String ?: throw CloudFailure("LOCAL_STORE")
        if (token.isBlank()) throw CloudFailure("LOCAL_STORE")
        val encodedKey = data["syncKey"] as? String
        val key = if (encodedKey.isNullOrEmpty()) null else try {
            Base64.getDecoder().decode(encodedKey).also { if (it.size != 32) throw CloudFailure("LOCAL_STORE") }
        } catch (_: Exception) { throw CloudFailure("LOCAL_STORE") }
        return MyTsAccount.Session(token, username, key, bookmarkKeyError = data["bookmarkKeyError"] as? String,
            email = data["email"] as? String ?: "")
    }

    /** Commit account authentication without needing any chat service. Clear the previous account's chat secrets. */
    fun writeIdentity(identity: MyTsAccount.Session) {
        require(identity.token.isNotBlank())
        val sealed = sealIdentity(identity)
        if (!prefs.edit().putString("myts-identity", sealed).remove("account").remove("matrix-session").remove("myts-bookmarks").remove("myts-contacts").remove("myts-profile").commit())
            throw CloudFailure("LOCAL_STORE")
    }

    private fun sealIdentity(identity: MyTsAccount.Session) = box().seal(
        Json.write(mapOf("token" to identity.token, "username" to identity.username,
            "syncKey" to identity.syncKey?.let { Base64.getEncoder().encodeToString(it) },
            "bookmarkKeyError" to identity.bookmarkKeyError, "email" to identity.email)), "cloud/identity")

    fun profile(identity: MyTsAccount.Session): MyTsProfile.Data? {
        val stored = prefs.getString("myts-profile", null) ?: return null
        if (!SecretBox.isSealed(stored)) throw CloudFailure("LOCAL_STORE")
        val root = Json.parse(box().open(stored, "cloud/profile")) as? Map<*, *> ?: throw CloudFailure("LOCAL_STORE")
        if (root["owner"] != identity.token) return null
        return MyTsProfile.Data(root["username"] as? String ?: "", root["email"] as? String ?: "",
            MyTsProfile.safeAvatarUrl(root["avatarUrl"] as? String ?: "").orEmpty())
    }

    /** Enrich metadata without clearing the account's chat, bookmark keys, or contact cache. */
    fun writeProfile(identity: MyTsAccount.Session, profile: MyTsProfile.Data) {
        val data = Json.write(mapOf("owner" to identity.token, "username" to profile.username,
            "email" to profile.email, "avatarUrl" to MyTsProfile.safeAvatarUrl(profile.avatarUrl).orEmpty()))
        if (!prefs.edit().putString("myts-profile", box().seal(data, "cloud/profile")).commit()) throw CloudFailure("LOCAL_STORE")
    }

    fun bookmarks(identity: MyTsAccount.Session): List<MyTsBookmarks.Bookmark>? {
        val stored = prefs.getString("myts-bookmarks", null) ?: return null
        if (!SecretBox.isSealed(stored)) throw CloudFailure("LOCAL_STORE")
        val root = Json.parse(box().open(stored, "cloud/bookmarks")) as? Map<*, *> ?: throw CloudFailure("LOCAL_STORE")
        if (root["owner"] != identity.token) return null
        val rows = root["items"] as? List<*> ?: throw CloudFailure("LOCAL_STORE")
        return rows.map { row ->
            val item = row as? Map<*, *> ?: throw CloudFailure("LOCAL_STORE")
            fun text(key: String) = item[key] as? String ?: throw CloudFailure("LOCAL_STORE")
            MyTsBookmarks.Bookmark(text("id"), text("name"), text("address"), text("nickname"), text("password"))
        }
    }

    fun writeBookmarks(identity: MyTsAccount.Session, bookmarks: List<MyTsBookmarks.Bookmark>) {
        val data = Json.write(mapOf("owner" to identity.token, "items" to bookmarks.map {
            mapOf("id" to it.id, "name" to it.name, "address" to it.address, "nickname" to it.nickname, "password" to it.password)
        }))
        val sealed = box().seal(data, "cloud/bookmarks")
        if (!prefs.edit().putString("myts-bookmarks", sealed).commit()) throw CloudFailure("LOCAL_STORE")
    }

    fun account(): CloudAccountTransfer? = prefs.getString("account", null)?.let {
        if (!SecretBox.isSealed(it)) throw CloudFailure("LOCAL_STORE")
        CloudAccountTransfer.parse(box().open(it, "cloud/account"))
    }

    fun contacts(identity: MyTsAccount.Session): List<MyTsAccount.Contact>? {
        val stored = prefs.getString("myts-contacts", null) ?: return null
        if (!SecretBox.isSealed(stored)) throw CloudFailure("LOCAL_STORE")
        val data = Json.parse(box().open(stored, "cloud/contacts")) as? Map<*, *> ?: throw CloudFailure("LOCAL_STORE")
        if (data["owner"] != identity.token) return null
        return (data["items"] as? List<*> ?: throw CloudFailure("LOCAL_STORE")).map { row ->
            val item = row as? Map<*, *> ?: throw CloudFailure("LOCAL_STORE")
            fun text(key: String) = item[key] as? String ?: throw CloudFailure("LOCAL_STORE")
            MyTsAccount.Contact(text("identifier"), text("matrixId"), text("note"), text("profileName"))
        }
    }

    fun writeContacts(identity: MyTsAccount.Session, contacts: List<MyTsAccount.Contact>) {
        val data = Json.write(mapOf("owner" to identity.token, "items" to contacts.map {
            mapOf("identifier" to it.identifier, "matrixId" to it.matrixId, "note" to it.note, "profileName" to it.profileName)
        }))
        if (!prefs.edit().putString("myts-contacts", box().seal(data, "cloud/contacts")).commit()) throw CloudFailure("LOCAL_STORE")
    }
    fun session(userId: String, deviceId: String): MatrixCloud.Session? {
        val stored = prefs.getString("matrix-session", null) ?: return null
        if (!SecretBox.isSealed(stored)) throw CloudFailure("LOCAL_STORE")
        val data = Json.parse(box().open(stored, "cloud/session")) as? Map<*, *> ?: throw CloudFailure("LOCAL_STORE")
        if (data["user"] != userId) return null
        val token = data["token"] as? String ?: throw CloudFailure("LOCAL_STORE")
        return MatrixCloud.Session(userId, token, deviceId)
    }
    fun write(account: CloudAccountTransfer, session: MatrixCloud.Session) {
        require(account.userId == session.userId)
        val key = box()
        val sealedAccount = key.seal(account.encode(), "cloud/account")
        val sealedSession = key.seal(Json.write(mapOf("user" to session.userId, "token" to session.token)), "cloud/session")
        val editor = prefs.edit().putString("account", sealedAccount).putString("matrix-session", sealedSession)
        val previous = identity()
        if (account.myTsSession.isNotBlank()) editor.putString("myts-identity", sealIdentity(
            previous?.takeIf { it.token == account.myTsSession } ?: MyTsAccount.Session(account.myTsSession, account.username)))
        else editor.remove("myts-identity")
        if (previous?.token != account.myTsSession) editor.remove("myts-bookmarks").remove("myts-contacts").remove("myts-profile")
        if (!editor.commit())
            throw CloudFailure("LOCAL_STORE")
    }
    fun forget() {
        if (!prefs.edit().remove("myts-identity").remove("myts-bookmarks").remove("myts-contacts").remove("myts-profile").remove("account").remove("matrix-session").commit()) throw CloudFailure("LOCAL_STORE")
    }
}
