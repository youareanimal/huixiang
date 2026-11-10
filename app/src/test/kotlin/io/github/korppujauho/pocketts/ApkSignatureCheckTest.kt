package io.github.korppujauho.pocketts

import android.content.pm.PackageInfo
import android.content.pm.Signature
import android.content.pm.SigningInfo
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ApkSignatureCheckTest {
    private fun modern(value: String) = PackageInfo().also {
        it.signingInfo = SigningInfo().also { info -> shadowOf(info).setSignatures(arrayOf(Signature(value))) }
    }
    @Suppress("DEPRECATION")
    private fun legacy(value: String) = PackageInfo().also { it.signatures = arrayOf(Signature(value)) }

    @Test fun modernSignaturesMustMatchWithoutUsingFallback() {
        assertEquals(ApkSignatureCheck.Result.MATCH, ApkSignatureCheck.compare(modern("1234"), modern("1234")) { error("unexpected fallback") })
        assertEquals(ApkSignatureCheck.Result.MISMATCH, ApkSignatureCheck.compare(modern("5678"), modern("1234")) { error("mismatch must not fall back") })
    }

    @Test fun missingModernInfoFallsBackOnlyToMatchingSystemCertificates() {
        val missing = PackageInfo()
        assertEquals(ApkSignatureCheck.Result.MATCH, ApkSignatureCheck.compare(missing, modern("1234")) { legacy("1234") to legacy("1234") })
        assertEquals(ApkSignatureCheck.Result.MISMATCH, ApkSignatureCheck.compare(missing, modern("1234")) { legacy("5678") to legacy("1234") })
        assertEquals(ApkSignatureCheck.Result.UNAVAILABLE, ApkSignatureCheck.compare(missing, modern("1234")) { null to legacy("1234") })
        assertEquals(ApkSignatureCheck.Result.UNAVAILABLE, ApkSignatureCheck.compare(missing, modern("1234")) { legacy("5678") to legacy("5678") })
    }
}
