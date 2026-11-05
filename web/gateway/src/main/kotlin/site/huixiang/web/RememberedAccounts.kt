package site.huixiang.web

import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/**
 * One account session kept alive for a browser that asked to be remembered.
 *
 * The browser itself only ever holds a random id (an HttpOnly cookie), so a stolen browser profile, an XSS bug or a
 * runaway extension cannot read a myTeamSpeak token out of it. Everything that actually grants access — the token,
 * the bookmark sync key, the voice identity material — stays in this process's memory and dies with it: a gateway
 * restart means signing in again, deliberately.
 */
class RememberedAccount(
    val token: String,
    val username: String,
    val email: String,
    val syncKey: ByteArray?,
    val bookmarkKeyError: String?,
    /** [io.github.korppujauho.pocketts.proto.MyTsVoiceMaterial.encodeForStorage] output, never the raw keys. */
    val voiceMaterial: String?,
    val voiceMaterialError: String?,
)

/**
 * The gateway's table of remembered account sessions, addressed by the cookie id.
 *
 * Nothing is written to disk. Entries expire after [ttlMillis] (30 days by default, `ACCOUNT_REMEMBER_DAYS` to
 * change it, `0` to switch remembering off entirely). Only an explicit sign-out drops an entry early; closing the
 * tab, reloading or losing the network never does — that is the whole point of the feature.
 */
object RememberedAccounts {
    private val DAY_MILLIS = 24L * 60L * 60L * 1000L

    /** How long a remembered sign-in lasts; 0 disables remembering. */
    val ttlMillis: Long = (System.getenv("ACCOUNT_REMEMBER_DAYS")?.toLongOrNull() ?: 30L)
        .coerceIn(0L, 3650L) * DAY_MILLIS

    val enabled: Boolean get() = ttlMillis > 0

    /** Seconds, for the cookie's Max-Age. */
    val ttlSeconds: Long get() = ttlMillis / 1000

    private class Entry(val account: RememberedAccount, val expiresAt: Long)

    private val entries = ConcurrentHashMap<String, Entry>()
    private val random = SecureRandom()

    /** Stores [account] under a fresh random id, or null when remembering is disabled. */
    fun put(account: RememberedAccount): String? {
        if (!enabled) return null
        sweep()
        val bytes = ByteArray(24)
        random.nextBytes(bytes)
        val id = bytes.joinToString("") { "%02x".format(it) }
        entries[id] = Entry(account, System.currentTimeMillis() + ttlMillis)
        return id
    }

    /** The live account behind [id], or null when unknown or expired (expired entries are dropped on the way). */
    fun get(id: String): RememberedAccount? {
        val entry = entries[id] ?: return null
        if (entry.expiresAt <= System.currentTimeMillis()) {
            entries.remove(id, entry)
            return null
        }
        return entry.account
    }

    fun contains(id: String): Boolean = get(id) != null

    /** Replaces the snapshot behind [id] (e.g. a new avatar or username after a resume), keeping the same id. */
    fun update(id: String, account: RememberedAccount) {
        val entry = entries[id] ?: return
        if (entry.expiresAt <= System.currentTimeMillis()) {
            entries.remove(id, entry)
            return
        }
        entries[id] = Entry(account, entry.expiresAt)
    }

    fun drop(id: String) {
        entries.remove(id)
    }

    private fun sweep() {
        val now = System.currentTimeMillis()
        entries.entries.removeIf { it.value.expiresAt <= now }
    }
}
