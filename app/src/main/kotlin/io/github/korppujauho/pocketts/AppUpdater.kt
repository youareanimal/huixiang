package io.github.korppujauho.pocketts

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import io.github.korppujauho.pocketts.proto.Json
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicReference

/** Public release metadata only. No TeamSpeak or cloud-drive login credentials are used. */
object UpdateFeed {
    enum class Source(val label: String, val feedUrl: String) {
        PI("PI 直连", BuildConfig.UPDATE_FEED_URL),
        CLOUDFLARE("Cloudflare", BuildConfig.UPDATE_FALLBACK_FEED_URL),
    }
    data class Artifact(val abi: String, val url: String, val bytes: Long, val sha256: String)
    data class Release(val code: Long, val name: String, val notes: String, val artifact: Artifact,
        val feedUrl: String = BuildConfig.UPDATE_FEED_URL)
    fun sameArtifact(a: Release, b: Release) = a.code == b.code && a.artifact.abi == b.artifact.abi &&
        a.artifact.bytes == b.artifact.bytes && a.artifact.sha256 == b.artifact.sha256
    const val MAX_APK = 100L * 1024 * 1024
    fun allowedUrl(value: String, feed: String = BuildConfig.UPDATE_FEED_URL): Boolean = try {
        val u = URI(value); val base = URI(feed)
        u.scheme == "https" && u.host == base.host && u.port == base.port && u.userInfo == null && u.fragment == null &&
            u.query == null && u.path.startsWith(base.path.substringBeforeLast('/') + "/") &&
            u.path.split('/').none { it == "." || it == ".." || '\\' in it }
    } catch (_: Exception) { false }

    fun parse(text: String, packageName: String = BuildConfig.APPLICATION_ID, abis: List<String> = Build.SUPPORTED_ABIS.toList(),
        sdk: Int = Build.VERSION.SDK_INT, feed: String = BuildConfig.UPDATE_FEED_URL): Release {
        require(text.length <= 65536) { "更新信息过大" }
        val root = Json.parse(text) as? Map<*, *> ?: error("更新信息格式错误")
        fun number(value: Any?): Long = (value as? Number)?.let {
            val n = it.toLong(); require(n.toDouble() == it.toDouble()); n
        } ?: error("更新信息缺少版本字段")
        require(number(root["schemaVersion"]) == 1L && root["packageName"] == packageName) { "更新信息与当前应用不匹配" }
        require(number(root["minSdk"]) in 29..sdk.toLong()) { "新版需要更高的 Android 版本" }
        val code = number(root["versionCode"]); require(code in 1..Int.MAX_VALUE.toLong())
        val name = root["versionName"] as? String ?: error("缺少版本名称")
        require(name.isNotBlank() && name.length <= 80)
        val notes = (root["notes"] as? String).orEmpty().take(4000)
        val artifacts = root["artifacts"] as? Map<*, *> ?: error("缺少安装包信息")
        val abi = abis.firstOrNull { it in artifacts } ?: "universal".takeIf {
            abis.any { a -> a == "arm64-v8a" || a == "armeabi-v7a" } && it in artifacts
        } ?: error("此设备暂无兼容的安装包")
        val row = artifacts[abi] as? Map<*, *> ?: error("安装包信息错误")
        val url = row["url"] as? String ?: error("缺少下载地址")
        val size = number(row["bytes"])
        val hash = (row["sha256"] as? String).orEmpty().lowercase()
        require(allowedUrl(url, feed) && size in 1..MAX_APK && hash.matches(Regex("[0-9a-f]{64}"))) { "安装包信息无效" }
        return Release(code, name, notes, Artifact(abi, url, size, hash), feed)
    }
    fun digest(file: File): String = MessageDigest.getInstance("SHA-256").let { md ->
        file.inputStream().use { input -> val buffer = ByteArray(65536); while (true) {
            val n = input.read(buffer); if (n < 0) break; md.update(buffer, 0, n)
        } }; md.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }
    /** Short/oversized/altered responses never leave a completed APK behind. */
    fun copy(input: InputStream, part: File, artifact: Artifact, progress: (Long) -> Unit) {
        var count = 0L
        try {
            part.outputStream().use { out -> val buffer = ByteArray(65536); while (true) {
                val n = input.read(buffer); if (n < 0) break
                count += n; require(count <= artifact.bytes && count <= MAX_APK) { "安装包大小不匹配" }
                out.write(buffer, 0, n); progress(count)
            } }
            require(count == artifact.bytes && digest(part) == artifact.sha256) { "安装包下载不完整，请重试" }
        } catch (e: Exception) { part.delete(); throw e }
    }
}

