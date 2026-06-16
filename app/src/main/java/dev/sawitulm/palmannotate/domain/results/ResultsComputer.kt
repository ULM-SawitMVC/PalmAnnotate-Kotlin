package dev.sawitulm.palmannotate.domain.results

import dev.sawitulm.palmannotate.domain.dedup.UnionFind
import dev.sawitulm.palmannotate.domain.model.*

/**
 * Results computation — unique bunch counting via Union-Find clustering.
 *
 * Port of JS `results.js compute(session)`. Builds Union-Find over every bbox
 * keyed `sideIndex:bboxId`, unions confirmed cross-side links, then emits
 * per-cluster majority-vote class, per-class unique counts, and full cluster
 * membership for the Output JSON v4 bunches.
 *
 * Key JS semantics:
 *   - linkedCount = total bboxes involved in at least one link (= rawCount - uniqueCount)
 *   - uniqueCount = total clusters (including singletons)
 *   - classCounts: per-cluster majority vote; unassigned → "other" bucket
 *   - Every box is in exactly one cluster (singletons are clusters of size 1)
 */
object ResultsComputer {

    /** Key format used in Union-Find: "sideIndex:bboxId". */
    private fun key(side: Int, id: String) = "$side:$id"

    /** Compute tree results from an ActiveSession. */
    fun compute(session: ActiveSession): TreeResults {
        val allKeys = mutableListOf<String>()
        val keyToMember = mutableMapOf<String, ClusterMember>()

        for (side in session.sides) {
            side.bboxes.forEachIndexed { boxIdx, b ->
                val k = key(side.sideIndex, b.id)
                allKeys.add(k)
                keyToMember[k] = ClusterMember(
                    sideIndex = side.sideIndex,
                    bboxId = b.id,
                    className = b.className,
                    x1 = b.x1, y1 = b.y1, x2 = b.x2, y2 = b.y2,
                    boxIndex = boxIdx,
                )
            }
        }

        val uf = UnionFind(allKeys)
        val linkedBoxKeys = mutableSetOf<String>()
        for (link in session.confirmedLinks) {
            val a = key(link.sideA, link.bboxIdA)
            val b = key(link.sideB, link.bboxIdB)
            if (a in allKeys && b in allKeys) {
                uf.union(a, b)
                linkedBoxKeys.add(a)
                linkedBoxKeys.add(b)
            }
        }

        // Cluster map: root key -> list of ClusterMember
        val rawClusters = uf.clusters()
        val clusterMap = LinkedHashMap<String, List<ClusterMember>>()
        for ((root, keys) in rawClusters) {
            clusterMap[root] = keys.mapNotNull { keyToMember[it] }
        }

        val rawCount = session.sides.sumOf { it.bboxes.size }
        val uniqueCount = clusterMap.size
        // linkedCount = how many bboxes effectively "collapsed" into clusters
        // (= rawCount - uniqueCount). This is what JS calls duplicates_linked.
        val linkedCount = rawCount - uniqueCount

        val unassignedCount = session.sides.sumOf { side ->
            side.bboxes.count { it.classId == AnnotationClass.UNASSIGNED.id }
        }

        // Per-class unique bunch counts (majority vote per cluster).
        val classCounts = AnnotationClass.entries.associateWith { 0 }.toMutableMap()
        for ((_, members) in clusterMap) {
            if (members.isEmpty()) continue
            val dominantName = members.groupBy { it.className }
                .maxByOrNull { it.value.size }?.key ?: "U"
            val cls = AnnotationClass.fromName(dominantName)
            classCounts[cls] = (classCounts[cls] ?: 0) + 1
        }

        return TreeResults(
            rawCount = rawCount,
            linkedCount = linkedCount,
            uniqueCount = uniqueCount,
            unassignedCount = unassignedCount,
            classCounts = classCounts,
            clusters = clusterMap,
        )
    }
}
