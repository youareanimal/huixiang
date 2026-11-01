package io.github.korppujauho.pocketts.proto

import java.net.URI

/**
 * Read-only, credential-free checks against the official Matrix homeserver.
 * GET /login only advertises supported login flows; it never authenticates a user.
 */
object MatrixLoginDiagnostics {
    data class Check(val status: String, val millis: Long)
    data class Result(val host: String, val legacy: Check, val stable: Check)

    fun inspect(domain: String, http: CloudTransport): Result {
        // Discovery validates the delegated hostname and forbids redirects to untrusted hosts.
        val base = MatrixServerDiscovery.resolve(domain, http)
        val host = URI(base).host
        fun check(path: String): Check {
            val started = System.nanoTime()
            val status = try {
                val response = http.request("GET", "$base/_matrix/client/$path/login", emptyMap(), null)
                "HTTP_${response.status}"
            } catch (e: CloudFailure) {
                e.code
            } catch (_: Exception) {
                "NETWORK"
            }
            return Check(status, ((System.nanoTime() - started) / 1_000_000).coerceAtLeast(0))
        }
        return Result(host, check("r0"), check("v3"))
    }
}
