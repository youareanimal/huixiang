package io.github.korppujauho.pocketts

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.gif.AnimatedImageDecoder
import coil3.key.Keyer
import coil3.request.Options
import coil3.svg.SvgDecoder
import io.github.korppujauho.pocketts.proto.Pictures
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.Path.Companion.toOkioPath

/**
 * A file in the server's files, for pictures on screen: an icon, an avatar (several possible names, tried in order),
 * or a picture in a channel's files. [version] (an avatar's flag) makes a changed file a new one.
 */
data class TsFile(
    val server: String,
    val cid: Int,
    val paths: List<String>,
    val maxBytes: Long,
    val version: String = "",
) {
    val key: String get() = "$server|$cid|${paths.first()}|$version"

    companion object {
        fun icon(server: String, id: Long) = Pictures.iconFileName(id)?.let { TsFile(server, 0, listOf(it), Pictures.ICON_MAX_BYTES) }

        fun avatar(server: String, uid: String, avatarName: String, flag: String) =
            Pictures.avatarFileNames(uid, avatarName).takeIf { it.isNotEmpty() && flag.isNotEmpty() }
                ?.let { TsFile(server, 0, it, Pictures.AVATAR_MAX_BYTES, flag) }

        fun picture(server: String, p: Pictures.ChannelPicture) = TsFile(server, p.cid, listOf(p.path), Pictures.PICTURE_MAX_BYTES)
    }
}

/** Gets [TsFile]s for Coil: from the disk cache, else through the connection's file transfer. */
object ServerFiles {
    private lateinit var cacheDir: File
    private val missing = HashMap<String, Long>() // key -> until when it counts as missing
    private val loading = HashMap<String, Any>() // key -> the lock its download is made under

    fun install(context: Context) {
        cacheDir = File(context.cacheDir, "server-files").apply { mkdirs() }
        PictureSizes.load(context.cacheDir)
        SingletonImageLoader.setSafe { ctx: PlatformContext ->
            ImageLoader.Builder(ctx)
                .components {
                    add(Fetch.Factory())
                    add(Keyer<TsFile> { data, _ -> data.key })
                    add(SvgDecoder.Factory())
                    add(AnimatedImageDecoder.Factory())
                }
                .build()
        }
    }

    private fun cacheFile(key: String): File {
        val h = MessageDigest.getInstance("SHA-256").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(cacheDir, h.take(40))
    }

    /**
     * The file, in the cache (blocking). One download per file at a time: the chat and the full-screen picture asking
     * for the same one get the same download.
     */
    fun get(f: TsFile): File {
        val cached = cacheFile(f.key)
        val lock = synchronized(loading) { loading.getOrPut(f.key) { Any() } }
        synchronized(lock) {
            if (cached.exists()) return cached.also { it.setLastModified(System.currentTimeMillis()) }
            synchronized(missing) {
                if ((missing[f.key] ?: 0) > System.currentTimeMillis()) throw IllegalStateException("not on the server")
            }
            var last: Exception? = null
            for (path in f.paths) {
                try {
                    val data = Session.downloadFile(f.server, f.cid, path, f.maxBytes)
                    if (Pictures.type(data, svgAllowed = f.cid == 0) == null) throw IllegalStateException("not a picture")
                    cached.writeBytes(data)
                    trim()
                    return cached
                } catch (e: Exception) {
                    last = e
                }
            }
            synchronized(missing) { missing[f.key] = System.currentTimeMillis() + 10 * 60_000 }
            throw last ?: IllegalStateException("not found")
        }
    }

    /** Keeps the cache under 100 MB, the least recently used out first. */
    private fun trim() {
        val files = cacheDir.listFiles()?.sortedBy { it.lastModified() } ?: return
        var total = files.sumOf { it.length() }
        for (f in files) {
            if (total < 100L * 1024 * 1024) break
            total -= f.length()
            f.delete()
        }
    }

    private class Fetch(private val data: TsFile, private val options: Options) : Fetcher {
        override suspend fun fetch(): FetchResult {
            // From the file, so a big picture is read as it's decoded (and made smaller), not first all into memory
            val (file, head) = withContext(Dispatchers.IO) {
                val file = get(data)
                file to file.inputStream().use { input -> ByteArray(4096).let { it.copyOf(maxOf(0, input.read(it))) } }
            }
            return SourceFetchResult(
                source = ImageSource(file.toOkioPath(), FileSystem.SYSTEM),
                mimeType = Pictures.type(head, svgAllowed = true)?.mime,
                dataSource = DataSource.DISK,
            )
        }

