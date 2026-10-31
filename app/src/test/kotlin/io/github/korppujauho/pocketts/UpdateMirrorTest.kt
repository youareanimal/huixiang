package io.github.korppujauho.pocketts

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.content.pm.SigningInfo
import android.os.Build
import io.github.korppujauho.pocketts.proto.Json
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class UpdateMirrorTest {
    private val context get() = RuntimeEnvironment.getApplication() as Context
    private val pi = UpdateFeed.Source.PI
    private val cf = UpdateFeed.Source.CLOUDFLARE
    private val code = BuildConfig.VERSION_CODE.toLong() + 1
    private val bytes = "synthetic signed APK".toByteArray()
    private val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun apk(source: UpdateFeed.Source) = source.feedUrl.substringBeforeLast('/') + "/fixture.apk"
    private fun feed(source: UpdateFeed.Source, digest: String = hash, version: Long = code) = Json.write(mapOf(
        "schemaVersion" to 1, "packageName" to context.packageName, "versionCode" to version,
        "versionName" to "fixture", "minSdk" to 29, "artifacts" to mapOf("arm64-v8a" to mapOf(
            "url" to apk(source), "bytes" to bytes.size, "sha256" to digest)))).toByteArray()
    private data class Reply(val body: ByteArray = byteArrayOf(), val status: Int = 200, val location: String? = null)
    private class Server(val reply: (String) -> Reply) {
        val requests = mutableListOf<String>()
        fun open(address: String): HttpURLConnection {
            requests += address
            val value = reply(address)
            return object : HttpURLConnection(URL(address)) {
                override fun connect() {}
                override fun disconnect() {}
                override fun usingProxy() = false
                override fun getResponseCode() = value.status
                override fun getInputStream() = ByteArrayInputStream(value.body)
                override fun getHeaderField(name: String?) = if (name == "Location") value.location else null
            }
        }
    }
    private fun updater(server: Server): AppUpdater {
        ReflectionHelpers.setStaticField(Build::class.java, "SUPPORTED_ABIS", arrayOf("arm64-v8a"))
        context.getSharedPreferences("huixiang-updates", Context.MODE_PRIVATE).edit().clear().commit()
        return AppUpdater(context, Dispatchers.Unconfined, server::open)
    }
    private fun prepareSignatures() {
        val pm = context.packageManager
        fun signer() = SigningInfo().also { shadowOf(it).setSignatures(arrayOf(Signature("1234"))) }
        pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES).also {
            it.signingInfo = signer(); shadowOf(pm).installPackage(it)
        }
        val archive = PackageInfo().also { it.packageName = context.packageName; it.longVersionCode = code; it.signingInfo = signer() }
        val dir = File(context.cacheDir, "updates")
        shadowOf(pm).setPackageArchiveInfo(File(dir, "download.part.apk").path, archive)
        shadowOf(pm).setPackageArchiveInfo(File(dir, "$code-arm64-v8a.apk").path, archive)
    }

    @Test fun defaultPiAndAnExplicitCloudflarePreferenceUseTheirOwnFeedOrigins() {
        val server = Server { url -> when(url) { pi.feedUrl -> Reply(feed(pi)); cf.feedUrl -> Reply(feed(cf)); else -> error("Unexpected request") } }
        val u = updater(server)
        try {
            u.check(); assertEquals(listOf(pi.feedUrl), server.requests)
            assertEquals(pi, u.state.value.activeSource); assertEquals(pi.feedUrl, u.state.value.release!!.feedUrl)
            u.source(cf); assertNull(u.state.value.release); u.check()
            assertEquals(cf.feedUrl, server.requests.last()); assertEquals(cf, u.state.value.activeSource)
        } finally { u.close() }
        val restored = AppUpdater(context, Dispatchers.Unconfined, server::open)
        try { assertEquals(cf, restored.state.value.source); assertEquals(cf, restored.state.value.activeSource) }
        finally { restored.close() }
    }

    @Test fun unavailablePiFallsBackAndACrossOriginRedirectIsNeverFollowed() {
        val server = Server { url -> when(url) {
            pi.feedUrl -> Reply(status = 302, location = "https://attacker.example/feed.json")
            cf.feedUrl -> Reply(feed(cf))
            else -> error("Untrusted redirect followed")
        } }
        val u = updater(server)
        try {
            u.check(); assertEquals(listOf(pi.feedUrl, cf.feedUrl), server.requests)
            assertEquals(cf, u.state.value.activeSource); assertNull(u.state.value.error)
        } finally { u.close() }
    }

    @Test fun aValidPiDownloadDoesNotContactTheFallbackAndKeepsSignatureValidation() {
        prepareSignatures()
        val server = Server { url -> when(url) { pi.feedUrl -> Reply(feed(pi)); apk(pi) -> Reply(bytes); else -> error("Unnecessary fallback") } }
        val u = updater(server)
        try {
            u.check(); u.download()
            assertTrue(u.state.value.ready); assertEquals(pi, u.state.value.activeSource)
            assertArrayEquals(bytes, File(context.cacheDir, "updates/$code-arm64-v8a.apk").readBytes())
            assertEquals(listOf(pi.feedUrl, apk(pi)), server.requests)
        } finally { u.close() }
    }

    @Test fun failedPiDownloadOnlySwitchesToAnIdenticalVersionAbiSizeAndHash() {
        prepareSignatures()
        val server = Server { url -> when(url) {
            pi.feedUrl -> Reply(feed(pi)); apk(pi) -> throw IOException("fixture connection failure")
            cf.feedUrl -> Reply(feed(cf)); apk(cf) -> Reply(bytes)
            else -> error("Unexpected request")
        } }
        val u = updater(server)
        try {
            u.check(); u.download(); assertTrue(u.state.value.ready)
            assertEquals(cf, u.state.value.activeSource)
            assertEquals(listOf(pi.feedUrl, apk(pi), cf.feedUrl, apk(cf)), server.requests)
        } finally { u.close() }
    }

    @Test fun aDifferentFallbackPackageIsBlockedBeforeDownloadingIt() {
        val server = Server { url -> when(url) {
            pi.feedUrl -> Reply(feed(pi)); apk(pi) -> throw IOException("fixture connection failure")
            cf.feedUrl -> Reply(feed(cf, digest = "b".repeat(64)))
            else -> error("Mismatched fallback must not download")
        } }
        val u = updater(server)
        try {
            u.check(); u.download(); assertFalse(u.state.value.ready)
            assertTrue(u.state.value.error!!.contains("备用源"))
            assertFalse(File(context.cacheDir, "updates/download.part.apk").exists())
            assertFalse(apk(cf) in server.requests)
        } finally { u.close() }
    }

    @Test fun piUrlsCannotEscapeTheConfiguredPortAndDirectory() {
        for (bad in listOf("https://updates.example.invalid/huixiang/a.apk", "http://updates.example.invalid:8443/huixiang/a.apk",
            "https://updates.example.invalid:8443/private/a.apk", "https://updates.example.invalid:8443/huixiang/%2e%2e/a.apk"))
            assertFalse(bad, UpdateFeed.allowedUrl(bad, pi.feedUrl))
        assertFalse(UpdateFeed.allowedUrl(apk(cf), pi.feedUrl))
        assertTrue(UpdateFeed.allowedUrl(apk(cf), cf.feedUrl))
    }
}
