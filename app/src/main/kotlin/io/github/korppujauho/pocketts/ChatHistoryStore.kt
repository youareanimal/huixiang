package io.github.korppujauho.pocketts

import io.github.korppujauho.pocketts.proto.Chat
import io.github.korppujauho.pocketts.proto.ChatHistory
import java.io.File
import java.security.MessageDigest

/**
 * The chat history on this phone ([ChatHistory] has the rules): a file per server in [dir], encrypted like the other
 * secrets ([Secrets]), with the server as the label so one server's file can't be passed off as another's. The app
 * keeps it in its no-backup folder, which Android leaves out of backups and moves to a new phone. A file that can't be
 * opened (the key is gone, or it's damaged) is removed: that server's history starts over.
 */
class ChatHistoryStore(private val dir: File) {

    /** The server's saved history, trimmed to today (empty when there's none or it can't be opened). */
    fun load(server: String, now: Long): Map<Chat.Key, List<Chat.Entry>> {
        sweep(now)
        val file = file(server)
        if (!file.exists()) return emptyMap()
        return try {
            val stored = file.readText()
            // Chat is never saved unencrypted, so a plain file isn't ours
            if (!SecretBox.isSealed(stored)) throw SecretBox.Unreadable("not encrypted")
            ChatHistory.trim(ChatHistory.decode(Secrets.box.open(stored, label(server))), now)
        } catch (e: Exception) {
            AppLog.log("This server's chat history can't be opened, starting it over: ${e.javaClass.simpleName}")
            file.delete()
            emptyMap()
        }
    }

    /** Saves the server's chat as it is now (trimmed). Not saved at all when it can't be encrypted. */
    fun save(server: String, chat: Map<out Chat.Key, List<Chat.Entry>>, now: Long) {
        val kept = ChatHistory.trim(chat, now)
        val file = file(server)
        if (kept.isEmpty()) {
            file.delete()
            return
        }
        try {
            val sealed = Secrets.box.seal(ChatHistory.encode(kept), label(server))
            dir.mkdirs()
            val tmp = File(dir, file.name + ".tmp")
            tmp.writeText(sealed)
            // The old file stays whole until the new one is complete
            if (!tmp.renameTo(file)) throw java.io.IOException("rename failed")
        } catch (e: Exception) {
            AppLog.log("Couldn't save the chat history: ${e.javaClass.simpleName}")
        }
    }

    /** Every server's history. */
    fun clear() {
        dir.listFiles()?.forEach { it.delete() }
    }

    /** A server not connected to for [ChatHistory.DAYS] days has nothing left to keep. */
    private fun sweep(now: Long) {
        val limit = now - ChatHistory.DAYS * 86_400_000L
        dir.listFiles()?.filter { it.lastModified() < limit }?.forEach { it.delete() }
    }

    // The address isn't in the file's name
    private fun file(server: String): File {
        val hash = MessageDigest.getInstance("SHA-256").digest(server.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(dir, "${hash.take(32)}.chat")
    }

    private fun label(server: String) = "chat/$server"
}
