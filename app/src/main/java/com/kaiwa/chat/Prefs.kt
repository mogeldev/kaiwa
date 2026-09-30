package com.kaiwa.chat

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject

/**
 * Everything the user can change, in one place, so no other class touches raw
 * SharedPreferences.
 *
 * The first four fields are what the Settings screen edits. Everything below them
 * exists so the app can talk to APIs that are OpenAI-shaped but not DeepSeek (Azure,
 * OpenRouter, a local Ollama), and is normally set through the config import.
 *
 * The voice group is separate again, because voice input is a different service from
 * the chat API, with its own URL, its own key and its own auth header. There are two of
 * it - the self-hosted whisper box and a hosted alternative - and [transcribeUse] picks
 * the one the mic sends to.
 */
class Prefs private constructor(private val sp: SharedPreferences) {

    constructor(context: Context) :
        this(context.getSharedPreferences("kaiwa", Context.MODE_PRIVATE))

    /**
     * The settings as they stand right now, frozen: what a request reads from its first
     * round to its last. The live settings are read afresh on every access, so an import
     * landing halfway through a request would otherwise pair the new key with the old
     * server. Writes to the copy stay in it.
     */
    fun snapshot(): Prefs = Prefs(FrozenPreferences(sp.all))

    init {
        // An install from 1.1 stored a model name DeepSeek has since retired. It is replaced
        // the first time the settings are read, just as an import of that old config would
        // replace it, so updating the app is all it takes.
        upgradeRetiredModel(baseUrl, model, extraBody, maxTokens)?.let {
            model = it.model
            extraBody = it.body
            maxTokens = it.maxTokens
        }
    }

    var apiKey: String
        get() = sp.getString(KEY_API_KEY, "").orEmpty()
        set(value) = sp.edit().putString(KEY_API_KEY, value.trim()).apply()

    var baseUrl: String
        get() = sp.getString(KEY_BASE_URL, DEFAULT_BASE_URL) ?: DEFAULT_BASE_URL
        set(value) = sp.edit().putString(KEY_BASE_URL, value.trim().trimEnd('/')).apply()

    var model: String
        get() = sp.getString(KEY_MODEL, DEFAULT_MODEL) ?: DEFAULT_MODEL
        set(value) = sp.edit().putString(KEY_MODEL, value.trim()).apply()

    var systemPrompt: String
        get() = sp.getString(KEY_SYSTEM_PROMPT, DEFAULT_SYSTEM_PROMPT) ?: DEFAULT_SYSTEM_PROMPT
        set(value) = sp.edit().putString(KEY_SYSTEM_PROMPT, value.trim()).apply()

    // ---- Endpoint compatibility, normally set by importing a config file. ----

    /** Header the key travels in. Azure-style APIs use `api-key` instead. */
    var authHeader: String
        get() = sp.getString(KEY_AUTH_HEADER, DEFAULT_AUTH_HEADER) ?: DEFAULT_AUTH_HEADER
        set(value) = sp.edit().putString(KEY_AUTH_HEADER, value.trim()).apply()

    /** Prefix in front of the key. Blank sends a bare key, again for Azure. */
    var authScheme: String
        get() = sp.getString(KEY_AUTH_SCHEME, DEFAULT_AUTH_SCHEME) ?: DEFAULT_AUTH_SCHEME
        set(value) = sp.edit().putString(KEY_AUTH_SCHEME, value.trim()).apply()

    /** Extra request headers, as a JSON object. */
    var extraHeaders: String
        get() = sp.getString(KEY_EXTRA_HEADERS, EMPTY_OBJECT).orEmpty()
        set(value) = sp.edit().putString(KEY_EXTRA_HEADERS, value.trim()).apply()

    /** Extra URL query parameters, as a JSON object. */
    var extraQuery: String
        get() = sp.getString(KEY_EXTRA_QUERY, EMPTY_OBJECT).orEmpty()
        set(value) = sp.edit().putString(KEY_EXTRA_QUERY, value.trim()).apply()

