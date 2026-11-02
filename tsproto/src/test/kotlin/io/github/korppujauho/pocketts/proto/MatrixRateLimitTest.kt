package io.github.korppujauho.pocketts.proto

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MatrixRateLimitTest {
    @Test fun syncUsesLongPollingAndHonorsServerRetryAfter() {
        val domain = "chat-2.tmspk.net"
        val requests = mutableListOf<String>()
        val http = CloudTransport { method, url, headers, body ->
            requests += url
            when {
                url.endsWith("/.well-known/matrix/client") -> CloudReply(404, byteArrayOf())
                url.contains("/_matrix/client/r0/sync?") -> {
                    assertEquals("GET", method)
                    assertEquals("Go-http-client/1.1", headers["User-Agent"])
                    assertTrue(url.contains("timeout=30000"))
                    assertEquals(null, body)
                    CloudReply(429, Json.write(mapOf(
                        "errcode" to "M_LIMIT_EXCEEDED",
                        "error" to "Too many requests",
                        "retry_after_ms" to 60_000,
                    )).toByteArray())
                }
                else -> error("Unexpected request: $url")
            }
        }
        val api = MatrixCloud(domain, http)
        api.discoverServer()
        val session = MatrixCloud.Session("@fixture:$domain", "fixture-token", "DEVICE_TEST")

        val first = assertFailsWith<CloudFailure> { api.sync(session) }
        assertEquals("M_LIMIT_EXCEEDED", first.code)
        assertEquals(60_000L, first.retryAfterMs)
        assertEquals(2, requests.size)

        val second = assertFailsWith<CloudFailure> { api.sync(session) }
        assertEquals("M_LIMIT_EXCEEDED", second.code)
        assertTrue((second.retryAfterMs ?: 0L) in 1L..60_000L)
        // The second attempt is stopped locally; it does not hit the homeserver again.
        assertEquals(2, requests.size)
    }

    @Test fun httpRetryAfterHeaderIsUsedWhenMatrixBodyHasNoDelay() {
        val domain = "chat-2.tmspk.net"
        val requests = mutableListOf<String>()
        val http = CloudTransport { method, url, _, _ ->
            requests += url
            when {
                url.endsWith("/.well-known/matrix/client") -> CloudReply(404, byteArrayOf())
                url.contains("/_matrix/client/r0/sync?") ->
                    CloudReply(429, Json.write(mapOf("errcode" to "M_LIMIT_EXCEEDED")).toByteArray(), 45_000)
                else -> error("Unexpected request: $url")
            }
        }
        val api = MatrixCloud(domain, http)
        api.discoverServer()
        val session = MatrixCloud.Session("@fixture:$domain", "fixture-token", "DEVICE_TEST")
        val failure = assertFailsWith<CloudFailure> { api.sync(session) }
        assertEquals(45_000L, failure.retryAfterMs)
        assertEquals(2, requests.size)
    }
}
