package com.kaiwa.chat

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.OutputStream
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder
import java.net.UnknownHostException
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.net.ssl.SSLException

/**
 * Minimal client for any OpenAI-compatible API (DeepSeek by default), plus the
 * speech-to-text endpoint that voice input posts to.
 *
 * A reply is not always an answer: it can be a request to run a tool instead. [send]
 * handles both, running what was asked for and asking again, and [Tools] holds the
 * handful that exist. None of them are advertised unless [Prefs.tools] names them.
 *
 * Built on HttpURLConnection + org.json deliberately: both ship with Android, so the
 * app carries no networking or JSON dependency at all. On a Snapdragon 210 with 1 GB
 * of RAM that is worth more than the convenience of Retrofit/OkHttp.
 *
 * Everything that varies between providers comes from [Prefs]: the auth header and
 * scheme, the request paths, extra headers/query/body fields, and the timeouts. The
 * transcription endpoints carry their own copy of all of it, because they are a different
 * service from the chat provider - a self-hosted whisper box, or a hosted one in OpenAI's
 * shape - each with its own key and a read timeout of its own.
 *
 * All calls block; callers must run them off the main thread.
 */
object ApiClient {

    sealed class Result {
        /**
         * [promptTokens] and [completionTokens] are what the turn cost, summed over its
         * tool rounds, or 0 when the server did not say. Usage is reported inconsistently
         * across providers, and a missing number is not worth failing a reply over.
         */
        data class Success(
            val content: String,
            val promptTokens: Int = 0,
            val completionTokens: Int = 0,
            /** The final reply's `reasoning_content`, for [Message.reasoning]. "" when none came. */
            val reasoning: String = ""
        ) : Result()

        /**
         * The tokens are what the turn had cost before it failed: a failure in the third
         * tool round comes after two rounds that were billed all the same.
         */
        data class Failure(
            val message: String,
            val promptTokens: Int = 0,
            val completionTokens: Int = 0
        ) : Result()
    }

    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val JSON_CONTENT_TYPE = "application/json; charset=utf-8"
    private const val AUDIO_CONTENT_TYPE = "audio/mp4"
    private const val EMPTY_OBJECT = "{}"
    private const val CRLF = "\r\n"
    private const val REASONING = "reasoning_content"

    /** How much of a body that is not a chat reply goes into the error saying so. */
    private const val EXCERPT_CHARS = 160

    /**
     * How many times the model may ask for a tool before it is made to answer. Three is
     * already generous for a single weather lookup; the cap is there so a model that
     * loops cannot spend the user's quota by itself.
     */
    private const val MAX_TOOL_ROUNDS = 3

    /** How one endpoint authenticates. The transcription box uses its own header. */
    private data class Auth(val header: String, val scheme: String, val key: String)

    /**
     * A request body, as a length plus the code that writes it.
     *
     * The length is what matters: it lets HttpURLConnection stream the body with
     * setFixedLengthStreamingMode() instead of buffering a whole recording in the
     * heap. On a 1 GB device a five-minute voice note is worth not copying twice.
     */
    private class Body(
        val length: Int,
        val contentType: String,
        val write: (OutputStream) -> Unit
    )

    /**
     * POST to the configured chat path, running any tool the model asks for on the way.
     *
     * A completion is text in and text out, so a question about the weather has nothing
     * to draw on. What the model can do is ask: with tools named in [Prefs.tools], the
     * request carries the declarations the app knows how to answer, and a reply of
     * `tool_calls` instead of text means "run these, then ask me again". That loop is the
     * whole of tool calling from this end of the wire.
     *
     * The exchange stays out of the conversation: only the question and the final answer
     * are ever saved, so a chat file does not grow a transcript of plumbing the user
     * never saw.
     */
    fun send(prefs: Prefs, history: List<Message>): Result = try {
        converse(prefs, history)
    } catch (e: Exception) {
        // Everything around the HTTP call can still throw - the request body, the parsed
        // reply, the tool calls in it: a stored temperature of NaN, or a provider answering
        // "choices": [null]. Callers run this on a bare worker thread, where an uncaught
        // exception takes the whole app down, so it has to end as an error row instead.
        Result.Failure(describeThrowable(e))
    }

