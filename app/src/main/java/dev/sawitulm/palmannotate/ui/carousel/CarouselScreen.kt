package dev.sawitulm.palmannotate.ui.carousel

import android.content.ContentResolver
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.sawitulm.palmannotate.R
import dev.sawitulm.palmannotate.ui.theme.PalmColors
import dev.sawitulm.palmannotate.data.detection.OnnxDetector
import dev.sawitulm.palmannotate.data.export.ExportManager
import dev.sawitulm.palmannotate.data.storage.ExportFolderRepository
import dev.sawitulm.palmannotate.data.storage.SaveResult
import dev.sawitulm.palmannotate.data.storage.SessionRepository
import dev.sawitulm.palmannotate.domain.model.*
import dev.sawitulm.palmannotate.domain.usecase.SessionUseCases
import dev.sawitulm.palmannotate.domain.usecase.WeightDatasetPolicy
import dev.sawitulm.palmannotate.domain.results.ResultsComputer
import dev.sawitulm.palmannotate.domain.util.OperationQueue
import dev.sawitulm.palmannotate.ui.common.AnnotationCanvas
import dev.sawitulm.palmannotate.ui.common.CanvasTool
import dev.sawitulm.palmannotate.ui.common.LocalToasts
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject

internal fun needsPagerSettle(canvasGestureActive: Boolean, pageOffsetFraction: Float): Boolean =
    !canvasGestureActive && pageOffsetFraction != 0f

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
        private set
    var saveErrorMessage by mutableStateOf<String?>(null)
        private set
    var linkArmed by mutableStateOf(false)
    /** Pending link source — mirrors dedup's pendingBboxId/pendingSide pattern.
     *  Set when user selects a box and taps Link; cleared on link creation or cancel. */
    var pendingLinkBboxId by mutableStateOf<String?>(null)
        private set
    var pendingLinkSide by mutableIntStateOf(-1)
        private set
    var isDetecting by mutableStateOf(false)
        private set
    /** Set by [completeLink] when the link discarded values the target bunch already had. */
    var linkReplacedMeasurements by mutableStateOf(false)
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
    private var editGeneration = 0L

    val currentSide: TreeSide?
        get() = session?.sides?.getOrNull(currentSideIndex)

    val selectedBbox: Bbox?
        get() = currentSide?.bboxes?.firstOrNull { it.id == selectedBboxId }

    val totalSides: Int get() = session?.sides?.size ?: 0

    /** Canvas tool for the current mode: read-only in REVIEW, the chosen tool in EDIT. */
    val canvasTool: CanvasTool
        get() = if (mode == CarouselMode.REVIEW) CanvasTool.VIEW else editTool

    fun load(treeKey: String) {
        // The activity can be recreated (dark-mode switch, display or font-size change) while
        // this ViewModel survives. Reloading then replaced the in-memory tree with the stored
        // one and silently dropped a box that was drawn but not yet saved. With nothing unsaved
        // the reload still runs, so changes made on the dedup screen are picked up as before.
        // A revision conflict is the exception: only a reload resolves it.
        if (dirty && !saveConflicted && session?.sessionId == treeKey) return
        saveConflicted = false
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
            // Reset to SELECT so re-entering Edit (or swiping straight to another side
            // while already in Edit) never silently resumes a stale DRAW sub-tool — that
            // made the "new box" button's first tap appear to do nothing (it flipped
            // DRAW→SELECT instead of arming DRAW).
            editTool = CanvasTool.SELECT
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
        // Same staleness fix as selectSide(): never resume a stale DRAW sub-tool from a
        // previous Edit session.
        editTool = CanvasTool.SELECT
    }

    /** Serializes all Carousel saves so revision tokens are applied in commit order. */
    private val autoSaveMutex = kotlinx.coroutines.sync.Mutex()

    private var saveConflicted = false

    private fun markDirty() {
        dirty = true
        editGeneration++
    }

    private suspend fun persistLatest(markComplete: Boolean): SaveResult {
        while (true) {
            val snapshot = withContext(Dispatchers.Main.immediate) { session }
                ?: return SaveResult.Failure("Tree is not loaded")
            val generation = withContext(Dispatchers.Main.immediate) { editGeneration }
            val result = try {
                val safTreeUri = exportFolder.folderUri.first()
                if (markComplete) {
                    repo.saveOutputJson(snapshot, safTreeUri, awaitSafVerification = false)
                } else {
                    repo.saveSession(snapshot, safTreeUri)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                SaveResult.Failure(error.message ?: "Save failed", error)
            }
            if (result !is SaveResult.Success) return result

            val stable = withContext(Dispatchers.Main.immediate) {
                session = session?.copy(revision = result.revision)
                val unchanged = editGeneration == generation
                if (unchanged) dirty = false
                unchanged
            }
            if (stable) return result
        }
    }

    private suspend fun reportSaveFailure(result: SaveResult) {
        val message = when (result) {
            is SaveResult.Success -> return
            is SaveResult.Conflict ->
                "Tree changed while saving (current revision r${result.actualRevision}). Reopen it before editing again."
            is SaveResult.Failure -> result.message
        }
        withContext(Dispatchers.Main.immediate) {
            dirty = true
            if (result is SaveResult.Conflict) saveConflicted = true
            saveErrorMessage = message
        }
    }

    fun consumeSaveError() {
        saveErrorMessage = null
    }

    fun consumeLinkReplacedNotice() {
        linkReplacedMeasurements = false
    }

    /**
     * Silent persistence (no busy overlay). A failed/conflicting save stays dirty and never emits
     * the saved pulse; a successful save publishes the returned revision into the live snapshot.
     */
    fun autoSave() {
        if (!dirty || session == null) return
        viewModelScope.launch {
            autoSaveMutex.withLock {
                if (!dirty) return@withLock
                when (val result = persistLatest(markComplete = false)) {
                    is SaveResult.Success -> savedTick = System.currentTimeMillis()
                    else -> reportSaveFailure(result)
                }
            }
        }
    }

    /** One tap from an empty photo to drawing: enter Edit if needed and arm the draw tool. */
    fun startDrawingBox() {
        if (mode == CarouselMode.REVIEW) {
            autoSave()
            mode = CarouselMode.EDIT
        }
        editTool = CanvasTool.DRAW
    }

    /** EDIT-mode sub-tool: flip between move/resize (SELECT) and draw-new-box (DRAW). */
    fun toggleDrawTool() {
        editTool = if (editTool == CanvasTool.DRAW) CanvasTool.SELECT else CanvasTool.DRAW
    }

    fun changeBboxClass(bboxId: String, newClass: AnnotationClass) {
        val s = session ?: return
        session = SessionUseCases.setBboxClass(s, currentSideIndex, bboxId, newClass, propagate = true)
        markDirty()
    }

    fun changeBboxMeasurements(bboxId: String, measurements: BunchMeasurements) {
        val s = session ?: return
        session = SessionUseCases.setBboxMeasurements(s, currentSideIndex, bboxId, measurements)
        markDirty()
        // A weighed bunch cannot be re-measured once the harvest moves on, so it must not sit
        // in memory until the operator happens to swipe. This is one deliberate tap, not a
        // drag, so it cannot spam the save path (DB + labels ~15ms; SAF stays off it).
        autoSave()
    }

    fun deleteBbox(bboxId: String) {
        val s = session ?: return
        session = SessionUseCases.deleteBbox(s, currentSideIndex, bboxId)
        selectedBboxId = null
        markDirty()
    }

    fun addBbox(x1: Float, y1: Float, x2: Float, y2: Float) {
        val s = session ?: return
        val side = s.sides.getOrNull(currentSideIndex) ?: return
        // Auto-select the freshly drawn box so the operator can immediately tap a class
        // (parity with the old annotation editor).
        val newId = Bbox.nextId(side.bboxes, "b")
        session = SessionUseCases.addBbox(s, currentSideIndex, x1, y1, x2, y2)
        selectedBboxId = newId
        markDirty()
    }

    fun updateBbox(bboxId: String, x1: Float, y1: Float, x2: Float, y2: Float) {
        val s = session ?: return
        session = SessionUseCases.updateBbox(s, currentSideIndex, bboxId, x1, y1, x2, y2)
        markDirty()
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

    fun unlinkSelectedBbox() {
        val s = session ?: return
        val id = selectedBboxId ?: return
        session = SessionUseCases.removeLinksForBbox(s, currentSideIndex, id)
        markDirty()
        autoSave()
    }

    /** Export the already-saved sample through Android's document picker. */
    fun exportCsv(uri: Uri, resolver: ContentResolver, onExported: () -> Unit) {
        val snapshot = session ?: return
        if (isSaving || isDetecting) return
        isSaving = true
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val csv = ExportManager.generateCsv(snapshot, ResultsComputer.compute(snapshot))
                    val stream = resolver.openOutputStream(uri, "wt")
                        ?: error("Could not open the CSV destination")
                    stream.bufferedWriter(Charsets.UTF_8).use { it.write(csv) }
                }
                onExported()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                saveErrorMessage = error.message ?: "CSV export failed"
            } finally {
                isSaving = false
            }
        }
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
        // A link makes one cluster carry one set of values, so the source's win. When the
        // target already held DIFFERENT values, that is a weight the operator typed and is
        // now gone — silently, since the panel simply re-renders with the new number.
        val targetBefore = s.sides.firstOrNull { it.sideIndex == tgtSide }
            ?.bboxes?.firstOrNull { it.id == targetBboxId }?.measurements?.normalized()
        // Note: srcId == targetBboxId is OK — IDs like "b0" repeat across sides.
        session = SessionUseCases.addManualLink(s, srcSide, srcId, tgtSide, targetBboxId)
        val targetAfter = session?.sides?.firstOrNull { it.sideIndex == tgtSide }
            ?.bboxes?.firstOrNull { it.id == targetBboxId }?.measurements?.normalized()
        linkReplacedMeasurements =
            targetBefore != null && targetBefore.hasAnyValue && targetAfter != targetBefore
        linkArmed = false
        pendingLinkBboxId = null
        pendingLinkSide = -1
        // Keep the just-linked box selected so the class bar stays enabled — otherwise the box
        // is deselected after linking and tapping a class is a no-op ("can't change the class
        // after linking"). Changing it now propagates to the whole cluster and auto-saves.
        selectedBboxId = targetBboxId
        markDirty()
        // Same reason as changeBboxMeasurements: a link decides which appearances share one
        // weight, and re-deriving it later means re-identifying the bunch across two photos.
        autoSave()
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
        fun key(side: Int, b: String) = "$side\u0000$b"
        s.confirmedLinks.forEach { l -> union(key(l.sideA, l.bboxIdA), key(l.sideB, l.bboxIdB)) }
        // Stable numbering: number each cluster by the order its FIRST link appears in
        // confirmedLinks (insertion order). The old "sort by union-find root" scheme reshuffled
        // every number whenever a new link changed which node became the component root — so
        // linking another bunch made the existing badges jump around (1→3, etc.).
        val groupNum = HashMap<String, Int>()
        var nextGroup = 1
        s.confirmedLinks.forEach { l ->
            val r = root(key(l.sideA, l.bboxIdA))
            if (r !in groupNum) groupNum[r] = nextGroup++
        }
        val result = HashMap<String, Int>()
        parent.keys.forEach { k ->
            val sep = k.indexOf('\u0000')
            val side = k.substring(0, sep).toInt()
            val boxId = k.substring(sep + 1)
            if (side == sideIndex) result[boxId] = groupNum.getValue(root(k))
        }
        return result
    }

    fun save() {
        if (session == null || isSaving) return
        isSaving = true
        opq.enqueue("save-carousel") {
            try {
                val result = autoSaveMutex.withLock { persistLatest(markComplete = false) }
                if (result !is SaveResult.Success) reportSaveFailure(result)
            } finally {
                withContext(Dispatchers.Main.immediate) { isSaving = false }
            }
        }
    }

    fun saveAndExit(onDone: () -> Unit) = persistAndExit(markComplete = true, onDone)

    fun saveDraftAndExit(onDone: () -> Unit) = persistAndExit(markComplete = false, onDone)

    // The screen's own `isWeightDataset` belongs to the last composition. For one frame after
    // the tree loads it still says "not bunch weight", and a tap in that frame used to take the
    // multiside exit: the sample was marked complete and the next capture opened with no box and
    // no weight. These two decide from the session as it is at the tap.
    private val isWeightSession: Boolean get() = session?.datasetType == DatasetType.BUNCH_WEIGHT

    fun exitTree(onDone: () -> Unit) =
        if (isWeightSession) saveDraftAndExit(onDone) else saveAndExit(onDone)

    fun exitToNextTree(onDone: () -> Unit) =
        if (isWeightSession) completeWeightSample(onDone) else saveAndExit(onDone)

    fun completeWeightSample(onDone: () -> Unit) {
        val current = session ?: return
        WeightDatasetPolicy.completionError(current)?.let { error ->
            saveErrorMessage = error
            return
        }
        persistAndExit(markComplete = true, onDone)
    }

    private fun persistAndExit(markComplete: Boolean, onDone: () -> Unit) {
        if (session == null) {
            onDone()
            return
        }
        if (isSaving) return
        if (isDetecting) {
            saveErrorMessage = "Wait for detection to finish before leaving this tree."
            return
        }
        isSaving = true
        opq.enqueue("save-carousel") {
            try {
                when (val result = autoSaveMutex.withLock { persistLatest(markComplete) }) {
                    is SaveResult.Success -> withContext(Dispatchers.Main.immediate) { onDone() }
                    else -> reportSaveFailure(result)
                }
            } finally {
                withContext(Dispatchers.Main.immediate) { isSaving = false }
            }
        }
    }

    /** Save before opening another editor/viewer, without falsely marking the tree complete. */
    fun saveAndNavigate(onDone: () -> Unit) {
        if (session == null || isSaving || isDetecting) return
        isSaving = true
        opq.enqueue("save-carousel") {
            try {
                when (val result = autoSaveMutex.withLock { persistLatest(markComplete = false) }) {
                    is SaveResult.Success -> withContext(Dispatchers.Main.immediate) { onDone() }
                    else -> reportSaveFailure(result)
                }
            } finally {
                withContext(Dispatchers.Main.immediate) { isSaving = false }
            }
        }
    }

    fun detectCurrentSide() {
        val side = currentSide ?: return
        val uri = side.imageUri ?: return
        val sideIndex = currentSideIndex   // H-08: capture BEFORE the suspending detect(); the user
                                           // can swipe to another side while the model runs (seconds).
        viewModelScope.launch {
            isDetecting = true
            try {
                val detections = detector.detect(uri)
                val s = session ?: return@launch
                // H-08: merge onto the side we actually ran detection on (by captured index) using its
                // LATEST state — never onto whatever side is showing now. If the side list changed
                // under us (image no longer matches), abort rather than corrupt the wrong side.
                val targetSide = s.sides.getOrNull(sideIndex) ?: return@launch
                if (targetSide.imageUri != uri) return@launch
                // Never-reused ids: each new box derives its id from the running
                // box list so a prior delete can't make a detect id collide.
                val running = targetSide.bboxes.toMutableList()
                val newBoxes = detections.filter { d ->
                    val overlaps = targetSide.bboxes.any { existing ->
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
                val baseline = if (targetSide.originalBboxes.isEmpty()) newBoxes else targetSide.originalBboxes
                val updatedSides = s.sides.toMutableList()
                updatedSides[sideIndex] = targetSide.copy(
                    bboxes = targetSide.bboxes + newBoxes,
                    originalBboxes = baseline,
                )
                session = s.copy(sides = updatedSides)
                if (newBoxes.isNotEmpty()) markDirty()
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
    val isWeightDataset = session?.datasetType == DatasetType.BUNCH_WEIGHT
    val totalSides = viewModel.totalSides
    val sidesCount = totalSides.coerceAtLeast(1)
    // Infinite/looping pager: a huge virtual page count (only when there is more than one
    // side).  Page-to-side mapping is always: side = page % sidesCount — the counter
    // always counts UP (1→2→3→4).  reverseLayout=true flips only the swipe direction
    // (right-to-advance for CCW) without changing the side numbering.
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

    // Sync VM ↔ pager: map virtual page to real side index (always raw = page % sidesCount).
    LaunchedEffect(pagerState.currentPage) {
        val side = pagerState.currentPage % sidesCount
        if (side != viewModel.currentSideIndex) viewModel.selectSide(side)
    }
    // When session loads (sidesCount > 1), jump to the center of the infinite pager
    // so the user can loop in both directions from the start.
    LaunchedEffect(sidesCount) {
        if (sidesCount <= 1) return@LaunchedEffect
        val initial = (Int.MAX_VALUE / 2).let { it - it % sidesCount }
        pagerState.scrollToPage(initial)
    }

    var showMoreMenu by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()

    // True while a touch on the CURRENT page's canvas is actively grabbing/drawing a box —
    // disables the Pager's own swipe so it can't win the slop race against a box-drag and
    // hijack the gesture into a page-swipe instead of moving the box.
    var isEditingBox by remember { mutableStateOf(false) }
    // Defensive reset on every mode change AND side change so a page/gesture torn down
    // mid-touch can never leave the Pager permanently swipe-disabled (the regression where
    // swiping died after editing). Combined with the mode==EDIT guard on userScrollEnabled
    // below, leaving Edit always re-enables swiping.
    LaunchedEffect(viewModel.mode, viewModel.currentSideIndex) { isEditingBox = false }
    LaunchedEffect(isEditingBox) {
        if (needsPagerSettle(isEditingBox, pagerState.currentPageOffsetFraction)) {
            pagerState.scrollToPage(pagerState.currentPage)
        }
    }

    // While no session exists there is nothing to save, so let NavHost handle Back normally.
    BackHandler(enabled = session != null) {
        viewModel.exitTree { onBack() }
    }

    val toasts = LocalToasts.current
    val resolver = LocalContext.current.contentResolver
    val csvExported = stringResource(R.string.results_export_done, "CSV")
    val csvLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) viewModel.exportCsv(uri, resolver) { toasts.info(csvExported) }
    }
    LaunchedEffect(viewModel.saveErrorMessage) {
        viewModel.saveErrorMessage?.let {
            toasts.error(it)
            viewModel.consumeSaveError()
        }
    }

    val linkReplacedText = stringResource(R.string.weight_link_replaced)
    LaunchedEffect(viewModel.linkReplacedMeasurements) {
        if (viewModel.linkReplacedMeasurements) {
            toasts.info(linkReplacedText)
            viewModel.consumeLinkReplacedNotice()
        }
    }

    // Brief "Tersimpan ✓" pulse whenever an auto-save completes.
    var showSaved by remember { mutableStateOf(false) }
    LaunchedEffect(viewModel.savedTick) {
        if (viewModel.savedTick > 0L) {
            showSaved = true
            kotlinx.coroutines.delay(1300)
            showSaved = false
        }
    }

    val weightMeasurementOpen = isWeightDataset && viewModel.selectedBbox != null

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            session?.treeName ?: stringResource(R.string.carousel_title_fallback),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            "${(pagerState.currentPage % sidesCount) + 1} / $totalSides",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    // Save-then-leave so edits are never lost by tapping Back.
                    IconButton(
                        onClick = {
                            viewModel.exitTree { onBack() }
                        },
                        enabled = !viewModel.isSaving && !viewModel.isDetecting,
                    ) {
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
                    if (isWeightDataset) {
                        IconButton(
                            enabled = !viewModel.isSaving && !viewModel.isDetecting,
                            onClick = {
                                viewModel.saveAndNavigate {
                                    csvLauncher.launch("${session?.treeName}_result.csv")
                                }
                            },
                        ) {
                            Icon(Icons.Default.TableChart, stringResource(R.string.weight_export_csv))
                        }
                    } else {
                        IconButton(onClick = { showMoreMenu = true }) {
                            Icon(Icons.Default.MoreVert, stringResource(R.string.cd_more))
                        }
                    }
                    DropdownMenu(expanded = showMoreMenu && !isWeightDataset, onDismissRequest = { showMoreMenu = false }) {
                        // H-07: these are sub-screens, not completion actions. Persist edits first,
                        // but do not generate final output / mark the tree complete.
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.menu_deduplication)) },
                            onClick = { showMoreMenu = false; viewModel.saveAndNavigate { onDedup() } },
                            leadingIcon = { Icon(Icons.Default.Link, null) },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.menu_results)) },
                            onClick = { showMoreMenu = false; viewModel.saveAndNavigate { onResults() } },
                            leadingIcon = { Icon(Icons.Default.Assessment, null) },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.menu_depth_viewer)) },
                            onClick = { showMoreMenu = false; viewModel.saveAndNavigate { onDepth() } },
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
                isSaving = viewModel.isSaving || viewModel.isDetecting,
                nextTreeEnabled = viewModel.runId != null,
                editMode = viewModel.mode == CarouselMode.EDIT,
                editTool = viewModel.editTool,
                showClassPicker = !isWeightDataset,
                showPrimaryActions = !weightMeasurementOpen,
                showSwipeDirection = !isWeightDataset,
                linkEnabled = totalSides > 1,
                onToggleDraw = { viewModel.toggleDrawTool() },
                onClassChange = { id, cls -> viewModel.changeBboxClass(id, cls) },
                onDelete = { viewModel.deleteBbox(it) },
                onToggleBoxes = { viewModel.toggleBoxes() },
                onToggleSwipe = { viewModel.toggleSwipeDirection() },
                onArmLink = {
                    viewModel.armLink()
                    if (isWeightDataset && totalSides == 2) {
                        coroutineScope.launch {
                            val currentPage = pagerState.currentPage
                            val currentSide = currentPage % sidesCount
                            val targetSide = if (currentSide == 0) 1 else 0
                            val targetPage = if (loop) currentPage + targetSide - currentSide else targetSide
                            pagerState.animateScrollToPage(targetPage)
                        }
                    }
                },
                onCancelLink = { viewModel.cancelLink() },
                onUnlink = if (isWeightDataset) ({ viewModel.unlinkSelectedBbox() }) else null,
                saveExitLabel = stringResource(
                    if (isWeightDataset) R.string.weight_save_draft_exit else R.string.carousel_save_exit,
                ),
                nextTreeLabel = stringResource(
                    if (isWeightDataset) R.string.weight_save_next else R.string.carousel_next_tree,
                ),
                onSaveExit = {
                    viewModel.exitTree { onBack() }
                },
                onNextTree = {
                    viewModel.runId?.let { rid ->
                        viewModel.exitToNextTree { onNextTree(rid) }
                    }
                },
            )
        },
    ) { padding ->
        if (session == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            // Window-relative bottom of the content area. Measured on the CONTAINER, not on the
            // panel: the panel is what the keyboard correction moves, so measuring the panel
            // would feed its own new position back into the calculation.
            var containerBottomPx by remember { mutableIntStateOf(0) }
            BoxWithConstraints(
                Modifier.fillMaxSize().padding(padding).onGloballyPositioned { coords ->
                    containerBottomPx = (coords.positionInWindow().y + coords.size.height).toInt()
                },
            ) {
            val selectedBbox = viewModel.selectedBbox
            val showMeasurements = isWeightDataset && selectedBbox != null
            val expandedInspector = maxWidth >= 720.dp
            val inspectorWidth = if (showMeasurements && expandedInspector) 340.dp else 0.dp
            // WindowInsets.ime measures from the WINDOW bottom, but this content area already
            // ends above the bottom bar. Subtract that gap so only the part of the keyboard that
            // actually overlaps the content is corrected for.
            val density = LocalDensity.current
            val rootHeightPx = LocalView.current.rootView.height
            val imeBottomPx = WindowInsets.ime.getBottom(density)
            val imeOverlapPx = if (rootHeightPx > 0 && containerBottomPx > 0) {
                (imeBottomPx - (rootHeightPx - containerBottomPx).coerceAtLeast(0)).coerceAtLeast(0)
            } else {
                0
            }
            val imeOverlap = with(density) { imeOverlapPx.toDp() }
            // Hoisted so the sheet can size itself to what is actually shown; reset per bbox.
            val measurementKey = "${viewModel.currentSideIndex}:${selectedBbox?.id}"
            var optionalDetailsOpen by remember(measurementKey) { mutableStateOf(false) }
            // Compact layout puts the panel over the bottom of the photo. Inset the pager by
            // the same amount so the canvas re-fits into what is actually visible, instead of
            // drawing the bunch under the sheet. Collapsed the sheet only carries the weight
            // field, the optional toggle and Apply, so it gives the rest of the height back to
            // the photo and only grows when the optional fields are open.
            val sheetHeight = (if (optionalDetailsOpen) (maxHeight * 0.68f).coerceAtMost(380.dp)
            else (maxHeight * 0.5f).coerceAtMost(285.dp))
                // The sheet is lifted clear of the keyboard, so it has to fit in what is left.
                .coerceAtMost((maxHeight - imeOverlap).coerceAtLeast(180.dp))
            val inspectorHeight = if (showMeasurements && !expandedInspector) sheetHeight else 0.dp
            HorizontalPager(
                state = pagerState,
                // Bunch weight has no capture direction and hides the swipe-direction toggle
                // (showSwipeDirection), so a reversed layout would strand it: tapping Photo 2
                // slid the page backwards with no way to flip it. Multiside keeps the CCW default.
                reverseLayout = !isWeightDataset && !viewModel.reverseSwipe,
                flingBehavior = pagerFling,
                // Only suppress swipe while actually grabbing a box IN edit mode. In Review
                // (and the moment Edit closes) swiping is always enabled, so a stuck flag can
                // never strand the pager.
                userScrollEnabled = !(isEditingBox && viewModel.mode == CarouselMode.EDIT),
                modifier = Modifier.fillMaxSize().padding(end = inspectorWidth, bottom = inspectorHeight),
            ) { page ->
                val sideIdx = page % sidesCount
                val side = session!!.sides.getOrNull(sideIdx) ?: return@HorizontalPager
                // bboxId → link-group number for this side (same number on the matching
                // bunch on the adjacent side), so links are visible at a glance.
                val numbers = if (isWeightDataset) WeightDatasetPolicy.bunchNumbers(session!!, sideIdx)
                    else viewModel.linkGroupFor(sideIdx)
                val linkedIds = session!!.confirmedLinks.mapNotNull {
                    when (sideIdx) {
                        it.sideA -> it.bboxIdA
                        it.sideB -> it.bboxIdB
                        else -> null
                    }
                }.toSet()
                val linkMap = if (isWeightDataset) numbers.filterKeys { it in linkedIds } else numbers

                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    AnnotationCanvas(
                        imageUriString = side.imageUri?.toString(),
                        bboxes = side.bboxes,
                        selectedBboxId = if (sideIdx == viewModel.currentSideIndex) viewModel.selectedBboxId else null,
                        imageWidth = side.imageWidth.coerceAtLeast(1),
                        imageHeight = side.imageHeight.coerceAtLeast(1),
                        tool = viewModel.canvasTool,
                        showBoxes = viewModel.showBoxes,
                        showClassLabels = !isWeightDataset,
                        boxColorOverride = if (isWeightDataset) PalmColors.Accent else null,
                        linkedBoxes = linkMap,
                        boxNumbers = numbers,
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
                        onActiveEditChange = { active ->
                            // Only the current page's gesture state should drive the Pager —
                            // adjacent pages stay composed (pager prefetch) but are not visible.
                            if (sideIdx == viewModel.currentSideIndex) isEditingBox = active
                        },
                        modifier = Modifier.fillMaxSize(),
                    )

                    // Bbox count overlay
                    val countText = stringResource(R.string.carousel_boxes_count, side.bboxes.size)
                    val unassignedText = if (!isWeightDataset && side.hasUnassigned) " · " + stringResource(R.string.carousel_boxes_unassigned, side.unassignedBboxCount) else ""
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
                            color = if (!isWeightDataset && side.hasUnassigned) PalmColors.Warning else Color.White,
                        )
                    }

                    // A bunch-weight photo with no box has nothing to weigh yet, and the way to
                    // add one (switch Review to Edit, then pick the draw tool) is two unlabeled
                    // steps. Offer it as one labeled action until the first box exists.
                    if (isWeightDataset && side.bboxes.isEmpty() &&
                        sideIdx == viewModel.currentSideIndex &&
                        viewModel.canvasTool != CanvasTool.DRAW && !viewModel.linkArmed
                    ) {
                        Button(
                            onClick = { viewModel.startDrawingBox() },
                            enabled = !viewModel.isSaving && !viewModel.isDetecting,
                            modifier = Modifier.align(Alignment.Center).heightIn(min = 56.dp),
                        ) {
                            Icon(Icons.Default.Add, null, Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.weight_draw_bunch))
                        }
                    }

                    // Link armed indicator
                    if (viewModel.linkArmed) {
                        Surface(
                            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 100.dp),
                            shape = RoundedCornerShape(8.dp),
                            color = PalmColors.LinkHighlight.copy(alpha = 0.92f),
                        ) {
                            Text(
                                stringResource(
                                    if (isWeightDataset) R.string.carousel_link_hint_weight
                                    else R.string.carousel_link_hint,
                                ),
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                                color = PalmColors.OnLinkHighlight,
                            )
                        }
                    }
                }
            }

            // Explicit photo buttons stay usable when Edit consumes horizontal canvas drags.
            if (isWeightDataset && totalSides > 1) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(end = inspectorWidth).padding(top = 40.dp),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    for (i in 0 until totalSides) {
                        val isCurrent = pagerState.currentPage % sidesCount == i
                        FilledTonalButton(
                            onClick = {
                                coroutineScope.launch {
                                    val current = pagerState.currentPage
                                    pagerState.animateScrollToPage(current + i - current % sidesCount)
                                }
                            },
                            enabled = !viewModel.isSaving && !pagerState.isScrollInProgress,
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
                                contentColor = if (isCurrent) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                            ),
                            modifier = Modifier.padding(horizontal = 4.dp).heightIn(min = 48.dp),
                        ) { Text(stringResource(R.string.weight_photo_number, i + 1)) }
                    }
                }
            } else if (totalSides > 1) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(end = inspectorWidth, bottom = inspectorHeight)
                        .padding(top = 56.dp)
                        .padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    for (i in 0 until totalSides) {
                        val raw = pagerState.currentPage % sidesCount
                        val isCurrent = raw == i
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clickable {
                                    coroutineScope.launch {
                                        // Dot i = side i (page = side via page % sidesCount). Jump to
                                        // the page in the current virtual cycle that shows side i.
                                        val cur = pagerState.currentPage
                                        val curRaw = cur % sidesCount
                                        val target = if (loop) cur + (i - curRaw) else i
                                        pagerState.animateScrollToPage(target)
                                    }
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Box(
                                Modifier
                                    .size(if (isCurrent) 8.dp else 6.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (isCurrent) PalmColors.Accent
                                        else Color.White.copy(alpha = 0.4f),
                                    ),
                            )
                        }
                    }
                }
            }

            // Auto-save confirmation pulse (brief, non-interactive). The bottom inset matters:
            // the pulse sits at the bottom of the content area, which is exactly where the
            // measurement sheet is, and the sheet is composed after it. Without the inset,
            // "Apply to bunch" saved silently under the sheet and operators could not tell
            // whether their tap had registered at all.
            if (showSaved) {
                Box(
                    Modifier.fillMaxSize().padding(end = inspectorWidth, bottom = inspectorHeight),
                    contentAlignment = Alignment.BottomCenter,
                ) {
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

            if (showMeasurements && selectedBbox != null) {
                BunchMeasurementPanel(
                    bbox = selectedBbox,
                    bunchNumber = WeightDatasetPolicy.bunchNumbers(session!!, viewModel.currentSideIndex).getValue(selectedBbox.id),
                    bboxKey = measurementKey,
                    showOptional = optionalDetailsOpen,
                    imeVisible = imeOverlapPx > 0,
                    // The full-height side inspector cannot move, so it pads its own content.
                    // The compact sheet is lifted instead (below) and must not pad twice.
                    imeInnerPadding = if (expandedInspector) imeOverlap else 0.dp,
                    onToggleOptional = { optionalDetailsOpen = !optionalDetailsOpen },
                    onApply = { viewModel.changeBboxMeasurements(selectedBbox.id, it) },
                    onClose = { viewModel.selectBbox(null) },
                    modifier = if (expandedInspector) {
                        Modifier.align(Alignment.CenterEnd).width(340.dp).fillMaxHeight()
                    } else {
                        // A sheet at 88% left the photo as a black strip in portrait — the box
                        // being measured has to stay visible. Capped so a tall screen does not
                        // stretch the fields, and proportional so a short one still fits.
                        // The bottom padding LIFTS the sheet clear of the keyboard. Padding its
                        // content instead only shrank it in place, which hid the weight field
                        // behind the keyboard the moment it was tapped.
                        Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .padding(bottom = imeOverlap)
                            .height(sheetHeight)
                    },
                )
            }
            }
        }
    }
}

