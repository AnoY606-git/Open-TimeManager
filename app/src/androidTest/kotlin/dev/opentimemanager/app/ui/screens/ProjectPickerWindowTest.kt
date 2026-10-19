package dev.opentimemanager.app.ui.screens

import android.os.ParcelFileDescriptor
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.opentimemanager.app.data.ProjectEntity
import dev.opentimemanager.app.settings.ThemeMode
import dev.opentimemanager.app.ui.theme.OpenTimeManagerTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.roundToInt

/** Проверяет реальное окно диалога: ForcedSize родителя не ограничивает отдельный Dialog. */
@RunWith(AndroidJUnit4::class)
class ProjectPickerWindowTest {
    @get:Rule val compose = createComposeRule()

    @Test fun projectRemainsReachableInShortLandscapeWithLargeText() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val resources = instrumentation.targetContext.resources
        val originalOrientation = resources.configuration.orientation
        val previousOverride = shell("wm size").lineSequence()
            .firstOrNull { it.startsWith("Override size:") }?.substringAfter(":")?.trim()
        val density = resources.displayMetrics.density
        val chosen = mutableStateOf<String?>(null)
        try {
            shell("wm size ${(800 * density).roundToInt()}x${(280 * density).roundToInt()}")
            compose.waitUntil(10_000) { resources.configuration.screenWidthDp > resources.configuration.screenHeightDp }
            compose.setContent {
                DeviceConfigurationOverride(DeviceConfigurationOverride.FontScale(2f)) {
                    OpenTimeManagerTheme(ThemeMode.LIGHT, false) {
                        ProjectChooserDialog(
                            projects = listOf(ProjectEntity("focus", "Focus project", 0xff6251da.toInt(), 0)),
                            selectedProjectId = null, query = "", onSearch = {}, hasMore = false,
                            loading = false, onLoadMore = {}, onSelect = { chosen.value = it.id },
                            onDismiss = {}, onCreate = {},
                        )
                    }
                }
            }
            compose.onNodeWithTag("project_picker_list").performScrollToNode(hasTestTag("project_choice_focus"))
            compose.onNodeWithTag("project_choice_focus").assertIsDisplayed().performClick()
            compose.runOnIdle { assertEquals("focus", chosen.value) }
        } finally {
            shell("wm size ${previousOverride ?: "reset"}")
            compose.waitUntil(10_000) { resources.configuration.orientation == originalOrientation }
        }
    }

    private fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command),
    ).bufferedReader().use { it.readText() }
}
