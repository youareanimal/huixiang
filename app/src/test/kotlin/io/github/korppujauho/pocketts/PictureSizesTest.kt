package io.github.korppujauho.pocketts

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PictureSizesTest {
    private val dir: File = Files.createTempDirectory("sizes").toFile()

    @Test
    fun keptOverARestart() {
        PictureSizes.load(dir)
        PictureSizes.put("server|5|/PocketTS/a.jpg|", 640, 480)
        PictureSizes.put("https://example.com/b.png", 300, 600)
        PictureSizes.put("server|5|/PocketTS/a.jpg|", 480, 640) // the same picture again, now another shape

        PictureSizes.load(dir)
        assertEquals(480, PictureSizes.get("server|5|/PocketTS/a.jpg|")?.width)
        assertEquals(640, PictureSizes.get("server|5|/PocketTS/a.jpg|")?.height)
        assertEquals(600, PictureSizes.get("https://example.com/b.png")?.height)
        assertNull(PictureSizes.get("unknown"))
    }

    @Test
    fun theOldestGoFirst() {
        PictureSizes.load(dir)
        for (i in 0 until 2500) PictureSizes.put("p$i", 100, 100 + i)
        PictureSizes.load(dir)
        assertNull(PictureSizes.get("p0"))
        assertNull(PictureSizes.get("p1499"))
        assertEquals(2599, PictureSizes.get("p2499")?.height)
        assertEquals(100 + 1500, PictureSizes.get("p1500")?.height)
        // The file was written again with only what is kept
        assertEquals(1000, File(dir, "picture-sizes.txt").readLines().size)
    }

    @Test
    fun brokenLinesAndBadSizesAreLeftOut() {
        File(dir, "picture-sizes.txt").writeText("x\t1\ta\n0\t5\tb\n7\t8\tc\n")
        PictureSizes.load(dir)
        PictureSizes.put("d", -1, 5)
        assertNull(PictureSizes.get("a"))
        assertNull(PictureSizes.get("b"))
        assertNull(PictureSizes.get("d"))
        assertEquals(8, PictureSizes.get("c")?.height)
    }
}