@Composable
private fun BunchMeasurementPanel(
    bbox: Bbox,
    bunchNumber: Int,
    bboxKey: String,
    showOptional: Boolean,
    imeVisible: Boolean,
    imeInnerPadding: Dp,
    onToggleOptional: () -> Unit,
    onApply: (BunchMeasurements) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var weight by remember(bboxKey, bbox.measurements) {
        mutableStateOf(bbox.measurements.weightKg?.toString().orEmpty())
    }
    var height by remember(bboxKey, bbox.measurements) {
        mutableStateOf(bbox.measurements.heightCm?.toString().orEmpty())
    }
    var circumference by remember(bboxKey, bbox.measurements) {
        mutableStateOf(bbox.measurements.circumferenceCm?.toString().orEmpty())
    }
    var notes by remember(bboxKey, bbox.measurements) {
        mutableStateOf(bbox.measurements.notes.orEmpty())
    }
    var error by remember(bboxKey) { mutableStateOf<String?>(null) }
    val fieldColors = OutlinedTextFieldDefaults.colors(
        unfocusedBorderColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
    )

    Surface(
        modifier = modifier,
        tonalElevation = 6.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = imeInnerPadding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.weight_bunch_number, bunchNumber),
                    modifier = Modifier.weight(1f),
                    // The keyboard leaves roughly a third of a landscape screen for this
                    // panel, so the header gives its space back to the fields while typing.
                    style = if (imeVisible) MaterialTheme.typography.titleMedium
                    else MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                IconButton(onClick = onClose, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Default.Close, stringResource(R.string.weight_close_panel))
                }
            }

            Column(
                modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                MeasurementNumberField(
                    value = weight,
                    onValueChange = { weight = it; error = null },
                    label = stringResource(R.string.weight_required_label),
                    colors = fieldColors,
                )
                TextButton(
                    onClick = onToggleOptional,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) {
                    Icon(
                        if (showOptional) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = null,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.weight_optional_details))
                    Spacer(Modifier.weight(1f))
                    val filledCount = listOf(height, circumference, notes).count(String::isNotBlank)
                    if (filledCount > 0) {
                        Text(
                            stringResource(R.string.weight_optional_filled, filledCount),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                AnimatedVisibility(visible = showOptional) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        MeasurementNumberField(
                            value = height,
                            onValueChange = { height = it; error = null },
                            label = stringResource(R.string.height_optional_label),
                            colors = fieldColors,
                        )
                        MeasurementNumberField(
                            value = circumference,
                            onValueChange = { circumference = it; error = null },
                            label = stringResource(R.string.circumference_optional_label),
                            colors = fieldColors,
                        )
                        OutlinedTextField(
                            value = notes,
                            onValueChange = { notes = it; error = null },
                            label = { Text(stringResource(R.string.notes_optional_label)) },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 2,
                            maxLines = 4,
                            colors = fieldColors,
                        )
                    }
                }
            }
            // Outside the scrolling column: inside it the collapsed sheet pushed the message
            // below the fold, where it rendered as a half-line clipped by the Apply button.
            error?.let {
                Text(
                    it,
                    modifier = Modifier.fillMaxWidth(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Button(
                onClick = {
                    BunchMeasurements.parseInput(weight, height, circumference, notes)
                        .onSuccess { measurements ->
                            onApply(measurements)
                            error = null
                        }
                        .onFailure { failure -> error = failure.message }
                },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) {
                Text(stringResource(R.string.weight_apply))
            }
        }
    }
}