    private fun converse(prefs: Prefs, history: List<Message>): Result {
        val endpoint = buildEndpoint(prefs, prefs.chatPath)
        val enabled = enabledTools(prefs)
        val messages = buildMessages(prefs, history, withReasoning = enabled.isNotEmpty())

        var promptTokens = 0
        var completionTokens = 0

        // One pass more than there are tool rounds. The last one still declares the tools,
        // since the conversation refers to them, but forbids calling one: the model has to
        // answer with what it has rather than be cut off after every round was paid for.
        repeat(MAX_TOOL_ROUNDS + 1) { round ->
            val last = round == MAX_TOOL_ROUNDS
            val raw = when (val posted = post(prefs, endpoint, messages, forbidTools = last)) {
                is Result.Failure -> return posted.copy(
                    promptTokens = promptTokens,
                    completionTokens = completionTokens
                )
                is Result.Success -> posted.content
            }

            val response = parseObject(raw)

            // Summed over the rounds rather than reported per request: one question can be
            // three requests when tools are involved, and the bill is the total.
            val usage = response.optJSONObject("usage")
            promptTokens += usage?.optInt("prompt_tokens", 0) ?: 0
            completionTokens += usage?.optInt("completion_tokens", 0) ?: 0

            val message = response.optJSONArray("choices")
                ?.optJSONObject(0)
                ?.optJSONObject("message")
            val calls = message?.optJSONArray("tool_calls")

            if (calls == null || calls.length() == 0) {
                // A refusal is the model's answer too, only carried in a field of its own.
                val content = contentOf(message)
                    .ifBlank { message?.let { textOf(it, "refusal") }.orEmpty() }
                if (content.isBlank()) {
                    return Result.Failure(noAnswer(response, raw), promptTokens, completionTokens)
                }
                return Result.Success(
                    content = content,
                    promptTokens = promptTokens,
                    completionTokens = completionTokens,
                    reasoning = reasoningOf(message)
                )
            }

            // Only a server that ignored "tool_choice": "none" gets here.
            if (last) {
                return Result.Failure(
                    "The model kept asking for tools instead of answering.",
                    promptTokens,
                    completionTokens
                )
            }

            // The assistant's own turn goes back first: the API rejects a tool result
            // that does not answer a call it can see in the conversation.
            messages.put(echoOf(message, calls))
            for (i in 0 until calls.length()) {
                messages.put(toolResultFor(calls.getJSONObject(i), enabled))
            }
        }

        return Result.Failure("The model never answered.")
    }

    private fun post(
        prefs: Prefs,
        endpoint: String,
        messages: JSONArray,
        forbidTools: Boolean
    ): Result {
        val json = buildBody(prefs, messages, forbidTools).toByteArray(Charsets.UTF_8)
        return request(
            prefs, "POST", endpoint,
            Body(json.size, JSON_CONTENT_TYPE) { it.write(json) }
        ) { raw -> Result.Success(raw) }
    }

    /** The assistant's turn, put back with its tool calls so the results have context. */
    private fun echoOf(message: JSONObject, calls: JSONArray): JSONObject {
        val text = contentOf(message)
        val echo = JSONObject()
            .put("role", Message.ROLE_ASSISTANT)
            .put("content", if (text.isBlank()) JSONObject.NULL else text)
            .put("tool_calls", calls)

        // A thinking model's reasoning goes back with the calls it led to: DeepSeek answers
        // HTTP 400 to a tool round that arrives without it.
        val reasoning = reasoningOf(message)
        if (reasoning.isNotEmpty()) echo.put(REASONING, reasoning)
        return echo
    }

    /** The `reasoning_content` a thinking model sent with a message, or "" when it sent none. */
    private fun reasoningOf(message: JSONObject?): String =
        message?.let { textOf(it, REASONING) }.orEmpty()

    /**
     * A string field, or "" when it is missing or null.
     *
     * Android's optString() turns a JSON null into the string "null", and OpenAI-style APIs
     * send exactly that for a tool call's `"content": null` - which then went back to the
     * model as its own word "null", or showed up in the transcript as the whole reply.
     */
    private fun textOf(json: JSONObject, name: String): String =
        if (json.isNull(name)) "" else json.optString(name)

    /**
     * A message's text: its content, or its parts joined when the content came as an array
     * of them, as some providers send it. Parts that are not text - a model's thinking, say -
     * are left out.
     */
    private fun contentOf(message: JSONObject?): String {
        if (message == null) return ""
        val parts = message.optJSONArray("content") ?: return textOf(message, "content")
        return (0 until parts.length())
            .mapNotNull { parts.optJSONObject(it)?.let { part -> textOf(part, "text") } }
            .joinToString("")
    }

