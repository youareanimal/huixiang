package io.github.korppujauho.pocketts.proto

import kotlin.test.*

class MatrixCloudUserAgentTest {
    @Test fun everyMatrixRequestUsesTheTeamSpeakGoClientUserAgent() {
        val domain = "chat-2.tmspk.net"
        val seen = mutableListOf<String>()
        val http = CloudTransport { method, url, headers, body ->
            assertEquals("Go-http-client/1.1", headers["User-Agent"], "Wrong User-Agent for $url")
            seen += url
            when {
                url.endsWith("/.well-known/matrix/client") -> {
                    assertEquals("GET", method); assertNull(body)
                    CloudReply(404, byteArrayOf())
                }
                url.endsWith("/_matrix/client/r0/login") -> {
                    assertEquals("POST", method); assertNotNull(body)
                    CloudReply(200, Json.write(mapOf(
                        "user_id" to "@fixture:$domain",
                        "access_token" to "fixture-token",
                        "device_id" to "DEVICE_TEST",
                    )).toByteArray())
                }
                url.contains("/_matrix/client/r0/sync?") -> {
                    assertEquals("GET", method); assertNull(body)
                    CloudReply(200, Json.write(mapOf(
                        "next_batch" to "cursor",
                        "rooms" to emptyMap<String, Any?>(),
                    )).toByteArray())
                }
                else -> error("Unexpected request: $url")
            }
        }
        val api = MatrixCloud(domain, http)
        api.discoverServer()
        val account = CloudAccountTransfer(domain, "@fixture:$domain", "fixture-password", "", "Fixture")
        val session = api.login(account, "DEVICE_TEST")
        assertEquals("cursor", api.sync(session).cursor)
        assertEquals(3, seen.size)
    }
}
