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
import java.util.UUID

enum class ThemeMode { SYSTEM, LIGHT, DARK }
enum class AppLanguage { SYSTEM, ENGLISH, RUSSIAN }

data class UserPreferences(
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = true,
    val privacyMode: Boolean = true,
    val dailyGoalMinutes: Int = 480,
)

data class PendingUserPreferences(
    val operationId: String,
    val preferences: UserPreferences,
)

/** Durable ordering boundary between Room recovery intent and DataStore. */
interface SettingsRecoveryJournal {
    suspend fun pending(): PendingUserPreferences?
    suspend fun save(value: PendingUserPreferences)
    suspend fun delete(operationId: String): Boolean
}

fun interface PrivacyMirror {
    suspend fun write(value: Boolean): Boolean
}

fun interface StrictPreferencesReader {
    suspend fun read(store: DataStore<Preferences>): Preferences
}

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

suspend fun reconcileSettingsJournalWithinMutationBoundary(
    journal: SettingsRecoveryJournal,
    target: RestorePreferenceTarget,
) {
    while (true) {
        val pending = journal.pending() ?: return
        target.replaceWithinMutationBoundary(pending.preferences)
        journal.delete(pending.operationId)
    }
}

/** Typed process-safe preferences. Privacy is mirrored for RemoteViews/system components. */
class UserSettings(
    context: Context,
    name: String = FILE_NAME,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    override val mutationBoundary: SettingsMutationBoundary = SettingsMutationBoundary.PROCESS,
    private val recoveryJournal: SettingsRecoveryJournal? = null,
    privacyMirror: PrivacyMirror? = null,
    private val strictPreferencesReader: StrictPreferencesReader = StrictPreferencesReader { store ->
        store.data.first()
    },
) : RestorePreferenceTarget {
    private val appContext = context.applicationContext
    private val privacyMirror = privacyMirror ?: PrivacyMirror { value ->
        appContext.getSharedPreferences(SYSTEM_SURFACES, Context.MODE_PRIVATE)
            .edit().putBoolean(PRIVACY_KEY, value).commit()
    }
    private val store: DataStore<Preferences> = stores.getOrPut(
        appContext.preferencesDataStoreFile(name).absolutePath,
    ) {
        PreferenceDataStoreFactory.create(scope = scope) { appContext.preferencesDataStoreFile(name) }
    }

    val values: Flow<UserPreferences> = store.data
        .catch { error ->
            if (error is IOException) emit(androidx.datastore.preferences.core.emptyPreferences()) else throw error
        }
        .map(::decode)

    suspend fun setTheme(value: ThemeMode) = mutatePartial { it.copy(theme = value) }
    suspend fun setDynamicColor(value: Boolean) = mutatePartial { it.copy(dynamicColor = value) }
    suspend fun setPrivacy(value: Boolean) = mutatePartial { it.copy(privacyMode = value) }
    suspend fun setDailyGoalMinutes(value: Int) {
        require(value in MIN_GOAL..MAX_GOAL)
        mutatePartial { it.copy(dailyGoalMinutes = value) }
    }

    suspend fun current(): UserPreferences = values.first()

    /**
     * Replaces the DataStore record in one edit, then mirrors privacy for system
     * surfaces. A Room journal provides retryable convergence; the two stores
     * are intentionally not described as atomically committed together.
     */
    suspend fun replace(value: UserPreferences) {
        mutationBoundary.serialized {
            drainRecoveryJournalWithinMutationBoundary()
            persistWithinMutationBoundary(value)
        }
    }

    private suspend fun mutatePartial(transform: (UserPreferences) -> UserPreferences) {
        mutationBoundary.serialized {
            drainRecoveryJournalWithinMutationBoundary()
            val current = decode(strictPreferencesReader.read(store))
            persistWithinMutationBoundary(transform(current))
        }
    }

    private suspend fun drainRecoveryJournalWithinMutationBoundary() {
        recoveryJournal?.let { reconcileSettingsJournalWithinMutationBoundary(it, this) }
    }

    private suspend fun persistWithinMutationBoundary(value: UserPreferences) {
        val journal = recoveryJournal
        if (journal == null) {
            replaceWithinMutationBoundary(value)
        } else {
            val pending = PendingUserPreferences(UUID.randomUUID().toString(), value)
            journal.save(pending)
            replaceWithinMutationBoundary(value)
            journal.delete(pending.operationId)
        }
    }

    private fun decode(preferences: Preferences): UserPreferences = preferences.toUserPreferences(
        defaultPrivacy = appContext.getSharedPreferences(SYSTEM_SURFACES, Context.MODE_PRIVATE)
            .getBoolean(PRIVACY_KEY, true),
    )

    private fun Preferences.toUserPreferences(defaultPrivacy: Boolean) = UserPreferences(
        theme = this[THEME]?.toEnumOrNull<ThemeMode>() ?: ThemeMode.SYSTEM,
        dynamicColor = this[DYNAMIC_COLOR] ?: true,
        privacyMode = this[PRIVACY] ?: defaultPrivacy,
        dailyGoalMinutes = (this[DAILY_GOAL] ?: 480).coerceIn(MIN_GOAL, MAX_GOAL),
    )

    override suspend fun replaceWithinMutationBoundary(value: UserPreferences) {
        require(value.dailyGoalMinutes in MIN_GOAL..MAX_GOAL)
        store.edit { preferences ->
            preferences[THEME] = value.theme.name
            preferences[DYNAMIC_COLOR] = value.dynamicColor
            preferences[PRIVACY] = value.privacyMode
            preferences[DAILY_GOAL] = value.dailyGoalMinutes
        }
        check(privacyMirror.write(value.privacyMode)) { "Unable to mirror privacy preference" }
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
