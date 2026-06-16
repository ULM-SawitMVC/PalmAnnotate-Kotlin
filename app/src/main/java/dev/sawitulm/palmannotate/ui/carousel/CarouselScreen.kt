package dev.sawitulm.palmannotate.ui.carousel

import androidx.compose.animation.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
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

// ═══════════════════════════════════════════════════════════════════════
// ViewModel
// ═══════════════════════════════════════════════════════════════════════

@HiltViewModel
class CarouselViewModel @Inject constructor(
    private val repo: SessionRepository,
    private val exportFolder: ExportFolderRepository,
    private val detector: OnnxDetector,
    private val opq: OperationQueue,
) : ViewModel() {

    var session by mutableStateOf<ActiveSession?>(null)
        private set
    var currentSideIndex by mutableIntStateOf(0)
    var selectedBboxId by mutableStateOf<String?>(null)
    var showBoxes by mutableStateOf(true)
    var mode by mutableStateOf(CarouselMode.REVIEW)
    var isLoading by mutableStateOf(true)
    var isSaving by mutableStateOf(false)
    var linkArmed by mutableStateOf(false)
    var isDetecting by mutableStateOf(false)
        private set

    val currentSide: TreeSide?
        get() = session?.sides?.getOrNull(currentSideIndex)

    val totalSides: Int get() = session?.sides?.size ?: 0

    fun load(treeKey: String) {
        viewModelScope.launch {
            isLoading = true
            session = repo.loadActiveSession(treeKey)
            isLoading = false
        }
    }

    fun selectSide(index: Int) {
        if (index in 0 until totalSides) {
            currentSideIndex = index
            selectedBboxId = null
            linkArmed = false
        }
    }

    fun selectBbox(id: String?) {
        selectedBboxId = id
    }

    fun toggleBoxes() { showBoxes = !showBoxes }
    fun toggleMode() { mode = if (mode == CarouselMode.REVIEW) CarouselMode.EDIT else CarouselMode.REVIEW }

    fun changeBboxClass(bboxId: String, newClass: AnnotationClass) {
        val s = session ?: return
        session = SessionUseCases.setBboxClass(s, currentSideIndex, bboxId, newClass, propagate = true)
    }

    fun deleteBbox(bboxId: String) {
        val s = session ?: return
        session = SessionUseCases.deleteBbox(s, currentSideIndex, bboxId)
        selectedBboxId = null
    }

    fun addBbox(x1: Float, y1: Float, x2: Float, y2: Float) {
        val s = session ?: return
        session = SessionUseCases.addBbox(s, currentSideIndex, x1, y1, x2, y2)
    }

    fun updateBbox(bboxId: String, x1: Float, y1: Float, x2: Float, y2: Float) {
        val s = session ?: return
        session = SessionUseCases.updateBbox(s, currentSideIndex, bboxId, x1, y1, x2, y2)
    }

    fun armLink() {
        selectedBboxId?.let {
            linkArmed = true
        }
    }

    fun createLink(targetBboxId: String) {
        if (!linkArmed) return
        val s = session ?: return
        val srcBboxId = selectedBboxId ?: return
        val srcSide = currentSideIndex
        val targetSide = if (srcSide < totalSides - 1) srcSide + 1 else srcSide - 1
        if (srcBboxId != targetBboxId && srcSide != targetSide) {
            session = SessionUseCases.addManualLink(s, srcSide, srcBboxId, targetSide, targetBboxId)
            linkArmed = false
        }
    }

    fun save() {
        val s = session ?: return
        opq.enqueue("save-carousel") {
            val safTreeUri = exportFolder.folderUri.first()
            repo.saveSession(s, safTreeUri)
        }
    }

    fun saveAndExit(onDone: () -> Unit) {
        val s = session ?: return
        opq.enqueue("save-carousel") {
            val safTreeUri = exportFolder.folderUri.first()
            repo.saveSession(s, safTreeUri)
            onDone()
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

enum class CarouselMode(val label: String) { REVIEW("Review"), EDIT("Edit") }

// ═══════════════════════════════════════════════════════════════════════
// UI — full-screen swipe carousel (JS Annotate/CarouselUI)
// ═══════════════════════════════════════════════════════════════════════

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun CarouselScreen(
    sessionId: String,
    onBack: () -> Unit,
    onDedup: () -> Unit = {},
    onResults: () -> Unit = {},
    onDepth: () -> Unit = {},
    onNextTree: () -> Unit = {},
    viewModel: CarouselViewModel = hiltViewModel(),
) {
    LaunchedEffect(sessionId) { viewModel.load(sessionId) }

    val session = viewModel.session
    val totalSides = viewModel.totalSides
    val pagerState = rememberPagerState(pageCount = { totalSides.coerceAtLeast(1) })

    LaunchedEffect(pagerState.currentPage) {
        if (pagerState.currentPage != viewModel.currentSideIndex) {
            viewModel.selectSide(pagerState.currentPage)
        }
    }

    var showMoreMenu by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(session?.treeName ?: "Annotate", maxLines = 1, fontSize = 16.sp)
                        Text(
                            "${pagerState.currentPage + 1} / $totalSides",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
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
                    // Mode toggle
                    FilterChip(
                        selected = viewModel.mode == CarouselMode.EDIT,
                        onClick = { viewModel.toggleMode() },
                        label = { Text(viewModel.mode.label, fontSize = 12.sp) },
                        modifier = Modifier.height(30.dp),
                    )
                    // More menu
                    IconButton(onClick = { showMoreMenu = true }) {
                        Icon(Icons.Default.MoreVert, "More")
                    }
                    DropdownMenu(expanded = showMoreMenu, onDismissRequest = { showMoreMenu = false }) {
                        DropdownMenuItem(
                            text = { Text("Deduplication") },
                            onClick = { showMoreMenu = false; onDedup() },
                            leadingIcon = { Icon(Icons.Default.Link, null) },
                        )
                        DropdownMenuItem(
                            text = { Text("Results") },
                            onClick = { showMoreMenu = false; onResults() },
                            leadingIcon = { Icon(Icons.Default.Assessment, null) },
                        )
                        DropdownMenuItem(
                            text = { Text("Depth & RAW viewer") },
                            onClick = { showMoreMenu = false; onDepth() },
                            leadingIcon = { Icon(Icons.Default.Thermostat, null) },
                        )
                    }
                },
            )
        },
        bottomBar = {
            // Class bar + actions
            CarouselBottomBar(
                session = session,
                currentSideIndex = viewModel.currentSideIndex,
                selectedBboxId = viewModel.selectedBboxId,
                showBoxes = viewModel.showBoxes,
                linkArmed = viewModel.linkArmed,
                isSave = viewModel.isSaving,
                onClassChange = { id, cls -> viewModel.changeBboxClass(id, cls) },
                onDelete = { viewModel.deleteBbox(it) },
                onToggleBoxes = { viewModel.toggleBoxes() },
                onArmLink = { viewModel.armLink() },
                onCancelLink = { viewModel.linkArmed = false; viewModel.selectBbox(null) },
                onSaveExit = { viewModel.saveAndExit { onBack() } },
                onNextTree = { viewModel.saveAndExit { onNextTree() } },
            )
        },
    ) { padding ->
        if (session == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize().padding(padding),
            ) { page ->
                val side = session!!.sides.getOrNull(page) ?: return@HorizontalPager

                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    AnnotationCanvas(
                        imageUriString = side.imageUri?.toString(),
                        bboxes = side.bboxes,
                        selectedBboxId = if (page == viewModel.currentSideIndex) viewModel.selectedBboxId else null,
                        imageWidth = side.imageWidth.coerceAtLeast(1),
                        imageHeight = side.imageHeight.coerceAtLeast(1),
                        tool = if (viewModel.mode == CarouselMode.EDIT) CanvasTool.SELECT else CanvasTool.SELECT,
                        showBoxes = viewModel.showBoxes,
                        onBboxTap = { id ->
                            if (page != viewModel.currentSideIndex) {
                                coroutineScope.launch { pagerState.animateScrollToPage(page) }
                                viewModel.selectSide(page)
                            }
                            if (viewModel.linkArmed && id != null) {
                                viewModel.createLink(id)
                            } else {
                                viewModel.selectBbox(id)
                            }
                        },
                        onBboxMoved = { id, x1, y1, x2, y2 ->
                            if (page == viewModel.currentSideIndex) viewModel.updateBbox(id, x1, y1, x2, y2)
                        },
                        onBboxDrawn = { x1, y1, x2, y2 ->
                            if (page == viewModel.currentSideIndex) viewModel.addBbox(x1, y1, x2, y2)
                        },
                        modifier = Modifier.fillMaxSize(),
                    )

                    // Bbox count overlay
                    Surface(
                        modifier = Modifier.align(Alignment.TopStart).padding(8.dp),
                        shape = RoundedCornerShape(4.dp),
                        color = Color.Black.copy(alpha = 0.7f),
                    ) {
                        Text(
                            "${side.bboxes.size} boxes" +
                                (if (side.hasUnassigned) " · ${side.unassignedBboxCount} unassigned" else ""),
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.labelMedium,
                            color = if (side.hasUnassigned) Color(0xFFE4B84A) else Color.White,
                        )
                    }

                    // Link armed indicator
                    if (viewModel.linkArmed) {
                        Surface(
                            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 100.dp),
                            shape = RoundedCornerShape(8.dp),
                            color = Color(0xFFB8E04A).copy(alpha = 0.9f),
                        ) {
                            Text(
                                "← Swipe to adjacent side → Tap matching bunch",
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF0C120C),
                            )
                        }
                    }
                }
            }

            // Page dots
            if (totalSides > 1) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 56.dp)
                        .padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    for (i in 0 until totalSides) {
                        Box(
                            modifier = Modifier
                                .clickable { coroutineScope.launch { pagerState.animateScrollToPage(i) } }
                                .padding(3.dp)
                                .size(if (i == pagerState.currentPage) 8.dp else 6.dp)
                                .clip(CircleShape)
                                .background(
                                    if (i == pagerState.currentPage) Color(0xFFB8E04A)
                                    else Color.White.copy(alpha = 0.4f)
                                ),
                        )
                    }
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════
// Bottom bar
// ═══════════════════════════════════════════════════════════════════════

