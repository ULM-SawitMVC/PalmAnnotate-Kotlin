package dev.sawitulm.palmannotate.domain.model

/**
 * A single bounding box annotation on one side of a tree.
 *
 * Coordinates are in pixel space relative to the original image.
 * Boxes start as UNASSIGNED — the expert explicitly assigns a class (B1–B4).
 */
data class Bbox(
    /** Stable id within a side, e.g. "b0", "b1", … */
    val id: String,
    /** Numeric class id (AnnotationClass.id). -1 = UNASSIGNED. */
    val classId: Int,
    /** Display name: "B1"–"B4" or "U". */
    val className: String,
    /** Bounding box in pixel coordinates. */
    val x1: Float,
    val y1: Float,
    val x2: Float,
    val y2: Float,
) {
    /** Width in pixels. */
    val width: Float get() = x2 - x1
    /** Height in pixels. */
    val height: Float get() = y2 - y1
    /** Centre X. */
    val cx: Float get() = (x1 + x2) / 2f
    /** Centre Y. */
    val cy: Float get() = (y1 + y2) / 2f
    /** Area in pixels². */
    val area: Float get() = width * height
    /** Whether this box has been assigned a real class. */
    val isAssigned: Boolean get() = AnnotationClass.fromId(classId) != AnnotationClass.UNASSIGNED

    companion object {
        /**
         * Create a new UNASSIGNED bbox. Used for freshly drawn or detected boxes.
         */
        fun unassigned(id: String, x1: Float, y1: Float, x2: Float, y2: Float) = Bbox(
            id = id,
            classId = AnnotationClass.UNASSIGNED.id,
            className = AnnotationClass.UNASSIGNED.displayName,
            x1 = x1, y1 = y1, x2 = x2, y2 = y2,
        )

        /**
         * Next never-reused id for [prefix] within [existing] boxes.
         *
         * Returns `<prefix><N>` where N is one greater than the largest numeric
         * suffix already present on ANY existing id (regardless of its prefix).
         * A single shared sequence across prefixes guarantees a brand-new id can
         * never collide with a surviving box even after a delete shrinks the list.
         * Port of the never-reused `'nb'+_idSeq++` scheme in js/bbox-editor.js.
         */
        fun nextId(existing: List<Bbox>, prefix: String): String {
            val maxSuffix = existing.maxOfOrNull { b ->
                b.id.takeLastWhile { it.isDigit() }.toIntOrNull() ?: -1
            } ?: -1
            return "$prefix${maxSuffix + 1}"
        }
    }
}
