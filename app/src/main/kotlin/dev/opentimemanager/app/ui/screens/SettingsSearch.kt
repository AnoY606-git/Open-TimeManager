package dev.opentimemanager.app.ui.screens

import java.util.Locale

internal data class SettingsSearchEntry(
    val page: SettingsPage,
    val title: String,
    val summary: String,
    val keywords: String,
    val settingLabels: List<String> = emptyList(),
)

internal fun searchSettingsEntries(entries: List<SettingsSearchEntry>, query: String): List<SettingsSearchEntry> {
    val words = query.trim().normalizedSettingsSearch().split(Regex("\\s+")).filter(String::isNotEmpty)
    if (words.isEmpty()) return entries
    return entries.filter { entry ->
        val searchable = (listOf(entry.title, entry.summary, entry.keywords) + entry.settingLabels)
            .joinToString(" ").normalizedSettingsSearch()
        words.all(searchable::contains)
    }
}

private fun String.normalizedSettingsSearch(): String = lowercase(Locale.ROOT).replace('ё', 'е')
