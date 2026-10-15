package dev.opentimemanager.app.ui

import androidx.test.core.app.ApplicationProvider
import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], application = Application::class)
class LegalDocumentRepositoryTest {
    @Test
    fun packagedLegalDocumentsAreCompleteAndReadableOffline() {
        val repository = LegalDocumentRepository(ApplicationProvider.getApplicationContext())

        assertEquals(
            listOf(
                LegalDocument.LICENSE,
                LegalDocument.NOTICE,
                LegalDocument.ATTRIBUTION,
                LegalDocument.THIRD_PARTY_NOTICES,
            ),
            LegalDocument.entries,
        )
        val license = repository.read(LegalDocument.LICENSE)
        val notice = repository.read(LegalDocument.NOTICE)
        val attribution = repository.read(LegalDocument.ATTRIBUTION)
        val thirdParty = repository.read(LegalDocument.THIRD_PARTY_NOTICES)

        assertTrue(license.contains("GNU GENERAL PUBLIC LICENSE"))
        assertTrue(notice.contains("Open TimeManager"))
        assertTrue(attribution.contains("OpenAuth"))
        assertTrue(attribution.contains("SplitBox"))
        assertTrue(thirdParty.contains("AndroidX"))
        assertTrue(thirdParty.contains("Okio"))
        assertTrue(thirdParty.contains("Guava ListenableFuture"))
        assertTrue(thirdParty.contains("JSpecify"))
        assertTrue(thirdParty.contains("datastore-preferences-external-protobuf"))
        assertTrue(thirdParty.contains("BSD-3-Clause"))
        assertTrue(listOf(license, notice, attribution, thirdParty).all(String::isNotBlank))
    }
}
