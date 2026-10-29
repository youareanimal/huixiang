package io.github.korppujauho.pocketts.proto

/** Explicitly selected, private transfer file; never embed one in an APK or a source archive. */
class CloudAccountTransfer(
    val domain: String,
    val userId: String,
    val password: String,
    val myTsSession: String,
    val username: String,
) {
    override fun toString() = "CloudAccountTransfer(redacted)"
    fun encode() = Json.write(mapOf("format" to "shengqiao-account", "version" to 1,
        "domain" to domain, "user_id" to userId, "chat_password" to password,
        "myts_session" to myTsSession, "username" to username))

    companion object {
        private val domainPattern = Regex("chat(?:-[0-9]+)?\\.(?:tmspk\\.net|teamspeak\\.com)")
        fun officialDomain(value: String) = domainPattern.matches(value)
        fun parse(text: String): CloudAccountTransfer {
            try {
                require(text.toByteArray().size <= 32_768)
                val root = Json.parse(text) as? Map<*, *> ?: error("Object required")
                require(root["format"] == "shengqiao-account" && (root["version"] as? Number)?.toInt() == 1)
                fun str(key: String, max: Int): String = (root[key] as? String ?: "").also { require(it.length <= max) }
                val domain = str("domain", 128)
                val userId = str("user_id", 512)
                val password = str("chat_password", 4096)
                require(officialDomain(domain) && userId.startsWith('@') && userId.endsWith(":$domain"))
                require(userId.none { it.isWhitespace() || it == '/' || it == '?' || it == '#' } && password.isNotEmpty())
                return CloudAccountTransfer(domain, userId, password, str("myts_session", 4096), str("username", 128))
            } catch (_: Exception) { throw CloudFailure("INVALID_TRANSFER") }
        }
    }
}