    /**
     * Runs one requested tool, wrapped as the `tool` turn the API expects back.
     *
     * A name the app does not know, one the config did not enable, or a tool that fails,
     * comes back as text too: the model can then say so in its reply, which is a better
     * outcome than the whole send failing over a tool the app never had.
     */
    private fun toolResultFor(call: JSONObject, enabled: Set<String>): JSONObject {
        val function = call.optJSONObject("function")
        val name = function?.let { textOf(it, "name") }.orEmpty()
        val arguments = function?.let { textOf(it, "arguments") }.orEmpty()

        return JSONObject()
            .put("role", "tool")
            .put("tool_call_id", textOf(call, "id"))
            .put("content", Tools.run(name, parseObject(arguments), enabled))
    }

    /** The tool names the config asks for, as a set. An empty set means no `tools` field. */
    private fun enabledTools(prefs: Prefs): Set<String> {
        val array = try {
            JSONArray(prefs.tools)
        } catch (e: Exception) {
            JSONArray()
        }
        return (0 until array.length())
            .mapNotNull { array.optString(it).trim().takeIf { name -> name.isNotEmpty() } }
            .toSet()
    }

    /** GET the configured models path - doubles as a connectivity and credential check. */
    fun listModels(prefs: Prefs): Result {
        val endpoint = buildEndpoint(prefs, prefs.modelsPath)
        return request(prefs, "GET", endpoint, null) { raw ->
            Result.Success(summariseModels(raw))
        }
    }

    /**
     * Uploads a recorded voice note and returns the transcribed text.
     *
     * A different service from the chat API, and a different shape: the audio goes up
     * as multipart/form-data to whichever endpoint [Prefs.transcribeUse] names, with its
     * key in that endpoint's own header. faster-whisper-fastapi wants the file in
     * `audio`, a bare key in `X-API-Key`, and answers `{"status":"ok","response":"..."}`;
     * OpenAI-style services want it in `file`, a `model` field beside it - which the
     * block's `form` supplies - and answer `{"text":"..."}`.
     *
     * The read timeout is the endpoint's own, not the chat timeout: the self-hosted box
     * spends about 23 seconds on a request before it has even looked at the audio, so
     * 90 seconds would cut off every recording.
     */
    fun transcribe(prefs: Prefs, audio: File): Result {
        val voice = prefs.voice
        val endpoint = buildEndpoint(
            prefs = prefs,
            path = voice.path,
            base = voice.baseUrl,
            // The configured query parameters belong to the chat API (Azure's
            // api-version, say). The transcription box is a different service and
            // would be sent parameters it never asked for.
            withQuery = false
        )
        if (endpoint.isBlank()) {
            return Result.Failure("No transcription endpoint is configured.")
        }

        val boundary = "----KaiwaBoundary${System.currentTimeMillis()}"
        val form = parseObject(voice.form)
        val head = buildString {
            // The plain fields go first, the file last. One set to null is left out,
            // the same as a header.
            form.keys().forEach { name ->
                if (!form.isNull(name)) {
                    append("--").append(boundary).append(CRLF)
                    append("Content-Disposition: form-data; name=\"").append(name).append('"')
                        .append(CRLF)
                    append(CRLF)
                    append(form.optString(name)).append(CRLF)
                }
            }
            append("--").append(boundary).append(CRLF)
            append("Content-Disposition: form-data; name=\"")
                .append(voice.field)
                .append("\"; filename=\"")
                .append(audio.name)
                .append('"').append(CRLF)
            append("Content-Type: ").append(AUDIO_CONTENT_TYPE).append(CRLF)
            append(CRLF)
        }.toByteArray(Charsets.UTF_8)
        val tail = "$CRLF--$boundary--$CRLF".toByteArray(Charsets.UTF_8)
        val length = head.size + audio.length().toInt() + tail.size

        return request(
            prefs = prefs,
            method = "POST",
            endpoint = endpoint,
            body = Body(length, "multipart/form-data; boundary=$boundary") { out ->
                out.write(head)
                audio.inputStream().use { it.copyTo(out) }
                out.write(tail)
            },
            auth = Auth(voice.header, voice.scheme, voice.apiKey),
            // Headers configured for the chat provider must not ride along here.
            extraHeaders = EMPTY_OBJECT,
            readTimeoutSeconds = voice.timeoutSeconds
        ) { raw -> Result.Success(extractTranscript(raw)) }
    }

