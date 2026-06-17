package dev.sawitulm.palmannotate.ui.common

import android.graphics.BitmapFactory
import android.util.Log
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.sawitulm.palmannotate.domain.model.AnnotationClass
import dev.sawitulm.palmannotate.domain.model.Bbox
import dev.sawitulm.palmannotate.domain.util.BboxHitTest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private const val CANVAS_TAG = "CanvasPerf"

/** Touch tolerance (screen dp) for selecting a bbox by tap, so tiny boxes stay reachable. */
private const val TOUCH_TOL_DP = 24f

/** Minimum drag span (screen dp) before DRAW commits a box — blocks accidental tiny boxes
 *  from a stray finger nudge. Screen-space, so zooming in still lets you draw small boxes. */
private const val MIN_DRAW_DP = 24f

/**
 * Full annotation canvas: image rendering + bbox overlay + interaction.
 *
 * Matches the web CanvasRenderer: class colours, selection handles,
 * label with class name, zoom/pan, draw-new-bbox, tap-to-select,
 * drag-to-move, drag-corner-to-resize.
 */

/**
 * Small LRU of decoded bitmaps keyed by URI. The dedup pager revisits the same side
 * images as the operator pages back and forth; without a cache every visit re-decoded the
 * JPEG (the "slow/heavy" feel). Bitmaps are downsampled so a handful fit comfortably.
 */
private object BitmapCache {
    private const val MAX = 8
    private val map = object : LinkedHashMap<String, androidx.compose.ui.graphics.ImageBitmap>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, androidx.compose.ui.graphics.ImageBitmap>?) = size > MAX
    }
    @Synchronized fun get(key: String) = map[key]
    @Synchronized fun put(key: String, value: androidx.compose.ui.graphics.ImageBitmap) { map[key] = value }
}

/**
 * Cache key that includes the file's last-modified time + size, not just the URI.
 *
 * Image paths are derived from the tree name (e.g. `…/DAMIMAS_A21B_0001_1.jpg`).
 * After a tree is deleted and a fresh one is captured, the id resets so the new
 * photo lands at the SAME path. Keying the bitmap cache by URI alone then served
 * the deleted tree's stale bitmap. Folding mtime+size into the key makes an
 * overwritten or recreated file miss the cache and re-decode the new content.
 */
private fun bitmapCacheKey(uriString: String): String {
    return try {
        val path = android.net.Uri.parse(uriString).path
        if (path != null) {
            val f = File(path)
            if (f.exists()) return "$uriString|${f.lastModified()}|${f.length()}"
        }
        uriString
    } catch (_: Exception) {
        uriString
    }
}

/**
 * Decode an image (content:// or file path) on a background thread, downsampled so its
 * largest dimension is <= [maxDimension]. Keeps memory + decode time low for the dedup
 * pager. Bbox coordinates are in original-image space and are scaled at draw time, so the
 * smaller bitmap renders identically.
 */
private fun decodeDownsampled(
    context: android.content.Context,
    uriString: String,
    maxDimension: Int,
): androidx.compose.ui.graphics.ImageBitmap? {
    fun openStream(): java.io.InputStream? = try {
        context.contentResolver.openInputStream(android.net.Uri.parse(uriString))
    } catch (_: Exception) {
        val file = File(android.net.Uri.parse(uriString).path ?: "")
        if (file.exists()) file.inputStream() else null
    }
    return try {
        // Pass 1: bounds only.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        openStream()?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        // Pass 2: decode with sample size.
        var sample = 1
        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        while (longest / sample > maxDimension) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        openStream()?.use { BitmapFactory.decodeStream(it, null, opts) }?.asImageBitmap()
    } catch (_: Exception) {
        null
    }
}

enum class CanvasTool {
    SELECT,
    DRAW,
    PAN,

    /**
     * Read-only viewer. Installs no zoom/pan and no drag-edit gestures, so a parent
     * `HorizontalPager` (the carousel's swipe-between-sides) receives the horizontal drag
     * instead of the canvas swallowing it. Tap-to-select still works (for assigning a
     * class / linking without entering Edit mode).
     */
    VIEW,
}

/** Which part of a selected bbox the user grabbed. */
enum class DragHandle { NONE, BODY, TL, TR, BL, BR, T, B, L, R }

