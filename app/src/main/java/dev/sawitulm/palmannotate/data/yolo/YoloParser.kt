package dev.sawitulm.palmannotate.data.yolo

import dev.sawitulm.palmannotate.domain.model.AnnotationClass
import dev.sawitulm.palmannotate.domain.model.Bbox
import java.util.Locale

/**
 * YOLO label file parser and serializer.
 *
 * YOLO format per line: `<classId> <cx> <cy> <w> <h>` (normalized 0–1).
 * UNASSIGNED boxes (classId -1) are EXCLUDED from .txt output
 * (YOLO needs integer 0–3) but survive in Output JSON.
 */
object YoloParser {

    /**
     * Parse a YOLO .txt label file into pixel-coordinate bbox objects.
     *
     * @param text  Raw .txt file content.
     * @param imgW  Image width in pixels.
     * @param imgH  Image height in pixels.
     * @return List of parsed bboxes (invalid lines silently skipped).
     */
    /** Pre-compiled whitespace regex for line splitting. */
    private val WS = Regex("\\s+")

    fun parse(text: String?, imgW: Int, imgH: Int): List<Bbox> {
        if (text.isNullOrBlank()) return emptyList()
        if (imgW <= 0 || imgH <= 0) return emptyList()
        val bboxes = mutableListOf<Bbox>()
        var idx = 0
        for (line in text.lineSequence()) {
            val parts = line.trim().split(WS)
            if (parts.size < 5) continue
            val classId = parts[0].toIntOrNull() ?: continue
            if (classId !in 0..3) continue  // only valid annotation classes
            val cx = parts[1].toFloatOrNull() ?: continue
            val cy = parts[2].toFloatOrNull() ?: continue
            val w  = parts[3].toFloatOrNull() ?: continue
            val h  = parts[4].toFloatOrNull() ?: continue
            if (cx.isNaN() || cy.isNaN() || w.isNaN() || h.isNaN() ||
                cx.isInfinite() || cy.isInfinite() || w.isInfinite() || h.isInfinite()) continue

            val x1 = ((cx - w / 2f) * imgW).coerceIn(0f, imgW.toFloat())
            val y1 = ((cy - h / 2f) * imgH).coerceIn(0f, imgH.toFloat())
            val x2 = ((cx + w / 2f) * imgW).coerceIn(0f, imgW.toFloat())
            val y2 = ((cy + h / 2f) * imgH).coerceIn(0f, imgH.toFloat())

            val cls = AnnotationClass.fromId(classId)
            bboxes.add(
                Bbox(
                    id = "b${idx++}",
                    classId = cls.id,
                    className = cls.displayName,
                    x1 = x1, y1 = y1, x2 = x2, y2 = y2,
                )
            )
        }
        return bboxes
    }

    /**
     * Serialize bboxes to YOLO normalized .txt format.
     * UNASSIGNED boxes are excluded (YOLO needs integer class 0–3).
     */
    fun serialize(bboxes: List<Bbox>, imgW: Int, imgH: Int): String {
        if (imgW <= 0 || imgH <= 0) return ""
        return bboxes
            .filter { it.classId in 0..3 }
            .joinToString("\n") { b ->
                val cx = ((b.x1 + b.x2) / 2f) / imgW
                val cy = ((b.y1 + b.y2) / 2f) / imgH
                val w  = (b.x2 - b.x1) / imgW
                val h  = (b.y2 - b.y1) / imgH
                "${b.classId} ${cx.f6()} ${cy.f6()} ${w.f6()} ${h.f6()}"
            }
    }

    /** Format a float to 6 decimal places (matching JS toFixed(6)).
     *  MUST use Locale.US to guarantee '.' as decimal separator regardless of device locale.
     *  Without this, YOLO labels on German/French devices would use ',' and become unparseable. */
    private fun Float.f6(): String = String.format(Locale.US, "%.6f", this)
}
