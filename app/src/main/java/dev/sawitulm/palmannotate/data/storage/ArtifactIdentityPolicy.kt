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
}
