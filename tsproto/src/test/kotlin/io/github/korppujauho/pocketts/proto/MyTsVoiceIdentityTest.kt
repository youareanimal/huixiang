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

    private fun wireInteger(field: Int, value: Long): ByteArray {
        // Protobuf uint64 fixture writer, including values above signed Long.MAX_VALUE.
        var bits = value
        val out = java.io.ByteArrayOutputStream()
        out.write(CloudProto.varint(field.toLong() shl 3))
        do {
            val low = (bits and 127).toInt()
            bits = bits ushr 7
            out.write(low or if (bits != 0L) 128 else 0)
        } while (bits != 0L)
        return out.toByteArray()
    }

    private fun session(secret: ByteArray = scalar, corrupt: Boolean = false, public: ByteArray = publicKey,
        wireTime: Long = java.lang.Long.reverseBytes(1_700_000_000L)): MyTsAccount.Session {
        val wrapped = encrypted(secret).also { if (corrupt) it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        val data = CloudProto.bytes(1, public) + CloudProto.bytes(2, CloudProto.bytes(1, wrapped)) +
            wireInteger(3, wireTime) + CloudProto.bytes(4, id) + CloudProto.bytes(5, signature)
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
            assertEquals("1700000000", fields["acTime"])
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

    @Test fun creationTimePreservesUnsignedBitsAndNeverRejectsTheHighBit() {
        // Native account reader bswaps uint64; native voice encoder uses snprintf("%llu").
        for ((wire, expected) in listOf(
            128L to "9223372036854775808",
            1_700_000_128L to "9291299199498846208",
            Long.MAX_VALUE to "18446744073709551487",
            Long.MIN_VALUE to "128",
            -1L to "18446744073709551615",
        )) {
            val identity = assertNotNull(MyTsVoiceIdentity.fromSession(session(wireTime = wire)))
            assertEquals(expected, identity.authentication(ByteArray(64))["acTime"])
        }
        assertFails { MyTsVoiceIdentity.fromSession(session(wireTime = 0)) }
    }

    @Test fun refusesCorruptCiphertextAndWrongPublicKeyWithoutExposingAccountMaterial() {
        assertFails { MyTsVoiceIdentity.fromSession(session(corrupt = true))!!.authentication(ByteArray(20)) }
        assertFails { MyTsVoiceIdentity.fromSession(session(public = ByteArray(32)))!!.authentication(ByteArray(20)) }
        assertNull(MyTsVoiceIdentity.fromSession(MyTsAccount.Session("legacy", "name")))
    }

    @Test fun clientinitActuallyCarriesOnlyPublicProofAndReconnectGetsFreshSignature() {
        val client = TsClient(object : TsClient.Listener {})
        val proof = assertNotNull(MyTsVoiceIdentity.fromSession(session(wireTime = 128L)))
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
        assertEquals("9223372036854775808", first["acTime"])
        assertContentEquals(id, Base64.getDecoder().decode(first["myTeamspeakId"]))
        assertNotNull(first["authSign"])
        assertFalse(first.toString().contains("synthetic-token"))
        assertNull(first["client_myteamspeak_id"])
        assertNotEquals(first["authSign"], command(ByteArray(64) { (it + 1).toByte() })["authSign"])
    }

    @Test fun proofConstructionNeverClaimsServerConfirmationAndOnlyOwnMatchingIdConfirms() {
        val states = mutableListOf<MyTsVoiceStatus>()
        val client = TsClient(object : TsClient.Listener {
            override fun onMyTsVoiceStatus(status: MyTsVoiceStatus) { states += status }
        })
        val proof = assertNotNull(MyTsVoiceIdentity.fromSession(session(wireTime = 128L)))
        val options = TsClient.Options("unused.invalid", identity = Identity.generate(0), nickname = "Fixture", myTsIdentity = proof)
        fun field(name: String, value: Any) {
            TsClient::class.java.getDeclaredField(name).apply { isAccessible = true }.set(client, value)
        }
        field("opts", options)
        TsClient::class.java.getDeclaredMethod("startEncryption", ByteArray::class.java).apply {
            isAccessible = true
        }.invoke(client, ByteArray(64))
        assertEquals(listOf(MyTsVoiceStatus.PROOF_SENT), states)
        field("state", TsClient.State.CONNECTED)
        field("clientId", 7)
        val process = TsClient::class.java.getDeclaredMethod("processCommand", ByteArray::class.java, Int::class.javaPrimitiveType).apply { isAccessible = true }
        fun feed(command: String) { process.invoke(client, command.toByteArray(Charsets.UTF_8), 0) }
        val encodedId = Base64.getEncoder().encodeToString(id)
        feed(Commands.build("clientvariables", "clid" to 8, "client_myteamspeak_id" to encodedId))
        feed("error id=0 return_code=quiet-myts-verify")
        feed("clientvariables clid=7 client_nickname=Fixture")
        assertEquals(listOf(MyTsVoiceStatus.PROOF_SENT, MyTsVoiceStatus.SERVER_NOT_CONFIRMED), states)
        feed("clientvariables clid=7 client_myteamspeak_id=")
        assertEquals(MyTsVoiceStatus.SERVER_NOT_CONFIRMED, states.last())
        feed(Commands.build("clientvariables", "clid" to 7, "client_myteamspeak_id" to encodedId))
        assertEquals(MyTsVoiceStatus.SERVER_CONFIRMED, states.last())
        feed("error id=0 return_code=quiet-myts-verify")
        assertEquals(MyTsVoiceStatus.SERVER_CONFIRMED, states.last())
        feed(Commands.build("notifyclientupdated", "clid" to 7, "client_myteamspeak_id" to Base64.getEncoder().encodeToString(ByteArray(33))))
        assertEquals(MyTsVoiceStatus.SERVER_NOT_CONFIRMED, states.last())
        feed("clientvariables clid=7 client_myteamspeak_id=invalid!")
        assertEquals(MyTsVoiceStatus.SERVER_NOT_CONFIRMED, states.last())
    }

    @Test fun metadataReceiptsAreCheckedAndSuccessfulMetadataRechecksIdentity() {
        val states = mutableListOf<MyTsVoiceStatus>()
        val receipts = mutableListOf<Pair<MyTsVoiceCheck, Int?>>()
        val logs = mutableListOf<String>()
        val client = TsClient(object : TsClient.Listener {
            override fun onMyTsVoiceStatus(status: MyTsVoiceStatus) { states += status }
            override fun onMyTsVoiceCheck(check: MyTsVoiceCheck, errorCode: Int?) { receipts += check to errorCode }
        }, logs::add)
        fun field(name: String, value: Any) {
            TsClient::class.java.getDeclaredField(name).apply { isAccessible = true }.set(client, value)
        }
        try {
            val proof = assertNotNull(MyTsVoiceIdentity.fromSession(session()))
            field("opts", TsClient.Options("unused.invalid", identity = Identity.generate(0), nickname = "Fixture", myTsIdentity = proof))
            TsClient::class.java.getDeclaredMethod("startEncryption", ByteArray::class.java).apply {
                isAccessible = true
            }.invoke(client, ByteArray(64))
            field("state", TsClient.State.CONNECTED)
            field("clientId", 7)
            val pending = TsClient::class.java.getDeclaredField("pending").apply { isAccessible = true }.get(client) as MutableMap<*, *>
            pending.clear()
            val process = TsClient::class.java.getDeclaredMethod("processCommand", ByteArray::class.java, Int::class.javaPrimitiveType).apply { isAccessible = true }
            fun feed(text: String) { process.invoke(client, text.toByteArray(Charsets.UTF_8), 0) }
            fun flush() {
                val executor = TsClient::class.java.getDeclaredField("exec").apply { isAccessible = true }.get(client) as java.util.concurrent.ScheduledExecutorService
                executor.submit {}.get(5, java.util.concurrent.TimeUnit.SECONDS)
            }
            feed("error id=2568 msg=synthetic-private-text return_code=quiet-myts-data")
            assertEquals(MyTsVoiceCheck.METADATA_REJECTED to 2568, receipts.last())
            assertTrue(pending.isEmpty())
            feed("error id=2568 return_code=quiet-myts-verify")
            assertEquals(MyTsVoiceCheck.QUERY_REJECTED to 2568, receipts.last())
            assertEquals(MyTsVoiceStatus.SERVER_NOT_CONFIRMED, states.last())
            feed("error id=0 return_code=quiet-myts-data")
            flush()
            assertEquals(MyTsVoiceCheck.METADATA_ACCEPTED to null, receipts.last())
            assertEquals(MyTsVoiceStatus.SERVER_NOT_CONFIRMED, states.last())
            val requests = pending.values.map { packet ->
                val raw = packet!!.javaClass.getDeclaredField("raw").apply { isAccessible = true }.get(packet) as ByteArray
                val packetId = ((raw[8].toInt() and 255) shl 8) or (raw[9].toInt() and 255)
                val kn = Crypto.deriveKeyNonce(false, packetId, 0, 2, ByteArray(64))
                Commands.parse(assertNotNull(Crypto.eaxDecrypt(kn.key, kn.nonce, raw.copyOfRange(8, 13), raw.copyOfRange(13, raw.size), raw.copyOfRange(0, 8))).toString(Charsets.UTF_8))
            }
            assertTrue(requests.any { it.name == "clientgetvariables" && it["clid"] == "7" && it["return_code"] == "quiet-myts-recheck" })
            feed("error id=0 return_code=quiet-myts-recheck")
            assertEquals(MyTsVoiceCheck.QUERY_NO_ID to null, receipts.last())
            feed("notifyclientupdated clid=7 client_myteamspeak_id=")
            assertEquals(MyTsVoiceCheck.ID_ABSENT to null, receipts.last())
            feed(Commands.build("notifyclientupdated", "clid" to 7, "client_myteamspeak_id" to Base64.getEncoder().encodeToString(id)))
            assertEquals(MyTsVoiceStatus.SERVER_CONFIRMED, states.last())
            assertEquals(MyTsVoiceCheck.ID_MATCHED to null, receipts.last())
            feed("error id=0 return_code=quiet-myts-recheck")
            assertEquals(MyTsVoiceStatus.SERVER_CONFIRMED, states.last())
            assertTrue(logs.none { "synthetic-private-text" in it || "synthetic-token" in it })
        } finally { client.close() }
    }

}
