package io.github.korppujauho.pocketts.proto

import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import kotlin.test.*

class MyTsVoiceIdentityTest {
    private fun hex(value: String) = Identity.hex(value)
    private val scalar = ByteArray(32).also { it[0] = 7 }
    private val key = ByteArray(32) { (it + 30).toByte() }
    private val publicKey = hex("b862409fb5c4c4123df2abf7462b88f041ad36dd6864ce872fd5472be363c5b1")
    private val id = ByteArray(33) { (it + 1).toByte() }
    private val signature = ByteArray(100) { (it + 2).toByte() }

    private fun encrypted(secret: ByteArray): ByteArray {
        val iv = ByteArray(16) { it.toByte() }
        val counter = iv.copyOf()
        val aes = Cipher.getInstance("AES/ECB/NoPadding")
        aes.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
        val cipher = ByteArray(secret.size)
        for (offset in secret.indices step 16) {
            val stream = aes.doFinal(counter)
            for (j in 0 until minOf(16, secret.size - offset)) cipher[offset + j] = (secret[offset + j].toInt() xor stream[j].toInt()).toByte()
            for (j in counter.indices) { counter[j] = (counter[j] + 1).toByte(); if (counter[j] != 0.toByte()) break }
        }
        return iv + cipher + Crypto.sha512(secret)
    }

