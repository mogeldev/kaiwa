package com.kaiwa.chat

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException

/** One conversation's identity, as shown in the chat list. */
data class ChatMeta(val id: String, val title: String, val updated: Long)

/**
 * Conversations on disk: an index plus one file per chat.
 *
 * One file per chat rather than a single document, so sending a message rewrites only
 * the conversation it belongs to. On a Snapdragon 210 that keeps every save small and
 * bounded, and a chat that gets corrupted cannot take the others down with it.
 */
object ChatStore {

    /**
     * Upper bound on how much history we keep. This bounds both the files and the size
     * of every request, which matters on a 1 GB device.
     */
    const val MAX_MESSAGES = 40

    /** Placeholder title until the first message gives the chat a better name. */
    const val UNTITLED = "New chat"

    private const val DIR = "chats"
    private const val INDEX_FILE = "index.json"
    private const val CHAT_SUFFIX = ".json"
    private const val LEGACY_FILE = "conversation.json"
    private const val TITLE_MAX = 38

    /**
     * The index as last read or written. The app is one process and this object the only
     * thing that writes the index, so once it has been read the file only ever needs
     * writing: a sent message used to read and parse it three times over, on the UI thread
     * of a Snapdragon 210. Everything here runs on the main thread.
     */
    private var index: List<ChatMeta>? = null

    /** Whether the chats directory is known to exist, so it is not looked up on every call. */
    private var dirReady = false

    private fun dir(context: Context): File {
        val d = File(context.filesDir, DIR)
        if (!dirReady) {
            if (!d.exists()) d.mkdirs()
            dirReady = true
        }
        return d
    }

    private fun chatFile(context: Context, id: String) = File(dir(context), "$id$CHAT_SUFFIX")

    private fun indexFile(context: Context) = File(dir(context), INDEX_FILE)

    // ---- files ----

    /**
     * A file's text, or null when there is no such file. Read through [AtomicFile], which
     * puts back the last complete version if a write to it was cut off halfway.
     */
    private fun read(file: File): String? = try {
        AtomicFile(file).readFully().toString(Charsets.UTF_8)
    } catch (e: FileNotFoundException) {
        null
    }

    /**
     * Replaces a file's text in one step. A plain writeText() truncates first, so a kill or a
     * full disk halfway through leaves an empty file behind - and an empty index reads as "no
     * chats", which the next save then makes permanent.
     */
    private fun write(file: File, text: String) {
        val atomic = AtomicFile(file)
        val out = atomic.startWrite()
        try {
            out.write(text.toByteArray(Charsets.UTF_8))
            atomic.finishWrite(out)
        } catch (e: Exception) {
            atomic.failWrite(out)
            throw e
        }
    }

    // ---- the index ----

    /** Newest activity first. */
    fun listChats(context: Context): List<ChatMeta> {
        index?.let { return it }
        val text = try {
            read(indexFile(context))
        } catch (e: Exception) {
            null
        }
        // A missing or unreadable index means the index was lost, not that the chats were:
        // their files are still there. Handing back an empty list would let the next save
        // write an index of one new chat over them, so they are listed again from the files.
        val chats = text?.let { parseIndex(it) } ?: return rebuildIndex(context)
        return chats.sortedByDescending { it.updated }.also { index = it }
    }

