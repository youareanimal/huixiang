package io.github.korppujauho.pocketts

import java.io.File

/**
 * The sizes of the chat's pictures as they were shown, so a picture's place in the chat is kept for it before it has
 * loaded (instead of the chat jumping when it comes). Kept in a small file next to the pictures.
 */
object PictureSizes {
    class Size(val width: Int, val height: Int)

    private const val KEEP = 1000
    private var file: File? = null
    private val sizes = LinkedHashMap<String, Size>(16, 0.75f, true) // least recently used first

    fun load(dir: File) {
        val f = File(dir, "picture-sizes.txt")
        synchronized(sizes) {
            file = f
            sizes.clear()
            val lines = runCatching { f.readLines() }.getOrDefault(emptyList())
            for (line in lines) {
                val parts = line.split('\t', limit = 3)
                val w = parts.getOrNull(0)?.toIntOrNull() ?: continue
                val h = parts.getOrNull(1)?.toIntOrNull() ?: continue
                val key = parts.getOrNull(2) ?: continue
                if (w > 0 && h > 0) sizes[key] = Size(w, h)
            }
            trim()
            // Written again only when the lines (added one by one) have grown well past what is kept
            if (lines.size > KEEP * 2) save()
        }
    }

    fun get(key: String): Size? = synchronized(sizes) { sizes[key] }

    fun put(key: String, width: Int, height: Int) {
        if (width <= 0 || height <= 0 || '\n' in key) return
        synchronized(sizes) {
            val old = sizes[key]
            if (old != null && old.width == width && old.height == height) return
            sizes[key] = Size(width, height)
            trim()
            runCatching { file?.appendText("$width\t$height\t$key\n") }
        }
    }

    private fun trim() {
        val extra = sizes.size - KEEP
        if (extra > 0) sizes.keys.take(extra).forEach { sizes.remove(it) }
    }

    private fun save() {
        runCatching { file?.writeText(sizes.entries.joinToString("") { "${it.value.width}\t${it.value.height}\t${it.key}\n" }) }
    }
}
