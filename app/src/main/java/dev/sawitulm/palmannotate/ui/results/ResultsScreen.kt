package dev.sawitulm.palmannotate.ui.results

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
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
    var isComputing by mutableStateOf(false)
        private set
    var isExporting by mutableStateOf(false)
        private set
    var exportStatus by mutableStateOf<String?>(null)
        private set
    var showQualityGate by mutableStateOf(false)
        private set
    var qualityIssues by mutableStateOf<List<String>>(emptyList())
        private set
    var pendingExportAction by mutableStateOf<(suspend () -> Unit)?>(null)
        private set

    fun load(treeKey: String) {
        viewModelScope.launch {
            isComputing = true
            val s = repo.loadActiveSession(treeKey)
            session = s
            if (s != null) {
                // compute() walks union-find over every box — keep it off the main thread so
                // opening Results never janks (the screen showed a spinner meanwhile anyway).
                results = withContext(Dispatchers.Default) { ResultsComputer.compute(s) }
            }
            isComputing = false
        }
    }

    fun compute() {
        val s = session ?: return
        viewModelScope.launch {
            isComputing = true
            results = withContext(Dispatchers.Default) { ResultsComputer.compute(s) }
            isComputing = false
        }
    }

    fun saveOutputJson() {
        val s = session ?: return
        val r = results ?: return
        viewModelScope.launch {
            val safTreeUri = exportFolder.folderUri.first()
            repo.saveOutputJson(s, r, safTreeUri)
            exportStatus = "Output JSON saved"
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
                qualityIssues = checks.issues.map { it.message }
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
    viewModel: ResultsViewModel = hiltViewModel(),
) {
    LaunchedEffect(sessionId) { viewModel.load(sessionId) }

    val session = viewModel.session
    val results = viewModel.results

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(session?.treeName ?: "Results") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
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
                // ─── Hero stat cards ────────────────────────────────────
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Max),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        StatCard("Unique Bunches", r.uniqueCount.toString(), Icons.Default.Grain, accent = true, Modifier.weight(1f).fillMaxHeight())
                        StatCard("Total Detections", r.rawCount.toString(), Icons.Default.CenterFocusStrong, accent = false, Modifier.weight(1f).fillMaxHeight())
                        StatCard("Linked Duplicates", r.linkedCount.toString(), Icons.Default.Link, accent = false, Modifier.weight(1f).fillMaxHeight())
                    }
                }

                // ─── By-Class breakdown ─────────────────────────────────
                item {
                    SectionCard("By Class") {
                        classOrder.forEach { cls ->
                            val count = r.classCounts[cls] ?: 0
                            val label = if (cls == AnnotationClass.UNASSIGNED) "Other" else cls.displayName
                            CountBarRow(cls.composeColor, label, count, count.toFloat() / classMax)
                        }
                    }
                }

                // ─── By-Side breakdown ──────────────────────────────────
                item {
                    SectionCard("By Side") {
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

                // ─── Export ─────────────────────────────────────────────
                item {
                    SectionCard("Export") {
                        if (viewModel.isExporting) {
                            LinearProgressIndicator(Modifier.fillMaxWidth().padding(bottom = 10.dp))
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            ActionButton("Output JSON", Icons.Default.DataObject, primary = true, busy = viewModel.isExporting, Modifier.weight(1f)) { viewModel.exportOutputJson() }
                            ActionButton("YOLO .txt", Icons.Default.Description, primary = false, busy = viewModel.isExporting, Modifier.weight(1f)) { viewModel.exportYolo() }
                        }
                        Spacer(Modifier.height(10.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            ActionButton("CSV", Icons.Default.TableChart, primary = false, busy = viewModel.isExporting, Modifier.weight(1f)) { viewModel.exportCsv() }
                            ActionButton("Identity", Icons.Default.Fingerprint, primary = false, busy = viewModel.isExporting, Modifier.weight(1f)) { viewModel.exportIdentity() }
                        }
                        Spacer(Modifier.height(8.dp))
                        TextButton(onClick = { viewModel.saveOutputJson() }, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Default.Save, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Save Output Again")
                        }
                    }
                }

                // ─── Status message ─────────────────────────────────────
                item {
                    viewModel.exportStatus?.let { msg ->
                        Snackbar(
                            modifier = Modifier.fillMaxWidth(),
                            action = { TextButton(onClick = { viewModel.clearStatus() }) { Text("OK") } },
                        ) { Text(msg) }
                    }
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

@Composable
private fun StatCard(label: String, value: String, icon: ImageVector, accent: Boolean, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Icon(
                icon, null,
                modifier = Modifier.size(20.dp),
                tint = if (accent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                value,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = if (accent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            )
            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
            Text(
                title.uppercase(),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