class AppUpdater(context: Context, dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val openConnection: (String) -> HttpURLConnection = { URI(it).toURL().openConnection() as HttpURLConnection }) {
    data class State(val release: UpdateFeed.Release? = null, val checking: Boolean = false, val checked: Boolean = false,
        val downloading: Boolean = false, val received: Long = 0, val ready: Boolean = false,
        val error: String? = null, val automatic: Boolean = true, val dismissed: Long = 0,
        val source: UpdateFeed.Source = UpdateFeed.Source.PI, val activeSource: UpdateFeed.Source? = null)
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("huixiang-updates", Context.MODE_PRIVATE)
    private val directory = File(app.cacheDir, "updates")
    private val cachedFeed get() = File(directory, "latest.json")
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val mutex = Mutex()
    private val connection = AtomicReference<HttpURLConnection?>()
    private var job: Job? = null
    private val mutable = MutableStateFlow(State(automatic = prefs.getBoolean("automatic", true), dismissed = prefs.getLong("dismissed", 0),
        source = UpdateFeed.Source.entries.firstOrNull { it.name == prefs.getString("source", "PI") } ?: UpdateFeed.Source.PI))
    val state = mutable.asStateFlow()
    private fun apk(release: UpdateFeed.Release) = File(directory, "${release.code}-${release.artifact.abi}.apk")

    init { run {
        if (cachedFeed.isFile) {
            val restored = UpdateFeed.Source.entries.firstNotNullOfOrNull { source ->
                try { UpdateFeed.parse(cachedFeed.readText(), feed = source.feedUrl) } catch (_: Exception) { null }
            }
            if (restored == null) cachedFeed.delete() else {
                offer(restored)
                mutable.update { it.copy(activeSource = UpdateFeed.Source.entries.first { s -> s.feedUrl == restored.feedUrl }) }
            }
        }
    } }

