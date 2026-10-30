package io.github.korppujauho.pocketts

import android.content.Context
import io.github.korppujauho.pocketts.proto.JoinLink
import org.json.JSONArray
import org.json.JSONObject

/** Recently successful connections contain display metadata only, never passwords or invitation URLs. */
object RecentServers {
    fun load(context: Context): List<Favorite> = try {
        val rows = JSONArray(context.getSharedPreferences("recent-servers", Context.MODE_PRIVATE).getString("items", "[]"))
        (0 until minOf(rows.length(), 6)).map { index -> rows.getJSONObject(index).let {
            Favorite(it.getString("address"), it.optString("name"), it.optString("nickname"))
        } }.filter { it.address.isNotBlank() && !JoinLink.isLink(it.address) }
    } catch (_: Exception) { emptyList() }

    fun record(context: Context, server: Favorite): List<Favorite> {
        if (server.address.isBlank() || JoinLink.isLink(server.address)) return load(context)
        val safe = Favorite(server.address, server.name, server.nickname)
        val items = (listOf(safe) + load(context).filterNot { Favorites.same(it.address, safe.address) && it.nickname == safe.nickname }).take(6)
        val rows = JSONArray().apply { items.forEach {
            put(JSONObject().put("address", it.address).put("name", it.name).put("nickname", it.nickname))
        } }
        context.getSharedPreferences("recent-servers", Context.MODE_PRIVATE).edit().putString("items", rows.toString()).apply()
        return items
    }
}
