package io.github.korppujauho.pocketts.proto

import java.net.URI

/**
 * Read-only, credential-free checks against the official Matrix homeserver.
 * These GET probes never send a password, access token, login body, or message.
 */
object MatrixLoginDiagnostics {
    data class Check(val status: String, val millis: Long)
    data class Variant(
        val label: String,
        val userAgent: String,
        val versions: Check,
        val legacy: Check,
        val stable: Check,
    )
    data class Result(val host: String, val variants: List<Variant>)

    fun inspect(domain: String, http: CloudTransport): Result {
        // Discovery validates the delegated hostname and forbids redirects to untrusted hosts.
        val base = MatrixServerDiscovery.resolve(domain, http)
        val host = URI(base).host

        fun check(path: String, userAgent: String): Check {
            val started = System.nanoTime()
            val status = try {
                val response = http.request(
                    "GET", "$base$path", mapOf("User-Agent" to userAgent), null,
                )
                "HTTP_${response.status}"
            } catch (e: CloudFailure) {
                e.code
            } catch (_: Exception) {
                "NETWORK"
            }
            return Check(status, ((System.nanoTime() - started) / 1_000_000).coerceAtLeast(0))
        }

        val agents = listOf(
            "回响修复前请求头" to "HuiXiang/0.3",
            "TeamSpeak Go 客户端默认请求头" to "Go-http-client/1.1",
        )
        val variants = agents.map { (label, userAgent) ->
            Variant(
                label = label,
                userAgent = userAgent,
                versions = check("/_matrix/client/versions", userAgent),
                legacy = check("/_matrix/client/r0/login", userAgent),
                stable = check("/_matrix/client/v3/login", userAgent),
            )
        }
        return Result(host, variants)
    }
}
