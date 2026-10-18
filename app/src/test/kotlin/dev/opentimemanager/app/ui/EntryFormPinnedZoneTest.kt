package dev.opentimemanager.app.ui

import dev.opentimemanager.app.data.EntryEntity
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EntryFormPinnedZoneTest {
    private val utc = ZoneId.of("UTC")
    private val moscow = ZoneId.of("Europe/Moscow")
    private val duration = 90_001L

    private fun entry(start: String): EntryEntity {
        val instant = Instant.parse(start).toEpochMilli()
        return EntryEntity("entry", "project", "note", instant, instant + duration, "TIMER", 2, true)
    }

    @Test fun dateOnlyEditKeepsOpeningUtcTimeAndHiddenPrecisionAfterSystemZoneChanges() {
        val form = entryFormState(entry("2026-10-15T12:34:56.789Z"), duration, utc)
        assertEquals("UTC", form.zoneId)

        val draft = requireNotNull(validateEntryForm(form.copy(dateInput = "2026-10-16", dateEdited = true), moscow).value)
        assertEquals(Instant.parse("2026-10-16T12:34:56.789Z").toEpochMilli(), draft.startUtcMillis)
        assertEquals(duration, draft.durationMillis)
        assertFalse(draft.preserveTiming)
    }

    @Test fun timeOnlyEditKeepsOpeningUtcDateEvenWhenMoscowWouldBeOnTheNextDay() {
        val form = entryFormState(entry("2026-10-15T23:34:56.789Z"), duration, utc)
        val draft = requireNotNull(validateEntryForm(form.copy(timeInput = "12:35", timeEdited = true), moscow).value)

        assertEquals(Instant.parse("2026-10-15T12:35:00Z").toEpochMilli(), draft.startUtcMillis)
        assertEquals(duration, draft.durationMillis)
        assertFalse(draft.preserveTiming)
    }

    @Test fun metadataOnlyEditsKeepExactOriginalTimingAcrossZoneChanges() {
        val original = entry("2026-10-15T23:34:56.789Z")
        val form = entryFormState(original, duration, utc)
        for (edited in listOf(form.copy(noteInput = "updated"), form.copy(projectId = "other"))) {
            val draft = requireNotNull(validateEntryForm(edited, moscow).value)
            assertEquals(original.startedAtUtcMillis, draft.startUtcMillis)
            assertEquals(duration, draft.durationMillis)
            assertTrue(draft.preserveTiming)
        }
    }

    @Test fun unpinnedLegacyFormsStillUseTheCallerZone() {
        val form = EntryFormState("project", "2026-10-15", "12:34", "60", "")
        val draft = requireNotNull(validateEntryForm(form, moscow).value)

        assertEquals(Instant.parse("2026-10-15T09:34:00Z").toEpochMilli(), draft.startUtcMillis)
    }

    @Test fun pinnedZoneRetainsTheExistingDstGapAndOverlapResolutionPolicy() {
        val berlin = ZoneId.of("Europe/Berlin")
        val form = EntryFormState("project", "2026-03-29", "02:30", "60", "", zoneId = berlin.id)
        val gap = requireNotNull(validateEntryForm(form, moscow).value)
        val overlap = requireNotNull(validateEntryForm(form.copy(dateInput = "2026-10-25"), moscow).value)

        assertEquals(Instant.parse("2026-03-29T01:30:00Z").toEpochMilli(), gap.startUtcMillis)
        assertEquals(Instant.parse("2026-10-25T00:30:00Z").toEpochMilli(), overlap.startUtcMillis)
    }
}
