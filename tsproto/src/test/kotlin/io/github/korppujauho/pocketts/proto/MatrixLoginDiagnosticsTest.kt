package io.github.korppujauho.pocketts.proto

import kotlin.test.*

class MatrixLoginDiagnosticsTest {
    @Test fun onlyPublicGetEndpointsOnTheOfficialHomeserverAreProbed() {
        val domain = "chat-2.tmspk.net"
        val seen = mutableListOf<String>()
        val transport = CloudTransport { method, url, headers, body ->
            assertEquals("GET", method)
            assertTrue(headers.isEmpty(), "No access token or other credentials may be sent")
            assertNull(body, "No password, login request, or message may be sent")
            seen += url
            when (url) {
                "https://$domain/.well-known/matrix/client" -> CloudReply(200, Json.write(
                    mapOf("m.homeserver" to mapOf("base_url" to "https://chat-6.tmspk.net/"))).toByteArray())
                "https://chat-6.tmspk.net/_matrix/client/r0/login" -> CloudReply(503, byteArrayOf())
                "https://chat-6.tmspk.net/_matrix/client/v3/login" -> CloudReply(200,
                    Json.write(mapOf("flows" to listOf(mapOf("type" to "m.login.password")))).toByteArray())
                else -> error("Unexpected or unauthorized endpoint: $url")
            }
        }
        val result = MatrixLoginDiagnostics.inspect(domain, transport)
        assertEquals("chat-6.tmspk.net", result.host)
        assertEquals("HTTP_503", result.legacy.status)
        assertEquals("HTTP_200", result.stable.status)
        assertTrue(result.legacy.millis >= 0)
        assertTrue(result.stable.millis >= 0)
        assertEquals(3, seen.size)
    }

    @Test fun failingPublicEndpointsKeepSafeErrorCodes() {
        val domain = "chat-2.tmspk.net"
        val result = MatrixLoginDiagnostics.inspect(domain, CloudTransport { _, url, headers, body ->
            assertTrue(headers.isEmpty()); assertNull(body)
            if (url.endsWith("/.well-known/matrix/client")) CloudReply(404, byteArrayOf())
            else throw CloudFailure("NETWORK")
        })
        assertEquals("NETWORK", result.legacy.status)
        assertEquals("NETWORK", result.stable.status)
    }
}
