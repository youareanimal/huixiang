package io.github.korppujauho.pocketts.proto

import io.github.korppujauho.pocketts.proto.CloudProto.number
import io.github.korppujauho.pocketts.proto.CloudProto.string

/** Read the account's primary public chat identifier without creating or modifying an account. */
class MyTsChatTag(private val http: CloudTransport = HttpsCloudTransport) {
    fun read(session: MyTsAccount.Session, matrixId: String): String? {
        if (session.token.isBlank() || matrixId.isBlank()) return null
        val fields = MyTsAccount(http).rpc("tschat", "getActiveIdentifierList",
            CloudProto.text(1, session.token) + CloudProto.text(2, matrixId))
        val status = fields.singleOrNull { it.number == 2 }?.bytes?.let { CloudProto.read(it).number(1) }
        if (status != 1L) throw CloudFailure("MYTS_TAG_${status ?: 0}")
        val active = fields.filter { it.number == 1 }.mapNotNull { it.bytes?.let(CloudProto::read) }
            .filter { it.number(3) == 1L }
        if (active.size != 1) return null
        val item = active.single()
        if (item.string(2) != matrixId) return null
        val tag = item.string(1)
        return tag.takeIf { it.isNotBlank() && it.length <= 100 && it.none(Char::isISOControl) &&
            Json.write(mapOf("tag" to it)).length <= 100 }
    }

    companion object {
        /** Match TS6's structured user tag and fallback metadata, without exposing it to logcat. */
        fun updateCommand(tag: String): String {
            require(tag.isNotBlank() && tag.none(Char::isISOControl))
            val value = Json.write(mapOf("tag" to tag))
            require(value.length <= 100)
            return Commands.build("clientupdate", "client_user_tag" to value, "client_meta_data" to value,
                "return_code" to "quiet-myts-tag")
        }
    }
}
