package dev.sawitulm.palmannotate.domain.model

import android.net.Uri

/**
 * In-memory state for one tree being annotated.
 *
 * This is the central data structure: every UI screen reads from and mutates
 * an ActiveSession. It is serialisable to/from the Output JSON v4 format
 * and the Room database.
 */
data class ActiveSession(
    val sessionId: String,            // UUID
    val treeName: String,             // e.g. "DAMIMAS_A21B_0001"
    val split: String,                // "train", "val", "test", or "field"
    val sides: List<TreeSide>,
    val suggestedLinks: List<CrossSideLink>,
    val confirmedLinks: List<CrossSideLink>,
    val metadata: TreeMetadata?,
    val dirty: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
) {
    val totalSides: Int get() = sides.size
    val totalBboxes: Int get() = sides.sumOf { it.bboxes.size }
    val totalUnassigned: Int get() = sides.sumOf { it.unassignedBboxCount }

    /** Generate adjacent pair indices for this session's side count. */
    val adjacentPairs: List<Pair<Int, Int>> get() = generateAdjacentPairs(totalSides)

    /** Set of adjacent-pair dedup keys for quick lookup. */
    val adjacentPairKeys: Set<String> by lazy {
        adjacentPairs.map { (a, b) ->
            if (a < b) "$a:$b" else "$b:$a"
        }.toSet()
    }

    fun isAdjacentPair(sideA: Int, sideB: Int): Boolean {
        val key = if (sideA < sideB) "$sideA:$sideB" else "$sideB:$sideA"
        return key in adjacentPairKeys
    }
}

/**
 * Metadata captured with a tree (variety, block, GPS, date).
 */
data class TreeMetadata(
    val variety: String = "",
    val block: String = "",
    val treeId: String = "",
    val date: String = "",             // ISO date string YYYY-MM-DD
    val latitude: Double? = null,
    val longitude: Double? = null,
)

/**
 * DatasetTree: the file-level grouping of images/labels for one tree.
 * Used by DatasetRepository to represent what's on disk.
 */
data class DatasetTree(
    val name: String,
    val split: String,
    val sides: List<DatasetSide>,
    val metadata: TreeMetadata?,
)

/**
 * One side of a dataset tree — the actual files on disk.
 */
data class DatasetSide(
    val sideIndex: Int,
    val imageFile: java.io.File?,
    val labelFile: java.io.File?,
    val imageUri: Uri,
    val labelUri: Uri?,
)