    /** Extra request-body fields, as a JSON object. Merged in last, so it wins. */
    var extraBody: String
        get() = sp.getString(KEY_EXTRA_BODY, EMPTY_OBJECT).orEmpty()
        set(value) = sp.edit().putString(KEY_EXTRA_BODY, value.trim()).apply()

    var chatPath: String
        get() = sp.getString(KEY_CHAT_PATH, DEFAULT_CHAT_PATH) ?: DEFAULT_CHAT_PATH
        set(value) = sp.edit().putString(KEY_CHAT_PATH, value.trim()).apply()

    var modelsPath: String
        get() = sp.getString(KEY_MODELS_PATH, DEFAULT_MODELS_PATH) ?: DEFAULT_MODELS_PATH
        set(value) = sp.edit().putString(KEY_MODELS_PATH, value.trim()).apply()

    /** Blank means the parameter is left out of the request entirely. */
    var temperature: String
        get() = sp.getString(KEY_TEMPERATURE, "").orEmpty()
        set(value) = sp.edit().putString(KEY_TEMPERATURE, value.trim()).apply()

    var maxTokens: String
        get() = sp.getString(KEY_MAX_TOKENS, "").orEmpty()
        set(value) = sp.edit().putString(KEY_MAX_TOKENS, value.trim()).apply()

    var timeoutSeconds: Int
        get() = sp.getInt(KEY_TIMEOUT_SECONDS, DEFAULT_TIMEOUT_SECONDS)
        set(value) = sp.edit().putInt(KEY_TIMEOUT_SECONDS, value).apply()

    /**
     * Permits plain `http://` endpoints such as a local Ollama or LM Studio.
     *
     * The manifest sets `usesCleartextTraffic="true"` because cleartext policy cannot
     * be changed at runtime (it is compiled into the APK), so this flag is the real
     * gate: [ApiClient] refuses any http:// URL unless it is set. It covers the
     * transcription endpoints too, which are often the ones that actually need it.
     */
    var allowCleartext: Boolean
        get() = sp.getBoolean(KEY_ALLOW_CLEARTEXT, false)
        set(value) = sp.edit().putBoolean(KEY_ALLOW_CLEARTEXT, value).apply()

    /**
     * Which of the app's tools the chat request advertises, as a JSON array of names -
     * `["weather", "wikipedia"]` - taken from [Tools.names]. Empty by default: a provider
     * that does not do tool calling may reject a request carrying `tools` rather than
     * ignore it, and this app promises to work with any OpenAI-compatible API.
     */
    var tools: String
        get() = sp.getString(KEY_TOOLS, EMPTY_ARRAY).orEmpty()
        set(value) = sp.edit().putString(KEY_TOOLS, value.trim()).apply()

    // ---- Voice input: the speech-to-text endpoints. ----

    /**
     * The self-hosted faster-whisper box this was built against. Its settings sit under
     * the keys the single endpoint always had, so an install that predates the cloud
     * profile keeps what it stored.
     */
    var selfHostedVoice: Voice
        get() = readVoice(SELF_HOSTED)
        set(value) = writeVoice(SELF_HOSTED, value)

    /** The alternative: a hosted service in OpenAI's shape - OpenAI, Groq, Mistral. */
    var cloudVoice: Voice
        get() = readVoice(CLOUD)
        set(value) = writeVoice(CLOUD, value)

    /**
     * Which of the two the mic sends to, [USE_SELF_HOSTED] or [USE_CLOUD]. Both stay
     * stored either way, so switching is this one value rather than a block rewritten.
     */
    var transcribeUse: String
        get() = sp.getString(KEY_TRANSCRIBE_USE, USE_SELF_HOSTED) ?: USE_SELF_HOSTED
        set(value) = sp.edit().putString(KEY_TRANSCRIBE_USE, value.trim()).apply()

    /** The endpoint recordings go to right now. */
    val voice: Voice
        get() = if (transcribeUse == USE_CLOUD) cloudVoice else selfHostedVoice

    val isVoiceConfigured: Boolean get() = voice.isConfigured

