package io.github.korppujauho.pocketts.proto

import io.github.korppujauho.pocketts.proto.CloudProto.number
import java.util.Base64

/** Signed, public account metadata. The server validates the original signatures. */
class MyTsVoiceData private constructor(private val certificate: ByteArray, private val avatar: ByteArray?,
    private val badges: ByteArray?) {
    override fun toString() = "MyTsVoiceData(redacted)"

    fun command(): String {
        fun encode(bytes: ByteArray) = Base64.getEncoder().encodeToString(bytes)
        return Commands.build("updatemytsdata", "myts_certificate" to encode(certificate),
            "myts_avatar" to avatar?.let(::encode), "myts_signed_badge" to badges?.let(::encode),
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
