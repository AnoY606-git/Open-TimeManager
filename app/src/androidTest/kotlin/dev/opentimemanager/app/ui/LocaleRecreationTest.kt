package dev.opentimemanager.app.ui

import android.app.LocaleManager
import android.app.Notification
import android.net.Uri
import android.os.LocaleList
import android.widget.TextView
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.app.LocaleManagerCompat
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.ViewModelProvider
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import dev.opentimemanager.app.MainActivity
import dev.opentimemanager.app.OpenTimeManagerApplication
import dev.opentimemanager.app.R
import dev.opentimemanager.app.data.EditResult
import dev.opentimemanager.app.data.ProjectEntity
import dev.opentimemanager.app.data.ProjectRepository
import dev.opentimemanager.app.dataexport.CsvExportResult
import dev.opentimemanager.app.system.TrackingNotification
import dev.opentimemanager.app.system.TrackingTileService
import dev.opentimemanager.app.tracking.LEGACY_COMMAND_GENERATION
import dev.opentimemanager.app.tracking.SystemSnapshot
import dev.opentimemanager.app.widget.TrackingWidgetProvider
import dev.opentimemanager.core.model.ClockSnapshot
import dev.opentimemanager.core.model.EntryId
import dev.opentimemanager.core.model.OpenSegment
import dev.opentimemanager.core.model.SegmentId
import dev.opentimemanager.core.model.TrackingSession
import dev.opentimemanager.core.model.TrackingState
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** External framework locales and non-Activity consumers on API 31/32 as well as newer Android. */
@RunWith(AndroidJUnit4::class)
class LocaleRecreationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    @SdkSuppress(minSdkVersion = 33)
    fun externalLocaleSelectionSurvivesRecreationAndSettingsReflectsIt() {
        val localeManager = compose.activity.getSystemService(LocaleManager::class.java)
        val russian = LocaleList.forLanguageTags("ru")
        try {
            localeManager.applicationLocales = russian
            compose.activityRule.scenario.recreate()
            compose.waitForIdle()
            compose.onNodeWithText("Настройки", substring = false).performClick()
            compose.onNodeWithTag("settings_category_appearance").performClick()
            compose.onNodeWithTag("language_russian").assertIsSelected()
            assertEquals("ru", localeManager.applicationLocales[0].language)
        } finally {
            localeManager.applicationLocales = LocaleList.getEmptyLocaleList()
        }
    }

    /** Includes Android 12/12L: their Application Context is not localized by AppCompat. */
    @Test
    @SdkSuppress(minSdkVersion = 31)
    fun reusedNonActivityConsumersFollowBothLanguageChangesIncludingDefaultProjectSearchAndCsv() {
        val app = compose.activity.application as OpenTimeManagerApplication
        lateinit var model: AppViewModel
        lateinit var original: LocaleListCompat
        compose.activityRule.scenario.onActivity { activity ->
            original = AppCompatDelegate.getApplicationLocales()
            model = ViewModelProvider(activity, AppViewModel.factory(app))[AppViewModel::class.java]
            model.navigate(AppDestination.SETTINGS)
        }
        compose.waitUntil(10_000) { model.state.value.snapshot != null }
        compose.onNodeWithTag("settings_category_appearance").performClick()
        val defaultProject = ProjectEntity(
            ProjectRepository.DEFAULT_PROJECT_ID.value, ProjectRepository.DEFAULT_PROJECT_STORAGE_NAME, 0, 0,
        )
        val snapshot = SystemSnapshot(
            TrackingSession.restore(
                TrackingState.RUNNING, 1, EntryId("locale-fixture"), ProjectRepository.DEFAULT_PROJECT_ID,
                OpenSegment(SegmentId("locale-open"), ClockSnapshot(1_000, 100, 1)), emptyList(),
            ),
            LEGACY_COMMAND_GENERATION, defaultProject, defaultProject,
        )
        // Both instances are created before the first switch and reused afterwards.
        val notification = TrackingNotification(app)
        val csv = app.container.csvExport
        val entryId = EntryId(UUID.randomUUID().toString())
        val range = UtcBounds(1_000, 61_000)
        val destination = File.createTempFile("locale-csv-", ".csv", app.cacheDir)
        try {
            assertEquals(EditResult.Applied, runBlocking {
                app.container.completedEntries.save(entryId, ProjectRepository.DEFAULT_PROJECT_ID, "", 1_000, 60_000, null)
            })
            for ((language, defaultName, running, pause, stop) in listOf(
                listOf("russian", "Без проекта", "Идёт учёт", "Пауза", "Завершить"),
                listOf("english", "No project", "Tracking", "Pause", "Stop"),
            )) {
                val tag = if (language == "russian") "ru" else "en"
                compose.onNodeWithTag("language_$language").performScrollTo().performClick()
                compose.waitUntil(10_000) {
                    LocaleManagerCompat.getApplicationLocales(app).toLanguageTags() == tag
                }
                compose.waitForIdle()
                compose.onNodeWithTag("language_$language").assertIsSelected()

                val rendered = notification.build(snapshot, privacyMode = false)
                assertEquals(running, rendered.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
                assertEquals(defaultName, rendered.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
                assertEquals(listOf(pause, stop), rendered.actions.map { it.title.toString() })
                assertEquals(running, TrackingTileService.presentation(app, snapshot).label)
                val widget = TrackingWidgetProvider.views(app, snapshot, privacyMode = false).apply(app, null)
                assertEquals(defaultName, widget.findViewById<TextView>(R.id.widget_status).text.toString())
                assertEquals(pause, widget.findViewById<TextView>(R.id.widget_primary).text.toString())

                // No pinned default can hide a mismatch between the displayed name and the search.
                compose.activityRule.scenario.onActivity {
                    model.openManualProjectPicker("not-the-default-project")
                    model.searchManualProjects(defaultName)
                }
                compose.waitUntil(10_000) {
                    val picker = model.state.value.controls.manualProjectPicker
                    picker.query == defaultName && !picker.loading
                }
                assertTrue(model.state.value.controls.manualProjectPicker.projects.any {
                    it.id == ProjectRepository.DEFAULT_PROJECT_ID.value
                })
                compose.activityRule.scenario.onActivity { model.closeManualProjectPicker() }

                assertEquals(CsvExportResult.Success, runBlocking {
                    csv.exportCsv(range, Uri.fromFile(destination), newlyCreated = false)
                })
                val row = destination.readLines().drop(1).map { it.split(',') }.single { it.getOrNull(1) == entryId.value }
                assertEquals(defaultName, row[3])
            }
        } finally {
            destination.delete()
            runBlocking { app.container.completedEntries.delete(entryId, 0) }
            compose.activityRule.scenario.onActivity {
                model.closeManualProjectPicker()
                AppCompatDelegate.setApplicationLocales(original)
            }
            compose.waitUntil(10_000) { LocaleManagerCompat.getApplicationLocales(app) == original }
        }
    }
}
