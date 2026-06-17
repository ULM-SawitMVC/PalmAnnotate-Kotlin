package dev.sawitulm.palmannotate.ui.carousel

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerDefaults
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
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.sawitulm.palmannotate.R
import dev.sawitulm.palmannotate.ui.theme.PalmColors
import dev.sawitulm.palmannotate.data.detection.OnnxDetector
import dev.sawitulm.palmannotate.data.storage.ExportFolderRepository
import dev.sawitulm.palmannotate.data.storage.SessionRepository
import dev.sawitulm.palmannotate.domain.model.*
import dev.sawitulm.palmannotate.domain.usecase.SessionUseCases
import dev.sawitulm.palmannotate.domain.util.OperationQueue
import dev.sawitulm.palmannotate.ui.common.AnnotationCanvas
import dev.sawitulm.palmannotate.ui.common.CanvasTool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    /** Per-screen swipe direction (NOT persisted): true = swipe runs right→left and the
     *  visual side order is reversed. Visual-only — no seam semantics like dedup's clockwise. */
    var reverseSwipe by mutableStateOf(false)
        private set
    var mode by mutableStateOf(CarouselMode.REVIEW)
    var isLoading by mutableStateOf(true)
    var isSaving by mutableStateOf(false)
    var linkArmed by mutableStateOf(false)
    /** Pending link source — mirrors dedup's pendingBboxId/pendingSide pattern.
     *  Set when user selects a box and taps Link; cleared on link creation or cancel. */
    var pendingLinkBboxId by mutableStateOf<String?>(null)
        private set
    var pendingLinkSide by mutableIntStateOf(-1)
        private set
    var isDetecting by mutableStateOf(false)
        private set

    // Active sub-tool while in EDIT mode (REVIEW mode is always read-only / CanvasTool.VIEW).
    var editTool by mutableStateOf(CanvasTool.SELECT)

    // RUN id for this tree, resolved on load — needed to navigate to "capture next tree".
    var runId by mutableStateOf<String?>(null)
        private set

    // Bumped each time a silent auto-save completes — drives a brief "Tersimpan ✓" pulse.
    var savedTick by mutableStateOf(0L)
        private set
    // Unpersisted-edit flag so auto-save is a no-op when nothing changed (avoids
    // re-writing identical label/SAF artifacts on every swipe or mode toggle).
    private var dirty = false

    val currentSide: TreeSide?
        get() = session?.sides?.getOrNull(currentSideIndex)

    val totalSides: Int get() = session?.sides?.size ?: 0

    /** Canvas tool for the current mode: read-only in REVIEW, the chosen tool in EDIT. */
    val canvasTool: CanvasTool
        get() = if (mode == CarouselMode.REVIEW) CanvasTool.VIEW else editTool

    fun load(treeKey: String) {
        viewModelScope.launch {
            isLoading = true
            session = repo.loadActiveSession(treeKey)
            runId = repo.getTreeRunId(treeKey)
            isLoading = false
        }
    }

    fun selectSide(index: Int) {
        if (index in 0 until totalSides) {
            // Persist edits made on the side we're leaving (swipe/dots) before switching.
            autoSave()
            currentSideIndex = index
            selectedBboxId = null
            // Keep linkArmed across swipes — the pendingLinkBboxId/Side track the source.
            // Link is completed or cancelled in onBboxTap / cancelLink.
        }
    }

    fun selectBbox(id: String?) {
        selectedBboxId = id
    }

    fun toggleBoxes() { showBoxes = !showBoxes }
    fun toggleSwipeDirection() { reverseSwipe = !reverseSwipe }
    fun toggleMode() {
        // Auto-save when flipping Edit↔Review so boxes drawn in Edit are never lost.
        autoSave()
        mode = if (mode == CarouselMode.REVIEW) CarouselMode.EDIT else CarouselMode.REVIEW
    }

    /**
     * Silent persistence (no busy overlay): writes DB + local artifacts synchronously and
     * mirrors SAF in the background. No-op unless [dirty]. Called on side change / mode
     * toggle / exit so the operator never has to remember to "Save".
     */
    fun autoSave() {
        if (!dirty) return
        val s = session ?: return
        dirty = false
        viewModelScope.launch {
            val safTreeUri = exportFolder.folderUri.first()
            repo.saveSession(s, safTreeUri)
            savedTick = System.currentTimeMillis()
        }
    }

    /** EDIT-mode sub-tool: flip between move/resize (SELECT) and draw-new-box (DRAW). */
    fun toggleDrawTool() {
        editTool = if (editTool == CanvasTool.DRAW) CanvasTool.SELECT else CanvasTool.DRAW
    }

    fun changeBboxClass(bboxId: String, newClass: AnnotationClass) {
        val s = session ?: return
        session = SessionUseCases.setBboxClass(s, currentSideIndex, bboxId, newClass, propagate = true)
        dirty = true
    }

    fun deleteBbox(bboxId: String) {
        val s = session ?: return
        session = SessionUseCases.deleteBbox(s, currentSideIndex, bboxId)
        selectedBboxId = null
        dirty = true
    }

    fun addBbox(x1: Float, y1: Float, x2: Float, y2: Float) {
        val s = session ?: return
        val side = s.sides.getOrNull(currentSideIndex) ?: return
        // Auto-select the freshly drawn box so the operator can immediately tap a class
        // (parity with the old annotation editor).
        val newId = Bbox.nextId(side.bboxes, "b")
        session = SessionUseCases.addBbox(s, currentSideIndex, x1, y1, x2, y2)
        selectedBboxId = newId
        dirty = true
    }

    fun updateBbox(bboxId: String, x1: Float, y1: Float, x2: Float, y2: Float) {
        val s = session ?: return
        session = SessionUseCases.updateBbox(s, currentSideIndex, bboxId, x1, y1, x2, y2)
        dirty = true
    }

    fun armLink() {
        selectedBboxId?.let {
            linkArmed = true
            pendingLinkBboxId = it
            pendingLinkSide = currentSideIndex
        }
    }

    fun cancelLink() {
        linkArmed = false
        pendingLinkBboxId = null
        pendingLinkSide = -1
        selectedBboxId = null
    }

    /** Create a link between pending source and [targetBboxId] on the current side.
     *  Mirrors DedupViewModel.onBboxTap second-tap logic. */
    fun completeLink(targetBboxId: String) {
        if (!linkArmed) return
        val s = session ?: return
        val srcId = pendingLinkBboxId ?: return
        val srcSide = pendingLinkSide
        val tgtSide = currentSideIndex
        if (srcSide == tgtSide) return           // must be different sides
        // Note: srcId == targetBboxId is OK — IDs like "b0" repeat across sides.
        session = SessionUseCases.addManualLink(s, srcSide, srcId, tgtSide, targetBboxId)
        linkArmed = false
        pendingLinkBboxId = null
        pendingLinkSide = -1
        dirty = true
    }

    /**
     * Maps each linked bbox on [sideIndex] to a stable 1-based link-group number, derived
     * from the tree's confirmed cross-side links (union-find). The SAME number appears on
     * the matching bunch on the adjacent side, so the operator can see at a glance which
     * boxes are linked together.
     */
    fun linkGroupFor(sideIndex: Int): Map<String, Int> {
        val s = session ?: return emptyMap()
        if (s.confirmedLinks.isEmpty()) return emptyMap()
        val parent = HashMap<String, String>()
        fun root(x: String): String {
            var r = x
            while ((parent[r] ?: r) != r) r = parent[r]!!
            return r
        }
        fun union(a: String, b: String) {
            parent.putIfAbsent(a, a); parent.putIfAbsent(b, b)
            val ra = root(a); val rb = root(b)
            if (ra != rb) parent[ra] = rb
        }
        fun key(side: Int, b: String) = "$side $b"
        s.confirmedLinks.forEach { l -> union(key(l.sideA, l.bboxIdA), key(l.sideB, l.bboxIdB)) }
        // Stable group numbering by sorted component root.
        val groupNum = parent.keys.map { root(it) }.distinct().sorted()
            .withIndex().associate { (i, r) -> r to i + 1 }
        val result = HashMap<String, Int>()
        parent.keys.forEach { k ->
            val sep = k.indexOf(' ')
            val side = k.substring(0, sep).toInt()
            val boxId = k.substring(sep + 1)
            if (side == sideIndex) result[boxId] = groupNum.getValue(root(k))
        }
        return result
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
            withContext(Dispatchers.Main) { onDone() }
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
                if (newBoxes.isNotEmpty()) dirty = true
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
    onNextTree: (runId: String) -> Unit = {},
    viewModel: CarouselViewModel = hiltViewModel(),
) {
    LaunchedEffect(sessionId) { viewModel.load(sessionId) }

    val session = viewModel.session
    val totalSides = viewModel.totalSides
    val sidesCount = totalSides.coerceAtLeast(1)
    val reverseSwipe = viewModel.reverseSwipe
    // Infinite/looping pager: a huge virtual page count (only when there is more than one
    // side).  Normal mode: page N → side (N % sidesCount).  Reversed mode: page N → side
    // (sidesCount - 1 - N % sidesCount).  Combined formula used everywhere:
    //   fun pageToSide(page) = if (reverseSwipe) sidesCount - 1 - page % sidesCount
    //                          else               page % sidesCount
    // This keeps the visual side order identical (1,2,3,4 left→right) in both modes,
    // but reversed flips which swipe direction advances — the physical "walk around the
    // tree" direction, not the card order.
    // Note: no key() block — its forced recreation caused a visible "1/0" hang when the
    // session first loaded (sidesCount jumps 1→4 and the pager was discarded mid-frame).
    val loop = totalSides > 1
    val pagerState = rememberPagerState(
        initialPage = if (loop) (Int.MAX_VALUE / 2).let { it - it % sidesCount } else 0,
        pageCount = { if (loop) Int.MAX_VALUE else sidesCount },
    )
    // Firmer, less "slippery" snap (a crisp settle like the capture-preview pager).
    val pagerFling = PagerDefaults.flingBehavior(
        state = pagerState,
        snapAnimationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium),
    )

    // Sync VM ↔ pager: map virtual page to real side index through the mode-dependent formula.
    LaunchedEffect(pagerState.currentPage, reverseSwipe) {
        val raw = pagerState.currentPage % sidesCount
        val side = if (reverseSwipe) sidesCount - 1 - raw else raw
        if (side != viewModel.currentSideIndex) viewModel.selectSide(side)
    }
    // When reverseSwipe toggles, pivot around the current side: find the virtual page in
    // the new mode that maps to the same real side, and jump there.  Normal page for side S
    // is cur + (S - raw); reversed page for side S is cur + ((sidesCount-1-S) - raw).
    LaunchedEffect(reverseSwipe, sidesCount) {
        if (!loop) return@LaunchedEffect
        val cur = pagerState.currentPage
        val raw = cur % sidesCount
        val side = if (!reverseSwipe) sidesCount - 1 - raw else raw  // side before toggle
        val newRaw = if (reverseSwipe) sidesCount - 1 - side else side
        val delta = newRaw - raw
        if (delta != 0) pagerState.scrollToPage(cur + delta)
    }

    var showMoreMenu by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()

    // System / gesture back also saves before leaving.
    BackHandler { viewModel.saveAndExit { onBack() } }

    // Brief "Tersimpan ✓" pulse whenever an auto-save completes.
    var showSaved by remember { mutableStateOf(false) }
    LaunchedEffect(viewModel.savedTick) {
        if (viewModel.savedTick > 0L) {
            showSaved = true
            kotlinx.coroutines.delay(1300)
            showSaved = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(session?.treeName ?: stringResource(R.string.carousel_title_fallback), maxLines = 1, style = MaterialTheme.typography.titleMedium)
                        Text(
                            run {
                                val raw = pagerState.currentPage % sidesCount
                                val sideIdx = if (reverseSwipe) sidesCount - 1 - raw else raw
                                "${sideIdx + 1} / $totalSides"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    // Save-then-leave so edits are never lost by tapping Back.
                    IconButton(onClick = { viewModel.saveAndExit { onBack() } }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back))
                    }
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
                            Icon(Icons.Default.AutoAwesome, stringResource(R.string.cd_detect))
                        }
                    }
                    // Mode toggle
                    FilterChip(
                        selected = viewModel.mode == CarouselMode.EDIT,
                        onClick = { viewModel.toggleMode() },
                        label = {
                            Text(
                                stringResource(if (viewModel.mode == CarouselMode.EDIT) R.string.carousel_mode_edit else R.string.carousel_mode_review),
                                style = MaterialTheme.typography.labelLarge,
                            )
                        },
                        modifier = Modifier.heightIn(min = 40.dp).padding(horizontal = 2.dp),
                    )
                    // More menu
                    IconButton(onClick = { showMoreMenu = true }) {
                        Icon(Icons.Default.MoreVert, stringResource(R.string.cd_more))
                    }
                    DropdownMenu(expanded = showMoreMenu, onDismissRequest = { showMoreMenu = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.menu_deduplication)) },
                            onClick = { showMoreMenu = false; onDedup() },
                            leadingIcon = { Icon(Icons.Default.Link, null) },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.menu_results)) },
                            onClick = { showMoreMenu = false; onResults() },
                            leadingIcon = { Icon(Icons.Default.Assessment, null) },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.menu_depth_viewer)) },
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
                reverseSwipe = viewModel.reverseSwipe,
                linkArmed = viewModel.linkArmed,
                isSave = viewModel.isSaving,
                editMode = viewModel.mode == CarouselMode.EDIT,
                editTool = viewModel.editTool,
                onToggleDraw = { viewModel.toggleDrawTool() },
                onClassChange = { id, cls -> viewModel.changeBboxClass(id, cls) },
                onDelete = { viewModel.deleteBbox(it) },
                onToggleBoxes = { viewModel.toggleBoxes() },
                onToggleSwipe = { viewModel.toggleSwipeDirection() },
                onArmLink = { viewModel.armLink() },
                onCancelLink = { viewModel.cancelLink() },
                onSaveExit = { viewModel.saveAndExit { onBack() } },
                onNextTree = {
                    val rid = viewModel.runId
                    if (rid != null) viewModel.saveAndExit { onNextTree(rid) }
                    else viewModel.saveAndExit { onBack() }
                },
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
                reverseLayout = viewModel.reverseSwipe,
                flingBehavior = pagerFling,
                modifier = Modifier.fillMaxSize().padding(padding),
            ) { page ->
                val raw = page % sidesCount
                val sideIdx = if (viewModel.reverseSwipe) sidesCount - 1 - raw else raw
                val side = session!!.sides.getOrNull(sideIdx) ?: return@HorizontalPager
                // bboxId → link-group number for this side (same number on the matching
                // bunch on the adjacent side), so links are visible at a glance.
                val linkMap = viewModel.linkGroupFor(sideIdx)

                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    AnnotationCanvas(
                        imageUriString = side.imageUri?.toString(),
                        bboxes = side.bboxes,
                        selectedBboxId = if (sideIdx == viewModel.currentSideIndex) viewModel.selectedBboxId else null,
                        imageWidth = side.imageWidth.coerceAtLeast(1),
                        imageHeight = side.imageHeight.coerceAtLeast(1),
                        tool = viewModel.canvasTool,
                        showBoxes = viewModel.showBoxes,
                        linkedBoxes = linkMap,
                        onBboxTap = { id ->
                            if (sideIdx != viewModel.currentSideIndex) {
                                coroutineScope.launch { pagerState.animateScrollToPage(page) }
                                viewModel.selectSide(sideIdx)
                            }
                            if (viewModel.linkArmed && id != null) {
                                if (sideIdx != viewModel.pendingLinkSide) {
                                    viewModel.completeLink(id)
                                } else {
                                    viewModel.cancelLink()
                                    viewModel.selectBbox(id)
                                }
                            } else {
                                viewModel.selectBbox(id)
                            }
                        },
                        onBboxMoved = { id, x1, y1, x2, y2 ->
                            if (sideIdx == viewModel.currentSideIndex) viewModel.updateBbox(id, x1, y1, x2, y2)
                        },
                        onBboxDrawn = { x1, y1, x2, y2 ->
                            if (sideIdx == viewModel.currentSideIndex) viewModel.addBbox(x1, y1, x2, y2)
                        },
                        modifier = Modifier.fillMaxSize(),
                    )

                    // Bbox count overlay
                    val countText = stringResource(R.string.carousel_boxes_count, side.bboxes.size)
                    val unassignedText = if (side.hasUnassigned) " · " + stringResource(R.string.carousel_boxes_unassigned, side.unassignedBboxCount) else ""
                    val linkedText = if (linkMap.isNotEmpty()) " · " + stringResource(R.string.carousel_boxes_linked, linkMap.size) else ""
                    Surface(
                        modifier = Modifier.align(Alignment.TopStart).padding(8.dp),
                        shape = RoundedCornerShape(4.dp),
                        color = Color.Black.copy(alpha = 0.7f),
                    ) {
                        Text(
                            countText + unassignedText + linkedText,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.labelMedium,
                            color = if (side.hasUnassigned) PalmColors.Warning else Color.White,
                        )
                    }

                    // Link armed indicator
                    if (viewModel.linkArmed) {
                        Surface(
                            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 100.dp),
                            shape = RoundedCornerShape(8.dp),
                            color = PalmColors.LinkHighlight.copy(alpha = 0.92f),
                        ) {
                            Text(
                                stringResource(R.string.carousel_link_hint),
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                                color = PalmColors.OnLinkHighlight,
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
                        val raw = pagerState.currentPage % sidesCount
                        val sideIdx = if (reverseSwipe) sidesCount - 1 - raw else raw
                        val isCurrent = sideIdx == i
                        Box(
                            modifier = Modifier
                                .clickable {
                                    coroutineScope.launch {
                                        // In both modes dot i = side i.  Normal: page for side i is
                                        // cur + (i - raw).  Reversed: virtual page for side i is
                                        // cur + ((sidesCount-1-i) - raw).
                                        val cur = pagerState.currentPage
                                        val curRaw = cur % sidesCount
                                        val target = if (loop) {
                                            if (reverseSwipe) cur + ((sidesCount - 1 - i) - curRaw)
                                            else               cur + (i - curRaw)
                                        } else i
                                        pagerState.animateScrollToPage(target)
                                    }
                                }
                                .padding(3.dp)
                                .size(if (isCurrent) 8.dp else 6.dp)
                                .clip(CircleShape)
                                .background(
                                    if (isCurrent) PalmColors.Accent
                                    else Color.White.copy(alpha = 0.4f)
                                ),
                        )
                    }
                }
            }

            // Auto-save confirmation pulse (brief, non-interactive).
            if (showSaved) {
                Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.BottomCenter) {
                    Surface(
                        modifier = Modifier.padding(bottom = 16.dp),
                        shape = RoundedCornerShape(20.dp),
                        color = PalmColors.Accent,
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Default.Check, null, tint = PalmColors.OnAccent, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(
                                stringResource(R.string.carousel_saved),
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                                color = PalmColors.OnAccent,
                            )
                        }
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
    reverseSwipe: Boolean,
    linkArmed: Boolean,
    isSave: Boolean,
    editMode: Boolean,
    editTool: CanvasTool,
    onToggleDraw: () -> Unit,
    onClassChange: (String, AnnotationClass) -> Unit,
    onDelete: (String) -> Unit,
    onToggleBoxes: () -> Unit,
    onToggleSwipe: () -> Unit,
    onArmLink: () -> Unit,
    onCancelLink: () -> Unit,
    onSaveExit: () -> Unit,
    onNextTree: () -> Unit,
) {
    Surface(tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth()) {
        // navigationBarsPadding lifts the whole bar above the system nav bar / gesture pill
        // — on phones the action buttons were drawn UNDER it and got clipped (the operator
        // couldn't reach "Next Tree" / the draw-box toggle to add a box manually).
        Column(
            Modifier
                .navigationBarsPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val hasSelection = selectedBboxId != null

            // Row 1 — class buttons share the full width (weight) so all four always fit,
            // even on a narrow phone, instead of overflowing off the right edge.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for (cls in AnnotationClass.assignableEntries) {
                    val isSelected = selectedBboxId?.let { id ->
                        session?.sides?.getOrNull(currentSideIndex)?.bboxes?.find { it.id == id }?.classId == cls.id
                    } == true
                    // Dim when no box is selected (tapping a class is a no-op then). When a box
                    // IS selected, show the full class colour and ring the box's current class.
                    val container = if (hasSelection) cls.composeColor else cls.composeColor.copy(alpha = 0.4f)
                    // Pick black/white by the class colour's luminance so the label always reads
                    // (white on amber B3 failed contrast before).
                    val labelColor = if (cls.composeColor.luminance() > 0.5f) Color.Black else Color.White
                    Surface(
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(enabled = hasSelection) {
                                selectedBboxId?.let { onClassChange(it, cls) }
                            },
                        color = container,
                        shape = RoundedCornerShape(8.dp),
                        border = if (isSelected) ButtonDefaults.outlinedButtonBorder(enabled = true) else null,
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                cls.displayName,
                                color = labelColor,
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }
            }

            // Row 2 — tools (delete · link · draw-box) on the left, visibility on the right.
            // Each is a 48dp target and the row always fits within a phone's width.
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = { selectedBboxId?.let { onDelete(it) } },
                    enabled = selectedBboxId != null,
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(Icons.Default.Delete, stringResource(R.string.action_delete), tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(26.dp))
                }

                if (linkArmed) {
                    TextButton(onClick = onCancelLink, modifier = Modifier.height(48.dp)) {
                        Text(stringResource(R.string.carousel_cancel_link), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.error)
                    }
                } else {
                    IconButton(
                        onClick = onArmLink,
                        enabled = selectedBboxId != null,
                        modifier = Modifier.size(48.dp),
                    ) {
                        Icon(Icons.Default.Link, stringResource(R.string.cd_link), modifier = Modifier.size(26.dp))
                    }
                }

                // Draw-new-box toggle — only meaningful while editing geometry.
                if (editMode) {
                    IconButton(onClick = onToggleDraw, modifier = Modifier.size(48.dp)) {
                        Icon(
                            Icons.Default.Crop,
                            stringResource(R.string.cd_draw_box),
                            tint = if (editTool == CanvasTool.DRAW) MaterialTheme.colorScheme.primary
                                   else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(26.dp),
                        )
                    }
                }

                Spacer(Modifier.weight(1f))

                // Flip swipe direction / side order (per-screen, not persisted). Mirrors the
                // dedup direction toggle but visual-only here. Sits just left of the eye toggle.
                IconButton(onClick = onToggleSwipe, modifier = Modifier.size(48.dp)) {
                    Icon(
                        if (reverseSwipe) Icons.Default.RotateLeft else Icons.Default.RotateRight,
                        contentDescription = stringResource(
                            if (reverseSwipe) R.string.cd_capture_counter_clockwise else R.string.cd_capture_clockwise
                        ),
                        modifier = Modifier.size(26.dp),
                    )
                }

                IconButton(onClick = onToggleBoxes, modifier = Modifier.size(48.dp)) {
                    Icon(
                        if (showBoxes) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                        stringResource(R.string.cd_toggle_boxes),
                        modifier = Modifier.size(26.dp),
                    )
                }
            }

            // Row 3 — primary actions: full-width split buttons.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedButton(
                    onClick = onSaveExit,
                    modifier = Modifier.weight(1f).height(48.dp),
                ) { Text(stringResource(R.string.carousel_save_exit), style = MaterialTheme.typography.labelLarge) }
                Button(
                    onClick = onNextTree,
                    modifier = Modifier.weight(1f).height(48.dp),
                ) { Text(stringResource(R.string.carousel_next_tree), style = MaterialTheme.typography.labelLarge) }
            }
        }
    }
}
