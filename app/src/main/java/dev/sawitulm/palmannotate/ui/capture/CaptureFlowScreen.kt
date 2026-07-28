package dev.sawitulm.palmannotate.ui.capture

import android.Manifest
import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import coil.compose.rememberAsyncImagePainter
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.sawitulm.palmannotate.R
import dev.sawitulm.palmannotate.ui.common.LocalToasts
import dev.sawitulm.palmannotate.ui.theme.PalmColors
import dev.sawitulm.palmannotate.data.camera.OrbbecManager
import dev.sawitulm.palmannotate.data.db.SessionEntity
import dev.sawitulm.palmannotate.data.location.GpsProvider
import dev.sawitulm.palmannotate.data.storage.AndroidStorageManager
import dev.sawitulm.palmannotate.data.storage.CaptureDraftCursorPolicy
import dev.sawitulm.palmannotate.data.storage.CaptureDraftSideSnapshot
import dev.sawitulm.palmannotate.data.storage.CaptureDraftSnapshot
import dev.sawitulm.palmannotate.data.storage.DepthArtifactContract
import dev.sawitulm.palmannotate.data.storage.DraftWriteAwaiter
import dev.sawitulm.palmannotate.data.storage.ExportFolderRepository
import dev.sawitulm.palmannotate.data.storage.InputCache
import dev.sawitulm.palmannotate.data.storage.JpegOrientationNormalizer
import dev.sawitulm.palmannotate.data.storage.PackageProvenanceCodec
import dev.sawitulm.palmannotate.data.storage.SessionRepository
import dev.sawitulm.palmannotate.domain.model.*
import dev.sawitulm.palmannotate.domain.quality.QualityCheck
import dev.sawitulm.palmannotate.ui.common.QualityGateModal
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject

enum class SideStep { PREVIEW, REVIEW }
enum class CaptureSource { PHONE_CAMERA, ORBBEC }

enum class CapturePhase { SIDES, REVIEW_ALL }

