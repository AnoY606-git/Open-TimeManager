package dev.opentimemanager.app.ui

import android.app.LocaleManager
import android.os.LocaleList
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import dev.opentimemanager.app.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Regression for an Android 13+ App Language change made outside the app. */
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
            compose.onNodeWithTag("language_russian").assertIsSelected()
            assertEquals("ru", localeManager.applicationLocales[0].language)
        } finally {
            localeManager.applicationLocales = LocaleList.getEmptyLocaleList()
        }
    }
}
