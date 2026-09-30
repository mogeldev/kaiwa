package com.kaiwa.chat

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * Imports settings from a JSON file chosen in the system file picker.
 *
 * Going through the Storage Access Framework means the file can live anywhere the user
 * can reach - Download, Documents, an SD card - and no storage permission is needed,
 * because the picker hands back a read grant for the one document that was chosen.
 *
 * Three properties are deliberate:
 *
 *  - **Partial files are fine.** Only the fields present are applied; everything else
 *    keeps its current value. A config containing just `{"model": "..."}` is valid.
 *  - **An explicit null puts a field back to its default**, so `"temperature": null`
 *    clears a value set by an earlier import and `"model": null` restores the default
 *    model. A scheme is the exception: null there is no scheme, a bare key.
 *  - **Validation happens before the first write.** A file that is malformed or has an
 *    out-of-range value changes nothing at all, rather than leaving a half-applied
 *    configuration behind.
 *
 * Voice input hangs off nested objects, because it is a different service from the chat
 * provider and needs its own URL, key and auth header: `transcribe` for the self-hosted
 * box, `transcribeCloud` for a hosted alternative, and `transcribeUse` naming the one the
 * mic sends to.
 */
object ConfigImporter {

    private const val SUPPORTED_VERSION = 1

    /** A config is a few hundred bytes; anything this size is the wrong file. */
    private const val MAX_BYTES = 256 * 1024

    data class Report(val ok: Boolean, val title: String, val message: String)

    /** A config problem worth showing the user verbatim. */
    private class Invalid(message: String) : Exception(message)

    fun importFromUri(context: Context, uri: Uri, prefs: Prefs): Report {
        val name = displayName(context, uri)

        // A missing stream and an oversized file are different failures, and the elvis
        // below used to swallow the second one: readBounded() returns null for a file over
        // the limit, which the compiler then knew could never reach the check underneath.
        val raw = try {
            val stream = context.contentResolver.openInputStream(uri)
                ?: return Report(false, "Config unreadable", "No data came back for $name.")
            stream.use { readBounded(it) }
        } catch (e: Exception) {
            return Report(false, "Config unreadable", "Could not read $name:\n${e.message}")
        }

        if (raw == null) {
            return Report(
                ok = false,
                title = "Config unreadable",
                message = "$name is larger than ${MAX_BYTES / 1024} KB, which is far too big " +
                    "for a config file."
            )
        }

        return apply(raw, name, prefs)
    }

