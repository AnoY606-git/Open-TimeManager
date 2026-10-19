package dev.opentimemanager.app.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.Locales
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
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
import dev.opentimemanager.app.settings.AppLanguage
import dev.opentimemanager.app.settings.ThemeMode
import dev.opentimemanager.app.tracking.AppSnapshot
import dev.opentimemanager.app.ui.theme.OpenTimeManagerTheme
import dev.opentimemanager.core.model.EntryId
import dev.opentimemanager.core.model.ProjectId
import dev.opentimemanager.core.model.TrackingSession
import dev.opentimemanager.core.model.TrackingState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TimerDraftRestorationTest {
    @get:Rule val compose = createComposeRule()
    private val project = ProjectEntity("focus", "Focus project", 0xff6251da.toInt(), 0)
    private val entry = EntryEntity("active", project.id, "Saved note", 1_000, null, "TIMER", 1, false, currentSlot = 1)
    private val state = AppUiState(
        snapshot = AppSnapshot(
            TrackingSession.restore(
                TrackingState.PAUSED, 1, EntryId(entry.id), ProjectId(project.id), null, emptyList(),
                confirmedDurationMillis = 60_000, lastConfirmedEndUtcMillis = 61_000,
            ),
            listOf(project), listOf(entry), emptyList(),
        ),
    )

    private fun show(layout: androidx.compose.runtime.State<NavigationLayout> = mutableStateOf(NavigationLayout.RAIL)): StateRestorationTester {
        val restoration = StateRestorationTester(compose)
        val overrides = DeviceConfigurationOverride.ForcedSize(DpSize(1_000.dp, 900.dp)) then
            DeviceConfigurationOverride.Locales(LocaleList(Locale("en")))
        restoration.setContent {
            DeviceConfigurationOverride(overrides) {
                OpenTimeManagerTheme(ThemeMode.LIGHT, false) {
                    AdaptiveTimerSurface(state, layout.value, compactTimer = true)
                }
            }
        }
        return restoration
    }

    @Test fun unsavedActiveNoteSurvivesSavedStateRestoration() {
        val restoration = show()
        compose.onNodeWithTag("timer_list").performScrollToNode(hasSetTextAction())
        compose.onNode(hasSetTextAction()).performTextReplacement("Keep this draft")
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("timer_list").performScrollToNode(hasSetTextAction())
        compose.onNode(hasSetTextAction()).assertTextContains("Keep this draft")
    }

    @Test fun unsavedActiveNoteSurvivesAdaptivePaneChanges() {
        val layout = mutableStateOf(NavigationLayout.RAIL)
        show(layout)
        compose.onNodeWithTag("timer_list").performScrollToNode(hasSetTextAction())
        compose.onNode(hasSetTextAction()).performTextReplacement("Keep across resize")
        compose.runOnIdle { layout.value = NavigationLayout.TWO_PANE }
        compose.onNodeWithTag("timer_list").performScrollToNode(hasSetTextAction())
        compose.onNode(hasSetTextAction()).assertTextContains("Keep across resize")
        compose.runOnIdle { layout.value = NavigationLayout.RAIL }
        compose.onNodeWithTag("timer_list").performScrollToNode(hasSetTextAction())
        compose.onNode(hasSetTextAction()).assertTextContains("Keep across resize")
    }

    @Test fun newProjectNameSurvivesSavedStateRestoration() {
        val restoration = show()
        compose.onNodeWithTag("timer_project_picker").performClick()
        compose.onNodeWithText("Create project").performClick()
        compose.onNodeWithTag("project_create_name").performTextReplacement("Design review")
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("project_create_name").assertTextContains("Design review")
    }

    @Test fun unsavedNoteSurvivesBottomNavigationRailAndTwoPaneTransitions() {
        val width = mutableStateOf(412)
        compose.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(width.value.dp, 900.dp))) {
                OpenTimeManagerTheme(ThemeMode.LIGHT, false) {
                    OpenTimeManagerApp(state, null, NotificationControlStatus.AVAILABLE, {}, AppLanguage.ENGLISH, {})
                }
            }
        }
        compose.onNodeWithTag("timer_list").performScrollToNode(hasSetTextAction())
        compose.onNode(hasSetTextAction()).performTextReplacement("Keep through every layout")
        listOf(700, 1_000, 412).forEach { nextWidth ->
            compose.runOnIdle { width.value = nextWidth }
            compose.onNodeWithTag("timer_list").performScrollToNode(hasSetTextAction())
            compose.onNode(hasSetTextAction()).assertTextContains("Keep through every layout")
        }
    }

    @Test fun restoredNoteDraftCannotMoveToAnotherActiveEntry() = assertRestoredNoteOwner(
        entry.copy(id = "another", note = "Saved note for another entry"),
    )

    @Test fun restoredNoteDraftCannotCrossDatabaseReplacementWithTheSameEntryId() = assertRestoredNoteOwner(
        entry, generation = "replacement database",
    )

    @Test fun restoredNoteDraftCannotOverrideANewerPersistedNote() = assertRestoredNoteOwner(
        entry.copy(note = "A newer saved note"),
    )

    private fun assertRestoredNoteOwner(replacement: EntryEntity, generation: String = requireNotNull(state.snapshot).generation) {
        val current = mutableStateOf(state)
        val next = state.copy(snapshot = requireNotNull(state.snapshot).copy(
            session = TrackingSession.restore(
                TrackingState.PAUSED, 1, EntryId(replacement.id), ProjectId(project.id), null, emptyList(),
                confirmedDurationMillis = 60_000, lastConfirmedEndUtcMillis = 61_000,
            ),
            entries = listOf(replacement),
            generation = generation,
        ))
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.Locales(LocaleList(Locale("en")))) {
                OpenTimeManagerTheme(ThemeMode.LIGHT, false) {
                    DisposableEffect(Unit) { onDispose { current.value = next } }
                    AdaptiveTimerSurface(current.value, NavigationLayout.RAIL, compactTimer = true)
                }
            }
        }
        compose.onNodeWithTag("timer_list").performScrollToNode(hasSetTextAction())
        compose.onNode(hasSetTextAction()).performTextReplacement("Draft owned by the original entry")

        restoration.emulateSavedInstanceStateRestore()

        compose.onNodeWithTag("timer_list").performScrollToNode(hasSetTextAction())
        compose.onNode(hasSetTextAction()).assertTextContains(replacement.note)
        compose.onNodeWithText("Save note").assertIsNotEnabled()
    }
}
