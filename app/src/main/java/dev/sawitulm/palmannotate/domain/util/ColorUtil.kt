package dev.sawitulm.palmannotate.domain.util

/**
 * Color utility for annotation classes.
 * Mirrors the web CanvasRenderer color logic.
 */
object ColorUtil {

    private val PALETTE = listOf(
        "#3b82f6", "#ef4444", "#f59e0b", "#8b5cf6",
        "#10b981", "#06b6d4", "#f97316", "#ec4899",
        "#84cc16", "#6366f1",
    )

    private val CLASS_COLORS = mapOf(
        "B0" to "#6366f1",
        "B1" to "#3b82f6",
        "B2" to "#ef4444",
        "B3" to "#f59e0b",
        "B4" to "#8b5cf6",
        "B5" to "#10b981",
        "B6" to "#06b6d4",
        "U"  to "#9ca3af",
    )

    /** Get a color from the palette by index (wraps around). */
    fun getColor(index: Int): String = PALETTE[((index % PALETTE.size) + PALETTE.size) % PALETTE.size]

    /** Normalize a class key: trim, strip spaces, uppercase. null/undefined → "OBJECT". */
    fun normalizeClassKey(key: String?): String {
        if (key == null) return "OBJECT"
        return key.trim().replace(" ", "").uppercase().ifEmpty { "OBJECT" }
    }

    /** Get the color for a class name. Case-insensitive. */
    fun getClassColor(className: String): String {
        val normalized = normalizeClassKey(className)
        CLASS_COLORS[normalized]?.let { return it }
        // Deterministic hash for unmapped classes
        val hash = normalized.hashCode()
        return getColor(kotlin.math.abs(hash) % PALETTE.size)
    }

    /** Get a track color for a track ID. null/undefined → first color, numeric → modulo palette. */
    fun getTrackColor(trackId: Int?): String {
        if (trackId == null) return getColor(0)
        return getColor(kotlin.math.abs(trackId))
    }

    /**
     * Format a detection label.
     * Returns "name XX.X%" if confidence is present, just "name" otherwise.
     * Supports optional trackId prefix: "#id name XX.X%".
     */
    fun formatDetectionLabel(name: String, confidence: Float? = null, trackId: Int? = null): String {
        val sb = StringBuilder()
        if (trackId != null) sb.append("#$trackId ")
        sb.append(name)
        if (confidence != null && !confidence.isNaN()) {
            sb.append(" ${String.format("%.1f", confidence * 100)}%")
        }
        return sb.toString()
    }
}