@Composable
private fun CarouselBottomBar(
    session: ActiveSession?,
    currentSideIndex: Int,
    selectedBboxId: String?,
    showBoxes: Boolean,
    linkArmed: Boolean,
    isSave: Boolean,
    onClassChange: (String, AnnotationClass) -> Unit,
    onDelete: (String) -> Unit,
    onToggleBoxes: () -> Unit,
    onArmLink: () -> Unit,
    onCancelLink: () -> Unit,
    onSaveExit: () -> Unit,
    onNextTree: () -> Unit,
) {
    Surface(tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            // Class buttons row — widened to ~48dp touch targets with more spacing so
            // the operator's thumb does not hit the wrong control on a moving boat/field.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                for (cls in AnnotationClass.assignableEntries) {
                    val isSelected = selectedBboxId?.let { id ->
                        session?.sides?.getOrNull(currentSideIndex)?.bboxes?.find { it.id == id }?.classId == cls.id
                    } == true
                    Surface(
                        modifier = Modifier
                            .height(48.dp)
                            .widthIn(min = 52.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .clickable {
                                selectedBboxId?.let { onClassChange(it, cls) }
                            },
                        color = cls.composeColor.copy(alpha = if (isSelected) 1f else 0.5f),
                        shape = RoundedCornerShape(8.dp),
                        border = if (isSelected) ButtonDefaults.outlinedButtonBorder(enabled = true) else null,
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                cls.displayName,
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }

                Spacer(Modifier.width(8.dp))

                // Delete
                IconButton(
                    onClick = { selectedBboxId?.let { onDelete(it) } },
                    enabled = selectedBboxId != null,
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(Icons.Default.Delete, "Delete", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(26.dp))
                }

                // Link (arm / cancel if armed)
                if (linkArmed) {
                    TextButton(onClick = onCancelLink, modifier = Modifier.height(48.dp)) {
                        Text("Cancel Link", fontSize = 14.sp, color = MaterialTheme.colorScheme.error)
                    }
                } else {
                    IconButton(
                        onClick = onArmLink,
                        enabled = selectedBboxId != null,
                        modifier = Modifier.size(48.dp),
                    ) {
                        Icon(Icons.Default.Link, "Link", modifier = Modifier.size(26.dp))
                    }
                }

                Spacer(Modifier.weight(1f))

                // Boxes toggle
                IconButton(onClick = onToggleBoxes, modifier = Modifier.size(48.dp)) {
                    Icon(
                        if (showBoxes) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                        "Boxes",
                        modifier = Modifier.size(26.dp),
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            // Action buttons row — full-width split buttons (no tiny text targets).
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                FilledTonalButton(
                    onClick = onSaveExit,
                    modifier = Modifier.weight(1f).height(48.dp),
                ) { Text("Save & Exit", fontSize = 15.sp) }
                Button(
                    onClick = onNextTree,
                    modifier = Modifier.weight(1f).height(48.dp),
                ) { Text("Next Tree", fontSize = 15.sp) }
            }
        }
    }
}
