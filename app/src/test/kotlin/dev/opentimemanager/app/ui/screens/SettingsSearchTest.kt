package dev.opentimemanager.app.ui.screens

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsSearchTest {
    private val entries = listOf(
        SettingsSearchEntry(SettingsPage.APPEARANCE, "Appearance", "Theme and language", "colors dark light"),
        SettingsSearchEntry(SettingsPage.TRACKING, "Tracking", "Default project and daily goal", "privacy minutes"),
        SettingsSearchEntry(SettingsPage.DATA, "Backups", "Copies and restore", "password encrypted export import"),
    )

    @Test
    fun blankQueryKeepsAllSectionsInTheirNavigationOrder() {
        assertEquals(
            listOf(SettingsPage.APPEARANCE, SettingsPage.TRACKING, SettingsPage.DATA),
            searchSettingsEntries(entries, " \n \t").map { it.page },
        )
    }

    @Test
    fun settingKeywordFindsItsOwningSectionInsteadOfRequiringSectionTitle() {
        assertEquals(listOf(SettingsPage.APPEARANCE), searchSettingsEntries(entries, "language").map { it.page })
        assertEquals(listOf(SettingsPage.TRACKING), searchSettingsEntries(entries, "daily goal").map { it.page })
        assertEquals(listOf(SettingsPage.DATA), searchSettingsEntries(entries, "encrypted").map { it.page })
    }

    @Test
    fun allWordsMustMatchTheSameSectionAcrossTitleSummaryAndKeywords() {
        assertEquals(listOf(SettingsPage.DATA), searchSettingsEntries(entries, " BACKUPS\tPASSWORD ").map { it.page })
        assertEquals(emptyList<SettingsPage>(), searchSettingsEntries(entries, "language password").map { it.page })
    }

    @Test
    fun localizedKeywordsAreMatchedWithoutChangingTheirSection() {
        val russian = listOf(
            SettingsSearchEntry(SettingsPage.APPEARANCE, "Оформление", "Тема и язык", "цвета светлая тёмная"),
            SettingsSearchEntry(SettingsPage.TRACKING, "Учёт времени", "Дневная цель", "проект минуты приватность"),
            SettingsSearchEntry(SettingsPage.DATA, "Резервные копии", "Создание и восстановление", "пароль шифрование экспорт"),
        )
        assertEquals(listOf(SettingsPage.APPEARANCE), searchSettingsEntries(russian, "ЯЗЫК").map { it.page })
        assertEquals(listOf(SettingsPage.TRACKING), searchSettingsEntries(russian, "дневная цель").map { it.page })
        assertEquals(listOf(SettingsPage.DATA), searchSettingsEntries(russian, "восстанов").map { it.page })
    }

    @Test
    fun russianYoAndYeMatchInterchangeablyInBothQueryAndIndex() {
        val entriesWithYo = listOf(SettingsSearchEntry(SettingsPage.APPEARANCE, "Тёмная тема", "Цвета", "оформление"))
        val entriesWithYe = listOf(SettingsSearchEntry(SettingsPage.APPEARANCE, "Темная тема", "Цвета", "оформление"))
        assertEquals(listOf(SettingsPage.APPEARANCE), searchSettingsEntries(entriesWithYo, "темная").map { it.page })
        assertEquals(listOf(SettingsPage.APPEARANCE), searchSettingsEntries(entriesWithYe, "тёмная").map { it.page })
    }

    @Test
    fun matchingIsIndependentOfTheDevicesCaseConversionLocale() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            assertEquals(listOf(SettingsPage.TRACKING), searchSettingsEntries(entries, "PRIVACY").map { it.page })
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun unknownQueryReturnsNoSections() {
        assertEquals(emptyList<SettingsPage>(), searchSettingsEntries(entries, "unfindable").map { it.page })
    }
}
