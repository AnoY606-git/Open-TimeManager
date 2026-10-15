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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

enum class ThemeMode { SYSTEM, LIGHT, DARK }
enum class AppLanguage { SYSTEM, ENGLISH, RUSSIAN }

data class UserPreferences(
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = true,
    val privacyMode: Boolean = true,
    val dailyGoalMinutes: Int = 480,
)

/** One process-wide serialization boundary for DataStore mutations and restore journals. */
class SettingsMutationBoundary {
    private val mutex = Mutex()

    suspend fun <T> serialized(block: suspend () -> T): T = mutex.withLock { block() }

    companion object {
        val PROCESS = SettingsMutationBoundary()
    }
}

/** Restore-only port; callers must already hold their shared [SettingsMutationBoundary]. */
interface RestorePreferenceTarget {
    val mutationBoundary: SettingsMutationBoundary
    suspend fun replaceWithinMutationBoundary(value: UserPreferences)
}

/** Typed process-safe preferences. Privacy is mirrored for RemoteViews/system components. */
class UserSettings(
    context: Context,
    name: String = FILE_NAME,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    override val mutationBoundary: SettingsMutationBoundary = SettingsMutationBoundary.PROCESS,
) : RestorePreferenceTarget {
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

    suspend fun setTheme(value: ThemeMode) = mutationBoundary.serialized {
        store.edit { it[THEME] = value.name }
        Unit
    }
    suspend fun setDynamicColor(value: Boolean) = mutationBoundary.serialized {
        store.edit { it[DYNAMIC_COLOR] = value }
        Unit
    }

    suspend fun setPrivacy(value: Boolean) = mutationBoundary.serialized {
        store.edit { it[PRIVACY] = value }
        check(
            appContext.getSharedPreferences(SYSTEM_SURFACES, Context.MODE_PRIVATE)
                .edit().putBoolean(PRIVACY_KEY, value).commit(),
        ) { "Unable to mirror privacy preference" }
    }

    suspend fun setDailyGoalMinutes(value: Int) = mutationBoundary.serialized {
        require(value in MIN_GOAL..MAX_GOAL)
        store.edit { it[DAILY_GOAL] = value }
        Unit
    }

    suspend fun current(): UserPreferences = values.first()

    /**
     * Replaces the DataStore record in one edit, then mirrors privacy for system
     * surfaces. A Room journal provides retryable convergence; the two stores
     * are intentionally not described as atomically committed together.
     */
    suspend fun replace(value: UserPreferences) {
        mutationBoundary.serialized { replaceWithinMutationBoundary(value) }
    }

    override suspend fun replaceWithinMutationBoundary(value: UserPreferences) {
        require(value.dailyGoalMinutes in MIN_GOAL..MAX_GOAL)
        store.edit { preferences ->
            preferences[THEME] = value.theme.name
            preferences[DYNAMIC_COLOR] = value.dynamicColor
            preferences[PRIVACY] = value.privacyMode
            preferences[DAILY_GOAL] = value.dailyGoalMinutes
        }
        check(
            appContext.getSharedPreferences(SYSTEM_SURFACES, Context.MODE_PRIVATE)
                .edit().putBoolean(PRIVACY_KEY, value.privacyMode).commit(),
        ) { "Unable to mirror privacy preference" }
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
