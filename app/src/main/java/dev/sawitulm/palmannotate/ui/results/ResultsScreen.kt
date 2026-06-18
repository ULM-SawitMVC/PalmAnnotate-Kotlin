package dev.sawitulm.palmannotate.ui.results

import android.net.Uri
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.sawitulm.palmannotate.R
import dev.sawitulm.palmannotate.ui.common.LocalToasts
import dev.sawitulm.palmannotate.data.export.ExportManager
import dev.sawitulm.palmannotate.data.storage.ExportFolderRepository
import dev.sawitulm.palmannotate.data.storage.SafMirrorStore
import dev.sawitulm.palmannotate.data.storage.SessionRepository
import dev.sawitulm.palmannotate.data.yolo.YoloParser
import dev.sawitulm.palmannotate.domain.model.*
import dev.sawitulm.palmannotate.domain.quality.QualityCheck
import dev.sawitulm.palmannotate.domain.results.ResultsComputer
import dev.sawitulm.palmannotate.ui.common.QualityGateModal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

private const val TAG = "ResultsVM"

// ════════════════════════════════════════════════════════════════════════════════
// ViewModel
// ════════════════════════════════════════════════════════════════════════════════

@HiltViewModel
class ResultsViewModel @Inject constructor(
    private val repo: SessionRepository,
    private val saf: SafMirrorStore,
    private val exportFolder: ExportFolderRepository,
) : ViewModel() {

    var session by mutableStateOf<ActiveSession?>(null)
        private set
    var results by mutableStateOf<TreeResults?>(null)
        private set
    /** Run (session) id that owns this tree — for "next capture" / "tree list" nav. */
    var runId by mutableStateOf<String?>(null)
        private set
    var isComputing by mutableStateOf(false)
        private set
    var isExporting by mutableStateOf(false)
        private set
    var exportStatus by mutableStateOf<String?>(null)
        private set
    var showQualityGate by mutableStateOf(false)
        private set
    var qualityIssues by mutableStateOf<List<QualityCheck.Issue>>(emptyList())
        private set
    var pendingExportAction by mutableStateOf<(suspend () -> Unit)?>(null)
        private set

    fun load(treeKey: String) {
        viewModelScope.launch {
            isComputing = true
            val s = repo.loadActiveSession(treeKey)
            session = s
            runId = repo.getTreeRunId(treeKey)
            if (s != null) {
                // compute() walks union-find over every box — keep it off the main thread so
                // opening Results never janks (the screen showed a spinner meanwhile anyway).
                results = withContext(Dispatchers.Default) { ResultsComputer.compute(s) }
            }
            isComputing = false
        }
    }

    /**
     * Finish this tree: persist the Output JSON (+ mark complete), then run [onNavigate].
     * Output JSON local write + DB flag are synchronous; the SAF mirror is backgrounded,
     * so this returns fast and navigation (next capture / tree list) feels instant.
     */
    fun finishAndThen(onNavigate: () -> Unit) {
        val s = session ?: return
        val r = results ?: return
        viewModelScope.launch {
            try {
                val safTreeUri = exportFolder.folderUri.first()
                repo.saveOutputJson(s, r, safTreeUri)
                exportStatus = "Output JSON saved"
            } catch (e: Exception) {
                // A failed finish must NOT crash nor silently advance: surface it and stay
                // so the operator can retry rather than leave the tree unmarked/unsaved.
                Log.e(TAG, "finishAndThen failed", e)
                exportStatus = e.localizedMessage ?: "Could not save Output JSON"
                return@launch
            }
            onNavigate()
        }
    }

    fun compute() {
        val s = session ?: return
        viewModelScope.launch {
            isComputing = true
            try {
                results = withContext(Dispatchers.Default) { ResultsComputer.compute(s) }
            } catch (e: Exception) {
                Log.e(TAG, "compute failed", e)
                exportStatus = e.localizedMessage ?: "Compute failed"
            } finally {
                isComputing = false
            }
        }
    }

    fun saveOutputJson() {
        val s = session ?: return
        val r = results ?: return
        viewModelScope.launch {
            try {
                val safTreeUri = exportFolder.folderUri.first()
                repo.saveOutputJson(s, r, safTreeUri)
                exportStatus = "Output JSON saved"
            } catch (e: Exception) {
                Log.e(TAG, "saveOutputJson failed", e)
                exportStatus = e.localizedMessage ?: "Save failed"
            }
        }
    }

    // ─── Export with quality gate ────────────────────────────────────────────

    private fun exportGated(actionLabel: String, action: suspend (Uri) -> Unit) {
        viewModelScope.launch {
            val s = session ?: return@launch
            val safUri = exportFolder.folderUri.first() ?: run {
                exportStatus = "Select an export folder first"
                return@launch
            }
            val checks = QualityCheck.analyzeTree(s)
            if (checks.status != QualityCheck.Level.OK) {
                qualityIssues = checks.issues
                pendingExportAction = {
                    action(safUri)
                    exportStatus = "$actionLabel exported"
                }
                showQualityGate = true
            } else {
                isExporting = true
                try {
                    action(safUri)
                    exportStatus = "$actionLabel exported"
                } catch (e: Exception) {
                    Log.e(TAG, "$actionLabel export failed", e)
                    exportStatus = e.localizedMessage ?: "$actionLabel export failed"
                } finally { isExporting = false }
            }
        }
    }

    fun exportOutputJson() = exportGated("Output JSON") { safUri ->
        val s = session ?: return@exportGated
        val r = results ?: return@exportGated
        val jsonText = ExportManager.generateOutputJson(s, r).toString(2)
        saf.writeText(safUri, "Output JSON/${s.treeName}.json", jsonText)
        repo.saveOutputJson(s, r, safUri)
    }

    fun exportYolo() = exportGated("YOLO") { safUri ->
        val s = session ?: return@exportGated
        for (side in s.sides) {
            val yolo = ExportManager.generateYoloTxt(side)
            if (yolo.isNotBlank()) {
                saf.writeText(safUri, "Output TXT/field/${s.treeName}_${side.sideIndex + 1}.txt", yolo)
            }
        }
    }

    fun exportCsv() = exportGated("CSV") { safUri ->
        val s = session ?: return@exportGated
        val r = results ?: return@exportGated
        val csv = ExportManager.generateCsv(s, r)
        saf.writeText(safUri, "exports/${s.treeName}_result.csv", csv)
    }

    fun exportIdentity() = exportGated("Identity JSON") { safUri ->
        val s = session ?: return@exportGated
        val r = results ?: return@exportGated
        val json = ExportManager.generateIdentityJson(s, r).toString(2)
        saf.writeText(safUri, "exports/${s.treeName}_identity.json", json)
    }

    fun dismissQualityGate(continueExport: Boolean) {
        showQualityGate = false
        if (continueExport && pendingExportAction != null) {
            viewModelScope.launch {
                isExporting = true
                try { pendingExportAction?.invoke() }
                catch (e: Exception) {
                    Log.e(TAG, "gated export failed", e)
                    exportStatus = e.localizedMessage ?: "Export failed"
                }
                finally { isExporting = false; pendingExportAction = null }
            }
        } else {
            pendingExportAction = null
        }
    }

    fun clearStatus() { exportStatus = null }
}

