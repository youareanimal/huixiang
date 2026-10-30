package io.github.korppujauho.pocketts.proto

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Raising an identity's security level: on several threads, with progress, and stopped halfway. */
class SecurityLevelTest {
    @Test
    fun raisesOnSeveralThreads() {
        val id = Identity.generate(securityLevel = 0)
        val seen = mutableListOf<Int>()
        val raised = id.improvedTo(14, threads = 4) { synchronized(seen) { seen.add(it) } }
        assertTrue(raised.securityLevel >= 14, "level ${raised.securityLevel}")
        // The same identity (the same keys): only the offset changed
        assertEquals(id.publicKeyString, raised.publicKeyString)
        assertEquals(Identity.securityLevel(raised.publicKeyString, raised.keyOffset), raised.securityLevel)
        // Each level heard is higher than the one before, ending at the result
        assertContentEquals(seen.sorted(), seen)
        assertEquals(raised.securityLevel, seen.last())
    }

    @Test
    fun stoppingKeepsTheBestSoFar() {
        val id = Identity.generate(securityLevel = 8)
        val end = System.nanoTime() + 300_000_000
        val started = System.nanoTime()
        val raised = id.improvedTo(60, cancelled = { System.nanoTime() > end }, threads = 2)
        assertTrue((System.nanoTime() - started) / 1e9 < 5, "stopped in time")
        assertTrue(raised.securityLevel >= id.securityLevel)
        assertEquals(id.publicKeyString, raised.publicKeyString)
        // What it found is still a valid offset for these keys
        assertEquals(Identity.securityLevel(raised.publicKeyString, raised.keyOffset), raised.securityLevel)
    }

    @Test
    fun alreadyHighEnough() {
        val id = Identity.generate(securityLevel = 10)
        assertEquals(id.keyOffset, id.improvedTo(5, threads = 2).keyOffset)
    }

    @Test
    fun estimates() {
        val rate = Identity.triesPerSecond(millis = 200, threads = 2)
        assertTrue(rate > 1000, "rate $rate")
        assertEquals(2.0 * Identity.secondsFor(20, rate), Identity.secondsFor(21, rate), 1e-9)
    }
}