@HiltViewModel
class CaptureFlowViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val repo: SessionRepository,
    private val storage: AndroidStorageManager,
    private val gps: GpsProvider,
    private val exportFolder: ExportFolderRepository,
    private val orbbec: OrbbecManager,
    private val inputCache: InputCache,
) : ViewModel() {

    var run by mutableStateOf<SessionEntity?>(null)
        private set
    var sideCount by mutableIntStateOf(4)
    var currentSide by mutableIntStateOf(0)
    val capturedImages = mutableStateListOf<Uri?>()
    val capturedDepths = mutableStateListOf<OrbbecManager.OrbbecDepthData?>()
    private val capturedSources = mutableStateListOf<CaptureSource?>()
    var manualId by mutableStateOf("")
    var gpsStatus by mutableStateOf<String?>(null)
    var currentStep by mutableStateOf(SideStep.PREVIEW)
        private set
    var phase by mutableStateOf(CapturePhase.SIDES)
        private set
    var retakingFromReview by mutableStateOf(false)
        private set
    /**
     * WS-13 — the full GPS record, not a bare coordinate pair.
     *
     * Replacing this wholesale on every refresh is the point: the previous code only ever
     * ASSIGNED lat/lng on success, so a failed refresh silently kept the previous tree's
     * coordinates and the next capture inherited them. Here a failure overwrites the record with
     * an explicit PERMISSION_DENIED / LOCATION_OFF / UNAVAILABLE and no coordinates.
     */
    var gpsProvenance by mutableStateOf(GpsProvenance.UNKNOWN)
        private set
    var isSaving by mutableStateOf(false)
        private set
    var saveError by mutableStateOf<String?>(null)
        private set
    var captureError by mutableStateOf<String?>(null)
        private set
    var captureSource by mutableStateOf(
        if (inputCache.lastCaptureUsesOrbbec) CaptureSource.ORBBEC
        else CaptureSource.PHONE_CAMERA
    )
        private set
    var showQaDialog by mutableStateOf(false)
        private set
    var qaReport by mutableStateOf<QualityCheck.CaptureReport?>(null)
        private set
    var draftStatus by mutableStateOf<String?>(null)
        private set
    var pendingDraftWrites by mutableIntStateOf(0)
        private set
    var isDraftValidating by mutableStateOf(false)
        private set
    private val pendingDraftJobs = mutableSetOf<Job>()
    private val draftPersistErrors = mutableStateMapOf<Int, String>()
    private var draftCursorTail: Job? = null

    private fun launchTrackedDraftWrite(block: suspend () -> Unit): Job {
        lateinit var job: Job
        job = viewModelScope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
            try {
                block()
            } finally {
                withContext(Dispatchers.Main.immediate) {
                    pendingDraftJobs.remove(job)
                    pendingDraftWrites = pendingDraftJobs.size
                }
            }
        }
        pendingDraftJobs += job
        pendingDraftWrites = pendingDraftJobs.size
        job.start()
        return job
    }

    private suspend fun awaitPendingDraftWrites() {
        DraftWriteAwaiter.awaitAll { pendingDraftJobs.toList() }
    }

    private suspend fun reloadDraftValidation(runId: String): CaptureDraftSnapshot? {
        val snapshot = repo.loadCaptureDraft(runId)
        draftStatus = snapshot?.status?.takeIf { it != "ACTIVE" }
        return snapshot
    }

    /** Cursor writes are chained in UI-call order so a late IO completion cannot restore stale nav. */
    private fun enqueueDraftCursorWrite(
        runId: String,
        side: Int,
        draftPhase: String,
        step: String,
        expectedTreeName: String = "",
        expectedTreeId: Int = 0,
    ) {
        val previous = draftCursorTail
        val job = launchTrackedDraftWrite {
            previous?.join()
            repo.updateCaptureDraftCursor(
                runId,
                side,
                draftPhase,
                step,
                expectedTreeName,
                expectedTreeId,
            )
        }
        draftCursorTail = job
    }

    // ── Orbbec live preview state ─────────────────────────────────────────────
    var orbbecAvailable by mutableStateOf(false)
        private set
    var orbbecPermissionGranted by mutableStateOf(false)
        private set
    var isOrbbecPreviewRunning by mutableStateOf(false)
        private set
    var isOrbbecStarting by mutableStateOf(false)
        private set
    var orbbecPreviewBitmap by mutableStateOf<ImageBitmap?>(null)
        private set
    var orbbecDepthBitmap by mutableStateOf<ImageBitmap?>(null)
        private set
    var orbbecStateMsg by mutableStateOf<String?>(null)
        private set

    init {
        initOrbbec()
    }

    private fun initOrbbec() {
        orbbec.onDeviceChange = { attached, _ ->
            orbbecAvailable = attached || orbbec.isAvailable()
            if (!attached && isOrbbecPreviewRunning) {
                isOrbbecPreviewRunning = false
                orbbecPreviewBitmap = null
                orbbecDepthBitmap = null
            }
        }
        orbbec.onState = { _, msg -> orbbecStateMsg = msg }
        orbbec.onFrame = { rgbB64, depthB64, _, _ ->
            rgbB64?.let { b64 ->
                runCatching {
                    val bytes = Base64.decode(b64, Base64.NO_WRAP)
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
                }.getOrNull()?.let { bmp -> orbbecPreviewBitmap = bmp }
            }
            depthB64?.let { b64 ->
                runCatching {
                    val bytes = Base64.decode(b64, Base64.NO_WRAP)
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
                }.getOrNull()?.let { bmp -> orbbecDepthBitmap = bmp }
            }
        }
        orbbec.start()
        orbbecAvailable = orbbec.isAvailable()
    }

    fun selectSource(src: CaptureSource) {
        if (src == captureSource) return
        if (captureSource == CaptureSource.ORBBEC) stopOrbbecPreview()
        captureSource = src
        inputCache.lastCaptureUsesOrbbec = src == CaptureSource.ORBBEC
    }

    fun refreshOrbbec() {
        viewModelScope.launch {
            orbbecStateMsg = null
            val found = orbbec.refresh()
            orbbecAvailable = found
        }
    }

    /**
     * Hard reset for the RGB-only / "unstable" lock (power-starved Orbbec on the Pad 8): clears
     * the in-memory degrade ladder and tears down + re-inits the SDK so depth can come back —
     * the in-app alternative to "Clear App Data", which would wipe the dataset. Recovery of the
     * *device* itself (vs a physical replug) is verified on hardware.
     */
    fun resetOrbbec() {
        viewModelScope.launch {
            orbbecStateMsg = null
            val found = orbbec.resetCameraState()
            orbbecAvailable = found
        }
    }

    fun requestOrbbecPermissionAndStart() {
        viewModelScope.launch {
            isOrbbecStarting = true
            orbbecStateMsg = null
            try {
                val granted = orbbec.requestPermission()
                orbbecPermissionGranted = granted
                if (granted) {
                    orbbec.startPreview()
                    isOrbbecPreviewRunning = true
                } else {
                    orbbecStateMsg = appContext.getString(R.string.orbbec_usb_denied)
                }
            } catch (e: Exception) {
                orbbecStateMsg = appContext.getString(R.string.orbbec_start_failed)
                Log.w("CaptureFlow", "Orbbec start failed", e)
            } finally {
                isOrbbecStarting = false
            }
        }
    }

    fun startOrbbecPreviewIfReady() {
        if (isOrbbecPreviewRunning || isOrbbecStarting || !orbbecAvailable) return
        viewModelScope.launch {
            isOrbbecStarting = true
            try {
                val granted = orbbec.requestPermission()
                orbbecPermissionGranted = granted
                if (granted) {
                    orbbec.startPreview()
                    isOrbbecPreviewRunning = true
                }
            } catch (e: Exception) {
                Log.w("CaptureFlow", "Orbbec auto-start failed", e)
            } finally {
                isOrbbecStarting = false
            }
        }
    }

    fun stopOrbbecPreview() {
        viewModelScope.launch {
            try { orbbec.stopPreview() } catch (_: Exception) {}
        }
        isOrbbecPreviewRunning = false
        orbbecPreviewBitmap = null
        orbbecDepthBitmap = null
    }

    fun captureOrbbecFrame(context: Context) {
        // Bind RGB + depth to the side showing when the shutter was pressed. Reading currentSide
        // after the blocking camera call could attach a valid pair to a different side.
        val capturedSideIndex = currentSide
        val runId = run?.sessionId ?: return
        val generation = repo.beginCaptureDraftSideWrite(runId, capturedSideIndex)
        draftPersistErrors.remove(capturedSideIndex)
        launchTrackedDraftWrite {
            try {
                val frame = orbbec.capture()
                val depth = frame.depth
                if (depth == null) {
                    Log.w("CaptureFlow", "Orbbec capture rejected for side $capturedSideIndex: Y16 depth missing")
                    withContext(Dispatchers.Main) {
                        if (repo.isCaptureDraftSideWriteCurrent(runId, capturedSideIndex, generation)) {
                            captureError = appContext.getString(R.string.capture_orbbec_depth_required)
                        }
                    }
                    return@launchTrackedDraftWrite
                }
                val colorBytes = Base64.decode(frame.base64, Base64.NO_WRAP)
                val rawBytes = Base64.decode(depth.base64, Base64.NO_WRAP)
                val meta = JSONObject().apply {
                    put("schemaVersion", 2); put("width", depth.width); put("height", depth.height)
                    put("format", depth.format); put("valueScale", depth.valueScale)
                    put("encoding", depth.encoding); put("unit", depth.unit)
                    put("decodeFormula", "depth_mm = uint16_le * valueScale"); put("invalidValue", 0)
                    put("alignedTo", depth.alignedTo); put("rgbWidth", frame.width); put("rgbHeight", frame.height)
                    put("rawSha256", DepthArtifactContract.sha256Hex(rawBytes))
                    put("rgbSha256", DepthArtifactContract.sha256Hex(colorBytes))
                    put("displayFloorMm", depth.displayFloorMm); put("displayCeilingMm", depth.displayCeilingMm)
                    depth.calibrationRawB64?.let { put("calibrationRawB64", it) }
                    depth.calibrationDump?.let { put("calibrationDump", it) }
                }.toString()
                val accepted = repo.persistCaptureDraftSide(
                    runId = runId,
                    sideIndex = capturedSideIndex,
                    generation = generation,
                    imageBytes = colorBytes,
                    imageWidth = frame.width,
                    imageHeight = frame.height,
                    captureOrigin = CaptureOrigin.ORBBEC,
                    depthRequired = true,
                    depthRawBytes = rawBytes,
                    depthJsonText = meta,
                )
                val refreshedDraft = if (accepted) repo.loadCaptureDraft(runId) else null
                val reviewCursorPhase: String? = withContext(Dispatchers.Main) {
                    // The draft image path is shared across retakes. Re-check the generation on
                    // Main immediately before every UI publication so an accepted-but-now-stale
                    // callback cannot pair a newer RGB URI with this frame's depth/source.
                    if (!accepted || !repo.isCaptureDraftSideWriteCurrent(runId, capturedSideIndex, generation)) {
                        return@withContext null
                    }
                    if (capturedSideIndex in capturedImages.indices) {
                        draftStatus = refreshedDraft?.status?.takeIf { it != "ACTIVE" }
                        capturedDepths[capturedSideIndex] = depth
                        capturedImages[capturedSideIndex] = storage.versionedImageUri(
                            storage.captureDraftImageFile(runId, capturedSideIndex),
                            DepthArtifactContract.sha256Hex(colorBytes),
                        )
                        capturedSources[capturedSideIndex] = CaptureSource.ORBBEC
                        captureError = null
                        if (currentSide == capturedSideIndex) {
                            currentStep = SideStep.REVIEW
                            phase.name
                        } else {
                            null
                        }
                    } else {
                        null
                    }
                }
                reviewCursorPhase?.let { cursorPhase ->
                    withContext(Dispatchers.Main) {
                        enqueueDraftCursorWrite(
                            runId,
                            capturedSideIndex,
                            cursorPhase,
                            SideStep.REVIEW.name,
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e("CaptureFlow", "Orbbec capture failed", e)
                withContext(Dispatchers.Main) {
                    if (!repo.isCaptureDraftSideWriteCurrent(runId, capturedSideIndex, generation)) {
                        return@withContext
                    }
                    val message = appContext.getString(R.string.orbbec_capture_failed, e.message ?: "")
                    orbbecStateMsg = message
                    draftPersistErrors[capturedSideIndex] = message
                    captureError = message
                    // Keep the captured URI/source visible. The operator can inspect or retake it;
                    // save remains gated until the failed draft write is explicitly resolved.
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        // viewModelScope is ALREADY cancelled by the time onCleared runs, so a coroutine
        // launched on it never executes — the Orbbec stream pump would keep running after the
        // capture screen is gone (USB/power drain; a step toward the Pad 8 lock). Stop it on an
        // independent scope that outlives this ViewModel.
        kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
            try { orbbec.close() } catch (_: Exception) {}
        }
        // Drop our callbacks from the SINGLETON OrbbecManager so it stops holding this
        // now-dead ViewModel — the app-lifetime USB hotplug receiver would otherwise keep
        // invoking them and mutating discarded Compose state (and leak the ViewModel).
        orbbec.onFrame = null
        orbbec.onDeviceChange = null
        orbbec.onState = null
    }

    // ── Standard capture logic ────────────────────────────────────────────────

    fun dismissQa() { showQaDialog = false }
    fun dismissCaptureError() { captureError = null }
    fun discardDraft() {
        run?.sessionId?.let { runId ->
            viewModelScope.launch {
                runCatching { repo.discardCaptureDraft(runId) }
                    .onSuccess {
                        capturedImages.indices.forEach { capturedImages[it] = null }
                        capturedDepths.indices.forEach { capturedDepths[it] = null }
                        capturedSources.indices.forEach { capturedSources[it] = null }
                        currentSide = 0
                        currentStep = SideStep.PREVIEW
                        phase = CapturePhase.SIDES
                        retakingFromReview = false
                        draftStatus = null
                        saveError = null
                        draftPersistErrors.clear()
                    }
                    .onFailure { error ->
                        saveError = error.message ?: "Capture draft could not be discarded"
                    }
            }
        }
    }

    fun requestSave(runId: String, context: Context, onDone: (String) -> Unit) {
        val r = run ?: return
        if (isSaving || isDraftValidating) return
        isDraftValidating = true
        viewModelScope.launch {
            try {
                awaitPendingDraftWrites()
                val draft = reloadDraftValidation(runId)
                if (draft == null || draft.status == "INVALID") {
                    saveError = "Capture draft is invalid; discard it explicitly before saving"
                    return@launch
                }
                draftPersistErrors.values.firstOrNull()?.let { failure ->
                    saveError = failure
                    return@launch
                }
                val capturedCount = capturedImages.count { it != null }
                val depthSides = capturedDepths.count { it != null }
                val orbbecSides = capturedSources.count { it == CaptureSource.ORBBEC }
                val missingOrbbecDepth = capturedImages.indices.firstOrNull { index ->
                    capturedImages[index] != null &&
                        capturedSources.getOrNull(index) == CaptureSource.ORBBEC &&
                        capturedDepths.getOrNull(index) == null
                }
                if (missingOrbbecDepth != null) {
                    saveError = appContext.getString(
                        R.string.capture_orbbec_pair_missing_side,
                        missingOrbbecDepth + 1,
                    )
                    return@launch
                }
                // WS-13: re-judge the fix here so the operator's GPS line, this QA gate and the
                // record that gets committed all describe the same instant. `hasGps` keeps its
                // historical meaning — "a coordinate was obtained at all" — deliberately: the
                // freshness window is 60 s and one tree takes minutes, so gating the warning on
                // FRESH would raise a blocking dialog on every single save without adding any
                // information the sidecar's gps.status does not already carry.
                val judgedGps = rejudgeGps()
                val hasGps = judgedGps.recordedCoordinates != null
                val report = QualityCheck.analyzeCaptureShots(
                    capturedSides = capturedCount,
                    expectedSides = sideCount,
                    depthSides = depthSides,
                    requiredDepthSides = orbbecSides,
                    hasGps = hasGps,
                    hasVariety = r.variety.isNotBlank(),
                    hasBlock = r.block.isNotBlank(),
                )
                if (report.status == QualityCheck.Level.ERROR || report.status == QualityCheck.Level.WARN) {
                    qaReport = report
                    showQaDialog = true
                } else {
                    save(runId, context, onDone)
                }
            } catch (error: Exception) {
                saveError = error.message ?: "Capture draft validation failed"
            } finally {
                isDraftValidating = false
            }
        }
    }

    fun saveIgnoringQa(runId: String, context: Context, onDone: (String) -> Unit) {
        showQaDialog = false
        save(runId, context, onDone)
    }

    fun load(runId: String) {
        if (run?.sessionId == runId && capturedImages.size == sideCount) {
            refreshGps()
            return
        }
        viewModelScope.launch {
            val r = repo.getRun(runId) ?: return@launch
            run = r
            sideCount = r.sideCount
            manualId = r.nextId.toString()
            capturedImages.clear(); capturedDepths.clear(); capturedSources.clear()
            repeat(sideCount) { capturedImages.add(null); capturedDepths.add(null); capturedSources.add(null) }
            currentSide = 0; currentStep = SideStep.PREVIEW; phase = CapturePhase.SIDES
            val expectedName = CaptureSetPolicy.treeName(r.variety, r.block, r.nameToken, r.nextId)
            val draft = repo.ensureCaptureDraft(runId, sideCount, expectedName, r.nextId)
            if (!r.autoId && draft.expectedTreeId > 0) manualId = draft.expectedTreeId.toString()
            draftStatus = draft.status.takeIf { it != "ACTIVE" }
            val restoredDepths = withContext(Dispatchers.IO) {
                draft.sides.associate { side -> side.sideIndex to loadDraftDepth(side) }
            }
            draft.sides.forEach { side ->
                if (side.sideIndex !in capturedImages.indices) return@forEach
                capturedImages[side.sideIndex] = storage.versionedImageUri(
                    File(side.imagePath),
                    side.imageSha256,
                )
                capturedSources[side.sideIndex] = when (CaptureOrigin.fromPersisted(side.captureOrigin)) {
                    CaptureOrigin.ORBBEC -> CaptureSource.ORBBEC
                    else -> CaptureSource.PHONE_CAMERA
                }
                capturedDepths[side.sideIndex] = restoredDepths[side.sideIndex]
            }
            currentSide = draft.currentSide.coerceIn(0, sideCount - 1)
            phase = runCatching { CapturePhase.valueOf(draft.phase) }.getOrDefault(CapturePhase.SIDES)
            currentStep = runCatching {
                SideStep.valueOf(
                    CaptureDraftCursorPolicy.restoreStep(
                        draft.step,
                        capturedImages[currentSide] != null,
                    )
                )
            }.getOrDefault(
                if (capturedImages[currentSide] != null) SideStep.REVIEW else SideStep.PREVIEW,
            )
            refreshGps()
        }
    }

    private fun loadDraftDepth(side: CaptureDraftSideSnapshot): OrbbecManager.OrbbecDepthData? {
        val rawPath = side.depthRawPath ?: return null
        val jsonPath = side.depthJsonPath ?: return null
        return runCatching {
            val raw = File(rawPath).readBytes()
            val json = JSONObject(File(jsonPath).readText())
            OrbbecManager.OrbbecDepthData(
                base64 = Base64.encodeToString(raw, Base64.NO_WRAP),
                width = json.optInt("width"), height = json.optInt("height"),
                format = json.optString("format", "Y16"), valueScale = json.optDouble("valueScale", 1.0).toFloat(),
                encoding = json.optString("encoding", "uint16le"), unit = json.optString("unit", "mm"),
                alignedTo = json.optString("alignedTo", "color"),
                displayFloorMm = json.optDouble("displayFloorMm", 250.0).toFloat(),
                displayCeilingMm = json.optDouble("displayCeilingMm", 7000.0).toFloat(),
                calibrationRawB64 = json.optString("calibrationRawB64").takeIf { it.isNotBlank() },
                calibrationDump = json.optString("calibrationDump").takeIf { it.isNotBlank() },
            )
        }.getOrNull()
    }

    /**
     * Fetch GPS and publish an actionable status. Safe to call repeatedly — it is invoked once
     * on load() and again once the location permission dialog resolves (the dialog is requested
     * concurrently with load(), so the first fetch usually runs *before* the user taps Allow and
     * would otherwise leave a stale "permission denied" for the whole session).
     */
    fun refreshGps() {
        viewModelScope.launch {
            val resolved = runCatching { gps.bestProvenance() }
                .getOrElse { GpsProvenance.unavailable(GpsStatus.UNAVAILABLE) }
            // Unconditional replacement — see the gpsProvenance KDoc. A failure must CLEAR the
            // previous fix, not fall through and leave it in place for the next tree.
            gpsProvenance = resolved
            gpsStatus = describeGps(resolved)
        }
    }

    /**
     * Re-judge the stored fix against [nowMillis], publish any downgrade to the on-screen GPS
     * line, and return the judged record.
     *
     * The GPS is read once, when the capture screen opens. Four or eight photos later the fix can
     * have aged out. Without this, the QA gate would test the status from screen-open while the
     * commit wrote a different one — the operator would read a coordinate that looks live while a
     * STALE record was persisted. Downgrade-only: [GpsFreshnessPolicy.recheckAtCommit] can never
     * turn STALE back into FRESH.
     */
    private fun rejudgeGps(nowMillis: Long = System.currentTimeMillis()): GpsProvenance {
        val judged = GpsFreshnessPolicy.recheckAtCommit(gpsProvenance, nowMillis)
        if (judged != gpsProvenance) {
            gpsProvenance = judged
            gpsStatus = describeGps(judged)
        }
        return judged
    }

    /** Operator-facing GPS line. Stale is spelled out; it never looks like a live reading. */
    private fun describeGps(p: GpsProvenance): String {
        // Locale.US so the decimal stays a dot — the default (Indonesian) locale uses a comma,
        // rendering "-3,44941, 114,84279" which reads as four numbers.
        val coords = if (p.latitude != null && p.longitude != null) {
            String.format(java.util.Locale.US, "%.5f, %.5f", p.latitude, p.longitude)
        } else null
        return when (p.status) {
            GpsStatus.FRESH -> coords ?: appContext.getString(R.string.capture_gps_unavailable)
            GpsStatus.STALE -> {
                val minutes = ((p.ageMs ?: 0L) / 60_000L).coerceAtLeast(1L)
                appContext.getString(R.string.capture_gps_stale, coords.orEmpty(), minutes)
            }
            GpsStatus.PERMISSION_DENIED ->
                appContext.getString(R.string.capture_gps_no_permission)
            GpsStatus.LOCATION_OFF -> appContext.getString(R.string.capture_gps_off)
            GpsStatus.UNAVAILABLE, GpsStatus.UNKNOWN ->
                appContext.getString(R.string.capture_gps_unavailable)
        }
    }

    fun onImageCaptured(uri: Uri) {
        val sideIndex = currentSide
        val runId = run?.sessionId ?: return
        if (sideIndex !in capturedImages.indices) return
        val generation = repo.beginCaptureDraftSideWrite(runId, sideIndex)
        draftPersistErrors.remove(sideIndex)
        capturedImages[sideIndex] = uri
        capturedDepths[sideIndex] = null
        capturedSources[sideIndex] = CaptureSource.PHONE_CAMERA
        captureError = null
        currentStep = SideStep.REVIEW
        launchTrackedDraftWrite {
            try {
                val sourceBytes = readBytes(appContext, uri)
                val bytes = JpegOrientationNormalizer.normalize(sourceBytes)
                val dims = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, dims)
                check(dims.outWidth > 0 && dims.outHeight > 0) { "Captured file has zero dimensions" }
                val accepted = repo.persistCaptureDraftSide(
                    runId, sideIndex, generation, bytes, dims.outWidth, dims.outHeight,
                    CaptureOrigin.PHONE_CAMERA, depthRequired = false,
                )
                val refreshedDraft = if (accepted) repo.loadCaptureDraft(runId) else null
                val reviewCursor = withContext(Dispatchers.Main) {
                    if (!accepted || !repo.isCaptureDraftSideWriteCurrent(runId, sideIndex, generation)) {
                        return@withContext null
                    }
                    draftStatus = refreshedDraft?.status?.takeIf { it != "ACTIVE" }
                    capturedImages[sideIndex] = storage.versionedImageUri(
                        storage.captureDraftImageFile(runId, sideIndex),
                        DepthArtifactContract.sha256Hex(bytes),
                    )
                    if (currentSide == sideIndex) phase.name else null
                }
                reviewCursor?.let { cursorPhase ->
                    withContext(Dispatchers.Main) {
                        enqueueDraftCursorWrite(
                            runId,
                            sideIndex,
                            cursorPhase,
                            SideStep.REVIEW.name,
                        )
                    }
                }
            } catch (error: Exception) {
                withContext(Dispatchers.Main) {
                    if (!repo.isCaptureDraftSideWriteCurrent(runId, sideIndex, generation)) {
                        return@withContext
                    }
                    val message = appContext.getString(R.string.capture_failed, error.message ?: "")
                    draftPersistErrors[sideIndex] = message
                    captureError = message
                    // Keep the operator's captured image visible for inspection or retake.
                }
            }
        }
    }

    fun goToSide(index: Int) {
        if (index in 0 until sideCount) {
            currentSide = index
            currentStep = if (capturedImages[index] != null) SideStep.REVIEW else SideStep.PREVIEW
            persistDraftCursor()
        }
    }

    fun retakeCurrent() {
        if (currentSide < capturedImages.size) {
            val runId = run?.sessionId
            capturedImages[currentSide] = null
            capturedDepths.getOrNull(currentSide)?.let { capturedDepths[currentSide] = null }
            if (currentSide < capturedSources.size) capturedSources[currentSide] = null
            if (runId != null) {
                val generation = repo.invalidateCaptureDraftSide(runId, currentSide)
                viewModelScope.launch { repo.removeCaptureDraftSide(runId, currentSide, generation) }
            }
        }
        currentStep = SideStep.PREVIEW
        persistDraftCursor()
    }

    fun continueFromReview(): Boolean {
        val result = if (currentSide < sideCount - 1) {
            currentSide++; currentStep = SideStep.PREVIEW; false
        } else {
            if (allCaptured) phase = CapturePhase.REVIEW_ALL
            true
        }
        persistDraftCursor()
        return result
    }

    fun retakeSide(index: Int) {
        if (index in 0 until capturedImages.size) {
            capturedImages[index] = null
            if (index < capturedDepths.size) capturedDepths[index] = null
            if (index < capturedSources.size) capturedSources[index] = null
            run?.sessionId?.let { runId ->
                val generation = repo.invalidateCaptureDraftSide(runId, index)
                viewModelScope.launch { repo.removeCaptureDraftSide(runId, index, generation) }
            }
            currentSide = index; currentStep = SideStep.PREVIEW; phase = CapturePhase.SIDES
            retakingFromReview = true
            persistDraftCursor()
        }
    }

    fun returnToReviewAll() {
        retakingFromReview = false
        if (allCaptured) { phase = CapturePhase.REVIEW_ALL; currentStep = SideStep.REVIEW }
        persistDraftCursor()
    }

    private fun persistDraftCursor(expectedTreeName: String = "", expectedTreeId: Int = 0) {
        run?.sessionId?.let { runId ->
            val side = currentSide
            val draftPhase = phase.name
            val step = currentStep.name
            enqueueDraftCursorWrite(runId, side, draftPhase, step, expectedTreeName, expectedTreeId)
        }
    }

    val allCaptured: Boolean get() = capturedImages.isNotEmpty() && capturedImages.all { it != null }

    // The former `safe`/`safeBlock` helpers moved into CaptureSetPolicy.treeName so the draft
    // cursor and the commit derive the name from ONE implementation. The character rules are
    // reproduced there byte-for-byte, so a run with no naming token still produces the exact
    // names already on disk.

    private fun save(runId: String, context: Context, onDone: (String) -> Unit) {
        val r = run ?: return
        // Latch synchronously on the main thread. Waiting until the launched coroutine starts
        // leaves a small double-tap window where two saves can race on the same .tmp filenames.
        if (isSaving) return
        isSaving = true
        saveError = null
        viewModelScope.launch {
            var stagingDir: File? = null
            try {
                awaitPendingDraftWrites()
                val validatedDraft = reloadDraftValidation(runId)
                if (validatedDraft == null || validatedDraft.status == "INVALID") {
                    saveError = "Capture draft is invalid; discard it explicitly before saving"
                    return@launch
                }
                draftPersistErrors.values.firstOrNull()?.let { failure ->
                    saveError = failure
                    return@launch
                }
                val treeId = if (r.autoId) r.nextId else (manualId.toIntOrNull() ?: r.nextId).coerceAtLeast(1)
                val treeName = CaptureSetPolicy.treeName(r.variety, r.block, r.nameToken, treeId)
                repo.updateCaptureDraftCursor(runId, currentSide, phase.name, currentStep.name, treeName, treeId)

                // Never overwrite a committed tree in-place. The operator must explicitly delete
                // the old tree first; until then its RGB/depth/annotations remain one intact unit.
                if (repo.treeNameExists(treeName)) {
                    saveError = appContext.getString(R.string.capture_tree_name_exists, treeName)
                    return@launch
                }

                val sides = withContext(Dispatchers.IO) {
                    val stage = storage.captureStagingDir(UUID.randomUUID().toString())
                    stagingDir = stage
                    val allSides = mutableListOf<TreeSide>()
                    capturedImages.forEachIndexed { index, uri ->
                        if (uri == null) return@forEachIndexed
                        val stagedImage = storage.stagedImageFile(stage, treeName, index)
                        val source = capturedSources.getOrNull(index)
                        val capturedBytes = try {
                            readBytes(context, uri)
                        } catch (e: Exception) {
                            Log.e("CaptureFlow", "Failed to read captured image for side $index", e)
                            null
                        }
                        if (capturedBytes == null) {
                            throw IllegalStateException(
                                "Side ${index + 1}: captured image could not be read",
                            )
                        }
                        // CameraX commonly stores landscape sensor pixels plus an EXIF rotation.
                        // Coil honors EXIF in the capture review, but BitmapFactory/detector/YOLO do
                        // not. Materialize that transform once before hashing or measuring so every
                        // downstream consumer sees the same canonical pixel coordinate system.
                        val bytes = if (source == CaptureSource.PHONE_CAMERA) {
                            JpegOrientationNormalizer.normalize(capturedBytes)
                        } else {
                            capturedBytes
                        }
                        val depth = capturedDepths.getOrNull(index)
                        if (source == CaptureSource.ORBBEC && depth == null) {
                            throw IllegalStateException(
                                "Side ${index + 1}: Orbbec RGB capture is missing required Y16 depth",
                            )
                        }
                        storage.writeBytes(stagedImage, bytes)
                        val dims = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeFile(stagedImage.path, dims)
                        if (dims.outWidth <= 0 || dims.outHeight <= 0) {
                            throw IllegalStateException("Side ${index + 1}: captured file has zero dimensions")
                        }

                        // Depth sidecar is a single fail-closed artifact. A tree must never be
                        // accepted with only half of the decoder pair or with stale depth from a
                        // previous manual-ID re-shoot.
                        if (depth != null) {
                            try {
                                val rawBytes = Base64.decode(depth.base64, Base64.NO_WRAP)
                                val meta = JSONObject().apply {
                                    put("schemaVersion", 2)
                                    put("width", depth.width)
                                    put("height", depth.height)
                                    put("format", depth.format)
                                    put("valueScale", depth.valueScale)
                                    put("encoding", depth.encoding)
                                    put("unit", depth.unit)
                                    put("decodeFormula", "depth_mm = uint16_le * valueScale")
                                    put("invalidValue", 0)
                                    put("alignedTo", depth.alignedTo)
                                    put("rgbWidth", dims.outWidth)
                                    put("rgbHeight", dims.outHeight)
                                    // Bind all three artifacts. A same-size stale raw/RGB file is
                                    // now detectable even when its filename and dimensions match.
                                    put("rawSha256", DepthArtifactContract.sha256Hex(rawBytes))
                                    put("rgbSha256", DepthArtifactContract.sha256Hex(bytes))
                                    put("displayFloorMm", depth.displayFloorMm)
                                    put("displayCeilingMm", depth.displayCeilingMm)
                                    // H-03: RGB-D calibration for offline reprojection / alignment
                                    // audit. calibrationRawB64 is the lossless Orbbec CameraParam
                                    // byte blob; calibrationDump is the SDK's readable form. Both
                                    // absent when depth calibration was unavailable.
                                    depth.calibrationRawB64?.let { put("calibrationRawB64", it) }
                                    depth.calibrationDump?.let { put("calibrationDump", it) }
                                    if (depth.calibrationRawB64 != null || depth.calibrationDump != null) {
                                        put("calibration", JSONObject().apply {
                                            depth.calibrationRawB64?.let { put("cameraParamRawB64", it) }
                                            depth.calibrationDump?.let { put("cameraParamDump", it) }
                                        })
                                    }
                                }
                                storage.writeBytes(
                                    storage.stagedDepthRawFile(stage, treeName, index),
                                    rawBytes,
                                )
                                storage.writeText(
                                    storage.stagedDepthJsonFile(stage, treeName, index),
                                    meta.toString(),
                                )
                            } catch (e: Exception) {
                                throw IllegalStateException(
                                    "Side ${index + 1}: depth could not be staged safely",
                                    e,
                                )
                            }
                        }

                        val rgbSha256 = DepthArtifactContract.sha256Hex(bytes)
                        val origin = when (source) {
                            CaptureSource.ORBBEC -> CaptureOrigin.ORBBEC
                            CaptureSource.PHONE_CAMERA -> CaptureOrigin.PHONE_CAMERA
                            null -> CaptureOrigin.UNKNOWN
                        }
                        allSides.add(
                            TreeSide(
                                sideIndex = index,
                                label = "Side ${index + 1}",
                                imageUri = storage.versionedImageUri(
                                    storage.imageFile(treeName, index),
                                    bytes,
                                ),
                                labelUri = null,
                                imageWidth = dims.outWidth,
                                imageHeight = dims.outHeight,
                                bboxes = emptyList(),
                                originalBboxes = emptyList(),
                                rgbSha256 = rgbSha256,
                                captureOrigin = origin,
                                depthRequired = source == CaptureSource.ORBBEC,
                            )
                        )
                    }
                    allSides
                }
                if (sides.isEmpty()) {
                    saveError = appContext.getString(R.string.capture_no_images)
                    return@launch
                }

                val safTreeUri = exportFolder.folderUri.first()

                val treeKey = repo.commitTreePackage(
                    sessionId = runId,
                    treeName = treeName,
                    treeId = treeId,
                    split = "field",
                    sides = sides,
                    metadata = TreeMetadata(
                        variety = r.variety,
                        block = r.block,
                        treeId = treeId.toString(),
                        // WS-13: the capture day, recorded once here. Downstream never
                        // back-fills it, so a re-export years later still says today.
                        date = PackageProvenanceCodec.captureDate(System.currentTimeMillis()),
                        // WS-13: judged against the COMMIT instant, not the instant the screen
                        // opened. Four or eight photos can take longer than the freshness
                        // window, and a fix that aged out during them is not a fresh fix.
                        // rejudgeGps() also updates the on-screen line, so what the operator
                        // reads and what lands in the sidecar can never disagree.
                        gps = rejudgeGps(),
                        operatorName = r.operatorName,
                        identity = CaptureSetIdentity(
                            r.captureSetId,
                            r.deviceToken,
                            r.nameToken,
                        ),
                    ),
                    stagingDir = requireNotNull(stagingDir),
                    requiredDepthSides = sides
                        .filter { it.depthRequired }
                        .mapTo(mutableSetOf()) { it.sideIndex },
                    safTreeUri = safTreeUri,
                )
                onDone(treeKey)
            } catch (e: Exception) {
                Log.e("CaptureFlow", "Failed to save tree", e)
                saveError = appContext.getString(R.string.capture_save_failed)
            } finally {
                stagingDir?.let { stage ->
                    withContext(Dispatchers.IO) {
                        if (!storage.deleteCaptureStaging(stage)) {
                            Log.w("CaptureFlow", "Could not clean staging directory ${stage.path}")
                        }
                    }
                }
                isSaving = false
            }
        }
    }

    private fun readBytes(context: Context, uri: Uri): ByteArray {
        return when (uri.scheme?.lowercase(Locale.US)) {
            "file" -> {
                val file = uri.path?.let { File(it) } ?: throw IOException("Invalid file URI")
                FileInputStream(file).use { it.readBytes() }
            }
            else -> context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: throw IOException("Could not open input stream for $uri")
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CaptureFlowScreen(
    sessionId: String,
    onTreeSaved: (String) -> Unit,
    onCancel: () -> Unit,
    viewModel: CaptureFlowViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val toasts = LocalToasts.current
    var hasCameraPermission by remember { mutableStateOf(false) }
    // Request camera + location together. Android shows the dialogs sequentially
    // (camera first, then GPS), so location no longer needs manual enabling in Settings.
    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        hasCameraPermission = results[Manifest.permission.CAMERA] == true
        // Location is requested in the same batch; re-read GPS once the user responds so a
        // first-run "permission denied / unavailable" status corrects itself after they Allow.
        val locationGranted = results[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            results[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (locationGranted) viewModel.refreshGps()
    }

    LaunchedEffect(sessionId) {
        viewModel.load(sessionId)
        permLauncher.launch(
            arrayOf(
                Manifest.permission.CAMERA,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            )
        )
    }

    // Auto-start Orbbec preview when switching to Orbbec source
    LaunchedEffect(viewModel.captureSource, viewModel.orbbecAvailable) {
        if (viewModel.captureSource == CaptureSource.ORBBEC && viewModel.orbbecAvailable) {
            viewModel.startOrbbecPreviewIfReady()
        }
    }

    LaunchedEffect(viewModel.captureError) {
        viewModel.captureError?.let { message ->
            toasts.error(message)
            viewModel.dismissCaptureError()
        }
    }

    val run = viewModel.run

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.capture_title, viewModel.currentSide + 1, viewModel.sideCount)) },
                navigationIcon = {
                    IconButton(onClick = {
                        viewModel.stopOrbbecPreview()
                        onCancel()
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_cancel))
                    }
                },
                actions = {
                    val isOrbbec = viewModel.captureSource == CaptureSource.ORBBEC
                    FilterChip(
                        selected = isOrbbec,
                        onClick = { viewModel.selectSource(if (isOrbbec) CaptureSource.PHONE_CAMERA else CaptureSource.ORBBEC) },
                        label = { Text(stringResource(if (isOrbbec) R.string.capture_source_orbbec else R.string.capture_source_phone), style = MaterialTheme.typography.labelLarge) },
                        leadingIcon = { Icon(if (isOrbbec) Icons.Default.Usb else Icons.Default.CameraAlt, null, Modifier.size(18.dp)) },
                        modifier = Modifier.heightIn(min = 40.dp).padding(end = 4.dp),
                    )
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 6.dp, vertical = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (run == null) {
                CircularProgressIndicator()
                return@Column
            }

            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Lock, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(6.dp))
                    Column {
                        Text(stringResource(R.string.capture_locked_format, run.variety, run.block), style = MaterialTheme.typography.titleSmall)
                        Text(
                            viewModel.gpsStatus ?: stringResource(R.string.capture_locating),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        viewModel.draftStatus?.let { status ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "Capture draft $status — review or discard before saving",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                                TextButton(onClick = viewModel::discardDraft) { Text("Discard") }
                            }
                        }
                    }
                }
                if (!run.autoId) {
                    OutlinedTextField(
                        value = viewModel.manualId,
                        onValueChange = { viewModel.manualId = it.filter { c -> c.isDigit() } },
                        label = { Text(stringResource(R.string.capture_tree_id)) },
                        singleLine = true,
                        modifier = Modifier.width(110.dp),
                    )
                }
            }
            Spacer(Modifier.height(6.dp))

            if (hasCameraPermission) {
                if (viewModel.phase == CapturePhase.REVIEW_ALL) {
                    viewModel.saveError?.let { err ->
                        Text(
                            text = stringResource(R.string.capture_save_error, err),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                    ReviewAllPager(
                        sideCount = viewModel.sideCount,
                        capturedImages = viewModel.capturedImages,
                        isSaving = viewModel.isSaving,
                        isDraftPersisting = viewModel.pendingDraftWrites > 0,
                        isDraftValidating = viewModel.isDraftValidating,
                        onRetake = { viewModel.retakeSide(it) },
                        onSave = { viewModel.requestSave(sessionId, context, onTreeSaved) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                    )
                } else {
                    CapturedThumbnails(
                        sideCount = viewModel.sideCount,
                        currentSide = viewModel.currentSide,
                        capturedImages = viewModel.capturedImages,
                        onSelect = { viewModel.goToSide(it) },
                    )
                    Spacer(Modifier.height(4.dp))

                    viewModel.saveError?.let { err ->
                        Text(
                            text = stringResource(R.string.capture_save_error, err),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(8.dp))
                    }

                    val onSideContinue: () -> Unit = {
                        if (viewModel.retakingFromReview) viewModel.returnToReviewAll()
                        else viewModel.continueFromReview()
                    }
                    val sideContinueLabel = if (viewModel.retakingFromReview) stringResource(R.string.action_done) else null

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .clip(RoundedCornerShape(16.dp))
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp)),
                    ) {
                        if (viewModel.captureSource == CaptureSource.ORBBEC) {
                            OrbbecCaptureStage(
                                isAvailable = viewModel.orbbecAvailable,
                                permissionGranted = viewModel.orbbecPermissionGranted,
                                isPreviewRunning = viewModel.isOrbbecPreviewRunning,
                                isStarting = viewModel.isOrbbecStarting,
                                previewBitmap = viewModel.orbbecPreviewBitmap,
                                depthBitmap = viewModel.orbbecDepthBitmap,
                                stateMsg = viewModel.orbbecStateMsg,
                                currentStep = viewModel.currentStep,
                                capturedUri = viewModel.capturedImages.getOrNull(viewModel.currentSide),
                                isLastSide = viewModel.currentSide == viewModel.sideCount - 1,
                                allCaptured = viewModel.allCaptured,
                                isSaving = viewModel.isSaving,
                                continueLabel = sideContinueLabel,
                                onRequestPermission = { viewModel.requestOrbbecPermissionAndStart() },
                                onRefresh = { viewModel.refreshOrbbec() },
                                onReset = { viewModel.resetOrbbec() },
                                onCapture = { viewModel.captureOrbbecFrame(context) },
                                onRetake = { viewModel.retakeCurrent() },
                                onContinue = onSideContinue,
                            )
                        } else {
                            when (viewModel.currentStep) {
                                SideStep.PREVIEW -> {
                                    CameraCaptureStage(
                                        onCaptured = {
                                            val side = viewModel.currentSide + 1
                                            viewModel.onImageCaptured(it)
                                            toasts.info(context.getString(R.string.capture_side_captured, side))
                                        },
                                    )
                                }
                                SideStep.REVIEW -> {
                                    CapturedReviewStage(
                                        uri = viewModel.capturedImages[viewModel.currentSide],
                                        isLastSide = viewModel.currentSide == viewModel.sideCount - 1,
                                        allCaptured = viewModel.allCaptured,
                                        isSaving = viewModel.isSaving,
                                        continueLabel = sideContinueLabel,
                                        onRetake = { viewModel.retakeCurrent() },
                                        onContinue = onSideContinue,
                                    )
                                }
                            }
                        }
                    }

                    Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        repeat(viewModel.sideCount) { i ->
                            val captured = viewModel.capturedImages.getOrNull(i) != null
                            val current = i == viewModel.currentSide
                            Box(
                                modifier = Modifier
                                    .size(if (current) 14.dp else 10.dp)
                                    .clip(CircleShape)
                                    .background(
                                        when {
                                            captured -> MaterialTheme.colorScheme.primary
                                            current -> MaterialTheme.colorScheme.outline
                                            else -> MaterialTheme.colorScheme.outlineVariant
                                        },
                                    )
                                    .border(
                                        width = if (current) 2.dp else 0.dp,
                                        color = if (current) MaterialTheme.colorScheme.onSurface else Color.Transparent,
                                        shape = CircleShape,
                                    ),
                            )
                        }
                    }
                }
            } else {
                Text(stringResource(R.string.capture_permission_required))
            }
        }
    }

    viewModel.qaReport?.let { report ->
        if (viewModel.showQaDialog) {
            QualityGateModal(
                issues = report.issues,
                onContinue = { viewModel.saveIgnoringQa(sessionId, context, onTreeSaved) },
                onBack = { viewModel.dismissQa() },
            )
        }
    }
}

