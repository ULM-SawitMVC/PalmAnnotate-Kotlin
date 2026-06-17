package dev.sawitulm.palmannotate.ui.home

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.view.WindowManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import dev.sawitulm.palmannotate.R
import dev.sawitulm.palmannotate.ui.common.LocalToasts
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.sawitulm.palmannotate.data.export.DatasetZipExporter
import dev.sawitulm.palmannotate.data.storage.ExportFolderRepository
import dev.sawitulm.palmannotate.data.storage.FolderResumeImporter
import dev.sawitulm.palmannotate.data.storage.InputCache
import dev.sawitulm.palmannotate.data.storage.RunSummary
import dev.sawitulm.palmannotate.data.storage.SessionRepository
import dev.sawitulm.palmannotate.ui.common.NewSessionDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

// ════════════════════════════════════════════════════════════════════════════════
// ViewModel — runs grouped by (variety, block); mirrors js/sessions.js Home.
// ════════════════════════════════════════════════════════════════════════════════

data class SessionGroup(
    val groupKey: String,
    val variety: String,
    val block: String,
    val runs: List<RunSummary>,
    val totalTrees: Int,
)

data class HomeStats(
    val totalSessions: Int = 0,
    val totalTrees: Int = 0,
    val totalGroups: Int = 0,
)

