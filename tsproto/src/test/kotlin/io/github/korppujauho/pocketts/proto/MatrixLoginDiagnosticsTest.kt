package io.github.korppujauho.pocketts.proto

import kotlin.test.*

class MatrixLoginDiagnosticsTest {
    @Test fun comparesPublicMatrixEndpointsWithHuiXiangAndGoUserAgentsOnly() {
        val domain = "chat-2.tmspk.net"
        val seen = mutableListOf<Pair<String, String>>()
        val transport = CloudTransport { method, url, headers, body ->
            assertEquals("GET", method)
            assertNull(body, "No password, login request, token, or message may be sent")
            if (url == "https://$domain/.well-known/matrix/client") {
                assertTrue(headers.isEmpty())
                CloudReply(200, Json.write(
                    mapOf("m.homeserver" to mapOf("base_url" to "https://chat-6.tmspk.net/"))
                ).toByteArray())
            } else {
                val userAgent = headers["User-Agent"] ?: error("User-Agent must be explicit for comparison")
                assertTrue(userAgent in setOf("HuiXiang/0.3", "Go-http-client/1.1"))
                seen += url to userAgent
                when {
                    url.endsWith("/_matrix/client/versions") ->
                        CloudReply(if (userAgent == "Go-http-client/1.1") 200 else 503, byteArrayOf())
                    url.endsWith("/_matrix/client/r0/login") ->
                        CloudReply(if (userAgent == "Go-http-client/1.1") 200 else 503, byteArrayOf())
                    url.endsWith("/_matrix/client/v3/login") ->
                        CloudReply(if (userAgent == "Go-http-client/1.1") 404 else 503, byteArrayOf())
                    else -> error("Unexpected or unauthorized endpoint: $url")
                }
            }
        }

        val result = MatrixLoginDiagnostics.inspect(domain, transport)
        assertEquals("chat-6.tmspk.net", result.host)
        val huiXiang = result.variants.single { it.userAgent == "HuiXiang/0.3" }
        val go = result.variants.single { it.userAgent == "Go-http-client/1.1" }
        assertEquals("HTTP_503", huiXiang.versions.status)
        assertEquals("HTTP_503", huiXiang.legacy.status)
        assertEquals("HTTP_503", huiXiang.stable.status)
        assertEquals("HTTP_200", go.versions.status)
        assertEquals("HTTP_200", go.legacy.status)
        assertEquals("HTTP_404", go.stable.status)
        assertTrue(result.variants.all { it.versions.millis >= 0 && it.legacy.millis >= 0 && it.stable.millis >= 0 })
        assertEquals(6, seen.size)
    }

    @Test fun failingPublicEndpointsKeepSafeErrorCodesForBothUserAgents() {
        val domain = "chat-2.tmspk.net"
        val result = MatrixLoginDiagnostics.inspect(domain, CloudTransport { _, url, headers, body ->
            assertNull(body)
            if (url.endsWith("/.well-known/matrix/client")) {
                assertTrue(headers.isEmpty())
                CloudReply(404, byteArrayOf())
            } else {
                assertTrue(headers["User-Agent"] in setOf("HuiXiang/0.3", "Go-http-client/1.1"))
                throw CloudFailure("NETWORK")
            }
        })
        assertEquals("chat-2.tmspk.net", result.host)
        assertTrue(result.variants.all {
            it.versions.status == "NETWORK" && it.legacy.status == "NETWORK" && it.stable.status == "NETWORK"
        })
    }
}
