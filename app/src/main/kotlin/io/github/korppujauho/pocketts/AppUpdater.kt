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
    data class Artifact(val abi: String, val url: String, val bytes: Long, val sha256: String)
    data class Release(val code: Long, val name: String, val notes: String, val artifact: Artifact)
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
        return Release(code, name, notes, Artifact(abi, url, size, hash))
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

class AppUpdater(context: Context) {
    data class State(val release: UpdateFeed.Release? = null, val checking: Boolean = false, val checked: Boolean = false,
        val downloading: Boolean = false, val received: Long = 0, val ready: Boolean = false,
        val error: String? = null, val automatic: Boolean = true, val dismissed: Long = 0)
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("huixiang-updates", Context.MODE_PRIVATE)
    private val directory = File(app.cacheDir, "updates")
    private val cachedFeed get() = File(directory, "latest.json")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val connection = AtomicReference<HttpURLConnection?>()
    private var job: Job? = null
    private val mutable = MutableStateFlow(State(automatic = prefs.getBoolean("automatic", true), dismissed = prefs.getLong("dismissed", 0)))
    val state = mutable.asStateFlow()
    private fun apk(release: UpdateFeed.Release) = File(directory, "${release.code}-${release.artifact.abi}.apk")

    init { run {
        if (cachedFeed.isFile) try { offer(UpdateFeed.parse(cachedFeed.readText())) } catch (_: Exception) { cachedFeed.delete() }
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
    fun dismiss() { val code = mutable.value.release?.code ?: return
        prefs.edit().putLong("dismissed", code).apply(); mutable.update { it.copy(dismissed = code) }
    }
    fun check() = run {
        mutable.update { it.copy(checking = true, error = null) }
        fetch(BuildConfig.UPDATE_FEED_URL) { c ->
            val text = c.inputStream.use { input ->
                val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(4096)
                while (true) { val n = input.read(buffer); if (n < 0) break
                    require(out.size() + n <= 65536) { "更新信息过大" }; out.write(buffer, 0, n)
                }; out.toString(Charsets.UTF_8.name())
            }
            val release = UpdateFeed.parse(text)
            directory.mkdirs()
            val temp = File(directory, "latest.tmp"); temp.writeText(text)
            check(temp.renameTo(cachedFeed)) { "无法保存更新信息" }
            offer(release)
            prefs.edit().putLong("checked", System.currentTimeMillis()).apply()
        }
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
            fetch(release.artifact.url) { c -> c.inputStream.use { input ->
                UpdateFeed.copy(input, part, release.artifact) { received ->
                    coroutine.ensureActive()
                    mutable.update { it.copy(received = received) }
                }
            } }
            validate(part, release)
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
    private suspend fun fetch(url: String, action: suspend (HttpURLConnection) -> Unit) {
        var address = url
        repeat(4) {
            if (!UpdateFeed.allowedUrl(address)) throw UpdateProblem("更新地址无效。")
            val c = URI(address).toURL().openConnection() as HttpURLConnection
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
                    action(c); return
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
