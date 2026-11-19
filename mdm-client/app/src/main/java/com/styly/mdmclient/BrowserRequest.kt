package com.styly.mdmclient

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.URI

internal const val OPEN_URL_CAPABILITY = "open_url_v1"

/** Validate without returning a normalized URL, which could change query data. */
internal fun validBrowserUrl(value: Any?): String? {
    val url = value as? String ?: return null
    if (url.isEmpty() || url.length > 4096 || '\\' in url ||
        url.any { it.isWhitespace() || it.isISOControl() }) return null
    return try {
        val uri = URI(url)
        if (uri.scheme?.lowercase() !in setOf("http", "https") ||
            uri.rawAuthority.isNullOrEmpty() || '@' in uri.rawAuthority) return null
        val parsed = url.toHttpUrlOrNull() ?: return null
        if (parsed.username.isNotEmpty() || parsed.password.isNotEmpty()) return null
        url
    } catch (_: Exception) {
        null
    }
}

/** One-shot handoff: stale, duplicate, and expired activity launches cannot navigate. */
internal class BrowserRequestGate<T> {
    private var pending: Triple<String, Long, T>? = null

    fun offer(token: String, expiresAt: Long, value: T): Boolean {
        if (pending != null) return false
        pending = Triple(token, expiresAt, value)
        return true
    }

    fun take(token: String?, now: Long): T? {
        val request = pending ?: return null
        if (request.first != token) return null
        pending = null
        return request.third.takeIf { now < request.second }
    }

    fun clear() { pending = null }

    fun matches(token: String?): Boolean = pending?.first == token && pending != null
}
