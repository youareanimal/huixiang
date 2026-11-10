package io.github.korppujauho.pocketts

import android.content.pm.PackageInfo
import android.content.pm.Signature
import java.security.MessageDigest

internal object ApkSignatureCheck {
    enum class Result { MATCH, MISMATCH, UNAVAILABLE }

    private fun digest(signatures: Array<Signature>?): Set<List<Byte>> = signatures?.map {
        MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).toList()
    }?.toSet().orEmpty()

    /** Only use legacy PackageManager certificates when modern signing info is missing. */
    @Suppress("DEPRECATION")
    fun compare(archive: PackageInfo, installed: PackageInfo,
        legacy: () -> Pair<PackageInfo?, PackageInfo?>): Result {
        val candidate = digest(archive.signingInfo?.apkContentsSigners)
        val current = digest(installed.signingInfo?.apkContentsSigners)
        if (candidate.isNotEmpty() && current.isNotEmpty())
            return if (candidate == current) Result.MATCH else Result.MISMATCH

        val (oldArchive, oldInstalled) = legacy()
        val legacyCandidate = digest(oldArchive?.signatures)
        val legacyCurrent = digest(oldInstalled?.signatures)
        if (legacyCandidate.isEmpty() || legacyCurrent.isEmpty()) return Result.UNAVAILABLE
        // Never accept a legacy result that contradicts a modern signer we did receive.
        if ((candidate.isNotEmpty() && candidate != legacyCandidate) ||
            (current.isNotEmpty() && current != legacyCurrent)) return Result.UNAVAILABLE
        return if (legacyCandidate == legacyCurrent) Result.MATCH else Result.MISMATCH
    }
}
