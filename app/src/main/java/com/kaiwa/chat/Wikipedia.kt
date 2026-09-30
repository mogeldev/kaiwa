package com.kaiwa.chat

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * Wikipedia, as a tool.
 *
 * Inventing things is the model's worst habit, and "what is X?" is the most common thing
 * anyone asks a phone. This answers it with a paragraph somebody else wrote.
 *
 * The search generator is what makes it work: `generator=search` finds the article and
 * returns its opening section in a single request, so a near-miss title - or a topic
 * rather than a title - still lands. Asking for an article by name alone would fail on
 * everything but an exact match, which is a lot to expect of text the model composed.
 */
object Wikipedia : Tool {

    override val name = "wikipedia"

    override fun declaration(): JSONObject = JSONObject()
        .put("type", "function")
        .put(
            "function",
            JSONObject()
                .put("name", name)
                .put(
                    "description",
                    "A short encyclopedia summary of a topic, person, place or thing. " +
                        "Prefer it to answering from memory when the facts matter."
                )
                .put(
                    "parameters",
                    JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "topic",
                                    JSONObject()
                                        .put("type", "string")
                                        .put(
                                            "description",
                                            "What to look up, in a few words. A name or a " +
                                                "title works better than a whole sentence."
                                        )
                                )
                                .put(
                                    "language",
                                    JSONObject()
                                        .put("type", "string")
                                        .put(
                                            "description",
                                            "Two-letter Wikipedia language code, for " +
                                                "example \"de\" or \"en\". Omit to use the " +
                                                "phone's own language."
                                        )
                                )
                        )
                        .put("required", JSONArray().put("topic"))
                )
        )

    override fun run(arguments: JSONObject): String {
        val topic = arguments.optString("topic").trim()
        if (topic.isEmpty()) return "Nothing was given to look up."

        // The model is asked for a code, but it is the model: anything unexpected falls
        // back to the phone's own language rather than to a request that cannot work.
        val asked = arguments.optString("language").trim().lowercase()
        val language = if (asked.matches(Regex("[a-z]{2,3}"))) {
            asked
        } else {
            Locale.getDefault().language.lowercase().ifBlank { "en" }
        }

        return try {
            summary(language, topic)
        } catch (e: Exception) {
            lookupFailed(e, "Wikipedia")
        }
    }

    private fun summary(language: String, topic: String): String {
        val url = "https://$language.wikipedia.org/w/api.php" +
            "?action=query&format=json&formatversion=2" +
            "&generator=search&gsrsearch=${urlEncode(topic)}&gsrlimit=1" +
            "&prop=extracts&exintro=1&explaintext=1"

        // formatversion=2 is why `pages` is an array here rather than an object keyed by
        // page id, which saves unpacking a single unpredictable key.
        val page = JSONObject(httpGet(url))
            .optJSONObject("query")
            ?.optJSONArray("pages")
            ?.optJSONObject(0)
            ?: return "No Wikipedia article was found for \"$topic\"."

        val text = page.optString("extract").trim()
        if (text.isEmpty()) return "No Wikipedia article was found for \"$topic\"."

        return page.optString("title").trim() + "\n\n" + text
    }
}