@Composable
private fun MeasurementNumberField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    colors: TextFieldColors,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        colors = colors,
    )
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
    isSaving: Boolean,
    nextTreeEnabled: Boolean,
    editMode: Boolean,
    editTool: CanvasTool,
    showClassPicker: Boolean,
    showPrimaryActions: Boolean,
    showSwipeDirection: Boolean,
    linkEnabled: Boolean,
    onToggleDraw: () -> Unit,
    onClassChange: (String, AnnotationClass) -> Unit,
    onDelete: (String) -> Unit,
    onToggleBoxes: () -> Unit,
    onToggleSwipe: () -> Unit,
    onArmLink: () -> Unit,
    onCancelLink: () -> Unit,
    onUnlink: (() -> Unit)?,
    saveExitLabel: String,
    nextTreeLabel: String,
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

            if (showClassPicker) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    for (cls in AnnotationClass.assignableEntries) {
                        val isSelected = selectedBboxId?.let { id ->
                            session?.sides?.getOrNull(currentSideIndex)?.bboxes?.find { it.id == id }?.classId == cls.id
                        } == true
                        val container = if (hasSelection) cls.composeColor else cls.composeColor.copy(alpha = 0.4f)
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
                        enabled = selectedBboxId != null && linkEnabled,
                        modifier = Modifier.size(48.dp),
                    ) {
                        Icon(Icons.Default.Link, stringResource(R.string.cd_link), modifier = Modifier.size(26.dp))
                    }
                }

                // Draw-new-box toggle — only meaningful while editing geometry.
                if (onUnlink != null && !linkArmed && session?.confirmedLinks?.any {
                    (it.sideA == currentSideIndex && it.bboxIdA == selectedBboxId) ||
                        (it.sideB == currentSideIndex && it.bboxIdB == selectedBboxId)
                } == true) {
                    IconButton(onClick = onUnlink, enabled = !isSaving, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Default.LinkOff, stringResource(R.string.cd_remove_link), modifier = Modifier.size(26.dp))
                    }
                }

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
                if (showSwipeDirection) {
                    IconButton(onClick = onToggleSwipe, modifier = Modifier.size(48.dp)) {
                        Icon(
                            if (reverseSwipe) Icons.Default.RotateLeft else Icons.Default.RotateRight,
                            contentDescription = stringResource(
                                if (reverseSwipe) R.string.cd_capture_counter_clockwise else R.string.cd_capture_clockwise
                            ),
                            modifier = Modifier.size(26.dp),
                        )
                    }
                }

                IconButton(onClick = onToggleBoxes, modifier = Modifier.size(48.dp)) {
                    Icon(
                        if (showBoxes) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                        stringResource(R.string.cd_toggle_boxes),
                        modifier = Modifier.size(26.dp),
                    )
                }
            }

            if (showPrimaryActions) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    OutlinedButton(
                        onClick = onSaveExit,
                        enabled = !isSaving,
                        modifier = Modifier.weight(1f).height(48.dp),
                    ) { Text(saveExitLabel, style = MaterialTheme.typography.labelLarge) }
                    Button(
                        onClick = onNextTree,
                        enabled = !isSaving && nextTreeEnabled,
                        modifier = Modifier.weight(1f).height(48.dp),
                    ) { Text(nextTreeLabel, style = MaterialTheme.typography.labelLarge) }
                }
            }
        }
    }
}