    /**
     * One speech-to-text endpoint, read and written as a whole.
     *
     * [timeoutSeconds] is deliberately not the chat timeout: transcription on a CPU box
     * costs about 23 seconds before it has looked at the audio, then roughly 0.4 seconds
     * per second of speech, and the chat timeout would cut off every recording.
     */
    data class Voice(
        /** Blank switches voice input off, and the mic says so rather than failing later. */
        val baseUrl: String,
        val apiKey: String,
        val path: String,
        /**
         * `X-API-Key` with a bare key for faster-whisper-fastapi, `Authorization: Bearer`
         * for the hosted services.
         */
        val header: String,
        val scheme: String,
        /** The multipart form field the audio file is uploaded as. */
        val field: String,
        /**
         * Extra multipart fields sent ahead of the file, as a JSON object - the `model`
         * that OpenAI-style services refuse to work without.
         */
        val form: String,
        val timeoutSeconds: Int
    ) {
        /**
         * Whether the mic has anywhere to send to. The key is optional, because a box on
         * the LAN usually has none.
         */
        val isConfigured: Boolean
            get() = Urls.resolve(baseUrl, path).isNotEmpty()
    }

    /** Where one endpoint's settings are stored, and what it is before any are. */
    private class Profile(val prefix: String, val defaults: Voice)

    private fun readVoice(profile: Profile): Voice {
        val defaults = profile.defaults
        fun text(key: String, default: String) =
            sp.getString(profile.prefix + key, default) ?: default

        return Voice(
            baseUrl = text(KEY_VOICE_BASE_URL, defaults.baseUrl),
            apiKey = text(KEY_VOICE_API_KEY, defaults.apiKey),
            path = text(KEY_VOICE_PATH, defaults.path),
            header = text(KEY_VOICE_HEADER, defaults.header),
            scheme = text(KEY_VOICE_SCHEME, defaults.scheme),
            field = text(KEY_VOICE_FIELD, defaults.field),
            form = text(KEY_VOICE_FORM, defaults.form),
            timeoutSeconds = sp.getInt(
                profile.prefix + KEY_VOICE_TIMEOUT_SECONDS,
                defaults.timeoutSeconds
            )
        )
    }

    private fun writeVoice(profile: Profile, voice: Voice) {
        val prefix = profile.prefix
        sp.edit()
            .putString(prefix + KEY_VOICE_BASE_URL, voice.baseUrl.trim().trimEnd('/'))
            .putString(prefix + KEY_VOICE_API_KEY, voice.apiKey.trim())
            .putString(prefix + KEY_VOICE_PATH, voice.path.trim())
            .putString(prefix + KEY_VOICE_HEADER, voice.header.trim())
            .putString(prefix + KEY_VOICE_SCHEME, voice.scheme.trim())
            .putString(prefix + KEY_VOICE_FIELD, voice.field.trim())
            .putString(prefix + KEY_VOICE_FORM, voice.form.trim())
            .putInt(prefix + KEY_VOICE_TIMEOUT_SECONDS, voice.timeoutSeconds)
            .apply()
    }

    /**
     * Which conversation the chat screen is showing. The chat list writes this and
     * finishes; the chat screen notices the change in onResume.
     */
    var activeChatId: String
        get() = sp.getString(KEY_ACTIVE_CHAT_ID, "").orEmpty()
        set(value) = sp.edit().putString(KEY_ACTIVE_CHAT_ID, value).apply()

    /**
     * A blank key is tolerated only for a plain http:// chat endpoint the config allowed -
     * a local server, which normally uses none. Everything else still has to be given a
     * key, https:// included: allowCleartext says nothing about a server on the internet.
     */
    val isConfigured: Boolean
        get() {
            if (apiKey.isNotBlank()) return true
            return allowCleartext && Urls.isCleartext(Urls.resolve(baseUrl, chatPath))
        }

    /**
     * A retired model name brought up to date: what to store in its place, the old name, and
     * whether thinking was switched on along the way.
     */
    data class ModelUpgrade(
        val model: String,
        val body: String,
        val maxTokens: String,
        val retired: String,
        val thinkingOn: Boolean
    )