data class DragState(
    val bboxId: String = "",
    val handle: DragHandle = DragHandle.NONE,
    val startOffset: Offset = Offset.Zero,
    val startBbox: Bbox? = null,
)

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun AnnotationCanvas(
    imageUriString: String?,
    bboxes: List<Bbox>,
    selectedBboxId: String?,
    imageWidth: Int,
    imageHeight: Int,
    tool: CanvasTool = CanvasTool.SELECT,
    showBoxes: Boolean = true,
    /** bboxId → 1-based link-group number; drawn as a badge so links are visible. */
    linkedBoxes: Map<String, Int> = emptyMap(),
    onBboxTap: ((String) -> Unit)? = null,
    onBboxMoved: ((String, Float, Float, Float, Float) -> Unit)? = null,
    onBboxDrawn: ((x1: Float, y1: Float, x2: Float, y2: Float) -> Unit)? = null,
    onCanvasTap: (() -> Unit)? = null,
    /** True while a touch is actively grabbing/drawing a box — lets a parent pager
     *  (e.g. the carousel's HorizontalPager) disable its own swipe so it can't steal
     *  the gesture mid-drag. */
    onActiveEditChange: ((Boolean) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    // Load image bitmap off the main thread, downsampled so the dedup pager (which keeps
    // several canvases composed at once) does not decode multiple full-res JPEGs on the UI
    // thread — that was the cause of the slow/heavy dedup screen.
    val context = LocalContext.current
    val bitmap by produceState<androidx.compose.ui.graphics.ImageBitmap?>(initialValue = null, imageUriString) {
        val uriStr = imageUriString ?: run { value = null; return@produceState }
        // Key by URI + file mtime/size so a reused path (delete + recapture with a
        // reset tree id) doesn't serve the previous tree's stale bitmap.
        val cacheKey = withContext(Dispatchers.IO) { bitmapCacheKey(uriStr) }
        val cached = BitmapCache.get(cacheKey)
        if (cached != null) {
            // Log.d(CANVAS_TAG, "Image CACHE HIT - uri=${uriStr.takeLast(50)}")
            value = cached
        } else {
            // Log.d(CANVAS_TAG, "Image LOAD START - uri=${uriStr.takeLast(50)}")
            val loadStart = System.currentTimeMillis()
            value = null
            value = withContext(Dispatchers.IO) {
                decodeDownsampled(context, uriStr, maxDimension = 1600)?.also {
                    BitmapCache.put(cacheKey, it)
                    val loadTime = System.currentTimeMillis() - loadStart
                    // Log.d(CANVAS_TAG, "Image LOAD END - uri=${uriStr.takeLast(50)}, time=${loadTime}ms")
                }
            }
        }
    }

    // Viewport transform
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    // Drawing state
    var drawStart by remember { mutableStateOf<Offset?>(null) }
    var drawCurrent by remember { mutableStateOf<Offset?>(null) }

    // Drag state (move/resize)
    var dragState by remember { mutableStateOf<DragState?>(null) }

    // True the instant a touch-down lands on an existing box — set in a dedicated
    // pointerInput below at ACTION_DOWN, before any drag-slop is resolved. dragState/
    // drawStart alone are not enough to gate the parent pager: they are only set inside
    // detectDragGestures' onDragStart, i.e. AFTER slop, the exact moment the pager's own
    // slop-based swipe detector is racing to claim the same touch.
    var touchOnBox by remember { mutableStateOf(false) }

    // Live snapshots of the mutating inputs the gesture handlers read. The gesture
    // pointerInput blocks are keyed on `tool` ONLY (not bboxes/selectedBboxId), so that a
    // box-move — which rebuilds `bboxes` into a new list every frame via onBboxMoved —
    // does NOT change a pointerInput key and tear down the in-progress gesture coroutine
    // mid-drag. That teardown was the real "sticky / can't grab" cause, and it also
    // cancelled the early-hit-test block before its touchOnBox=false cleanup, stranding the
    // parent pager disabled. rememberUpdatedState lets the long-lived gesture coroutine read
    // the freshest values without restarting.
    val currentBboxes by rememberUpdatedState(bboxes)
    val currentSelectedId by rememberUpdatedState(selectedBboxId)
    val currentOnBboxTap by rememberUpdatedState(onBboxTap)
    val currentOnBboxMoved by rememberUpdatedState(onBboxMoved)
    val currentOnBboxDrawn by rememberUpdatedState(onBboxDrawn)
    val currentOnCanvasTap by rememberUpdatedState(onCanvasTap)

    // Fit image on first composition
    var didFit by remember { mutableStateOf(false) }

    // Reused across draw frames — allocating a Paint per frame churned the GC during pan/zoom.
    val labelPaint = remember {
        android.graphics.Paint().apply {
            isAntiAlias = true
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
    }

    // Read before the Canvas — DrawScope has no MaterialTheme access. On-palette neutral
    // (was an off-palette blue-purple literal) shown until the bitmap finishes decoding.
    val placeholderColor = androidx.compose.material3.MaterialTheme.colorScheme.surfaceVariant

    val transformState = rememberTransformableState { zoomChange, panChange, _ ->
        scale = (scale * zoomChange).coerceIn(0.3f, 15f)
        offset += panChange
    }

    // Refuse viewport-pan when the touch started ON a box, so a single-finger box-move
    // isn't stolen by transformable's pan. transformable treats a one-finger drag as a
    // pan and — being earlier in the modifier chain — consumes it before the box-move
    // drag detector can claim it (the "sticky / can't move existing box" bug). canPan is
    // consulted only after pan-slop is crossed, by which point touchOnBox is already set
    // from ACTION_DOWN, so this reads a settled value (no race). Remembered (stable
    // identity) so transformable's node is never reset mid-gesture by recomposition;
    // the body still reads the live touchOnBox via the captured state delegate.
    // Pinch-zoom is unaffected, and on empty canvas (touchOnBox=false) single-finger pan
    // still works.
    val canPanViewport = remember { { _: Offset -> !touchOnBox } }

    // Helpers: screen ↔ image coords
    fun screenToImage(sx: Float, sy: Float): Offset =
        Offset((sx - offset.x) / scale, (sy - offset.y) / scale)
    fun imageToScreen(ix: Float, iy: Float): Offset =
        Offset(ix * scale + offset.x, iy * scale + offset.y)

    // Hit-test: find which handle (if any) the user tapped on a selected bbox
    fun hitTestHandle(b: Bbox, imgX: Float, imgY: Float): DragHandle {
        val r = 20f / scale // handle radius in image coords
        // Corners
        if (kotlin.math.abs(imgX - b.x1) < r && kotlin.math.abs(imgY - b.y1) < r) return DragHandle.TL
        if (kotlin.math.abs(imgX - b.x2) < r && kotlin.math.abs(imgY - b.y1) < r) return DragHandle.TR
        if (kotlin.math.abs(imgX - b.x1) < r && kotlin.math.abs(imgY - b.y2) < r) return DragHandle.BL
        if (kotlin.math.abs(imgX - b.x2) < r && kotlin.math.abs(imgY - b.y2) < r) return DragHandle.BR
        // Edges
        if (imgX in b.x1..b.x2 && kotlin.math.abs(imgY - b.y1) < r) return DragHandle.T
        if (imgX in b.x1..b.x2 && kotlin.math.abs(imgY - b.y2) < r) return DragHandle.B
        if (imgY in b.y1..b.y2 && kotlin.math.abs(imgX - b.x1) < r) return DragHandle.L
        if (imgY in b.y1..b.y2 && kotlin.math.abs(imgX - b.x2) < r) return DragHandle.R
        // Body
        if (imgX in b.x1..b.x2 && imgY in b.y1..b.y2) return DragHandle.BODY
        return DragHandle.NONE
    }

    // Reported to the parent (e.g. carousel HorizontalPager) so it can disable its own
    // swipe gesture while a box-grab/draw is in progress, including the down-to-slop
    // window covered by touchOnBox. Only fires on actual transitions (LaunchedEffect key).
    val isActiveEdit = touchOnBox || dragState != null || drawStart != null
    LaunchedEffect(isActiveEdit) { onActiveEditChange?.invoke(isActiveEdit) }

    Canvas(
        modifier = modifier
            .fillMaxSize()
            // No zoom/pan in VIEW mode: it would consume the horizontal drag and block the
            // carousel's swipe-between-sides. Editing modes keep pinch-zoom/pan.
            .then(if (tool != CanvasTool.VIEW) Modifier.transformable(state = transformState, canPan = canPanViewport) else Modifier)
            // Early "down landed on an existing box" signal, observed at ACTION_DOWN
            // before any drag-slop is resolved. Never consumes — purely observes — so the
            // tap/drag detectors below still see every event normally. Closes the race
            // against the parent pager's own slop-based swipe detector (see isActiveEdit).
            .pointerInput(tool) {
                if (tool == CanvasTool.SELECT || tool == CanvasTool.DRAW) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val img = screenToImage(down.position.x, down.position.y)
                        touchOnBox = BboxHitTest.pick(currentBboxes, img.x, img.y, TOUCH_TOL_DP.dp.toPx() / scale) != null
                        waitForUpOrCancellation()
                        touchOnBox = false
                    }
                }
            }
            // Tap-to-select. detectDragGestures (below) only fires after the touch-slop
            // drag threshold is crossed, so a plain tap would never select a box — which
            // made class assignment (annotation) and box linking (dedup) impossible.
            // DRAW is included so an earlier box can be tapped to (re)select it and assign
            // a class without leaving to Review — only the last-drawn box was reachable
            // before. A tap and a draw-drag don't conflict: detectTapGestures fires only on
            // a tap (no slop), detectDragGestures only after slop is crossed.
            .pointerInput(tool) {
                if (tool == CanvasTool.SELECT || tool == CanvasTool.VIEW || tool == CanvasTool.DRAW) {
                    detectTapGestures { tapScreen ->
                        val img = screenToImage(tapScreen.x, tapScreen.y)
                        // Tolerant + smallest-first pick so tiny boxes (and tiny boxes stacked
                        // on a big one) stay selectable; tol is a constant screen distance.
                        val tapped = BboxHitTest.pick(currentBboxes, img.x, img.y, TOUCH_TOL_DP.dp.toPx() / scale)
                        if (tapped != null) currentOnBboxTap?.invoke(tapped.id)
                        else currentOnCanvasTap?.invoke()
                    }
                }
            }
            .pointerInput(tool) {
                when (tool) {
                    CanvasTool.SELECT -> {
                        detectDragGestures(
                            onDragStart = { startScreen ->
                                val img = screenToImage(startScreen.x, startScreen.y)
                                // Check if we grabbed the selected bbox
                                val sel = currentBboxes.find { it.id == currentSelectedId }
                                if (sel != null) {
                                    val handle = hitTestHandle(sel, img.x, img.y)
                                    if (handle != DragHandle.NONE) {
                                        dragState = DragState(sel.id, handle, startScreen, sel)
                                        return@detectDragGestures
                                    }
                                }
                                // Check if we tapped a different bbox (tolerant, smallest-first)
                                val tapped = BboxHitTest.pick(currentBboxes, img.x, img.y, TOUCH_TOL_DP.dp.toPx() / scale)
                                if (tapped != null) {
                                    currentOnBboxTap?.invoke(tapped.id)
                                    dragState = DragState(tapped.id, DragHandle.BODY, startScreen, tapped)
                                } else {
                                    currentOnCanvasTap?.invoke()
                                }
                            },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                val ds = dragState ?: return@detectDragGestures
                                val start = ds.startBbox ?: return@detectDragGestures
                                val dx = dragAmount.x / scale
                                val dy = dragAmount.y / scale

                                val newBbox = when (ds.handle) {
                                    DragHandle.BODY -> start.copy(
                                        x1 = (start.x1 + dx).coerceIn(0f, imageWidth.toFloat() - start.width),
                                        y1 = (start.y1 + dy).coerceIn(0f, imageHeight.toFloat() - start.height),
                                        x2 = (start.x2 + dx).coerceIn(start.width, imageWidth.toFloat()),
                                        y2 = (start.y2 + dy).coerceIn(start.height, imageHeight.toFloat()),
                                    )
                                    DragHandle.TL -> start.copy(x1 = (start.x1 + dx).coerceIn(0f, start.x2 - 5f), y1 = (start.y1 + dy).coerceIn(0f, start.y2 - 5f))
                                    DragHandle.TR -> start.copy(x2 = (start.x2 + dx).coerceIn(start.x1 + 5f, imageWidth.toFloat()), y1 = (start.y1 + dy).coerceIn(0f, start.y2 - 5f))
                                    DragHandle.BL -> start.copy(x1 = (start.x1 + dx).coerceIn(0f, start.x2 - 5f), y2 = (start.y2 + dy).coerceIn(start.y1 + 5f, imageHeight.toFloat()))
                                    DragHandle.BR -> start.copy(x2 = (start.x2 + dx).coerceIn(start.x1 + 5f, imageWidth.toFloat()), y2 = (start.y2 + dy).coerceIn(start.y1 + 5f, imageHeight.toFloat()))
                                    DragHandle.T -> start.copy(y1 = (start.y1 + dy).coerceIn(0f, start.y2 - 5f))
                                    DragHandle.B -> start.copy(y2 = (start.y2 + dy).coerceIn(start.y1 + 5f, imageHeight.toFloat()))
                                    DragHandle.L -> start.copy(x1 = (start.x1 + dx).coerceIn(0f, start.x2 - 5f))
                                    DragHandle.R -> start.copy(x2 = (start.x2 + dx).coerceIn(start.x1 + 5f, imageWidth.toFloat()))
                                    DragHandle.NONE -> start
                                }
                                dragState = ds.copy(startBbox = newBbox)
                                currentOnBboxMoved?.invoke(ds.bboxId, newBbox.x1, newBbox.y1, newBbox.x2, newBbox.y2)
                            },
                            onDragEnd = { dragState = null },
                        )
                    }
                    CanvasTool.DRAW -> {
                        detectDragGestures(
                            // Always start a draw rect on drag — selecting an existing box is
                            // handled by the tap detector above, so a drag can still draw a new
                            // box anywhere, including overlapping an existing one (palm bunches
                            // are often packed close together).
                            onDragStart = { drawStart = it; drawCurrent = it },
                            onDrag = { change, _ -> change.consume(); drawCurrent = change.position },
                            onDragEnd = {
                                val s = drawStart; val e = drawCurrent
                                // Require a deliberate drag measured in SCREEN space so a stray
                                // finger nudge can't spawn a tiny accidental box. Because the
                                // threshold is screen-space, zooming in still lets you draw boxes
                                // that are genuinely small in image space.
                                val minDrawPx = MIN_DRAW_DP.dp.toPx()
                                if (s != null && e != null &&
                                    kotlin.math.abs(e.x - s.x) >= minDrawPx &&
                                    kotlin.math.abs(e.y - s.y) >= minDrawPx
                                ) {
                                    val i1 = screenToImage(minOf(s.x, e.x), minOf(s.y, e.y))
                                    val i2 = screenToImage(maxOf(s.x, e.x), maxOf(s.y, e.y))
                                    val x1 = i1.x.coerceIn(0f, imageWidth.toFloat())
                                    val y1 = i1.y.coerceIn(0f, imageHeight.toFloat())
                                    val x2 = i2.x.coerceIn(0f, imageWidth.toFloat())
                                    val y2 = i2.y.coerceIn(0f, imageHeight.toFloat())
                                    currentOnBboxDrawn?.invoke(x1, y1, x2, y2)
                                }
                                drawStart = null; drawCurrent = null
                            },
                        )
                    }
                    CanvasTool.PAN -> {
                        detectDragGestures { change, dragAmount ->
                            change.consume(); offset += dragAmount
                        }
                    }
                    // Read-only: install no drag detector so drags fall through to the
                    // parent (carousel pager) for swipe-between-sides.
                    CanvasTool.VIEW -> {}
                }
            }
    ) {
        // Auto-fit on first composition
        if (!didFit && imageWidth > 0 && imageHeight > 0) {
            val fitScale = minOf(size.width / imageWidth, size.height / imageHeight)
            scale = fitScale
            offset = Offset(
                (size.width - imageWidth * fitScale) / 2f,
                (size.height - imageHeight * fitScale) / 2f,
            )
            didFit = true
        }

        // Draw image
        val bmp = bitmap
        if (bmp != null) {
            drawImage(
                image = bmp,
                dstOffset = androidx.compose.ui.unit.IntOffset(offset.x.toInt(), offset.y.toInt()),
                dstSize = IntSize((imageWidth * scale).toInt(), (imageHeight * scale).toInt()),
            )
        } else {
            // Placeholder
            drawRect(
                color = placeholderColor,
                topLeft = offset,
                size = Size(imageWidth.toFloat() * scale, imageHeight.toFloat() * scale),
            )
        }

        if (!showBoxes) return@Canvas

        // Draw bboxes (reuse the hoisted Paint; textSize/colour are set per box below)
        val paint = labelPaint

        for (bbox in bboxes) {
            val cls = AnnotationClass.fromId(bbox.classId)
            val color = cls.composeColor
            val isSelected = bbox.id == selectedBboxId
            val strokeW = if (isSelected) 3.dp.toPx() else 1.5.dp.toPx()

            val tl = imageToScreen(bbox.x1, bbox.y1)
            val sz = Size(bbox.width * scale, bbox.height * scale)

            // Fill with translucent color
            drawRoundRect(
                color = color.copy(alpha = if (isSelected) 0.2f else 0.1f),
                topLeft = tl,
                size = sz,
                cornerRadius = CornerRadius(2.dp.toPx()),
            )
            // Stroke
            drawRoundRect(
                color = color,
                topLeft = tl,
                size = sz,
                cornerRadius = CornerRadius(2.dp.toPx()),
                style = Stroke(width = strokeW),
            )

            // Label background
            val labelText = bbox.className
            paint.textSize = (11 * scale).coerceIn(9f, 18f)
            val textW = paint.measureText(labelText)
            val labelH = paint.textSize + 4.dp.toPx()
            val labelY = tl.y - labelH
            drawRoundRect(
                color = color,
                topLeft = Offset(tl.x, labelY.coerceIn(0f, size.height)),
                size = Size(textW + 8.dp.toPx(), labelH),
                cornerRadius = CornerRadius(2.dp.toPx()),
            )
            // Label text
            drawContext.canvas.nativeCanvas.drawText(
                labelText,
                tl.x + 4.dp.toPx(),
                labelY + labelH - 3.dp.toPx(),
                paint.apply { this.color = android.graphics.Color.WHITE },
            )

            // Link badge: a green chip with the link-group number at the box's top-right
            // corner. The same number appears on the matching bunch on the adjacent side,
            // so the operator can see what is linked to what.
            val linkGroup = linkedBoxes[bbox.id]
            if (linkGroup != null) {
                val linkColor = Color(0xFFB8E04A)
                val br = 9.dp.toPx()
                val bcx = tl.x + sz.width
                val bcy = tl.y
                drawCircle(color = Color.Black, radius = br + 1.5.dp.toPx(), center = Offset(bcx, bcy))
                drawCircle(color = linkColor, radius = br, center = Offset(bcx, bcy))
                paint.textSize = br * 1.5f
                paint.color = android.graphics.Color.BLACK
                val gt = linkGroup.toString()
                val gtw = paint.measureText(gt)
                drawContext.canvas.nativeCanvas.drawText(gt, bcx - gtw / 2f, bcy + br * 0.55f, paint)
            }

            // Selection handles
            if (isSelected) {
                val handleR = 5.dp.toPx()
                val handles = listOf(
                    Offset(tl.x, tl.y),                                    // TL
                    Offset(tl.x + sz.width, tl.y),                         // TR
                    Offset(tl.x, tl.y + sz.height),                        // BL
                    Offset(tl.x + sz.width, tl.y + sz.height),             // BR
                    Offset(tl.x + sz.width / 2, tl.y),                     // T
                    Offset(tl.x + sz.width / 2, tl.y + sz.height),         // B
                    Offset(tl.x, tl.y + sz.height / 2),                    // L
                    Offset(tl.x + sz.width, tl.y + sz.height / 2),         // R
                )
                for (h in handles) {
                    drawCircle(color = Color.White, radius = handleR + 1.dp.toPx(), center = h)
                    drawCircle(color = color, radius = handleR, center = h)
                }
            }
        }

        // Draw in-progress rectangle
        val s = drawStart; val e = drawCurrent
        if (s != null && e != null) {
            drawRect(
                color = Color.White,
                topLeft = Offset(minOf(s.x, e.x), minOf(s.y, e.y)),
                size = Size(kotlin.math.abs(e.x - s.x), kotlin.math.abs(e.y - s.y)),
                style = Stroke(width = 1.5.dp.toPx()),
            )
        }
    }
}
