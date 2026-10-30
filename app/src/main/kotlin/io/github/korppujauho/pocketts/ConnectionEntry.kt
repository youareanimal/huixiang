package io.github.korppujauho.pocketts

import android.content.Context

/** Account entry and an explicitly chosen visitor session are mutually exclusive. */
class ConnectionEntry(context: Context) {
    private val prefs = context.getSharedPreferences("hub", Context.MODE_PRIVATE)
    // The old "entered" flag also meant account login, so it cannot authorize a visitor connection.
    val guestEntered: Boolean get() = prefs.getBoolean("guest-entered", false)
    fun enterGuest(account: CloudAccountController.State): Boolean {
        if (!account.restored || account.hasAccount) return false
        prefs.edit().putBoolean("guest-entered", true).remove("entered").apply()
        return true
    }
    fun clearGuest() { prefs.edit().remove("guest-entered").remove("entered").apply() }
    fun canConnect(account: CloudAccountController.State): Boolean =
        account.restored && (account.hasAccount || guestEntered)

    /** A historical nickname, bookmark or invitation must never override the signed-in account. */
    fun nickname(account: CloudAccountController.State, requested: String): String? =
        if (!canConnect(account)) null
        else (if (account.hasAccount) account.username else requested).takeIf { it.isNotBlank() }

    fun recentTarget(account: CloudAccountController.State, recent: Favorite, favorites: List<Favorite>): Favorite {
        val bookmark = if (account.hasAccount) account.bookmarks.firstOrNull { Favorites.same(it.address, recent.address) } else null
        val saved = bookmark?.let { Favorite(it.address, it.name, it.nickname, it.password) }
            ?: favorites.firstOrNull { Favorites.same(it.address, recent.address) } ?: recent
        return saved.copy(name = recent.name.ifBlank { saved.name },
            nickname = if (account.hasAccount) account.username else saved.nickname, auto = false)
    }
}
