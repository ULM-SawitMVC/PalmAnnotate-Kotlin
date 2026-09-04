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
    /** Optimistic-concurrency token copied from the committed Room tree row. */
    val revision: Long = 0L,
    val dirty: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val datasetType: DatasetType = DatasetType.MULTISIDE,
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
 * Metadata captured with a tree (variety, block, GPS, date, operator, capture-set identity).
 *
 * WS-13: the raw `latitude`/`longitude` pair was replaced by [gps]. A bare coordinate carries no
 * statement about when it was measured, which is exactly how 42 trees ended up sharing one
 * hours-old fix with nothing in the data to say so. [latitude]/[longitude] remain available as
 * derived accessors over the same record, so old call sites keep compiling and keep seeing the
 * coordinate that was actually measured; whether it may be trusted is [gps].`status`.
 */
data class TreeMetadata(
    val variety: String = "",
    val block: String = "",
    val treeId: String = "",
    val date: String = "",             // ISO date string YYYY-MM-DD (capture day)
    val capturedAtMillis: Long? = null, // Original capture instant when the package recorded one.
    val gps: GpsProvenance = GpsProvenance.UNKNOWN,
    val operatorName: String = "",
    val identity: CaptureSetIdentity = CaptureSetIdentity.UNKNOWN,
) {
    /** The recorded latitude, whatever its freshness. Read [gps].`status` before trusting it. */
    val latitude: Double? get() = gps.recordedCoordinates?.first

    /** The recorded longitude, whatever its freshness. Read [gps].`status` before trusting it. */
    val longitude: Double? get() = gps.recordedCoordinates?.second
}

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
