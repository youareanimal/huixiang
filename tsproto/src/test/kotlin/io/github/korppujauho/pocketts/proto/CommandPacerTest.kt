package io.github.korppujauho.pocketts.proto

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CommandPacerTest {

    @Test
    fun aFewAtOnceThenSpacedOut() {
        val p = CommandPacer(burst = 3, perSecond = 4.0)
        repeat(5) { p.add("clientupdate n=$it") }
        val first = p.take(0)
        assertEquals(3, first.size)
        assertEquals("clientupdate n=0 return_code=pq-0", first[0])
        assertEquals(250, p.waitMs(0))
        assertEquals(emptyList(), p.take(100))
        assertEquals(listOf("clientupdate n=3 return_code=pq-3"), p.take(250))
        assertEquals(1, p.take(500).size)
        assertNull(p.waitMs(500))
    }

    @Test
    fun ownReturnCodesKept() {
        val p = CommandPacer()
        p.add("requeststreaminfo clid=4 return_code=stream-info-1")
        assertEquals(listOf("requeststreaminfo clid=4 return_code=stream-info-1"), p.take(0))
        assertEquals("stream-info-1", CommandPacer.returnCode("a b=1 return_code=stream-info-1"))
        assertNull(CommandPacer.returnCode("a b=1 xreturn_code=nope"))
    }

    @Test
    fun floodRefusalPausesAndSendsAgain() {
        val p = CommandPacer(burst = 10, perSecond = 4.0)
        p.add("ftinitdownload a=1")
        p.add("ftinitdownload a=2")
        p.add("ftinitdownload a=3")
        p.take(0)
        // The first is answered, the second refused, the third is still out
        assertFalse(p.onError(Commands.parse("error id=0 msg=ok return_code=pq-0"), 10))
        val refused = Commands.parse("error id=524 msg=client\\sis\\sflooding extra_msg=please\\swait\\s1500\\sms return_code=pq-1")
        assertTrue(p.onError(refused, 20))
        p.add("clientupdate b=1")
        // Nothing goes during the wait; then the refused one first
        assertEquals(1800, p.waitMs(20))
        assertEquals(emptyList(), p.take(1000))
        assertEquals(listOf("ftinitdownload a=2 return_code=pq-1", "clientupdate b=1 return_code=pq-3"), p.take(1820))
        // An answer it never sent, or another error, is someone else's
        assertFalse(p.onError(Commands.parse("error id=524 return_code=other"), 2000))
        assertFalse(p.onError(Commands.parse("error id=2568 msg=insufficient\\sclient\\spermissions return_code=pq-2"), 2000))
    }

    @Test
    fun waitFromTheMessage() {
        assertEquals(1500, CommandPacer.waitAsked("please wait 1500 ms"))
        assertEquals(2000, CommandPacer.waitAsked("please wait 2 seconds"))
        assertEquals(5000, CommandPacer.waitAsked(""))
    }
}