@Composable
private fun CapturedThumbnails(
    sideCount: Int,
    currentSide: Int,
    capturedImages: List<Uri?>,
    onSelect: (Int) -> Unit,
) {
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        itemsIndexed(List(sideCount) { it }) { index, _ ->
            val uri = capturedImages.getOrNull(index)
            val selected = index == currentSide
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .border(
                        width = if (selected) 3.dp else 1.dp,
                        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                        shape = RoundedCornerShape(8.dp),
                    )
                    .clickable(enabled = uri != null) { onSelect(index) }
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                if (uri != null) {
                    Image(
                        painter = rememberAsyncImagePainter(uri),
                        contentDescription = "Side ${index + 1}",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Text(
                        "${index + 1}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun CapturedReviewStage(
    uri: Uri?,
    isLastSide: Boolean,
    allCaptured: Boolean,
    isSaving: Boolean,
    continueLabel: String? = null,
    onRetake: () -> Unit,
    onContinue: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        if (uri != null) {
            Image(
                painter = rememberAsyncImagePainter(uri),
                contentDescription = "Captured side",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        }

        CapturedBadge(Modifier.padding(16.dp).align(Alignment.TopEnd))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, Color.Black.copy(alpha = 0.6f))
                    )
                )
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedButton(
                onClick = onRetake,
                modifier = Modifier.weight(1f).height(48.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
            ) { Text(stringResource(R.string.action_retake)) }

            Button(
                onClick = onContinue,
                modifier = Modifier.weight(1f).height(48.dp),
                enabled = !isSaving && (if (isLastSide) allCaptured else true),
            ) {
                if (isSaving) {
                    CircularProgressIndicator(Modifier.size(20.dp), color = MaterialTheme.colorScheme.onPrimary)
                } else {
                    Text(continueLabel ?: stringResource(if (isLastSide) R.string.capture_review_all else R.string.action_continue))
                }
            }
        }
    }
}

/** Tokenized "Captured" pill (icon + label on the success color) reused by both review stages. */
@Composable
private fun CapturedBadge(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(PalmColors.Success)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.Check, null, tint = PalmColors.OnAccent, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(4.dp))
        Text(
            stringResource(R.string.capture_captured_badge),
            color = PalmColors.OnAccent,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

@Composable
private fun ReviewAllPager(
    sideCount: Int,
    capturedImages: List<Uri?>,
    isSaving: Boolean,
    isDraftPersisting: Boolean,
    isDraftValidating: Boolean,
    onRetake: (Int) -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val pageCount = sideCount.coerceAtLeast(1)
    val pagerState = rememberPagerState(pageCount = { pageCount })
    // Per-screen swipe direction (NOT persisted): flip to review photos right→left.
    var reverseSwipe by remember { mutableStateOf(false) }

    Column(modifier = modifier) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(16.dp))
                .background(Color.Black)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp)),
        ) {
            HorizontalPager(
                state = pagerState,
                reverseLayout = !reverseSwipe,
                modifier = Modifier.fillMaxSize(),
            ) { page ->
                val uri = capturedImages.getOrNull(page)
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    if (uri != null) {
                        Image(
                            painter = rememberAsyncImagePainter(uri),
                            contentDescription = "Side ${page + 1}",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }

                    Box(
                        modifier = Modifier
                            .padding(16.dp)
                            .align(Alignment.TopStart)
                            .clip(RoundedCornerShape(50))
                            .background(Color.Black.copy(alpha = 0.55f))
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                    ) {
                        Text(
                            stringResource(R.string.capture_side_of, page + 1, sideCount),
                            color = Color.White,
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                    CapturedBadge(Modifier.padding(16.dp).align(Alignment.TopEnd))

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .align(Alignment.BottomCenter)
                            .background(
                                Brush.verticalGradient(
                                    listOf(Color.Transparent, Color.Black.copy(alpha = 0.6f))
                                )
                            )
                            .padding(16.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        OutlinedButton(
                            onClick = { onRetake(page) },
                            enabled = !isSaving && !isDraftPersisting && !isDraftValidating,
                            modifier = Modifier.height(48.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                        ) {
                            Icon(Icons.Default.CameraAlt, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.capture_retake_side, page + 1))
                        }
                    }
                }
            }

            // Per-screen swipe-direction toggle — overlay outside the pager so it stays put
            // while pages swipe. Not persisted; resets each time the preview opens.
            IconButton(
                onClick = { reverseSwipe = !reverseSwipe },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(8.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.55f))
                    .size(44.dp),
            ) {
                Icon(
                    if (reverseSwipe) Icons.Default.RotateLeft else Icons.Default.RotateRight,
                    contentDescription = stringResource(
                        if (reverseSwipe) R.string.cd_capture_counter_clockwise else R.string.cd_capture_clockwise
                    ),
                    tint = Color.White,
                    modifier = Modifier.size(24.dp),
                )
            }
        }

        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
            horizontalArrangement = Arrangement.Center,
        ) {
            repeat(pageCount) { i ->
                val current = i == pagerState.currentPage
                Box(
                    modifier = Modifier
                        .padding(horizontal = 3.dp)
                        .size(if (current) 12.dp else 8.dp)
                        .clip(CircleShape)
                        .background(
                            if (current) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.outlineVariant
                        ),
                )
            }
        }

        Button(
            onClick = onSave,
            enabled = !isSaving && !isDraftPersisting && !isDraftValidating,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp)
                .height(52.dp),
        ) {
            if (isSaving) {
                CircularProgressIndicator(Modifier.size(20.dp), color = MaterialTheme.colorScheme.onPrimary)
            } else {
                Text(stringResource(R.string.capture_save_annotate))
            }
        }
    }
}

