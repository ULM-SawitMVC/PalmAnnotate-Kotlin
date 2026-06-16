package dev.sawitulm.palmannotate.domain.dedup

/**
 * Union-Find (Disjoint Set Union) data structure for cross-side clustering.
 *
 * Used to group bounding boxes linked across adjacent sides into clusters,
 * where each cluster represents a unique physical fruit bunch.
 *
 * Features: path compression + union by rank for O(α(n)) amortised ops.
 * Maintains a reverse index (root → member set) for O(1) cluster lookup.
 */
class UnionFind(nodes: Collection<String>) {

    private val parent: MutableMap<String, String> =
        nodes.associateWith { it }.toMutableMap()
    private val rank: MutableMap<String, Int> =
        nodes.associateWith { 0 }.toMutableMap()

    /** Reverse index: root → mutable set of members. Updated on every union. */
    private val members: MutableMap<String, MutableSet<String>> =
        nodes.associateWith { mutableSetOf(it) }.toMutableMap()

    /** Number of distinct sets. */
    val size: Int get() = members.size

    /**
     * Find the root representative of the set containing [x].
     * Uses path compression for efficiency.
     */
    fun find(x: String): String {
        val p = parent[x] ?: x
        if (p == x) return x
        val root = find(p)
        parent[x] = root  // path compression
        return root
    }

    /**
     * Merge the sets containing [a] and [b].
     * Uses union by rank to keep trees shallow.
     * Updates the reverse index in O(1).
     */
    fun union(a: String, b: String) {
        val ra = find(a)
        val rb = find(b)
        if (ra == rb) return

        val rankA = rank[ra] ?: 0
        val rankB = rank[rb] ?: 0
        val (smaller, larger) = if (rankA < rankB) ra to rb else rb to ra
        // Attach smaller tree under larger root.
        parent[smaller] = larger
        if (rankA == rankB) rank[larger] = (rank[larger] ?: 0) + 1

        // Merge member sets: smaller into larger (amortised O(1) per element).
        val largerSet = members[larger] ?: mutableSetOf<String>().also { members[larger] = it }
        val smallerSet = members.remove(smaller) ?: emptySet()
        largerSet.addAll(smallerSet)
        // Update reverse pointers for all migrated members.
        for (node in smallerSet) {
            // Already path-compressed by find() calls above; just ensure parent points to larger.
            parent[node] = larger
        }
    }

    /**
     * Return all elements in the same set as [x], including [x] itself.
     * O(1) lookup via the reverse index.
     */
    fun getCluster(x: String): List<String> {
        val root = find(x)
        return members[root]?.toList() ?: listOf(x)
    }

    /**
     * Return all clusters as a map from root → member list.
     */
    fun clusters(): Map<String, List<String>> {
        // Ensure all path pointers are compressed for clean root keys.
        for (node in parent.keys) find(node)
        return members.mapValues { it.value.toList() }
    }

    /**
     * Check whether [a] and [b] are in the same set.
     */
    fun connected(a: String, b: String): Boolean = find(a) == find(b)
}
