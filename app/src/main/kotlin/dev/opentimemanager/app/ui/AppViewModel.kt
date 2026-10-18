package dev.opentimemanager.app.ui

import android.app.Application
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.opentimemanager.app.OpenTimeManagerApplication
import dev.opentimemanager.app.data.EditResult
import dev.opentimemanager.app.data.EntryEntity
import dev.opentimemanager.app.data.ProjectEditResult
import dev.opentimemanager.app.data.ProjectEntity
import dev.opentimemanager.app.data.ProjectPageCursor
import dev.opentimemanager.app.data.ProjectRepository
import dev.opentimemanager.app.data.ReportSummaryProjection
import dev.opentimemanager.app.di.AppContainer
import dev.opentimemanager.app.backup.BackupInspection
import dev.opentimemanager.app.backup.BackupFormatResult
import dev.opentimemanager.app.backup.BackupWriteResult
import dev.opentimemanager.app.backup.BackupPreview
import dev.opentimemanager.app.backup.DataTransferSession
import dev.opentimemanager.app.backup.RestoreMode
import dev.opentimemanager.app.backup.RestoreResult
import dev.opentimemanager.app.dataexport.CsvExportResult
import dev.opentimemanager.app.settings.ThemeMode
import dev.opentimemanager.app.settings.SettingsMutationResult
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
import dev.opentimemanager.core.backup.BackupFormat
import dev.opentimemanager.core.report.ReportAggregator
import dev.opentimemanager.core.report.ReportTotals
import java.time.LocalDate
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job

enum class AppDestination { TIMER, HISTORY, REPORTS, SETTINGS }
enum class UiMessage {
    INVALID_ENTRY,
    INVALID_PROJECT,
    PROJECT_LIMIT_REACHED,
    STALE_ENTRY,
    ACTION_FAILED,
    SAVED,
    CSV_EXPORTED,
    BACKUP_CREATED,
    PLAIN_BACKUP_CREATED,
    DATA_IO_ERROR,
    PARTIAL_FILE_CLEANUP_REQUIRED,
    SYSTEM_CONTROLS_UNAVAILABLE,
    SETTINGS_SYSTEM_PRIVACY_PENDING,
}

data class AppControls(
    val destination: AppDestination = AppDestination.TIMER,
    val selectedProjectId: String? = null,
    val historySearch: String = "",
    val reportPeriod: ReportPeriod = ReportPeriod.WEEK,
    val historyLimit: Int = INITIAL_HISTORY_LIMIT,
    val projectLimit: Int = INITIAL_PROJECT_LIMIT,
    val message: UiMessage? = null,
    val dataTransferBusy: Boolean = false,
    val pendingRestoreUri: String? = null,
    val lastRestoreResult: RestoreResult? = null,
    val manualProjectPicker: ManualProjectPickerState = ManualProjectPickerState(),
)

data class ManualProjectPickerState(
    val query: String = "",
    val projects: List<ProjectEntity> = emptyList(),
    val pinnedProject: ProjectEntity? = null,
    val nextCursor: ProjectPageCursor? = null,
    val pinnedProjectId: String? = null,
    val loading: Boolean = false,
) {
    val hasMore: Boolean get() = nextCursor != null
}