    private fun session(secret: ByteArray = scalar, corrupt: Boolean = false, public: ByteArray = publicKey): MyTsAccount.Session {
        val wrapped = encrypted(secret).also { if (corrupt) it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        val data = CloudProto.bytes(1, public) + CloudProto.bytes(2, CloudProto.bytes(1, wrapped)) +
            CloudProto.integer(3, java.lang.Long.reverseBytes(1_700_000_000L)) + CloudProto.bytes(4, id) + CloudProto.bytes(5, signature)
        return MyTsAccount.Session("synthetic-token", "Fixture", key,
            voiceMaterial = MyTsVoiceMaterial.fromLogin(CloudProto.read(CloudProto.bytes(9, data))))
    }

    @Test fun matchesOfficialBeta5NativeScalarSigningVectors() {
        // Public binary SHA256 1189672c2c290997a419100b0f29f4da95d0df9d1e744b07a764eba1e8a0944f.
        // Actions run 41004024803 compared independent synthetic vectors to native routine 0x474bab0.
        assertContentEquals(hex("5a5d1a747039603867971d0d66379aa878ac5e4fc4032559cf89ae55431b7521e3bc23f957d1194f9a4b984d0249e42735797c6c569ad6542237d45decd5f202"),
            MyTsVoiceIdentity.scalarSign(hex("0100000000000000000000000000000000000000000000000000000000000000"), hex("5866666666666666666666666666666666666666666666666666666666666666"), hex("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f4d795465616d537065616b4944")))
        assertContentEquals(hex("d56692d660be0ee119d4bb240bc1b22338d83a787ee9c395864ab9aba36e8e5f02ed3ba716cb26680da14555f17b6486173d8735bb9377a74de8356197ab880b"),
            MyTsVoiceIdentity.scalarSign(hex("0700000000000000000000000000000000000000000000000000000000000000"), hex("b862409fb5c4c4123df2abf7462b88f041ad36dd6864ce872fd5472be363c5b1"), hex("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f4d795465616d537065616b4944")))
        assertContentEquals(hex("584da7caa139b440f1bb8e5c12752647e070a1b8f4c2e3017467b69b741e73d416694d0f67bbefe91aa03db95ca917e5bfdc5790a480697139e1330e234d1a0b"),
            MyTsVoiceIdentity.scalarSign(hex("efcdab8967452301000000000000000000000000000000000000000000000000"), hex("4007da55933fc4e347bc451b387cfef6b0dfec7b14da5aab1738f47c52f77b70"), hex("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f4d795465616d537065616b4944")))
    }

    @Test fun decryptsScalarAndBindsProofToEachConnectionForBothHandshakeFormats() {
        val proof = assertNotNull(MyTsVoiceIdentity.fromSession(session()))
        for (size in listOf(20, 64)) {
            val iv = ByteArray(size) { it.toByte() }
            val fields = proof.authentication(iv)
            assertEquals(setOf("myTeamspeakId", "acTime", "userPubKey", "authSign", "pubSign", "pubSignCert"), fields.keys)
            assertEquals(1_700_000_000L, fields["acTime"])
            val decoder = Base64.getDecoder()
            assertContentEquals(id, decoder.decode(fields["myTeamspeakId"] as String))
            assertContentEquals(publicKey, decoder.decode(fields["userPubKey"] as String))
            assertContentEquals(signature.copyOfRange(0, 64), decoder.decode(fields["pubSign"] as String))
            assertContentEquals(signature.copyOfRange(64, 100), decoder.decode(fields["pubSignCert"] as String))
            assertEquals(64, decoder.decode(fields["authSign"] as String).size)
            assertNotEquals(fields["authSign"], proof.authentication(iv.also { it[0] = 99 })["authSign"])
        }
        assertEquals("MyTsVoiceIdentity(redacted)", proof.toString())
    }

    @Test fun refusesCorruptCiphertextAndWrongPublicKeyWithoutExposingAccountMaterial() {
        assertFails { MyTsVoiceIdentity.fromSession(session(corrupt = true))!!.authentication(ByteArray(20)) }
        assertFails { MyTsVoiceIdentity.fromSession(session(public = ByteArray(32)))!!.authentication(ByteArray(20)) }
        assertNull(MyTsVoiceIdentity.fromSession(MyTsAccount.Session("legacy", "name")))
    }

    @Test fun clientinitActuallyCarriesOnlyPublicProofAndReconnectGetsFreshSignature() {
        val client = TsClient(object : TsClient.Listener {})
        val proof = assertNotNull(MyTsVoiceIdentity.fromSession(session()))
        val options = TsClient.Options("unused.invalid", identity = Identity.generate(0), nickname = "Fixture", myTsIdentity = proof)
        TsClient::class.java.getDeclaredField("opts").apply { isAccessible = true }.set(client, options)
        val start = TsClient::class.java.getDeclaredMethod("startEncryption", ByteArray::class.java).apply { isAccessible = true }
        fun command(iv: ByteArray): Command {
            val pending = TsClient::class.java.getDeclaredField("pending").apply { isAccessible = true }.get(client) as MutableMap<*, *>
            pending.clear()
            start.invoke(client, iv)
            val chunks = pending.values.map { packet ->
                val raw = packet!!.javaClass.getDeclaredField("raw").apply { isAccessible = true }.get(packet) as ByteArray
                val packetId = ((raw[8].toInt() and 255) shl 8) or (raw[9].toInt() and 255)
                val kn = Crypto.deriveKeyNonce(false, packetId, 0, 2, iv)
                packetId to assertNotNull(Crypto.eaxDecrypt(kn.key, kn.nonce, raw.copyOfRange(8, 13), raw.copyOfRange(13, raw.size), raw.copyOfRange(0, 8)))
            }.sortedBy { it.first }
            return Commands.parse(chunks.fold(ByteArray(0)) { bytes, part -> bytes + part.second }.toString(Charsets.UTF_8))
        }
        val first = command(ByteArray(64) { it.toByte() })
        assertEquals("clientinit", first.name)
        assertEquals("Fixture", first["client_nickname"])
        assertEquals("1700000000", first["acTime"])
        assertContentEquals(id, Base64.getDecoder().decode(first["myTeamspeakId"]))
        assertNotNull(first["authSign"])
        assertFalse(first.toString().contains("synthetic-token"))
        assertNull(first["client_myteamspeak_id"])
        assertNotEquals(first["authSign"], command(ByteArray(64) { (it + 1).toByte() })["authSign"])
    }
}
