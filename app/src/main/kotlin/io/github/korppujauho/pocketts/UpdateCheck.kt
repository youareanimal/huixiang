package io.github.korppujauho.pocketts

import android.content.Context
import io.github.korppujauho.pocketts.proto.Json
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Whether a newer PocketTS is out on GitHub, for the version installed from there (Google Play updates apps by
 * itself). At most once a day it asks GitHub for the latest release; nothing about the person is sent. The answer
 * shows as a card on the connect screen until it's dismissed or that version is installed. Off in
 * Settings > General > Check for updates.
 */
object UpdateCheck {
    data class Release(val version: String, val url: String)

    private const val LATEST = "https://api.github.com/repos/KorppuJauho/pocketts/releases/latest"
    private const val RELEASES = "https://github.com/KorppuJauho/pocketts/releases"
    private const val DAY_MS = 24 * 3600 * 1000L

    private val _available = MutableStateFlow<Release?>(null)
    val available: StateFlow<Release?> = _available

    /** Whether this build checks at all (the GitHub release does; test builds don't). */
    val enabled: Boolean get() = BuildConfig.UPDATE_CHECK

    fun start(context: Context) {
        if (!enabled || !VoiceSettings.load(context).checkUpdates) return
        val prefs = context.applicationContext.getSharedPreferences("updates", Context.MODE_PRIVATE)
        // The last answer shows at once; GitHub is asked again only after a day
        prefs.getString("version", null)?.let { offer(prefs, it, prefs.getString("url", null) ?: RELEASES) }
        if (System.currentTimeMillis() - prefs.getLong("checked", 0) < DAY_MS) return
        Thread({
            try {
                val c = java.net.URL(LATEST).openConnection() as java.net.HttpURLConnection
                c.connectTimeout = 10_000
                c.readTimeout = 10_000
                c.setRequestProperty("Accept", "application/vnd.github+json")
                c.setRequestProperty("User-Agent", "PocketTS")
                val body = try {
                    if (c.responseCode != 200) null else c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
                } finally {
                    c.disconnect()
                }
                val release = body?.let { Json.parse(it) as? Map<*, *> } ?: return@Thread
                val version = (release["tag_name"] as? String)?.removePrefix("v")?.trim()?.ifEmpty { null } ?: return@Thread
                val url = (release["html_url"] as? String)?.takeIf { it.startsWith("https://github.com/") } ?: RELEASES
                prefs.edit().putLong("checked", System.currentTimeMillis()).putString("version", version).putString("url", url).apply()
                AppLog.log("Update check: latest is $version")
                offer(prefs, version, url)
            } catch (e: Exception) {
                AppLog.log("Update check failed: ${e.javaClass.simpleName}")
            }
        }, "update-check").apply { isDaemon = true }.start()
    }

    /** "Not now": this version isn't offered again (a later one is). */
    fun dismiss(context: Context) {
        val r = _available.value ?: return
        context.applicationContext.getSharedPreferences("updates", Context.MODE_PRIVATE).edit().putString("dismissed", r.version).apply()
        _available.value = null
    }

    private fun offer(prefs: android.content.SharedPreferences, version: String, url: String) {
        if (newer(version, BuildConfig.VERSION_NAME) && version != prefs.getString("dismissed", null)) {
            _available.value = Release(version, url)
        }
    }

    /** Whether version [a] is later than [b] ("0.10.0" > "0.9.2"; anything after a "-" is left out). */
    fun newer(a: String, b: String): Boolean {
        fun parts(v: String) = v.substringBefore('-').split('.').map { it.trim().toIntOrNull() ?: 0 }
        val x = parts(a)
        val y = parts(b)
        for (i in 0 until maxOf(x.size, y.size)) {
            val d = x.getOrElse(i) { 0 } - y.getOrElse(i) { 0 }
            if (d != 0) return d > 0
        }
        return false
    }
}
