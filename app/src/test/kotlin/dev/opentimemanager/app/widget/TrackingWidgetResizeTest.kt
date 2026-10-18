package dev.opentimemanager.app.widget

import android.app.Application
import android.appwidget.AppWidgetManager
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 34], application = Application::class)
class TrackingWidgetResizeTest {
    @Test fun resizingReusesHostViewsWithoutReadingRoomOrStartingAsyncWork() {
        // A static layout resize must not need the application's tracking container at all.
        val context = ApplicationProvider.getApplicationContext<Application>()
        val provider = TrackingWidgetProvider()
        provider.onAppWidgetOptionsChanged(context, AppWidgetManager.getInstance(context), 42, Bundle().apply {
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 240)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 160)
        })
        assertFalse(shadowOf(provider).wentAsync())
    }
}
