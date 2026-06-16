package dev.sawitulm.palmannotate.ui.annotation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.sawitulm.palmannotate.data.detection.OnnxDetector
import dev.sawitulm.palmannotate.data.storage.ExportFolderRepository
import dev.sawitulm.palmannotate.data.storage.SessionRepository
import dev.sawitulm.palmannotate.domain.model.*
import dev.sawitulm.palmannotate.domain.usecase.SessionUseCases
import dev.sawitulm.palmannotate.domain.util.OperationQueue
import dev.sawitulm.palmannotate.ui.common.AnnotationCanvas
import dev.sawitulm.palmannotate.ui.common.CanvasTool
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

// ════════════════════════════════════════════════════════════════════════════════
// ViewModel
// ════════════════════════════════════════════════════════════════════════════════

@HiltViewModel
class AnnotationViewModel @Inject constructor(
    private val repo: SessionRepository,
    private val exportFolder: ExportFolderRepository,
    private val detector: OnnxDetector,
    private val opq: OperationQueue,
) : ViewModel() {

    var session by mutableStateOf<ActiveSession?>(null)
        private set
    var currentSideIndex by mutableIntStateOf(0)
        private set
    var selectedBboxId by mutableStateOf<String?>(null)
        private set
    var currentTool by mutableStateOf(CanvasTool.SELECT)
        private set
    var showBoxes by mutableStateOf(true)
        private set
    var isDetecting by mutableStateOf(false)
        private set

    val isBusy: Boolean get() = opq.isBusy

    val currentSide: TreeSide?
        get() = session?.sides?.getOrNull(currentSideIndex)

    fun load(sessionId: String) {
        viewModelScope.launch {
            session = repo.loadActiveSession(sessionId)
        }
    }

    fun selectSide(index: Int) {
        currentSideIndex = index
        selectedBboxId = null
    }

    fun nextSide() {
        val s = session ?: return
        if (currentSideIndex < s.sides.size - 1) selectSide(currentSideIndex + 1)
    }

    fun prevSide() {
        if (currentSideIndex > 0) selectSide(currentSideIndex - 1)
    }

    fun selectBbox(id: String?) {
        selectedBboxId = id
    }

    fun setTool(tool: CanvasTool) {
        currentTool = tool
    }

    fun toggleBoxes() {
        showBoxes = !showBoxes
    }

    fun addBbox(x1: Float, y1: Float, x2: Float, y2: Float) {
        val s = session ?: return
        val side = s.sides.getOrNull(currentSideIndex) ?: return
        val newId = Bbox.nextId(side.bboxes, "b")
        session = SessionUseCases.addBbox(s, currentSideIndex, x1, y1, x2, y2)
        selectedBboxId = newId
    }

    fun updateBbox(bboxId: String, x1: Float, y1: Float, x2: Float, y2: Float) {
        val s = session ?: return
        val side = s.sides.getOrNull(currentSideIndex) ?: return
        val updatedBboxes = side.bboxes.map { b ->
            if (b.id == bboxId) b.copy(x1 = x1, y1 = y1, x2 = x2, y2 = y2) else b
        }
        val updatedSides = s.sides.toMutableList()
        updatedSides[currentSideIndex] = side.copy(bboxes = updatedBboxes)
        session = s.copy(sides = updatedSides)
    }

    fun changeBboxClass(bboxId: String, newClass: AnnotationClass) {
        val s = session ?: return
        // Use SessionUseCases to change class AND propagate to cluster siblings
        session = SessionUseCases.setBboxClass(s, currentSideIndex, bboxId, newClass, propagate = true)
    }

    fun deleteBbox(bboxId: String) {
        val s = session ?: return
        // Shared helper also prunes any confirmed/suggested links touching this box.
        session = SessionUseCases.deleteBbox(s, currentSideIndex, bboxId)
        selectedBboxId = null
    }

    fun save() {
        val s = session ?: return
        opq.enqueue("save-annotation") {
            val safTreeUri = exportFolder.folderUri.first()
            repo.saveSession(s, safTreeUri)
        }
    }

    /** Save and wait for completion (used by dedup button). */
    suspend fun saveAndAwait() {
        val s = session ?: return
        opq.enqueueAndWait("save-annotation") {
            val safTreeUri = exportFolder.folderUri.first()
            repo.saveSession(s, safTreeUri)
        }
    }
    
    /** Save only DB (fast) - used before navigating to dedup. */
    suspend fun saveDbOnly() {
        val s = session ?: return
        opq.enqueueAndWait("save-annotation-db") {
            repo.saveDbOnly(s)
        }
    }

    fun detectCurrentSide() {
        val side = currentSide ?: return
        val uri = side.imageUri ?: return
        viewModelScope.launch {
            isDetecting = true
            try {
                val detections = detector.detect(uri)
                val s = session ?: return@launch
                // Never-reused ids: each new box derives its id from the running
                // box list so a prior delete can't make a detect id collide.
                val running = side.bboxes.toMutableList()
                val newBoxes = detections.filter { d ->
                    val overlaps = side.bboxes.any { existing ->
                        val existingArea = (existing.x2 - existing.x1) * (existing.y2 - existing.y1)
                        val detArea = (d.x2 - d.x1) * (d.y2 - d.y1)
                        val ix1 = maxOf(d.x1, existing.x1)
                        val iy1 = maxOf(d.y1, existing.y1)
                        val ix2 = minOf(d.x2, existing.x2)
                        val iy2 = minOf(d.y2, existing.y2)
                        val iw = maxOf(0f, ix2 - ix1)
                        val ih = maxOf(0f, iy2 - iy1)
                        val inter = iw * ih
                        val union = existingArea + detArea - inter
                        union > 0f && inter / union > 0.5f
                    }
                    !overlaps
                }.map { d ->
                    val id = Bbox.nextId(running, "det")
                    Bbox.unassigned(id, d.x1, d.y1, d.x2, d.y2).also { running.add(it) }
                }
                // For freshly captured trees the annot-log baseline is empty;
                // seed originalBboxes with the detector output (the suggestion baseline).
                val baseline = if (side.originalBboxes.isEmpty()) newBoxes else side.originalBboxes
                val updatedSides = s.sides.toMutableList()
                updatedSides[currentSideIndex] = side.copy(
                    bboxes = side.bboxes + newBoxes,
                    originalBboxes = baseline,
                )
                session = s.copy(sides = updatedSides)
            } catch (_: Exception) {
            } finally {
                isDetecting = false
            }
        }
    }
}