// ════════════════════════════════════════════════════════════════════════════════
// UI — matches the JS Results panel (#panel-results)
// ════════════════════════════════════════════════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResultsScreen(
    sessionId: String,
    onBack: () -> Unit,
    onCaptureNext: (runId: String) -> Unit = {},
    onTreeList: (runId: String) -> Unit = {},
    viewModel: ResultsViewModel = hiltViewModel(),
) {
    LaunchedEffect(sessionId) { viewModel.load(sessionId) }

    val session = viewModel.session
    val results = viewModel.results
    var showExportSheet by remember { mutableStateOf(false) }

    // Surface export/save status through the single app-level toast host instead of an
    // awkward Snackbar wedged into the scrolling content.
    val toasts = LocalToasts.current
    LaunchedEffect(viewModel.exportStatus) {
        viewModel.exportStatus?.let { msg ->
            toasts.info(msg)
            viewModel.clearStatus()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(session?.treeName ?: stringResource(R.string.results_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back)) }
                },
            )
        },
    ) { padding ->
        if (results == null || session == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            val r = results            // smart-cast non-null inside this branch
            val sess = session
            val classOrder = listOf(
                AnnotationClass.B1, AnnotationClass.B2, AnnotationClass.B3,
                AnnotationClass.B4, AnnotationClass.UNASSIGNED,
            )
            val classMax = classOrder.maxOf { r.classCounts[it] ?: 0 }.coerceAtLeast(1)
            val sideMax = (sess.sides.maxOfOrNull { it.bboxes.size } ?: 0).coerceAtLeast(1)

            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // ─── Headline result + supporting figures ───────────────
                // One emphasized number (the answer the operator came for), then two calmer
                // supporting stats — not three equal "hero metric" cards.
                item {
                    PrimaryResult(
                        label = stringResource(R.string.results_unique_bunches),
                        value = r.uniqueCount.toString(),
                    )
                }
                item {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        MiniStat(stringResource(R.string.results_total_detections), r.rawCount.toString(), Icons.Default.CenterFocusStrong, Modifier.weight(1f))
                        MiniStat(stringResource(R.string.results_linked_duplicates), r.linkedCount.toString(), Icons.Default.Link, Modifier.weight(1f))
                    }
                }

                // ─── By-Class breakdown ─────────────────────────────────
                item {
                    SectionCard(stringResource(R.string.results_by_class)) {
                        val otherLabel = stringResource(R.string.results_other)
                        classOrder.forEach { cls ->
                            val count = r.classCounts[cls] ?: 0
                            val label = if (cls == AnnotationClass.UNASSIGNED) otherLabel else cls.displayName
                            CountBarRow(cls.composeColor, label, count, count.toFloat() / classMax)
                        }
                    }
                }

                // ─── By-Side breakdown ──────────────────────────────────
                item {
                    SectionCard(stringResource(R.string.results_by_side)) {
                        sess.sides.forEachIndexed { i, side ->
                            CountBarRow(
                                MaterialTheme.colorScheme.primary,
                                "Side ${i + 1}",
                                side.bboxes.size,
                                side.bboxes.size.toFloat() / sideMax,
                            )
                        }
                    }
                }

                // ─── Finish: continue the workflow ──────────────────────
                // The two genuinely useful actions: move to the next capture, or go
                // back to the tree list. Both persist the Output JSON first (so the
                // tree is "saved by default" on finish). The many export formats are
                // tucked into a "more" sheet since they're rarely used and Output JSON
                // is written automatically here.
                item {
                    SectionCard(stringResource(R.string.results_finish)) {
                        if (viewModel.isExporting) {
                            LinearProgressIndicator(Modifier.fillMaxWidth().padding(bottom = 10.dp))
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            ActionButton(stringResource(R.string.results_capture_next), Icons.Default.PhotoCamera, primary = true, busy = viewModel.isExporting, Modifier.weight(1f)) {
                                viewModel.runId?.let { rid -> viewModel.finishAndThen { onCaptureNext(rid) } }
                            }
                            ActionButton(stringResource(R.string.results_tree_list), Icons.AutoMirrored.Filled.List, primary = false, busy = viewModel.isExporting, Modifier.weight(1f)) {
                                viewModel.runId?.let { rid -> viewModel.finishAndThen { onTreeList(rid) } }
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        TextButton(onClick = { showExportSheet = true }, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Default.Share, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.results_more_exports))
                        }
                    }
                }
            }
        }
    }

    // "More exports" bottom sheet — rarely-used formats, kept out of the main flow.
    if (showExportSheet) {
        ModalBottomSheet(onDismissRequest = { showExportSheet = false }) {
            Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 28.dp)) {
                Text(
                    stringResource(R.string.results_more_exports_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    stringResource(R.string.results_more_exports_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp, bottom = 14.dp),
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ActionButton(stringResource(R.string.results_export_output_json), Icons.Default.DataObject, primary = false, busy = viewModel.isExporting, Modifier.weight(1f)) { showExportSheet = false; viewModel.exportOutputJson() }
                    ActionButton(stringResource(R.string.results_export_yolo), Icons.Default.Description, primary = false, busy = viewModel.isExporting, Modifier.weight(1f)) { showExportSheet = false; viewModel.exportYolo() }
                }
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ActionButton(stringResource(R.string.results_export_csv), Icons.Default.TableChart, primary = false, busy = viewModel.isExporting, Modifier.weight(1f)) { showExportSheet = false; viewModel.exportCsv() }
                    ActionButton(stringResource(R.string.results_export_identity), Icons.Default.Fingerprint, primary = false, busy = viewModel.isExporting, Modifier.weight(1f)) { showExportSheet = false; viewModel.exportIdentity() }
                }
            }
        }
    }

    // Quality gate modal
    if (viewModel.showQualityGate) {
        QualityGateModal(
            issues = viewModel.qualityIssues,
            onContinue = { viewModel.dismissQualityGate(true) },
            onBack = { viewModel.dismissQualityGate(false) },
        )
    }
}

