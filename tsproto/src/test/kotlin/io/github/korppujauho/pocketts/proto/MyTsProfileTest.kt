package io.github.korppujauho.pocketts.proto

import io.github.korppujauho.pocketts.proto.CloudProto.number
import io.github.korppujauho.pocketts.proto.CloudProto.string
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class MyTsProfileTest {
    private val session = MyTsAccount.Session("private-token", "Fixture")
    private val ok = CloudProto.bytes(1, CloudProto.integer(1, 1))
    private val image = java.util.Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jJpUAAAAASUVORK5CYII=")
    private fun payload(body: ByteArray): Pair<String, List<CloudProto.Field>> {
        val length = body[0].toInt()
        return body.copyOfRange(1, 1 + length).toString(Charsets.US_ASCII) to CloudProto.read(body.copyOfRange(1 + length, body.size))
    }
    @Test fun readsBasicInfoAndOnlineAvatarWithoutChatOrAuthentication() {
        val api = MyTsProfile(CloudTransport { method, url, headers, body ->
            assertEquals("POST", method); assertEquals("https://clientapi.myteamspeak.com/user", url)
            assertEquals("application/ts3cloud", headers["Content-Type"])
            val (name, fields) = payload(body!!)
            assertEquals("getAccountData", name); assertEquals(session.token, fields.string(1))
            assertEquals(listOf(1L, 3L), fields.filter { it.number == 2 }.map { it.integer })
            CloudReply(200, ok + CloudProto.text(2, "Fixture") + CloudProto.text(3, "fixture@example.com") +
                CloudProto.bytes(5, CloudProto.bytes(1, CloudProto.integer(1, 2) + CloudProto.text(2, "https://storage.googleapis.com/bucket/avatar"))))
        })
        val profile = api.read(session)
        assertEquals("fixture@example.com", profile.email)
        assertTrue(profile.avatarUrl.startsWith("https://storage.googleapis.com/"))
        assertFalse(profile.toString().contains("example.com"))
    }
    @Test fun refusesUntrustedOrCredentialBearingAvatarLocations() {
        for (url in listOf("http://storage.googleapis.com/b/a", "https://storage.googleapis.com.attacker.test/b/a",
            "https://attacker.test/b/a", "https://secret@storage.googleapis.com/b/a", "https://storage.googleapis.com:444/b/a",
            "https://storage.googleapis.com/b/a#private", "file:///avatar")) assertNull(MyTsProfile.safeAvatarUrl(url))
    }
    @Test fun uploadUsesSignedPutThenRegistersOnlyOnlineStateAndNeverSendsAccountTokenToStorage() {
        val calls = mutableListOf<String>()
        var objectName = ""
        val api = MyTsProfile(CloudTransport { method, url, headers, body ->
            if (method == "PUT") {
                calls += "PUT"
                assertTrue(url.startsWith("https://storage.googleapis.com/"))
                assertEquals(mapOf("Content-Type" to "text/plain"), headers)
                assertArrayEquals(image, body)
                return@CloudTransport CloudReply(200, byteArrayOf())
            }
            val (name, fields) = payload(body!!); calls += name
            assertEquals(session.token, fields.string(1))
            when (name) {
                "requestAvatarSignedUrl" -> {
                    objectName = fields.string(2)
                    assertTrue(objectName.matches(Regex("[A-Za-z0-9]{35,43}")))
                    CloudReply(200, ok + CloudProto.bytes(2, CloudProto.text(1, objectName) +
                        CloudProto.text(2, "https://storage.googleapis.com/bucket/$objectName?signature=fixture")))
                }
                "requestUploadAvatar" -> {
                    val mapping = CloudProto.read(fields.single { it.number == 2 }.bytes!!)
                    assertEquals(2L, mapping.number(1)); assertEquals(objectName, mapping.string(2))
                    CloudReply(200, CloudProto.integer(1, 1))
                }
                else -> error("Unexpected request")
            }
        })
        api.updateOnlineAvatar(session, image)
        assertEquals(listOf("requestAvatarSignedUrl", "PUT", "requestUploadAvatar"), calls)
    }
    @Test fun failedUploadOrRedirectNeverRegistersAnAvatar() {
        for (status in listOf(307, 403, 503)) {
            var writes = 0
            val api = MyTsProfile(CloudTransport { method, _, _, body ->
                if (method == "PUT") { writes++; CloudReply(status, "private-server-body".toByteArray()) }
                else {
                    val (name, fields) = payload(body!!); assertEquals("requestAvatarSignedUrl", name)
                    CloudReply(200, ok + CloudProto.bytes(2, CloudProto.text(1, fields.string(2)) + CloudProto.text(2, "https://storage.googleapis.com/b/a")))
                }
            })
            val error = assertThrows(CloudFailure::class.java) { api.updateOnlineAvatar(session, image) }
            assertEquals("AVATAR_HTTP_$status", error.code); assertFalse(error.toString().contains("private-server")); assertEquals(1, writes)
        }
    }
    @Test fun untrustedUploadUrlAndOversizedImagesAreRejectedBeforeUpload() {
        var calls = 0
        val api = MyTsProfile(CloudTransport { _, _, _, body ->
            calls++
            val (_, fields) = payload(body!!)
            CloudReply(200, ok + CloudProto.bytes(2, CloudProto.text(1, fields.string(2)) + CloudProto.text(2, "https://attacker.test/a")))
        })
        assertEquals("AVATAR_HOST", assertThrows(CloudFailure::class.java) { api.updateOnlineAvatar(session, image) }.code)
        assertEquals(1, calls)
        assertEquals("AVATAR_IMAGE", assertThrows(CloudFailure::class.java) { api.updateOnlineAvatar(session, ByteArray(524289)) }.code)
        assertEquals(1, calls)
    }
    @Test fun removalTargetsOnlyOnlineStateAndRequiresSuccessfulReply() {
        val api = MyTsProfile(CloudTransport { _, _, _, body ->
            val (name, fields) = payload(body!!)
            assertEquals("requestDeleteAvatar", name); assertEquals(session.token, fields.string(1))
            assertEquals(2L, fields.number(2)); assertEquals(2, fields.size)
            CloudReply(200, CloudProto.integer(1, 1))
        })
        api.removeOnlineAvatar(session)
    }
    @Test fun rejectedProfileDoesNotExposeTheServersErrorText() {
        val api = MyTsProfile(CloudTransport { _, _, _, _ -> CloudReply(200, CloudProto.bytes(1,
            CloudProto.integer(1, 0) + CloudProto.text(2, "private-error-token") + CloudProto.integer(3, 109))) })
        val failure = assertThrows(CloudFailure::class.java) { api.read(session) }
        assertEquals("PROFILE_109", failure.code); assertFalse(failure.toString().contains("private-error-token"))
    }
}
