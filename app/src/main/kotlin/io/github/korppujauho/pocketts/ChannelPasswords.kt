package io.github.korppujauho.pocketts

import android.content.Context
import io.github.korppujauho.pocketts.proto.ChatHistory
import org.json.JSONObject

/**
 * The channel passwords the person chose to remember, per server and channel. Encrypted with the other secrets
 * (see [secretPrefs]), so they're never copied to a new phone.
 */
class ChannelPasswords(context: Context) {
    private val prefs = secretPrefs(context)

    fun get(server: String, channel: Int): String? = all().optString(key(server, channel)).ifEmpty { null }

    fun put(server: String, channel: Int, password: String) = save(all().put(key(server, channel), password))

    fun remove(server: String, channel: Int) {
        val all = all()
        if (all.has(key(server, channel))) save(all.apply { remove(key(server, channel)) })
    }

    fun clear() = prefs.edit().remove(NAME).apply()

    // One that can't be opened (a new phone, a reset Keystore) is as if none were saved
    private fun all(): JSONObject = try {
        JSONObject(prefs.getSecret(NAME, LABEL) ?: "{}")
    } catch (e: Exception) {
        AppLog.log("Saved channel passwords can't be read: ${e.javaClass.simpleName}")
        JSONObject()
    }

    private fun save(all: JSONObject) = prefs.edit().putSecret(NAME, LABEL, all.toString()).apply()

    // The server as typed in different ways ("Host:9987", "host") is the same server
    private fun key(server: String, channel: Int) = "${ChatHistory.serverKey(server) ?: server}#$channel"

    private companion object {
        const val NAME = "channelPasswords"
        const val LABEL = "channels/passwords"
    }
}
