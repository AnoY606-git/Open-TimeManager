package dev.opentimemanager.app.widget

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import android.graphics.Rect
import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import dev.opentimemanager.app.R
import dev.opentimemanager.app.data.ProjectEntity
import dev.opentimemanager.app.tracking.LEGACY_COMMAND_GENERATION
import dev.opentimemanager.app.tracking.SystemSnapshot
import dev.opentimemanager.core.model.EntryId
import dev.opentimemanager.core.model.ProjectId
import dev.opentimemanager.core.model.Segment
import dev.opentimemanager.core.model.SegmentId
import dev.opentimemanager.core.model.TrackingSession
import dev.opentimemanager.core.model.TrackingState
import java.util.Locale
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TrackingWidgetLayoutTest {
    @Test fun minimumHostKeepsTimerAndActionsInsideBoundsAtLargeFont() {
        for (locale in listOf("en", "ru")) {
            for (dark in listOf(false, true)) {
                for (width in listOf(180, 240)) {
                    val context = widgetContext(locale, dark)
                    val widget = render(context, width, privacyMode = true)
                    val scenario = "$locale dark=$dark ${width}x110dp font=200%"
                    assertActionFits(widget, R.id.widget_primary, scenario)
                    assertActionFits(widget, R.id.widget_stop, scenario)
                    assertTextFits(widget, R.id.widget_timer, scenario)
                    val status = widget.findViewById<TextView>(R.id.widget_status)
                    assertEquals("$scenario status must stay on one line", 1, status.lineCount)
                }
            }
        }
    }

    @Test fun longProjectNameKeepsTimerAndActionsVisibleWithoutLosingAccessibleTitle() {
        val context = widgetContext("ru", dark = true)
        val widget = render(context, widthDp = 180, privacyMode = false)
        assertActionFits(widget, R.id.widget_primary, "long project")
        assertActionFits(widget, R.id.widget_stop, "long project")
        assertTextFits(widget, R.id.widget_timer, "long project")
        val status = widget.findViewById<TextView>(R.id.widget_status)
        assertEquals(1, status.lineCount)
        assertEquals(PROJECT_NAME, status.contentDescription.toString())
    }

    @Test fun iconActionsKeepFullLocalizedAccessibleLabels() {
        for ((locale, primary, stop) in listOf(
            Triple("en", "Resume", "Stop"),
            Triple("ru", "Продолжить", "Завершить"),
        )) {
            val widget = render(widgetContext(locale, dark = false), widthDp = 180, privacyMode = true)
            assertEquals(primary, widget.findViewById<View>(R.id.widget_primary).contentDescription?.toString())
            assertEquals(stop, widget.findViewById<View>(R.id.widget_stop).contentDescription?.toString())
            assertFalse(widget.findViewById<TextView>(R.id.widget_status).contentDescription.toString().contains(PROJECT_NAME))
        }
    }

    private fun widgetContext(locale: String, dark: Boolean): Context {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val configuration = Configuration(application.resources.configuration).apply {
            fontScale = 2f
            setLocale(Locale.forLanguageTag(locale))
            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
        }
        return ContextThemeWrapper(
            application.createConfigurationContext(configuration),
            if (dark) android.R.style.Theme_Material else android.R.style.Theme_Material_Light,
        )
    }

    private fun render(context: Context, widthDp: Int, privacyMode: Boolean): ViewGroup {
        val project = ProjectEntity("project", PROJECT_NAME, 0, 0)
        val snapshot = SystemSnapshot(
            TrackingSession.restore(
                TrackingState.PAUSED, 1, EntryId("entry"), ProjectId("project"), null,
                listOf(Segment(SegmentId("segment"), EntryId("entry"), ProjectId("project"), 0, 28_800_000)),
            ),
            LEGACY_COMMAND_GENERATION, project, project,
        )
        return (TrackingWidgetProvider.views(context, snapshot, privacyMode, 0).apply(context, null) as ViewGroup).apply {
            val width = context.dp(widthDp)
            val height = context.dp(110)
            measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
            )
            layout(0, 0, width, height)
        }
    }

    private fun assertActionFits(widget: ViewGroup, id: Int, scenario: String) {
        val action = widget.findViewById<View>(id)
        assertEquals("$scenario action visible", View.VISIBLE, action.visibility)
        assertTrue("$scenario action touch width", action.width >= action.context.dp(48))
        assertTrue("$scenario action touch height", action.height >= action.context.dp(48))
        assertInside(widget, action, scenario)
        if (action is TextView) assertTextFits(widget, id, scenario)
    }

    private fun assertTextFits(widget: ViewGroup, id: Int, scenario: String) {
        val text = widget.findViewById<TextView>(id)
        assertInside(widget, text, scenario)
        val layout = requireNotNull(text.layout)
        val availableWidth = text.width - text.compoundPaddingLeft - text.compoundPaddingRight
        val availableHeight = text.height - text.compoundPaddingTop - text.compoundPaddingBottom
        assertTrue("$scenario complete text height: ${layout.height} > $availableHeight", layout.height <= availableHeight)
        for (line in 0 until layout.lineCount) {
            assertEquals("$scenario timer/action text must not ellipsize", 0, layout.getEllipsisCount(line))
            assertTrue("$scenario complete text width: ${layout.getLineWidth(line)} > $availableWidth", layout.getLineWidth(line) <= availableWidth + 1f)
        }
    }

    private fun assertInside(widget: ViewGroup, child: View, scenario: String) {
        val bounds = Rect(0, 0, child.width, child.height)
        widget.offsetDescendantRectToMyCoords(child, bounds)
        assertTrue("$scenario child outside ${widget.width}x${widget.height}: $bounds", Rect(0, 0, widget.width, widget.height).contains(bounds))
    }

    private fun Context.dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()

    companion object {
        private const val PROJECT_NAME = "Очень длинное название проекта, которое должно оставаться доступным целиком"
    }
}
