package dev.opentimemanager.app.ui.screens

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.Locales
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.then
import androidx.compose.ui.text.intl.Locale
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.opentimemanager.app.data.EntryEntity
import dev.opentimemanager.app.data.ProjectEntity
import dev.opentimemanager.app.settings.ThemeMode
import dev.opentimemanager.app.tracking.AppSnapshot
import dev.opentimemanager.app.ui.AppUiState
import dev.opentimemanager.app.ui.AppControls
import dev.opentimemanager.app.ui.ManualEntryDraft
import dev.opentimemanager.app.ui.ManualProjectPickerState
import dev.opentimemanager.app.ui.ReportPeriod
import dev.opentimemanager.app.ui.theme.OpenTimeManagerTheme
import dev.opentimemanager.core.model.TrackingSession
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Черновики восстанавливаются вместе с исходным контрактом сохранения. */
@RunWith(AndroidJUnit4::class)
class HistoryReportsRestorationTest {
    @get:Rule val compose = createComposeRule()
    private val start = Instant.parse("2026-10-10T12:34:56.789Z").toEpochMilli()
    private val duration = 90_001L
    private val entry = EntryEntity("entry", "project", "original", start, start + duration, "TIMER", 7, true)

    private fun state(generation: String = "opened", editingEntry: EntryEntity = entry) = AppUiState(
        snapshot = AppSnapshot(
            session = TrackingSession.idle(),
            projects = listOf(ProjectEntity("project", "Focus project", 0xff6251da.toInt(), 0)),
            entries = listOf(editingEntry),
            segments = emptyList(),
            generation = generation,
            entryDurations = mapOf(editingEntry.id to duration),
        ),
    )

    private fun show(content: @androidx.compose.runtime.Composable () -> Unit): StateRestorationTester {
        val restorer = StateRestorationTester(compose)
        restorer.setContent {
            DeviceConfigurationOverride(
                DeviceConfigurationOverride.ForcedSize(DpSize(412.dp, 915.dp)) then
                    DeviceConfigurationOverride.Locales(LocaleList(Locale("en"))),
            ) {
                OpenTimeManagerTheme(ThemeMode.LIGHT, dynamicColor = false, content = content)
            }
        }
        return restorer
    }

    private fun replaceField(label: String, value: String) {
        compose.onNode(hasScrollToNodeAction() and hasAnyAncestor(isDialog()))
            .performScrollToNode(hasText(label))
        compose.onNode(hasText(label) and hasSetTextAction()).performTextReplacement(value)
    }

    @Test fun manualDraftSurvivesRestorationAndKeepsOpeningGeneration() {
        val current = mutableStateOf(state())
        var saved: Triple<String?, Long?, ManualEntryDraft>? = null
        val openingZone = ZoneId.systemDefault()
        val restorer = show {
            HistoryScreen(
                state = current.value, onSearch = {},
                onSave = { id, revision, draft -> saved = Triple(id, revision, draft) },
                onDelete = { _, _, _ -> }, onLoadMore = {}, onOpenProjectPicker = {},
                onSearchProjects = {}, onLoadMoreProjects = {}, onCloseProjectPicker = {},
            )
        }
        compose.onNodeWithContentDescription("Add entry").performClick()
        replaceField("Date (YYYY-MM-DD)", "2026-10-10")
        replaceField("Start (HH:MM)", "09:15")
        replaceField("Duration in minutes", "37")
        replaceField("Note", "manual draft")
        compose.runOnIdle { current.value = state("replacement") }

        restorer.emulateSavedInstanceStateRestore()

        compose.onNodeWithText("Save").performClick()
        compose.runOnIdle {
            val result = requireNotNull(saved)
            assertNull(result.first)
            assertNull(result.second)
            assertEquals("opened", result.third.expectedGeneration)
            assertEquals("manual draft", result.third.note)
            assertEquals(2_220_000L, result.third.durationMillis)
            assertEquals(LocalDateTime.parse("2026-10-10T09:15").atZone(openingZone).toInstant().toEpochMilli(), result.third.startUtcMillis)
        }
    }

    @Test fun editedDraftRestoresOriginalRevisionGenerationAndPreciseTiming() {
        val current = mutableStateOf(state())
        var saved: Triple<String?, Long?, ManualEntryDraft>? = null
        val restorer = show {
            HistoryScreen(
                state = current.value, onSearch = {},
                onSave = { id, revision, draft -> saved = Triple(id, revision, draft) },
                onDelete = { _, _, _ -> }, onLoadMore = {}, onOpenProjectPicker = {},
                onSearchProjects = {}, onLoadMoreProjects = {}, onCloseProjectPicker = {},
            )
        }
        compose.onNodeWithContentDescription("Edit entry").performClick()
        replaceField("Note", "edited draft")
        compose.runOnIdle {
            current.value = state("replacement", entry.copy(revision = 99, startedAtUtcMillis = start + 60_000, note = "replaced"))
        }

        restorer.emulateSavedInstanceStateRestore()

        compose.onNodeWithText("Save").performClick()
        compose.runOnIdle {
            val result = requireNotNull(saved)
            assertEquals("entry", result.first)
            assertEquals(7L, result.second)
            assertEquals("opened", result.third.expectedGeneration)
            assertEquals("edited draft", result.third.note)
            assertEquals(start, result.third.startUtcMillis)
            assertEquals(duration, result.third.durationMillis)
            assertTrue(result.third.preserveTiming)
        }
    }

    @Test fun customReportDatesSurviveRestorationBeforeApply() {
        var selected: ReportPeriod? = null
        val restorer = show { ReportsScreen(state(), onSelectPeriod = { selected = it }) }
        compose.onNodeWithText("Custom").performClick()
        compose.onNode(hasText("From (YYYY-MM-DD)") and hasSetTextAction()).performTextReplacement("2026-09-03")
        compose.onNode(hasText("Through (YYYY-MM-DD)") and hasSetTextAction()).performTextReplacement("2026-09-19")

        restorer.emulateSavedInstanceStateRestore()

        compose.onNodeWithText("Apply").performClick()
        compose.runOnIdle {
            assertEquals(ReportPeriod.Custom(LocalDate.parse("2026-09-03"), LocalDate.parse("2026-09-19")), selected)
        }
    }

    @Test fun historyProjectSearchSurvivesPickerLifecycleResetDuringRestoration() {
        val initial = state()
        val current = mutableStateOf(initial.copy(
            controls = AppControls(manualProjectPicker = ManualProjectPickerState(projects = requireNotNull(initial.snapshot).projects)),
        ))
        fun search(query: String) {
            val value = current.value
            current.value = value.copy(controls = value.controls.copy(
                manualProjectPicker = value.controls.manualProjectPicker.copy(query = query),
            ))
        }
        val restorer = show {
            HistoryScreen(
                state = current.value, onSearch = {}, onSave = { _, _, _ -> },
                onDelete = { _, _, _ -> }, onLoadMore = {},
                onOpenProjectPicker = { search("") }, onSearchProjects = ::search,
                onLoadMoreProjects = {}, onCloseProjectPicker = { search("") },
            )
        }
        compose.onNodeWithContentDescription("Add entry").performClick()
        compose.onNodeWithTag("entry_project_picker").performClick()
        compose.onNodeWithTag("project_picker_search").performTextReplacement("Focus")

        restorer.emulateSavedInstanceStateRestore()

        compose.onNodeWithTag("project_picker_search").assertTextContains("Focus")
        compose.runOnIdle { assertEquals("Focus", current.value.controls.manualProjectPicker.query) }
    }
}
