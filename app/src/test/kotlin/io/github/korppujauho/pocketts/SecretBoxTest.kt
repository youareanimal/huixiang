package io.github.korppujauho.pocketts

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** A key made in memory, standing in for the Keystore's (which the build machine doesn't have). */
class SoftwareKey : SecretBox.Key {
    private val key = SecretKeySpec(ByteArray(32).also { SecureRandom().nextBytes(it) }, "AES")

    override fun encrypt(plain: ByteArray, aad: ByteArray): ByteArray {
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv)); updateAAD(aad) }
        return iv + c.doFinal(plain)
    }

    override fun decrypt(sealed: ByteArray, aad: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, sealed, 0, 12))
            updateAAD(aad)
        }
        return c.doFinal(sealed, 12, sealed.size - 12)
    }
}

class SecretBoxTest {
    private val box = SecretBox(SoftwareKey())

    @Test
    fun sealsAndOpens() {
        val sealed = box.seal("hunter2 ä", "settings/serverPassword")
        assertTrue(SecretBox.isSealed(sealed))
        assertFalse("hunter2" in sealed)
        assertEquals("hunter2 ä", box.open(sealed, "settings/serverPassword"))
        // A fresh IV each time
        assertNotEquals(sealed, box.seal("hunter2 ä", "settings/serverPassword"))
    }

    @Test
    fun plainTextFromBeforePassesThrough() {
        assertEquals("hunter2", box.open("hunter2", "settings/serverPassword"))
        assertEquals("", box.open("", "settings/serverPassword"))
        assertEquals("", box.seal("", "settings/serverPassword"))
    }

    @Test
    fun unreadableWithAnotherKeyLabelOrChange() {
        val sealed = box.seal("hunter2", "settings/serverPassword")
        assertThrows(SecretBox.Unreadable::class.java) { SecretBox(SoftwareKey()).open(sealed, "settings/serverPassword") }
        assertThrows(SecretBox.Unreadable::class.java) { box.open(sealed, "voice/turnPassword") }
        val bytes = Base64.getDecoder().decode(sealed.removePrefix(SecretBox.PREFIX))
        bytes[14] = (bytes[14].toInt() xor 1).toByte()
        val changed = SecretBox.PREFIX + Base64.getEncoder().encodeToString(bytes)
        assertThrows(SecretBox.Unreadable::class.java) { box.open(changed, "settings/serverPassword") }
        assertThrows(SecretBox.Unreadable::class.java) { box.open(SecretBox.PREFIX + "not base64!", "settings/serverPassword") }
    }
}
