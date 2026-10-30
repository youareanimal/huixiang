package io.github.korppujauho.pocketts

import android.app.Activity
import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.content.pm.SigningInfo
import android.os.Build
import io.github.korppujauho.pocketts.proto.Json
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AppUpdateInstallerTest {
    private val context get() = RuntimeEnvironment.getApplication() as Context
    private fun signer(value: String) = SigningInfo().also { shadowOf(it).setSignatures(arrayOf(Signature(value))) }
    private fun await(condition: () -> Boolean) {
        val limit = System.nanoTime() + 5_000_000_000L
        while (!condition() && System.nanoTime() < limit) Thread.sleep(10)
        assertTrue("Updater did not finish", condition())
    }
    private fun fixture(cert: String = "1234", packageName: String = BuildConfig.APPLICATION_ID): Pair<File, AppUpdater> {
        ReflectionHelpers.setStaticField(Build::class.java, "SUPPORTED_ABIS", arrayOf("arm64-v8a"))
        val directory = File(context.cacheDir, "updates").also { it.mkdirs() }
        val code = BuildConfig.VERSION_CODE.toLong() + 1
        val file = File(directory, "$code-arm64-v8a.apk").also { it.writeText("fixture APK") }
        val pm = context.packageManager
        pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES).also {
            it.signingInfo = signer("1234"); shadowOf(pm).installPackage(it)
        }
        val candidate = PackageInfo().also { it.packageName = packageName; it.longVersionCode = code; it.signingInfo = signer(cert) }
        shadowOf(pm).setPackageArchiveInfo(file.path, candidate)
        File(directory, "latest.json").writeText(Json.write(mapOf("schemaVersion" to 1, "packageName" to context.packageName,
            "minSdk" to 29, "versionCode" to code, "versionName" to "fixture-new-version", "notes" to "fixture",
            "artifacts" to mapOf("arm64-v8a" to mapOf("url" to BuildConfig.UPDATE_FEED_URL.substringBeforeLast('/') + "/fixture.apk",
                "bytes" to file.length(), "sha256" to UpdateFeed.digest(file))))))
        val updater = AppUpdater(context)
        await { updater.state.value.checked }
        return file to updater
    }
    @Test fun cachedUpdateMustHaveTheInstalledAppsCertificate() {
        val (file, updater) = fixture(cert = "5678")
        try { assertFalse(updater.state.value.ready); assertFalse(file.exists()) } finally { updater.close() }
    }
    @Test fun anotherAppsPackageCannotBecomeInstallReady() {
        val (file, updater) = fixture(packageName = "other.app")
        try { assertFalse(updater.state.value.ready); assertFalse(file.exists()) } finally { updater.close() }
    }
    @Test fun aMatchingCachedApkRestoresAndTamperingBeforeInstallStopsTheInstaller() {
        val (file, updater) = fixture()
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        try {
            assertTrue(updater.state.value.ready)
            Thread.sleep(30)
            file.appendText("changed")
            updater.install(activity)
            await { updater.state.value.error != null }
            assertFalse(updater.state.value.ready); assertFalse(file.exists())
            assertNull(shadowOf(activity).nextStartedActivity)
        } finally { updater.close(); activity.finish() }
    }
}
