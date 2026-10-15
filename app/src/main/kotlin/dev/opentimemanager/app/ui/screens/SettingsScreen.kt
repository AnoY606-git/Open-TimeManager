package dev.opentimemanager.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import dev.opentimemanager.app.R
import dev.opentimemanager.app.settings.AppLanguage
import dev.opentimemanager.app.settings.ThemeMode
import dev.opentimemanager.app.settings.UserSettings
import dev.opentimemanager.app.ui.AppUiState

@Composable
@OptIn(ExperimentalLayoutApi::class)
fun SettingsScreen(
    state: AppUiState,
    onTheme: (ThemeMode) -> Unit,
    onDynamicColor: (Boolean) -> Unit,
    onLanguage: (AppLanguage) -> Unit,
    onPrivacy: (Boolean) -> Unit,
    onDailyGoal: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val preferences = state.preferences
    val context = LocalContext.current
    var goal by remember(preferences.dailyGoalMinutes) { mutableStateOf(preferences.dailyGoalMinutes.toString()) }
    val version = remember {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
    }
    LazyColumn(
        modifier.fillMaxSize().padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Text(
                stringResource(R.string.settings_heading),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 18.dp),
            )
        }
        item { Text(stringResource(R.string.appearance), style = MaterialTheme.typography.titleLarge) }
        item {
            PreferenceCard(title = stringResource(R.string.theme)) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ThemeMode.entries.forEach { mode ->
                        FilterChip(
                            selected = preferences.theme == mode,
                            onClick = { onTheme(mode) },
                            label = { Text(stringResource(mode.label())) },
                        )
                    }
                }
            }
        }
        item {
            TogglePreference(
                title = stringResource(R.string.dynamic_color),
                checked = preferences.dynamicColor,
                onChecked = onDynamicColor,
            )
        }
        item {
            PreferenceCard(title = stringResource(R.string.language)) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    AppLanguage.entries.forEach { language ->
                        FilterChip(
                            selected = preferences.language == language,
                            onClick = { onLanguage(language) },
                            label = { Text(stringResource(language.label())) },
                        )
                    }
                }
            }
        }
        item {
            TogglePreference(
                title = stringResource(R.string.privacy_mode),
                checked = preferences.privacyMode,
                onChecked = onPrivacy,
            )
        }
        item {
            PreferenceCard(title = stringResource(R.string.daily_goal_minutes)) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = goal,
                        onValueChange = { goal = it.filter(Char::isDigit).take(4) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(
                        onClick = { goal.toIntOrNull()?.takeIf { it in UserSettings.MIN_GOAL..UserSettings.MAX_GOAL }?.let(onDailyGoal) },
                        enabled = goal.toIntOrNull() in UserSettings.MIN_GOAL..UserSettings.MAX_GOAL,
                        modifier = Modifier.align(Alignment.End),
                    ) { Text(stringResource(R.string.apply)) }
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.about_local))
                    Text(stringResource(R.string.version_label, version), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun PreferenceCard(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun TogglePreference(title: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            Switch(checked = checked, onCheckedChange = onChecked)
        }
    }
}

private fun ThemeMode.label(): Int = when (this) {
    ThemeMode.SYSTEM -> R.string.theme_system
    ThemeMode.LIGHT -> R.string.theme_light
    ThemeMode.DARK -> R.string.theme_dark
}

private fun AppLanguage.label(): Int = when (this) {
    AppLanguage.SYSTEM -> R.string.language_system
    AppLanguage.ENGLISH -> R.string.language_english
    AppLanguage.RUSSIAN -> R.string.language_russian
}
