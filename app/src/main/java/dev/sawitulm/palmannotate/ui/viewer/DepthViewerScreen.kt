package dev.sawitulm.palmannotate.ui.viewer

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.sawitulm.palmannotate.data.db.TreeDao
import dev.sawitulm.palmannotate.data.storage.AndroidStorageManager
import dev.sawitulm.palmannotate.domain.util.DepthUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import javax.inject.Inject

@HiltViewModel
class DepthViewerViewModel @Inject constructor(
    private val treeDao: TreeDao,
    private val storage: AndroidStorageManager,
) : ViewModel() {

    var treeName by mutableStateOf<String?>(null)
        private set
    var depthBitmap by mutableStateOf<ImageBitmap?>(null)
        private set
    var rangeInfo by mutableStateOf<DepthUtil.DepthRange?>(null)
        private set
    var errorMsg by mutableStateOf<String?>(null)
        private set
    var isLoading by mutableStateOf(false)
        private set

    // Raw depth data for tap-to-read
    var depthData by mutableStateOf<IntArray?>(null)
        private set
    var depthWidth by mutableStateOf(0)
        private set
    var depthHeight by mutableStateOf(0)
        private set
    var valueScale by mutableStateOf(1.0f)
        private set

    // Tap readout
    var tapReadout by mutableStateOf<String?>(null)
        private set

    fun readDepthAtPixel(normalizedX: Float, normalizedY: Float) {
        val data = depthData ?: return
        val w = depthWidth
        val h = depthHeight
        if (w <= 0 || h <= 0) return

        val px = (normalizedX * w).toInt().coerceIn(0, w - 1)
        val py = (normalizedY * h).toInt().coerceIn(0, h - 1)
        val idx = py * w + px
        if (idx < 0 || idx >= data.size) return

        val rawMm = data[idx]
        val mm = rawMm * valueScale
        tapReadout = if (rawMm > 0) {
            "px($px, $py) = ${mm.toInt()} mm (raw $rawMm)"
        } else {
            "px($px, $py) = no reading (0)"
        }
    }

    fun load(treeKey: String, sideIndex: Int) {
        viewModelScope.launch {
            isLoading = true
            depthBitmap = null
            rangeInfo = null
            errorMsg = null
            depthData = null
            depthWidth = 0
            depthHeight = 0
            tapReadout = null
            try {
                val name = withContext(Dispatchers.IO) { treeDao.getByKey(treeKey)?.treeName }
                    ?: run { errorMsg = "Tree not found"; isLoading = false; return@launch }
                treeName = name

                val result = withContext(Dispatchers.IO) {
                    val rawFile = storage.depthRawFile(name, sideIndex)
                    if (!rawFile.exists()) return@withContext null

                    // Read dimension sidecar if available, fall back to square root guess
                    val jsonFile = storage.depthJsonFile(name, sideIndex)
                    var width = 0; var height = 0
                    if (jsonFile.exists()) {
                        runCatching {
                            val j = JSONObject(jsonFile.readText())
                            width = j.optInt("width", 0)
                            height = j.optInt("height", 0)
                        }
                    }

                    val raw = rawFile.readBytes()
                    val rawDepths = DepthUtil.toUint16(raw)

                    // Apply valueScale so values are in mm (Gemini 335L: scale ≈ 0.1, raw 5000 = 500mm)
                    var scale = 1.0f
                    if (jsonFile.exists()) {
                        runCatching { scale = JSONObject(jsonFile.readText()).optDouble("valueScale", 1.0).toFloat() }
                    }
                    val depths = if (scale == 1.0f) rawDepths else IntArray(rawDepths.size) { (rawDepths[it] * scale).toInt() }

                    val range = DepthUtil.range(depths, 7000, 250)
                    if (range.valid == 0) return@withContext Triple(null, range, Triple(rawDepths, 0, 0))

                    // If sidecar dimensions missing, derive from pixel count
                    val pixelCount = depths.size
                    if (width <= 0 || height <= 0 || width * height != pixelCount) {
                        // Common Orbbec resolutions: 1280x800, 1280x720, 640x400, 640x360
                        width = when {
                            pixelCount == 1280 * 800 -> 1280
                            pixelCount == 1280 * 720 -> 1280
                            pixelCount == 640 * 400 -> 640
                            pixelCount == 640 * 360 -> 640
                            pixelCount == 848 * 480 -> 848
                            else -> { val s = Math.sqrt(pixelCount.toDouble()).toInt(); if (s * s == pixelCount) s else 1280 }
                        }
                        height = pixelCount / width
                    }
                    if (width <= 0 || height <= 0) return@withContext Triple(null, range, Triple(rawDepths, 0, 0))

                    // Build ARGB pixel array off the main thread
                    val pixels = IntArray(pixelCount)
                    val floor = range.displayFloorMm
                    val ceiling = range.displayCeilingMm
                    for (i in 0 until pixelCount) {
                        val (r, g, b) = DepthUtil.depthColor(depths[i], floor, ceiling)
                        pixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                    }
                    val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    bmp.setPixels(pixels, 0, width, 0, 0, width, height)
                    Triple(bmp.asImageBitmap(), range, Triple(rawDepths, width, height))
                }

                if (result == null) {
                    errorMsg = "No depth captured for Side ${sideIndex + 1}."
                } else {
                    val (bmp, range, depthInfo) = result
                    if (bmp == null) {
                        errorMsg = "No valid depth values in file."
                    } else {
                        depthBitmap = bmp
                        rangeInfo = range
                        // Store raw depth data for tap-to-read
                        depthData = depthInfo.first
                        depthWidth = depthInfo.second
                        depthHeight = depthInfo.third
                        // Read valueScale for display
                        val jsonFile = storage.depthJsonFile(treeName!!, sideIndex)
                        if (jsonFile.exists()) {
                            runCatching { valueScale = JSONObject(jsonFile.readText()).optDouble("valueScale", 1.0).toFloat() }
                        }
                    }
                }
            } catch (e: Exception) {
                errorMsg = e.message ?: "Failed to load depth"
            } finally {
                isLoading = false
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DepthViewerScreen(
    treeKey: String,
    onBack: () -> Unit,
    viewModel: DepthViewerViewModel = hiltViewModel(),
) {
    var currentSide by remember { mutableIntStateOf(0) }

    LaunchedEffect(treeKey, currentSide) {
        viewModel.load(treeKey, currentSide)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Depth & RAW${viewModel.treeName?.let { " — $it" } ?: ""}") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            // Side tabs
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (i in 1..4) {
                    FilterChip(
                        selected = currentSide == i - 1,
                        onClick = { currentSide = i - 1 },
                        label = { Text("Side $i", fontSize = 12.sp) },
                    )
                }
            }

            when {
                viewModel.depthBitmap != null -> {
                    // Store container size for coordinate conversion
                    var containerSize by remember { mutableStateOf(IntSize.Zero) }
                    val bitmap = viewModel.depthBitmap!!
                    val bmpW = bitmap.width.toFloat()
                    val bmpH = bitmap.height.toFloat()

                    // Single Image draw — no per-pixel Canvas loop
                    Box(
                        Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .background(Color.Black)
                            .onSizeChanged { containerSize = it }
                            .pointerInput(Unit) {
                                detectTapGestures { offset: Offset ->
                                    if (containerSize.width > 0 && containerSize.height > 0 && bmpW > 0 && bmpH > 0) {
                                        // Convert tap position to normalized coordinates
                                        // accounting for ContentScale.Fit
                                        val containerW = containerSize.width.toFloat()
                                        val containerH = containerSize.height.toFloat()
                                        val scale = minOf(containerW / bmpW, containerH / bmpH)
                                        val displayedW = bmpW * scale
                                        val displayedH = bmpH * scale
                                        val offsetX = (containerW - displayedW) / 2f
                                        val offsetY = (containerH - displayedH) / 2f

                                        val normalizedX = ((offset.x - offsetX) / displayedW).coerceIn(0f, 1f)
                                        val normalizedY = ((offset.y - offsetY) / displayedH).coerceIn(0f, 1f)

                                        viewModel.readDepthAtPixel(normalizedX, normalizedY)
                                    }
                                }
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Image(
                            bitmap = bitmap,
                            contentDescription = "Depth map Side ${currentSide + 1}",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    viewModel.rangeInfo?.let { r ->
                        Surface(Modifier.fillMaxWidth().padding(8.dp), tonalElevation = 2.dp) {
                            Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text("Display range: ${r.displayFloorMm} – ${r.displayCeilingMm} mm", fontSize = 12.sp)
                                Text("Observed: ${r.observedMinMm} – ${r.observedMaxMm} mm", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("Valid pixels: ${r.valid}", fontSize = 12.sp, fontWeight = FontWeight.Medium)
                                // Tap readout
                                viewModel.tapReadout?.let { readout ->
                                    Text(
                                        readout,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                } ?: Text(
                                    "Tap the heatmap to read depth (mm)",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
                viewModel.isLoading -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                else -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            viewModel.errorMsg ?: "Loading…",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
