package dev.opentimemanager.app.ui.screens

import dev.opentimemanager.app.data.EntryEntity
import dev.opentimemanager.app.data.ProjectEntity
import dev.opentimemanager.app.tracking.AppSnapshot
import dev.opentimemanager.app.ui.AppUiState
import dev.opentimemanager.app.ui.ManualEntryDraft
import dev.opentimemanager.app.ui.validateEntryForm
import dev.opentimemanager.core.model.TrackingSession
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryDialogGenerationTest {
    private val utc = ZoneId.of("UTC")
    private val moscow = ZoneId.of("Europe/Moscow")
    private val start = Instant.parse("2026-10-18T12:34:56.789Z").toEpochMilli()
    private val duration = 90_001L
    private val entry = EntryEntity("entry", "project", "original", start, start + duration, "TIMER", 7, true)

    private fun state(generation: String) = AppUiState(
        snapshot = AppSnapshot(
            session = TrackingSession.idle(),
            projects = listOf(ProjectEntity("project", "Project", 0, 0)),
            entries = listOf(entry.copy(note = if (generation == "opened") "original" else "restored")),
            segments = emptyList(),
            generation = generation,
            entryDurations = mapOf(entry.id to duration),
        ),
    )

    @Test fun editEmitsOpeningGenerationAndRevisionAfterSnapshotReplacementWithTheSameIds() {
        var current = state("opened")
        val editor = HistoryEditSession.open(current, entry, utc)
        current = state("replaced")
        assertEquals("replaced", current.snapshot?.generation)
        assertEquals("restored", current.snapshot?.entries?.single()?.note)
        assertEquals("original", editor.initialForm.noteInput)

        val draft = requireNotNull(validateEntryForm(editor.initialForm.copy(noteInput = "edited"), moscow).value)
        var emitted: Triple<String?, Long?, ManualEntryDraft>? = null
        editor.save(draft) { id, revision, value -> emitted = Triple(id, revision, value) }

        val saved = requireNotNull(emitted)
        assertEquals(entry.id, saved.first)
        assertEquals(entry.revision, saved.second)
        assertEquals("opened", saved.third.expectedGeneration)
        assertEquals(start, saved.third.startUtcMillis)
        assertEquals(duration, saved.third.durationMillis)
        assertTrue(saved.third.preserveTiming)
    }

    @Test fun manualAddPinsOpeningGenerationAndZoneBeforeAReplacementArrives() {
        var current = state("opened")
        val editor = HistoryEditSession.open(current, zoneId = utc)
        current = state("replaced")
        assertEquals("replaced", current.snapshot?.generation)
        assertEquals("UTC", editor.initialForm.zoneId)

        val form = editor.initialForm
        val draft = requireNotNull(validateEntryForm(form, moscow).value)
        var emitted: Triple<String?, Long?, ManualEntryDraft>? = null
        editor.save(draft.copy(expectedGeneration = current.snapshot?.generation)) { id, revision, value ->
            emitted = Triple(id, revision, value)
        }

        val saved = requireNotNull(emitted)
        assertNull(saved.first)
        assertNull(saved.second)
        assertEquals("opened", saved.third.expectedGeneration)
        assertEquals(
            LocalDateTime.parse("${form.dateInput}T${form.timeInput}").atZone(utc).toInstant().toEpochMilli(),
            saved.third.startUtcMillis,
        )
    }

    @Test fun deleteConfirmationEmitsOpeningGenerationEvenWhenReplacementReusesIdAndRevision() {
        var current = state("opened")
        val deletion = HistoryDeleteRequest.open(current, entry)
        current = state("replaced")
        assertEquals(entry.id, current.snapshot?.entries?.single()?.id)
        assertEquals(entry.revision, current.snapshot?.entries?.single()?.revision)

        var emitted: Triple<String, Long, String>? = null
        deletion.confirm { id, revision, generation -> emitted = Triple(id, revision, generation) }

        assertEquals(Triple(entry.id, entry.revision, "opened"), emitted)
    }
}
