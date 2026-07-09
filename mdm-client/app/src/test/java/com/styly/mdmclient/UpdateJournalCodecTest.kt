package com.styly.mdmclient

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The journal is the only evidence that survives the client updating itself, so its encoding
 * is proven here rather than on real hardware.
 */
class UpdateJournalCodecTest {

    @Test
    fun `append then parse round-trips an entry`() {
        val raw = UpdateJournalCodec.append("", 1_700_000_000_000L, "SERVICE_ONCREATE", "version_code=7")

        val entries = UpdateJournalCodec.parse(raw)

        assertEquals(1, entries.size)
        assertEquals(1_700_000_000_000L, entries[0].timestampMillis)
        assertEquals("SERVICE_ONCREATE", entries[0].event)
        assertEquals("version_code=7", entries[0].detail)
    }

    @Test
    fun `appends accumulate in order`() {
        var raw = UpdateJournalCodec.append("", 1L, "APP_ONCREATE", "")
        raw = UpdateJournalCodec.append(raw, 2L, "SERVICE_ONCREATE", "")
        raw = UpdateJournalCodec.append(raw, 3L, "SERVICE_FOREGROUND_OK", "")

        assertEquals(
            listOf("APP_ONCREATE", "SERVICE_ONCREATE", "SERVICE_FOREGROUND_OK"),
            UpdateJournalCodec.parse(raw).map { it.event }
        )
    }

    @Test
    fun `an empty detail round-trips as empty`() {
        val raw = UpdateJournalCodec.append("", 1L, "SERVICE_FOREGROUND_OK", "")

        assertEquals("", UpdateJournalCodec.parse(raw).single().detail)
    }

    /**
     * The detail that matters most is an exception message from startForegroundService(), and
     * a stack-trace-ish message carrying a newline or tab would otherwise forge a record
     * boundary and corrupt every following entry.
     */
    @Test
    fun `a detail containing newlines tabs and backslashes survives`() {
        val nasty = "threw java.lang.IllegalStateException: not allowed\n\tat com.foo\\bar(Baz.java:1)\r\n"

        var raw = UpdateJournalCodec.append("", 1L, "FGS_ATTEMPT", nasty)
        raw = UpdateJournalCodec.append(raw, 2L, "SERVICE_DESTROYED", "after")

        val entries = UpdateJournalCodec.parse(raw)
        assertEquals(2, entries.size)
        assertEquals(nasty, entries[0].detail)
        assertEquals("SERVICE_DESTROYED", entries[1].event)
        assertEquals("after", entries[1].detail)
    }

    @Test
    fun `an event name containing a separator survives`() {
        val raw = UpdateJournalCodec.append("", 1L, "WEIRD\tEVENT\nNAME", "d")

        assertEquals("WEIRD\tEVENT\nNAME", UpdateJournalCodec.parse(raw).single().event)
    }

    @Test
    fun `the journal is capped and keeps the newest entries`() {
        var raw = ""
        for (i in 1..UpdateJournalCodec.MAX_EVENTS + 10) {
            raw = UpdateJournalCodec.append(raw, i.toLong(), "E$i", "")
        }

        val entries = UpdateJournalCodec.parse(raw)
        assertEquals(UpdateJournalCodec.MAX_EVENTS, entries.size)
        assertEquals("E11", entries.first().event)
        assertEquals("E${UpdateJournalCodec.MAX_EVENTS + 10}", entries.last().event)
    }

    @Test
    fun `unreadable lines are dropped without losing the rest`() {
        val raw = listOf(
            "not-a-timestamp\tEVENT\tdetail",
            "",
            "onlyonefield",
            "42\tGOOD_EVENT\tgood detail"
        ).joinToString("\n")

        val entries = UpdateJournalCodec.parse(raw)

        assertEquals(1, entries.size)
        assertEquals("GOOD_EVENT", entries.single().event)
    }

    @Test
    fun `a truncated final line costs only that entry`() {
        var raw = UpdateJournalCodec.append("", 1L, "KEPT", "detail")
        raw += "\n17000000"

        val entries = UpdateJournalCodec.parse(raw)

        assertEquals(1, entries.size)
        assertEquals("KEPT", entries.single().event)
    }

    @Test
    fun `parsing an empty journal yields nothing`() {
        assertTrue(UpdateJournalCodec.parse("").isEmpty())
    }
}
