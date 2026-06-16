package dev.sawitulm.palmannotate.data.detection

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.util.Log
import dev.sawitulm.palmannotate.domain.model.Bbox
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * On-device YOLO detector (onnxruntime-android) — faithful port of
 * `js/detect/detector.js`.
 *
 * DETECT-ONLY: the model is treated as single-class; every returned box is
 * UNASSIGNED (the expert assigns the class). OVER-DETECT: a LOW confThreshold so
 * weak bunches surface; class-agnostic NMS suppresses overlaps. Letterbox (keep
 * aspect, pad neutral grey 114) — NOT a stretch — so coordinates are correct for
 * non-square images. All methods are NON-THROWING (return [] on failure).
 */
class OnnxDetector(private val context: Context) {

    companion object {
        private const val TAG = "OnnxDetector"
        private const val MODEL_PATH = "models/ffb-detector.onnx"
        private const val CONFIG_PATH = "models/detector.config.json"
        private const val PAD = 114 // Ultralytics letterbox grey
    }

    private var session: OrtSession? = null
    private var env: OrtEnvironment? = null
    private var inputSize = 640
    // Defaults match detector.js DEFAULT_CONFIG (overridden by detector.config.json).
    private var confThreshold = 0.05f
    private var iouThreshold = 0.35f
    private var maxBoxes = 300
    private var isInitialized = false

    // Pre-allocated reusable buffer for NCHW float data (~4.7 MB at 640×640).
    // Avoids allocating + GC'ing a large buffer on every detection call.
    private var floatBuffer: FloatBuffer? = null
    private var floatBufferSize = 0

    suspend fun initialize() = withContext(Dispatchers.IO) {
        if (isInitialized) return@withContext
        try {
            loadConfig()
            val ortEnv = OrtEnvironment.getEnvironment()
            val opts = OrtSession.SessionOptions()
            val modelBytes = context.assets.open(MODEL_PATH).use { it.readBytes() }
            session = ortEnv.createSession(modelBytes, opts)
            env = ortEnv
            isInitialized = true
            // Log.i(TAG, "ONNX detector ready (input=$inputSize conf=$confThreshold iou=$iouThreshold max=$maxBoxes)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize ONNX detector", e)
        }
    }

    private fun loadConfig() {
        try {
            val text = context.assets.open(CONFIG_PATH).use { it.readBytes().toString(Charsets.UTF_8) }
            val json = JSONObject(text)
            inputSize = json.optInt("inputSize", inputSize)
            confThreshold = json.optDouble("confThreshold", confThreshold.toDouble()).toFloat()
            iouThreshold = json.optDouble("iouThreshold", iouThreshold.toDouble()).toFloat()
            maxBoxes = json.optInt("maxBoxes", maxBoxes)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load detector config, using defaults", e)
        }
    }

    suspend fun detect(imageUri: android.net.Uri): List<Bbox> = withContext(Dispatchers.IO) {
        try {
            if (!isInitialized) initialize()
            val sess = session ?: return@withContext emptyList()
            val ortEnv = env ?: return@withContext emptyList()
            val bitmap = context.contentResolver.openInputStream(imageUri)?.use {
                BitmapFactory.decodeStream(it)
            } ?: return@withContext emptyList()
            val result = detectFromBitmap(bitmap, sess, ortEnv)
            bitmap.recycle()
            result
        } catch (e: Exception) {
            Log.e(TAG, "Detection failed", e); emptyList()
        }
    }

    suspend fun detect(bitmap: Bitmap): List<Bbox> = withContext(Dispatchers.IO) {
        try {
            if (!isInitialized) initialize()
            val sess = session ?: return@withContext emptyList()
            val ortEnv = env ?: return@withContext emptyList()
            detectFromBitmap(bitmap, sess, ortEnv)
        } catch (e: Exception) {
            Log.e(TAG, "Detection failed", e); emptyList()
        }
    }

    private data class Det(val x1: Float, val y1: Float, val x2: Float, val y2: Float, val score: Float)

