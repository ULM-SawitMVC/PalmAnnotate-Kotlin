package dev.sawitulm.palmannotate.ui.home

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.content.Intent
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.sawitulm.palmannotate.data.storage.ExportFolderRepository
import dev.sawitulm.palmannotate.data.storage.FolderResumeImporter
import dev.sawitulm.palmannotate.data.storage.InputCache
import dev.sawitulm.palmannotate.data.storage.RunSummary
import dev.sawitulm.palmannotate.data.storage.SessionRepository
import dev.sawitulm.palmannotate.ui.common.NewSessionDialog
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*
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

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val repo: SessionRepository,
    private val exportFolder: ExportFolderRepository,
    private val folderResumeImporter: FolderResumeImporter,
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
        viewModelScope.launch { onDone(repo.createRun(variety, block, sideCount, autoId)) }
    }

    fun deleteRun(sessionId: String) {
        viewModelScope.launch { repo.deleteRun(sessionId) }
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
    val runs by viewModel.runs.collectAsState()
    val stats by viewModel.stats.collectAsState()
    val groups by viewModel.groups.collectAsState()
    val folderName by viewModel.folderName.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

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
                scope.launch {
                    val msg = if (imported > 0) "Resumed $imported tree${if (imported == 1) "" else "s"} from folder" else "New folder set"
                    snackbarHostState.showSnackbar(msg)
                }
            }
        }
    }

    var showNewDialog by remember { mutableStateOf(false) }
    var expandedGroup by remember { mutableStateOf<String?>(null) }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("PalmAnnotate", fontWeight = FontWeight.Bold)
                        Text("Oil Palm · Offline", style = MaterialTheme.typography.bodySmall)
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
                icon = { Icon(Icons.Default.Add, "New") },
                text = { Text("New Session") },
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
                            StatItem("Sessions", stats.totalSessions)
                            StatItem("Trees", stats.totalTrees)
                            StatItem("Groups", stats.totalGroups)
                        }
                    }
                }
            }

            if (runs.isEmpty()) {
                item {
                    ElevatedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(24.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Icon(Icons.Default.Forest, "Tree", Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
                            Text("No sessions yet", style = MaterialTheme.typography.titleMedium)
                            Text("Tap \"New Session\" to start documenting trees.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                        RunCard(run, onClick = { onSessionClick(run.sessionId) }, onDelete = { viewModel.deleteRun(run.sessionId) })
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
                    contentDescription = "Export folder",
                    tint = if (isSet) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Export folder", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Text(
                        folderName ?: "Not set — files are kept privately inside the app",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isSet) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                    )
                }
                TextButton(onClick = onChoose) {
                    Text(if (isSet) "Change" else "Choose")
                }
                if (isSet) {
                    TextButton(onClick = onClear) { Text("Clear") }
                }
            }
            if (!isSet) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Choose a public folder so your captured images, metadata, and export files are also saved somewhere you can browse with a file manager or another app.",
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
            Icon(Icons.Default.Folder, "Group", tint = MaterialTheme.colorScheme.onSecondaryContainer)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text("${group.variety} · ${group.block}", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSecondaryContainer)
                Text(
                    "${group.runs.size} session${if (group.runs.size > 1) "s" else ""} · ${group.totalTrees} trees",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f),
                )
            }
            Icon(if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, "Toggle", tint = MaterialTheme.colorScheme.onSecondaryContainer)
        }
    }
}

@Composable
private fun RunCard(run: RunSummary, onClick: () -> Unit, onDelete: () -> Unit) {
    var confirmDelete by remember { mutableStateOf(false) }
    val dateFormat = remember { SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault()) }

    ElevatedCard(Modifier.fillMaxWidth().padding(start = 16.dp).clickable(onClick = onClick)) {
        Row(Modifier.padding(12.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("${run.variety} · ${run.block}", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium, maxLines = 1)
                Text("${run.treeCount} trees · ${run.sideCount} photos/tree", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(dateFormat.format(Date(run.updatedAt)), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = { confirmDelete = true }) {
                Icon(Icons.Default.Delete, "Delete", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
            }
            IconButton(onClick = onClick) {
                Icon(Icons.AutoMirrored.Filled.ArrowForward, "Open", modifier = Modifier.size(18.dp))
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete Session?") },
            text = { Text("\"${run.variety} · ${run.block}\" and its ${run.treeCount} tree(s) will be removed, including stored photos/outputs.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; onDelete() }) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}
