package dev.sawitulm.palmannotate.ui.session

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.sawitulm.palmannotate.data.db.SessionEntity
import dev.sawitulm.palmannotate.data.db.TreeEntity
import dev.sawitulm.palmannotate.data.storage.ExportFolderRepository
import dev.sawitulm.palmannotate.data.storage.SessionRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

// ════════════════════════════════════════════════════════════════════════════════
// ViewModel — a RUN (variety+block) with its list of trees.
// ════════════════════════════════════════════════════════════════════════════════

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@HiltViewModel
class SessionDetailViewModel @Inject constructor(
    private val repo: SessionRepository,
    private val exportFolder: ExportFolderRepository,
) : ViewModel() {

    var run by mutableStateOf<SessionEntity?>(null)
        private set

    private val runIdFlow = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)

    val trees: StateFlow<List<TreeEntity>> = runIdFlow
        .flatMapLatest { id -> if (id == null) flowOf(emptyList()) else repo.observeTrees(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun load(runId: String) {
        runIdFlow.value = runId
        viewModelScope.launch { run = repo.getRun(runId) }
    }

    fun deleteTree(treeKey: String) {
        viewModelScope.launch {
            // Pass the export-folder URI so the SAF mirror copies (images, labels,
            // Output JSON/TXT) are deleted too. Without this the export folder kept the
            // tree's files, and a later recapture (id reset → same path) was then SKIPPED
            // by the "mirror once if absent" guard, leaving the OLD photo in the export.
            val safTreeUri = exportFolder.folderUri.first()
            repo.deleteTree(treeKey, safTreeUri)
            run?.let { run = repo.getRun(it.sessionId) } // refresh nextId
        }
    }
}

// ════════════════════════════════════════════════════════════════════════════════
// UI — mirrors the session-detail view from js/sessions.js
// ════════════════════════════════════════════════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionDetailScreen(
    sessionId: String,                 // run id
    onBack: () -> Unit,
    onAddTree: () -> Unit,             // navigate to capture(runId)
    onOpenTree: (String) -> Unit,      // navigate to annotation(treeKey)
    onOpenCarousel: (String) -> Unit = {}, // navigate to carousel(treeKey)
    viewModel: SessionDetailViewModel = hiltViewModel(),
) {
    LaunchedEffect(sessionId) { viewModel.load(sessionId) }
    val run = viewModel.run
    val trees by viewModel.trees.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Session") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onAddTree,
                icon = { Icon(Icons.Default.Add, "Add tree") },
                text = { Text("Add Tree") },
            )
        },
    ) { padding ->
        if (run == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item { LockBadge(run!!) }
                item { RunStats(run!!, trees) }
                item {
                    Text("Trees", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }
                if (trees.isEmpty()) {
                    item {
                        Text(
                            "No trees yet. Tap \"Add Tree\" to capture the first one.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    items(trees, key = { it.treeKey }) { tree ->
                        TreeRow(
                            tree = tree,
                            onAnnotate = { onOpenTree(tree.treeKey) },
                            onCarousel = { onOpenCarousel(tree.treeKey) },
                            onDelete = { viewModel.deleteTree(tree.treeKey) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LockBadge(run: SessionEntity) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Lock, null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
            Spacer(Modifier.width(8.dp))
            Column {
                Text(
                    "${run.variety}${if (run.block.isNotBlank()) " · ${run.block}" else ""}",
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                Text(
                    "Locked for this session",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f),
                )
            }
        }
    }
}

@Composable
private fun RunStats(run: SessionEntity, trees: List<TreeEntity>) {
    val photos = trees.sumOf { it.sideCount }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Stat("Trees", trees.size.toString(), Modifier.weight(1f))
        Stat("Photos", photos.toString(), Modifier.weight(1f))
        Stat("Next ID", if (run.autoId) "%04d".format(run.nextId) else "—", Modifier.weight(1f))
    }
}

@Composable
private fun Stat(label: String, value: String, modifier: Modifier = Modifier) {
    ElevatedCard(modifier) {
        Column(Modifier.padding(12.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun TreeRow(tree: TreeEntity, onAnnotate: () -> Unit, onCarousel: () -> Unit, onDelete: () -> Unit) {
    var confirm by remember { mutableStateOf(false) }
    ElevatedCard(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.primary, modifier = Modifier.height(36.dp).widthIn(min = 44.dp)) {
                Box(Modifier.padding(horizontal = 8.dp), contentAlignment = Alignment.Center) {
                    Text("%04d".format(tree.treeId), color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f).clickable(onClick = onAnnotate)) {
                Text(tree.treeName, fontWeight = FontWeight.Medium, maxLines = 1)
                Text("${tree.sideCount} sides${if (tree.isComplete) " · complete" else ""}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (tree.isComplete) Icon(Icons.Default.CheckCircle, "Complete", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(4.dp))
            IconButton(onClick = onCarousel) {
                Icon(Icons.Default.ViewCarousel, "Carousel", modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = { confirm = true }) {
                Icon(Icons.Default.Delete, "Delete", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
            }
        }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Delete tree?") },
            text = { Text("${tree.treeName} and its ${tree.sideCount} photos will be removed.") },
            confirmButton = { TextButton(onClick = { confirm = false; onDelete() }) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } },
        )
    }
}
