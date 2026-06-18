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
import dev.sawitulm.palmannotate.data.storage.ExportFolderRepository
import dev.sawitulm.palmannotate.data.storage.SessionRepository
import dev.sawitulm.palmannotate.domain.model.*
import dev.sawitulm.palmannotate.domain.quality.QualityCheck
import dev.sawitulm.palmannotate.ui.common.QualityGateModal
import kotlinx.coroutines.Dispatchers
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
) : ViewModel() {

    var run by mutableStateOf<SessionEntity?>(null)
        private set
    var sideCount by mutableIntStateOf(4)
    var currentSide by mutableIntStateOf(0)
    val capturedImages = mutableStateListOf<Uri?>()
    val capturedDepths = mutableStateListOf<OrbbecManager.OrbbecDepthData?>()
    var manualId by mutableStateOf("")
    var gpsStatus by mutableStateOf<String?>(null)
    var currentStep by mutableStateOf(SideStep.PREVIEW)
        private set
    var phase by mutableStateOf(CapturePhase.SIDES)
        private set
    var retakingFromReview by mutableStateOf(false)
        private set
    private var latitude: Double? = null
    private var longitude: Double? = null
    var isSaving by mutableStateOf(false)
        private set
    var saveError by mutableStateOf<String?>(null)
        private set
    var captureSource by mutableStateOf(CaptureSource.PHONE_CAMERA)
        private set
    var showQaDialog by mutableStateOf(false)
        private set
    var qaReport by mutableStateOf<QualityCheck.CaptureReport?>(null)
        private set

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
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val frame = orbbec.capture()
                val colorBytes = Base64.decode(frame.base64, Base64.NO_WRAP)
                val ts = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
                val file = File(context.cacheDir, "orbbec_$ts.jpg")
                file.writeBytes(colorBytes)
                val idx = currentSide
                if (idx < capturedDepths.size) capturedDepths[idx] = frame.depth
                withContext(Dispatchers.Main) {
                    onImageCaptured(Uri.fromFile(file))
                }
            } catch (e: Exception) {
                Log.e("CaptureFlow", "Orbbec capture failed", e)
                withContext(Dispatchers.Main) {
                    orbbecStateMsg = appContext.getString(R.string.orbbec_capture_failed, e.message ?: "")
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        viewModelScope.launch {
            try { orbbec.stopPreview() } catch (_: Exception) {}
        }
    }

    // ── Standard capture logic ────────────────────────────────────────────────

    fun dismissQa() { showQaDialog = false }

    fun requestSave(runId: String, context: Context, onDone: (String) -> Unit) {
        val r = run ?: return
        val capturedCount = capturedImages.count { it != null }
        val depthSides = capturedDepths.count { it != null }
        val hasGps = latitude != null && longitude != null
        val report = QualityCheck.analyzeCaptureShots(
            capturedSides = capturedCount,
            expectedSides = sideCount,
            depthSides = depthSides,
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
    }

    fun saveIgnoringQa(runId: String, context: Context, onDone: (String) -> Unit) {
        showQaDialog = false
        save(runId, context, onDone)
    }

    fun load(runId: String) {
        viewModelScope.launch {
            val r = repo.getRun(runId) ?: return@launch
            run = r
            sideCount = r.sideCount
            manualId = r.nextId.toString()
            capturedImages.clear()
            capturedDepths.clear()
            repeat(sideCount) {
                capturedImages.add(null)
                capturedDepths.add(null)
            }
            currentSide = 0
            currentStep = SideStep.PREVIEW
            phase = CapturePhase.SIDES
            refreshGps()
        }
    }

    /**
     * Fetch GPS and publish an actionable status. Safe to call repeatedly — it is invoked once
     * on load() and again once the location permission dialog resolves (the dialog is requested
     * concurrently with load(), so the first fetch usually runs *before* the user taps Allow and
     * would otherwise leave a stale "permission denied" for the whole session).
     */
    fun refreshGps() {
        viewModelScope.launch {
            runCatching {
                val loc = gps.getBestLocation()
                gpsStatus = if (loc != null) {
                    latitude = loc.latitude
                    longitude = loc.longitude
                    // Locale.US so the decimal stays a dot — the default (Indonesian) locale
                    // uses a comma, rendering "-3,44941, 114,84279" which reads as four numbers.
                    String.format(java.util.Locale.US, "%.5f, %.5f", loc.latitude, loc.longitude)
                } else when {
                    // Distinguish the failure so the message is actionable instead of a blank "unavailable".
                    !gps.hasPermission() -> appContext.getString(R.string.capture_gps_no_permission)
                    !gps.isLocationEnabled() -> appContext.getString(R.string.capture_gps_off)
                    else -> appContext.getString(R.string.capture_gps_unavailable)
                }
            }.onFailure { gpsStatus = appContext.getString(R.string.capture_gps_unavailable) }
        }
    }

    fun onImageCaptured(uri: Uri) {
        if (currentSide < capturedImages.size) {
            capturedImages[currentSide] = uri
            currentStep = SideStep.REVIEW
        }
    }

    fun goToSide(index: Int) {
        if (index in 0 until sideCount) {
            currentSide = index
            currentStep = if (capturedImages[index] != null) SideStep.REVIEW else SideStep.PREVIEW
        }
    }

    fun retakeCurrent() {
        if (currentSide < capturedImages.size) {
            capturedImages[currentSide] = null
            capturedDepths.getOrNull(currentSide)?.let { capturedDepths[currentSide] = null }
        }
        currentStep = SideStep.PREVIEW
    }

    fun continueFromReview(): Boolean {
        return if (currentSide < sideCount - 1) {
            currentSide++
            currentStep = SideStep.PREVIEW
            false
        } else {
            if (allCaptured) phase = CapturePhase.REVIEW_ALL
            true
        }
    }

    fun retakeSide(index: Int) {
        if (index in 0 until capturedImages.size) {
            capturedImages[index] = null
            if (index < capturedDepths.size) capturedDepths[index] = null
            currentSide = index
            currentStep = SideStep.PREVIEW
            phase = CapturePhase.SIDES
            retakingFromReview = true
        }
    }

    fun returnToReviewAll() {
        retakingFromReview = false
        if (allCaptured) {
            phase = CapturePhase.REVIEW_ALL
            currentStep = SideStep.REVIEW
        }
    }

    val allCaptured: Boolean get() = capturedImages.isNotEmpty() && capturedImages.all { it != null }

    private fun safe(s: String) = s.uppercase().replace(Regex("[^A-Z0-9_]+"), "_").trim('_').ifBlank { "TREE" }
    private fun safeBlock(s: String) = s.uppercase().replace(Regex("[^A-Z0-9]"), "")

    private fun save(runId: String, context: Context, onDone: (String) -> Unit) {
        val r = run ?: return
        saveError = null
        viewModelScope.launch {
            isSaving = true
            try {
                val treeId = if (r.autoId) r.nextId else (manualId.toIntOrNull() ?: r.nextId).coerceAtLeast(1)
                val v = safe(r.variety)
                val b = safeBlock(r.block)
                val treeName = if (b.isNotEmpty()) "${v}_${b}_${"%04d".format(treeId)}" else "${v}_${"%04d".format(treeId)}"

                val sides = withContext(Dispatchers.IO) {
                    val allSides = mutableListOf<TreeSide>()
                    capturedImages.forEachIndexed { index, uri ->
                        if (uri == null) return@forEachIndexed
                        val dest = storage.imageFile(treeName, index)
                        val bytes = try {
                            readBytes(context, uri)
                        } catch (e: Exception) {
                            Log.e("CaptureFlow", "Failed to read captured image for side $index", e)
                            null
                        }
                        if (bytes == null) throw IllegalStateException("Side ${index + 1}: captured image could not be read")
                        storage.writeBytes(dest, bytes)
                        val dims = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeFile(dest.path, dims)
                        if (dims.outWidth <= 0 || dims.outHeight <= 0) {
                            throw IllegalStateException("Side ${index + 1}: captured file has zero dimensions")
                        }

                        // Depth sidecar — best-effort, never blocks save
                        capturedDepths.getOrNull(index)?.let { depth ->
                            try {
                                val rawBytes = Base64.decode(depth.base64, Base64.NO_WRAP)
                                storage.writeBytes(storage.depthRawFile(treeName, index), rawBytes)
                                val meta = JSONObject().apply {
                                    put("width", depth.width)
                                    put("height", depth.height)
                                    put("format", depth.format)
                                    put("valueScale", depth.valueScale)
                                    put("encoding", depth.encoding)
                                    put("unit", depth.unit)
                                    put("alignedTo", depth.alignedTo)
                                    put("displayFloorMm", depth.displayFloorMm)
                                    put("displayCeilingMm", depth.displayCeilingMm)
                                }
                                storage.writeText(storage.depthJsonFile(treeName, index), meta.toString())
                                // Log.i("CaptureFlow", "Depth sidecar written for side $index (${rawBytes.size} bytes)")
                            } catch (e: Exception) {
                                Log.w("CaptureFlow", "Depth sidecar write failed for side $index", e)
                            }
                        }

                        allSides.add(
                            TreeSide(
                                sideIndex = index,
                                label = "Side ${index + 1}",
                                imageUri = Uri.fromFile(dest),
                                labelUri = null,
                                imageWidth = dims.outWidth,
                                imageHeight = dims.outHeight,
                                bboxes = emptyList(),
                                originalBboxes = emptyList(),
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

                val treeKey = repo.addTree(
                    sessionId = runId,
                    treeName = treeName,
                    treeId = treeId,
                    split = "field",
                    sides = sides,
                    metadata = TreeMetadata(
                        variety = r.variety,
                        block = r.block,
                        treeId = treeId.toString(),
                        latitude = latitude,
                        longitude = longitude,
                    ),
                    safTreeUri = safTreeUri,
                )
                onDone(treeKey)
            } catch (e: Exception) {
                Log.e("CaptureFlow", "Failed to save tree", e)
                saveError = appContext.getString(R.string.capture_save_failed)
            } finally {
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
                            enabled = !isSaving,
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
            enabled = !isSaving,
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
