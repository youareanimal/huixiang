package io.github.korppujauho.pocketts.proto

import io.github.korppujauho.pocketts.proto.CloudProto.number
import io.github.korppujauho.pocketts.proto.CloudProto.string
import java.util.Base64

/**
 * Publishes the primary public chat identifier in the format consumed by the official TS6 client.
 *
 * The signed token is issued by the MyTeamSpeak service. It must be forwarded unchanged (Base64 encoded);
 * it is not the MyTeamSpeak login session token and must never be synthesized by this client.
 */
class MyTsChatTag(private val http: CloudTransport = HttpsCloudTransport) {
    class Payload internal constructor(
        val tag: String,
        val myTsToken: String,
        val updated: Long,
    ) {
        internal fun json(): String = Json.write(linkedMapOf(
            "myts_token" to myTsToken,
            "tag" to tag,
            "updated" to updated,
        ))

        override fun toString() = "MyTsChatTag.Payload(redacted)"
    }

    /** Read the account's primary public chat identifier without creating or modifying an account. */
    fun read(session: MyTsAccount.Session, matrixId: String): String? =
        readPrimaryIdentifier(session, matrixId)

    /**
     * Build the complete TS6 user-tag payload. The signed token is bound to the same MyTS session,
     * Matrix user, and primary identifier that are placed in the JSON.
     */
    fun readPayload(
        session: MyTsAccount.Session,
        matrixId: String,
        updatedAtMs: Long = System.currentTimeMillis(),
    ): Payload? {
        val tag = readPrimaryIdentifier(session, matrixId) ?: return null
        val token = signedAllowedIdentifierToken(session, matrixId, tag)
        if (updatedAtMs !in MIN_UPDATED_MS..MAX_UPDATED_MS)
            throw CloudFailure("MYTS_TAG_INVALID_UPDATED")
        val payload = Payload(tag, token, updatedAtMs)
        if (payload.json().length > MAX_USER_TAG_JSON_LENGTH)
            throw CloudFailure("MYTS_TAG_TOO_LARGE")
        return payload
    }

    private fun readPrimaryIdentifier(session: MyTsAccount.Session, matrixId: String): String? {
        if (session.token.isBlank() || !validMatrixId(matrixId)) return null
        val fields = MyTsAccount(http).rpc(
            "tschat",
            "getActiveIdentifierList",
            CloudProto.text(1, session.token) + CloudProto.text(2, matrixId),
        )
        val status = fields.singleOrNull { it.number == 2 }?.bytes?.let {
            try { CloudProto.read(it).number(1) } catch (_: Exception) { null }
        }
        if (status != SUCCESS) throw CloudFailure("MYTS_TAG_" + (status ?: 0L))
        val active = fields.filter { it.number == 1 }.mapNotNull { it.bytes?.let(CloudProto::read) }
            .filter { it.number(3) == 1L }
        if (active.size != 1) return null
        val item = active.single()
        if (item.string(2) != matrixId) return null
        val tag = item.string(1)
        return tag.takeIf {
            it.isNotBlank() && it.length <= MAX_IDENTIFIER_LENGTH && it.none(Char::isISOControl)
        }
    }

    private fun signedAllowedIdentifierToken(
        session: MyTsAccount.Session,
        matrixId: String,
        primaryTag: String,
    ): String {
        val fields = MyTsAccount(http).rpc(
            "tschat",
            "requestSignedAllowedIdentifierList",
            CloudProto.text(1, session.token) + CloudProto.text(2, matrixId),
        )

        val errorFields = fields.filter { it.number == 2 }
        val status = errorFields.singleOrNull()?.bytes?.let {
            try { CloudProto.read(it).number(1) } catch (_: Exception) { null }
        }
        if (status != SUCCESS)
            throw CloudFailure("MYTS_SIGNED_IDENTIFIER_" + (status ?: 0L))

        val tokenBytes = fields.singleOrNull { it.number == 1 }?.bytes
            ?: throw CloudFailure("MYTS_SIGNED_IDENTIFIER_NO_TOKEN")
        validateSignedIdentifierToken(tokenBytes, primaryTag)
        return Base64.getEncoder().encodeToString(tokenBytes)
    }

    /**
     * MatrixIdentifierToken: signature(1), signing certificate(2), timestamp seconds(3),
     * and the signed allowed tag list(4). Validate the envelope and primary-tag binding, but
     * do not attempt to recreate or locally verify the proprietary signature chain.
     */
    private fun validateSignedIdentifierToken(tokenBytes: ByteArray, primaryTag: String) {
        if (tokenBytes.isEmpty() || tokenBytes.size > MAX_SIGNED_TOKEN_BYTES)
            throw CloudFailure("MYTS_SIGNED_IDENTIFIER_INVALID")

        val fields = try {
            CloudProto.read(tokenBytes)
        } catch (_: Exception) {
            throw CloudFailure("MYTS_SIGNED_IDENTIFIER_INVALID")
        }
        val signature = fields.singleOrNull { it.number == 1 }?.bytes
        val certificate = fields.singleOrNull { it.number == 2 }?.bytes
        val timestamp = fields.singleOrNull { it.number == 3 }?.integer
        val tagList = fields.singleOrNull { it.number == 4 }?.bytes
        if (signature?.size != SIGNATURE_LENGTH ||
            certificate.isNullOrEmpty() || certificate.size > MAX_CERTIFICATE_BYTES ||
            timestamp == null || timestamp <= 0L || tagList == null) {
            throw CloudFailure("MYTS_SIGNED_IDENTIFIER_INVALID")
        }

        val allowedTags = try {
            CloudProto.read(tagList).filter { it.number == 1 }.mapNotNull {
                it.bytes?.toString(Charsets.UTF_8)
            }
        } catch (_: Exception) {
            throw CloudFailure("MYTS_SIGNED_IDENTIFIER_INVALID")
        }
        if (primaryTag !in allowedTags)
            throw CloudFailure("MYTS_SIGNED_IDENTIFIER_TAG_MISMATCH")
    }

    private fun validMatrixId(value: String): Boolean {
        if (!value.startsWith("@") || value.length !in 4..512 ||
            value.any { it.isWhitespace() || it.isISOControl() }) return false
        val separator = value.lastIndexOf(':')
        if (separator <= 1 || separator == value.lastIndex) return false
        return CloudAccountTransfer.officialDomain(value.substring(separator + 1))
    }

    companion object {
        private const val SUCCESS = 1L
        private const val MAX_IDENTIFIER_LENGTH = 100
        private const val MAX_SIGNED_TOKEN_BYTES = 8 * 1024
        private const val MAX_CERTIFICATE_BYTES = 4 * 1024
        private const val SIGNATURE_LENGTH = 64
        private const val MAX_USER_TAG_JSON_LENGTH = 1024
        private const val MIN_UPDATED_MS = 1_000_000_000_000L
        private const val MAX_UPDATED_MS = 9_999_999_999_999L

        /** Set only the official public user-tag property; TS6's verified tag does not require metadata. */
        fun updateCommand(payload: Payload): String {
            val value = payload.json()
            require(value.length <= MAX_USER_TAG_JSON_LENGTH)
            return Commands.build(
                "clientupdate",
                linkedMapOf(
                    "client_user_tag" to value,
                    "return_code" to "quiet-myts-tag",
                ),
            )
        }
    }
}
