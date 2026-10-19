package dev.opentimemanager.app.widget

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import android.graphics.Rect
import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.SizeF
import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.ImageButton
import android.widget.RemoteViews
import androidx.core.graphics.ColorUtils
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
@Config(sdk = [31, 34], application = Application::class)
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

    @Test fun productionThemeFollowsSystemNightModeWithReadableTextAndIcons() {
        for (dark in listOf(true, false)) {
            val widget = render(widgetContext("en", dark), widthDp = 180, privacyMode = true)
            assertPalette(widget, dark)
        }
    }

    @Test fun systemPaletteDoesNotDependOnLauncherLightTheme() {
        val context = ContextThemeWrapper(widgetContext("en", dark = true), android.R.style.Theme_Material_Light)
        val widget = render(context, widthDp = 216, heightDp = 222, privacyMode = true)
        assertPalette(widget, dark = true)
    }

    @Test fun tallAndWideHostsUseHorizontalActionsAndKeepContentInsideCard() {
        for (locale in listOf("en", "ru")) for (dark in listOf(false, true)) {
            for ((width, height) in listOf(180 to 180, 216 to 222, 300 to 110, 300 to 180, 368 to 222)) {
                val widget = render(widgetContext(locale, dark), width, privacyMode = true, heightDp = height)
                val primary = widget.findViewById<View>(R.id.widget_primary)
                val stop = widget.findViewById<View>(R.id.widget_stop)
                val scenario = "$locale dark=$dark responsive ${width}x${height}dp"
                assertActionFits(widget, R.id.widget_primary, scenario)
                assertActionFits(widget, R.id.widget_stop, scenario)
                assertTextFits(widget, R.id.widget_timer, scenario)
                assertEquals("$scenario actions share a row", primary.top, stop.top)
                assertTrue("$scenario actions have a gap", stop.left - primary.right >= widget.context.dp(8))
                if (height >= 180) {
                    assertTrue("$scenario primary uses footer width", primary.width > widget.context.dp(48))
                }
                if (height >= 222) assertTrue("$scenario timer uses the card center", visibleTimer(widget).height >= widget.context.dp(60))
            }
        }
    }

    @Test fun idleTallHostLetsPrimaryFillTheFooter() {
        val widget = render(widgetContext("en", dark = false), 216, privacyMode = true, heightDp = 222, idle = true)
        val primary = widget.findViewById<View>(R.id.widget_primary)
        assertEquals(View.GONE, widget.findViewById<View>(R.id.widget_stop).visibility)
        assertTrue("Idle primary should fill the footer", primary.width > widget.width * 0.8f)
    }

    @Test fun wideTallHostsKeepFullWidthFooterInsteadOfCompactControls() {
        for ((width, height) in listOf(300 to 180, 368 to 222)) {
            val widget = render(widgetContext("en", dark = true), width, privacyMode = true, heightDp = height)
            val primary = widget.findViewById<View>(R.id.widget_primary)
            val bounds = Rect(0, 0, primary.width, primary.height)
            widget.offsetDescendantRectToMyCoords(primary, bounds)
            assertTrue("${width}x${height}dp uses broad footer actions", primary.width > widget.width * 0.3f)
            assertTrue("${width}x${height}dp places actions at the card bottom", bounds.bottom >= widget.height - widget.context.dp(20))
        }
    }

    private fun assertPalette(widget: ViewGroup, dark: Boolean) {
        val background = backgroundColor(widget)
        val luminance = ColorUtils.calculateLuminance(background)
        assertTrue(
            "Widget surface must follow system dark=$dark; luminance=$luminance",
            if (dark) luminance < 0.1 else luminance > 0.8,
        )
        for (id in listOf(R.id.widget_status, R.id.widget_timer)) {
            val foreground = (if (id == R.id.widget_timer) visibleTimer(widget) else widget.findViewById<TextView>(id)).currentTextColor
            val contrast = ColorUtils.calculateContrast(foreground, background)
            assertTrue("Widget text dark=$dark contrast=$contrast must reach 4.5", contrast >= 4.5)
        }
        for (id in listOf(R.id.widget_primary, R.id.widget_stop)) {
            val button = widget.findViewById<ImageButton>(id)
            val tint = requireNotNull(button.imageTintList)
            val foreground = tint.getColorForState(button.drawableState, tint.defaultColor)
            val contrast = ColorUtils.calculateContrast(foreground, ColorUtils.compositeColors(backgroundColor(button), background))
            assertTrue("Widget control dark=$dark contrast=$contrast must reach 4.5", contrast >= 4.5)
        }
    }

    private fun backgroundColor(view: View): Int {
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        val originalBounds = Rect(view.background.bounds)
        return try {
            view.background.setBounds(0, 0, view.width, view.height)
            view.background.draw(Canvas(bitmap))
            bitmap.getPixel(view.width / 2, view.height / 2)
        } finally {
            view.background.bounds = originalBounds
            bitmap.recycle()
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
            application.applicationInfo.theme,
        )
    }

    private fun render(context: Context, widthDp: Int, privacyMode: Boolean, heightDp: Int = 110, idle: Boolean = false): ViewGroup {
        val project = ProjectEntity("project", PROJECT_NAME, 0, 0)
        val snapshot = SystemSnapshot(
            if (idle) TrackingSession.idle() else TrackingSession.restore(
                TrackingState.PAUSED, 1, EntryId("entry"), ProjectId("project"), null,
                listOf(Segment(SegmentId("segment"), EntryId("entry"), ProjectId("project"), 0, 28_800_000)),
            ),
            LEGACY_COMMAND_GENERATION, project, project,
        )
        val remote = TrackingWidgetProvider.views(context, snapshot, privacyMode, 0)
        // В JVM-тесте используем тот же выбор размера, который делает host; метод скрыт из публичного SDK.
        val selected = RemoteViews::class.java.getMethod("getRemoteViewsToApply", Context::class.java, SizeF::class.java)
            .invoke(remote, context, SizeF(widthDp.toFloat(), heightDp.toFloat())) as RemoteViews
        return (selected.apply(context, null) as ViewGroup).apply {
            val width = context.dp(widthDp)
            val height = context.dp(heightDp)
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
        val text = if (id == R.id.widget_timer) visibleTimer(widget) else widget.findViewById<TextView>(id)
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

    private fun visibleTimer(widget: ViewGroup): TextView = widget.findViewById<TextView>(R.id.widget_timer)
        .takeIf { it.visibility == View.VISIBLE } ?: widget.findViewById(R.id.widget_frozen_timer)

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