    companion object {
        /** DeepSeek is OpenAI-compatible, so this default works with their API. */
        const val DEFAULT_BASE_URL = "https://api.deepseek.com"
        /**
         * DeepSeek's current model. The names it replaced, deepseek-chat and
         * deepseek-reasoner, were retired on 2026-07-24; one still stored, or still named by
         * an imported config, is swapped for this by [upgradeRetiredModel].
         */
        const val DEFAULT_MODEL = "deepseek-flash"

        /**
         * The model names of Kaiwa 1.1's configs. Both were modes of the model now called
         * deepseek-flash - deepseek-chat without thinking, deepseek-reasoner with it.
         */
        private val RETIRED_MODELS = setOf("deepseek-chat", "deepseek-reasoner")
        private const val DEEPSEEK_HOST = "api.deepseek.com"

        /**
         * Room to think in. DeepSeek's own max_tokens default is 64K with thinking against 8K
         * without, because the reasoning is generated tokens as well - 1.1's 1024 would cut
         * most answers off before they began.
         */
        private const val THINKING_MAX_TOKENS = "65536"

        /**
         * The current model for a retired DeepSeek name, with thinking switched on - it is
         * deepseek-flash's default, and set here outright - and room for it in max_tokens. A
         * `thinking` already in [body] is a choice the config made, and then neither is
         * touched. Null for any other model, and for any server but DeepSeek's own, where the
         * same name may well mean something else.
         */
        fun upgradeRetiredModel(
            baseUrl: String,
            model: String,
            body: String,
            maxTokens: String
        ): ModelUpgrade? {
            val host = baseUrl.substringAfter("://").substringBefore('/').substringBefore(':')
            if (!host.equals(DEEPSEEK_HOST, ignoreCase = true)) return null
            if (model.lowercase() !in RETIRED_MODELS) return null

            val json = try {
                JSONObject(body)
            } catch (e: Exception) {
                JSONObject()
            }
            if (json.has("thinking")) {
                return ModelUpgrade(DEFAULT_MODEL, body, maxTokens, model, thinkingOn = false)
            }
            json.put("thinking", JSONObject().put("type", "enabled"))
            return ModelUpgrade(
                DEFAULT_MODEL, json.toString(), THINKING_MAX_TOKENS, model, thinkingOn = true
            )
        }

        const val DEFAULT_AUTH_HEADER = "Authorization"
        const val DEFAULT_AUTH_SCHEME = "Bearer"
        const val DEFAULT_CHAT_PATH = "/chat/completions"
        const val DEFAULT_MODELS_PATH = "/models"
        const val DEFAULT_TIMEOUT_SECONDS = 90

        /** The values [transcribeUse] takes, as the config file spells them. */
        const val USE_SELF_HOSTED = "selfhosted"
        const val USE_CLOUD = "cloud"

        /** The shape of the self-hosted faster-whisper setup this was built against. */
        val DEFAULT_SELF_HOSTED_VOICE = Voice(
            baseUrl = "",
            apiKey = "",
            path = "/v2/transcribe",
            header = "X-API-Key",
            scheme = "",
            field = "audio",
            form = EMPTY_OBJECT,
            timeoutSeconds = 300
        )

        /**
         * OpenAI's shape, which Groq and Mistral share. The model is left to `form`,
         * because no one name works with all three.
         */
        val DEFAULT_CLOUD_VOICE = Voice(
            baseUrl = "",
            apiKey = "",
            path = "/audio/transcriptions",
            header = "Authorization",
            scheme = "Bearer",
            field = "file",
            form = EMPTY_OBJECT,
            timeoutSeconds = 120
        )

        private val SELF_HOSTED = Profile("transcribe", DEFAULT_SELF_HOSTED_VOICE)
        private val CLOUD = Profile("transcribe_cloud", DEFAULT_CLOUD_VOICE)

        /** Short replies are a feature on a 320dp screen. */
        const val DEFAULT_SYSTEM_PROMPT =
            "You are Kaiwa, a helpful assistant on a very small phone screen. " +
                "Reply in plain text, keep answers short and direct."

        private const val EMPTY_OBJECT = "{}"
        private const val EMPTY_ARRAY = "[]"

        private const val KEY_API_KEY = "api_key"
        private const val KEY_BASE_URL = "base_url"
        private const val KEY_MODEL = "model"
        private const val KEY_SYSTEM_PROMPT = "system_prompt"
        private const val KEY_AUTH_HEADER = "auth_header"
        private const val KEY_AUTH_SCHEME = "auth_scheme"
        private const val KEY_EXTRA_HEADERS = "extra_headers"
        private const val KEY_EXTRA_QUERY = "extra_query"
        private const val KEY_EXTRA_BODY = "extra_body"
        private const val KEY_CHAT_PATH = "chat_path"
        private const val KEY_MODELS_PATH = "models_path"
        private const val KEY_TEMPERATURE = "temperature"
        private const val KEY_MAX_TOKENS = "max_tokens"
        private const val KEY_TIMEOUT_SECONDS = "timeout_seconds"
        private const val KEY_ALLOW_CLEARTEXT = "allow_cleartext"
        private const val KEY_TOOLS = "tools"
        private const val KEY_TRANSCRIBE_USE = "transcribe_use"

        // Each follows a profile's prefix: "transcribe" + "_base_url" is the key the single
        // endpoint always used.
        private const val KEY_VOICE_BASE_URL = "_base_url"
        private const val KEY_VOICE_API_KEY = "_api_key"
        private const val KEY_VOICE_PATH = "_path"
        private const val KEY_VOICE_HEADER = "_header"
        private const val KEY_VOICE_SCHEME = "_scheme"
        private const val KEY_VOICE_FIELD = "_field"
        private const val KEY_VOICE_FORM = "_form"
        private const val KEY_VOICE_TIMEOUT_SECONDS = "_timeout_seconds"
        private const val KEY_ACTIVE_CHAT_ID = "active_chat_id"
    }
}

