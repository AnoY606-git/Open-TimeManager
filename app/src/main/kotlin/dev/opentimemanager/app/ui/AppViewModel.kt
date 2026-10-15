package dev.opentimemanager.app.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.opentimemanager.app.OpenTimeManagerApplication
import dev.opentimemanager.app.data.EditResult
import dev.opentimemanager.app.data.EntryEntity
import dev.opentimemanager.app.data.ProjectEditResult
import dev.opentimemanager.app.data.ProjectEntity
import dev.opentimemanager.app.di.AppContainer
import dev.opentimemanager.app.backup.BackupInspection
import dev.opentimemanager.app.backup.BackupWriteResult
import dev.opentimemanager.app.backup.BackupPreview
import dev.opentimemanager.app.backup.DataTransferSession
import dev.opentimemanager.app.backup.RestoreMode
import dev.opentimemanager.app.backup.RestoreResult
import dev.opentimemanager.app.dataexport.CsvExportResult
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
enum class UiMessage {
    INVALID_ENTRY,
    INVALID_PROJECT,
    STALE_ENTRY,
    ACTION_FAILED,
    SAVED,
    CSV_EXPORTED,
    BACKUP_CREATED,
    DATA_IO_ERROR,
}

data class AppControls(
    val destination: AppDestination = AppDestination.TIMER,
    val selectedProjectId: String? = null,
    val historySearch: String = "",
    val reportPeriod: ReportPeriod = ReportPeriod.WEEK,
    val message: UiMessage? = null,
    val dataTransferBusy: Boolean = false,
    val pendingRestoreUri: String? = null,
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

    fun completedEntries(projectDisplayName: (ProjectEntity) -> String): List<EntryEntity> =
        filterCompletedEntries(
            entries = snapshot?.entries.orEmpty(),
            projects = snapshot?.projects.orEmpty(),
            query = controls.historySearch,
            projectDisplayName = projectDisplayName,
        )
}

fun filterCompletedEntries(
    entries: List<EntryEntity>,
    projects: List<ProjectEntity>,
    query: String,
    projectDisplayName: (ProjectEntity) -> String,
): List<EntryEntity> {
    val normalizedQuery = query.trim()
    val projectNames = projects.associate { it.id to projectDisplayName(it) }
    return entries.asSequence()
        .filter { it.completed }
        .filter {
            normalizedQuery.isBlank() || it.note.contains(normalizedQuery, ignoreCase = true) ||
                projectNames[it.projectId].orEmpty().contains(normalizedQuery, ignoreCase = true)
        }
        .sortedByDescending { it.startedAtUtcMillis }
        .toList()
}

