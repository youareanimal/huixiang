package site.huixiang.web

import io.github.korppujauho.pocketts.proto.ChatHistory
import io.github.korppujauho.pocketts.proto.Json
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors

/**
 * 频道聊天的历史，网关侧的一份。
 *
 * TeamSpeak 服务器自己不保存频道消息（TS6 的服务端持久化仍是待办），所以「历史」只能是有人在收消息的时候记下来。
 * 网关恰好一直连着，就有两个用处：同一网关下换浏览器、换设备，还能看到之前聊过什么；浏览器本地库没覆盖到的
 * 那一段，也能由它补上。
 *
 * 浏览器本地（IndexedDB）是默认的那一份，这个存储是可选的：只有设了 `CHAT_HISTORY_DIR` 才落盘，否则只活在内存里，
 * 网关重启就清空。落盘时每个服务器一个 JSONL，一行一条消息，和内存里的一样按 [ChatHistory] 的规则修剪
 * （90 天、每会话 300 条、每服务器 100 个会话）。
 *
 * 这里只记频道消息，不记私聊：私聊按对方身份分档，网关不分身份地存下来的话，谁连上都可能读到别人的话。
 */
class ChannelHistory(private val dir: File?, private val log: (String) -> Unit = {}) {

    data class Line(val time: Long, val from: Int, val name: String, val uid: String, val text: String)

    /** 一页结果：按时间从旧到新，[more] 表示这个频道还有更早的。 */
    data class Page(val lines: List<Line>, val more: Boolean)

    private val lock = Any()

    /** key 是「服务器 key + 频道 id」，保持插入顺序，淘汰时就按它最后一条的时间。 */
    private val conversations = LinkedHashMap<String, ArrayDeque<Line>>()

    private val writer = if (dir != null) {
        Executors.newSingleThreadExecutor { r ->
            Thread(r, "web-chat-history").apply { isDaemon = true }
        }
    } else {
        null
    }

    init {
        if (dir != null) load()
    }

    /** 一条收到的频道消息。修剪在这里做，读的时候就总是干净的。 */
    fun append(server: String, channel: Int, line: Line) {
        if (server.isEmpty() || channel <= 0 || line.text.isEmpty()) return
        val kept: Boolean
        synchronized(lock) {
            val key = key(server, channel)
            val list = conversations.getOrPut(key) { ArrayDeque() }
            list.addLast(line)
            trimLocked(server, channel)
            kept = conversations[key]?.isNotEmpty() == true
        }
        if (kept) save(server, channel, line)
    }

    /** [before] 之前的 [limit] 条（不含 [before] 本身）；before <= 0 表示从最新往前取。 */
    fun page(server: String, channel: Int, before: Long, limit: Int): Page {
        val size = limit.coerceIn(1, ChatHistory.LINES)
        val all = synchronized(lock) { conversations[key(server, channel)]?.toList() ?: emptyList() }
        val older = if (before > 0) all.filter { it.time < before } else all
        val lines = older.takeLast(size)
        return Page(lines, older.size > lines.size)
    }

    /** 忘掉一个服务器的历史（目前没有入口调用，留给将来的「清除记录」）。 */
    fun clear(server: String) {
        synchronized(lock) {
            conversations.keys.removeAll { it.startsWith("$server\u0000") }
        }
        if (dir == null) return
        file(server).delete()
    }

    // ====== 修剪 ======

    private fun trimLocked(server: String, channel: Int) {
        val key = key(server, channel)
        val list = conversations[key] ?: return
        val from = System.currentTimeMillis() - ChatHistory.DAYS * DAY
        while (list.isNotEmpty() && list.first().time < from) list.removeFirst()
        while (list.size > ChatHistory.LINES) list.removeFirst()
        if (list.isEmpty()) {
            conversations.remove(key)
            return
        }
        if (conversations.size > ChatHistory.CONVERSATIONS) {
            // 最久没说话的会话先走；不能删掉刚写进来的那个
            conversations.entries
                .filter { it.key != key }
                .sortedBy { it.value.lastOrNull()?.time ?: 0L }
                .take(conversations.size - ChatHistory.CONVERSATIONS)
                .forEach { conversations.remove(it.key) }
        }
    }

    // ====== 落盘（可选） ======

    private fun load() {
        val files = dir?.listFiles { f -> f.isFile && f.name.endsWith(".jsonl") } ?: return
        for (f in files) {
            try {
                for (raw in f.readLines()) {
                    if (raw.isBlank()) continue
                    val m = Json.parse(raw) as? Map<*, *> ?: continue
                    val server = m["server"] as? String ?: continue
                    val channel = (m["cid"] as? Number)?.toInt() ?: continue
                    val time = (m["time"] as? Number)?.toLong() ?: continue
                    val text = m["text"] as? String ?: continue
                    if (server.isEmpty() || channel <= 0 || text.isEmpty()) continue
                    val line = Line(
                        time = time,
                        from = (m["from"] as? Number)?.toInt() ?: 0,
                        name = m["name"] as? String ?: "",
                        uid = m["uid"] as? String ?: "",
                        text = text,
                    )
                    synchronized(lock) {
                        conversations.getOrPut(key(server, channel)) { ArrayDeque() }.addLast(line)
                    }
                }
            } catch (e: Exception) {
                log("chat history $f can't be read: ${e.javaClass.simpleName}")
            }
        }
        // 文件里可能是按追加顺序排的，也可能有旧的；统一修剪一次，读出来的顺序才和内存里一样
        val servers = synchronized(lock) { conversations.keys.map { it.substringBefore('\u0000') }.toSet() }
        for (server in servers) {
            synchronized(lock) {
                val channels = conversations.keys
                    .filter { it.startsWith("$server\u0000") }
                    .map { it.substringAfter('\u0000').toInt() }
                for (cid in channels) {
                    val list = conversations[key(server, cid)] ?: continue
                    val sorted = list.sortedBy { it.time }
                    list.clear()
                    list.addAll(sorted)
                    trimLocked(server, cid)
                }
            }
        }
    }

    private fun save(server: String, channel: Int, line: Line) {
        val w = writer ?: return
        val target = file(server)
        val row = Json.write(
            mapOf(
                "server" to server,
                "cid" to channel,
                "time" to line.time,
                "from" to line.from,
                "name" to line.name,
                "uid" to line.uid,
                "text" to line.text,
            )
        )
        try {
            w.execute {
                try {
                    target.parentFile?.mkdirs()
                    target.appendText(row + "\n")
                } catch (e: Exception) {
                    log("chat history write failed: ${e.javaClass.simpleName}")
                }
            }
        } catch (e: Exception) {
            // 线程池已经关了（进程结束）：内存里的那份还在，只是这一行没有落盘
        }
    }

    /** 服务器不写进文件名：地址里有主机名和端口，哈希一下更省事，也避免路径问题。 */
    private fun file(server: String): File {
        val hash = MessageDigest.getInstance("SHA-256").digest(server.toByteArray())
            .joinToString("") { "%02x".format(it) }
        return File(dir, "${hash.take(24)}.jsonl")
    }

    private fun key(server: String, channel: Int) = "$server\u0000$channel"

    private companion object {
        const val DAY = 86_400_000L
    }
}
