package dev.sawitulm.palmannotate.domain.util

import dev.sawitulm.palmannotate.domain.model.Bbox
import kotlin.math.sqrt

/**
 * Pure geometry for picking a bounding box under a tap/drag point (image-space).
 *
 * Extracted from AnnotationCanvas so it is unit-testable and shared by every
 * call-site (tap-to-select, drag-start, dedup linking). Two behaviours fix the
 * "tiny box can't be selected" problem:
 *   1. Among boxes that strictly contain the point, the SMALLEST-area one wins,
 *      so a tiny box stacked on top of a big one stays reachable.
 *   2. If no box contains the point, the nearest box whose edge is within
 *      [tolImg] image-space px is picked, so a near-miss on a tiny box still
 *      selects it.
 */
object BboxHitTest {

    /**
     * Pick the bbox at image-space point ([x], [y]).
     * Prefers the smallest box that strictly contains the point; otherwise the
     * nearest box within [tolImg] image-space px of its edge. null if none.
     */
    fun pick(bboxes: List<Bbox>, x: Float, y: Float, tolImg: Float): Bbox? {
        // 1) smallest-area box that strictly contains the point.
        var best: Bbox? = null
        var bestArea = Float.MAX_VALUE
        for (b in bboxes) {
            if (x in b.x1..b.x2 && y in b.y1..b.y2 && b.area < bestArea) {
                best = b; bestArea = b.area
            }
        }
        if (best != null) return best

        // 2) none contain it — nearest box whose edge is within tolerance.
        var bestDist = tolImg
        for (b in bboxes) {
            val d = distanceToRect(x, y, b)
            if (d <= bestDist) { bestDist = d; best = b }
        }
        return best
    }

    /** 0 if (x,y) is inside the rect, else Euclidean distance to the nearest edge/corner. */
    fun distanceToRect(x: Float, y: Float, b: Bbox): Float {
        val dx = maxOf(b.x1 - x, 0f, x - b.x2)
        val dy = maxOf(b.y1 - y, 0f, y - b.y2)
        return sqrt(dx * dx + dy * dy)
    }
}
