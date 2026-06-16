package dev.sawitulm.palmannotate.domain.model

/**
 * A confirmed cross-side link between two bounding boxes on adjacent sides,
 * indicating they represent the same physical fruit bunch.
 */
data class CrossSideLink(
    val linkId: String,     // "L0", "L1", …
    val sideA: Int,         // lower side index (oriented)
    val bboxIdA: String,
    val sideB: Int,         // higher side index (oriented)
    val bboxIdB: String,
) {
    init {
        require(sideA < sideB) { "Link must be oriented: sideA ($sideA) < sideB ($sideB)" }
    }

    /** Normalised dedup key (order-independent). */
    val dedupKey: String
        get() {
            val a = "$sideA:$bboxIdA"
            val b = "$sideB:$bboxIdB"
            return if (a < b) "$a|$b" else "$b|$a"
        }

    companion object {
        /**
         * Create a link, auto-orienting so sideA < sideB.
         */
        fun create(
            linkId: String,
            side1: Int, bboxId1: String,
            side2: Int, bboxId2: String,
        ): CrossSideLink {
            return if (side1 < side2) {
                CrossSideLink(linkId, side1, bboxId1, side2, bboxId2)
            } else {
                CrossSideLink(linkId, side2, bboxId2, side1, bboxId1)
            }
        }
    }
}