    private fun parseIndex(json: String): List<ChatMeta>? = try {
        val arr = JSONArray(json)
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optString("id")
            if (id.isBlank()) null
            else ChatMeta(id, o.optString("title", UNTITLED), o.optLong("updated", 0L))
        }
    } catch (e: Exception) {
        null
    }

    /**
     * The index again, from the chat files themselves: every file is a chat, named after the
     * first thing the user said in it and dated by when it was last written.
     */
    private fun rebuildIndex(context: Context): List<ChatMeta> {
        val files = dir(context).listFiles().orEmpty()
            .filter { it.name.endsWith(CHAT_SUFFIX) && it.name != INDEX_FILE }
        if (files.isEmpty()) return emptyList()

        val chats = files.map { file ->
            val id = file.name.removeSuffix(CHAT_SUFFIX)
            val first = loadMessages(context, id).firstOrNull { it.isUser }?.content
            ChatMeta(id, first?.let { titleFrom(it) } ?: UNTITLED, file.lastModified())
        }.sortedByDescending { it.updated }

        writeIndex(context, chats)
        return chats
    }

    /** Memory first, then the file: the file can fail, and the list on screen should not. */
    private fun writeIndex(context: Context, chats: List<ChatMeta>) {
        val sorted = chats.sortedByDescending { it.updated }
        index = sorted
        try {
            val arr = JSONArray()
            sorted.forEach { c ->
                arr.put(JSONObject().put("id", c.id).put("title", c.title).put("updated", c.updated))
            }
            write(indexFile(context), arr.toString())
        } catch (e: Exception) {
            // Losing the index is not worth crashing over.
        }
    }

    // ---- lifecycle ----

    /** Listed first, then given its empty file - one index write, not the two a save costs. */
    fun createChat(context: Context): ChatMeta {
        val id = uniqueId(context)
        val chat = ChatMeta(id, UNTITLED, System.currentTimeMillis())
        writeIndex(context, listChats(context) + chat)
        try {
            write(chatFile(context, id), "[]")
        } catch (e: Exception) {
            // A chat with no file yet loads as empty, which is what it is.
        }
        return chat
    }

    /** Whether [id] is still a chat, rather than one deleted since it was last looked at. */
    fun exists(context: Context, id: String): Boolean = listChats(context).any { it.id == id }

    fun deleteChat(context: Context, id: String) {
        AtomicFile(chatFile(context, id)).delete()
        writeIndex(context, listChats(context).filterNot { it.id == id })
    }

    /** Guarantees there is always somewhere to type, and upgrades the old single chat. */
    fun ensureChatExists(context: Context): List<ChatMeta> {
        migrateLegacy(context)
        val chats = listChats(context)
        return if (chats.isEmpty()) listOf(createChat(context)) else chats
    }

    /** Names an untitled chat after its first user message. No-op once it has a name. */
    fun nameFromFirstMessage(context: Context, id: String, text: String) {
        val chats = listChats(context)
        val chat = chats.firstOrNull { it.id == id } ?: return
        if (chat.title != UNTITLED) return

        val title = titleFrom(text)
        if (title == UNTITLED) return

        writeIndex(context, chats.map { if (it.id == id) it.copy(title = title) else it })
    }

    /**
     * A chat's list label: the first line of a message, trimmed to fit. The cut moves one
     * character earlier when it would fall inside an emoji: half of a surrogate pair is no
     * character at all, and the list showed it as "?".
     */
    fun titleFrom(text: String): String {
        val line = text.trim().lineSequence().firstOrNull().orEmpty().trim()
        if (line.isEmpty()) return UNTITLED
        if (line.length <= TITLE_MAX) return line

        var end = TITLE_MAX - 1
        if (Character.isHighSurrogate(line[end - 1])) end--
        return line.substring(0, end).trimEnd() + "\u2026"
    }

    // ---- messages ----

    fun loadMessages(context: Context, id: String): MutableList<Message> {
        return try {
            val text = read(chatFile(context, id)) ?: return mutableListOf()
            parse(text)
        } catch (e: Exception) {
            // A corrupt conversation should not stop the app from starting.
            mutableListOf()
        }
    }

    fun saveMessages(context: Context, id: String, messages: List<Message>) {
        try {
            // A deleted chat stays deleted. A reply still on its way when its chat was deleted
            // would otherwise write the conversation back to disk - as a file no index lists,
            // so nothing would ever show it again, or remove it.
            val chats = listChats(context)
            if (chats.none { it.id == id }) return

            val kept = messages
                .filter { !it.pending && !it.isError && it.content.isNotBlank() }
                .takeLast(MAX_MESSAGES)
            val arr = JSONArray()
            kept.forEach { m ->
                    val o = JSONObject().put("role", m.role).put("content", m.content)
                    // Written only when there is something to write, so a file saved by a
                    // build that knew nothing of these differs by nothing but the keys.
                    if (m.time > 0) o.put("time", m.time)
                    if (m.tokensIn > 0 || m.tokensOut > 0) {
                        o.put("tokensIn", m.tokensIn).put("tokensOut", m.tokensOut)
                    }
                    if (m.reasoning.isNotEmpty()) o.put("reasoning", m.reasoning)
                    arr.put(o)
                }
            write(chatFile(context, id), arr.toString())

            // Marks the chat as just used, which is what orders the list - and names an
            // untitled one after the first thing said in it, in the same write, so no title
            // ever has to be typed and the index is written once per save.
            val now = System.currentTimeMillis()
            val said = kept.firstOrNull { it.isUser }?.content
            writeIndex(context, chats.map { chat ->
                when {
                    chat.id != id -> chat
                    chat.title == UNTITLED && said != null ->
                        chat.copy(updated = now, title = titleFrom(said))
                    else -> chat.copy(updated = now)
                }
            })
        } catch (e: Exception) {
            // Losing a save is not worth crashing over.
        }
    }

    private fun parse(json: String): MutableList<Message> {
        val arr = JSONArray(json)
        val out = mutableListOf<Message>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val role = o.optString("role")
            val content = o.optString("content")
            if (role.isNotBlank() && content.isNotBlank()) {
                out.add(
                    Message(
                        role = role,
                        content = content,
                        time = o.optLong("time", 0L),
                        tokensIn = o.optInt("tokensIn", 0),
                        tokensOut = o.optInt("tokensOut", 0),
                        reasoning = o.optString("reasoning")
                    )
                )
            }
        }
        return out
    }

    /**
     * Before this version there was a single conversation.json. Fold it into the first
     * chat so an upgrade does not lose anything, then rename it out of the way rather
     * than deleting it.
     */
    private fun migrateLegacy(context: Context) {
        val legacy = File(context.filesDir, LEGACY_FILE)
        if (!legacy.exists()) return
        try {
            val messages = parse(legacy.readText())
            if (messages.isNotEmpty() && listChats(context).isEmpty()) {
                val chat = createChat(context)
                saveMessages(context, chat.id, messages)
                val firstUser = messages.firstOrNull { it.isUser }?.content
                    ?: messages.first().content
                nameFromFirstMessage(context, chat.id, firstUser)
            }
        } catch (e: Exception) {
            // A broken legacy file must not stop the app from starting.
        } finally {
            legacy.renameTo(File(context.filesDir, "$LEGACY_FILE.migrated"))
        }
    }

    private fun uniqueId(context: Context): String {
        val base = System.currentTimeMillis().toString()
        var id = base
        var n = 1
        while (chatFile(context, id).exists()) {
            id = "$base-$n"
            n++
        }
        return id
    }
}
