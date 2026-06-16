package dev.sawitulm.palmannotate.domain.model

import android.net.Uri

/**
 * One photo (side) of a tree, with its annotations.
 */
data class TreeSide(
    val sideIndex: Int,
    val label: String,              // "Side 1", "Side 2", …
    val imageUri: Uri?,             // on-disk URI of the captured image
    val labelUri: Uri?,             // URI of the .txt label file (may not exist yet)
    val imageWidth: Int,
    val imageHeight: Int,
    val bboxes: List<Bbox>,
    val originalBboxes: List<Bbox>, // snapshot at load time (for annot-log diffing)
) {
    val assignedBboxCount: Int get() = bboxes.count { it.isAssigned }
    val unassignedBboxCount: Int get() = bboxes.count { !it.isAssigned }
    val hasUnassigned: Boolean get() = bboxes.any { !it.isAssigned }
}

/**
 * Generate the standard side labels for N sides.
 */
fun generateSideLabels(n: Int): List<String> =
    (1..n).map { "Side $it" }

/**
 * Generate adjacent pair indices for N sides (clockwise with wraparound).
 *   - 2 sides → [[0,1]]  (single pair, no wrap)
 *   - 3+ sides → [[0,1],[1,2],…,[N-1,0]]
 */
fun generateAdjacentPairs(n: Int): List<Pair<Int, Int>> = when {
    n < 2 -> emptyList()
    n == 2 -> listOf(0 to 1)
    else -> (0 until n).map { i -> i to (i + 1) % n }
}
