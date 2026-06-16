package dev.sawitulm.palmannotate.domain.model

import androidx.compose.ui.graphics.Color

/**
 * Annotation class for FFB (Fresh Fruit Bunch) grading.
 *
 * Damimas dataset uses 4 classes: B1–B4 (ripeness stages).
 * UNASSIGNED (-1 / "U") is the sentinel for newly drawn/detected boxes
 * that haven't been classified yet — rendered grey, excluded from YOLO .txt
 * but kept in Output JSON.
 */
enum class AnnotationClass(val id: Int, val displayName: String, val color: Long) {
    B1(0, "B1", 0xFF3B82F6),       // Blue  — unripe
    B2(1, "B2", 0xFFEF4444),       // Red   — under-ripe
    B3(2, "B3", 0xFFF59E0B),       // Amber — ripe
    B4(3, "B4", 0xFF8B5CF6),       // Purple— overripe
    UNASSIGNED(-1, "U", 0xFF9CA3AF); // Grey  — not yet classified

    val composeColor: Color get() = Color(color)

    companion object {
        /** Lookup by numeric class id. */
        fun fromId(id: Int): AnnotationClass =
            entries.firstOrNull { it.id == id } ?: UNASSIGNED

        /** Lookup by display name. */
        fun fromName(name: String): AnnotationClass =
            entries.firstOrNull {
                it.displayName.equals(name, ignoreCase = true)
            } ?: UNASSIGNED

        /** Only the real assignable classes (excludes UNASSIGNED). */
        val assignableEntries: List<AnnotationClass> =
            entries.filter { it != UNASSIGNED }

        /** All class colours keyed by display name (for canvas drawing). */
        val colorMap: Map<String, Color> =
            entries.associate { it.displayName to it.composeColor }
    }
}
