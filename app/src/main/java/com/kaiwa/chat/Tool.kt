package com.kaiwa.chat

import org.json.JSONArray
import org.json.JSONObject

/**
 * Something the app can run when the model asks for it.
 *
 * **Read-only, always.** A tool result is untrusted text dropped into the model's
 * context, and it is the model that decides to call a tool at all. Anything that writes,
 * sends or deletes is out of bounds for that reason alone, however convenient it would
 * look: "new chat" and "delete this" are things the keypad already does safely, and they
 * do not need a language model's judgement in the middle.
 */
interface Tool {

    /** The name the model calls, and the name this is registered under. */
    val name: String

    /** What the model is told it can do, in the shape the API expects. */
    fun declaration(): JSONObject

    /**
     * Runs it. [arguments] is the model's own JSON, unparsed and untrusted, so a tool has
     * to tolerate anything: a missing field, a number where a string goes, an empty
     * object. The return value is the text the model will read; the caller caps its size.
     */
    fun run(arguments: JSONObject): String
}

/**
 * The tools this build has, by the name the model calls them.
 *
 * Adding one means writing a [Tool] and adding it to [all]. Config files name tools by
 * string, so nothing else has to know the set in advance - and a config naming something
 * this build does not have is rejected at import time rather than quietly ignored.
 */
object Tools {

    /**
     * How much of a tool's answer the model may see. A Wikipedia article runs to tens of
     * kilobytes and the user pays for every token of it, so silence is cheaper than
     * completeness here - and a phone screen cannot show the rest anyway.
     */
    private const val MAX_RESULT_CHARS = 2000

    val all: List<Tool> = listOf(Weather, AirQuality, Wikipedia)

    private val byName: Map<String, Tool> = all.associateBy { it.name }

    /** The names a config may ask for. */
    val names: List<String> get() = all.map { it.name }

    /** Declarations for the named tools, in registry order so the list stays stable. */
    fun declarations(enabled: Set<String>): JSONArray = JSONArray().apply {
        all.filter { it.name in enabled }.forEach { put(it.declaration()) }
    }

    /**
     * Runs the tool the model asked for, or says there is no such thing.
     *
     * Only the [enabled] ones count. A model can name any tool in its reply - a guess, or a
     * name planted in a page it was shown - and one the config never opted into must not
     * run on its say-so, so it gets the same answer as a name this build does not have.
     *
     * The cap lives here rather than in each tool, so a new one cannot forget it. It covers
     * that answer too, since the name in it is the model's own text.
     */
    fun run(name: String, arguments: JSONObject, enabled: Set<String>): String {
        val tool = byName[name]?.takeIf { name in enabled }
        val answer = if (tool == null) {
            "There is no tool called \"$name\"."
        } else {
            try {
                tool.run(arguments)
            } catch (e: Exception) {
                "The tool failed: ${e.message ?: e.javaClass.simpleName}"
            }
        }
        return if (answer.length <= MAX_RESULT_CHARS) {
            answer
        } else {
            answer.substring(0, MAX_RESULT_CHARS) + "\n[...truncated]"
        }
    }
}
