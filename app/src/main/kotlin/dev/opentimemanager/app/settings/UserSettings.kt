package dev.opentimemanager.app.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.util.concurrent.ConcurrentHashMap

enum class ThemeMode { SYSTEM, LIGHT, DARK }
enum class AppLanguage { SYSTEM, ENGLISH, RUSSIAN }

data class UserPreferences(
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = true,
    val privacyMode: Boolean = true,
    val dailyGoalMinutes: Int = 480,
)

/** Typed process-safe preferences. Privacy is mirrored for RemoteViews/system components. */
class UserSettings(
    context: Context,
    name: String = FILE_NAME,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private val appContext = context.applicationContext
    private val store: DataStore<Preferences> = stores.getOrPut(
        appContext.preferencesDataStoreFile(name).absolutePath,
    ) {
        PreferenceDataStoreFactory.create(scope = scope) { appContext.preferencesDataStoreFile(name) }
    }

    val values: Flow<UserPreferences> = store.data
        .catch { error ->
            if (error is IOException) emit(androidx.datastore.preferences.core.emptyPreferences()) else throw error
        }
        .map { preferences ->
            UserPreferences(
                theme = preferences[THEME]?.toEnumOrNull<ThemeMode>() ?: ThemeMode.SYSTEM,
                dynamicColor = preferences[DYNAMIC_COLOR] ?: true,
                privacyMode = preferences[PRIVACY]
                    ?: appContext.getSharedPreferences(SYSTEM_SURFACES, Context.MODE_PRIVATE)
                        .getBoolean(PRIVACY_KEY, true),
                dailyGoalMinutes = (preferences[DAILY_GOAL] ?: 480).coerceIn(MIN_GOAL, MAX_GOAL),
            )
        }

    suspend fun setTheme(value: ThemeMode) = store.edit { it[THEME] = value.name }.let { Unit }
    suspend fun setDynamicColor(value: Boolean) = store.edit { it[DYNAMIC_COLOR] = value }.let { Unit }

    suspend fun setPrivacy(value: Boolean) {
        store.edit { it[PRIVACY] = value }
        appContext.getSharedPreferences(SYSTEM_SURFACES, Context.MODE_PRIVATE)
            .edit().putBoolean(PRIVACY_KEY, value).apply()
    }

    suspend fun setDailyGoalMinutes(value: Int) {
        require(value in MIN_GOAL..MAX_GOAL)
        store.edit { it[DAILY_GOAL] = value }
    }

    companion object {
        const val FILE_NAME = "user_settings"
        const val MIN_GOAL = 15
        const val MAX_GOAL = 1_440
        private const val SYSTEM_SURFACES = "system_surfaces"
        private const val PRIVACY_KEY = "privacy"
        private val THEME = stringPreferencesKey("theme")
        private val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        private val PRIVACY = booleanPreferencesKey("privacy")
        private val DAILY_GOAL = intPreferencesKey("daily_goal_minutes")
        private val stores = ConcurrentHashMap<String, DataStore<Preferences>>()
    }
}

private inline fun <reified T : Enum<T>> String.toEnumOrNull(): T? =
    enumValues<T>().firstOrNull { it.name == this }