/**
 * Settings held in memory, copied from the real ones at one moment - see [Prefs.snapshot].
 * Nothing written to it reaches the disk or the live settings; it only changes the copy.
 */
private class FrozenPreferences(values: Map<String, *>) : SharedPreferences {

    private val values = HashMap<String, Any?>(values)

    override fun getAll(): Map<String, *> = HashMap(values)

    override fun getString(key: String, defValue: String?): String? =
        values[key] as? String ?: defValue

    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? =
        (values[key] as? Set<String>)?.toMutableSet() ?: defValues

    override fun getInt(key: String, defValue: Int): Int = values[key] as? Int ?: defValue

    override fun getLong(key: String, defValue: Long): Long = values[key] as? Long ?: defValue

    override fun getFloat(key: String, defValue: Float): Float =
        values[key] as? Float ?: defValue

    override fun getBoolean(key: String, defValue: Boolean): Boolean =
        values[key] as? Boolean ?: defValue

    override fun contains(key: String): Boolean = values.containsKey(key)

    override fun edit(): SharedPreferences.Editor = Editor()

    // Nothing to tell anyone: the copy changes only when its own holder writes to it.
    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?
    ) = Unit

    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?
    ) = Unit

    private inner class Editor : SharedPreferences.Editor {
        private val changes = HashMap<String, Any?>()
        private val removed = mutableSetOf<String>()
        private var cleared = false

        override fun putString(key: String, value: String?) = put(key, value)
        override fun putStringSet(key: String, values: MutableSet<String>?) =
            put(key, values?.toSet())
        override fun putInt(key: String, value: Int) = put(key, value)
        override fun putLong(key: String, value: Long) = put(key, value)
        override fun putFloat(key: String, value: Float) = put(key, value)
        override fun putBoolean(key: String, value: Boolean) = put(key, value)

        override fun remove(key: String): SharedPreferences.Editor {
            removed += key
            return this
        }

        override fun clear(): SharedPreferences.Editor {
            cleared = true
            return this
        }

        override fun commit(): Boolean {
            apply()
            return true
        }

        override fun apply() {
            if (cleared) values.clear()
            removed.forEach { values.remove(it) }
            // A null put removes the key, as it does in the real thing.
            changes.forEach { (key, value) ->
                if (value == null) values.remove(key) else values[key] = value
            }
        }

        private fun put(key: String, value: Any?): SharedPreferences.Editor {
            changes[key] = value
            return this
        }
    }
}