@Composable
private fun CameraCaptureStage(
    onCaptured: (Uri) -> Unit,
) {
    val context = LocalContext.current
    val toasts = LocalToasts.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val imageCapture = remember { ImageCapture.Builder().build() }
    val captureCd = stringResource(R.string.capture_source_phone)
    // The ImageCapture use case only works once it has been bound to a camera. Tapping the
    // shutter before that bind completes used to throw a cryptic CameraX error toast (the
    // "camera not found when I tap too fast" report). Gate the shutter on this flag instead.
    var cameraReady by remember { mutableStateOf(false) }
    // Blocks a second shutter tap while a capture is already in flight (rapid double-tap).
    var capturing by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        CameraPreview(
            context = context,
            lifecycleOwner = lifecycleOwner,
            imageCapture = imageCapture,
            onReadyChange = { cameraReady = it },
        )

        // Until the camera is bound, show a clear "starting" hint instead of a dead shutter.
        if (!cameraReady) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(color = Color.White)
                    Text(
                        stringResource(R.string.capture_camera_starting),
                        color = Color.White,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        FloatingActionButton(
            onClick = {
                if (!cameraReady) {
                    toasts.info(context.getString(R.string.capture_camera_starting))
                    return@FloatingActionButton
                }
                if (capturing) return@FloatingActionButton
                capturing = true
                val ts = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
                val file = File(context.cacheDir, "cap_$ts.jpg")
                val opts = ImageCapture.OutputFileOptions.Builder(file).build()
                imageCapture.takePicture(
                    opts,
                    ContextCompat.getMainExecutor(context),
                    object : ImageCapture.OnImageSavedCallback {
                        override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                            capturing = false
                            onCaptured(Uri.fromFile(file))
                        }
                        override fun onError(exc: ImageCaptureException) {
                            capturing = false
                            toasts.error(context.getString(R.string.capture_failed, exc.message ?: ""))
                        }
                    },
                )
            },
            modifier = Modifier.padding(bottom = 32.dp).size(72.dp),
            containerColor = if (cameraReady) MaterialTheme.colorScheme.primary
                             else MaterialTheme.colorScheme.primary.copy(alpha = 0.4f),
        ) {
            if (capturing) {
                CircularProgressIndicator(Modifier.size(28.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 3.dp)
            } else {
                Icon(Icons.Default.CameraAlt, captureCd, Modifier.size(32.dp))
            }
        }
    }
}

