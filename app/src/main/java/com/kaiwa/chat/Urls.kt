package com.kaiwa.chat

/**
 * What kind of address a configured URL is.
 *
 * Blind to case on purpose: `URL` accepts `HTTP://` as readily as `http://`, so a check that
 * was not let the capitals straight past the cleartext gate.
 */
object Urls {

    /** Plain http://, which only a config with allowCleartext may use. */
    fun isCleartext(url: String): Boolean = url.startsWith("http://", ignoreCase = true)

    /** A full address, http:// or https://, rather than a path to put after the base URL. */
    fun isAbsolute(url: String): Boolean =
        isCleartext(url) || url.startsWith("https://", ignoreCase = true)

    /**
     * Where a request goes: [path] itself when it is a full address, otherwise [path] after
     * [base]. "" when there is neither - a relative path with no base URL, which is an
     * endpoint that is not configured.
     */
    fun resolve(base: String, path: String): String = when {
        isAbsolute(path) -> path
        base.isBlank() -> ""
        else -> base.trimEnd('/') + "/" + path.trimStart('/')
    }
}