        class Factory : Fetcher.Factory<TsFile> {
            override fun create(data: TsFile, options: Options, imageLoader: ImageLoader) = Fetch(data, options)
        }
    }

    // ====== Pictures going out ======

    class Prepared(val bytes: ByteArray, val type: Pictures.Type)

    /**
     * A picture for the chat: GIFs and WebPs as they are (they may move); photos are turned the right way up, made at
     * most 2560 px, and saved again as JPEG (PNG keeps PNG) - which also leaves out what the camera wrote in them
     * (like where the photo was taken).
     */
    fun prepareChatPicture(context: Context, uri: Uri): Prepared {
        val raw = context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
        val type = Pictures.type(raw)
        if (type == Pictures.Type.GIF || type == Pictures.Type.WEBP) {
            if (raw.size > Pictures.PICTURE_UPLOAD_MAX_BYTES) throw IllegalArgumentException(context.getString(R.string.picture_too_big, Pictures.PICTURE_UPLOAD_MAX_BYTES / (1024 * 1024)))
            return Prepared(raw, type)
        }
        val bitmap = decode(raw, 2560)
        val png = type == Pictures.Type.PNG
        val out = encode(bitmap, if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 88)
        if (out.size > Pictures.PICTURE_UPLOAD_MAX_BYTES) throw IllegalArgumentException(context.getString(R.string.picture_too_big, Pictures.PICTURE_UPLOAD_MAX_BYTES / (1024 * 1024)))
        return Prepared(out, if (png) Pictures.Type.PNG else Pictures.Type.JPG)
    }

    /**
     * A profile picture as JPEG, best first: inside 320 × 320, then smaller ones for a server whose limit (the max
     * avatar file size, 200 KB as it comes) refuses it. A 320 px photo is usually far under that.
     */
    fun prepareAvatar(context: Context, uri: Uri): List<ByteArray> {
        val raw = context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
        return listOf(Pictures.AVATAR_MAX_SIDE to 90, 240 to 80, 160 to 75, 96 to 70).map { (side, quality) ->
            encode(decode(raw, side), Bitmap.CompressFormat.JPEG, quality)
        }
    }

    /** Re-encode a system-picker image as bounded sRGB PNG, removing EXIF and other source metadata. */
    fun prepareAccountAvatar(context: Context, uri: Uri): ByteArray {
        val raw = context.contentResolver.openInputStream(uri)?.use { input ->
            val out = ByteArrayOutputStream(); val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer); if (count < 0) break
                if (out.size() + count > 20 * 1024 * 1024) throw io.github.korppujauho.pocketts.proto.CloudFailure("AVATAR_IMAGE")
                out.write(buffer, 0, count)
            }
            out.toByteArray()
        } ?: throw io.github.korppujauho.pocketts.proto.CloudFailure("AVATAR_IMAGE")
        return try {
            val bitmap = decode(raw, 320)
            try { encode(bitmap, Bitmap.CompressFormat.PNG, 100) }
            finally { bitmap.recycle() }
        } finally { raw.fill(0) }
    }

    private fun decode(raw: ByteArray, maxSide: Int): Bitmap =
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(java.nio.ByteBuffer.wrap(raw))) { decoder, info, _ ->
            val w = info.size.width
            val h = info.size.height
            val scale = minOf(1.0, maxSide.toDouble() / maxOf(w, h))
            if (scale < 1.0) decoder.setTargetSize((w * scale).toInt().coerceAtLeast(1), (h * scale).toInt().coerceAtLeast(1))
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            // Plain sRGB, as browsers and the TeamSpeak app make them: a phone photo's wide colours (Display P3) would
            // otherwise go along as an embedded colour profile, which the TeamSpeak app didn't show as a profile picture
            decoder.setTargetColorSpace(android.graphics.ColorSpace.get(android.graphics.ColorSpace.Named.SRGB))
        }

    private fun encode(b: Bitmap, format: Bitmap.CompressFormat, quality: Int): ByteArray =
        ByteArrayOutputStream().also { b.compress(format, quality, it) }.toByteArray()
}