@Composable
private fun CameraPreview(
    context: Context,
    lifecycleOwner: androidx.lifecycle.LifecycleOwner,
    imageCapture: ImageCapture,
    onReadyChange: (Boolean) -> Unit = {},
) {
    AndroidView(
        factory = { ctx ->
            val previewView = PreviewView(ctx).apply { implementationMode = PreviewView.ImplementationMode.PERFORMANCE }
            previewView
        },
        update = { previewView ->
            val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
            cameraProviderFuture.addListener({
                val cameraProvider = try { cameraProviderFuture.get() } catch (_: Exception) { onReadyChange(false); return@addListener }
                val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
                try {
                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        imageCapture,
                    )
                    // Bind succeeded — the shutter (ImageCapture) is now usable.
                    onReadyChange(true)
                } catch (_: Exception) {
                    onReadyChange(false)
                }
            }, ContextCompat.getMainExecutor(context))
        },
        modifier = Modifier.fillMaxSize(),
    )
    DisposableEffect(Unit) {
        onDispose {
            onReadyChange(false)
            try {
                ProcessCameraProvider.getInstance(context).get().unbindAll()
            } catch (_: Exception) { }
        }
    }
}

/**
 * Orbbec RGB-D capture stage with live preview.
 *
 * States:
 *   No device  → prompt + "Find Camera" refresh button
 *   Device found, no permission → "Grant USB Access" button
 *   Starting preview → spinner
 *   Preview running → live RGB full-screen + depth PiP (top-right) + shutter FAB
 *   Captured (REVIEW step) → CapturedReviewStage
 */
