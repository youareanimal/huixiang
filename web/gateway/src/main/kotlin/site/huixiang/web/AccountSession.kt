package site.huixiang.web

import io.github.korppujauho.pocketts.proto.CloudFailure
import io.github.korppujauho.pocketts.proto.Json
import io.github.korppujauho.pocketts.proto.MyTsAccount
import io.github.korppujauho.pocketts.proto.MyTsBookmarks
import io.github.korppujauho.pocketts.proto.MyTsProfile
import io.github.korppujauho.pocketts.proto.MyTsVoiceData
import io.github.korppujauho.pocketts.proto.MyTsVoiceIdentity
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * One browser tab's myTeamSpeak account.
 *
 * The account is deliberately independent of the voice connection: you can sign in without joining a server, look
 * at your cloud bookmarks and friends, and a server connection started while signed in carries the voice identity
 * that makes TeamSpeak show the account's badges and signed avatar.
 *
 * Nothing is written to disk and the browser keeps no token: the myTeamSpeak session lives in this object, in the
 * gateway's memory, for as long as the tab's WebSocket is open. A reload means signing in again, which also means a
 * stolen browser profile cannot read the account afterwards.
 *
 * All account RPCs (login, bookmarks, contacts, profile) run on one thread. They block on HTTPS for up to half a
 * minute, and none of that may happen on Netty's event loop. Contact display-name lookups and the optional voice
 * metadata are public-read only and use a small side pool, so one slow Matrix profile cannot hold up a login.
 */
