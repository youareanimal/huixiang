package io.github.korppujauho.pocketts.proto

import kotlin.test.*

class MatrixServerDiscoveryTest {
    private val domain = "chat-2.tmspk.net"
    private fun reply(value: Any?, status: Int = 200) = CloudReply(status, Json.write(value).toByteArray())

    @Test fun onlyTheAccountHomeserversExplicitHttpsDelegationChangesTheApiDestination() {
        val paths = mutableListOf<String>()
        val api = MatrixCloud(domain, CloudTransport { method, url, headers, body ->
            paths += url
            if (url.endsWith("/.well-known/matrix/client")) {
                assertEquals("https://$domain/.well-known/matrix/client", url)
                assertEquals("GET", method); assertTrue(headers.isEmpty()); assertNull(body)
                reply(mapOf("m.homeserver" to mapOf("base_url" to "https://chat-6.tmspk.net/")))
            } else {
                assertEquals("https://chat-6.tmspk.net/_matrix/client/r0/login", url)
                val data = Json.parse(body!!.decodeToString()) as Map<*, *>
                assertEquals("@fixture:$domain", (data["identifier"] as Map<*, *>)["user"])
                reply(mapOf("user_id" to "@fixture:$domain", "access_token" to "fixture-token", "device_id" to "DEVICE_TEST"))
            }
        })
        api.discoverServer()
        val session = api.login(CloudAccountTransfer(domain, "@fixture:$domain", "fixture-password", "", "Fixture"), "DEVICE_TEST")
        assertEquals("@fixture:$domain", session.userId)
        assertEquals(2, paths.size)
    }

    @Test fun discoveryCannotSendCredentialsToAnArbitraryShardOrUntrustedUrl() {
        for (bad in listOf("http://chat-6.tmspk.net", "https://evil.test", "https://chat-6.tmspk.net.evil.test",
            "https://chat-6.tmspk.net@evil.test", "https://chat-6.tmspk.net:8443", "https://chat-6.tmspk.net/path",
            "https://chat-6.tmspk.net?query", "https://chat-6.tmspk.net#fragment", "https://user@chat-6.tmspk.net")) {
            var calls = 0
            val api = MatrixCloud(domain, CloudTransport { _, url, headers, body ->
                calls++
                assertTrue(url.endsWith("/.well-known/matrix/client")); assertTrue(headers.isEmpty()); assertNull(body)
                reply(mapOf("m.homeserver" to mapOf("base_url" to bad)))
            })
            assertEquals("CHAT_DISCOVERY_INVALID", assertFailsWith<CloudFailure> { api.discoverServer() }.code)
            assertEquals(1, calls)
        }
    }

    @Test fun unavailableDiscoveryDoesNotFallBackAndAttemptLoginOnTheAccountDomain() {
        for (status in listOf(500, 502, 503, 504, 302)) {
            var calls = 0
            val api = MatrixCloud(domain, CloudTransport { _, url, headers, body ->
                calls++
                assertEquals("https://$domain/.well-known/matrix/client", url)
                assertTrue(headers.isEmpty()); assertNull(body)
                reply(emptyMap<String, Any?>(), status)
            })
            val expected = if (status in 300..399) "CHAT_DISCOVERY_REDIRECT" else "CHAT_DISCOVERY_HTTP_$status"
            assertEquals(expected, assertFailsWith<CloudFailure> { api.discoverServer() }.code)
            assertEquals(1, calls, "login must not be attempted after discovery fails")
        }
        var calls = 0
        val api = MatrixCloud(domain, CloudTransport { _, _, _, _ -> calls++; throw CloudFailure("NETWORK") })
        assertEquals("CHAT_DISCOVERY_NETWORK", assertFailsWith<CloudFailure> { api.discoverServer() }.code)
        assertEquals(1, calls)
    }

    @Test fun onlyExplicitNotFoundOrNoDelegationUsesTheMxidDomainDefault() {
        assertEquals("https://$domain", MatrixServerDiscovery.resolve(domain, CloudTransport { _, _, headers, body ->
            assertTrue(headers.isEmpty()); assertNull(body)
            reply(emptyMap<String, Any?>(), 404)
        }))
        assertEquals("https://$domain", MatrixServerDiscovery.resolve(domain, CloudTransport { _, _, _, _ ->
            reply(emptyMap<String, Any?>())
        }))
    }
}