@Composable
private fun OrbbecCaptureStage(
    isAvailable: Boolean,
    permissionGranted: Boolean,
    isPreviewRunning: Boolean,
    isStarting: Boolean,
    previewBitmap: ImageBitmap?,
    depthBitmap: ImageBitmap?,
    stateMsg: String?,
    currentStep: SideStep,
    capturedUri: Uri?,
    isLastSide: Boolean,
    allCaptured: Boolean,
    isSaving: Boolean,
    continueLabel: String?,
    onRequestPermission: () -> Unit,
    onRefresh: () -> Unit,
    onReset: () -> Unit,
    onCapture: () -> Unit,
    onRetake: () -> Unit,
    onContinue: () -> Unit,
) {
    if (currentStep == SideStep.REVIEW && capturedUri != null) {
        CapturedReviewStage(
            uri = capturedUri,
            isLastSide = isLastSide,
            allCaptured = allCaptured,
            isSaving = isSaving,
            continueLabel = continueLabel,
            onRetake = onRetake,
            onContinue = onContinue,
        )
        return
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        when {
            // Live preview running — show RGB frame + depth PiP + shutter
            isPreviewRunning -> {
                if (previewBitmap != null) {
                    Image(
                        bitmap = previewBitmap,
                        contentDescription = "Orbbec live preview",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize(),
                    )
                }

                // Depth PiP — top-right corner
                depthBitmap?.let { bmp ->
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(12.dp)
                            .size(width = 130.dp, height = 90.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .border(1.dp, Color.White.copy(alpha = 0.4f), RoundedCornerShape(8.dp)),
                    ) {
                        Image(
                            bitmap = bmp,
                            contentDescription = "Depth preview",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize(),
                        )
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .background(Color.Black.copy(alpha = 0.55f))
                                .padding(horizontal = 4.dp, vertical = 2.dp),
                        ) {
                            Text(stringResource(R.string.orbbec_depth), color = Color.White, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                // Status message overlay (bottom-left)
                stateMsg?.let { msg ->
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(start = 12.dp, bottom = 100.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color.Black.copy(alpha = 0.6f))
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    ) {
                        Text(msg, color = Color.White, style = MaterialTheme.typography.labelMedium)
                    }
                }

                // Shutter button
                FloatingActionButton(
                    onClick = onCapture,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 32.dp)
                        .size(72.dp),
                    containerColor = MaterialTheme.colorScheme.primary,
                ) {
                    Icon(Icons.Default.CameraAlt, stringResource(R.string.cd_capture), Modifier.size(32.dp))
                }

                // "LIVE" badge top-left
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(12.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(MaterialTheme.colorScheme.error.copy(alpha = 0.85f))
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                ) {
                    Text(stringResource(R.string.orbbec_live), color = Color.White, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                }
            }

            // Starting up
            isStarting -> {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    Text(
                        stringResource(R.string.orbbec_starting),
                        color = Color.White,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            // Device found, no permission yet
            isAvailable && !permissionGranted -> {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.padding(24.dp),
                ) {
                    Icon(Icons.Default.Usb, null, modifier = Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
                    Text(
                        stringResource(R.string.orbbec_detected),
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        stringResource(R.string.orbbec_grant_hint),
                        color = Color.White.copy(alpha = 0.75f),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    stateMsg?.let { Text(it, color = PalmColors.Warning, style = MaterialTheme.typography.bodySmall) }
                    Spacer(Modifier.height(4.dp))
                    Button(onClick = onRequestPermission, modifier = Modifier.heightIn(min = 48.dp)) {
                        Icon(Icons.Default.LockOpen, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.orbbec_grant))
                    }
                }
            }

            // No device detected
            else -> {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.padding(24.dp),
                ) {
                    Icon(Icons.Default.UsbOff, null, modifier = Modifier.size(56.dp), tint = Color.White.copy(alpha = 0.5f))
                    Text(
                        stringResource(R.string.orbbec_none_title),
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        stringResource(R.string.orbbec_none_hint),
                        color = Color.White.copy(alpha = 0.75f),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    stateMsg?.let { Text(it, color = PalmColors.Warning, style = MaterialTheme.typography.bodySmall) }
                    Spacer(Modifier.height(4.dp))
                    OutlinedButton(
                        onClick = onRefresh,
                        modifier = Modifier.heightIn(min = 48.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                    ) {
                        Icon(Icons.Default.Search, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.orbbec_find))
                    }
                    // Recovery for the RGB-only / "keeps resetting" lock: a stateMsg here means
                    // the flapping guard suppressed the camera. Reset clears it in-app instead of
                    // forcing a data-wiping "Clear App Data".
                    if (stateMsg != null) {
                        OutlinedButton(
                            onClick = onReset,
                            modifier = Modifier.heightIn(min = 48.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                        ) {
                            Icon(Icons.Default.Refresh, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.orbbec_reset))
                        }
                    }
                }
            }
        }
    }
}
