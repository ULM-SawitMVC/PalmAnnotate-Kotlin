package dev.sawitulm.palmannotate.domain.dedup

/**
 * Union-Find (Disjoint Set Union) data structure for cross-side clustering.
 *
 * Used to group bounding boxes linked across adjacent sides into clusters,
 * where each cluster represents a unique physical fruit bunch.
 *
 * Features: path compression + union by rank for O(α(n)) amortised ops.
 */
class UnionFind(nodes: Collection<String>) {

    private val parent: MutableMap<String, String> =
        nodes.associateWith { it }.toMutableMap()
    private val rank: MutableMap<String, Int> =
        nodes.associateWith { 0 }.toMutableMap()

    /** Number of distinct sets. */
    val size: Int get() = parent.keys.count { find(it) == it }

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
     */
    fun union(a: String, b: String) {
        val ra = find(a)
        val rb = find(b)
        if (ra == rb) return

        val rankA = rank[ra] ?: 0
        val rankB = rank[rb] ?: 0
        when {
            rankA < rankB -> { parent[ra] = rb }
            rankA > rankB -> { parent[rb] = ra }
            else -> { parent[rb] = ra; rank[ra] = rankA + 1 }
        }
    }

    /**
     * Return all elements in the same set as [x], including [x] itself.
     */
    fun getCluster(x: String): List<String> {
        val root = find(x)
        return parent.keys.filter { find(it) == root }
    }

    /**
     * Return all clusters as a map from root → member list.
     */
    fun clusters(): Map<String, List<String>> {
        val groups = mutableMapOf<String, MutableList<String>>()
        for (node in parent.keys) {
            val root = find(node)
            groups.getOrPut(root) { mutableListOf() }.add(node)
        }
        return groups
    }

    /**
     * Check whether [a] and [b] are in the same set.
     */
    fun connected(a: String, b: String): Boolean = find(a) == find(b)
}
