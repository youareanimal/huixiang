package io.github.korppujauho.pocketts.proto

import io.github.korppujauho.pocketts.proto.CloudProto.number

/** Signed, public account metadata. The server validates the original signatures. */
class MyTsVoiceData private constructor(private val certificate: ByteArray, private val avatar: ByteArray?,
    private val badges: ByteArray?) {
    override fun toString() = "MyTsVoiceData(redacted)"

    fun commandBytes(): ByteArray {
        return Commands.buildBytes("updatemytsdata", "myts_certificate" to certificate,
            "myts_avatar" to avatar, "myts_signed_badge" to badges,
            "return_code" to "quiet-myts-data")
    }

    companion object {
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
            val badges = signedBadges?.takeIf { bytes ->
                require(bytes.size <= 65536)
                CloudProto.read(bytes).all { field ->
                    if (field.number != 1) true else {
                        val fields = CloudProto.read(requireNotNull(field.bytes))
                        fields.singleOrNull { it.number == 1 }?.bytes != null && fields.number(3) != null &&
                            fields.singleOrNull { it.number == 2 }?.bytes?.size == 64
                    }
                }
            }
            return MyTsVoiceData(certificate, avatar, badges?.copyOf())
        }
    }
}
