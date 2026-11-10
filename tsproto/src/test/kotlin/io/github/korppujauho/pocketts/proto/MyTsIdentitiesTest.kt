package io.github.korppujauho.pocketts.proto

import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import kotlin.test.*

class MyTsIdentitiesTest {
    private val key = ByteArray(32) { it.toByte() }
    private val fixtureIdentity = Identity.generate(securityLevel = 0)
    private val fixtureId = "00000000-0000-0000-0000-000000000123"
    private fun blob(plain: ByteArray): ByteArray {
        val ctr = ByteArray(16) { (it + 1).toByte() }
        val initial = ctr.copyOf()
        val aes = Cipher.getInstance("AES/ECB/NoPadding")
        aes.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
        val encrypted = ByteArray(plain.size)
        for (offset in plain.indices step 16) {
            val block = aes.doFinal(ctr)
            for (i in 0 until minOf(16, plain.size - offset)) encrypted[offset + i] = (plain[offset + i].toInt() xor block[i].toInt()).toByte()
            for (i in ctr.indices) { ctr[i] = (ctr[i] + 1).toByte(); if (ctr[i] != 0.toByte()) break }
        }
        return initial + encrypted + MessageDigest.getInstance("SHA-512").digest(plain)
    }
    private fun response(cipher: ByteArray, itemId: String = fixtureId, duplicate: Boolean = false): ByteArray {
        val detail = CloudProto.text(1, itemId) + CloudProto.bytes(3, cipher)
        val group = CloudProto.integer(1, 1) + CloudProto.bytes(3, detail) + if (duplicate) CloudProto.bytes(3, detail) else byteArrayOf()
        return CloudProto.integer(1, 301) + CloudProto.bytes(2, group)
    }
    private fun plaintext(name: String = "fixture"): ByteArray {
        val identity = CloudProto.text(1, fixtureIdentity.export()) + CloudProto.text(2, name) + CloudProto.text(3, "sample")
        return CloudProto.text(2, fixtureId) + CloudProto.integer(6, 1) + CloudProto.bytes(17, identity)
    }
    private fun reader(response: ByteArray) = MyTsIdentities(CloudTransport { method, url, _, body ->
        assertEquals("POST", method)
        assertEquals("https://clientapi.myteamspeak.com/synchronization", url)
        assertEquals("requestServerItems", body!!.copyOfRange(1, 19).decodeToString())
        CloudReply(200, response)
    })
    private fun session() = MyTsAccount.Session("fixture", "sample", key)
    @Test fun readsIdentityWithoutExposingItsSecretInToString() {
        val entry = reader(response(blob(plaintext()))).read(session()).single()
        assertEquals(fixtureIdentity.uid, entry.legacyUid)
        val ts6Expected = java.util.Base64.getEncoder().encodeToString(
            MessageDigest.getInstance("SHA-256").digest(fixtureIdentity.publicKeyString.toByteArray(Charsets.US_ASCII)))
        assertEquals(ts6Expected, entry.ts6Uid)
        assertEquals("fixture", entry.name)
        assertEquals("sample", entry.nickname)
        assertFalse(entry.toString().contains(fixtureIdentity.export()))
    }
    @Test fun rejectsTamperingMismatchedIdsAndDuplicates() {
        val cipher = blob(plaintext())
        val damaged = cipher.copyOf().also { it[20] = (it[20].toInt() xor 1).toByte() }
        assertFailsWith<CloudFailure> { reader(response(damaged)).read(session()) }
        assertFailsWith<CloudFailure> { reader(response(cipher, itemId = "other")).read(session()) }
        assertFailsWith<CloudFailure> { reader(response(cipher, duplicate = true)).read(session()) }
    }
    @Test fun refusesMissingKeyWithoutNetworkRequest() {
        val api = MyTsIdentities(CloudTransport { _, _, _, _ -> error("unexpected network") })
        assertFailsWith<CloudFailure> { api.read(MyTsAccount.Session("fixture", "sample")) }
    }
    @Test fun automaticIdentityRequiresExactlyOneMatchingName() {
        val cipher = blob(plaintext("default"))
        val identity = reader(response(cipher)).defaultIdentity(session())
        assertEquals(fixtureIdentity.uid, identity.uid)
        val empty = CloudProto.integer(1, 301) + CloudProto.bytes(2, CloudProto.integer(1, 1))
        assertEquals("IDENTITY_DEFAULT_UNAVAILABLE", assertFailsWith<CloudFailure> { reader(empty).defaultIdentity(session()) }.code)
        assertEquals("IDENTITY_DEFAULT_UNAVAILABLE", assertFailsWith<CloudFailure> { reader(response(blob(plaintext("默认")))).defaultIdentity(session()) }.code)
        assertEquals("IDENTITY_DEFAULT_UNAVAILABLE", assertFailsWith<CloudFailure> { reader(response(blob(plaintext("defult")))).defaultIdentity(session()) }.code)
        val other = "00000000-0000-0000-0000-000000000456"
        val otherIdentity = Identity.generate(securityLevel = 0)
        fun otherPlain(name: String) = CloudProto.text(2, other) + CloudProto.integer(6, 1) + CloudProto.bytes(17,
            CloudProto.text(1, otherIdentity.export()) + CloudProto.text(2, name))
        val detail1 = CloudProto.text(1, fixtureId) + CloudProto.bytes(3, cipher)
        fun both(name: String): ByteArray {
            val detail2 = CloudProto.text(1, other) + CloudProto.bytes(3, blob(otherPlain(name)))
            return CloudProto.integer(1, 301) + CloudProto.bytes(2, CloudProto.integer(1, 1) + CloudProto.bytes(3, detail2) + CloudProto.bytes(3, detail1))
        }
        assertEquals(fixtureIdentity.uid, reader(both("默认")).defaultIdentity(session()).uid)
        assertEquals("IDENTITY_DEFAULT_UNAVAILABLE", assertFailsWith<CloudFailure> { reader(both("default")).defaultIdentity(session()) }.code)
    }
}
