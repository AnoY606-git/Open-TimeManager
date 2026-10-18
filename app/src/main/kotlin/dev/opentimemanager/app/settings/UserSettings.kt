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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
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

enum class SettingsMutationResult {
    APPLIED,
    APPLIED_PENDING_RECOVERY,
}

class SettingsRecoveryPendingException : IOException(
    "Pending privacy preference could not be mirrored",
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
    suspend fun replaceWithinMutationBoundary(value: UserPreferences): SettingsMutationResult
}

suspend fun reconcileSettingsJournalWithinMutationBoundary(
    journal: SettingsRecoveryJournal,
    target: RestorePreferenceTarget,
): SettingsMutationResult {
    while (true) {
        val pending = journal.pending() ?: return SettingsMutationResult.APPLIED
        val result = target.replaceWithinMutationBoundary(pending.preferences)
        if (result == SettingsMutationResult.APPLIED_PENDING_RECOVERY) return result
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
    private val privacyMirrorDispatcher: CoroutineDispatcher = Dispatchers.IO,
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

    private val observedPreferences = store.data.recoverPreferenceReadFailures()

    val values: Flow<UserPreferences> = observedPreferences.map(::decode)

    /**
     * Local-only project override; null keeps automatic selection. This key is
     * deliberately outside UserPreferences, restore journals and portable backups.
     * Restoring other preferences preserves it; a missing/archived project is
     * ignored by tracking until it becomes eligible again or the override is cleared.
     */
    val defaultProjectId: Flow<String?> = observedPreferences
        .map { canonicalDefaultProjectIdOrNull(it[DEFAULT_PROJECT]) }
        .distinctUntilChanged()

    suspend fun setDefaultProjectId(projectId: String?) {
        require(projectId == null || canonicalDefaultProjectIdOrNull(projectId) != null) {
            "Default project must be a canonical UUID"
        }
        mutationBoundary.serialized {
            store.edit { preferences ->
                if (projectId == null) preferences.remove(DEFAULT_PROJECT) else preferences[DEFAULT_PROJECT] = projectId
            }
        }
    }

    suspend fun setTheme(value: ThemeMode) = mutatePartial { it.copy(theme = value) }
    suspend fun setDynamicColor(value: Boolean) = mutatePartial { it.copy(dynamicColor = value) }
    suspend fun setPrivacy(value: Boolean) = mutatePartial { it.copy(privacyMode = value) }
    suspend fun setDailyGoalMinutes(value: Int): SettingsMutationResult {
        require(value in MIN_GOAL..MAX_GOAL)
        return mutatePartial { it.copy(dailyGoalMinutes = value) }
    }

    suspend fun current(): UserPreferences = values.first()

    /**
     * Replaces portable preference fields in one edit, preserving local-only
     * keys, then mirrors privacy for system surfaces. A Room journal provides
     * retryable convergence; the two stores are not atomically committed together.
     */
    suspend fun replace(value: UserPreferences): SettingsMutationResult =
        mutationBoundary.serialized {
            drainRecoveryJournalWithinMutationBoundary()
            persistWithinMutationBoundary(value)
        }

    /**
     * Produces one strict export view while holding the same ordering boundary
     * as restore and user mutations. The callback may take a Room snapshot, so
     * graph and settings cannot be combined from different serial states.
     */
    suspend fun <T> withStrictExportSnapshot(
        block: suspend (UserPreferences) -> T,
    ): T = mutationBoundary.serialized {
        drainRecoveryJournalWithinMutationBoundary()
        block(decode(strictPreferencesReader.read(store)))
    }

    private suspend fun mutatePartial(
        transform: (UserPreferences) -> UserPreferences,
    ): SettingsMutationResult = mutationBoundary.serialized {
        drainRecoveryJournalWithinMutationBoundary()
        val current = decode(strictPreferencesReader.read(store))
        persistWithinMutationBoundary(transform(current))
    }

    private suspend fun drainRecoveryJournalWithinMutationBoundary() {
        val result = recoveryJournal?.let {
            reconcileSettingsJournalWithinMutationBoundary(it, this)
        } ?: SettingsMutationResult.APPLIED
        if (result == SettingsMutationResult.APPLIED_PENDING_RECOVERY) {
            throw SettingsRecoveryPendingException()
        }
    }

    private suspend fun persistWithinMutationBoundary(value: UserPreferences): SettingsMutationResult {
        val journal = recoveryJournal
        return if (journal == null) {
            replaceWithinMutationBoundary(value)
        } else {
            val pending = PendingUserPreferences(UUID.randomUUID().toString(), value)
            journal.save(pending)
            val result = replaceWithinMutationBoundary(value)
            if (result == SettingsMutationResult.APPLIED) journal.delete(pending.operationId)
            result
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

    override suspend fun replaceWithinMutationBoundary(value: UserPreferences): SettingsMutationResult {
        require(value.dailyGoalMinutes in MIN_GOAL..MAX_GOAL)
        store.edit { preferences ->
            preferences[THEME] = value.theme.name
            preferences[DYNAMIC_COLOR] = value.dynamicColor
            preferences[PRIVACY] = value.privacyMode
            preferences[DAILY_GOAL] = value.dailyGoalMinutes
        }
        val mirrored = try {
            withContext(privacyMirrorDispatcher) { privacyMirror.write(value.privacyMode) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
        return if (mirrored) {
            SettingsMutationResult.APPLIED
        } else {
            SettingsMutationResult.APPLIED_PENDING_RECOVERY
        }
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
        private val DEFAULT_PROJECT = stringPreferencesKey("default_project_id")
        private val stores = ConcurrentHashMap<String, DataStore<Preferences>>()
    }
}

private inline fun <reified T : Enum<T>> String.toEnumOrNull(): T? =
    enumValues<T>().firstOrNull { it.name == this }

/** Read-only fallback with subscriber-scoped retries; strict mutation/export reads bypass it. */
internal fun Flow<Preferences>.recoverPreferenceReadFailures(
    waitBeforeRetry: suspend (Long) -> Unit = { delay(it) },
): Flow<Preferences> = flow {
    var backoffMillis = 100L
    emitAll(
        this@recoverPreferenceReadFailures
            .onEach { backoffMillis = 100L }
            .retryWhen { error, _ ->
                if (error !is IOException) return@retryWhen false
                emit(androidx.datastore.preferences.core.emptyPreferences())
                waitBeforeRetry(backoffMillis)
                backoffMillis = (backoffMillis * 2).coerceAtMost(5_000L)
                true
            },
    )
}

/** UUID.fromString alone also accepts abbreviated groups, which are not project keys. */
internal fun canonicalDefaultProjectIdOrNull(value: String?): String? {
    if (value == null || value.length != 36) return null
    return try {
        value.takeIf { UUID.fromString(it).toString() == it }
    } catch (_: IllegalArgumentException) {
        null
    }
}
