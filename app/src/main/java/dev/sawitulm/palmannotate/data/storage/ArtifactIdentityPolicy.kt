package dev.sawitulm.palmannotate.data.storage

/**
 * Pure checks for artifact identities before any value is interpolated into a filesystem/SAF path.
 */
internal object ArtifactIdentityPolicy {
    private val safeTreeName = Regex("^[A-Za-z0-9][A-Za-z0-9_-]{0,127}$")

    fun treeNameError(treeName: String): String? =
        if (safeTreeName.matches(treeName)) null
        else "tree name contains unsafe or unsupported characters"

    fun sideSetError(sideIndices: List<Int>): String? = when {
        sideIndices.isEmpty() -> "capture package has no sides"
        sideIndices.any { it < 0 } -> "side index must not be negative"
        sideIndices.distinct().size != sideIndices.size -> "side indices must be unique"
        else -> null
    }

    fun collisionProbePaths(treeName: String, sideIndices: List<Int>): List<String> =
        buildList {
            add("dataset/manifests/$treeName.json")
            add("dataset/metadata/$treeName.json")
            add("Output JSON/$treeName.json")
            for (sideIndex in sideIndices) {
                val stem = "${treeName}_${sideIndex + 1}"
                add("dataset/images/field/$stem.jpg")
                add("dataset/depth/field/$stem.raw")
                add("dataset/depth/field/$stem.json")
                add("dataset/annotlog/field/$stem.json")
                add("Output TXT/field/$stem.txt")
            }
        }
}
