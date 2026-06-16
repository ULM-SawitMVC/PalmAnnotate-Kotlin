package dev.sawitulm.palmannotate.domain.util

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Depth data processing utilities.
 * Ported from js/viewer/depth-viewer.js (DepthViewer._toUint16, _range, _depthColor).
 */
object DepthUtil {

    /**
     * Decode raw little-endian bytes into uint16 values.
     */
    fun toUint16(raw: ByteArray): IntArray {
        val out = IntArray(raw.size / 2)
        for (i in out.indices) {
            out[i] = (raw[i * 2].toInt() and 0xFF) or ((raw[i * 2 + 1].toInt() and 0xFF) shl 8)
        }
        return out
    }

    /**
     * Compute depth range statistics, ignoring invalid (0) and out-of-range values.
     * Uses robust P2–P98 instead of raw min/max.
     *
     * @param depths Array of depth values in mm.
     * @param displayMaxMm Maximum display range in mm (values above are out-of-range).
     * @param displayMinMm Minimum display range in mm (values below are out-of-range).
     * @return Triple of (minMm, maxMm, validCount) for the robust range.
     */
    data class DepthRange(
        val minMm: Int,
        val maxMm: Int,
        val valid: Int,
        val displayFloorMm: Int,
        val displayCeilingMm: Int,
        val observedMinMm: Int,
        val observedMaxMm: Int,
    )

    fun range(depths: IntArray, displayMaxMm: Int = 7000, displayMinMm: Int = 250): DepthRange {
        val valid = depths.filter { it in displayMinMm..displayMaxMm }
        if (valid.isEmpty()) {
            return DepthRange(0, 0, 0, displayMinMm, displayMaxMm, 0, 0)
        }

        val sorted = valid.sorted()
        val observedMin = sorted.first()
        val observedMax = sorted.last()

        // Robust P2–P98 percentile (matches js/viewer/depth-viewer.js _range)
        val p2Idx = max(0, (sorted.size * 0.02).roundToInt() - 1)
        val p98Idx = min(sorted.size - 1, (sorted.size * 0.98).roundToInt() - 1)
        val robustMin = sorted[p2Idx]
        val robustMax = sorted[p98Idx].coerceAtLeast(robustMin + 1)

        // Use P2-P98 directly as colormap range (same as web app)
        // This ensures the full blue→red spectrum maps to the actual depth variation
        return DepthRange(
            minMm = robustMin,
            maxMm = robustMax,
            valid = valid.size,
            displayFloorMm = robustMin,
            displayCeilingMm = robustMax,
            observedMinMm = observedMin,
            observedMaxMm = observedMax,
        )
    }

    /**
     * Map a depth value to an RGB color using jet colormap (matches Orbbec preview).
     * Invalid (0) or out-of-range values map to black.
     */
    fun depthColor(depthMm: Int, floorMm: Int, ceilingMm: Int): Triple<Int, Int, Int> {
        if (depthMm <= 0 || depthMm < floorMm || depthMm > ceilingMm) {
            return Triple(0, 0, 0)
        }
        val t = (depthMm - floorMm).toFloat() / (ceilingMm - floorMm).coerceAtLeast(1)
        // Jet colormap (matches OrbbecManager.encodeDepthPreviewBase64)
        val r = (clampUnit(1.5f - kotlin.math.abs(4f * t - 3f)) * 255f).toInt()
        val g = (clampUnit(1.5f - kotlin.math.abs(4f * t - 2f)) * 255f).toInt()
        val b = (clampUnit(1.5f - kotlin.math.abs(4f * t - 1f)) * 255f).toInt()
        return Triple(r, g, b)
    }

    private fun clampUnit(v: Float) = if (v < 0f) 0f else if (v > 1f) 1f else v
}
