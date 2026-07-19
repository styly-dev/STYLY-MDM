package com.styly.mdmclient

import org.json.JSONObject
import java.io.File

/**
 * Tunables for the bounded connection window (#67), overridable by an
 * operator-placed JSON file on shared storage:
 *
 *   /sdcard/styly-mdm/config.json
 *   { "connect_window_seconds": 10, "connect_retry_interval_seconds": 2 }
 *
 * The file is optional; each key falls back to its default independently when
 * missing or invalid (non-numeric or non-positive). It is re-read every time a
 * connection window opens, so a pushed config takes effect on the next network
 * transition or disconnect without reinstalling. The path is deliberately
 * outside Downloads: EXECUTE_PUSH_FILES refuses the protected top-level media
 * dirs, so /sdcard/styly-mdm is the one place both an operator (via file
 * manager or adb) and a push bundle can write. Free of Android imports so
 * parsing runs under plain JUnit.
 */
object ClientConfig {

    const val CONFIG_RELATIVE_PATH = "styly-mdm/config.json"

    const val KEY_CONNECT_WINDOW_SECONDS = "connect_window_seconds"
    const val KEY_RETRY_INTERVAL_SECONDS = "connect_retry_interval_seconds"

    const val DEFAULT_CONNECT_WINDOW_MS = 10_000L
    const val DEFAULT_RETRY_INTERVAL_MS = 2_000L

    data class Values(
        val connectWindowMs: Long = DEFAULT_CONNECT_WINDOW_MS,
        val retryIntervalMs: Long = DEFAULT_RETRY_INTERVAL_MS,
    )

    val DEFAULTS = Values()

    fun load(file: File): Values = parse(
        try {
            if (file.isFile) file.readText() else null
        } catch (t: Throwable) {
            null
        }
    )

    fun parse(jsonText: String?): Values {
        if (jsonText == null) return DEFAULTS
        val json = try {
            JSONObject(jsonText)
        } catch (t: Throwable) {
            return DEFAULTS
        }
        return Values(
            connectWindowMs = secondsAsMs(json, KEY_CONNECT_WINDOW_SECONDS, DEFAULT_CONNECT_WINDOW_MS),
            retryIntervalMs = secondsAsMs(json, KEY_RETRY_INTERVAL_SECONDS, DEFAULT_RETRY_INTERVAL_MS),
        )
    }

    private fun secondsAsMs(json: JSONObject, key: String, defaultMs: Long): Long {
        val seconds = json.optDouble(key)
        if (seconds.isNaN() || seconds.isInfinite() || seconds <= 0.0) return defaultMs
        return (seconds * 1000.0).toLong()
    }
}
