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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import dev.sawitulm.palmannotate.R
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import android.util.Log
import dev.sawitulm.palmannotate.data.db.SessionEntity
import dev.sawitulm.palmannotate.data.db.TreeEntity
import dev.sawitulm.palmannotate.data.storage.ExportFolderRepository
import dev.sawitulm.palmannotate.data.storage.MirrorStates
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

    private val runIdFlow = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)

    val run: StateFlow<SessionEntity?> = runIdFlow
        .flatMapLatest { id -> if (id == null) flowOf(null) else repo.observeRun(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val trees: StateFlow<List<TreeEntity>> = runIdFlow
        .flatMapLatest { id -> if (id == null) flowOf(emptyList()) else repo.observeTrees(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val mirrorStatuses = repo.observeMirrorStatuses()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun retryMirror(treeKey: String) {
        viewModelScope.launch { repo.retryMirror(treeKey) }
    }

    fun load(runId: String) {
        runIdFlow.value = runId
    }

    fun deleteTree(treeKey: String) {
        viewModelScope.launch {
            try {
                // Pass the export-folder URI so the SAF mirror copies (images, labels,
                // Output JSON/TXT) are deleted too. Without this the export folder kept the
                // tree's files, and a later recapture (id reset → same path) was then SKIPPED
                // by the "mirror once if absent" guard, leaving the OLD photo in the export.
                val safTreeUri = exportFolder.folderUri.first()
                repo.deleteTree(treeKey, safTreeUri)
            } catch (e: Exception) {
                Log.e("SessionDetailVM", "deleteTree failed", e)
            }
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
    val run by viewModel.run.collectAsState()
    val trees by viewModel.trees.collectAsState()
    val mirrorStatuses by viewModel.mirrorStatuses.collectAsState()
    val mirrorByTree = remember(mirrorStatuses) { mirrorStatuses.associateBy { it.treeKey } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.session_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back)) }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onAddTree,
                icon = { Icon(Icons.Default.Add, null) },
                text = { Text(stringResource(R.string.session_add_tree)) },
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
                    Text(stringResource(R.string.session_trees_header), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }
                if (trees.isEmpty()) {
                    item {
                        Text(
                            stringResource(R.string.session_empty),
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
                            mirrorStatus = mirrorByTree[tree.treeKey],
                            onRetryMirror = { viewModel.retryMirror(tree.treeKey) },
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
                    stringResource(R.string.session_locked),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        }
    }
}

@Composable
private fun RunStats(run: SessionEntity, trees: List<TreeEntity>) {
    val photos = trees.sumOf { it.sideCount }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Stat(stringResource(R.string.home_stat_trees), trees.size.toString(), Modifier.weight(1f))
        Stat(stringResource(R.string.session_stat_photos), photos.toString(), Modifier.weight(1f))
        Stat(stringResource(R.string.session_stat_next_id), if (run.autoId) "%04d".format(run.nextId) else "—", Modifier.weight(1f))
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
private fun TreeRow(
    tree: TreeEntity,
    onAnnotate: () -> Unit,
    onCarousel: () -> Unit,
    onDelete: () -> Unit,
    mirrorStatus: dev.sawitulm.palmannotate.data.db.MirrorStatusEntity?,
    onRetryMirror: () -> Unit,
) {
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
                Text(
                    stringResource(
                        if (tree.isComplete) R.string.session_tree_sides_complete else R.string.session_tree_sides,
                        tree.sideCount,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                mirrorStatus?.let { status ->
                    val label = when (status.status) {
                        MirrorStates.VERIFIED -> "Remote verified (r${status.verifiedRevision ?: status.requestedRevision})"
                        MirrorStates.PENDING -> "Remote mirror pending (r${status.requestedRevision})"
                        MirrorStates.FAILED -> "Remote mirror failed: ${status.errorMessage ?: "retry required"}"
                        else -> "Remote mirror not requested"
                    }
                    Text(
                        label,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (status.status == MirrorStates.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
            if (mirrorStatus?.status == MirrorStates.FAILED) {
                TextButton(onClick = onRetryMirror) { Text("Retry") }
            }
            if (tree.isComplete) Icon(Icons.Default.CheckCircle, stringResource(R.string.cd_complete), tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(4.dp))
            IconButton(onClick = onCarousel) {
                Icon(Icons.Default.ViewCarousel, stringResource(R.string.cd_open_carousel), modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = { confirm = true }) {
                Icon(Icons.Default.Delete, stringResource(R.string.action_delete), tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
            }
        }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text(stringResource(R.string.session_delete_tree_title)) },
            text = { Text(stringResource(R.string.session_delete_tree_body, tree.treeName, tree.sideCount)) },
            confirmButton = { TextButton(onClick = { confirm = false; onDelete() }) { Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}
