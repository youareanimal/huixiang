package io.github.korppujauho.pocketts.proto

import java.util.Base64

/** Opaque login material, not a verified or sendable voice account proof.
 * Preserve original nested protobuf bytes until signature and voice encoding are established.
 */
class MyTsVoiceMaterial private constructor(private val messages: Map<Int, ByteArray>) {
    val idData: ByteArray? get() = messages[9]?.copyOf()
    val userCertificate: ByteArray? get() = messages[10]?.copyOf()
    val signedAvatar: ByteArray? get() = messages[11]?.copyOf()
    override fun toString() = "MyTsVoiceMaterial(redacted)"

    /** Contains account key material; persist only inside the sealed account session. */
    fun encodeForStorage(): String = Base64.getEncoder().encodeToString(
        messages.entries.fold(ByteArray(0)) { bytes, (number, value) -> bytes + CloudProto.bytes(number, value) })

    companion object {
        private const val MAX_MESSAGE_BYTES = 64 * 1024
        private const val MAX_ENCODED_CHARS = 270_000

        internal fun fromLogin(fields: List<CloudProto.Field>): MyTsVoiceMaterial? {
            val selected = fields.filter { it.number in 9..11 }
            if (selected.isEmpty()) return null
            require(selected.map { it.number }.distinct().size == selected.size)
            val messages = selected.associate { field ->
                val bytes = requireNotNull(field.bytes)
                require(bytes.size <= MAX_MESSAGE_BYTES)
                // Check only the envelope. Neither signatures nor private key encoding are inferred here.
                CloudProto.read(bytes)
                field.number to bytes.copyOf()
            }
            return MyTsVoiceMaterial(messages)
        }

        fun decodeFromStorage(encoded: String): MyTsVoiceMaterial {
            require(encoded.length <= MAX_ENCODED_CHARS)
            val bytes = Base64.getDecoder().decode(encoded)
            return requireNotNull(fromLogin(CloudProto.read(bytes)))
        }
    }
}
