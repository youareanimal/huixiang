package io.github.korppujauho.pocketts

import io.github.korppujauho.pocketts.proto.Json
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.MessageDigest

class AppUpdateTest {
    private val base = BuildConfig.UPDATE_FEED_URL.substringBeforeLast('/')
    private fun feed(code: Any = 12, pkg: String = BuildConfig.APPLICATION_ID, hash: String = "a".repeat(64),
        url: String = "$base/release-arm64.apk", size: Long = 10) = Json.write(mapOf(
        "schemaVersion" to 1, "packageName" to pkg, "minSdk" to 29, "versionCode" to code,
        "versionName" to "0.4.3-preview", "notes" to "修复语音问题",
        "artifacts" to mapOf("arm64-v8a" to mapOf("url" to url, "bytes" to size, "sha256" to hash),
            "armeabi-v7a" to mapOf("url" to "$base/release-v7.apk", "bytes" to 7, "sha256" to "b".repeat(64)))))
    private fun parse(text: String, abis: List<String> = listOf("arm64-v8a", "armeabi-v7a")) =
        UpdateFeed.parse(text, abis = abis, sdk = 36)

    @Test fun architectureSelectionFollowsTheDevicesPreferredCompatibleAbi() {
        assertEquals("arm64-v8a", parse(feed()).artifact.abi)
        assertEquals("armeabi-v7a", parse(feed(), listOf("armeabi-v7a")).artifact.abi)
        assertThrows(IllegalStateException::class.java) { parse(feed(), listOf("x86_64")) }
    }
    @Test fun wrongAppMalformedVersionsAndUnboundedMetadataAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { parse(feed(pkg = "other.app")) }
        assertThrows(IllegalArgumentException::class.java) { parse(feed(code = 12.5)) }
        assertThrows(IllegalArgumentException::class.java) { parse(feed(size = UpdateFeed.MAX_APK + 1)) }
        assertThrows(IllegalArgumentException::class.java) { parse(feed(hash = "bad")) }
        assertThrows(IllegalArgumentException::class.java) { parse(" ".repeat(65537)) }
    }
    @Test fun downloadLocationsStayOnTheHttpsReleaseHost() {
        assertTrue(UpdateFeed.allowedUrl("$base/release.apk"))
        for (url in listOf("http://" + java.net.URI(base).host + "/release.apk", "https://attacker.example/release.apk",
            "$base/../secret.apk", "$base/%2e%2e/secret.apk", "$base/release.apk#x", "$base/release.apk?token=x",
            "https://secret@" + java.net.URI(base).host + java.net.URI(base).path + "/release.apk"))
            assertFalse(url, UpdateFeed.allowedUrl(url))
    }
    @Test fun completeDownloadsHaveTheExpectedBytesAndDigest() {
        val dir = java.nio.file.Files.createTempDirectory("huixiang-update").toFile()
        val part = File(dir, "download.part")
        val data = "synthetic-apk".toByteArray()
        val hash = MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it.toInt() and 255) }
        try {
            var progress = 0L
            UpdateFeed.copy(data.inputStream(), part, UpdateFeed.Artifact("arm64-v8a", "$base/a.apk", data.size.toLong(), hash)) { progress = it }
            assertEquals(data.size.toLong(), progress); assertArrayEquals(data, part.readBytes())
        } finally { part.delete(); dir.delete() }
    }
    @Test fun shortOversizedAndModifiedDownloadsDoNotLeaveAnInstallableFile() {
        val dir = java.nio.file.Files.createTempDirectory("huixiang-update").toFile(); val part = File(dir, "download.part")
        try {
            for (bytes in listOf(ByteArray(3), ByteArray(11), ByteArray(10))) {
                assertThrows(IllegalArgumentException::class.java) {
                    UpdateFeed.copy(bytes.inputStream(), part, parse(feed()).artifact) {}
                }
                assertFalse(part.exists())
            }
        } finally { part.delete(); dir.delete() }
    }
}