/** UI state for the dataset ZIP export (per-session or all). */
sealed interface ExportUiState {
    object Idle : ExportUiState
    data class Running(val done: Int, val total: Int, val tree: String) : ExportUiState
    data class Done(val uri: Uri, val fileName: String) : ExportUiState
    data class Error(val message: String) : ExportUiState
    object Empty : ExportUiState
    object Cancelled : ExportUiState
}

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val repo: SessionRepository,
    private val exportFolder: ExportFolderRepository,
    private val folderResumeImporter: FolderResumeImporter,
    private val zipExporter: DatasetZipExporter,
    val inputCache: InputCache,
) : ViewModel() {

    val runs: StateFlow<List<RunSummary>> = repo.observeRuns()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val stats: StateFlow<HomeStats> = runs.map { list ->
        HomeStats(
            totalSessions = list.size,
            totalTrees = list.sumOf { it.treeCount },
            totalGroups = list.map { it.groupKey }.distinct().size,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), HomeStats())

    val groups: StateFlow<List<SessionGroup>> = runs.map { list ->
        list.groupBy { it.groupKey }
            .map { (key, rs) ->
                val first = rs.first()
                SessionGroup(key, first.variety, first.block, rs.sortedByDescending { it.updatedAt }, rs.sumOf { it.treeCount })
            }
            .sortedByDescending { g -> g.runs.maxOfOrNull { it.updatedAt } ?: 0 }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val folderName: StateFlow<String?> = exportFolder.folderName
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    fun createRun(variety: String, block: String, sideCount: Int, autoId: Boolean, onDone: (String) -> Unit) {
        viewModelScope.launch {
            val id = try {
                repo.createRun(variety, block, sideCount, autoId)
            } catch (e: Exception) {
                // Navigation must not fire (nor the app crash) on a failed create.
                Log.e("HomeVM", "createRun failed", e)
                return@launch
            }
            onDone(id)
        }
    }

    fun deleteRun(sessionId: String) {
        viewModelScope.launch {
            try {
                // Pass the export-folder URI so SAF mirror copies are removed too (same
                // orphan-file issue as tree delete).
                val safTreeUri = exportFolder.folderUri.first()
                repo.deleteRun(sessionId, safTreeUri)
            } catch (e: Exception) {
                Log.e("HomeVM", "deleteRun failed", e)
            }
        }
    }

    fun setFolder(uri: android.net.Uri) {
        viewModelScope.launch { exportFolder.saveFolder(uri) }
    }

    /**
     * Persist the chosen export folder, then resume-by-scan: if it already holds a
     * PalmAnnotate/ structure, prior runs/trees are rebuilt into Room. The chosen
     * folder stays a best-effort mirror; app-external remains the primary store.
     * [onResult] reports how many trees were resumed (0 = new/empty folder).
     */
    fun setFolderAndResume(uri: android.net.Uri, onResult: (Int) -> Unit) {
        viewModelScope.launch {
            exportFolder.saveFolder(uri)
            val imported = folderResumeImporter.resumeFromFolder(uri)
            onResult(imported)
        }
    }

    fun clearFolder() {
        viewModelScope.launch { exportFolder.clear() }
    }

    // ─── Dataset ZIP export ──────────────────────────────────────────────────────

    private val _exportState = MutableStateFlow<ExportUiState>(ExportUiState.Idle)
    val exportState: StateFlow<ExportUiState> = _exportState

    /** Polled by the exporter between files; flipping it aborts and removes the partial zip. */
    private val cancelFlag = AtomicBoolean(false)

    fun exportRun(sessionId: String) = runExport { zipExporter.exportRun(sessionId, ::onZipProgress, cancelFlag::get) }

    fun exportAll() = runExport { zipExporter.exportAll(::onZipProgress, cancelFlag::get) }

    fun cancelExport() { cancelFlag.set(true) }

    /** Reset to Idle once the UI has consumed a terminal state (launched share / shown toast). */
    fun consumeExportState() { _exportState.value = ExportUiState.Idle }

    private fun onZipProgress(p: DatasetZipExporter.Progress) {
        _exportState.value = ExportUiState.Running(p.done, p.total, p.currentTree)
    }

    private fun runExport(block: suspend () -> DatasetZipExporter.Outcome) {
        if (_exportState.value is ExportUiState.Running) return  // one export at a time
        cancelFlag.set(false)
        _exportState.value = ExportUiState.Running(0, 0, "")
        viewModelScope.launch(Dispatchers.IO) {
            val outcome = try {
                block()
            } catch (e: Exception) {
                Log.e("HomeVM", "export failed", e)
                DatasetZipExporter.Outcome.Failed(e.message ?: "Export failed")
            }
            _exportState.value = when (outcome) {
                is DatasetZipExporter.Outcome.Success -> ExportUiState.Done(outcome.uri, outcome.fileName)
                DatasetZipExporter.Outcome.Empty -> ExportUiState.Empty
                DatasetZipExporter.Outcome.Cancelled -> ExportUiState.Cancelled
                is DatasetZipExporter.Outcome.Failed -> ExportUiState.Error(outcome.message)
            }
        }
    }
}

// ════════════════════════════════════════════════════════════════════════════════
// UI
// ════════════════════════════════════════════════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onSessionClick: (String) -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val toasts = LocalToasts.current
    val runs by viewModel.runs.collectAsState()
    val stats by viewModel.stats.collectAsState()
    val groups by viewModel.groups.collectAsState()
    val folderName by viewModel.folderName.collectAsState()
    val exportState by viewModel.exportState.collectAsState()

    val folderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
            // Persist the folder, then resume-by-scan any PalmAnnotate/ data it holds.
            viewModel.setFolderAndResume(uri) { imported ->
                if (imported > 0) toasts.success(context.getString(R.string.home_folder_resumed, imported))
                else toasts.info(context.getString(R.string.home_folder_new))
            }
        }
    }

    var showNewDialog by remember { mutableStateOf(false) }
    var expandedGroup by remember { mutableStateOf<String?>(null) }
    var showExportAllConfirm by remember { mutableStateOf(false) }

    // Terminal export states → launch share sheet / toast, then reset to Idle.
    LaunchedEffect(exportState) {
        when (val s = exportState) {
            is ExportUiState.Done -> {
                val share = Intent(Intent.ACTION_SEND).apply {
                    type = "application/zip"
                    putExtra(Intent.EXTRA_STREAM, s.uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                runCatching {
                    context.startActivity(
                        Intent.createChooser(share, context.getString(R.string.home_export_zip_run)),
                    )
                }
                toasts.success(context.getString(R.string.export_zip_done, s.fileName))
                viewModel.consumeExportState()
            }
            is ExportUiState.Error -> {
                toasts.error(context.getString(R.string.export_zip_failed, s.message))
                viewModel.consumeExportState()
            }
            ExportUiState.Empty -> {
                toasts.info(context.getString(R.string.export_zip_empty))
                viewModel.consumeExportState()
            }
            ExportUiState.Cancelled -> {
                toasts.info(context.getString(R.string.export_zip_cancelled))
                viewModel.consumeExportState()
            }
            else -> {}
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.app_name), fontWeight = FontWeight.Bold)
                        Text(stringResource(R.string.app_tagline), style = MaterialTheme.typography.bodySmall)
                    }
                },
                actions = {
                    if (runs.isNotEmpty()) {
                        IconButton(onClick = { showExportAllConfirm = true }) {
                            Icon(
                                Icons.Default.Archive,
                                contentDescription = stringResource(R.string.home_export_zip_all),
                                tint = MaterialTheme.colorScheme.onPrimary,
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                ),
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showNewDialog = true },
                icon = { Icon(Icons.Default.Add, null) },
                text = { Text(stringResource(R.string.home_new_session)) },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                ExportFolderCard(
                    folderName = folderName,
                    onChoose = { folderPicker.launch(null) },
                    onClear = { viewModel.clearFolder() },
                )
            }

            if (stats.totalSessions > 0) {
                item {
                    ElevatedCard(Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(16.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                            StatItem(stringResource(R.string.home_stat_sessions), stats.totalSessions)
                            StatItem(stringResource(R.string.home_stat_trees), stats.totalTrees)
                            StatItem(stringResource(R.string.home_stat_groups), stats.totalGroups)
                        }
                    }
                }
            }

            if (runs.isEmpty()) {
                item {
                    ElevatedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(24.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Icon(Icons.Default.Forest, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
                            Text(stringResource(R.string.home_empty_title), style = MaterialTheme.typography.titleMedium)
                            Text(stringResource(R.string.home_empty_body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                        }
                    }
                }
            }

            for (group in groups) {
                val isExpanded = expandedGroup == group.groupKey
                item {
                    GroupHeader(group, isExpanded) { expandedGroup = if (isExpanded) null else group.groupKey }
                }
                if (isExpanded) {
                    items(group.runs, key = { it.sessionId }) { run ->
                        RunCard(
                            run,
                            onClick = { onSessionClick(run.sessionId) },
                            onDelete = { viewModel.deleteRun(run.sessionId) },
                            onExport = { viewModel.exportRun(run.sessionId) },
                        )
                    }
                }
            }
        }
    }

    if (showNewDialog) {
        NewSessionDialog(
            onDismiss = { showNewDialog = false },
            onCreate = { variety, block, sideCount, autoId ->
                viewModel.createRun(variety, block, sideCount, autoId) { runId ->
                    showNewDialog = false
                    onSessionClick(runId)
                }
            },
            inputCache = viewModel.inputCache,
        )
    }

    if (showExportAllConfirm) {
        AlertDialog(
            onDismissRequest = { showExportAllConfirm = false },
            title = { Text(stringResource(R.string.export_zip_all_confirm_title)) },
            text = { Text(stringResource(R.string.export_zip_all_confirm_body, stats.totalTrees)) },
            confirmButton = {
                TextButton(onClick = { showExportAllConfirm = false; viewModel.exportAll() }) {
                    Text(stringResource(R.string.home_export_zip_all))
                }
            },
            dismissButton = {
                TextButton(onClick = { showExportAllConfirm = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }

    (exportState as? ExportUiState.Running)?.let { running ->
        ExportProgressDialog(running, onCancel = { viewModel.cancelExport() })
    }
}

/** Non-dismissable progress dialog shown while a ZIP is being built; keeps the screen awake. */
@Composable
private fun ExportProgressDialog(state: ExportUiState.Running, onCancel: () -> Unit) {
    val context = LocalContext.current
    // Hold the screen on for the (potentially multi-minute) streaming zip so the app stays
    // foreground and isn't doze-killed mid-export.
    DisposableEffect(Unit) {
        val window = context.findActivity()?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
    AlertDialog(
        onDismissRequest = { /* non-dismissable — use Cancel */ },
        title = { Text(stringResource(R.string.export_zip_progress_title)) },
        text = {
            Column {
                if (state.total > 0) {
                    LinearProgressIndicator(
                        progress = { state.done.toFloat() / state.total },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    stringResource(R.string.export_zip_progress_status, state.done, state.total, state.tree),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

/** Unwrap a (possibly wrapped) Context to its hosting Activity, for window flags. */
private fun Context.findActivity(): Activity? {
    var ctx: Context = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}

@Composable
private fun ExportFolderCard(
    folderName: String?,
    onChoose: () -> Unit,
    onClear: () -> Unit,
) {
    val isSet = folderName != null
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Folder,
                    contentDescription = null,
                    tint = if (isSet) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.home_export_folder), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Text(
                        folderName ?: stringResource(R.string.home_export_folder_unset),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isSet) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                    )
                }
                TextButton(onClick = onChoose) {
                    Text(stringResource(if (isSet) R.string.home_export_folder_change else R.string.home_export_folder_choose))
                }
                if (isSet) {
                    TextButton(onClick = onClear) { Text(stringResource(R.string.home_export_folder_clear)) }
                }
            }
            if (!isSet) {
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.home_export_folder_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun StatItem(label: String, value: Int) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value.toString(), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun GroupHeader(group: SessionGroup, isExpanded: Boolean, onToggle: () -> Unit) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onToggle),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Row(Modifier.padding(12.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Folder, null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text("${group.variety} · ${group.block}", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSecondaryContainer)
                Text(
                    stringResource(R.string.home_group_summary, group.runs.size, group.totalTrees),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
            Icon(if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
        }
    }
}

@Composable
private fun RunCard(run: RunSummary, onClick: () -> Unit, onDelete: () -> Unit, onExport: () -> Unit) {
    var confirmDelete by remember { mutableStateOf(false) }
    val dateFormat = remember { SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault()) }

    // Flattened sub-row (not a nested ElevatedCard): a tonal surface indented under its
    // group header, so the group→run relationship reads as a list, not card-in-card.
    Surface(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp).clickable(onClick = onClick),
        tonalElevation = 1.dp,
        shape = MaterialTheme.shapes.medium,
    ) {
        Row(Modifier.padding(start = 14.dp, top = 10.dp, bottom = 10.dp, end = 4.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("${run.variety} · ${run.block}", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium, maxLines = 1)
                Text(stringResource(R.string.home_run_summary, run.treeCount, run.sideCount), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(dateFormat.format(Date(run.updatedAt)), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = onExport) {
                Icon(Icons.Default.IosShare, stringResource(R.string.home_export_zip_run), tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
            }
            IconButton(onClick = { confirmDelete = true }) {
                Icon(Icons.Default.Delete, stringResource(R.string.action_delete), tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
            }
            IconButton(onClick = onClick) {
                Icon(Icons.AutoMirrored.Filled.ArrowForward, null, modifier = Modifier.size(20.dp))
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.home_delete_session_title)) },
            text = { Text(stringResource(R.string.home_delete_session_body, "${run.variety} · ${run.block}", run.treeCount)) },
            confirmButton = { TextButton(onClick = { confirmDelete = false; onDelete() }) { Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}