    private fun detectFromBitmap(original: Bitmap, sess: OrtSession, ortEnv: OrtEnvironment): List<Bbox> {
        val imgW = original.width
        val imgH = original.height
        if (imgW <= 0 || imgH <= 0) return emptyList()

        // Letterbox: keep aspect, pad neutral grey.
        val scale = min(inputSize.toFloat() / imgW, inputSize.toFloat() / imgH)
        val newW = (imgW * scale).roundToInt()
        val newH = (imgH * scale).roundToInt()
        val padX = (inputSize - newW) / 2
        val padY = (inputSize - newH) / 2

        val letterboxed = Bitmap.createBitmap(inputSize, inputSize, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(letterboxed)
        canvas.drawColor(Color.rgb(PAD, PAD, PAD))
        val scaled = Bitmap.createScaledBitmap(original, newW, newH, true)
        canvas.drawBitmap(scaled, padX.toFloat(), padY.toFloat(), null)
        scaled.recycle()

        val pixels = IntArray(inputSize * inputSize)
        letterboxed.getPixels(pixels, 0, inputSize, 0, 0, inputSize, inputSize)
        letterboxed.recycle()

        // NCHW float, normalized 0..1.
        // Reuse pre-allocated buffer to avoid ~4.7 MB alloc + GC per detection.
        val area = inputSize * inputSize
        val requiredSize = 3 * area
        val buf = floatBuffer?.takeIf { it.capacity() >= requiredSize }
            ?: FloatBuffer.allocate(requiredSize).also { floatBuffer = it; floatBufferSize = requiredSize }
        buf.clear()
        buf.rewind()
        for (i in 0 until area) buf.put(((pixels[i] shr 16) and 0xFF) / 255f) // R
        for (i in 0 until area) buf.put(((pixels[i] shr 8) and 0xFF) / 255f)  // G
        for (i in 0 until area) buf.put((pixels[i] and 0xFF) / 255f)          // B
        buf.rewind()
        // Slice to exact size so OnnxTensor sees the correct shape.
        val floatBuf = FloatBuffer.allocate(requiredSize)
        floatBuf.put(buf)
        floatBuf.rewind()

        val inputTensor = OnnxTensor.createTensor(ortEnv, floatBuf, longArrayOf(1, 3, inputSize.toLong(), inputSize.toLong()))
        val raw: FloatArray
        val shape: LongArray
        sess.run(mapOf(sess.inputNames.first() to inputTensor)).use { results ->
            val outputTensor = results.first().value as OnnxTensor
            shape = outputTensor.info.shape
            raw = outputTensor.floatBuffer.array()
        }
        inputTensor.close()

        if (shape.size != 3 || shape[0] != 1L) {
            Log.w(TAG, "Unexpected output shape: ${shape.joinToString()}")
            return emptyList()
        }
        val a = shape[1].toInt()
        val b = shape[2].toInt()
        // Channels-first [1, 4+nc, N] when a<b (the common YOLOv8 onnx layout), else [1, N, 4+nc].
        val channelsFirst = a < b
        val attrs = if (channelsFirst) a else b
        val rows = if (channelsFirst) b else a
        val nc = attrs - 4
        if (nc < 1) return emptyList()

        fun at(row: Int, attr: Int): Float =
            if (channelsFirst) raw[attr * rows + row] else raw[row * attrs + attr]

        val candidates = ArrayList<Det>()
        for (n in 0 until rows) {
            val cx = at(n, 0); val cy = at(n, 1); val w = at(n, 2); val h = at(n, 3)
            // Single-class objectness: max over the class block (detect-only — class ignored).
            var score = 0f
            for (c in 0 until nc) { val s = at(n, 4 + c); if (s > score) score = s }
            if (score < confThreshold) continue
            // Undo letterbox (pad + scale) → original image px.
            var x1 = (cx - w / 2f - padX) / scale
            var y1 = (cy - h / 2f - padY) / scale
            var x2 = (cx + w / 2f - padX) / scale
            var y2 = (cy + h / 2f - padY) / scale
            x1 = x1.coerceIn(0f, imgW.toFloat()); y1 = y1.coerceIn(0f, imgH.toFloat())
            x2 = x2.coerceIn(0f, imgW.toFloat()); y2 = y2.coerceIn(0f, imgH.toFloat())
            if (x2 - x1 < 1f || y2 - y1 < 1f) continue
            candidates.add(Det(x1, y1, x2, y2, score))
        }

        val kept = nms(candidates, iouThreshold, maxBoxes)
        return kept.mapIndexed { i, d -> Bbox.unassigned("det$i", d.x1, d.y1, d.x2, d.y2) }
    }

    /** Class-agnostic NMS (detect-only) capped at [limit]. */
    private fun nms(candidates: List<Det>, iouThreshold: Float, limit: Int): List<Det> {
        val sorted = candidates.sortedByDescending { it.score }
        val kept = ArrayList<Det>()
        for (cand in sorted) {
            var overlap = false
            for (k in kept) { if (iou(cand, k) > iouThreshold) { overlap = true; break } }
            if (!overlap) { kept.add(cand); if (kept.size >= limit) break }
        }
        return kept
    }

    private fun iou(a: Det, b: Det): Float {
        val ix1 = max(a.x1, b.x1); val iy1 = max(a.y1, b.y1)
        val ix2 = min(a.x2, b.x2); val iy2 = min(a.y2, b.y2)
        val iw = max(0f, ix2 - ix1); val ih = max(0f, iy2 - iy1)
        val inter = iw * ih
        if (inter <= 0f) return 0f
        val union = (a.x2 - a.x1) * (a.y2 - a.y1) + (b.x2 - b.x1) * (b.y2 - b.y1) - inter
        return if (union > 0f) inter / union else 0f
    }

    fun close() {
        session?.close()
        session = null
        floatBuffer = null
        isInitialized = false
    }
}
