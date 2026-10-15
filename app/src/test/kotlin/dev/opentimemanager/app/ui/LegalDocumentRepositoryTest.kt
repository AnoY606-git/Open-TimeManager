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
            listOf(LegalDocument.LICENSE, LegalDocument.NOTICE, LegalDocument.THIRD_PARTY_NOTICES),
            LegalDocument.entries,
        )
        val license = repository.read(LegalDocument.LICENSE)
        val notice = repository.read(LegalDocument.NOTICE)
        val thirdParty = repository.read(LegalDocument.THIRD_PARTY_NOTICES)

        assertTrue(license.contains("GNU GENERAL PUBLIC LICENSE"))
        assertTrue(notice.contains("Open TimeManager"))
        assertTrue(thirdParty.contains("AndroidX"))
        assertTrue(listOf(license, notice, thirdParty).all(String::isNotBlank))
    }
}
