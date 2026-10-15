package dev.opentimemanager.app.ui

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.opentimemanager.app.OpenTimeManagerApplication
import dev.opentimemanager.app.data.EditResult
import dev.opentimemanager.app.data.EntryEntity
import dev.opentimemanager.app.data.ProjectEditResult
import dev.opentimemanager.app.di.AppContainer
import dev.opentimemanager.app.settings.AppLanguage
import dev.opentimemanager.app.settings.ThemeMode
import dev.opentimemanager.app.settings.UserPreferences
import dev.opentimemanager.app.system.SystemSurfaceUpdater
import dev.opentimemanager.app.tracking.AppSnapshot
import dev.opentimemanager.app.tracking.TrackingCommand
import dev.opentimemanager.app.tracking.TrackingResult
import dev.opentimemanager.app.tracking.ReviewResolution
import dev.opentimemanager.core.model.CommandId
import dev.opentimemanager.core.model.EntryId
import dev.opentimemanager.core.model.ProjectId
import dev.opentimemanager.core.model.TrackingState
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class AppDestination { TIMER, HISTORY, REPORTS, SETTINGS }
enum class UiMessage { INVALID_ENTRY, INVALID_PROJECT, STALE_ENTRY, ACTION_FAILED, SAVED }

data class AppControls(
    val destination: AppDestination = AppDestination.TIMER,
    val selectedProjectId: String? = null,
    val historySearch: String = "",
    val reportPeriod: ReportPeriod = ReportPeriod.WEEK,
    val message: UiMessage? = null,
)

data class AppUiState(
    val snapshot: AppSnapshot? = null,
    val preferences: UserPreferences = UserPreferences(),
    val controls: AppControls = AppControls(),
) {
    val availableProjects get() = snapshot?.projects.orEmpty().filter { it.archivedAtUtcMillis == null }
    val selectedProjectId: String? get() {
        val persistedActive = snapshot?.session
            ?.takeIf { it.state != TrackingState.IDLE }
            ?.projectId
            ?.value
        if (persistedActive != null && availableProjects.any { it.id == persistedActive }) return persistedActive
        val requested = controls.selectedProjectId
        return requested?.takeIf { id -> availableProjects.any { it.id == id } }
            ?: availableProjects.firstOrNull()?.id
    }

    val completedEntries: List<EntryEntity> get() {
        val query = controls.historySearch.trim()
        val projectNames = snapshot?.projects.orEmpty().associate { it.id to it.name }
        return snapshot?.entries.orEmpty()
            .asSequence()
            .filter { it.completed }
            .filter {
                query.isBlank() || it.note.contains(query, ignoreCase = true) ||
                    projectNames[it.projectId].orEmpty().contains(query, ignoreCase = true)
            }
            .sortedByDescending { it.startedAtUtcMillis }
            .toList()
    }
}

