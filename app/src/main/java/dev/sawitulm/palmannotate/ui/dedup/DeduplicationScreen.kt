package dev.sawitulm.palmannotate.ui.dedup

import android.content.Context
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.sawitulm.palmannotate.R
import dev.sawitulm.palmannotate.ui.common.LocalToasts
import dev.sawitulm.palmannotate.ui.theme.PalmColors
import dev.sawitulm.palmannotate.data.storage.ExportFolderRepository
import dev.sawitulm.palmannotate.data.storage.SessionRepository
import dev.sawitulm.palmannotate.domain.dedup.SuggestionEngine
import dev.sawitulm.palmannotate.domain.model.*
import dev.sawitulm.palmannotate.domain.usecase.SessionUseCases
import dev.sawitulm.palmannotate.ui.common.AnnotationCanvas
import dev.sawitulm.palmannotate.ui.common.CanvasTool
import dev.sawitulm.palmannotate.ui.common.linkGroupColor
import dev.sawitulm.palmannotate.ui.common.MismatchResolveModal
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val TAG = "DedupPerf"
private const val CANVAS_TAG = "CanvasPerf"

// ════════════════════════════════════════════════════════════════════════════════
// ViewModel
// ════════════════════════════════════════════════════════════════════════════════

@HiltViewModel
class DedupViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val repo: SessionRepository,
    private val exportFolder: ExportFolderRepository,
) : ViewModel() {

    var session by mutableStateOf<ActiveSession?>(null)
        private set
    var currentPairIndex by mutableIntStateOf(0)
        private set
    var suggestions by mutableStateOf<List<SuggestedPair>>(emptyList())
        private set
    var showSuggestions by mutableStateOf(true)
    /** Capture rotation around the tree. true = clockwise (default), false = counter-clockwise.
     *  Drives both the seam-gating in SuggestionEngine and which side is shown on the left,
     *  so the overlapping seam always lands on the centre divider. */
    var clockwise by mutableStateOf(true)
        private set
    var isLoading by mutableStateOf(true)
        private set
    var errorMessage by mutableStateOf<String?>(null)
        private set
    var selectedSideB by mutableStateOf<String?>(null) // bboxId on right canvas (sideA)
    var selectedSideA by mutableStateOf<String?>(null) // bboxId on left canvas (sideB)
    var pendingBboxId by mutableStateOf<String?>(null)
    var pendingSide by mutableIntStateOf(-1)

    val adjacentPairs: List<Pair<Int, Int>>
        get() = session?.adjacentPairs ?: emptyList()

    val currentPair: Pair<Int, Int>?
        get() = adjacentPairs.getOrNull(currentPairIndex)

    // Place the overlapping seam on the CENTER divider for either capture direction:
    //   clockwise  → sideB (higher index) on the left, sideA (lower index) on the right
    //   counter-cw → sideA (lower index) on the left, sideB (higher index) on the right
    val leftSideIndex: Int get() = (if (clockwise) currentPair?.second else currentPair?.first) ?: 0
    val rightSideIndex: Int get() = (if (clockwise) currentPair?.first else currentPair?.second) ?: 1

    val leftSide: TreeSide? get() = session?.sides?.getOrNull(leftSideIndex)
    val rightSide: TreeSide? get() = session?.sides?.getOrNull(rightSideIndex)

    /** Links relevant to current pair */
    val pairLinks: List<CrossSideLink>
        get() = session?.confirmedLinks?.filter {
            (it.sideA == rightSideIndex && it.sideB == leftSideIndex) ||
            (it.sideA == leftSideIndex && it.sideB == rightSideIndex)
        } ?: emptyList()

    /** Partner (on the RIGHT canvas / rightSideIndex) of a box on the LEFT canvas (leftSideIndex).
     *  Matched by (side, id), NOT id alone: bbox ids repeat across sides (left "b0" and right
     *  "b0" both exist), so matching a bare id would mistake a link whose RIGHT endpoint shares
     *  the id for a link on this left box — falsely flagging it linked / highlighting the wrong box. */
    fun linkedBboxIdForSideB(bboxId: String): String? {
        for (link in pairLinks) {
            if (link.sideA == leftSideIndex && link.bboxIdA == bboxId) return link.bboxIdB
            if (link.sideB == leftSideIndex && link.bboxIdB == bboxId) return link.bboxIdA
        }
        return null
    }

    /** Partner (on the LEFT canvas / leftSideIndex) of a box on the RIGHT canvas (rightSideIndex). */
    fun linkedBboxIdForSideA(bboxId: String): String? {
        for (link in pairLinks) {
            if (link.sideA == rightSideIndex && link.bboxIdA == bboxId) return link.bboxIdB
            if (link.sideB == rightSideIndex && link.bboxIdB == bboxId) return link.bboxIdA
        }
        return null
    }

    fun load(sessionId: String) {
        val startTime = System.currentTimeMillis()
        // Log.d(TAG, "load() START - sessionId=$sessionId")
        viewModelScope.launch {
            isLoading = true
            errorMessage = null
            try {
                val dbStart = System.currentTimeMillis()
                val loaded = repo.loadActiveSession(sessionId)
                val dbTime = System.currentTimeMillis() - dbStart
                // Log.d(TAG, "load() DB query took ${dbTime}ms")
                
                session = loaded
                if (loaded == null) {
                    errorMessage = appContext.getString(R.string.dedup_session_not_found)
                } else if (loaded.sides.size < 2) {
                    errorMessage = appContext.getString(R.string.dedup_need_two_sides, loaded.sides.size)
                }
                
                val totalTime = System.currentTimeMillis() - startTime
                // Log.d(TAG, "load() END - total=${totalTime}ms, sides=${loaded?.sides?.size ?: 0}, links=${loaded?.confirmedLinks?.size ?: 0}")
            } catch (e: Exception) {
                errorMessage = appContext.getString(R.string.dedup_load_failed)
                Log.e(TAG, "load() ERROR", e)
            } finally {
                isLoading = false
            }
        }
    }

    fun nextPair() {
        if (adjacentPairs.isNotEmpty()) {
            currentPairIndex = (currentPairIndex + 1) % adjacentPairs.size
            clearSelection()
        }
    }

    fun prevPair() {
        if (adjacentPairs.isNotEmpty()) {
            currentPairIndex = (currentPairIndex + adjacentPairs.size - 1) % adjacentPairs.size
            clearSelection()
        }
    }

    fun goToPair(index: Int) {
        if (index in adjacentPairs.indices && index != currentPairIndex) {
            currentPairIndex = index
            clearSelection()
        }
    }

    fun selectSideB(bboxId: String?) { selectedSideB = bboxId }
    fun selectSideA(bboxId: String?) { selectedSideA = bboxId }

    fun clearSelection() {
        selectedSideB = null
        selectedSideA = null
        pendingBboxId = null
        pendingSide = -1
    }

    /** True when a box is selected on either canvas (drives the class bar's visibility). */
    val hasSelection: Boolean get() = selectedSideA != null || selectedSideB != null

    /** The box the class bar edits: left-canvas selection if present, else right-canvas.
     *  (selectedSideB = box on the LEFT canvas / leftSideIndex; selectedSideA = RIGHT.) */
    private fun selectedTarget(): Pair<Int, String>? = when {
        selectedSideB != null -> leftSideIndex to selectedSideB!!
        selectedSideA != null -> rightSideIndex to selectedSideA!!
        else -> null
    }

    /** classId of the currently selected box, or null if none / not found (drives the ring). */
    val selectedClassId: Int?
        get() {
            val (side, id) = selectedTarget() ?: return null
            return session?.sides?.getOrNull(side)?.bboxes?.find { it.id == id }?.classId
        }

    /** Set the selected box's class and propagate to its whole linked cluster — parity with
     *  the carousel class bar + mismatch modal, so a misgraded bunch can be fixed here.
     *  Re-derives mismatches on next read; saved by the normal Back/Compute save path. */
    fun setSelectedClass(cls: AnnotationClass) {
        val s = session ?: return
        val (side, id) = selectedTarget() ?: return
        session = SessionUseCases.setBboxClass(s, side, id, cls, propagate = true)
    }

    fun runSuggestions() {
        val s = session ?: return
        suggestions = SuggestionEngine.suggestAll(s, clockwise)
        showSuggestions = true
    }

    /** Flip capture direction; re-run suggestions if some are already shown. */
    fun toggleDirection() {
        clockwise = !clockwise
        clearSelection()
        if (suggestions.isNotEmpty()) runSuggestions()
    }

    fun onBboxTap(sideIndex: Int, bboxId: String) {
        val s = session ?: return
        val isLeft = sideIndex == leftSideIndex
        val isRight = sideIndex == rightSideIndex
        if (!isLeft && !isRight) return

        if (pendingBboxId != null) {
            val a = if (pendingSide == leftSideIndex) leftSideIndex else rightSideIndex
            val aId = pendingBboxId!!
            val b: Int
            val bId: String
            if (isLeft) { b = leftSideIndex; bId = bboxId }
            else { b = rightSideIndex; bId = bboxId }

            if (a != b) {
                s.addManualLink(a, aId, b, bId).let { session = it }
                suggestions = suggestions.filterNot {
                    (it.sideA == a && it.bboxIdA == aId && it.sideB == b && it.bboxIdB == bId) ||
                    (it.sideA == b && it.bboxIdA == bId && it.sideB == a && it.bboxIdB == aId)
                }
            }
            if (isLeft) { selectedSideB = bboxId; selectedSideA = aId }
            else { selectedSideA = bboxId; selectedSideB = aId }
            pendingBboxId = null
            pendingSide = -1
        } else {
            if (isLeft) {
                val partner = linkedBboxIdForSideB(bboxId)
                if (partner != null) {
                    pendingBboxId = partner
                    pendingSide = rightSideIndex
                    selectedSideB = bboxId
                    selectedSideA = partner
                } else {
                    pendingBboxId = bboxId
                    pendingSide = leftSideIndex
                    selectedSideB = bboxId
                    selectedSideA = null
                }
            } else {
                val partner = linkedBboxIdForSideA(bboxId)
                if (partner != null) {
                    pendingBboxId = partner
                    pendingSide = leftSideIndex
                    selectedSideA = bboxId
                    selectedSideB = partner
                } else {
                    pendingBboxId = bboxId
                    pendingSide = rightSideIndex
                    selectedSideA = bboxId
                    selectedSideB = null
                }
            }
        }
    }

    fun removeLink(linkId: String) {
        val s = session ?: return
        session = s.copy(confirmedLinks = s.confirmedLinks.filter { it.linkId != linkId })
    }

    fun confirmSuggestion(sug: SuggestedPair) {
        val s = session ?: return
        SessionUseCases.addManualLink(s, sug.sideA, sug.bboxIdA, sug.sideB, sug.bboxIdB).let { session = it }
        suggestions = suggestions - sug
    }

    fun rejectSuggestion(sug: SuggestedPair) {
        suggestions = suggestions - sug
    }

    fun acceptAllAuto() {
        val auto = suggestions.filter { it.category == "auto" }
        for (sug in auto) confirmSuggestion(sug)
    }

    fun save() {
        val s = session ?: return
        viewModelScope.launch {
            try {
                val safTreeUri = exportFolder.folderUri.first()
                repo.saveSession(s, safTreeUri)
            } catch (e: Exception) {
                Log.e(TAG, "dedup save failed", e)
                errorMessage = e.localizedMessage ?: "Save failed"
            }
        }
    }

    fun currentMismatches(): List<SessionUseCases.MismatchCluster> {
        val s = session ?: return emptyList()
        return SessionUseCases.getMismatchedClusters(s)
    }

    fun saveAndContinue(onDone: () -> Unit) {
        val s = session ?: return
        viewModelScope.launch {
            try {
                val safTreeUri = exportFolder.folderUri.first()
                repo.saveSession(s, safTreeUri)
            } catch (e: Exception) {
                // Don't navigate forward on a failed save — surface the error so the
                // operator can retry rather than losing dedup work to a silent crash.
                Log.e(TAG, "dedup saveAndContinue failed", e)
                errorMessage = e.localizedMessage ?: "Save failed"
                return@launch
            }
            onDone()
        }
    }

    fun resolveAllMismatchesAndSave(choices: Map<String, Int>? = null, onDone: () -> Unit) {
        val s = session ?: return
        val resolved = SessionUseCases.resolveAllMismatches(s, choices)
        session = resolved
        viewModelScope.launch {
            try {
                val safTreeUri = exportFolder.folderUri.first()
                repo.saveSession(resolved, safTreeUri)
            } catch (e: Exception) {
                Log.e(TAG, "dedup resolveAllMismatchesAndSave failed", e)
                errorMessage = e.localizedMessage ?: "Save failed"
                return@launch
            }
            onDone()
        }
    }
}

