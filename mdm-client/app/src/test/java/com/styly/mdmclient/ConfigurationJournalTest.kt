package com.styly.mdmclient

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigurationJournalTest {
    private val first = ConfigurationVersion("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", "11111111-1111-4111-8111-111111111111")
    private val second = first.copy(packageId = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb")
    private val updated = first.copy(revision = "22222222-2222-4222-8222-222222222222")

    @Test fun `shared revision UUID is scoped to its package and a successful attempt repeats only after boot`() {
        val firstAttempt = ConfigurationAttempt(first, 133, "complete")
        val secondAttempt = ConfigurationAttempt(second, 133, "complete")
        val journal = ConfigurationJournal().withAttempt(firstAttempt)
        assertFalse(journal.shouldApply(first, 133, null))
        assertTrue(journal.shouldApply(second, 133, null))
        val restored = ConfigurationJournal.restore(journal.withAttempt(secondAttempt).encode())
        assertFalse(restored.shouldApply(first, 133, null))
        assertFalse(restored.shouldApply(second, 133, null))
        assertTrue(restored.shouldApply(first, 134, null))
        assertTrue(restored.shouldApply(updated, 133, null))
    }

    @Test fun `failed revision waits across reboot even after an attempt for another package`() {
        val results = JSONObject().put("brightness", settingOutcome("applied", 60))
            .put("wifi", settingOutcome("failed", reason = "TIMEOUT_ROLLED_BACK"))
        val failed = ConfigurationAttempt(first, 133, "complete", results)
        val journal = ConfigurationJournal().withAttempt(failed)
            .withAttempt(ConfigurationAttempt(second, 133, "complete"))
        val restored = ConfigurationJournal.restore(journal.encode())
        assertFalse(restored.shouldApply(first, 134, null))
        assertTrue(restored.shouldApply(first, 134, "retry-one"))
        assertTrue(restored.shouldApply(second, 134, null))
        assertTrue(restored.shouldApply(updated, 134, null))
        assertEquals("TIMEOUT_ROLLED_BACK", restored.find(first)?.results?.getJSONObject("wifi")?.getString("reason"))
        assertNull(restored.withAttempt(ConfigurationAttempt(updated, 134)).find(first))
    }

    @Test fun `interrupted claim and consumed retries survive a process restart`() {
        val saved = ConfigurationAttempt(first, 133, retryIds = setOf("retry-one", "retry-two"))
        val restored = ConfigurationJournal.restore(ConfigurationJournal().withAttempt(saved).encode())
        val interrupted = requireNotNull(restored.find(first))
        assertEquals("interrupted", interrupted.status)
        assertFalse(restored.shouldApply(first, 133, null))
        assertFalse(restored.shouldApply(first, 134, null))
        assertFalse(restored.shouldApply(first, 134, "retry-one"))
        assertFalse(restored.shouldApply(first, 134, "retry-two"))
        assertTrue(restored.shouldApply(first, 134, "retry-three"))
        assertFalse(restored.shouldApply(second, 134, "retry-three"))
        val report = interrupted.report()
        assertEquals(first.packageId, report.getString("package_id"))
        assertEquals(first.revision, report.getString("revision"))
        assertFalse(report.has("boot_count"))
        assertFalse(report.has("retry_ids"))
    }

    @Test fun `explicit retry cannot replay a successful or unattempted revision`() {
        val journal = ConfigurationJournal().withAttempt(ConfigurationAttempt(first, 133, "complete"))
        assertFalse(journal.shouldApply(first, 133, "retry-one"))
        assertFalse(journal.shouldApply(first, 134, "retry-one"))
        assertFalse(journal.shouldApply(updated, 134, "retry-one"))
    }
}
