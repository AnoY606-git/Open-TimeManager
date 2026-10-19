package dev.opentimemanager.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.opentimemanager.app.R
import dev.opentimemanager.app.ui.LegalDocument
import dev.opentimemanager.app.ui.components.AppGlyph
import dev.opentimemanager.app.ui.components.AppIcon
import dev.opentimemanager.app.ui.components.AppMessageCard
import java.util.Locale

@Composable
internal fun settingsSearchEntries(): List<SettingsSearchEntry> =
    SettingsPage.entries.filter { it != SettingsPage.OVERVIEW }.map { page ->
        SettingsSearchEntry(
            page = page,
            title = stringResource(page.title),
            summary = stringResource(page.summary),
            keywords = stringResource(when (page) {
                SettingsPage.APPEARANCE -> R.string.settings_search_keywords_appearance
                SettingsPage.TRACKING -> R.string.settings_search_keywords_tracking
                SettingsPage.DATA -> R.string.settings_search_keywords_data
                SettingsPage.SYSTEM -> R.string.settings_search_keywords_system
                SettingsPage.HELP -> R.string.settings_search_keywords_help
                SettingsPage.ABOUT -> R.string.settings_search_keywords_about
                SettingsPage.OVERVIEW -> error("Обзор не является результатом поиска")
            }),
            settingLabels = settingsSectionLabels(page),
        )
    }

@Composable
private fun settingsSectionLabels(page: SettingsPage): List<String> {
    val resources = when (page) {
        SettingsPage.APPEARANCE -> listOf(
            R.string.theme,
            R.string.theme_system,
            R.string.theme_light,
            R.string.theme_dark,
            R.string.dynamic_color,
            R.string.language,
            R.string.language_system,
            R.string.language_english,
            R.string.language_russian,
        )
        SettingsPage.TRACKING -> listOf(
            R.string.default_project_title,
            R.string.default_project_choose,
            R.string.default_project_automatic,
            R.string.search_projects,
            R.string.daily_goal,
            R.string.design_settings_minutes,
            R.string.apply,
            R.string.privacy_mode,
        )
        SettingsPage.DATA -> listOf(
            R.string.data_management,
            R.string.backup_encrypted_title,
            R.string.create_backup,
            R.string.backup_plain_create,
            R.string.restore_backup,
            R.string.backup_password,
            R.string.backup_preview_title,
            R.string.backup_preview_resume,
            R.string.restore_merge,
            R.string.restore_replace,
            R.string.backup_preview_discard,
        )
        SettingsPage.SYSTEM -> listOf(
            R.string.notifications_settings_title,
            R.string.manage_notifications,
            R.string.platform_controls_title,
            R.string.device_setup_open,
            R.string.platform_add_tile,
            R.string.platform_pin_widget,
            R.string.platform_app_settings,
            R.string.platform_compatibility,
            R.string.platform_hide_details,
            R.string.live_updates_title,
            R.string.live_updates_enable,
            R.string.live_updates_manage,
        )
        SettingsPage.HELP -> listOf(R.string.help_search) + HelpTopic.entries.map { it.title }
        SettingsPage.ABOUT -> listOf(R.string.legal_information, R.string.design_settings_local_title) +
            LegalDocument.entries.map { it.titleResource }
        SettingsPage.OVERVIEW -> emptyList()
    }
    return resources.map { stringResource(it) }
}

@Composable
internal fun SettingsSearchField(query: String, onQueryChange: (String) -> Unit) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        label = { Text(stringResource(R.string.settings_search_title)) },
        leadingIcon = { AppIcon(AppGlyph.Search, modifier = Modifier.size(22.dp)) },
        trailingIcon = if (query.isNotEmpty()) {
            {
                IconButton(
                    onClick = { onQueryChange("") },
                    modifier = Modifier.size(48.dp).testTag("settings_search_clear"),
                ) {
                    AppIcon(
                        AppGlyph.Close,
                        contentDescription = stringResource(R.string.settings_search_clear),
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
        } else null,
        singleLine = true,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 16.dp).testTag("settings_search"),
    )
}

@Composable
internal fun SettingsSearchEmptyState() {
    AppMessageCard(
        title = stringResource(R.string.settings_search_empty_title),
        message = stringResource(R.string.settings_search_empty_hint),
        glyph = AppGlyph.Search,
        modifier = Modifier.testTag("settings_search_empty"),
    )
}

@Composable
internal fun SettingsCategory(
    entry: SettingsSearchEntry,
    first: Boolean,
    last: Boolean,
    onClick: () -> Unit,
) {
    val top = if (first) 20.dp else 0.dp
    val bottom = if (last) 20.dp else 0.dp
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().testTag("settings_category_${entry.page.name.lowercase(Locale.ROOT)}"),
        shape = RoundedCornerShape(topStart = top, topEnd = top, bottomStart = bottom, bottomEnd = bottom),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 16.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AppIcon(entry.page.glyph, modifier = Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(entry.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        entry.summary,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                AppIcon(AppGlyph.ChevronRight, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (!last) {
                HorizontalDivider(
                    modifier = Modifier.padding(start = 50.dp, end = 16.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f),
                )
            }
        }
    }
}