data class AppUiState(
    val snapshot: AppSnapshot? = null,
    val preferences: UserPreferences = UserPreferences(),
    val controls: AppControls = AppControls(),
    val reportTotals: ReportTotals = ReportAggregator.aggregateTotals(emptyList(), 0, 0),
    val reportBounds: UtcBounds? = null,
    val reportLoading: Boolean = false,
    val todayProjection: ConfirmedDayProjection = ConfirmedDayProjection(0, 0, 0, emptyList()),
    val reportProjects: List<ProjectEntity> = emptyList(),
    val todayEntries: List<EntryEntity> = emptyList(),
    val todayProjects: List<ProjectEntity> = emptyList(),
    val defaultProjectId: String? = snapshot?.defaultProjectId,
) {
    val availableProjects get() = snapshot?.projects.orEmpty().filter { it.archivedAtUtcMillis == null }
    val selectedProjectId: String? get() {
        val persistedActive = snapshot?.session
            ?.takeIf { it.state != TrackingState.IDLE }
            ?.projectId
            ?.value
        if (persistedActive != null) return persistedActive
        val requested = controls.selectedProjectId
        return requested?.takeIf { id -> availableProjects.any { it.id == id } }
            ?: defaultProjectId?.takeIf { id -> availableProjects.any { it.id == id } }
            ?: availableProjects.firstOrNull { it.id == ProjectRepository.DEFAULT_PROJECT_ID.value }?.id
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

@OptIn(ExperimentalCoroutinesApi::class)
class AppViewModel(
    private val application: Application,
    private val container: AppContainer,
) : ViewModel() {
    private val transfers = DataTransferSession(container.csvExport, container.backupFiles)
    private val controls = MutableStateFlow(AppControls())
    private var manualProjectPickerJob: Job? = null
    private var pendingBackupFormat: BackupFormat? = null
    private val snapshots = controls
        .map { current ->
            SnapshotRequest(
                current.historyLimit,
                current.projectLimit,
                current.selectedProjectId ?: ProjectRepository.DEFAULT_PROJECT_ID.value,
            )
        }
        .distinctUntilChanged()
        .flatMapLatest { request ->
            container.trackingCoordinator.observeSnapshot(
                request.historyLimit,
                request.projectLimit,
                request.pinnedProjectId,
            )
        }
    private val localDays = localDayContexts()
        .distinctUntilChanged()
        .shareIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), replay = 1)
    private val reportProjection = combine(
        controls.map { it.reportPeriod }.distinctUntilChanged(),
        localDays,
    ) { period, day ->
        period to (period.validatedBounds(day.date, day.zoneId).value
            ?: requireNotNull(ReportPeriod.WEEK.validatedBounds(day.date, day.zoneId).value))
        }
        .distinctUntilChanged()
        .flatMapLatest { (period, bounds) ->
            container.completedEntries.observeReportSummary(bounds.fromUtcMillis, bounds.toUtcMillis)
                .map { ReportRangeProjection(period, bounds, it) }
                .onStart { emit(ReportRangeProjection(period, bounds, null)) }
        }
    private val todayProjection = localDays.flatMapLatest { day ->
        val from = day.date.atStartOfDay(day.zoneId).toInstant().toEpochMilli()
        val to = day.date.plusDays(1).atStartOfDay(day.zoneId).toInstant().toEpochMilli()
        container.completedEntries.observeReportDetails(from, to).map { details ->
            TodayRangeProjection(
                projection = confirmedDayProjection(details.segments, day.date, day.zoneId),
                entries = details.entries,
                projects = details.projects,
            )
        }
    }
    val state: StateFlow<AppUiState> = combine(
        snapshots,
        container.settings.values,
        controls,
        reportProjection,
        todayProjection,
    ) { snapshot, preferences, current, report, today ->
        val currentReport = report.takeIf { it.period == current.reportPeriod }?.summary
        AppUiState(
            snapshot = snapshot,
            preferences = preferences,
            controls = current,
            reportTotals = currentReport?.totals ?: ReportAggregator.aggregateTotals(emptyList(), 0, 0),
            reportBounds = report.bounds,
            reportLoading = currentReport == null,
            todayProjection = today.projection,
            reportProjects = currentReport?.projects.orEmpty(),
            todayEntries = today.entries,
            todayProjects = today.projects,
            defaultProjectId = snapshot.defaultProjectId,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppUiState())

    fun navigate(destination: AppDestination) = updateControls { copy(destination = destination) }
    fun selectProject(projectId: String) {
        val snapshot = state.value.snapshot
        val switch = snapshot?.let { switchCommand(it, ProjectId(projectId), newCommandId()) }
        updateControls { copy(selectedProjectId = projectId) }
        if (switch != null) execute(switch)
    }
    fun searchHistory(query: String) = updateControls { copy(historySearch = query) }
    fun loadMoreHistory() = updateControls { copy(historyLimit = nextHistoryLimit(historyLimit)) }
    fun loadMoreProjects() = updateControls { copy(projectLimit = nextProjectLimit(projectLimit)) }
    fun openManualProjectPicker(pinnedProjectId: String?) {
        manualProjectPickerJob?.cancel()
        updateControls {
            copy(manualProjectPicker = ManualProjectPickerState(pinnedProjectId = pinnedProjectId, loading = true))
        }
        loadManualProjectPage(append = false)
    }

    fun searchManualProjects(query: String) {
        manualProjectPickerJob?.cancel()
        updateControls {
            copy(
                manualProjectPicker = manualProjectPicker.copy(
                    query = query,
                    projects = emptyList(),
                    nextCursor = null,
                    loading = true,
                ),
            )
        }
        loadManualProjectPage(append = false)
    }

    fun loadMoreManualProjects() {
        if (controls.value.manualProjectPicker.loading || !controls.value.manualProjectPicker.hasMore) return
        updateControls { copy(manualProjectPicker = manualProjectPicker.copy(loading = true)) }
        loadManualProjectPage(append = true)
    }

    fun closeManualProjectPicker() {
        manualProjectPickerJob?.cancel()
        updateControls { copy(manualProjectPicker = ManualProjectPickerState()) }
    }
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
            snapshot.generation,
        ))
    }

    fun createProject(name: String, colorArgb: Int) = launchWrite {
        val result = container.projects.create(ProjectId(UUID.randomUUID().toString()), name, colorArgb, System.currentTimeMillis())
        projectEditMessage(result)
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
                    expectedGeneration = draft.expectedGeneration,
                )
            } else {
                container.completedEntries.save(
                    EntryId(entryId ?: UUID.randomUUID().toString()),
                    ProjectId(draft.projectId),
                    draft.note,
                    draft.startUtcMillis,
                    draft.durationMillis,
                    expectedRevision,
                    expectedGeneration = draft.expectedGeneration,
                )
            }
            result.toMessage()
        }
    }

    fun deleteEntry(entryId: String, expectedRevision: Long, expectedGeneration: String? = null) = launchWrite {
        container.completedEntries.delete(EntryId(entryId), expectedRevision, expectedGeneration).toMessage()
    }

    fun setTheme(value: ThemeMode) = changeSettings { container.settings.setTheme(value) }
    fun setDynamicColor(value: Boolean) = changeSettings { container.settings.setDynamicColor(value) }
    fun setPrivacy(value: Boolean) = changeSettings { container.settings.setPrivacy(value) }
    fun setDailyGoalMinutes(value: Int) = changeSettings { container.settings.setDailyGoalMinutes(value) }
    /** Changes the durable idle/system default, not the current selection or live session. */
    fun setDefaultProject(projectId: String?) = changeSettings {
        require(projectId == null || container.projects.activeProject(ProjectId(projectId)) != null) {
            "Default project must be available"
        }
        container.settings.setDefaultProjectId(projectId)
        SettingsMutationResult.APPLIED
    }

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
                when (val result = transfers.finishCsv(uri)) {
                    CsvExportResult.Success -> show(UiMessage.CSV_EXPORTED)
                    CsvExportResult.Cancelled -> Unit
                    is CsvExportResult.Failed -> show(transferFailureMessage(result.partialDocumentDeleted))
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
            return transfers.beginBackup(password).also { pendingBackupFormat = BackupFormat.ENCRYPTED }
        } catch (cancelled: CancellationException) {
            updateControls { copy(dataTransferBusy = false) }
            throw cancelled
        } catch (error: Exception) {
            updateControls { copy(dataTransferBusy = false) }
            throw error
        }
    }

    suspend fun preparePlainBackup(): String {
        updateControls { copy(dataTransferBusy = true) }
        try {
            return transfers.beginPlainBackup().also { pendingBackupFormat = BackupFormat.PLAINTEXT }
        } catch (error: Exception) {
            updateControls { copy(dataTransferBusy = false) }
            throw error
        }
    }

    fun cancelBackup() {
        transfers.cancelBackup()
        pendingBackupFormat = null
        updateControls { copy(dataTransferBusy = false) }
    }

    fun selectBackupDocument(uri: Uri?) = updateControls {
        copy(pendingRestoreUri = uri?.toString(), destination = if (uri != null) AppDestination.SETTINGS else destination)
    }

    fun clearBackupDocument() = updateControls { copy(pendingRestoreUri = null) }

    fun completeBackup(uri: Uri?) {
        val format = pendingBackupFormat
        pendingBackupFormat = null
        updateControls { copy(dataTransferBusy = true) }
        viewModelScope.launch {
            try {
                when (val result = transfers.finishBackup(uri)) {
                    BackupWriteResult.Success -> show(if (format == BackupFormat.PLAINTEXT) UiMessage.PLAIN_BACKUP_CREATED else UiMessage.BACKUP_CREATED)
                    BackupWriteResult.Cancelled -> Unit
                    is BackupWriteResult.Failed -> show(transferFailureMessage(result.partialDocumentDeleted))
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
            val result = container.backupFiles.inspectBackup(uri, password, expectedFormat = BackupFormat.ENCRYPTED)
            if (result is BackupInspection.Ready) transfers.retainPreview(result.preview)
            return result
        } finally {
            updateControls { copy(dataTransferBusy = false) }
        }
    }

    fun currentBackupPreview(): BackupPreview? = transfers.currentPreview()

    suspend fun backupFormat(uri: Uri?): BackupFormatResult {
        updateControls { copy(dataTransferBusy = true) }
        try {
            return container.backupFiles.backupFormat(uri)
        } finally {
            updateControls { copy(dataTransferBusy = false) }
        }
    }

    suspend fun inspectPlainBackup(uri: Uri?): BackupInspection {
        updateControls { copy(dataTransferBusy = true) }
        try {
            val result = container.backupFiles.inspectBackup(uri, expectedFormat = BackupFormat.PLAINTEXT)
            if (result is BackupInspection.Ready) transfers.retainPreview(result.preview)
            return result
        } finally {
            updateControls { copy(dataTransferBusy = false) }
        }
    }

    fun discardBackupPreview() = transfers.discardPreview()

    suspend fun restoreBackup(
        preview: dev.opentimemanager.app.backup.BackupPreview,
        mode: RestoreMode,
    ): RestoreResult = viewModelScope.async {
        updateControls { copy(dataTransferBusy = true, lastRestoreResult = null) }
        try {
            val result = container.restore.restore(preview, mode)
            transfers.onRestoreResult(preview, result)
            updateControls { copy(lastRestoreResult = result) }
            if (result is RestoreResult.Applied) {
                val controlsWarning = systemSurfaceWarning {
                    val snapshot = container.trackingCoordinator.observeSystemSnapshot().first()
                    SystemSurfaceUpdater(application).refresh(snapshot) == dev.opentimemanager.app.system.NotificationAvailability.AVAILABLE ||
                        snapshot.session.state == TrackingState.IDLE
                }
                restoreCompletionWarning(result, controlsWarning)?.let(::show)
            }
            result
        } finally {
            updateControls { copy(dataTransferBusy = false) }
        }
    }.await()

    override fun onCleared() {
        manualProjectPickerJob?.cancel()
        transfers.clear()
        super.onCleared()
    }

    private fun execute(command: TrackingCommand) = viewModelScope.launch {
        val result = try {
            container.trackingCoordinator.execute(command)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            show(UiMessage.ACTION_FAILED)
            return@launch
        }
        when (result) {
            is TrackingResult.Applied -> {
                val updater = SystemSurfaceUpdater(application)
                systemSurfaceWarning {
                    refreshAndEnsureSystemControls(
                        refresh = {
                            updater.refresh(result.snapshot) ==
                                dev.opentimemanager.app.system.NotificationAvailability.AVAILABLE
                        },
                        ensureForeground = { updater.ensureForegroundControls(result.snapshot) },
                    )
                }?.let(::show)
            }
            is TrackingResult.Duplicate -> Unit
            is TrackingResult.Rejected -> show(UiMessage.ACTION_FAILED)
        }
    }

    private fun changeSettings(
        block: suspend () -> SettingsMutationResult,
    ) = viewModelScope.launch {
        settingsMutationUiMessage(block)?.let {
            show(it)
            return@launch
        }
        systemSurfaceWarning {
            val snapshot = container.trackingCoordinator.observeSystemSnapshot().first()
            SystemSurfaceUpdater(application).refresh(snapshot) == dev.opentimemanager.app.system.NotificationAvailability.AVAILABLE ||
                snapshot.session.state == TrackingState.IDLE
        }?.let(::show)
    }

    fun reconcileSystemControls() = viewModelScope.launch {
        val preferencesWarning = settingsMutationUiMessage { container.restore.reconcilePendingPreferences() }
        val controlsWarning = systemSurfaceWarning {
            val snapshot = container.trackingCoordinator.observeSystemSnapshot().first()
            val updater = SystemSurfaceUpdater(application)
            refreshAndEnsureSystemControls(
                refresh = {
                    updater.refresh(snapshot) == dev.opentimemanager.app.system.NotificationAvailability.AVAILABLE ||
                        snapshot.session.state == TrackingState.IDLE
                },
                ensureForeground = { updater.ensureForegroundControls(snapshot) },
            )
        }
        (preferencesWarning ?: controlsWarning)?.let(::show)
    }

    private fun launchWrite(block: suspend () -> UiMessage?) = viewModelScope.launch {
        cancellationAwareUiWrite(block)?.let(::show)
    }

    private fun show(message: UiMessage) = updateControls { copy(message = message) }
    private fun loadManualProjectPage(append: Boolean) {
        val request = controls.value.manualProjectPicker
        manualProjectPickerJob = viewModelScope.launch {
            try {
                val localizedContext = ContextCompat.getContextForLanguage(application)
                val page = container.projects.manualEntryPage(
                    query = request.query,
                    cursor = if (append) request.nextCursor else null,
                    pinnedProjectId = request.pinnedProjectId,
                    pageSize = MANUAL_PROJECT_PAGE_SIZE,
                    displayName = { localizedContext.projectName(it) },
                )
                updateControls {
                    val current = manualProjectPicker
                    if (current.query != request.query || current.pinnedProjectId != request.pinnedProjectId) return@updateControls this
                    copy(
                        manualProjectPicker = current.copy(
                            projects = if (append) {
                                (current.projects + page.projects).distinctBy(ProjectEntity::id)
                            } else {
                                page.projects
                            },
                            pinnedProject = page.pinnedProject ?: current.pinnedProject,
                            nextCursor = page.nextCursor,
                            loading = false,
                        ),
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                updateControls {
                    val current = manualProjectPicker
                    if (current.query != request.query || current.pinnedProjectId != request.pinnedProjectId) return@updateControls this
                    copy(manualProjectPicker = current.copy(loading = false), message = UiMessage.ACTION_FAILED)
                }
            }
        }
    }
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

internal suspend fun settingsMutationUiMessage(
    mutation: suspend () -> SettingsMutationResult,
): UiMessage? = try {
    when (mutation()) {
        SettingsMutationResult.APPLIED -> null
        SettingsMutationResult.APPLIED_PENDING_RECOVERY -> UiMessage.SETTINGS_SYSTEM_PRIVACY_PENDING
    }
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: Exception) {
    UiMessage.ACTION_FAILED
}

internal fun restoreCompletionWarning(
    result: RestoreResult.Applied,
    systemWarning: UiMessage?,
): UiMessage? = if (result.preferencesPending) UiMessage.SETTINGS_SYSTEM_PRIVACY_PENDING else systemWarning

internal suspend fun systemSurfaceWarning(operation: suspend () -> Boolean): UiMessage? = try {
    if (operation()) null else UiMessage.SYSTEM_CONTROLS_UNAVAILABLE
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: Exception) {
    UiMessage.SYSTEM_CONTROLS_UNAVAILABLE
}

/** Both effects must run: notification availability must not short-circuit FGS startup. */
internal fun refreshAndEnsureSystemControls(
    refresh: () -> Boolean,
    ensureForeground: () -> Boolean,
): Boolean {
    val notificationAvailable = systemControlAttempt(refresh)
    val foregroundAvailable = systemControlAttempt(ensureForeground)
    return notificationAvailable && foregroundAvailable
}

private fun systemControlAttempt(operation: () -> Boolean): Boolean = try {
    operation()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: Exception) {
    false
}

internal fun transferFailureMessage(partialDocumentDeleted: Boolean): UiMessage =
    if (partialDocumentDeleted) UiMessage.DATA_IO_ERROR else UiMessage.PARTIAL_FILE_CLEANUP_REQUIRED

internal fun projectEditMessage(result: ProjectEditResult): UiMessage = when (result) {
    ProjectEditResult.APPLIED -> UiMessage.SAVED
    ProjectEditResult.LIMIT_REACHED -> UiMessage.PROJECT_LIMIT_REACHED
    ProjectEditResult.NOT_FOUND,
    ProjectEditResult.DUPLICATE_ID,
    ProjectEditResult.INVALID_NAME,
    -> UiMessage.INVALID_PROJECT
}

internal data class LocalDayContext(val date: LocalDate, val zoneId: ZoneId)

internal data class ReportRangeProjection(
    val period: ReportPeriod,
    val bounds: UtcBounds,
    val summary: ReportSummaryProjection?,
)

private data class SnapshotRequest(
    val historyLimit: Int,
    val projectLimit: Int,
    val pinnedProjectId: String,
)

internal data class TodayRangeProjection(
    val projection: ConfirmedDayProjection,
    val entries: List<EntryEntity>,
    val projects: List<ProjectEntity>,
)

/** Emits immediately and at least once a minute so midnight and time-zone changes re-key range queries. */
internal fun localDayContexts(
    now: () -> Instant = Instant::now,
    zoneId: () -> ZoneId = ZoneId::systemDefault,
    waitUntilNextDay: suspend (Long) -> Unit = { delay(it) },
): Flow<LocalDayContext> = flow {
    while (true) {
        val instant = now()
        val zone = zoneId()
        emit(LocalDayContext(instant.atZone(zone).toLocalDate(), zone))
        waitUntilNextDay(minOf(millisUntilNextLocalDay(instant, zone), LOCAL_DAY_POLL_MILLIS))
    }
}

private const val LOCAL_DAY_POLL_MILLIS = 60_000L

private fun EditResult.toMessage(): UiMessage = when (this) {
    EditResult.Applied -> UiMessage.SAVED
    EditResult.StaleRevision -> UiMessage.STALE_ENTRY
    EditResult.InvalidInput, EditResult.MissingProject, EditResult.ArchivedProject -> UiMessage.INVALID_ENTRY
    EditResult.NotFound, EditResult.ActiveEntry -> UiMessage.ACTION_FAILED
}

fun defaultCustomReport(today: LocalDate = LocalDate.now(ZoneId.systemDefault())): ReportPeriod.Custom =
    ReportPeriod.Custom(today.minusDays(6), today)

internal const val INITIAL_HISTORY_LIMIT = 100
internal const val MAX_HISTORY_LIMIT = 2_000
internal fun nextHistoryLimit(current: Int): Int =
    (current + INITIAL_HISTORY_LIMIT).coerceAtMost(MAX_HISTORY_LIMIT)
internal const val INITIAL_PROJECT_LIMIT = 100
internal const val MANUAL_PROJECT_PAGE_SIZE = 50
internal const val MAX_PROJECT_LIMIT = dev.opentimemanager.app.data.ProjectRepository.MAX_PROJECTS
internal fun nextProjectLimit(current: Int): Int =
    (current + INITIAL_PROJECT_LIMIT).coerceAtMost(MAX_PROJECT_LIMIT)