class AppViewModel(
    private val application: Application,
    private val container: AppContainer,
) : ViewModel() {
    private val transfers = DataTransferSession(container.csvExport, container.backupFiles)
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
        launchWrite {
            val result = container.activeNotes.update(entryId, session.revision, note)
            if (result == dev.opentimemanager.app.data.ActiveNoteResult.APPLIED) UiMessage.SAVED else UiMessage.ACTION_FAILED
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

    fun createProject(name: String, colorArgb: Int) = launchWrite {
        val result = container.projects.create(ProjectId(UUID.randomUUID().toString()), name, colorArgb, System.currentTimeMillis())
        if (result == ProjectEditResult.APPLIED) UiMessage.SAVED else UiMessage.INVALID_PROJECT
    }

    fun archiveProject(projectId: String) = launchWrite {
        val result = container.projects.archive(ProjectId(projectId), System.currentTimeMillis())
        if (result == ProjectEditResult.APPLIED) UiMessage.SAVED else UiMessage.ACTION_FAILED
    }

    fun saveManualEntry(
        entryId: String?,
        expectedRevision: Long?,
        draft: ManualEntryDraft,
    ) {
        launchWrite {
            val result = if (draft.preserveTiming && entryId != null && expectedRevision != null) {
                container.completedEntries.updateMetadata(
                    EntryId(entryId),
                    ProjectId(draft.projectId),
                    draft.note,
                    expectedRevision,
                )
            } else {
                container.completedEntries.save(
                    EntryId(entryId ?: UUID.randomUUID().toString()),
                    ProjectId(draft.projectId),
                    draft.note,
                    draft.startUtcMillis,
                    draft.durationMillis,
                    expectedRevision,
                )
            }
            result.toMessage()
        }
    }

    fun deleteEntry(entryId: String, expectedRevision: Long) = launchWrite {
        container.completedEntries.delete(EntryId(entryId), expectedRevision).toMessage()
    }

    fun setTheme(value: ThemeMode) = changeSettings { container.settings.setTheme(value) }
    fun setDynamicColor(value: Boolean) = changeSettings { container.settings.setDynamicColor(value) }
    fun setPrivacy(value: Boolean) = changeSettings { container.settings.setPrivacy(value) }
    fun setDailyGoalMinutes(value: Int) = changeSettings { container.settings.setDailyGoalMinutes(value) }

    fun beginCsvExport(range: UtcBounds): String = transfers.beginCsv(range).also {
        updateControls { copy(dataTransferBusy = true) }
    }

    fun cancelCsvExport() {
        transfers.cancelCsv()
        updateControls { copy(dataTransferBusy = false) }
    }

    fun completeCsvExport(uri: Uri?) {
        updateControls { copy(dataTransferBusy = true) }
        viewModelScope.launch {
            try {
                when (transfers.finishCsv(uri)) {
                    CsvExportResult.Success -> show(UiMessage.CSV_EXPORTED)
                    CsvExportResult.Cancelled -> Unit
                    is CsvExportResult.Failed -> show(UiMessage.DATA_IO_ERROR)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                show(UiMessage.DATA_IO_ERROR)
            } finally {
                updateControls { copy(dataTransferBusy = false) }
            }
        }
    }

    suspend fun prepareBackup(password: CharArray): String {
        updateControls { copy(dataTransferBusy = true) }
        try {
            return transfers.beginBackup(password)
        } catch (cancelled: CancellationException) {
            updateControls { copy(dataTransferBusy = false) }
            throw cancelled
        } catch (error: Exception) {
            updateControls { copy(dataTransferBusy = false) }
            throw error
        }
    }

    fun cancelBackup() {
        transfers.cancelBackup()
        updateControls { copy(dataTransferBusy = false) }
    }

    fun selectBackupDocument(uri: Uri?) = updateControls { copy(pendingRestoreUri = uri?.toString()) }

    fun clearBackupDocument() = updateControls { copy(pendingRestoreUri = null) }

    fun completeBackup(uri: Uri?) {
        updateControls { copy(dataTransferBusy = true) }
        viewModelScope.launch {
            try {
                when (transfers.finishBackup(uri)) {
                    BackupWriteResult.Success -> show(UiMessage.BACKUP_CREATED)
                    BackupWriteResult.Cancelled -> Unit
                    is BackupWriteResult.Failed -> show(UiMessage.DATA_IO_ERROR)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                show(UiMessage.DATA_IO_ERROR)
            } finally {
                updateControls { copy(dataTransferBusy = false) }
            }
        }
    }

    suspend fun inspectBackup(uri: Uri?, password: CharArray): BackupInspection {
        updateControls { copy(dataTransferBusy = true) }
        try {
            val result = container.backupFiles.inspectBackup(uri, password)
            if (result is BackupInspection.Ready) transfers.retainPreview(result.preview)
            return result
        } finally {
            updateControls { copy(dataTransferBusy = false) }
        }
    }

    fun currentBackupPreview(): BackupPreview? = transfers.currentPreview()

    fun discardBackupPreview() = transfers.discardPreview()

    suspend fun restoreBackup(
        preview: dev.opentimemanager.app.backup.BackupPreview,
        mode: RestoreMode,
    ): RestoreResult {
        updateControls { copy(dataTransferBusy = true) }
        try {
            transfers.consumePreview(preview)
            val result = container.restore.restore(preview, mode)
            if (result is RestoreResult.Applied) {
                val snapshot = container.trackingCoordinator.observeSnapshot().first()
                SystemSurfaceUpdater(application).refresh(snapshot)
            }
            return result
        } finally {
            updateControls { copy(dataTransferBusy = false) }
        }
    }

    override fun onCleared() {
        transfers.clear()
        super.onCleared()
    }

    private fun execute(command: TrackingCommand) = launchWrite {
        when (val result = container.trackingCoordinator.execute(command)) {
            is TrackingResult.Applied -> {
                val updater = SystemSurfaceUpdater(application)
                updater.refresh(result.snapshot)
                updater.ensureForegroundControls(result.snapshot)
                null
            }
            is TrackingResult.Duplicate -> null
            is TrackingResult.Rejected -> UiMessage.ACTION_FAILED
        }
    }

    private fun changeSettings(block: suspend () -> Unit) = launchWrite {
        block()
        val snapshot = container.trackingCoordinator.observeSnapshot().first()
        SystemSurfaceUpdater(application).refresh(snapshot)
        null
    }

    private fun launchWrite(block: suspend () -> UiMessage?) = viewModelScope.launch {
        cancellationAwareUiWrite(block)?.let(::show)
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

internal suspend fun cancellationAwareUiWrite(block: suspend () -> UiMessage?): UiMessage? = try {
    block()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: Exception) {
    UiMessage.ACTION_FAILED
}

private fun EditResult.toMessage(): UiMessage = when (this) {
    EditResult.Applied -> UiMessage.SAVED
    EditResult.StaleRevision -> UiMessage.STALE_ENTRY
    EditResult.InvalidInput, EditResult.MissingProject, EditResult.ArchivedProject -> UiMessage.INVALID_ENTRY
    EditResult.NotFound, EditResult.ActiveEntry -> UiMessage.ACTION_FAILED
}

fun defaultCustomReport(today: LocalDate = LocalDate.now(ZoneId.systemDefault())): ReportPeriod.Custom =
    ReportPeriod.Custom(today.minusDays(6), today)
