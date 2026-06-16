package dev.sawitulm.palmannotate.domain.model

/**
 * A suggested cross-side duplicate pair produced by geometry-based analysis.
 *
 * Unlike [CrossSideLink] (confirmed by the operator), a suggestion is
 * auto-generated and awaiting confirmation or dismissal.
 *
 * Mirrors the JS `dedup-utils.js` suggestion shape: a weighted [score] (NOT raw
 * IoU), a [category] of "auto" (>= autoMin) or "candidate", and the per-signal
 * breakdown that drove the score.
 */
data class SuggestedPair(
    val sideA: Int,
    val bboxIdA: String,
    val sideB: Int,
    val bboxIdB: String,
    /** Weighted suggestion score, 0.0–1.0 (0.45·seam + 0.35·vert + 0.20·size, ×classMult). */
    val score: Float,
    /** "auto" (score >= autoMin) or "candidate". */
    val category: String = "candidate",
    /** Per-signal breakdown (for the dedup suggestion badges). */
    val signals: SuggestionSignals? = null,
) {
    val dedupKey: String
        get() {
            val a = "$sideA:$bboxIdA"
            val b = "$sideB:$bboxIdB"
            return if (a < b) "$a|$b" else "$b|$a"
        }
}

/** Per-signal breakdown of a [SuggestedPair] score (matches dedup-utils.js signals). */
data class SuggestionSignals(
    val seam: Float,
    val vert: Float,
    val size: Float,
    val cls: Float,
    val sizeRatio: Float,
)

/**
 * Results of computing unique bunch counts from a session.
 */
data class TreeResults(
    /** Total bboxes across all sides. */
    val rawCount: Int,
    /** Bboxes involved in at least one cross-side link. */
    val linkedCount: Int,
    /** Unique bunches = number of clusters + unlinked singles. */
    val uniqueCount: Int,
    /** Bboxes still unassigned (classId == -1). */
    val unassignedCount: Int,
    /** Per-class unique bunch counts. */
    val classCounts: Map<AnnotationClass, Int>,
    /** Cluster root key → list of member bboxes (for output JSON). */
    val clusters: Map<String, List<ClusterMember>>,
)

/**
 * One member of a cross-side cluster (used in results & output generation).
 */
data class ClusterMember(
    val sideIndex: Int,
    val bboxId: String,
    val className: String,
    val x1: Float, val y1: Float, val x2: Float, val y2: Float,
    /** Box index on its side (for output JSON stability). */
    val boxIndex: Int,
)