    /**
     * Joins a base URL and a path, then appends the configured query parameters.
     *
     * A path may be absolute, which is how an endpoint that happens to live somewhere
     * else entirely can be reached. An empty base with a relative path means the
     * endpoint is simply not configured, and comes back as an empty string.
     */
    private fun buildEndpoint(
        prefs: Prefs,
        path: String,
        base: String = prefs.baseUrl,
        withQuery: Boolean = true
    ): String {
        val target = Urls.resolve(base, path)
        if (target.isEmpty()) return ""

        val query = if (withQuery) parseObject(prefs.extraQuery) else JSONObject()
        if (query.length() == 0) return target

        // A parameter set to null in the config is left out, not sent as the text "null".
        val encoded = query.keys().asSequence()
            .filterNot { query.isNull(it) }
            .map { name -> name + "=" + URLEncoder.encode(query.optString(name), "UTF-8") }
            .joinToString("&")

        return target + (if (target.contains('?')) "&" else "?") + encoded
    }

    private fun request(
        prefs: Prefs,
        method: String,
        endpoint: String,
        body: Body?,
        auth: Auth = Auth(prefs.authHeader, prefs.authScheme, prefs.apiKey),
        extraHeaders: String = prefs.extraHeaders,
        readTimeoutSeconds: Int = prefs.timeoutSeconds,
        onSuccess: (String) -> Result
    ): Result {
        // Cleartext policy is compiled into the APK and cannot be changed at runtime, so
        // the manifest permits http:// and this is the gate that actually enforces it.
        if (Urls.isCleartext(endpoint) && !prefs.allowCleartext) {
            return Result.Failure(
                "Plain http:// is disabled.\n\nSet \"allowCleartext\": true in the config " +
                    "if this endpoint is yours, or use https://."
            )
        }

        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                // A redirect is reported, never followed. Following one turned a POST into a
                // GET with no body, and sent a key in api-key or X-API-Key - only
                // Authorization is stripped - on to whatever host the answer named.
                instanceFollowRedirects = false
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = readTimeoutSeconds.coerceIn(5, 1800) * 1000

                // A blank key is normal for a local server; send no auth header at all.
                if (auth.key.isNotBlank() && auth.header.isNotBlank()) {
                    val scheme = auth.scheme
                    setRequestProperty(
                        auth.header,
                        if (scheme.isBlank()) auth.key else "$scheme ${auth.key}"
                    )
                }

                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", USER_AGENT)

                // Likewise a header set to null: left out rather than sent as "null".
                val headers = parseObject(extraHeaders)
                headers.keys().forEach { name ->
                    if (!headers.isNull(name)) setRequestProperty(name, headers.optString(name))
                }

                if (body != null) {
                    doOutput = true
                    setRequestProperty("Content-Type", body.contentType)
                    setFixedLengthStreamingMode(body.length)
                }
            }

            if (body != null) {
                conn.outputStream.use { body.write(it) }
            }

