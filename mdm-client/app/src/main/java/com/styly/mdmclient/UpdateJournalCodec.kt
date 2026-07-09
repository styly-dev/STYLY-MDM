package com.styly.mdmclient

/**
 * Serialization for [UpdateJournal], kept free of Android types so it can be proven on the
 * host JVM. The journal is the only post-mortem available after the client updates itself and
 * is killed mid-flight, so a silent encoding bug would cost a physical trip to the device.
 *
 * One event per line, tab-separated: `<epochMillis>\t<EVENT>\t<detail>`. A line format rather
 * than JSON because appending is a string concat with no parse of the existing content, and a
 * truncated or corrupt line costs exactly one event instead of the whole journal.
 */
internal object UpdateJournalCodec {

    /** Oldest entries are dropped past this. Enough to hold several update cycles. */
    const val MAX_EVENTS = 80

    private const val SEPARATOR = '\t'

    /** Returns [existing] with one event appended, trimmed to the newest [MAX_EVENTS] lines. */
    fun append(existing: String, timestampMillis: Long, event: String, detail: String): String {
        val line = "$timestampMillis$SEPARATOR${escape(event)}$SEPARATOR${escape(detail)}"
        val lines = existing.lineSequence().filter { it.isNotBlank() }.toMutableList()
        lines.add(line)
        while (lines.size > MAX_EVENTS) {
            lines.removeAt(0)
        }
        return lines.joinToString("\n")
    }

    /** Parses [raw], silently dropping lines that a partial write or corruption made unreadable. */
    fun parse(raw: String): List<Entry> {
        return raw.lineSequence()
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                val parts = line.split(SEPARATOR)
                if (parts.size < 2) return@mapNotNull null
                val timestamp = parts[0].toLongOrNull() ?: return@mapNotNull null
                Entry(
                    timestampMillis = timestamp,
                    event = unescape(parts[1]),
                    detail = if (parts.size >= 3) unescape(parts[2]) else ""
                )
            }
            .toList()
    }

    // Exception messages carry newlines and tabs, either of which would forge a record boundary.
    private fun escape(value: String): String {
        val out = StringBuilder(value.length)
        for (c in value) {
            when (c) {
                '\\' -> out.append("\\\\")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                else -> out.append(c)
            }
        }
        return out.toString()
    }

    private fun unescape(value: String): String {
        val out = StringBuilder(value.length)
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (c != '\\' || i == value.length - 1) {
                out.append(c)
                i++
                continue
            }
            when (val next = value[i + 1]) {
                '\\' -> out.append('\\')
                'n' -> out.append('\n')
                'r' -> out.append('\r')
                't' -> out.append('\t')
                // Not an escape we produce; keep both characters rather than lose data.
                else -> out.append(c).append(next)
            }
            i += 2
        }
        return out.toString()
    }

    data class Entry(
        val timestampMillis: Long,
        val event: String,
        val detail: String
    )
}
