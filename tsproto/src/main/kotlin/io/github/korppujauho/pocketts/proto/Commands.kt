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
        '\u0007' to "\\a", '\b' to "\\b", '\u000c' to "\\f", '\n' to "\\n", '\r' to "\\r", '\t' to "\\t", '\u000b' to "\\v",
    )
    private val UNESCAPE = mapOf(
        '\\' to '\\', '/' to '/', 's' to ' ', 'p' to '|',
        'a' to '\u0007', 'b' to '\b', 'f' to '\u000c', 'n' to '\n', 'r' to '\r', 't' to '\t', 'v' to '\u000b',
    )

    fun escape(s: String): String {
        val sb = StringBuilder(s.length + 8)
        for (ch in s) sb.append(ESCAPE[ch] ?: ch)
        return sb.toString()
    }

    /** The command protocol also carries opaque binary values; never UTF-8 encode those values. */
    fun escapeBytes(bytes: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream(bytes.size)
        for (byte in bytes) {
            val value = byte.toInt() and 255
            val replacement = ESCAPE[value.toChar()]
            if (replacement == null) out.write(value)
            else out.write(replacement.toByteArray(Charsets.US_ASCII))
        }
        return out.toByteArray()
    }

    fun buildBytes(name: String, params: Map<String, Any?>): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        out.write(escapeBytes(name.toByteArray(Charsets.UTF_8)))
        for ((key, value) in params) {
            if (value == null) continue
            out.write(' '.code)
            out.write(escapeBytes(key.toByteArray(Charsets.UTF_8)))
            out.write('='.code)
            val bytes = when (value) {
                is ByteArray -> value
                is Boolean -> (if (value) "1" else "0").toByteArray(Charsets.US_ASCII)
                else -> value.toString().toByteArray(Charsets.UTF_8)
            }
            out.write(escapeBytes(bytes))
        }
        return out.toByteArray()
    }

    fun buildBytes(name: String, vararg params: Pair<String, Any?>): ByteArray = buildBytes(name, linkedMapOf(*params))

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
