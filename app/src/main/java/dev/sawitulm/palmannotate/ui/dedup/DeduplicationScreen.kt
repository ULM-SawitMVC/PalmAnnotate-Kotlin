package dev.sawitulm.palmannotate.ui.dedup

import android.util.Log
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.sawitulm.palmannotate.data.storage.ExportFolderRepository
import dev.sawitulm.palmannotate.data.storage.SessionRepository
import dev.sawitulm.palmannotate.domain.dedup.SuggestionEngine
import dev.sawitulm.palmannotate.domain.model.*
import dev.sawitulm.palmannotate.domain.usecase.SessionUseCases
import dev.sawitulm.palmannotate.ui.common.AnnotationCanvas
import dev.sawitulm.palmannotate.ui.common.CanvasTool
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

    /** Boxes on sideB that are linked from sideA's perspective */
    fun linkedBboxIdForSideB(bboxId: String): String? {
        for (link in pairLinks) {
            if (link.bboxIdB == bboxId) return link.bboxIdA
            if (link.bboxIdA == bboxId) return link.bboxIdB
        }
        return null
    }

    fun linkedBboxIdForSideA(bboxId: String): String? {
        for (link in pairLinks) {
            if (link.bboxIdA == bboxId) return link.bboxIdB
            if (link.bboxIdB == bboxId) return link.bboxIdA
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
                    errorMessage = "Session not found."
                } else if (loaded.sides.size < 2) {
                    errorMessage = "Need at least 2 sides for deduplication (found ${loaded.sides.size})."
                }
                
                val totalTime = System.currentTimeMillis() - startTime
                // Log.d(TAG, "load() END - total=${totalTime}ms, sides=${loaded?.sides?.size ?: 0}, links=${loaded?.confirmedLinks?.size ?: 0}")
            } catch (e: Exception) {
                errorMessage = "Failed to load: ${e.localizedMessage ?: "Unknown error"}"
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
            val safTreeUri = exportFolder.folderUri.first()
            repo.saveSession(s, safTreeUri)
        }
    }

    fun currentMismatches(): List<SessionUseCases.MismatchCluster> {
        val s = session ?: return emptyList()
        return SessionUseCases.getMismatchedClusters(s)
    }

    fun saveAndContinue(onDone: () -> Unit) {
        val s = session ?: return
        val tap = System.currentTimeMillis()
        viewModelScope.launch {
            val safTreeUri = exportFolder.folderUri.first()
            repo.saveSession(s, safTreeUri)
            // android.util.Log.d("SavePerf", "dedup saveAndContinue: tap→nav = ${System.currentTimeMillis() - tap}ms (felt)")
            onDone()
        }
    }

    fun resolveAllMismatchesAndSave(choices: Map<String, Int>? = null, onDone: () -> Unit) {
        val s = session ?: return
        val resolved = SessionUseCases.resolveAllMismatches(s, choices)
        session = resolved
        viewModelScope.launch {
            val safTreeUri = exportFolder.folderUri.first()
            repo.saveSession(resolved, safTreeUri)
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

    var showMismatch by remember { mutableStateOf(false) }
    val mismatches = remember(session) { viewModel.currentMismatches() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(session?.treeName ?: "Deduplication") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
                actions = {
                    IconButton(onClick = { viewModel.toggleDirection() }) {
                        Icon(
                            if (viewModel.clockwise) Icons.Default.RotateRight else Icons.Default.RotateLeft,
                            contentDescription = if (viewModel.clockwise) "Capture: clockwise" else "Capture: counter-clockwise",
                        )
                    }
                    IconButton(onClick = { viewModel.runSuggestions() }) {
                        Icon(Icons.Default.AutoAwesome, "Suggest")
                    }
                    IconButton(onClick = {
                        if (mismatches.isNotEmpty()) showMismatch = true
                        else viewModel.resolveAllMismatchesAndSave { onCompute() }
                    }) {
                        Icon(Icons.Default.CheckCircle, "Compute")
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
                    Icon(Icons.Default.Warning, "Error", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(48.dp))
                    Spacer(Modifier.height(8.dp))
                    Text(
                        viewModel.errorMessage ?: "Insufficient data for deduplication.",
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 24.dp),
                    )
                    Spacer(Modifier.height(16.dp))
                    OutlinedButton(onClick = onBack) { Text("Go Back") }
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
                    leftLabel = "Side ${viewModel.leftSideIndex + 1}",
                    rightLabel = "Side ${viewModel.rightSideIndex + 1}",
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
                            Text("No data for this pair")
                        }
                        return@HorizontalPager
                    }

                    // Pair-specific links
                    val pageLinks = viewModel.session?.confirmedLinks?.filter {
                        (it.sideA == rIdx && it.sideB == lIdx) ||
                        (it.sideA == lIdx && it.sideB == rIdx)
                    } ?: emptyList()
                    val linkedIds = pageLinks.flatMap { listOf(it.bboxIdA, it.bboxIdB) }.toSet()

                    if (isPortrait) {
                        Column(modifier = Modifier.fillMaxSize()) {
                            DedupHalfCanvas(
                                label = "Side ${lIdx + 1}",
                                side = lSide,
                                linkedIds = linkedIds,
                                selectedId = viewModel.selectedSideB,
                                pending = viewModel.pendingBboxId.takeIf { viewModel.pendingSide == lIdx },
                                onTap = { viewModel.onBboxTap(lIdx, it) },
                                modifier = Modifier.weight(1f).fillMaxWidth(),
                            )
                            HorizontalDivider(color = Color(0xFFB8E04A), thickness = 2.dp)
                            DedupHalfCanvas(
                                label = "Side ${rIdx + 1}",
                                side = rSide,
                                linkedIds = linkedIds,
                                selectedId = viewModel.selectedSideA,
                                pending = viewModel.pendingBboxId.takeIf { viewModel.pendingSide == rIdx },
                                onTap = { viewModel.onBboxTap(rIdx, it) },
                                modifier = Modifier.weight(1f).fillMaxWidth(),
                            )
                        }
                    } else {
                        Row(modifier = Modifier.fillMaxSize()) {
                            DedupHalfCanvas(
                                label = "Side ${lIdx + 1}",
                                side = lSide,
                                linkedIds = linkedIds,
                                selectedId = viewModel.selectedSideB,
                                pending = viewModel.pendingBboxId.takeIf { viewModel.pendingSide == lIdx },
                                onTap = { viewModel.onBboxTap(lIdx, it) },
                                modifier = Modifier.weight(1f).fillMaxHeight(),
                            )
                            VerticalDivider(color = Color(0xFFB8E04A), thickness = 2.dp)
                            DedupHalfCanvas(
                                label = "Side ${rIdx + 1}",
                                side = rSide,
                                linkedIds = linkedIds,
                                selectedId = viewModel.selectedSideA,
                                pending = viewModel.pendingBboxId.takeIf { viewModel.pendingSide == rIdx },
                                onTap = { viewModel.onBboxTap(rIdx, it) },
                                modifier = Modifier.weight(1f).fillMaxHeight(),
                            )
                        }
                    }
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
                Icon(Icons.Default.ChevronLeft, "Previous pair")
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("$leftLabel  ↔  $rightLabel", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Text("Pair ${pairIndex + 1} / $totalPairs", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = onNext) {
                Icon(Icons.Default.ChevronRight, "Next pair")
            }
        }
    }
}

