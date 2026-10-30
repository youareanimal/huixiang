package io.github.korppujauho.pocketts.proto

import io.github.korppujauho.pocketts.proto.CloudProto.number
import java.math.BigInteger
import java.security.MessageDigest
import java.util.Base64

/** Account scalar stays on the client. Each connection signs its own transport challenge. */
class MyTsVoiceIdentity private constructor(
    private val id: ByteArray, private val creationTime: Long, private val publicKey: ByteArray,
    private val encryptedKey: ByteArray?, private val plainKey: ByteArray?, private val syncKey: ByteArray?,
    private val publicSignature: ByteArray,
) {
    override fun toString() = "MyTsVoiceIdentity(redacted)"

    internal fun authentication(iv: ByteArray): Map<String, Any?> {
        require(iv.size == 20 || iv.size == 64)
        val secret = if (encryptedKey != null) MyTsBookmarks.openItem(encryptedKey, requireNotNull(syncKey))
            else requireNotNull(plainKey).copyOf()
        try {
            require(secret.size == 32)
            require(MessageDigest.isEqual(Ed25519.scalarMulBase(secret), publicKey))
            val derived = Crypto.deriveKeyNonce(true, 0, 0, 8, iv)
            val challenge = derived.key + derived.nonce + "MyTeamSpeakID".toByteArray(Charsets.US_ASCII)
            val signature = scalarSign(secret, publicKey, challenge)
            return linkedMapOf("myTeamspeakId" to base64(id), "acTime" to creationTime,
                "userPubKey" to base64(publicKey), "authSign" to base64(signature),
                "pubSign" to base64(publicSignature.copyOfRange(0, 64)),
                "pubSignCert" to base64(publicSignature.copyOfRange(64, publicSignature.size)))
        } finally { secret.fill(0) }
    }

    companion object {
        private val ORDER = BigInteger.ONE.shiftLeft(252) + BigInteger("27742317777372353535851937790883648493")
        private fun base64(bytes: ByteArray) = Base64.getEncoder().encodeToString(bytes)
        private fun number(bytes: ByteArray) = BigInteger(1, bytes.reversedArray())
        private fun little(value: BigInteger): ByteArray {
            val bytes = value.toByteArray().reversedArray()
            return bytes.copyOf(32)
        }

        /** Scalar-key Ed25519: this is not the seed-based JCA Ed25519 key format. */
        internal fun scalarSign(secret: ByteArray, publicKey: ByteArray, message: ByteArray): ByteArray {
            require(secret.size == 32 && publicKey.size == 32)
            val nonceHash = Crypto.sha512(secret + message)
            val nonce = number(nonceHash).mod(ORDER)
            nonceHash.fill(0)
            val encodedNonce = little(nonce)
            val first = try { Ed25519.scalarMulBase(encodedNonce) } finally { encodedNonce.fill(0) }
            val challenge = number(Crypto.sha512(first + publicKey + message)).mod(ORDER)
            return first + little((nonce + challenge * number(secret)).mod(ORDER))
        }

        fun fromSession(session: MyTsAccount.Session): MyTsVoiceIdentity? {
            val raw = session.voiceMaterial?.idData ?: return null
            try {
                val fields = CloudProto.read(raw)
                fun bytes(n: Int) = requireNotNull(fields.singleOrNull { it.number == n }?.bytes)
                val publicKey = bytes(1)
                val privateFields = CloudProto.read(bytes(2))
                val encrypted = privateFields.singleOrNull { it.number == 1 }?.bytes?.takeIf { it.isNotEmpty() }
                val plain = privateFields.singleOrNull { it.number == 2 }?.bytes?.takeIf { it.isNotEmpty() }
                val id = bytes(4)
                val signature = bytes(5)
                // Official account reader converts the network-order uint64 before passing it to voice.
                val time = java.lang.Long.reverseBytes(requireNotNull(fields.number(3)))
                require(publicKey.size == 32 && id.size == 33 && signature.size in 65..65536 && time > 0)
                if (encrypted != null) require(encrypted.size == 112 && session.syncKey?.size == 32)
                else require(plain?.size == 32)
                return MyTsVoiceIdentity(id, time, publicKey, encrypted, plain, session.syncKey?.copyOf(), signature)
            } catch (_: Exception) { throw CloudFailure("MYTS_VOICE_IDENTITY_INVALID") }
            finally { raw.fill(0) }
        }
    }
}
