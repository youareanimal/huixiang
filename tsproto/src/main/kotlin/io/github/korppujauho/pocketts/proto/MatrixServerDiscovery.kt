package io.github.korppujauho.pocketts.proto

import java.net.URI

/** Resolve only an explicit delegation published by the account's own official homeserver. */
object MatrixServerDiscovery {
    fun resolve(domain: String, http: CloudTransport): String {
        require(CloudAccountTransfer.officialDomain(domain))
        val original = "https://$domain"
        // Discovery is public: never attach the account password or an access token.
        val reply = try { http.request("GET", "$original/.well-known/matrix/client", emptyMap(), null) }
            catch (_: CloudFailure) { throw CloudFailure("CHAT_DISCOVERY_NETWORK") }
        // Matrix uses 404 to mean there is no delegation, in which case the MXID domain is the homeserver.
        if (reply.status == 404) return original
        if (reply.status in 300..399) throw CloudFailure("CHAT_DISCOVERY_REDIRECT")
        if (reply.status != 200) throw CloudFailure("CHAT_DISCOVERY_HTTP_${reply.status}")
        val data = try { Json.parse(reply.body.toString(Charsets.UTF_8)) as? Map<*, *> }
            catch (_: Exception) { null } ?: throw CloudFailure("CHAT_DISCOVERY_INVALID")
        val server = data["m.homeserver"] as? Map<*, *> ?: return original
        val url = server["base_url"] as? String ?: throw CloudFailure("CHAT_DISCOVERY_INVALID")
        val uri = try { URI(url) } catch (_: Exception) { throw CloudFailure("CHAT_DISCOVERY_INVALID") }
        if (uri.scheme != "https" || uri.host == null || !CloudAccountTransfer.officialDomain(uri.host) ||
            uri.userInfo != null || uri.rawQuery != null || uri.rawFragment != null ||
            uri.port !in listOf(-1, 443) || uri.rawPath.orEmpty() !in listOf("", "/"))
            throw CloudFailure("CHAT_DISCOVERY_INVALID")
        return "https://${uri.host}"
    }
}