class AppViewModel(
    private val application: Application,
    private val container: AppContainer,
) : ViewModel() {
    private val controls = MutableStateFlow(AppControls())
    val state: StateFlow<AppUiState> = combine(
        container.trackingCoordinator.observeSnapshot(),
        container.settings.values,
        controls,
    ) { snapshot, preferences, current ->
        AppUiState(snapshot, preferences, current)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppUiState())

    fun navigate(destination: AppDestination) = updateControls { copy(destination = destination) }
    fun selectProject(projectId: String) {
        val snapshot = state.value.snapshot
        val switch = snapshot?.let { switchCommand(it, ProjectId(projectId), newCommandId()) }
        updateControls { copy(selectedProjectId = projectId) }
        if (switch != null) execute(switch)
    }
    fun searchHistory(query: String) = updateControls { copy(historySearch = query) }
    fun selectReportPeriod(period: ReportPeriod) = updateControls { copy(reportPeriod = period) }
    fun clearMessage() = updateControls { copy(message = null) }

    fun performPrimary() {
        val current = state.value
        val snapshot = current.snapshot ?: return
        val project = current.selectedProjectId ?: return show(UiMessage.INVALID_PROJECT)
        val command = primaryCommand(snapshot, ProjectId(project), newCommandId()) ?: return
        execute(command)
    }

    fun stop() {
        state.value.snapshot?.let { snapshot -> stopCommand(snapshot, newCommandId())?.let(::execute) }
    }

    fun saveActiveNote(note: String) {
        val session = state.value.snapshot?.session ?: return
        val entryId = session.entryId ?: return
        viewModelScope.launch {
            val result = container.activeNotes.update(entryId, session.revision, note)
            show(if (result == dev.opentimemanager.app.data.ActiveNoteResult.APPLIED) UiMessage.SAVED else UiMessage.ACTION_FAILED)
        }
    }

    fun resolveReview(endUtcMillis: Long?) {
        val snapshot = state.value.snapshot ?: return
        val entryId = snapshot.session.entryId ?: return
        val resolution = endUtcMillis?.let(ReviewResolution::ManualEnd) ?: ReviewResolution.CONFIRMED_CHECKPOINT
        execute(TrackingCommand.ResolveReview(
            dev.opentimemanager.core.model.CommandTarget(entryId, snapshot.session.revision, newCommandId()),
            resolution,
        ))
    }

    fun createProject(name: String, colorArgb: Int) = viewModelScope.launch {
        val result = container.projects.create(ProjectId(UUID.randomUUID().toString()), name, colorArgb, System.currentTimeMillis())
        show(if (result == ProjectEditResult.APPLIED) UiMessage.SAVED else UiMessage.INVALID_PROJECT)
    }

    fun archiveProject(projectId: String) = viewModelScope.launch {
        val result = container.projects.archive(ProjectId(projectId), System.currentTimeMillis())
        show(if (result == ProjectEditResult.APPLIED) UiMessage.SAVED else UiMessage.ACTION_FAILED)
    }

    fun saveManualEntry(
        entryId: String?,
        expectedRevision: Long?,
        projectId: String,
        startUtcMillis: Long,
        endUtcMillis: Long,
        note: String,
    ) {
        val validated = validateManualEntry(projectId, startUtcMillis, endUtcMillis, note)
        val draft = validated.value ?: return show(UiMessage.INVALID_ENTRY)
        viewModelScope.launch {
            val result = container.completedEntries.save(
                EntryId(entryId ?: UUID.randomUUID().toString()),
                ProjectId(draft.projectId),
                draft.note,
                draft.startUtcMillis,
                draft.durationMillis,
                expectedRevision,
            )
            show(result.toMessage())
        }
    }

    fun deleteEntry(entryId: String, expectedRevision: Long) = viewModelScope.launch {
        show(container.completedEntries.delete(EntryId(entryId), expectedRevision).toMessage())
    }

    fun setTheme(value: ThemeMode) = changeSettings { container.settings.setTheme(value) }
    fun setDynamicColor(value: Boolean) = changeSettings { container.settings.setDynamicColor(value) }
    fun setLanguage(value: AppLanguage) = changeSettings { container.settings.setLanguage(value) }
    fun setPrivacy(value: Boolean) = changeSettings { container.settings.setPrivacy(value) }
    fun setDailyGoalMinutes(value: Int) = changeSettings { container.settings.setDailyGoalMinutes(value) }

    private fun execute(command: TrackingCommand) = viewModelScope.launch {
        try {
            when (val result = container.trackingCoordinator.execute(command)) {
                is TrackingResult.Applied -> {
                    val updater = SystemSurfaceUpdater(application)
                    updater.refresh(result.snapshot)
                    updater.ensureForegroundControls(result.snapshot)
                }
                is TrackingResult.Duplicate -> Unit
                is TrackingResult.Rejected -> show(UiMessage.ACTION_FAILED)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            show(UiMessage.ACTION_FAILED)
        }
    }

    private fun changeSettings(block: suspend () -> Unit) = viewModelScope.launch {
        try {
            block()
            val snapshot = container.trackingCoordinator.observeSnapshot().first()
            SystemSurfaceUpdater(application).refresh(snapshot)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            show(UiMessage.ACTION_FAILED)
        }
    }

    private fun show(message: UiMessage) = updateControls { copy(message = message) }
    private fun updateControls(block: AppControls.() -> AppControls) { controls.value = controls.value.block() }
    private fun newCommandId() = CommandId(UUID.randomUUID().toString())

    companion object {
        fun factory(application: OpenTimeManagerApplication) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                AppViewModel(application, application.container) as T
        }
    }
}

private fun EditResult.toMessage(): UiMessage = when (this) {
    EditResult.Applied -> UiMessage.SAVED
    EditResult.StaleRevision -> UiMessage.STALE_ENTRY
    EditResult.InvalidInput, EditResult.MissingProject, EditResult.ArchivedProject -> UiMessage.INVALID_ENTRY
    EditResult.NotFound, EditResult.ActiveEntry -> UiMessage.ACTION_FAILED
}

fun defaultCustomReport(today: LocalDate = LocalDate.now(ZoneId.systemDefault())): ReportPeriod.Custom =
    ReportPeriod.Custom(today.minusDays(6), today)