            val code = conn.responseCode
            when (code) {
                in 200..299 -> onSuccess(readUtf8(conn.inputStream))
                in 300..399 -> Result.Failure(
                    describeRedirect(code, conn.getHeaderField("Location"))
                )
                else -> Result.Failure(describeHttpError(code, readUtf8(conn.errorStream)))
            }
        } catch (e: Exception) {
            Result.Failure(describeThrowable(e))
        } finally {
            conn?.disconnect()
        }
    }

    /**
     * The conversation as the API wants it: the system prompt first, then the history,
     * capped at what [ChatStore] keeps. Kept separate from the body around it because
     * tool calling appends to it between requests.
     *
     * [withReasoning] puts each earlier reply's reasoning back beside it, which is only asked
     * of a request that carries tools: DeepSeek's thinking mode rejects one without it.
     * Everywhere else the field stays out, since a provider that never sent it may not
     * accept it either.
     */
    private fun buildMessages(
        prefs: Prefs,
        history: List<Message>,
        withReasoning: Boolean
    ): JSONArray {
        val messages = JSONArray()

        // The date goes out with every request because the model has no clock of its own,
        // and "heute", "morgen" and "diese Woche" are guesses without one - which is most
        // of what makes the weather answers worth having. It is appended rather than
        // prepended so the configured prompt stays the first thing the model reads.
        //
        // A blank prompt sends no system message at all, which is what a chat template
        // without a system role needs - Gemma 2's refuses the request outright. The date
        // then travels at the head of the question instead.
        val date = "Current date and time: " + now()
        val prompt = prefs.systemPrompt.trim()
        if (prompt.isNotEmpty()) {
            messages.put(
                JSONObject().put("role", Message.ROLE_SYSTEM).put("content", "$prompt\n\n$date")
            )
        }

        val turns = alternating(
            history.asSequence()
                .filter { !it.pending && !it.isError && it.content.isNotBlank() }
                .toList()
                .takeLast(ChatStore.MAX_MESSAGES)
        )
        turns.forEachIndexed { i, message ->
            val content = if (prompt.isEmpty() && i == turns.lastIndex && message.isUser) {
                "$date\n\n${message.content}"
            } else {
                message.content
            }
            val turn = JSONObject().put("role", message.role).put("content", content)
            if (withReasoning && message.reasoning.isNotEmpty()) {
                turn.put(REASONING, message.reasoning)
            }
            messages.put(turn)
        }

        return messages
    }

    /**
     * The history as strict chat templates want it: user and assistant taking turns, starting
     * with the user.
     *
     * A reply that fails leaves its question unanswered, so the next question follows it
     * directly - and a template that enforces the alternation (Gemma's, Mistral's, as served
     * by LM Studio, llama.cpp or vLLM) then rejects every request in that chat for good. Two
     * turns in a row from the same side are joined into one instead, and a reply that the
     * history window happens to start on is left out.
     */
    private fun alternating(history: List<Message>): List<Message> {
        val turns = mutableListOf<Message>()
        for (message in history) {
            val previous = turns.lastOrNull()
            when {
                previous == null && !message.isUser -> Unit
                previous != null && previous.role == message.role ->
                    turns[turns.lastIndex] = previous.copy(
                        content = previous.content + "\n\n" + message.content,
                        reasoning = listOf(previous.reasoning, message.reasoning)
                            .filter { it.isNotEmpty() }
                            .joinToString("\n\n")
                    )
                else -> turns += message
            }
        }
        return turns
    }

    /**
     * The date and time as the model should read them: named in English, in the phone's
     * own zone. English because the model translates and the app does not, and the phone's
     * zone because a handset in Munich should not be told it is UTC - the timezone name is
     * included for the same reason, so a daylight-saving change cannot quietly shift it.
     *
     * `Locale.US` is not a taste: the device's own locale would be right for a German
     * reader and wrong for the one reader this string actually has, the same reason
     * [com.kaiwa.chat.oneDecimal] pins its decimal point.
     */
    private fun now(): String {
        val format = DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy, HH:mm", Locale.US)
        return ZonedDateTime.now().format(format) + " (" + ZoneId.systemDefault().id + ")"
    }

    private fun buildBody(prefs: Prefs, messages: JSONArray, forbidTools: Boolean): String {
        val root = JSONObject()
            .put("model", prefs.model)
            .put("messages", messages)
            .put("stream", false)

        // Only sent when set, so a provider that rejects unknown parameters is unaffected.
        prefs.temperature.toDoubleOrNull()?.let { root.put("temperature", it) }
        prefs.maxTokens.toIntOrNull()?.let { root.put("max_tokens", it) }

        // Off by default and by config: a provider that does not do tool calling may
        // reject a request carrying `tools` rather than ignore it, and this app promises
        // to work with any OpenAI-compatible API.
        val enabled = enabledTools(prefs)
        if (enabled.isNotEmpty()) {
            root.put("tools", Tools.declarations(enabled))
            if (forbidTools) root.put("tool_choice", "none")
        }

        // Provider-specific fields last, so they can override the standard ones.
        val extra = parseObject(prefs.extraBody)
        extra.keys().forEach { root.put(it, extra.get(it)) }

        return root.toString()
    }

    /** Extra headers/query/body come from config files, so never trust the parse. */
    private fun parseObject(json: String): JSONObject = try {
        JSONObject(json)
    } catch (e: Exception) {
        JSONObject()
    }

    /**
     * Why a successful response carried no answer, for the error row that stands in for one.
     *
     * These used to come back as a Success, which put "The server returned an unexpected
     * response." into the transcript as though the model had said it - saved to the chat file,
     * sent back as its own words in every later request, and with the server's actual reason
     * nowhere to be seen. A proxy answering 200 with an error object, an HTML login page, or a
     * stream the app did not ask for all end up here.
     */
    private fun noAnswer(response: JSONObject, raw: String): String {
        val reason = reasonOf(response)
        if (reason.isNotEmpty()) return "The server answered without a reply: $reason"

        val choices = response.optJSONArray("choices")
            ?: return "The server's answer was not a chat reply." + excerpt(raw)
        if (choices.length() == 0) return "The server returned no reply."

        val finish = choices.optJSONObject(0)?.let { textOf(it, "finish_reason") }.orEmpty()
        return "The model returned an empty message." +
            if (finish.isNotEmpty()) " (finish_reason: $finish)" else ""
    }

    /** The start of a body that is not JSON, so an HTML page can be told from a stream. */
    private fun excerpt(raw: String): String {
        val text = raw.trim().replace(Regex("\\s+"), " ")
        if (text.isEmpty() || text.startsWith("{")) return ""
        return "\n\n" + text.take(EXCERPT_CHARS) + if (text.length > EXCERPT_CHARS) "\u2026" else ""
    }

    /**
     * faster-whisper-fastapi answers `{"status":"ok","response":" Hello, this is a
     * test."}` - the leading space is theirs, hence the trim. A blank result is
     * returned as-is rather than papered over, because "the recording held no speech"
     * is something the caller has to decide what to do about. `text` is accepted too:
     * it is OpenAI's shape, and Groq and Mistral answer the same way.
     */
    private fun extractTranscript(raw: String): String {
        val json = JSONObject(raw)
        val text = textOf(json, "response").ifBlank { textOf(json, "text") }
        return text.trim()
    }

    private fun summariseModels(raw: String): String {
        val data = JSONObject(raw).optJSONArray("data") ?: return "Connected, but no model list was returned."
        val names = (0 until data.length())
            .mapNotNull { data.getJSONObject(it).optString("id").takeIf { s -> s.isNotBlank() } }
        if (names.isEmpty()) return "Connected, but the model list is empty."
        return "Connected. ${names.size} models available:\n" + names.joinToString(", ")
    }

    /**
     * The human-readable reason in an error body, or "" when it has none. Services name it
     * differently: OpenAI-compatible APIs use error.message, Ollama and LM Studio send
     * error as plain text, Mistral and vLLM put message at the top, FastAPI uses detail, and
     * faster-whisper-fastapi answers {"status":"error","response":"transcription failed"}.
     * A top-level message counts only as text: Ollama's own chat reply has an object there.
     */
    private fun reasonOf(json: JSONObject?): String {
        if (json == null) return ""
        val reason = when (val error = json.opt("error")) {
            is JSONObject -> textOf(error, "message")
            is String -> error
            else -> ""
        }
        return reason
            .ifBlank { json.opt("message") as? String ?: "" }
            .ifBlank { textOf(json, "detail") }
            .ifBlank { textOf(json, "response") }
            .trim()
    }

    private fun describeHttpError(code: Int, raw: String): String {
        val json = try {
            JSONObject(raw)
        } catch (e: Exception) {
            null
        }
        val detail = reasonOf(json)

        val hint = when (code) {
            401 -> "Check your API key."
            402 -> "This account has insufficient balance."
            403 -> "This key is not allowed to do that."
            404 -> "Endpoint not found - check the Base URL and paths."
            413 -> "The upload was too large for the server to accept."
            429 -> "Rate limited or out of quota. Try again shortly."
            in 500..599 -> "The server had a problem. Try again."
            else -> ""
        }

        return buildString {
            append("HTTP ").append(code)
            if (detail.isNotBlank()) append(": ").append(detail)
            if (hint.isNotBlank()) append('\n').append(hint)
        }
    }

    /**
     * A redirect that was not followed, with where it pointed: that is the address the
     * config should name instead. The query string stays out of the message, because a
     * redirect can carry a token in it.
     */
    private fun describeRedirect(code: Int, location: String?): String = buildString {
        append("HTTP ").append(code).append(": the server redirected the request")
        val target = location?.substringBefore('?')?.trim().orEmpty()
        if (target.isNotEmpty()) append(" to\n").append(target)
        append("\nRedirects are not followed - put that address in the Base URL or path.")
    }

    private fun describeThrowable(e: Exception): String = when (e) {
        is UnknownHostException ->
            "Can't resolve that server address. Check the Base URL and your connection."
        is SocketTimeoutException ->
            "The request timed out. Raise it in the config: timeoutSeconds for chat, " +
                "transcribe.timeoutSeconds or transcribeCloud.timeoutSeconds for voice."
        is ConnectException ->
            "Couldn't connect. If a permission prompt appeared, allow network access and retry."
        is SSLException ->
            "Secure connection failed: ${e.message}"
        else ->
            "${e.javaClass.simpleName}: ${e.message}"
    }
}
