package io.github.korppujauho.pocketts.proto

import java.math.BigInteger
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.ECPublicKeySpec
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The Kotlin code must give exactly what WebTS's library gives (tools/make-test-vectors.mts). */
@Suppress("UNCHECKED_CAST")
class WebTsVectorsTest {
    private val v = MiniJson.parse(javaClass.getResource("/webts-vectors.json")!!.readText()) as Map<String, Any?>
    private fun Map<String, Any?>.s(k: String) = this[k] as String
    private fun Map<String, Any?>.b(k: String) = Identity.hex(this[k] as String)
    private fun Map<String, Any?>.m(k: String) = this[k] as Map<String, Any?>

    @Test
    fun eax() {
        for (e in v["eax"] as List<Map<String, Any?>>) {
            val sealed = Crypto.eaxEncrypt(e.b("key"), e.b("nonce"), e.b("header"), e.b("plain"))
            assertContentEquals(e.b("ciphertext"), sealed.ciphertext)
            assertContentEquals(e.b("mac"), sealed.mac)
            assertContentEquals(e.b("plain"), Crypto.eaxDecrypt(e.b("key"), e.b("nonce"), e.b("header"), sealed.ciphertext, sealed.mac))
            val wrongMac = sealed.mac.copyOf().also { it[0] = (it[0] + 1).toByte() }
            assertNull(Crypto.eaxDecrypt(e.b("key"), e.b("nonce"), e.b("header"), sealed.ciphertext, wrongMac))
        }
    }

    @Test
    fun keyNonce() {
        for (k in v["keyNonce"] as List<Map<String, Any?>>) {
            val kn = Crypto.deriveKeyNonce(
                k["fromServer"] as Boolean, (k["packetId"] as Long).toInt(), k["gen"] as Long, (k["type"] as Long).toInt(), k.b("iv"),
            )
            assertContentEquals(k.b("key"), kn.key)
            assertContentEquals(k.b("nonce"), kn.nonce)
        }
    }

    private fun identity(m: Map<String, Any?>) = Identity(BigInteger(m.s("scalar"), 16), m.s("offset").toLong())

    @Test
    fun identities() {
        val ids = v.m("identities")
        for (name in listOf("a", "b")) {
            val m = ids.m(name)
            val id = identity(m)
            assertEquals(m.s("publicKeyString"), id.publicKeyString)
            assertEquals(m.s("uid"), id.uid)
            assertTrue(id.securityLevel >= 8)
        }
        val a = identity(ids.m("a"))
        val b = identity(ids.m("b"))
        assertContentEquals(ids.b("sharedSecretAB"), a.sharedSecret(Base64.getDecoder().decode(b.publicKeyString)))

        // WebTS's signature checks out with our public key, and ours with the platform's verifier.
        val pub = KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(a.publicPoint, P256.params))
        val verify = { sig: ByteArray ->
            Signature.getInstance("SHA256withECDSA").run { initVerify(pub); update(ids.b("signedData")); verify(sig) }
        }
        assertTrue(verify(ids.b("signatureA")))
        assertTrue(verify(a.sign(ids.b("signedData"))))
    }

    @Test
    fun tsExportFormat() {
        val t = v.m("tsExport")
        val imported = Identity.import(t.s("exported"))
        assertEquals(BigInteger(t.s("scalar"), 16), imported.privateScalar)
        assertEquals(t.s("uid"), imported.uid)
        assertEquals(t.s("exported"), imported.export())
        // The same in the .ini file a TeamSpeak app saves
        val ini = "[Identity]\nid=Main\nidentity=\"${t.s("exported")}\"\nnickname=Jamie\n"
        assertEquals(t.s("uid"), Identity.import(ini).uid)
    }

    @Test
    fun license() {
        val l = v.m("license")
        val serverKey = License.deriveKey(License.parse(l.b("license")))
        assertContentEquals(l.b("serverKey"), serverKey)
        assertContentEquals(l.b("tempPublic"), Ed25519.scalarMulBase(l.b("tempPrivate")))
        assertContentEquals(l.b("sharedSecret"), License.sharedSecret(serverKey, l.b("tempPrivate")))
    }

    @Test
    fun files() {
        val f = v.m("files")
        assertEquals(f["avatarNames"] as List<*>, Pictures.avatarFileNames(f.s("uid")))
        assertEquals(f["serverNamed"] as List<*>, Pictures.avatarFileNames(f.s("uid"), "abcdefghijklmnopabcdefghijklmnop"))
        assertEquals(f.s("icon"), Pictures.iconFileName(3000000000))
        assertEquals(null, Pictures.iconFileName(300))
        assertEquals(f.s("pictureMessage"), Pictures.pictureMessage("2026-10-12_18-35-01_a7k3f9.jpg", 5, "/webTS")) // the same line, in its folder
    }

    @Test
    fun commands() {
        val c = v.m("cmd")
        assertEquals(
            c.s("built"),
            Commands.build("sendtextmessage", "targetmode" to 2, "target" to 1, "msg" to "Hei | maailma / \\ äö\n"),
        )
        assertEquals(c.s("hashedPassword"), Crypto.hashPassword("salainen sana ä"))
        val parsed = Commands.parse(c.s("built"))
        assertEquals("sendtextmessage", parsed.name)
        assertEquals("Hei | maailma / \\ äö\n", parsed["msg"])
        assertNotNull(parsed["target"])
    }
}