class AccountSession(
    private val push: (String) -> Unit,
    private val fileToken: String,
    private val log: (String) -> Unit = {},
) {
    companion object {
        /** The same ceiling MyTsProfile applies; checked before an HTTP upload leaves memory. */
        const val MAX_AVATAR_BYTES = 512 * 1024

        fun avatarErrorText(code: String): String = when {
            code == "MYTS_103" -> "账号会话已过期，请重新登录"
            code == "AVATAR_IMAGE" -> "头像需要是 PNG，且不超过 320×320 / 512 KB"
            code.startsWith("AVATAR_HTTP_") -> "头像上传失败，原头像仍保留"
            code == "AVATAR_AUTH" || code == "AVATAR_REPLY" || code == "AVATAR_HOST" || code == "AVATAR_SAVE" ->
                "myTeamSpeak 没有接受这次头像更新，原头像仍保留"
            code == "AVATAR_TIMEOUT" -> "头像上传超时，原头像仍保留"
            code == "NETWORK" -> "连不上 myTeamSpeak，稍后再试"
            else -> "头像更新失败（$code）"
        }
    }

    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "web-account").apply { isDaemon = true }
    }

    /** Public Matrix profile names and myTS metadata: read-only lookups that may wait on someone else's server. */
    private val sidePool = Executors.newFixedThreadPool(4) { r ->
        Thread(r, "web-account-side").apply { isDaemon = true }
    }

    private val deviceId = "web-" + UUID.randomUUID().toString().replace("-", "")
    private val contactsLock = Any()

    @Volatile private var session: MyTsAccount.Session? = null
    @Volatile private var voiceIdentity: MyTsVoiceIdentity? = null
    @Volatile private var phase = "guest"
    @Volatile private var username = ""
    @Volatile private var email = ""
    @Volatile private var avatarUrl = ""
    @Volatile private var error: String? = null
    @Volatile private var bookmarks: List<MyTsBookmarks.Bookmark> = emptyList()
    @Volatile private var bookmarksLoading = false
    @Volatile private var bookmarksError: String? = null
    @Volatile private var contacts: List<MyTsAccount.Contact> = emptyList()
    @Volatile private var contactsLoading = false
    @Volatile private var contactsError: String? = null
    @Volatile private var closed = false

    /** For [site.huixiang.web.WebSession.connect]: the account proof for the next voice connection, if it has one. */
    fun voiceIdentity(): MyTsVoiceIdentity? = voiceIdentity

    fun login(email: String, password: String, otp: String) {
        if (closed) return
        if (phase == "busy") return
        val address = email.trim()
        if (address.isBlank() || password.isEmpty() || address.length > 320 || password.length > 4096 || otp.length > 64) {
            phase = "guest"
            error = if (address.isBlank() || password.isEmpty()) "INPUT_REQUIRED" else "INPUT_INVALID"
            pushAccount()
            return
        }
        phase = "busy"
        error = null
        pushAccount()
        submit {
            try {
                val auth = MyTsAccount()
                val fresh = auth.login(address, password, otp.trim(), deviceId)
                if (closed) {
                    fresh.syncKey?.fill(0)
                    return@submit
                }
                session = fresh
                username = fresh.username
                this.email = fresh.email.ifBlank { address }
                avatarUrl = ""
                error = null
                voiceIdentity = try {
                    MyTsVoiceIdentity.fromSession(fresh)
                } catch (e: Exception) {
                    log("voice identity unavailable: ${e.message}")
                    null
                }
                phase = "signedIn"
                pushAccount()
                loadProfile(fresh)
                loadBookmarks(fresh)
                loadContacts(fresh)
            } catch (e: CloudFailure) {
                failLogin(e.code)
            } catch (e: Exception) {
                failLogin("MYTS_UNAVAILABLE")
            }
        }
    }

    fun logout() {
        if (closed) return
        submit {
            val old = session
            clear()
            pushAccount()
            pushBookmarks()
            pushContacts()
            // Only the session this gateway made, and never one imported from another device.
            if (old != null) try {
                MyTsAccount().revokeOwnSession(old)
            } catch (e: Exception) {
                log("revoke on logout failed: ${e.message}")
            }
        }
    }

    /** Re-read what the account knows; useful after changing something on another client. */
    fun refresh() {
        val current = session ?: return
        submit {
            if (session !== current) return@submit
            loadProfile(current)
            loadBookmarks(current)
            loadContacts(current)
        }
    }

    /** Blocks the calling HTTP worker until the upload is done; null means success. */
    fun setAvatar(png: ByteArray): String? = try {
        await {
            val current = session ?: throw CloudFailure("MYTS_103")
            if (png.isEmpty() || png.size > MAX_AVATAR_BYTES) throw CloudFailure("AVATAR_IMAGE")
            MyTsProfile().updateOnlineAvatar(current, png)
            loadProfile(current)
            null
        }
    } catch (e: CloudFailure) {
        e.code
    } catch (e: ExecutionException) {
        (e.cause as? CloudFailure)?.code ?: "AVATAR_FAILED"
    } catch (e: TimeoutException) {
        "AVATAR_TIMEOUT"
    } catch (e: Exception) {
        "AVATAR_FAILED"
    }

    fun removeAvatar(): String? = try {
        await {
            val current = session ?: throw CloudFailure("MYTS_103")
            MyTsProfile().removeOnlineAvatar(current)
            loadProfile(current)
            null
        }
    } catch (e: CloudFailure) {
        e.code
    } catch (e: ExecutionException) {
        (e.cause as? CloudFailure)?.code ?: "AVATAR_FAILED"
    } catch (e: TimeoutException) {
        "AVATAR_TIMEOUT"
    } catch (e: Exception) {
        "AVATAR_FAILED"
    }

    /**
     * Fetch the signed certificate/avatar/badges after the voice connection accepted the account proof. Optional:
     * the connection works without it, the server just shows less.
     */
    fun sendVoiceData(use: (MyTsVoiceData) -> Unit) {
        val current = session ?: return
        try {
            sidePool.execute {
                try {
                    val data = MyTsAccount().voiceData(current)
                    if (session === current && data != null) use(data)
                } catch (e: Exception) {
                    log("voice metadata unavailable: ${e.message}")
                }
            }
        } catch (_: RejectedExecutionException) {
            // The tab closed while the connection was being made.
        }
    }

    fun close() {
        closed = true
        session?.syncKey?.fill(0)
        session = null
        voiceIdentity = null
        worker.shutdownNow()
        sidePool.shutdownNow()
    }

    // ====== The work that must not run on Netty's thread ======

    private fun submit(block: () -> Unit) {
        try {
            worker.execute {
                if (closed) return@execute
                try {
                    block()
                } catch (e: CloudFailure) {
                    log("account operation failed: ${e.code}")
                } catch (e: Exception) {
                    log("account operation failed: ${e.message}")
                }
            }
        } catch (_: RejectedExecutionException) {
            // The tab is gone.
        }
    }

    private fun <T> await(block: () -> T): T {
        val result = CompletableFuture<T>()
        try {
            worker.execute {
                try {
                    result.complete(block())
                } catch (t: Throwable) {
                    result.completeExceptionally(t)
                }
            }
        } catch (_: RejectedExecutionException) {
            result.completeExceptionally(CloudFailure("MYTS_103"))
        }
        return result.get(60, TimeUnit.SECONDS)
    }

    private fun loadProfile(current: MyTsAccount.Session) {
        try {
            val profile = MyTsProfile().read(current)
            if (session !== current) return
            if (profile.username.isNotBlank()) username = profile.username
            if (profile.email.isNotBlank()) email = profile.email
            avatarUrl = profile.avatarUrl
            pushAccount()
        } catch (e: Exception) {
            log("profile unavailable: ${e.message}")
        }
    }

    private fun loadBookmarks(current: MyTsAccount.Session) {
        bookmarksLoading = true
        bookmarksError = null
        pushBookmarks()
        try {
            val items = MyTsBookmarks().read(current)
            if (session !== current) return
            bookmarks = items
            bookmarksError = null
        } catch (e: CloudFailure) {
            bookmarks = emptyList()
            bookmarksError = e.code
        } catch (e: Exception) {
            bookmarks = emptyList()
            bookmarksError = "BOOKMARK_INVALID_REPLY"
        } finally {
            if (session === current) {
                bookmarksLoading = false
                pushBookmarks()
            }
        }
    }

    private fun loadContacts(current: MyTsAccount.Session) {
        contactsLoading = true
        contactsError = null
        pushContacts()
        try {
            val auth = MyTsAccount()
            val items = auth.contacts(current)
            if (session !== current) return
            contacts = items
            contactsError = null
            contactsLoading = false
            pushContacts()
            for (contact in items) {
                if (!contact.needsProfileName) continue
                try {
                    sidePool.execute {
                        val named = auth.withContactProfile(contact)
                        if (named == contact || session !== current) return@execute
                        synchronized(contactsLock) {
                            if (session !== current) return@synchronized
                            contacts = contacts.map {
                                if (it.matrixId == contact.matrixId && it.identifier == contact.identifier) named else it
                            }
                        }
                        pushContacts()
                    }
                } catch (_: RejectedExecutionException) {
                    return
                }
            }
        } catch (e: CloudFailure) {
            if (session === current) {
                contacts = emptyList()
                contactsError = e.code
            }
        } catch (e: Exception) {
            if (session === current) {
                contacts = emptyList()
                contactsError = "CONTACTS_INVALID_REPLY"
            }
        } finally {
            if (session === current) {
                contactsLoading = false
                pushContacts()
            }
        }
    }

    private fun failLogin(code: String) {
        clear()
        phase = "guest"
        error = code
        pushAccount()
        pushBookmarks()
        pushContacts()
    }

    private fun clear() {
        session?.syncKey?.fill(0)
        session = null
        voiceIdentity = null
        username = ""
        email = ""
        avatarUrl = ""
        error = null
        bookmarks = emptyList()
        bookmarksLoading = false
        bookmarksError = null
        contacts = emptyList()
        contactsLoading = false
        contactsError = null
    }

    // ====== Snapshots ======

    private fun pushAccount() = push(
        Json.write(
            linkedMapOf(
                "t" to "account",
                "phase" to phase,
                "username" to username,
                "email" to email,
                "avatarUrl" to avatarUrl,
                "error" to error,
                "fileToken" to fileToken,
            )
        )
    )

    private fun pushBookmarks() {
        val items = bookmarks.map {
            linkedMapOf(
                "id" to it.id,
                "name" to it.name,
                "address" to it.address,
                "nickname" to it.nickname,
                // The browser needs it to fill the hidden server-password field; it never leaves this tab's TLS
                // connection and is not written to localStorage.
                "password" to it.password,
            )
        }
        push(
            Json.write(
                linkedMapOf(
                    "t" to "bookmarks",
                    "items" to items,
                    "error" to bookmarksError,
                    "loading" to bookmarksLoading,
                )
            )
        )
    }

    private fun pushContacts() {
        val items = synchronized(contactsLock) {
            contacts.map {
                linkedMapOf(
                    "id" to it.identifier,
                    "name" to it.displayName,
                    "subtitle" to it.subtitle,
                )
            }
        }
        push(
            Json.write(
                linkedMapOf(
                    "t" to "contacts",
                    "items" to items,
                    "error" to contactsError,
                    "loading" to contactsLoading,
                )
            )
        )
    }
}