    /** The name shown in the picker, so the report can name the file that was read. */
    fun displayName(context: Context, uri: Uri): String {
        try {
            context.contentResolver
                .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val name = cursor.getString(0)
                        if (!name.isNullOrBlank()) return name
                    }
                }
        } catch (e: Exception) {
            // Fall through to the URI itself.
        }
        return uri.lastPathSegment ?: "the selected file"
    }

    /** Reads at most [MAX_BYTES], returning null when the file is bigger than that. */
    private fun readBounded(stream: InputStream): String? {
        val out = ByteArrayOutputStream()
        val chunk = ByteArray(8 * 1024)
        while (true) {
            val read = stream.read(chunk)
            if (read < 0) break
            if (out.size() + read > MAX_BYTES) return null
            out.write(chunk, 0, read)
        }
        return out.toByteArray().toString(Charsets.UTF_8)
    }

    private fun apply(raw: String, source: String, prefs: Prefs): Report {
        val root = try {
            JSONObject(raw)
        } catch (e: Exception) {
            return Report(
                ok = false,
                title = "Config rejected",
                message = "$source is not valid JSON:\n${e.message}\n\nNothing was changed."
            )
        }

        return try {
            val plan = buildPlan(root, prefs)
            if (plan.applied.isEmpty()) {
                Report(
                    ok = false,
                    title = "Nothing to import",
                    message = "No recognised settings in $source.\n\nNothing was changed."
                )
            } else {
                plan.applyTo(prefs)
                Report(ok = true, title = "Config imported", message = plan.summary(source))
            }
        } catch (e: Invalid) {
            Report(
                ok = false,
                title = "Config rejected",
                message = "$source:\n${e.message}\n\nNothing was changed."
            )
        }
    }

    /**
     * A field that is present but explicitly null means "unset this".
     *
     * Without the check, org.json's optString() would turn `"apiKey": null` into the
     * literal string "null" rather than clearing it.
     */
    private fun isUnset(root: JSONObject, name: String): Boolean =
        root.has(name) && root.opt(name) === JSONObject.NULL

    /** The stored tools array as a line a person can read back in the import report. */
    private fun readableTools(stored: String): String {
        val array = try {
            JSONArray(stored)
        } catch (e: Exception) {
            return ""
        }
        return (0 until array.length()).joinToString(", ") { array.optString(it) }
    }

    /**
     * The merged result of overlaying the file onto the current settings. Building this
     * first is what allows everything to be checked before any of it is written.
     */
    private class Plan(
        val baseUrl: String,
        val model: String,
        val apiKey: String,
        val systemPrompt: String,
        val authHeader: String,
        val authScheme: String,
        val extraHeaders: String,
        val extraQuery: String,
        val extraBody: String,
        val chatPath: String,
        val modelsPath: String,
        val temperature: String,
        val maxTokens: String,
        val timeoutSeconds: Int,
        val allowCleartext: Boolean,
        val tools: String,
        val selfHostedVoice: Prefs.Voice,
        val cloudVoice: Prefs.Voice,
        val transcribeUse: String,
        /** What became of a retired model name, when the file or the settings held one. */
        val upgrade: Prefs.ModelUpgrade?,
        val applied: List<String>
    ) {
        fun applyTo(prefs: Prefs) {
            prefs.baseUrl = baseUrl
            prefs.model = model
            prefs.apiKey = apiKey
            prefs.systemPrompt = systemPrompt
            prefs.authHeader = authHeader
            prefs.authScheme = authScheme
            prefs.extraHeaders = extraHeaders
            prefs.extraQuery = extraQuery
            prefs.extraBody = extraBody
            prefs.chatPath = chatPath
            prefs.modelsPath = modelsPath
            prefs.temperature = temperature
            prefs.maxTokens = maxTokens
            prefs.timeoutSeconds = timeoutSeconds
            prefs.allowCleartext = allowCleartext
            prefs.tools = tools
            prefs.selfHostedVoice = selfHostedVoice
            prefs.cloudVoice = cloudVoice
            prefs.transcribeUse = transcribeUse
        }

        fun summary(source: String): String {
            fun host(url: String) = url.substringAfter("://").substringBefore('/')
            // The endpoint the mic will use, named after its block, so a switch to one that
            // was never set up shows as such before anyone tries to record.
            val voice = if (transcribeUse == Prefs.USE_CLOUD) cloudVoice else selfHostedVoice
            return buildString {
                append("File: ").append(source).append('\n')
                append("Model: ").append(model)
                upgrade?.let {
                    append(" (replaces the retired ").append(it.retired)
                    if (it.thinkingOn) append("; thinking on, maxTokens ").append(it.maxTokens)
                    append(')')
                }
                append('\n')
                append("Server: ").append(host(baseUrl)).append('\n')
                append("API key: ").append(if (apiKey.isBlank()) "not set" else "set").append('\n')
                append("Voice: ").append(
                    if (voice.isConfigured) host(Urls.resolve(voice.baseUrl, voice.path))
                    else "not configured"
                ).append(" (").append(transcribeUse).append(')').append('\n')
                if (allowCleartext) append("Plain http:// allowed").append('\n')
                append("Tools: ").append(readableTools(tools).ifEmpty { "none" }).append('\n')
                append("Applied: ").append(applied.joinToString(", "))
            }
        }
    }

    private fun buildPlan(root: JSONObject, prefs: Prefs): Plan {
        val applied = mutableListOf<String>()

        // Absent or null is this version; anything else has to be it, as a whole number.
        if (root.has("version") && !isUnset(root, "version")) {
            val version = root.opt("version")
            if (wholeNumber(version) != SUPPORTED_VERSION) {
                throw Invalid(
                    "Unsupported version $version.\n" +
                        "This build understands version $SUPPORTED_VERSION."
                )
            }
        }

        // A present field wins; an absent one keeps whatever is already configured, and an
        // explicit null puts [default] back.
        fun text(name: String, current: String, default: String): String =
            if (root.has(name)) {
                applied += name
                if (isUnset(root, name)) default else root.optString(name).trim()
            } else {
                current
            }

        // Same, but the value has to be a JSON object; null is the empty one.
        fun objectField(name: String, current: String): String {
            if (!root.has(name)) return current
            applied += name
            if (isUnset(root, name)) return "{}"
            val value = root.optJSONObject(name)
                ?: throw Invalid("\"$name\" must be a JSON object, e.g. {\"key\": \"value\"}.")
            return value.toString()
        }

        // Numbers or numeric strings, so `0.7` and `"0.7"` both work.
        fun raw(name: String, current: String): String =
            if (root.has(name)) {
                applied += name
                if (isUnset(root, name)) "" else root.get(name).toString().trim()
            } else {
                current
            }

        val baseUrl = text("baseUrl", prefs.baseUrl, Prefs.DEFAULT_BASE_URL).trimEnd('/')
        if (!Urls.isAbsolute(baseUrl)) {
            throw Invalid("\"baseUrl\" must start with http:// or https://.")
        }

        val allowCleartext = when {
            !root.has("allowCleartext") -> prefs.allowCleartext
            isUnset(root, "allowCleartext") -> false
            root.get("allowCleartext") is Boolean -> root.getBoolean("allowCleartext")
            else -> throw Invalid("\"allowCleartext\" must be true or false.")
        }
        if (root.has("allowCleartext")) applied += "allowCleartext"

        // A list of names rather than a flag per tool: adding a tool then costs a line in
        // the registry, not another boolean in every config file. Names are checked here,
        // so a typo is reported at import time instead of surfacing later as a model that
        // mysteriously cannot do the thing.
        val tools = if (!root.has("tools")) {
            prefs.tools
        } else if (isUnset(root, "tools")) {
            applied += "tools"
            "[]"
        } else {
            val array = root.optJSONArray("tools")
                ?: throw Invalid("\"tools\" must be an array of names, e.g. [\"weather\"].")
            val names = (0 until array.length())
                .map { array.optString(it).trim() }
                .filter { it.isNotEmpty() }
            for (name in names) {
                if (name !in Tools.names) {
                    throw Invalid(
                        "\"$name\" is not a tool this build has.\n" +
                            "Known tools: ${Tools.names.joinToString(", ")}."
                    )
                }
            }
            applied += "tools"
            JSONArray(names).toString()
        }

        val model = text("model", prefs.model, Prefs.DEFAULT_MODEL)
        if (model.isBlank()) throw Invalid("\"model\" cannot be blank.")

        val chatPath = text("chatPath", prefs.chatPath, Prefs.DEFAULT_CHAT_PATH)
        if (chatPath.isBlank()) throw Invalid("\"chatPath\" cannot be blank.")

        val modelsPath = text("modelsPath", prefs.modelsPath, Prefs.DEFAULT_MODELS_PATH)
        if (modelsPath.isBlank()) throw Invalid("\"modelsPath\" cannot be blank.")

        // "auth": null is both parts back to their defaults.
        val authUnset = isUnset(root, "auth")
        val auth = root.optJSONObject("auth")
        if (root.has("auth") && auth == null && !authUnset) {
            throw Invalid("\"auth\" must be a JSON object with optional \"header\" and \"scheme\".")
        }
        // A null here never becomes the text "null", which is what optString() makes of it. A
        // null header goes back to the default, since a key needs some header to travel in;
        // a null scheme is no scheme - a bare key, the same as "" and as transcribe.scheme.
        val authHeader = when {
            authUnset -> Prefs.DEFAULT_AUTH_HEADER
            auth == null || !auth.has("header") -> prefs.authHeader
            auth.isNull("header") -> Prefs.DEFAULT_AUTH_HEADER
            else -> auth.optString("header").trim().ifEmpty { prefs.authHeader }
        }
        val authScheme = when {
            authUnset -> Prefs.DEFAULT_AUTH_SCHEME
            auth == null || !auth.has("scheme") -> prefs.authScheme
            auth.isNull("scheme") -> ""
            else -> auth.optString("scheme").trim()
        }
        if (root.has("auth")) applied += "auth"

        val extraHeaders = objectField("headers", prefs.extraHeaders)
        val extraQuery = objectField("query", prefs.extraQuery)
        val extraBody = objectField("body", prefs.extraBody)

        val temperature = raw("temperature", prefs.temperature)
        if (temperature.isNotBlank()) {
            val value = temperature.toDoubleOrNull()
                ?: throw Invalid("\"temperature\" must be a number.")
            // "NaN" parses, and passes both comparisons below - then org.json refuses to put
            // it into a request, so it has to be turned away here.
            if (value.isNaN() || value < 0.0 || value > 2.0) {
                throw Invalid("\"temperature\" must be between 0 and 2.")
            }
        }

        val maxTokens = raw("maxTokens", prefs.maxTokens)
        if (maxTokens.isNotBlank()) {
            val value = maxTokens.toIntOrNull()
                ?: throw Invalid("\"maxTokens\" must be a whole number.")
            if (value < 1 || value > 200_000) {
                throw Invalid("\"maxTokens\" must be between 1 and 200000.")
            }
        }

        val timeoutSeconds = when {
            !root.has("timeoutSeconds") -> prefs.timeoutSeconds
            isUnset(root, "timeoutSeconds") -> Prefs.DEFAULT_TIMEOUT_SECONDS
            else -> wholeNumber(root.opt("timeoutSeconds"))?.takeIf { it in 5..600 }
                ?: throw Invalid("\"timeoutSeconds\" must be a whole number between 5 and 600.")
        }
        if (root.has("timeoutSeconds")) applied += "timeoutSeconds"

        // ---- voice input ----
        // Two blocks kept side by side - the self-hosted box and the hosted alternative -
        // and a switch between them, so trying the other one is a single line and never
        // costs the settings of the first.
        val selfHostedVoice = voiceBlock(
            root, "transcribe", prefs.selfHostedVoice, Prefs.DEFAULT_SELF_HOSTED_VOICE, applied
        )
        val cloudVoice = voiceBlock(
            root, "transcribeCloud", prefs.cloudVoice, Prefs.DEFAULT_CLOUD_VOICE, applied
        )

        val transcribeUse = when {
            !root.has("transcribeUse") -> prefs.transcribeUse
            isUnset(root, "transcribeUse") -> Prefs.USE_SELF_HOSTED
            else -> root.optString("transcribeUse").trim().also {
                if (it != Prefs.USE_SELF_HOSTED && it != Prefs.USE_CLOUD) {
                    throw Invalid(
                        "\"transcribeUse\" must be \"${Prefs.USE_SELF_HOSTED}\" or " +
                            "\"${Prefs.USE_CLOUD}\"."
                    )
                }
            }
        }
        if (root.has("transcribeUse")) applied += "transcribeUse"

        // ---- cleartext ----
        // Checked once, on where the requests will really go, rather than field by field: a
        // stored endpoint counts as much as one in the file, and a path that is a full address
        // as much as a base URL. Otherwise a file could switch allowCleartext off and leave a
        // plain http:// endpoint behind, to fail at every send instead of here.
        if (!allowCleartext) {
            val endpoints = listOf(
                endpoint("baseUrl", baseUrl, "chatPath", chatPath),
                endpoint("baseUrl", baseUrl, "modelsPath", modelsPath),
                endpoint(
                    "transcribe.baseUrl", selfHostedVoice.baseUrl,
                    "transcribe.path", selfHostedVoice.path
                ),
                endpoint(
                    "transcribeCloud.baseUrl", cloudVoice.baseUrl,
                    "transcribeCloud.path", cloudVoice.path
                )
            )
            endpoints.firstOrNull { (_, url) -> Urls.isCleartext(url) }?.let { (field, _) ->
                throw Invalid(
                    "$field is plain http://, but \"allowCleartext\" is not true.\n\n" +
                        "Add \"allowCleartext\": true to confirm the endpoint is yours " +
                        "(a local Ollama, LM Studio or whisper box, for example)."
                )
            }
        }

        // A config from 1.1 names a model DeepSeek has since retired. It gets the model that
        // replaced it, rather than an import that works and a chat that fails on every send.
        // Checked on the merged result, so a stored model counts as much as one in the file.
        val upgrade = Prefs.upgradeRetiredModel(baseUrl, model, extraBody, maxTokens)

        return Plan(
            baseUrl = baseUrl,
            model = upgrade?.model ?: model,
            apiKey = text("apiKey", prefs.apiKey, ""),
            // Blank is no system message at all; null is the built-in prompt back.
            systemPrompt = text("systemPrompt", prefs.systemPrompt, Prefs.DEFAULT_SYSTEM_PROMPT),
            authHeader = authHeader,
            authScheme = authScheme,
            extraHeaders = extraHeaders,
            extraQuery = extraQuery,
            extraBody = upgrade?.body ?: extraBody,
            chatPath = chatPath,
            modelsPath = modelsPath,
            temperature = temperature,
            maxTokens = upgrade?.maxTokens ?: maxTokens,
            timeoutSeconds = timeoutSeconds,
            allowCleartext = allowCleartext,
            tools = tools,
            selfHostedVoice = selfHostedVoice,
            cloudVoice = cloudVoice,
            transcribeUse = transcribeUse,
            upgrade = upgrade,
            applied = applied
        )
    }

    /**
     * One transcription block, `transcribe` or `transcribeCloud`, laid over what is
     * stored for it. Both take the same fields and the same rules as the rest of the
     * config: a partial block is fine, an explicit null puts a single field back to its
     * default, and a null block puts the whole block back - which switches it off.
     */
    private fun voiceBlock(
        root: JSONObject,
        name: String,
        current: Prefs.Voice,
        defaults: Prefs.Voice,
        applied: MutableList<String>
    ): Prefs.Voice {
        if (!root.has(name)) return current

        val block = root.optJSONObject(name)
        val voice = when {
            // Off, and as a new install has it: no endpoint and no key left behind, and no
            // absolute path either, which on its own kept the mic pointed somewhere.
            isUnset(root, name) -> defaults
            block == null -> throw Invalid(
                "\"$name\" must be a JSON object holding the transcription endpoint's settings."
            )
            else -> {
                // Absent keeps the current value, and null puts the default back - except for
                // "scheme", where null is no scheme, as it is for auth.scheme. An empty string
                // is taken as written: for "header" and "scheme" it is meaningful.
                fun sub(field: String, value: String, default: String): String = when {
                    !block.has(field) -> value
                    block.isNull(field) -> default
                    else -> block.optString(field).trim()
                }

                val timeoutSeconds = when {
                    !block.has("timeoutSeconds") -> current.timeoutSeconds
                    block.isNull("timeoutSeconds") -> defaults.timeoutSeconds
                    else -> wholeNumber(block.opt("timeoutSeconds"))?.takeIf { it in 5..1800 }
                        ?: throw Invalid(
                            "\"$name.timeoutSeconds\" must be a whole number between 5 and 1800."
                        )
                }

                Prefs.Voice(
                    baseUrl = sub("baseUrl", current.baseUrl, defaults.baseUrl).trimEnd('/'),
                    apiKey = sub("apiKey", current.apiKey, defaults.apiKey),
                    path = sub("path", current.path, defaults.path),
                    header = sub("header", current.header, defaults.header),
                    scheme = sub("scheme", current.scheme, ""),
                    field = sub("field", current.field, defaults.field),
                    form = formFields(block, name, current.form),
                    timeoutSeconds = timeoutSeconds
                )
            }
        }
        applied += name

        if (voice.baseUrl.isNotBlank() && !Urls.isAbsolute(voice.baseUrl)) {
            throw Invalid("\"$name.baseUrl\" must start with http:// or https://.")
        }
        if (voice.path.isBlank()) {
            throw Invalid("\"$name.path\" cannot be blank.")
        }
        if (voice.field.isBlank()) {
            throw Invalid("\"$name.field\" cannot be blank.")
        }
        return voice
    }

    /**
     * A block's `form`: the plain fields sent up beside the audio, such as the `model`
     * that OpenAI, Groq and Mistral all require. Each value goes up as one form field, so
     * only text, numbers and true/false make sense; null leaves a field out.
     */
    private fun formFields(block: JSONObject, name: String, current: String): String {
        if (!block.has("form")) return current
        if (block.isNull("form")) return "{}"
        val form = block.optJSONObject("form") ?: throw Invalid(
            "\"$name.form\" must be a JSON object, e.g. {\"model\": \"whisper-1\"}."
        )
        for (field in form.keys()) {
            val value = form.opt(field)
            if (value is JSONObject || value is JSONArray) {
                throw Invalid("\"$name.form.$field\" must be text, a number or true/false.")
            }
        }
        return form.toString()
    }

    /**
     * Where one kind of request goes, named after the field that decides it: the path when it
     * is a full address of its own, otherwise the base URL it follows. The address is "" for
     * an endpoint that is not configured.
     */
    private fun endpoint(
        baseField: String,
        base: String,
        pathField: String,
        path: String
    ): Pair<String, String> =
        (if (Urls.isAbsolute(path)) pathField else baseField) to Urls.resolve(base, path)

    /**
     * A whole number, written as one - `90` - or as text - `"90"` - and null for anything
     * else. Not optInt(), which read `"v2"` and `true` as its fallback and cut `1.9` down to
     * 1, so a wrong value passed for a right one.
     */
    private fun wholeNumber(value: Any?): Int? = when (value) {
        is Int -> value
        is Long, is Double -> (value as Number).toDouble().takeIf {
            it % 1.0 == 0.0 && it >= Int.MIN_VALUE && it <= Int.MAX_VALUE
        }?.toInt()
        is String -> value.trim().toIntOrNull()
        else -> null
    }
}
