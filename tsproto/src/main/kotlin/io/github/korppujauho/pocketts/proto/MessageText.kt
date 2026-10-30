package io.github.korppujauho.pocketts.proto

/**
 * A chat message's text as pieces with their style: links (bare, or TeamSpeak's [URL] tags), bold, italic,
 * underline and strike-through (TeamSpeak's [b] [i] [u] [s] tags and the **, *, ++, ~~ marks WebTS shows).
 * Other tags ([color], [size]...) are left out, their text kept.
 */
object MessageText {
    data class Span(
        val text: String,
        val bold: Boolean = false,
        val italic: Boolean = false,
        val underline: Boolean = false,
        val strike: Boolean = false,
        val link: String? = null,
    )

    private val TAG = Regex("""\[(/?)([a-zA-Z*]+)(?:=([^\]]*))?]""")
    private val URL = Regex("""https?://[^\s\[\]<>"]+""")
    private val MARKS = listOf("**" to "b", "++" to "u", "~~" to "s", "*" to "i")

    fun parse(input: String): List<Span> {
        val out = ArrayList<Span>()
        var b = 0; var i = 0; var u = 0; var s = 0
        var link: String? = null

        fun emit(text: String) {
            if (text.isEmpty()) return
            if (link != null) {
                out.add(Span(text, b > 0, i > 0, u > 0, s > 0, link))
                return
            }
            // Bare links inside plain text
            var last = 0
            for (m in URL.findAll(text)) {
                addMarked(out, text.substring(last, m.range.first), b > 0, i > 0, u > 0, s > 0)
                val url = m.value.trimEnd('.', ',', ')', '!', '?', ';', ':')
                out.add(Span(url, b > 0, i > 0, u > 0, s > 0, url))
                if (url.length < m.value.length) addMarked(out, m.value.substring(url.length), b > 0, i > 0, u > 0, s > 0)
                last = m.range.last + 1
            }
            addMarked(out, text.substring(last), b > 0, i > 0, u > 0, s > 0)
        }

        var pos = 0
        for (m in TAG.findAll(input)) {
            val name = m.groupValues[2].lowercase()
            val closing = m.groupValues[1] == "/"
            if (name !in setOf("b", "i", "u", "s", "url", "color", "size", "left", "right", "center", "img", "list", "*", "hr", "table", "tr", "td", "th", "quote", "code")) continue
            emit(input.substring(pos, m.range.first))
            pos = m.range.last + 1
            val d = if (closing) -1 else 1
            when (name) {
                "b" -> b = maxOf(0, b + d)
                "i" -> i = maxOf(0, i + d)
                "u" -> u = maxOf(0, u + d)
                "s" -> s = maxOf(0, s + d)
                "url" -> if (closing) {
                    link = null
                } else {
                    val target = m.groupValues[3].trim().ifEmpty {
                        // [URL]address[/URL]: the address is the text up to the closing tag
                        val end = input.indexOf("[/url]", pos, ignoreCase = true)
                        if (end >= 0) input.substring(pos, end).trim() else ""
                    }
                    link = target.takeIf { it.startsWith("http://", true) || it.startsWith("https://", true) }
                }
            }
        }
        emit(input.substring(pos))
        return merge(out)
    }

    /** Plain text with WebTS's marks: **bold**, *italic*, ++underline++, ~~strike~~. */
    private fun addMarked(out: MutableList<Span>, text: String, b: Boolean, i: Boolean, u: Boolean, s: Boolean) {
        if (text.isEmpty()) return
        var rest = text
        while (rest.isNotEmpty()) {
            var best: Triple<Int, String, String>? = null // start, mark, style
            for ((mark, style) in MARKS) {
                val start = rest.indexOf(mark)
                if (start < 0) continue
                val end = rest.indexOf(mark, start + mark.length)
                if (end <= start + mark.length) continue
                if (best == null || start < best.first) best = Triple(start, mark, style)
            }
            if (best == null) {
                out.add(Span(rest, b, i, u, s))
                return
            }
            val (start, mark, style) = best
            val end = rest.indexOf(mark, start + mark.length)
            if (start > 0) out.add(Span(rest.substring(0, start), b, i, u, s))
            addMarked(
                out, rest.substring(start + mark.length, end),
                b || style == "b", i || style == "i", u || style == "u", s || style == "s",
            )
            rest = rest.substring(end + mark.length)
        }
    }

    private fun merge(spans: List<Span>): List<Span> {
        val out = ArrayList<Span>()
        for (sp in spans) {
            val last = out.lastOrNull()
            if (last != null && last.copy(text = "") == sp.copy(text = "")) out[out.size - 1] = last.copy(text = last.text + sp.text)
            else out.add(sp)
        }
        return out
    }

    /** The text without any tags (for notifications and the like). */
    fun plain(input: String): String = parse(input).joinToString("") { it.text }
}
