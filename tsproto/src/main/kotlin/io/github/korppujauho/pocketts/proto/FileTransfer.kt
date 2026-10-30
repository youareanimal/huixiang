package io.github.korppujauho.pocketts.proto

import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutionException
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

/**
 * TeamSpeak's file browser: folders, uploads and downloads (pictures in the chat, icons, avatars), as WebTS does it.
 * The request goes over the connection (ftinitupload / ftinitdownload); the server answers with a key and a port, and
 * the bytes go over a TCP connection to that port: first the key, then the file.
 *
 * The calls block, so they belong on a background thread (never the connection's). Feed every command to [onCommand].
 */
class FileTransfer(
    private val client: TsClient,
    /** Where the bytes go: normally the server's own address, and the port it names unless [portOverride] is set. */
    private val portOverride: Int? = null,
) {
    class Error(message: String, val code: Int = 0) : Exception(message)

    companion object {
        const val FILE_EXISTS = 2050
        const val FILE_NOT_FOUND = 2051
        const val WRONG_PASSWORD = 781
        const val NO_PERMISSION = 2568
        const val INVALID_PATH = 2054 // e.g. deleting a name that isn't there
        private const val EMPTY = 1281 // "database empty result set": an empty folder
        private const val ANSWER_MS = 15_000L
        private const val TRANSFER_MS = 120_000
    }

    private class Waiter(val pick: (Command) -> Any?, val result: CompletableFuture<Any?>)

    private val waiters = CopyOnWriteArrayList<Waiter>()
    private val counter = AtomicInteger(0)
    private val slots = Semaphore(4) // a few at once, like WebTS

    private fun nextId() = counter.updateAndGet { (it % 65535) + 1 }

    /** Every command from the server (any thread). */
    fun onCommand(cmd: Command) {
        for (w in waiters) {
            try {
                val v = w.pick(cmd) ?: continue
                w.result.complete(v)
            } catch (e: Exception) {
                w.result.completeExceptionally(e)
            }
        }
    }

    fun onDisconnected() {
        for (w in waiters) w.result.completeExceptionally(Error("The connection closed"))
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> await(command: String, pick: (Command) -> T?): T {
        val w = Waiter(pick, CompletableFuture())
        waiters.add(w)
        try {
            client.sendCommand(command)
            return w.result.get(ANSWER_MS, TimeUnit.MILLISECONDS) as T
        } catch (e: TimeoutException) {
            throw Error("TeamSpeak didn't answer")
        } catch (e: ExecutionException) {
            throw (e.cause as? Error) ?: Error(e.cause?.message ?: "failed")
        } finally {
            waiters.remove(w)
        }
    }

    private fun answer(cmd: Command, returnCode: String): Pair<Int, String>? {
        if (cmd.name != "error" || cmd["return_code"] != returnCode) return null
        return (cmd["id"]?.toIntOrNull() ?: 0) to cmd["msg"].orEmpty()
    }

    /** A file or folder in a channel's files; [time] in milliseconds (0 when the server gave none). */
    data class Entry(val name: String, val folder: Boolean, val size: Long, val time: Long)

    /**
     * What's in a folder of channel [cid]'s files ([path] "/" for the top). An empty folder is an empty list (the server
     * says "empty result set"); a missing one, a wrong password or no permission is an [Error] with the server's code.
     * [cpw] is the channel's password hashed, as when joining.
     */
    fun list(cid: Int, path: String, cpw: String = ""): List<Entry> {
        val rc = "ft-${nextId()}"
        val found = ArrayList<Entry>()
        var ours = false // the list's lines come in batches; the first says which folder it is
        await(Commands.build("ftgetfilelist", "cid" to cid, "cpw" to cpw, "path" to path, "return_code" to rc)) { c ->
            when (c.name) {
                "notifyfilelist" -> {
                    val first = c.groups.firstOrNull()
                    if (first?.get("cid") != null) ours = first["cid"] == "$cid" && first["path"] == path
                    if (ours) for (g in c.groups) entry(g)?.let { found.add(it) }
                    null
                }
                "notifyfilelistfinished" -> if (c["cid"] == "$cid" && c["path"] == path) true else null
                else -> answer(c, rc)?.let { (id, msg) ->
                    when (id) {
                        0 -> null // the list itself ends with notifyfilelistfinished
                        EMPTY -> true
                        else -> throw Error(msg.ifEmpty { "refused" }, id)
                    }
                }
            }
        }
        return found
    }

    private fun entry(g: Map<String, String>): Entry? {
        val name = g["name"]?.takeIf { it.isNotEmpty() } ?: return null
        // TeamSpeak 6 gives milliseconds, TeamSpeak 3 seconds
        val time = g["datetime"]?.toLongOrNull()?.let { if (it > 100_000_000_000L) it else it * 1000 } ?: 0
        return Entry(name, folder = g["type"] == "0", size = g["size"]?.toLongOrNull() ?: 0, time = time)
    }

    /** A folder in a channel's files ("/PocketTS"); one that already exists is fine. */
    fun createDirectory(cid: Int, dirname: String, cpw: String = "") {
        val rc = "ft-${nextId()}"
        val (id, msg) = await(Commands.build("ftcreatedir", "cid" to cid, "cpw" to cpw, "dirname" to dirname, "return_code" to rc)) { answer(it, rc) }
        if (id != 0 && id != FILE_EXISTS) throw Error(msg.ifEmpty { "refused" }, id)
    }

    private class Start(val serverftfid: String, val key: String, val port: Int, val size: Long)

    private fun start(command: String, params: List<Pair<String, Any?>>, started: String): Start {
        val ftid = nextId()
        val rc = "ft-${nextId()}"
        val cmd = Commands.build(command, *(listOf("clientftfid" to ftid) + params + ("return_code" to rc)).toTypedArray())
        return await(cmd) { c ->
            val p = c.params
            when {
                c.name == started && p["clientftfid"]?.toIntOrNull() == ftid -> {
                    val port = p["port"]?.toIntOrNull() ?: 0
                    val key = p["ftkey"].orEmpty()
                    if (key.isEmpty() || port !in 1..65535) throw Error("TeamSpeak gave no transfer address")
                    Start(p["serverftfid"].orEmpty(), key, port, p["size"]?.toLongOrNull() ?: 0)
                }
                c.name == "notifystatusfiletransfer" && p["clientftfid"]?.toIntOrNull() == ftid ->
                    throw Error(p["msg"].orEmpty().ifEmpty { "refused" }, p["status"]?.toIntOrNull() ?: 0)
                else -> answer(c, rc)?.let { (id, msg) -> if (id != 0) throw Error(msg.ifEmpty { "refused" }, id) else null }
            }
        }
    }

    private fun socket(port: Int): Socket {
        val host = client.serverAddress ?: throw Error("Not connected")
        return Socket().apply {
            connect(InetSocketAddress(host, portOverride ?: port), 10_000)
            soTimeout = TRANSFER_MS
        }
    }

    /** Downloads [name] (a full path) from channel [cid]'s files; refused before reading when over [maxBytes]. */
    fun download(cid: Int, name: String, maxBytes: Long, cpw: String = ""): ByteArray {
        val out = ByteArrayOutputStream()
        download(cid, name, out, cpw, maxBytes)
        return out.toByteArray()
    }

    class Cancelled : Exception("Cancelled")

    /**
     * Downloads [name] (a full path) from channel [cid]'s files into [out] as it comes, telling [progress] the bytes so
     * far and the size. Refused before reading when over [maxBytes]; [cancelled] stops it ([Cancelled]) between pieces.
     * Returns the size.
     */
    fun download(
        cid: Int, name: String, out: java.io.OutputStream, cpw: String = "", maxBytes: Long = Long.MAX_VALUE,
        progress: (done: Long, size: Long) -> Unit = { _, _ -> }, cancelled: () -> Boolean = { false },
    ): Long {
        slots.acquire()
        try {
            val s = start("ftinitdownload", listOf("name" to name, "cid" to cid, "cpw" to cpw, "seekpos" to 0), "notifystartdownload")
            fun stop() {
                if (s.serverftfid.isNotEmpty()) client.sendCommand(Commands.build("ftstop", "serverftfid" to s.serverftfid, "delete" to 0))
            }
            if (s.size > maxBytes) {
                stop()
                throw Error("The file is too big")
            }
            if (s.size == 0L) return 0
            socket(s.port).use { sock ->
                sock.getOutputStream().apply { write(s.key.toByteArray(Charsets.US_ASCII)); flush() }
                val input = sock.getInputStream()
                val buf = ByteArray(65536)
                var done = 0L
                progress(0, s.size)
                while (done < s.size) {
                    if (cancelled()) {
                        stop()
                        throw Cancelled()
                    }
                    val n = input.read(buf, 0, minOf(buf.size.toLong(), s.size - done).toInt())
                    if (n < 0) throw Error("The download connection closed early")
                    out.write(buf, 0, n)
                    done += n
                    progress(done, s.size)
                }
                return s.size
            }
        } finally {
            slots.release()
        }
    }

    /** Uploads [data] as [name] (a full path) into channel [cid]'s files (channel 0: the server's own, e.g. "/avatar"). */
    fun upload(cid: Int, name: String, data: ByteArray, cpw: String = "", overwrite: Boolean = false) =
        upload(cid, name, data.inputStream(), data.size.toLong(), cpw, overwrite)

    /**
     * Uploads [size] bytes from [input] as [name] (a full path) into channel [cid]'s files, telling [progress] the bytes
     * sent so far. A name already there is refused ([FILE_EXISTS]) unless [overwrite]; [cancelled] stops it
     * ([Cancelled]) between pieces, and the server drops the part it got.
     */
    fun upload(
        cid: Int, name: String, input: java.io.InputStream, size: Long, cpw: String = "", overwrite: Boolean = false,
        progress: (done: Long, size: Long) -> Unit = { _, _ -> }, cancelled: () -> Boolean = { false },
    ) {
        slots.acquire()
        try {
            val s = start(
                "ftinitupload",
                listOf("name" to name, "cid" to cid, "cpw" to cpw, "size" to size, "overwrite" to if (overwrite) 1 else 0, "resume" to 0),
                "notifystartupload",
            )
            socket(s.port).use { sock ->
                val out = sock.getOutputStream()
                out.write(s.key.toByteArray(Charsets.US_ASCII))
                val buf = ByteArray(65536)
                var done = 0L
                progress(0, size)
                while (done < size) {
                    if (cancelled()) {
                        if (s.serverftfid.isNotEmpty()) client.sendCommand(Commands.build("ftstop", "serverftfid" to s.serverftfid, "delete" to 1))
                        throw Cancelled()
                    }
                    val n = input.read(buf, 0, minOf(buf.size.toLong(), size - done).toInt())
                    if (n < 0) throw Error("The file ended early")
                    out.write(buf, 0, n)
                    done += n
                    progress(done, size)
                }
                out.flush()
                sock.shutdownOutput()
                // Wait for the server to close its side (it has everything then)
                try {
                    while (sock.getInputStream().read() >= 0) Unit
                } catch (_: Exception) {
                }
            }
        } finally {
            slots.release()
        }
    }

    /** Deletes [name] (a full path) from channel [cid]'s files; a folder goes with everything in it. */
    fun delete(cid: Int, name: String, cpw: String = "") {
        val rc = "ft-${nextId()}"
        val (id, msg) = await(Commands.build("ftdeletefile", "cid" to cid, "cpw" to cpw, "name" to name, "return_code" to rc)) { answer(it, rc) }
        if (id != 0) throw Error(msg.ifEmpty { "refused" }, id)
    }

    /**
     * Renames (or moves, with another folder in [to]) [from] to [to], both full paths in channel [cid]'s files. The
     * server replaces a file already called [to] without a word: check first.
     */
    fun rename(cid: Int, from: String, to: String, cpw: String = "") {
        val rc = "ft-${nextId()}"
        val cmd = Commands.build("ftrenamefile", "cid" to cid, "cpw" to cpw, "tcid" to cid, "tcpw" to cpw, "oldname" to from, "newname" to to, "return_code" to rc)
        val (id, msg) = await(cmd) { answer(it, rc) }
        if (id != 0) throw Error(msg.ifEmpty { "refused" }, id)
    }
}
