package io.github.korppujauho.pocketts.proto

/**
 * A TeamSpeak command: `name key=value key=value|key=value ...`. The parts between `|` are [groups]
 * (one per channel or client in a list); [params] is the first one.
 */
class Command(val name: String, val groups: List<Map<String, String>>) {
    val params: Map<String, String> get() = groups.firstOrNull() ?: emptyMap()
    operator fun get(key: String): String? = params[key]

    override fun toString(): String = Commands.build(name, params)
}

object Commands {
    private val ESCAPE = mapOf(
        '\\' to "\\\\", '/' to "\\/", ' ' to "\\s", '|' to "\\p",
        '\u000c' to "\\f", '\n' to "\\n", '\r' to "\\r", '\t' to "\\t", '\u000b' to "\\v",
    )
    private val UNESCAPE = mapOf(
        '\\' to '\\', '/' to '/', 's' to ' ', 'p' to '|',
        'f' to '\u000c', 'n' to '\n', 'r' to '\r', 't' to '\t', 'v' to '\u000b',
    )

    fun escape(s: String): String {
        val sb = StringBuilder(s.length + 8)
        for (ch in s) sb.append(ESCAPE[ch] ?: ch)
        return sb.toString()
    }

    fun unescape(s: String): String {
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val ch = s[i]
            if (ch == '\\') {
                i++
                require(i < s.length) { "Invalid escape sequence" }
                sb.append(UNESCAPE[s[i]] ?: throw IllegalArgumentException("Unknown escape: \\${s[i]}"))
            } else {
                sb.append(ch)
            }
            i++
        }
        return sb.toString()
    }

    /** Builds a command; null values are left out, booleans become 1/0. */
    fun build(name: String, params: Map<String, Any?>): String {
        val sb = StringBuilder(escape(name))
        for ((k, v) in params) {
            if (v == null) continue
            val s = when (v) {
                is Boolean -> if (v) "1" else "0"
                else -> v.toString()
            }
            sb.append(' ').append(k).append('=').append(escape(s))
        }
        return sb.toString()
    }

    fun build(name: String, vararg params: Pair<String, Any?>): String = build(name, linkedMapOf(*params))

    fun parse(raw: String): Command {
        val parts = raw.split('|')
        val first = parts[0].trim().split(' ')
        val name = unescape(first[0])
        val groups = ArrayList<Map<String, String>>(parts.size)
        groups.add(parseTokens(first.drop(1)))
        for (g in 1 until parts.size) groups.add(parseTokens(parts[g].trim().split(' ')))
        return Command(name, groups)
    }

    private fun parseTokens(tokens: List<String>): Map<String, String> {
        val m = LinkedHashMap<String, String>()
        for (t in tokens) {
            if (t.isEmpty()) continue
            val eq = t.indexOf('=')
            if (eq >= 0) m[t.substring(0, eq)] = unescape(t.substring(eq + 1)) else m[t] = ""
        }
        return m
    }
}
