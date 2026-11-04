package site.huixiang.web

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 频道历史的分页、频道隔离、过期修剪和重启后重读。 */
class ChannelHistoryTest {

    /** 消息时间用「刚才」附近的值：太老的会被 90 天规则当场修剪掉。基准只取一次，翻页才能对上。 */
    private val base = System.currentTimeMillis() - 60_000L

    private fun at(offsetMs: Long) = base + offsetMs

    private fun line(time: Long, text: String, from: Int = 1) =
        ChannelHistory.Line(time, from, "user$from", "uid$from", text)

    @Test
    fun `最近一屏从新往前取，带上更早的标记`() {
        val h = ChannelHistory(null)
        h.append("ts.example", 7, line(at(0), "a"))
        h.append("ts.example", 7, line(at(1_000), "b"))
        h.append("ts.example", 7, line(at(2_000), "c"))

        val last = h.page("ts.example", 7, 0, 2)
        assertEquals(listOf("b", "c"), last.lines.map { it.text })
        assertTrue(last.more)

        // before 是上一页最早一条的时间，不含它本身
        val older = h.page("ts.example", 7, at(1_000), 2)
        assertEquals(listOf("a"), older.lines.map { it.text })
        assertFalse(older.more)
    }

    @Test
    fun `频道之间互不串，别的服务器也不串`() {
        val h = ChannelHistory(null)
        h.append("ts.example", 7, line(at(0), "in seven"))
        h.append("ts.example", 8, line(at(100), "in eight"))
        h.append("other.example", 7, line(at(200), "other server"))

        assertEquals(listOf("in seven"), h.page("ts.example", 7, 0, 10).lines.map { it.text })
        assertEquals(listOf("in eight"), h.page("ts.example", 8, 0, 10).lines.map { it.text })
        assertTrue(h.page("ts.example", 9, 0, 10).lines.isEmpty())
    }

    @Test
    fun `超过 90 天的消息写进来就被丢掉`() {
        val h = ChannelHistory(null)
        val old = System.currentTimeMillis() - 91L * 86_400_000L
        h.append("ts.example", 7, line(old, "too old"))
        h.append("ts.example", 7, line(at(0), "recent"))

        assertEquals(listOf("recent"), h.page("ts.example", 7, 0, 10).lines.map { it.text })
    }

    @Test
    fun `落盘之后重新打开还能读到`() {
        val dir = Files.createTempDirectory("hx-chat-history").toFile()
        try {
            val first = ChannelHistory(dir)
            first.append("ts.example", 7, line(at(0), "keep me"))

            // 写盘在单独的线程上，等它落下来再重读
            val deadline = System.currentTimeMillis() + 5_000
            while (System.currentTimeMillis() < deadline) {
                val written = dir.listFiles().orEmpty().any { f -> f.readLines().any { it.isNotBlank() } }
                if (written) break
                Thread.sleep(50)
            }

            val second = ChannelHistory(dir)
            assertEquals(listOf("keep me"), second.page("ts.example", 7, 0, 10).lines.map { it.text })
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `clear 只清一台服务器`() {
        val h = ChannelHistory(null)
        h.append("ts.example", 7, line(at(0), "gone"))
        h.append("other.example", 7, line(at(100), "kept"))

        h.clear("ts.example")

        assertTrue(h.page("ts.example", 7, 0, 10).lines.isEmpty())
        assertEquals(listOf("kept"), h.page("other.example", 7, 0, 10).lines.map { it.text })
    }

    @Test
    fun `空文本和非法频道不记`() {
        val h = ChannelHistory(null)
        h.append("ts.example", 7, line(at(0), ""))
        h.append("ts.example", 0, line(at(0), "no channel"))
        h.append("", 7, line(at(0), "no server"))

        assertTrue(h.page("ts.example", 7, 0, 10).lines.isEmpty())
    }

    @Test
    fun `目录为 null 时只在内存里，不写文件`() {
        val h = ChannelHistory(null)
        h.append("ts.example", 7, line(at(0), "memory only"))
        assertEquals(listOf("memory only"), h.page("ts.example", 7, 0, 10).lines.map { it.text })
    }

    @Test
    fun `落盘目录不存在也能自己建`() {
        val root = Files.createTempDirectory("hx-chat-root").toFile()
        val nested = File(root, "a/b/c")
        try {
            val h = ChannelHistory(nested)
            h.append("ts.example", 7, line(at(0), "deep"))

            val deadline = System.currentTimeMillis() + 5_000
            var files: List<File> = emptyList()
            while (System.currentTimeMillis() < deadline) {
                files = nested.listFiles().orEmpty().filter { it.isFile }
                if (files.isNotEmpty()) break
                Thread.sleep(50)
            }
            assertTrue(files.isNotEmpty(), "应该写出一个 jsonl")
        } finally {
            root.deleteRecursively()
        }
    }
}
