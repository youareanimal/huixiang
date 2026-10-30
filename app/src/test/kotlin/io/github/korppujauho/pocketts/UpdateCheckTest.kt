package io.github.korppujauho.pocketts

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckTest {
    @Test
    fun versions() {
        assertTrue(UpdateCheck.newer("0.2.0", "0.1.0"))
        assertTrue(UpdateCheck.newer("0.10.0", "0.9.2"))
        assertTrue(UpdateCheck.newer("1.0", "0.9.9"))
        assertTrue(UpdateCheck.newer("0.1.1", "0.1.0-debug"))
        assertFalse(UpdateCheck.newer("0.1.0", "0.1.0"))
        assertFalse(UpdateCheck.newer("0.1.0", "0.1.0-debug"))
        assertFalse(UpdateCheck.newer("0.1.0", "0.2.0"))
        assertFalse(UpdateCheck.newer("nonsense", "0.1.0"))
    }
}