// ════════════════════════════════════════════════════════════════════════════════
// Extensions
// ════════════════════════════════════════════════════════════════════════════════

private fun ActiveSession.addManualLink(sA: Int, bA: String, sB: Int, bB: String): ActiveSession {
    return SessionUseCases.addManualLink(this, sA, bA, sB, bB)
}

// ════════════════════════════════════════════════════════════════════════════════
// UI — Two-canvas dedup surface (matches JS DedupUI + index.html #panel-dedup)
// ════════════════════════════════════════════════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeduplicationScreen(
    sessionId: String,
    onBack: () -> Unit,
    onCompute: () -> Unit,
    viewModel: DedupViewModel = hiltViewModel(),
) {
    // Track screen open time
    val screenOpenTime = remember { System.currentTimeMillis() }
    LaunchedEffect(sessionId) {
        // Log.d(TAG, "DeduplicationScreen composable START - sessionId=$sessionId")
        viewModel.load(sessionId)
    }
    
    // Log when session is loaded
    LaunchedEffect(viewModel.session) {
        if (viewModel.session != null) {
            val elapsed = System.currentTimeMillis() - screenOpenTime
            // Log.d(TAG, "DeduplicationScreen SESSION LOADED - elapsed=${elapsed}ms")
        }
    }
    
    // Log when loading is complete
    LaunchedEffect(viewModel.isLoading) {
        if (!viewModel.isLoading) {
            val elapsed = System.currentTimeMillis() - screenOpenTime
            // Log.d(TAG, "DeduplicationScreen LOADING COMPLETE - elapsed=${elapsed}ms")
        }
    }

    val session = viewModel.session
    val leftSide = viewModel.leftSide
    val rightSide = viewModel.rightSide
    val isPortrait = LocalConfiguration.current.screenWidthDp < 600

    // Persist confirmed links before leaving — Back used to pop without saving, so reviewed
    // links were lost unless the operator pressed Compute. Now Back saves then exits (with a
    // brief "Links saved" toast); a failed save surfaces via errorMessage instead of navigating.
    val toasts = LocalToasts.current
    val savedMsg = stringResource(R.string.dedup_saved)
    fun saveThenExit() = viewModel.saveAndContinue { toasts.success(savedMsg); onBack() }
    BackHandler { saveThenExit() }

    var showMismatch by remember { mutableStateOf(false) }
    val mismatches = remember(session) { viewModel.currentMismatches() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(session?.treeName ?: stringResource(R.string.dedup_title)) },
                navigationIcon = {
                    IconButton(onClick = { saveThenExit() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back)) }
                },
                actions = {
                    IconButton(onClick = { viewModel.toggleDirection() }) {
                        Icon(
                            if (viewModel.clockwise) Icons.Default.RotateRight else Icons.Default.RotateLeft,
                            contentDescription = stringResource(if (viewModel.clockwise) R.string.cd_capture_clockwise else R.string.cd_capture_counter_clockwise),
                        )
                    }
                    IconButton(onClick = { viewModel.runSuggestions() }) {
                        Icon(Icons.Default.AutoAwesome, stringResource(R.string.cd_suggest))
                    }
                    IconButton(onClick = {
                        if (mismatches.isNotEmpty()) showMismatch = true
                        else viewModel.resolveAllMismatchesAndSave { onCompute() }
                    }) {
                        Icon(Icons.Default.CheckCircle, stringResource(R.string.cd_compute))
                    }
                },
            )
        },
    ) { padding ->
        if (viewModel.isLoading) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else if (viewModel.errorMessage != null || leftSide == null || rightSide == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.Warning, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(48.dp))
                    Spacer(Modifier.height(8.dp))
                    Text(
                        viewModel.errorMessage ?: stringResource(R.string.dedup_error_generic),
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 24.dp),
                    )
                    Spacer(Modifier.height(16.dp))
                    OutlinedButton(onClick = onBack) { Text(stringResource(R.string.action_go_back)) }
                }
            }
        } else {
            val totalPairs = viewModel.adjacentPairs.size
            // The pager is the single source of truth for which pair is shown. The arrow
            // buttons scroll the pager; the pager's settled page drives the ViewModel index.
            // (The previous two-way LaunchedEffect sync fought itself: an arrow-triggered
            // animateScrollToPage across the wrap-around scrolled THROUGH the intermediate
            // pages, each of which pushed a goToPair back — that was the "weird jumping".)
            val pagerState = rememberPagerState(pageCount = { totalPairs.coerceAtLeast(1) })
            val scope = rememberCoroutineScope()
            // Capture direction drives the VISUAL slide direction, so paging feels like the
            // physical walk around the tree and matches the seam-entry logic (the new side
            // enters from the left when clockwise, from the right when counter-clockwise):
            //   clockwise  → advancing a pair slides content left→right  (reverseLayout = true)
            //   counter-cw → advancing a pair slides content right→left  (reverseLayout = false)
            val reverseLayout = viewModel.clockwise

            // Drive the ViewModel from the pager's SETTLED page only. Using currentPage here
            // raced during the initial pageCount 1→4 expansion and made the screen open on a
            // random pair; settledPage updates once the pager comes to rest.
            LaunchedEffect(pagerState) {
                snapshotFlow { pagerState.settledPage }.collect { page ->
                    if (page < totalPairs) viewModel.goToPair(page)
                }
            }

            // Move one page toward the requested VISUAL side so the chevron's arrow, the swipe
            // motion, and the revealed page all agree (with wrap-around). reverseLayout flips
            // which page index sits on which visual side, so we fold it into the delta here.
            fun scrollVisual(towardRight: Boolean) {
                if (totalPairs == 0) return
                val cur = pagerState.currentPage
                val delta = if (towardRight == reverseLayout) -1 else 1
                val target = (((cur + delta) % totalPairs) + totalPairs) % totalPairs
                scope.launch {
                    if (kotlin.math.abs(target - cur) == 1) pagerState.animateScrollToPage(target)
                    else pagerState.scrollToPage(target)
                }
            }

            Column(Modifier.fillMaxSize().padding(padding)) {
                // Pair navigation bar
                PairNav(
                    pairIndex = viewModel.currentPairIndex,
                    totalPairs = totalPairs,
                    leftLabel = stringResource(R.string.dedup_side, viewModel.leftSideIndex + 1),
                    rightLabel = stringResource(R.string.dedup_side, viewModel.rightSideIndex + 1),
                    // Each chevron reveals the neighbour it points at; the swipe motion and the
                    // arrow always agree. Progression around the tree (next pair) therefore runs
                    // via the LEFT chevron when clockwise and the RIGHT chevron when counter-cw.
                    onPrev = { scrollVisual(towardRight = false) }, // visually-left chevron
                    onNext = { scrollVisual(towardRight = true) },  // visually-right chevron
                )

                HorizontalPager(
                    state = pagerState,
                    reverseLayout = reverseLayout,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                ) { page ->
                    val pair = viewModel.adjacentPairs.getOrNull(page) ?: return@HorizontalPager
                    // Left/right follows capture direction so the seam sits at the centre.
                    val lIdx = if (viewModel.clockwise) pair.second else pair.first
                    val rIdx = if (viewModel.clockwise) pair.first else pair.second
                    val lSide = viewModel.session?.sides?.getOrNull(lIdx)
                    val rSide = viewModel.session?.sides?.getOrNull(rIdx)

                    if (lSide == null || rSide == null) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(stringResource(R.string.dedup_no_pair_data))
                        }
                        return@HorizontalPager
                    }

                    // Global, tree-wide link numbering (shared with the carousel via
                    // SessionUseCases) so a link keeps the SAME number + colour everywhere — both
                    // dedup canvases AND the chips below, and the carousel badges.
                    val lLinkMap = viewModel.session?.let { SessionUseCases.linkGroupForSide(it, lIdx) } ?: emptyMap()
                    val rLinkMap = viewModel.session?.let { SessionUseCases.linkGroupForSide(it, rIdx) } ?: emptyMap()

                    if (isPortrait) {
                        Column(modifier = Modifier.fillMaxSize()) {
                            DedupHalfCanvas(
                                label = stringResource(R.string.dedup_side, lIdx + 1),
                                side = lSide,
                                linkedBoxes = lLinkMap,
                                selectedId = viewModel.selectedSideB,
                                pending = viewModel.pendingBboxId.takeIf { viewModel.pendingSide == lIdx },
                                onTap = { viewModel.onBboxTap(lIdx, it) },
                                modifier = Modifier.weight(1f).fillMaxWidth(),
                            )
                            HorizontalDivider(color = PalmColors.Accent, thickness = 2.dp)
                            DedupHalfCanvas(
                                label = stringResource(R.string.dedup_side, rIdx + 1),
                                side = rSide,
                                linkedBoxes = rLinkMap,
                                selectedId = viewModel.selectedSideA,
                                pending = viewModel.pendingBboxId.takeIf { viewModel.pendingSide == rIdx },
                                onTap = { viewModel.onBboxTap(rIdx, it) },
                                modifier = Modifier.weight(1f).fillMaxWidth(),
                            )
                        }
                    } else {
                        Row(modifier = Modifier.fillMaxSize()) {
                            DedupHalfCanvas(
                                label = stringResource(R.string.dedup_side, lIdx + 1),
                                side = lSide,
                                linkedBoxes = lLinkMap,
                                selectedId = viewModel.selectedSideB,
                                pending = viewModel.pendingBboxId.takeIf { viewModel.pendingSide == lIdx },
                                onTap = { viewModel.onBboxTap(lIdx, it) },
                                modifier = Modifier.weight(1f).fillMaxHeight(),
                            )
                            VerticalDivider(color = PalmColors.Accent, thickness = 2.dp)
                            DedupHalfCanvas(
                                label = stringResource(R.string.dedup_side, rIdx + 1),
                                side = rSide,
                                linkedBoxes = rLinkMap,
                                selectedId = viewModel.selectedSideA,
                                pending = viewModel.pendingBboxId.takeIf { viewModel.pendingSide == rIdx },
                                onTap = { viewModel.onBboxTap(rIdx, it) },
                                modifier = Modifier.weight(1f).fillMaxHeight(),
                            )
                        }
                    }
                }

                // Class bar — appears when a box is selected so a misgraded bunch can be
                // re-classed right here; propagates to the whole linked cluster.
                if (viewModel.hasSelection) {
                    DedupClassBar(
                        currentClassId = viewModel.selectedClassId,
                        onClassChange = { viewModel.setSelectedClass(it) },
                    )
                }

                // Bottom panels: Suggestions + Confirmed Links
                BottomPanels(
                    suggestions = viewModel.suggestions,
                    showSuggestions = viewModel.showSuggestions,
                    confirmedLinks = viewModel.pairLinks,
                    session = session,
                    onToggleSuggestions = { viewModel.showSuggestions = !viewModel.showSuggestions },
                    onConfirm = { viewModel.confirmSuggestion(it) },
                    onReject = { viewModel.rejectSuggestion(it) },
                    onAcceptAll = { viewModel.acceptAllAuto() },
                    onRemoveLink = { viewModel.removeLink(it) },
                )
            }
        }
    }

    // Mismatch resolve modal
    if (showMismatch && mismatches.isNotEmpty()) {
        MismatchResolveModal(
            mismatches = mismatches,
            onResolveAll = { choices ->
                viewModel.resolveAllMismatchesAndSave(choices) { onCompute() }
                showMismatch = false
            },
            onCancel = { showMismatch = false },
        )
    }
}