/** The headline figure: one emphasized number on a calm surface (not one of three equal cards). */
@Composable
private fun PrimaryResult(label: String, value: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.large,
    ) {
        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(4.dp))
                Text(value, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            }
            Icon(Icons.Default.Grain, null, modifier = Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f))
        }
    }
}

/** A compact supporting stat (icon + label over a value) — secondary to the headline. */
@Composable
private fun MiniStat(label: String, value: String, icon: ImageVector, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        tonalElevation = 1.dp,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(6.dp))
                Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(8.dp))
            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(16.dp)) {
            // Normal-case heading (was an all-caps tracked "eyebrow").
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}

/** One labelled, colour-coded count with a proportion bar — used for both class & side tables. */
@Composable
private fun CountBarRow(color: Color, label: String, count: Int, fraction: Float) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
    ) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(12.dp))
        Text(label, fontWeight = FontWeight.Medium, modifier = Modifier.width(64.dp))
        Box(
            modifier = Modifier
                .weight(1f)
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(color.copy(alpha = 0.15f)),
        ) {
            if (fraction > 0f) {
                Box(
                    Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(fraction.coerceIn(0f, 1f))
                        .clip(RoundedCornerShape(4.dp))
                        .background(color),
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Text(
            count.toString(),
            fontWeight = FontWeight.Bold,
            color = if (count > 0) color else MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.End,
            modifier = Modifier.widthIn(min = 28.dp),
        )
    }
}

@Composable
private fun ActionButton(
    label: String,
    icon: ImageVector,
    primary: Boolean,
    busy: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val inner: @Composable RowScope.() -> Unit = {
        Icon(icon, null, Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    if (primary) {
        Button(onClick = onClick, enabled = !busy, modifier = modifier, content = inner)
    } else {
        OutlinedButton(onClick = onClick, enabled = !busy, modifier = modifier, content = inner)
    }
}
