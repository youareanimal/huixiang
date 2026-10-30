package io.github.korppujauho.pocketts.proto

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Checks against published values and hand-made cases. */
class BasicsTest {
    private fun hex(s: String) = Identity.hex(s)

    @Test
    fun eaxPublishedVectors() {
        // From the EAX paper (Bellare, Rogaway, Wagner), with the full 16-byte tag
        val sealed = Crypto.eaxEncrypt(
            hex("91945d3f4dcbee0bf45ef52255f095a4"), hex("becaf043b0a23d843194ba972c66debd"),
            hex("fa3bfd4806eb53fa"), hex("f7fb"), macLen = 16,
        )
        assertContentEquals(hex("19dd"), sealed.ciphertext)
        assertContentEquals(hex("5c4c9331049d0bdab0277408f67967e5"), sealed.mac)

        val empty = Crypto.eaxEncrypt(
            hex("233952dee4d5ed5f9b9c6d6ff80ff478"), hex("62ec67f9c3a4a407fcb2a8c49031a8b3"),
            hex("6bfb914fd07eae6b"), ByteArray(0), macLen = 16,
        )
        assertContentEquals(hex("e037830e8389f27b025a2d6527e79d01"), empty.mac)
    }

    @Test
    fun ed25519BasePoint() {
        val one = ByteArray(32).also { it[0] = 1 }
        assertContentEquals(hex("5866666666666666666666666666666666666666666666666666666666666666"), Ed25519.scalarMulBase(one))
        val two = ByteArray(32).also { it[0] = 2 }
        val b = Ed25519.encode(Ed25519.BASE)
        assertContentEquals(Ed25519.add(b, b), Ed25519.scalarMulBase(two))
    }

    @Test
    fun commandEscaping() {
        val text = "a b|c/d\\e\tf\ng"
        assertEquals("a\\sb\\pc\\/d\\\\e\\tf\\ng", Commands.escape(text))
        assertEquals(text, Commands.unescape(Commands.escape(text)))
    }

    @Test
    fun commandLists() {
        val c = Commands.parse("notifycliententerview ctid=5 clid=1 client_nickname=A|clid=2 client_nickname=B\\sC")
        assertEquals("notifycliententerview", c.name)
        assertEquals(2, c.groups.size)
        assertEquals("5", c["ctid"])
        assertEquals("B C", c.groups[1]["client_nickname"])
        assertEquals(null, c.groups[1]["ctid"])
    }

    @Test
    fun quickLzUncompressed() {
        // Flag 0x04 = level 1, not compressed; short header: flags, compressed size, size
        val payload = "channellist cid=1".toByteArray()
        val packed = byteArrayOf(0x04, (payload.size + 3).toByte(), payload.size.toByte()) + payload
        assertContentEquals(payload, QuickLz.decompress(packed))
    }

    @Test
    fun identityRoundTrip() {
        val id = Identity.generate(securityLevel = 8)
        assertTrue(id.securityLevel >= 8)
        val back = Identity.import(id.export())
        assertEquals(id.uid, back.uid)
        assertEquals(id.keyOffset, back.keyOffset)
        assertEquals(id.privateScalar, back.privateScalar)
    }

    @Test
    fun identityImportForms() {
        val id = Identity.generate(securityLevel = 0)
        val exported = id.export()
        // A TeamSpeak app's .ini export (Windows line ends, a byte order mark, other fields around it)
        val ini = "﻿[Identity]\r\nid=Main\r\nidentity=\"$exported\"\r\nnickname=Jamie\r\nphonetic_nickname=\r\n"
        val forms = listOf(exported, "  $exported\n", "\"$exported\"", ini, ini.replace("\r\n", "\n"), "IDENTITY = $exported")
        for (form in forms) {
            val back = Identity.import(form)
            assertEquals(id.uid, back.uid, form)
            assertEquals(id.keyOffset, back.keyOffset, form)
        }
        // Our own .ini file opens again (as it does in TeamSpeak apps)
        val own = id.exportIni("PocketTS", "Jamie \"J\"\nnext")
        assertEquals(id.uid, Identity.import(own).uid)
        assertTrue(own.startsWith("[Identity]\r\nid=PocketTS\r\n") && "nickname=Jamie  J  next\r\n" in own, own)
        for (bad in listOf("", "[Identity]\nid=Main\nnickname=Jamie\n", "hello", "\"\"", "identity=\"\"")) {
            assertFailsWith<IllegalArgumentException>(bad) { Identity.import(bad) }
        }
    }

    @Test
    fun channelOrder() {
        val m = ServerModel()
        m.apply(Commands.parse("channellist cid=1 cpid=0 channel_order=0 channel_name=First|cid=3 cpid=0 channel_order=2 channel_name=Third|cid=2 cpid=0 channel_order=1 channel_name=Second|cid=4 cpid=2 channel_order=0 channel_name=Sub"))
        m.apply(Commands.parse("notifycliententerview ctid=4 clid=7 client_nickname=Someone client_type=0|clid=8 client_nickname=Other client_type=0"))
        val tree = m.tree()
        assertEquals(listOf("First", "Second", "Third"), tree.map { it.channel.name })
        assertEquals("Sub", tree[1].children.single().channel.name)
        assertEquals(listOf("Other", "Someone"), tree[1].children.single().clients.map { it.nickname })
    }
}