// ════════════════════════════════════════════════════════════════════════════════
// Sub-composables
// ════════════════════════════════════════════════════════════════════════════════

@Composable
private fun PairNav(
    pairIndex: Int,
    totalPairs: Int,
    leftLabel: String,
    rightLabel: String,
    onPrev: () -> Unit,
    onNext: () -> Unit,
) {
    Surface(tonalElevation = 1.dp) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onPrev) {
                Icon(Icons.Default.ChevronLeft, stringResource(R.string.cd_prev_pair))
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(R.string.dedup_pair_labels, leftLabel, rightLabel), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
                Text(stringResource(R.string.dedup_pair_of, pairIndex + 1, totalPairs), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = onNext) {
                Icon(Icons.Default.ChevronRight, stringResource(R.string.cd_next_pair))
            }
        }
    }
}

@Composable
private fun DedupHalfCanvas(
    label: String,
    side: TreeSide,
    linkedBoxes: Map<String, Int>,
    selectedId: String?,
    pending: String?,
    onTap: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier) {
        AnnotationCanvas(
            imageUriString = side.imageUri?.toString(),
            bboxes = side.bboxes,
            selectedBboxId = selectedId,
            imageWidth = side.imageWidth.coerceAtLeast(1),
            imageHeight = side.imageHeight.coerceAtLeast(1),
            tool = CanvasTool.SELECT,
            showBoxes = true,
            linkedBoxes = linkedBoxes,
            onBboxTap = { id -> if (id != null) onTap(id) },
            modifier = Modifier.fillMaxSize(),
        )

        // Side label overhead
        Surface(
            modifier = Modifier.align(Alignment.TopStart).padding(4.dp),
            shape = RoundedCornerShape(4.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
        ) {
            Text(
                label,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        // Pending indicator
        if (pending != null) {
            Surface(
                modifier = Modifier.align(Alignment.Center).padding(8.dp),
                shape = RoundedCornerShape(8.dp),
                color = PalmColors.LinkHighlight.copy(alpha = 0.92f),
            ) {
                Text(
                    stringResource(R.string.dedup_tap_matching),
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = PalmColors.OnLinkHighlight,
                )
            }
        }
    }
}

@Composable
private fun DedupClassBar(
    currentClassId: Int?,
    onClassChange: (AnnotationClass) -> Unit,
) {
    // Mirrors CarouselBottomBar Row 1: B1–B4 sharing full width, ring on the active class.
    Surface(tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for (cls in AnnotationClass.assignableEntries) {
                val isSelected = currentClassId == cls.id
                val labelColor = if (cls.composeColor.luminance() > 0.5f) Color.Black else Color.White
                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .height(44.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onClassChange(cls) },
                    color = cls.composeColor,
                    shape = RoundedCornerShape(8.dp),
                    border = if (isSelected) ButtonDefaults.outlinedButtonBorder(enabled = true) else null,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(cls.displayName, color = labelColor, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun BottomPanels(
    suggestions: List<SuggestedPair>,
    showSuggestions: Boolean,
    confirmedLinks: List<CrossSideLink>,
    session: ActiveSession?,
    onToggleSuggestions: () -> Unit,
    onConfirm: (SuggestedPair) -> Unit,
    onReject: (SuggestedPair) -> Unit,
    onAcceptAll: () -> Unit,
    onRemoveLink: (String) -> Unit,
) {
    Surface(tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(8.dp).heightIn(max = 200.dp)) {
            // Suggestions header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onToggleSuggestions) {
                    Icon(
                        if (showSuggestions) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.dedup_suggestions, suggestions.size), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge)
                }
                val autoCount = suggestions.count { it.category == "auto" }
                if (autoCount > 0) {
                    TextButton(onClick = onAcceptAll) {
                        Text(stringResource(R.string.dedup_accept_all_auto, autoCount), style = MaterialTheme.typography.labelLarge)
                    }
                }
            }

            if (showSuggestions) {
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    for (sug in suggestions.take(20)) {
                        SuggestionChip(sug, onConfirm, onReject)
                    }
                }
            }

            // Confirmed links
            if (confirmedLinks.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(stringResource(R.string.dedup_confirmed_links, confirmedLinks.size), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    val groups = session?.let { SessionUseCases.linkGroupNumbers(it) } ?: emptyMap()
                    confirmedLinks.forEach { link ->
                        // Same number + colour as the on-canvas badge for this link, so the list
                        // and the images read as one thing instead of bare "b2↔b3" text.
                        val num = groups["${link.sideA}:${link.bboxIdA}"] ?: 1
                        val gc = linkGroupColor(num)
                        InputChip(
                            selected = true,
                            onClick = { onRemoveLink(link.linkId) },
                            leadingIcon = {
                                Box(
                                    modifier = Modifier.size(22.dp).clip(CircleShape).background(gc),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        num.toString(),
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = if (gc.luminance() > 0.5f) Color.Black else Color.White,
                                    )
                                }
                            },
                            label = { Text("${link.bboxIdA} ↔ ${link.bboxIdB}", style = MaterialTheme.typography.labelMedium) },
                            trailingIcon = { Icon(Icons.Default.Close, stringResource(R.string.cd_remove_link), modifier = Modifier.size(16.dp)) },
                            modifier = Modifier.height(40.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SuggestionChip(
    sug: SuggestedPair,
    onConfirm: (SuggestedPair) -> Unit,
    onReject: (SuggestedPair) -> Unit,
) {
    val scorePct = (sug.score * 100).toInt()
    val tint = when {
        sug.category == "auto" -> PalmColors.Success
        scorePct >= 50 -> PalmColors.Warning
        else -> MaterialTheme.colorScheme.error
    }
    Surface(
        shape = RoundedCornerShape(8.dp),
        border = ButtonDefaults.outlinedButtonBorder(enabled = true),
        modifier = Modifier.widthIn(min = 132.dp, max = 200.dp),
    ) {
        Column(Modifier.padding(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${sug.bboxIdA} ↔ ${sug.bboxIdB}",
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.weight(1f),
                )
                Text("$scorePct%", style = MaterialTheme.typography.labelMedium, color = tint, fontWeight = FontWeight.Bold)
            }
            Text(
                sug.category.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = tint,
            )
            // Signal badges
            sug.signals?.let { sig ->
                Row(horizontalArrangement = Arrangement.spacedBy(3.dp), modifier = Modifier.padding(top = 2.dp)) {
                    SignalBadge("S", sig.seam)
                    SignalBadge("V", sig.vert)
                    SignalBadge("Z", sig.size)
                    SignalBadge("C", sig.cls)
                }
            }
            Spacer(Modifier.height(2.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TextButton(
                    onClick = { onConfirm(sug) },
                    modifier = Modifier.weight(1f).height(40.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp),
                ) { Text(stringResource(R.string.dedup_accept), style = MaterialTheme.typography.labelMedium) }
                TextButton(
                    onClick = { onReject(sug) },
                    modifier = Modifier.weight(1f).height(40.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp),
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text(stringResource(R.string.dedup_reject), style = MaterialTheme.typography.labelMedium) }
            }
        }
    }
}

@Composable
private fun SignalBadge(label: String, value: Float) {
    val c = when {
        value >= 0.75f -> PalmColors.Success
        value >= 0.5f -> PalmColors.Warning
        else -> PalmColors.Danger
    }
    Surface(
        shape = RoundedCornerShape(3.dp),
        color = c.copy(alpha = 0.2f),
    ) {
        Text(
            "$label ${(value * 100).toInt()}",
            style = MaterialTheme.typography.labelSmall,
            color = c,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
            fontWeight = FontWeight.Bold,
        )
    }
}

