package dev.opentimemanager.app.ui.components

import android.app.Application
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], application = Application::class)
class DurationTextFitTest {
    @Test fun timerAndLargeReportTotalsFitThe320DpCardAtNormalAndEnlargedText() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        for (fontScale in listOf(1f, 1.3f, 2f)) {
            val measurer = TextMeasurer(
                defaultFontFamilyResolver = createFontFamilyResolver(context),
                defaultDensity = Density(1f, fontScale),
                defaultLayoutDirection = LayoutDirection.Ltr,
            )
            val style = TextStyle(
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.Medium,
                fontSize = 68.sp,
                lineHeight = 76.sp,
                letterSpacing = (-1).sp,
                fontFeatureSettings = "tnum",
            )
            for (text in listOf("0:00:00", "123:45:59", "2562047788015:12")) {
                for (availableWidth in listOf(232, 248)) {
                    val fitted = fittedDurationStyle(text, style, measurer, availableWidth)
                    val measured = measurer.measure(text, fitted, softWrap = false, maxLines = 1)
                    assertTrue("$text at fontScale=$fontScale exceeded $availableWidth", measured.size.width <= availableWidth)
                    assertEquals(1, measured.lineCount)
                    assertTrue(fitted.fontSize.value <= style.fontSize.value)
                }
            }
        }
    }
}
