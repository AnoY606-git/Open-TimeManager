package dev.opentimemanager.app.ui

import android.content.Context
import android.content.ContextWrapper
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import dev.opentimemanager.app.R
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class PlatformIntegrationTest {
    @Test fun manufacturerHintsAreCaseInsensitiveAndDoNotGuessCustomRoms() {
        assertEquals(DeviceFamily.XIAOMI, deviceFamily("Xiaomi", "POCO"))
        assertEquals(DeviceFamily.SAMSUNG, deviceFamily("samsung", "Samsung"))
        assertEquals(DeviceFamily.OPPO, deviceFamily("OnePlus", "oneplus"))
        assertEquals(DeviceFamily.OPPO, deviceFamily("realme", "realme"))
        assertEquals(DeviceFamily.OTHER, deviceFamily("Google", "google"))
        assertEquals(DeviceFamily.OTHER, deviceFamily("not-xiaomi", "custom"))
    }

    @Test fun unavailableBackgroundServicesAreUnknownRatherThanReportedAsUnrestricted() {
        val context = object : ContextWrapper(ApplicationProvider.getApplicationContext()) {
            override fun getSystemService(name: String): Any? = throw SecurityException("Unavailable")
        }
        val status = readDeviceBackgroundStatus(context)
        assertNull(status.powerSave)
        assertNull(status.backgroundRestricted)
    }

    @Test fun manufacturerHintsDoNotTreatWhitespaceOrUnrelatedNamesAsRomEvidence() {
        assertEquals(DeviceFamily.XIAOMI, deviceFamily(" Xiaomi ", " POCO "))
        assertEquals(DeviceFamily.OTHER, deviceFamily("custom", "not-samsung"))
    }

    @Test fun android12TileRequestExplainsManualPlacementWithoutCallingNewApi() {
        var feedback: Int? = null
        requestTrackingTile(ApplicationProvider.getApplicationContext()) { feedback = it }
        assertEquals(R.string.platform_tile_manual, feedback)
    }

    @Test fun appSettingsUsesPublicPackageScopedIntentNotVendorComponents() {
        val intent = appSystemSettingsIntent("dev.opentimemanager.app")
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, intent.action)
        assertEquals("package:dev.opentimemanager.app", intent.dataString)
        assertNull(intent.component)
    }
}
