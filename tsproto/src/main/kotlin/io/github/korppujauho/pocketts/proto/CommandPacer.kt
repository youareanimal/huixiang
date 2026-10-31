package io.github.korppujauho.pocketts.proto

/**
 * Keeps commands under a server's flood limit: a few go at once, the rest a little apart. When the server still
 * says "client is flooding" (error 524), sending pauses for as long as it asks and the refused command goes again.
 *
 * Every command gets a return code (its own, or one added here) so a refusal can be matched to its command.
 * Not thread-safe: [TsClient] uses it on its own thread. Times are in milliseconds.
 */
class CommandPacer(private val burst: Int = 10, private val perSecond: Double = 4.0) {

    class Packet internal constructor(val text: String, val bytes: ByteArray? = null) {
        override fun toString() = "PacedCommand(redacted)"
    }

    private val queue = ArrayDeque<Packet>()
    private var credit = burst.toDouble()
    private var refilled = Long.MIN_VALUE
    private var pausedUntil = Long.MIN_VALUE
    private var nextCode = 0

    // What each sent command was (by its return code) until the server answers it
    private val sent = object : LinkedHashMap<String, Packet>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Packet>?) = size > 200
    }

    fun add(cmd: String) {
        queue.addLast(Packet(if (returnCode(cmd) != null) cmd else "$cmd return_code=pq-${nextCode++}"))
    }

    fun add(cmd: ByteArray) {
        // Latin-1 is only a reversible view for finding the ASCII return_code delimiter.
        // The original bytes, not this view, are retained for transport and flood retries.
        val text = cmd.toString(Charsets.ISO_8859_1)
        val suffix = if (returnCode(text) == null) " return_code=pq-${nextCode++}" else ""
        queue.addLast(Packet(text + suffix, cmd.copyOf() + suffix.toByteArray(Charsets.US_ASCII)))
    }

    /** Compatibility view for text-only callers. Binary senders use [takePackets]. */
    fun take(now: Long): List<String> = takePackets(now).map { it.text }

    /** The commands that may go [now], in order, retaining any binary payload. */
    fun takePackets(now: Long): List<Packet> {
        refill(now)
        if (now < pausedUntil) return emptyList()
        val out = ArrayList<Packet>()
        while (queue.isNotEmpty() && credit >= 1) {
            credit -= 1
            val cmd = queue.removeFirst()
            returnCode(cmd.text)?.let { sent[it] = cmd }
            out.add(cmd)
        }
        return out
    }

    /** How long until the next waiting command may go; null when none is waiting. */
    fun waitMs(now: Long): Long? {
        if (queue.isEmpty()) return null
        refill(now)
        val forCredit = if (credit >= 1) 0L else Math.ceil((1 - credit) * 1000 / perSecond).toLong()
        return maxOf(forCredit, if (now < pausedUntil) pausedUntil - now else 0L, 1L)
    }

    /**
     * An "error" answer from the server. True when it was a flood refusal of a command sent here: that command
     * waits to go again and nobody else needs to hear about it.
     */
    fun onError(cmd: Command, now: Long): Boolean {
        val code = cmd["return_code"] ?: return false
        val original = sent.remove(code) ?: return false
        if (cmd["id"] != FLOODING) return false
        pausedUntil = now + waitAsked(cmd["extra_msg"].orEmpty()) + 300
        queue.addFirst(original)
        return true
    }

    fun clear() {
        queue.clear()
        sent.clear()
    }

    private fun refill(now: Long) {
        if (refilled != Long.MIN_VALUE) credit = minOf(burst.toDouble(), credit + (now - refilled) * perSecond / 1000)
        refilled = now
    }

    companion object {
        const val FLOODING = "524"

        private val CODE = Regex("""(?:^| )return_code=(\S+)""")

        fun returnCode(cmd: String): String? = CODE.find(cmd)?.groupValues?.get(1)

        /** The wait in the server's message ("please wait 1500 ms" or "… 2 seconds"); 5 s when it doesn't say. */
        fun waitAsked(message: String): Long {
            Regex("""(\d+)\s*ms""").find(message)?.let { return it.groupValues[1].toLong() }
            Regex("""(\d+)\s*s""").find(message)?.let { return it.groupValues[1].toLong() * 1000 }
            return 5000
        }
    }
}
