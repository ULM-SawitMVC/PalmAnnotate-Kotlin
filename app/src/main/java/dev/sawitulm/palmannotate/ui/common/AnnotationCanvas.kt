package dev.sawitulm.palmannotate.ui.common

import android.graphics.BitmapFactory
import android.util.Log
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private const val CANVAS_TAG = "CanvasPerf"

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

enum class CanvasTool { SELECT, DRAW, PAN }

/** Which part of a selected bbox the user grabbed. */
enum class DragHandle { NONE, BODY, TL, TR, BL, BR, T, B, L, R }

data class DragState(
    val bboxId: String = "",
    val handle: DragHandle = DragHandle.NONE,
    val startOffset: Offset = Offset.Zero,
    val startBbox: Bbox? = null,
)

@Composable
fun AnnotationCanvas(
    imageUriString: String?,
    bboxes: List<Bbox>,
    selectedBboxId: String?,
    imageWidth: Int,
    imageHeight: Int,
    tool: CanvasTool = CanvasTool.SELECT,
    showBoxes: Boolean = true,
    onBboxTap: ((String) -> Unit)? = null,
    onBboxMoved: ((String, Float, Float, Float, Float) -> Unit)? = null,
    onBboxDrawn: ((x1: Float, y1: Float, x2: Float, y2: Float) -> Unit)? = null,
    onCanvasTap: (() -> Unit)? = null,
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
            Log.d(CANVAS_TAG, "Image CACHE HIT - uri=${uriStr.takeLast(50)}")
            value = cached
        } else {
            Log.d(CANVAS_TAG, "Image LOAD START - uri=${uriStr.takeLast(50)}")
            val loadStart = System.currentTimeMillis()
            value = null
            value = withContext(Dispatchers.IO) {
                decodeDownsampled(context, uriStr, maxDimension = 1600)?.also {
                    BitmapCache.put(cacheKey, it)
                    val loadTime = System.currentTimeMillis() - loadStart
                    Log.d(CANVAS_TAG, "Image LOAD END - uri=${uriStr.takeLast(50)}, time=${loadTime}ms")
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

    // Fit image on first composition
    var didFit by remember { mutableStateOf(false) }

    // Reused across draw frames — allocating a Paint per frame churned the GC during pan/zoom.
    val labelPaint = remember {
        android.graphics.Paint().apply {
            isAntiAlias = true
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
    }

    val transformState = rememberTransformableState { zoomChange, panChange, _ ->
        scale = (scale * zoomChange).coerceIn(0.3f, 15f)
        offset += panChange
    }

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

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .transformable(state = transformState)
            // Tap-to-select. detectDragGestures (below) only fires after the touch-slop
            // drag threshold is crossed, so a plain tap would never select a box — which
            // made class assignment (annotation) and box linking (dedup) impossible.
            // A dedicated tap detector restores single-tap selection in SELECT mode.
            .pointerInput(tool, bboxes) {
                if (tool == CanvasTool.SELECT) {
                    detectTapGestures { tapScreen ->
                        val img = screenToImage(tapScreen.x, tapScreen.y)
                        val tapped = bboxes.lastOrNull { b ->
                            img.x in b.x1..b.x2 && img.y in b.y1..b.y2
                        }
                        if (tapped != null) onBboxTap?.invoke(tapped.id)
                        else onCanvasTap?.invoke()
                    }
                }
            }
            .pointerInput(tool, bboxes, selectedBboxId) {
                when (tool) {
                    CanvasTool.SELECT -> {
                        detectDragGestures(
                            onDragStart = { startScreen ->
                                val img = screenToImage(startScreen.x, startScreen.y)
                                // Check if we grabbed the selected bbox
                                val sel = bboxes.find { it.id == selectedBboxId }
                                if (sel != null) {
                                    val handle = hitTestHandle(sel, img.x, img.y)
                                    if (handle != DragHandle.NONE) {
                                        dragState = DragState(sel.id, handle, startScreen, sel)
                                        return@detectDragGestures
                                    }
                                }
                                // Check if we tapped a different bbox
                                val tapped = bboxes.lastOrNull { b ->
                                    img.x in b.x1..b.x2 && img.y in b.y1..b.y2
                                }
                                if (tapped != null) {
                                    onBboxTap?.invoke(tapped.id)
                                    dragState = DragState(tapped.id, DragHandle.BODY, startScreen, tapped)
                                } else {
                                    onCanvasTap?.invoke()
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
                                onBboxMoved?.invoke(ds.bboxId, newBbox.x1, newBbox.y1, newBbox.x2, newBbox.y2)
                            },
                            onDragEnd = { dragState = null },
                        )
                    }
                    CanvasTool.DRAW -> {
                        detectDragGestures(
                            onDragStart = { drawStart = it; drawCurrent = it },
                            onDrag = { change, _ -> change.consume(); drawCurrent = change.position },
                            onDragEnd = {
                                val s = drawStart; val e = drawCurrent
                                if (s != null && e != null) {
                                    val i1 = screenToImage(minOf(s.x, e.x), minOf(s.y, e.y))
                                    val i2 = screenToImage(maxOf(s.x, e.x), maxOf(s.y, e.y))
                                    val x1 = i1.x.coerceIn(0f, imageWidth.toFloat())
                                    val y1 = i1.y.coerceIn(0f, imageHeight.toFloat())
                                    val x2 = i2.x.coerceIn(0f, imageWidth.toFloat())
                                    val y2 = i2.y.coerceIn(0f, imageHeight.toFloat())
                                    if (x2 - x1 > 5f && y2 - y1 > 5f) {
                                        onBboxDrawn?.invoke(x1, y1, x2, y2)
                                    }
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
                color = Color(0xFF1a1a2e),
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
