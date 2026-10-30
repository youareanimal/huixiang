package io.github.korppujauho.pocketts

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import coil3.SingletonImageLoader
import io.github.korppujauho.pocketts.proto.Pictures
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** A chat picture out of the app: into the phone's gallery (Pictures/PocketTS), or to another app. */
object SavedPictures {
    const val FOLDER = "PocketTS"

    class Picture(val bytes: ByteArray, val type: Pictures.Type, val name: String)

    /** The picture itself, as it is on the server or the web (blocking: it may have to download it). */
    fun load(context: Context, model: Any): Picture {
        val (bytes, name) = when (model) {
            is TsFile -> ServerFiles.get(model).readBytes() to model.paths.first().substringAfterLast('/')
            is String -> web(context, model) to Uri.parse(model).lastPathSegment.orEmpty()
            else -> throw IOException("not a picture")
        }
        val type = Pictures.type(bytes) ?: throw IOException("not a picture")
        // The name as it was, with the ending that fits what the picture really is
        val base = name.substringBeforeLast('.').filter { it.isLetterOrDigit() || it in "-_" }.take(60)
            .ifEmpty { "picture-${System.currentTimeMillis()}" }
        return Picture(bytes, type, "$base.${type.ext}")
    }

    /** A picture from a link: the copy the chat already loaded, else downloaded again. */
    private fun web(context: Context, url: String): ByteArray {
        SingletonImageLoader.get(context).diskCache?.openSnapshot(url)?.use { return it.data.toFile().readBytes() }
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000
        c.readTimeout = 20_000
        try {
            if (c.responseCode !in 200..299) throw IOException("the address answered ${c.responseCode}")
            val out = ByteArrayOutputStream()
            c.inputStream.use { input ->
                val buf = ByteArray(16 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    if (out.size() > Pictures.PICTURE_MAX_BYTES) throw IOException("the picture is too big")
                }
            }
            return out.toByteArray()
        } finally {
            c.disconnect()
        }
    }

    /** Into the gallery, under Pictures/PocketTS (no permission needed for the app's own pictures there). */
    fun saveToGallery(context: Context, p: Picture) {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, p.name)
            put(MediaStore.Images.Media.MIME_TYPE, p.type.mime)
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$FOLDER")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
            ?: throw IOException("the gallery didn't take it")
        try {
            resolver.openOutputStream(uri)?.use { it.write(p.bytes) } ?: throw IOException("the gallery didn't take it")
            resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
    }

    /** Android's share menu with the picture (a copy in the app's cache, which the other app may read). */
    fun share(context: Context, p: Picture) {
        val dir = File(context.cacheDir, "shared").apply { mkdirs() }
        // Only the newest shared picture is kept: the one before has been handed over by now
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, p.name).apply { writeBytes(p.bytes) }
        val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = p.type.mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
