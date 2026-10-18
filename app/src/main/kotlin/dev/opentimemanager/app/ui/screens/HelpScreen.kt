package dev.opentimemanager.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.opentimemanager.app.R
import dev.opentimemanager.app.ui.components.AppCard
import dev.opentimemanager.app.ui.components.ScreenHeading

/** Entirely local, searchable help: no browser, network, or document-provider access. */
@Composable
fun HelpScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    var query by rememberSaveable { mutableStateOf("") }
    var expanded by rememberSaveable { mutableStateOf<String?>("getting_started") }
    val topics = HelpTopic.entries.map { topic ->
        Triple(topic, stringResource(topic.title), stringResource(topic.body) +
            if (topic == HelpTopic.CONTROLS) "\n\n" + stringResource(R.string.platform_live_updates_guide) else "")
    }
    val matches = topics.filter { (_, title, body) ->
        query.isBlank() || title.contains(query.trim(), ignoreCase = true) || body.contains(query.trim(), ignoreCase = true)
    }
    LazyColumn(
        modifier.fillMaxSize().testTag("help_list"),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item("back") {
            TextButton(onClick = onBack, modifier = Modifier.testTag("settings_back")) {
                Text("‹  " + stringResource(R.string.back_to_settings))
            }
        }
        item("heading") {
            ScreenHeading(stringResource(R.string.help_title), stringResource(R.string.help_summary))
        }
        item("search") {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text(stringResource(R.string.help_search)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("help_search"),
            )
        }
        if (matches.isEmpty()) item("empty") { Text(stringResource(R.string.help_no_results)) }
        matches.forEach { (topic, title, body) ->
            item(topic.name) {
                val isExpanded = query.isNotBlank() || expanded == topic.name.lowercase()
                AppCard(contentPadding = PaddingValues(0.dp)) {
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 56.dp)
                            .clickable(enabled = query.isBlank(), role = Role.Button) {
                                expanded = if (isExpanded) null else topic.name.lowercase()
                            }
                            .padding(18.dp)
                            .semantics { heading() }
                            .testTag("help_topic_${topic.name.lowercase()}"),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        // Decorative affordance; the whole heading is one accessible action.
                        if (query.isBlank()) Text(if (isExpanded) "−" else "+", color = MaterialTheme.colorScheme.primary)
                    }
                    if (isExpanded) {
                        SelectionContainer {
                            Text(
                                body,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(start = 18.dp, end = 18.dp, bottom = 18.dp)
                                    .testTag("help_body_${topic.name.lowercase()}"),
                            )
                        }
                    }
                }
            }
        }
    }
}

internal enum class HelpTopic(val title: Int, val body: Int) {
    GETTING_STARTED(R.string.help_getting_started_title, R.string.help_getting_started_body),
    PROJECTS(R.string.help_projects_title, R.string.help_projects_body),
    CONTROLS(R.string.help_controls_title, R.string.help_controls_body),
    HISTORY(R.string.help_history_title, R.string.help_history_body),
    REPORTS(R.string.help_reports_title, R.string.help_reports_body),
    BACKUP(R.string.help_backup_title, R.string.help_backup_body),
    RESTORE(R.string.help_restore_title, R.string.help_restore_body),
    RECOVERY(R.string.help_recovery_title, R.string.help_recovery_body),
    BATTERY(R.string.help_battery_title, R.string.help_battery_body),
    PRIVACY(R.string.help_privacy_title, R.string.help_privacy_body),
}
