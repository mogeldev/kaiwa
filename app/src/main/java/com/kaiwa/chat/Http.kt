package com.kaiwa.chat

import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder
import java.net.UnknownHostException

// What the tools share of HTTP, and what ApiClient shares with them: one GET, one way to
// read a body, one User-Agent, and one way to say a lookup failed. Each tool used to carry
// its own copy of all of it, and the copies had started to drift.

/** What every request the app makes calls itself. */
const val USER_AGENT = "Kaiwa/" + BuildConfig.VERSION_NAME + " (Android)"

/** How long a tool waits for its service, to connect and again to read. */
private const val TOOL_TIMEOUT_MS = 20_000

/**
 * The GET every tool makes: JSON asked for, the app's User-Agent sent, and anything but a
 * 2xx turned into an exception, which [lookupFailed] then puts into words.
 */
fun httpGet(url: String): String {
    val conn = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = TOOL_TIMEOUT_MS
        readTimeout = TOOL_TIMEOUT_MS
        setRequestProperty("Accept", "application/json")
        setRequestProperty("User-Agent", USER_AGENT)
    }
    return try {
        if (conn.responseCode !in 200..299) {
            throw IllegalStateException("HTTP ${conn.responseCode}")
        }
        readUtf8(conn.inputStream)
    } finally {
        conn.disconnect()
    }
}

/** A response body as text, or "" when there is none - an error with no body, say. */
fun readUtf8(stream: InputStream?): String {
    if (stream == null) return ""
    return BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { it.readText() }
}

/** A value for a URL's query string. */
fun urlEncode(value: String): String = URLEncoder.encode(value, "UTF-8")

/**
 * A tool that could not run, in words the model can pass on. [service] is what could not
 * be reached, and every tool fails in the same shape of sentence.
 */
fun lookupFailed(e: Exception, service: String): String {
    val reason = when (e) {
        is UnknownHostException -> "it could not reach $service"
        is SocketTimeoutException -> "$service did not answer in time"
        else -> e.message ?: e.javaClass.simpleName
    }
    return "The lookup could not run: $reason."
}