    fun start() {
        if (BuildConfig.BUILD_TYPE == "play" || BuildConfig.DEBUG || !mutable.value.automatic) return
        val now = System.currentTimeMillis()
        if (now - maxOf(prefs.getLong("checked", 0), prefs.getLong("automatic-attempt", 0)) >= 24L * 3600 * 1000) {
            prefs.edit().putLong("automatic-attempt", now).apply()
            val restoring = job
            scope.launch { restoring?.join(); check() }
        }
    }
    fun automatic(value: Boolean) { prefs.edit().putBoolean("automatic", value).apply(); mutable.update { it.copy(automatic = value) } }
    fun source(value: UpdateFeed.Source) {
        if (job?.isActive == true || value == mutable.value.source) return
        prefs.edit().putString("source", value.name).apply()
        mutable.update { it.copy(source = value, activeSource = null, release = null, checked = false, ready = false, error = null) }
    }
    fun dismiss() { val code = mutable.value.release?.code ?: return
        prefs.edit().putLong("dismissed", code).apply(); mutable.update { it.copy(dismissed = code) }
    }
    fun check() = run {
        mutable.update { it.copy(checking = true, error = null) }
        var last: Exception? = null
        for (source in orderedSources(mutable.value.source)) {
            try {
                mutable.update { it.copy(activeSource = source) }
                val (text, release) = readRelease(source)
                directory.mkdirs()
                val temp = File(directory, "latest.tmp"); temp.writeText(text)
                check(temp.renameTo(cachedFeed)) { "无法保存更新信息" }
                offer(release)
                prefs.edit().putLong("checked", System.currentTimeMillis()).apply()
                return@run
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { last = e }
        }
        throw last ?: UpdateProblem("更新源暂时不可用。")
    }
    private fun orderedSources(first: UpdateFeed.Source) = listOf(first) + UpdateFeed.Source.entries.filter { it != first }
    private suspend fun readRelease(source: UpdateFeed.Source): Pair<String, UpdateFeed.Release> {
        val text = fetch(source.feedUrl, source.feedUrl) { c -> c.inputStream.use { input ->
            val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(4096)
            while (true) { val n = input.read(buffer); if (n < 0) break
                require(out.size() + n <= 65536) { "更新信息过大" }; out.write(buffer, 0, n)
            }; out.toString(Charsets.UTF_8.name())
        } }
        return text to UpdateFeed.parse(text, feed = source.feedUrl)
    }
    private fun offer(release: UpdateFeed.Release) {
        if (release.code <= BuildConfig.VERSION_CODE) {
            mutable.update { it.copy(release = null, checked = true, ready = false, error = null) }; return
        }
        val file = apk(release)
        val ready = if (file.isFile) try { validate(file, release); true } catch (_: Exception) { file.delete(); false } else false
        mutable.update { it.copy(release = release, checked = true, ready = ready, error = null) }
    }
    fun download() = run {
        val release = mutable.value.release ?: return@run
        mutable.update { it.copy(downloading = true, received = 0, ready = false, error = null) }
        directory.mkdirs()
        val part = File(directory, "download.part.apk")
        try {
            val coroutine = currentCoroutineContext()
            var completed = false
            var last: Exception? = null
            val first = UpdateFeed.Source.entries.firstOrNull { it.feedUrl == release.feedUrl }
                ?: throw UpdateProblem("更新源无效，请重新检查更新。")
            for (source in orderedSources(first)) {
                try {
                    mutable.update { it.copy(activeSource = source, received = 0) }
                    val candidate = if (source == first) release else readRelease(source).second
                    if (!UpdateFeed.sameArtifact(release, candidate)) throw UpdateProblem("备用源的版本或安装包不同，请重新检查更新。")
                    fetch(candidate.artifact.url, source.feedUrl) { c -> c.inputStream.use { input ->
                        UpdateFeed.copy(input, part, release.artifact) { received ->
                            coroutine.ensureActive()
                            mutable.update { it.copy(received = received) }
                        }
                    } }
                    validate(part, release)
                    completed = true; break
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { part.delete(); last = e }
            }
            if (!completed) throw last ?: UpdateProblem("安装包下载失败，请重试。")
            val target = apk(release)
            check(part.renameTo(target)) { "无法保存安装包" }
            directory.listFiles()?.filter { it.extension == "apk" && it != target }?.forEach { it.delete() }
            mutable.update { it.copy(ready = true) }
        } finally { part.delete() }
    }
    fun cancel() { job?.cancel(); connection.getAndSet(null)?.disconnect() }
    private fun run(action: suspend () -> Unit) {
        if (job?.isActive == true) return
        job = scope.launch {
            if (!mutex.tryLock()) return@launch
            try { action() }
            catch (e: CancellationException) { throw e }
            catch (e: UpdateProblem) { mutable.update { it.copy(error = e.message) } }
            catch (_: IllegalArgumentException) { mutable.update { it.copy(error = "更新信息或安装包校验未通过，请稍后重试。") } }
            catch (_: Exception) { mutable.update { it.copy(error = "更新暂未完成，请检查网络后重试。") } }
            finally { mutable.update { it.copy(checking = false, downloading = false) }; mutex.unlock() }
        }
    }
    private suspend fun <T> fetch(url: String, feed: String, action: suspend (HttpURLConnection) -> T): T {
        var address = url
        repeat(4) {
            if (!UpdateFeed.allowedUrl(address, feed)) throw UpdateProblem("更新地址无效。")
            val c = openConnection(address)
            connection.set(c)
            c.instanceFollowRedirects = false; c.connectTimeout = 10000; c.readTimeout = 20000
            c.setRequestProperty("User-Agent", "HuiXiang/${BuildConfig.VERSION_NAME}")
            c.setRequestProperty("Cache-Control", "no-cache")
            try {
                currentCoroutineContext().ensureActive()
                val code = c.responseCode
                if (code in listOf(301, 302, 303, 307, 308)) {
                    address = URI(address).resolve(c.getHeaderField("Location") ?: throw UpdateProblem("下载地址跳转失败。")).toString()
                } else {
                    if (code != 200) throw UpdateProblem("更新服务暂不可用（HTTP $code），请稍后重试。")
                    return action(c)
                }
            } finally { connection.compareAndSet(c, null); c.disconnect() }
        }
        throw UpdateProblem("下载地址跳转过多。")
    }
    /** The system installer makes the final installation decision; this catches the wrong app/key earlier. */
    private fun validate(file: File, release: UpdateFeed.Release) {
        if (file.length() != release.artifact.bytes || UpdateFeed.digest(file) != release.artifact.sha256)
            throw UpdateProblem("安装包校验失败，请重新下载。")
        val pm = app.packageManager
        val archive = pm.getPackageArchiveInfo(file.path, PackageManager.GET_SIGNING_CERTIFICATES)
            ?: throw UpdateProblem("无法读取安装包，请重新下载。")
        val installed = pm.getPackageInfo(app.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        fun signers(info: android.content.pm.PackageInfo) = info.signingInfo?.apkContentsSigners?.map {
            MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).toList()
        }?.toSet().orEmpty()
        if (archive.packageName != app.packageName || archive.longVersionCode != release.code || release.code <= installed.longVersionCode)
            throw UpdateProblem("安装包与当前应用或更新版本不匹配。")
        if (signers(installed).isEmpty() || signers(archive) != signers(installed))
            throw UpdateProblem("安装包签名不匹配，已停止安装。")
    }
    /** Called from the visible Activity only after the person taps Install. */
    fun install(activity: android.app.Activity) {
        val release = mutable.value.release ?: return
        if (!mutable.value.ready) return
        run {
            mutable.update { it.copy(error = null) }
            try { validate(apk(release), release) } catch (e: UpdateProblem) {
                apk(release).delete(); mutable.update { it.copy(ready = false) }; throw e
            }
            withContext(Dispatchers.Main) {
                if (!activity.packageManager.canRequestPackageInstalls()) {
                    activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${app.packageName}")))
                    mutable.update { it.copy(error = "允许回响安装应用后，返回这里点击“安装新版”。") }
                } else {
                    val uri = FileProvider.getUriForFile(app, app.packageName + ".files", apk(release))
                    activity.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                }
            }
        }
    }
    fun close() { cancel(); scope.cancel() }
    private class UpdateProblem(message: String) : Exception(message)
}
