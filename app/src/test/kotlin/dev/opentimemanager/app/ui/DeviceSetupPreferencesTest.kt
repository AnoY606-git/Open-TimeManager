package dev.opentimemanager.app.ui

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class DeviceSetupPreferencesTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before fun clearLocalChoice() {
        context.getSharedPreferences("device_setup", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun cleanInstallationRemainsPendingAcrossProcessOwnersUntilExplicitDismissal() {
        val first = DeviceSetupPreferences(context)
        assertTrue(first.initialize(existingInstallation = false))
        assertEquals(DeviceSetupState.PENDING, DeviceSetupPreferences(context).state())
        assertTrue(DeviceSetupPreferences(context).initialize(existingInstallation = true))
    }

    @Test fun skippedWizardDoesNotAppearOnRelaunch() {
        val first = DeviceSetupPreferences(context)
        first.initialize(existingInstallation = false)
        assertTrue(first.finish(DeviceSetupState.SKIPPED))
        assertFalse(DeviceSetupPreferences(context).initialize(existingInstallation = false))
        assertEquals(DeviceSetupState.SKIPPED, DeviceSetupPreferences(context).state())
    }

    @Test fun completedWizardDoesNotAppearOnRelaunchOrUpgrade() {
        val first = DeviceSetupPreferences(context)
        first.initialize(existingInstallation = false)
        assertTrue(first.finish(DeviceSetupState.COMPLETED))
        assertFalse(DeviceSetupPreferences(context).initialize(existingInstallation = true))
        assertEquals(DeviceSetupState.COMPLETED, DeviceSetupPreferences(context).state())
    }

    @Test fun existingInstallationIsNotPromptedEvenWhenNoOtherPreferencesWereChanged() {
        assertFalse(DeviceSetupPreferences(context).initialize(existingInstallation = true))
        assertFalse(DeviceSetupPreferences(context).initialize(existingInstallation = false))
    }

    @Test fun unreadableChoiceIsConservativeAndDoesNotCreateRepeatedPrompts() {
        context.getSharedPreferences("device_setup", Context.MODE_PRIVATE).edit().putInt("state", 7).commit()
        assertFalse(DeviceSetupPreferences(context).initialize(existingInstallation = false))
    }

    @Test fun existingDataAndUpgradesPreventAutomaticWizardButFreshInstallIsEligible() {
        assertFalse(isExistingDeviceSetupInstallation(100L, 100L, hasLocalData = false))
        assertTrue(isExistingDeviceSetupInstallation(100L, 200L, hasLocalData = false))
        assertTrue(isExistingDeviceSetupInstallation(100L, 100L, hasLocalData = true))
        assertTrue(isExistingDeviceSetupInstallation(null, null, hasLocalData = false))
        assertTrue(isExistingDeviceSetupInstallation(0L, 0L, hasLocalData = false))
    }
}
