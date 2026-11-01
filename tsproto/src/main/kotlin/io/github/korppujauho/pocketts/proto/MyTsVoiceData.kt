package io.github.korppujauho.pocketts.proto

import io.github.korppujauho.pocketts.proto.CloudProto.number

/** Signed, public account metadata. The server validates the original signatures. */
class MyTsVoiceData private constructor(private val certificate: ByteArray, private val avatar: ByteArray?,
    private val badges: ByteArray?, val badgeCount: Int) {
    override fun toString() = "MyTsVoiceData(redacted)"

    fun commandBytes(): ByteArray {
        return Commands.buildBytes("updatemytsdata", "myts_certificate" to certificate,
            "myts_avatar" to avatar, "myts_signed_badge" to badges,
            "return_code" to "quiet-myts-data")
    }

    companion object {
        /**
         * Validate the bounded signed-badge envelope without exposing or logging its contents.
         * Returns null for malformed data, otherwise the number of signed badge entries.
         */
        fun signedBadgeCount(bytes: ByteArray): Int? {
            return try {
                when {
                    bytes.isEmpty() -> 0
                    bytes.size > 65536 -> null
                    else -> {
                        val fields = CloudProto.read(bytes)
                        val entries = fields.filter { it.number == 1 }
                        val valid = fields.all { field ->
                            if (field.number != 1) true else {
                                val nested = CloudProto.read(requireNotNull(field.bytes))
                                nested.singleOrNull { it.number == 1 }?.bytes != null && nested.number(3) != null &&
                                    nested.singleOrNull { it.number == 2 }?.bytes?.size == 64
                            }
                        }
                        if (valid) entries.size else null
                    }
                }
            } catch (_: Exception) { null }
        }

        fun fromSession(session: MyTsAccount.Session, signedBadges: ByteArray? = null): MyTsVoiceData? {
            val material = session.voiceMaterial ?: return null
            val wrapper = material.userCertificate ?: return null
            val certificate = CloudProto.read(wrapper).singleOrNull { it.number == 1 }?.bytes ?: return null
            if (certificate.isEmpty()) return null
            val avatar = material.signedAvatar?.takeIf { bytes ->
                val fields = CloudProto.read(bytes)
                fields.singleOrNull { it.number == 1 }?.bytes != null && fields.number(2) != null &&
                    fields.singleOrNull { it.number == 3 }?.bytes?.size == 64
            }
            val count = signedBadges?.let(::signedBadgeCount) ?: 0
            val badges = signedBadges?.takeIf { count > 0 }?.copyOf()
            return MyTsVoiceData(certificate, avatar, badges, count)
        }
    }
}