// ════════════════════════════════════════════════════════════════════════════════
// UI — matches the annotation editor from index.html
// ════════════════════════════════════════════════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnnotationScreen(
    sessionId: String,                 // treeKey
    treeIndex: Int = 0,                // starting side index
    onBack: () -> Unit,
    onViewResults: () -> Unit,
    onOpenDedup: () -> Unit = {},
    onOpenCarousel: () -> Unit = {},
    viewModel: AnnotationViewModel = hiltViewModel(),
) {
    LaunchedEffect(sessionId) { viewModel.load(sessionId) }
    LaunchedEffect(treeIndex) { viewModel.selectSide(treeIndex) }

    val session = viewModel.session
    val scope = rememberCoroutineScope()
    val currentSide = viewModel.currentSide

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(session?.treeName ?: "Annotation", maxLines = 1)
                        if (currentSide != null) {
                            Text(
                                currentSide.label,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
                actions = {
                    // Detect
                    IconButton(
                        onClick = { viewModel.detectCurrentSide() },
                        enabled = !viewModel.isDetecting,
                    ) {
                        if (viewModel.isDetecting) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Default.AutoAwesome, "Detect")
                        }
                    }
                    // Carousel
                    IconButton(onClick = onOpenCarousel) {
                        Icon(Icons.Default.ViewCarousel, "Carousel")
                    }
                    // Dedup
                    IconButton(onClick = {
                        // Save DB only (fast, ~12ms) then navigate.
                        // writeSideArtifacts (YOLO/SAF) runs later via normal save.
                        scope.launch {
                            viewModel.saveDbOnly()
                            onOpenDedup()
                        }
                    }) {
                        Icon(Icons.Default.Link, "Deduplication")
                    }
                    // Save
                    IconButton(onClick = { viewModel.save() }) {
                        Icon(Icons.Default.Save, "Save")
                    }
                    // Toggle boxes
                    IconButton(onClick = { viewModel.toggleBoxes() }) {
                        Icon(
                            if (viewModel.showBoxes) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                            "Toggle boxes",
                        )
                    }
                },
            )
        },
        bottomBar = {
            // Class buttons + tools
            Surface(
                tonalElevation = 3.dp,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(8.dp)) {
                    // Side pills
                    if (session != null && session!!.sides.size > 1) {
                        Row(
                            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            for (i in session!!.sides.indices) {
                                val isSelected = i == viewModel.currentSideIndex
                                val side = session!!.sides[i]
                                val hasUnassigned = side.hasUnassigned
                                FilterChip(
                                    selected = isSelected,
                                    onClick = { viewModel.selectSide(i) },
                                    label = {
                                        Text(
                                            "S${i + 1}",
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                        )
                                    },
                                    leadingIcon = if (hasUnassigned) {
                                        { Badge(containerColor = MaterialTheme.colorScheme.error, modifier = Modifier.size(8.dp)) }
                                    } else null,
                                )
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                    }

                    // Tools row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // Class buttons
                        for (cls in AnnotationClass.assignableEntries) {
                            val isSelected = viewModel.selectedBboxId?.let { id ->
                                currentSide?.bboxes?.find { it.id == id }?.classId == cls.id
                            } == true
                            ClassButton(
                                cls = cls,
                                isSelected = isSelected,
                                onClick = {
                                    viewModel.selectedBboxId?.let { id ->
                                        viewModel.changeBboxClass(id, cls)
                                    }
                                },
                            )
                        }

                        Spacer(Modifier.width(8.dp))

                        // Tool buttons
                        ToolButton(
                            icon = Icons.Default.Edit,
                            label = "Select",
                            isActive = viewModel.currentTool == CanvasTool.SELECT,
                            onClick = { viewModel.setTool(CanvasTool.SELECT) },
                        )
                        ToolButton(
                            icon = Icons.Default.Crop,
                            label = "Draw",
                            isActive = viewModel.currentTool == CanvasTool.DRAW,
                            onClick = { viewModel.setTool(CanvasTool.DRAW) },
                        )

                        // Delete
                        IconButton(
                            onClick = {
                                viewModel.selectedBboxId?.let { viewModel.deleteBbox(it) }
                            },
                            enabled = viewModel.selectedBboxId != null,
                        ) {
                            Icon(Icons.Default.Delete, "Delete", tint = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        },
    ) { padding ->
        if (currentSide == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("No side selected")
            }
        } else {
            Box(Modifier.fillMaxSize().padding(padding)) {
                AnnotationCanvas(
                    imageUriString = currentSide.imageUri?.toString(),
                    bboxes = currentSide.bboxes,
                    selectedBboxId = viewModel.selectedBboxId,
                    imageWidth = currentSide.imageWidth.coerceAtLeast(1),
                    imageHeight = currentSide.imageHeight.coerceAtLeast(1),
                    tool = viewModel.currentTool,
                    showBoxes = viewModel.showBoxes,
                    onBboxTap = { viewModel.selectBbox(it) },
                    onBboxMoved = { id, x1, y1, x2, y2 -> viewModel.updateBbox(id, x1, y1, x2, y2) },
                    onBboxDrawn = { x1, y1, x2, y2 -> viewModel.addBbox(x1, y1, x2, y2) },
                    onCanvasTap = { viewModel.selectBbox(null) },
                    modifier = Modifier.fillMaxSize(),
                )

                // Bbox count overlay
                Surface(
                    modifier = Modifier.align(Alignment.TopStart).padding(8.dp),
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
                    tonalElevation = 2.dp,
                ) {
                    Text(
                        "${currentSide.bboxes.size} bbox" +
                            if (currentSide.hasUnassigned) " (${currentSide.unassignedBboxCount} unassigned)" else "",
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = if (currentSide.hasUnassigned) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.onSurface,
                    )
                }

                // Navigation arrows
                Row(
                    modifier = Modifier.fillMaxWidth().align(Alignment.Center),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    if (viewModel.currentSideIndex > 0) {
                        IconButton(onClick = { viewModel.prevSide() }) {
                            Icon(Icons.Default.ChevronLeft, "Prev side", modifier = Modifier.size(36.dp))
                        }
                    } else Spacer(Modifier.size(48.dp))
                    if (session != null && viewModel.currentSideIndex < session!!.sides.size - 1) {
                        IconButton(onClick = { viewModel.nextSide() }) {
                            Icon(Icons.Default.ChevronRight, "Next side", modifier = Modifier.size(36.dp))
                        }
                    } else Spacer(Modifier.size(48.dp))
                }
            }
        }
    }
}

// ════════════════════════════════════════════════════════════════════════════════
// Helper composables
// ════════════════════════════════════════════════════════════════════════════════

@Composable
private fun ClassButton(cls: AnnotationClass, isSelected: Boolean, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.size(40.dp).clip(RoundedCornerShape(6.dp)).clickable(onClick = onClick),
        color = cls.composeColor.copy(alpha = if (isSelected) 1f else 0.6f),
        shape = RoundedCornerShape(6.dp),
        border = if (isSelected) ButtonDefaults.outlinedButtonBorder(enabled = true) else null,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                cls.displayName,
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun ToolButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, isActive: Boolean, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        IconButton(onClick = onClick, modifier = Modifier.size(36.dp)) {
            Icon(icon, label, tint = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
        }
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}
