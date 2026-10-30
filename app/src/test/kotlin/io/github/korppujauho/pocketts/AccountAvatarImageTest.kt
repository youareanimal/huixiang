package io.github.korppujauho.pocketts

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.nio.ByteBuffer

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class AccountAvatarImageTest {
    @Test fun pickerImageIsReencodedAsBoundedPngWithoutChangingTheSource() {
        val context = RuntimeEnvironment.getApplication()
        val source = File(context.cacheDir, "avatar-fixture.jpg")
        val bitmap = Bitmap.createBitmap(640, 480, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.MAGENTA)
        source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        bitmap.recycle()
        val before = source.readBytes()
        try {
            val result = ServerFiles.prepareAccountAvatar(context, Uri.fromFile(source))
            assertArrayEquals(byteArrayOf(0x89.toByte(), 80, 78, 71, 13, 10, 26, 10), result.copyOfRange(0, 8))
            val dimensions = ByteBuffer.wrap(result, 16, 8)
            assertEquals(320, dimensions.int); assertEquals(240, dimensions.int)
            assertTrue(result.size <= 524288)
            assertArrayEquals(before, source.readBytes())
        } finally { source.delete() }
    }
}