@Composable
private fun DedupHalfCanvas(
    label: String,
    side: TreeSide,
    linkedIds: Set<String>,
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
                color = Color(0xFFB8E04A).copy(alpha = 0.9f),
            ) {
                Text(
                    "← Tap matching box →",
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF0C120C),
                )
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
                        "Toggle",
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("Suggestions (${suggestions.size})", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
                val autoCount = suggestions.count { it.category == "auto" }
                if (autoCount > 0) {
                    TextButton(onClick = onAcceptAll) {
                        Text("Accept All Auto ($autoCount)", fontSize = 12.sp)
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
                Text("Confirmed Links (${confirmedLinks.size})", fontWeight = FontWeight.Bold, fontSize = 13.sp, modifier = Modifier.padding(start = 8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    for (link in confirmedLinks) {
                        InputChip(
                            selected = true,
                            onClick = { onRemoveLink(link.linkId) },
                            label = { Text("${link.bboxIdA}↔${link.bboxIdB}", fontSize = 11.sp) },
                            trailingIcon = { Icon(Icons.Default.Close, "Remove", modifier = Modifier.size(14.dp)) },
                            modifier = Modifier.height(28.dp),
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
        sug.category == "auto" -> Color(0xFF2DD47B)
        scorePct >= 50 -> Color(0xFFE4B84A)
        else -> MaterialTheme.colorScheme.error
    }
    Surface(
        shape = RoundedCornerShape(8.dp),
        border = ButtonDefaults.outlinedButtonBorder(enabled = true),
        modifier = Modifier.widthIn(max = 200.dp),
    ) {
        Column(Modifier.padding(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${sug.bboxIdA} ↔ ${sug.bboxIdB}",
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp,
                    modifier = Modifier.weight(1f),
                )
                Text("$scorePct%", fontSize = 11.sp, color = tint, fontWeight = FontWeight.Bold)
            }
            Text(
                sug.category.uppercase(),
                fontSize = 9.sp,
                color = tint,
            )
            // Signal badges
            sug.signals?.let { sig ->
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    SignalBadge("S", sig.seam)
                    SignalBadge("V", sig.vert)
                    SignalBadge("Z", sig.size)
                    SignalBadge("C", sig.cls)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(
                    onClick = { onConfirm(sug) },
                    modifier = Modifier.height(24.dp),
                    contentPadding = PaddingValues(horizontal = 6.dp),
                ) { Text("Accept", fontSize = 10.sp) }
                TextButton(
                    onClick = { onReject(sug) },
                    modifier = Modifier.height(24.dp),
                    contentPadding = PaddingValues(horizontal = 6.dp),
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text("Reject", fontSize = 10.sp) }
            }
        }
    }
}

@Composable
private fun SignalBadge(label: String, value: Float) {
    val c = when {
        value >= 0.75f -> Color(0xFF2DD47B)
        value >= 0.5f -> Color(0xFFE4B84A)
        else -> Color(0xFFF06060)
    }
    Surface(
        shape = RoundedCornerShape(3.dp),
        color = c.copy(alpha = 0.2f),
    ) {
        Text(
            "$label ${(value * 100).toInt()}",
            fontSize = 8.sp,
            color = c,
            modifier = Modifier.padding(horizontal = 2.dp),
            fontWeight = FontWeight.Bold,
        )
    }
}

