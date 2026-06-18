package dev.sawitulm.palmannotate.domain.usecase

import dev.sawitulm.palmannotate.domain.dedup.UnionFind
import dev.sawitulm.palmannotate.domain.model.*

/**
 * Use cases for session mutations: class propagation, mismatch detection,
 * link management. Port of session.js ActiveSession methods.
 */
object SessionUseCases {

    // ─── Cluster Helpers ──────────────────────────────────────────────────────

    /**
     * Build Union-Find from confirmed links and return clusters.
     * Keys are "sideIndex:bboxId".
     */
    fun buildClusters(session: ActiveSession): UnionFind {
        val allKeys = mutableListOf<String>()
        for (side in session.sides) {
            for (bbox in side.bboxes) {
                allKeys.add("${side.sideIndex}:${bbox.id}")
            }
        }
        val uf = UnionFind(allKeys)
        for (link in session.confirmedLinks) {
            val a = "${link.sideA}:${link.bboxIdA}"
            val b = "${link.sideB}:${link.bboxIdB}"
            if (a in allKeys && b in allKeys) {
                uf.union(a, b)
            }
        }
        return uf
    }

    /**
     * Get all bboxes in the same cluster as the given bbox.
     */
    fun getClusterMembers(session: ActiveSession, sideIndex: Int, bboxId: String): List<Triple<Int, Int, Bbox>> {
        val uf = buildClusters(session)
        val key = "$sideIndex:$bboxId"
        val clusterKeys = uf.getCluster(key)
        val result = mutableListOf<Triple<Int, Int, Bbox>>()
        for (side in session.sides) {
            for ((idx, bbox) in side.bboxes.withIndex()) {
                if ("${side.sideIndex}:${bbox.id}" in clusterKeys) {
                    result.add(Triple(side.sideIndex, idx, bbox))
                }
            }
        }
        return result
    }

    /**
     * Stable 1-based link-group number for every linked bbox, keyed by `"sideIndex:bboxId"`.
     *
     * Clusters are numbered by the order their FIRST confirmed link appears in
     * [ActiveSession.confirmedLinks] (insertion order) — NOT by union-find root identity, which
     * reshuffles when a new link changes which node becomes the root (the "badge numbers jump
     * around when I link another bunch" bug). Both endpoints of a link, and every member of a
     * multi-side cluster, share one number — so the matching bunch carries the same number +
     * colour on every side, and in BOTH the carousel and the deduplication view.
     *
     * A link pointing at a bbox that no longer exists on its side is skipped.
     */
    fun linkGroupNumbers(session: ActiveSession): Map<String, Int> {
        if (session.confirmedLinks.isEmpty()) return emptyMap()
        val uf = buildClusters(session)
        val existing = HashSet<String>()
        for (side in session.sides) for (b in side.bboxes) existing.add("${side.sideIndex}:${b.id}")
        val rootNum = HashMap<String, Int>()
        var next = 1
        val result = HashMap<String, Int>()
        for (link in session.confirmedLinks) {
            val ka = "${link.sideA}:${link.bboxIdA}"
            val kb = "${link.sideB}:${link.bboxIdB}"
            if (ka !in existing || kb !in existing) continue // link to a deleted box
            val num = rootNum.getOrPut(uf.find(ka)) { next++ }
            result[ka] = num
            result[kb] = num
        }
        return result
    }

    /** Project [linkGroupNumbers] onto one side: `bboxId → group number` for boxes on [sideIndex]. */
    fun linkGroupForSide(session: ActiveSession, sideIndex: Int): Map<String, Int> {
        val prefix = "$sideIndex:"
        return linkGroupNumbers(session)
            .filterKeys { it.startsWith(prefix) }
            .mapKeys { it.key.removePrefix(prefix) }
    }

    // ─── Class Propagation ────────────────────────────────────────────────────

    /**
     * Propagate a bbox's class to all members of its confirmed cluster.
     * Returns the updated session with the new class applied to all cluster members.
     *
     * Port of JS `propagateClassFromBox(side, id, {propagate:true})`.
     */
    fun propagateClassFromBox(
        session: ActiveSession,
        sideIndex: Int,
        bboxId: String,
    ): ActiveSession {
        val members = getClusterMembers(session, sideIndex, bboxId)
        if (members.size <= 1) return session // no cluster, no propagation

        val sourceBbox = members.find { it.first == sideIndex && it.third.id == bboxId }?.third ?: return session
        val newClassId = sourceBbox.classId
        val newClassName = sourceBbox.className

        val updatedSides = session.sides.map { side ->
            val updatedBboxes = side.bboxes.map { bbox ->
                val isInCluster = members.any { it.first == side.sideIndex && it.third.id == bbox.id }
                if (isInCluster && bbox.classId != newClassId) {
                    bbox.copy(classId = newClassId, className = newClassName)
                } else bbox
            }
            side.copy(bboxes = updatedBboxes)
        }
        return session.copy(sides = updatedSides, dirty = true)
    }

    /**
     * Set a bbox's class and propagate to cluster.
     * Returns the updated session.
     */
    fun setBboxClass(
        session: ActiveSession,
        sideIndex: Int,
        bboxId: String,
        newClass: AnnotationClass,
        propagate: Boolean = true,
    ): ActiveSession {
        // First, update the target bbox
        val updatedSides = session.sides.map { side ->
            if (side.sideIndex != sideIndex) side
            else {
                val updatedBboxes = side.bboxes.map { bbox ->
                    if (bbox.id == bboxId) bbox.copy(classId = newClass.id, className = newClass.displayName)
                    else bbox
                }
                side.copy(bboxes = updatedBboxes)
            }
        }
        var result = session.copy(sides = updatedSides, dirty = true)

        // Then propagate to cluster if requested
        if (propagate) {
            result = propagateClassFromBox(result, sideIndex, bboxId)
        }
        return result
    }

    // ─── Mismatch Detection ───────────────────────────────────────────────────

    data class MismatchCluster(
        val rootKey: String,
        val members: List<Triple<Int, Int, Bbox>>, // (sideIndex, boxIndex, bbox)
        val observedClassIds: Set<Int>,
        val majorityClassId: Int,
    )

    /**
     * Find confirmed clusters that have inconsistent classes across their members.
     * Port of JS `getMismatchedClusters()`.
     */
    fun getMismatchedClusters(session: ActiveSession): List<MismatchCluster> {
        val uf = buildClusters(session)
        val clusters = uf.clusters()
        val mismatches = mutableListOf<MismatchCluster>()

        for ((root, members) in clusters) {
            if (members.size <= 1) continue

            // Collect all bboxes in this cluster
            val memberBboxes = mutableListOf<Triple<Int, Int, Bbox>>()
            for (side in session.sides) {
                for ((idx, bbox) in side.bboxes.withIndex()) {
                    if ("${side.sideIndex}:${bbox.id}" in members) {
                        memberBboxes.add(Triple(side.sideIndex, idx, bbox))
                    }
                }
            }

            val classIds = memberBboxes.map { it.third.classId }.toSet()
            if (classIds.size > 1) {
                // Mismatch! Find majority vote
                val majorityClassId = memberBboxes.groupBy { it.third.classId }
                    .maxByOrNull { it.value.size }?.key ?: 0

                mismatches.add(MismatchCluster(
                    rootKey = root,
                    members = memberBboxes,
                    observedClassIds = classIds,
                    majorityClassId = majorityClassId,
                ))
            }
        }
        return mismatches
    }

    /**
     * Resolve a mismatch cluster by setting all members to the majority class.
     * Returns the updated session.
     */
    fun resolveMismatch(session: ActiveSession, mismatch: MismatchCluster, chosenClassId: Int? = null): ActiveSession {
        val targetClassId = chosenClassId ?: mismatch.majorityClassId
        val newClass = AnnotationClass.fromId(targetClassId)
        val updatedSides = session.sides.map { side ->
            val updatedBboxes = side.bboxes.map { bbox ->
                val isMember = mismatch.members.any { it.first == side.sideIndex && it.third.id == bbox.id }
                if (isMember && bbox.classId != targetClassId) {
                    bbox.copy(classId = newClass.id, className = newClass.displayName)
                } else bbox
            }
            side.copy(bboxes = updatedBboxes)
        }
        return session.copy(sides = updatedSides, dirty = true)
    }

    /**
     * Resolve all mismatches using specified class choices (or majority vote as fallback).
     */
    fun resolveAllMismatches(session: ActiveSession, choices: Map<String, Int>? = null): ActiveSession {
        var result = session
        val maxIter = 10 // safety limit
        for (i in 0 until maxIter) {
            val mismatches = getMismatchedClusters(result)
            if (mismatches.isEmpty()) break
            for (m in mismatches) {
                val chosen = choices?.get(m.rootKey)
                result = resolveMismatch(result, m, chosen)
            }
        }
        return result
    }

    // ─── Link Management ──────────────────────────────────────────────────────

    /**
     * Add a manual cross-side link. Only between adjacent sides.
     * If a link already exists for the same pair of sides touching either endpoint,
     * it is replaced. Port of JS `addManualLink`.
     */
    fun addManualLink(
        session: ActiveSession,
        sideA: Int, bboxIdA: String,
        sideB: Int, bboxIdB: String,
    ): ActiveSession {
        if (!session.isAdjacentPair(sideA, sideB)) return session

        // Orient so sideA < sideB
        val sA: Int; val bA: String; val sB: Int; val bB: String
        if (sideA < sideB) {
            sA = sideA; bA = bboxIdA; sB = sideB; bB = bboxIdB
        } else {
            sA = sideB; bA = bboxIdB; sB = sideA; bB = bboxIdA
        }

        // Remove existing same-pair links that share THIS link's endpoint. Endpoints are
        // per-side: bbox ids are only unique within a side and repeat across sides (b0, b1,
        // det0 …), so compare same-side only — never bboxIdB (side sB) against bA (side sA),
        // which falsely matched an unrelated box and deleted a good link.
        val filtered = session.confirmedLinks.filter { link ->
            val samePair = (link.sideA == sA && link.sideB == sB)
            if (!samePair) return@filter true // keep links on other pairs
            val sharesEndpoint = link.bboxIdA == bA || link.bboxIdB == bB
            !sharesEndpoint
        }

        val linkId = "lnk-${System.nanoTime()}"
        val newLink = CrossSideLink.create(linkId, sA, bA, sB, bB)
        return session.copy(confirmedLinks = filtered + newLink, dirty = true)
    }

    /**
     * Remove a confirmed link by ID.
     */
    fun removeLink(session: ActiveSession, linkId: String): ActiveSession {
        return session.copy(
            confirmedLinks = session.confirmedLinks.filter { it.linkId != linkId },
            dirty = true,
        )
    }

    /**
     * Remove links involving a specific bbox (called when deleting a bbox).
     */
    fun removeLinksForBbox(session: ActiveSession, sideIndex: Int, bboxId: String): ActiveSession {
        return session.copy(
            confirmedLinks = session.confirmedLinks.filter { link ->
                !(link.sideA == sideIndex && link.bboxIdA == bboxId) &&
                !(link.sideB == sideIndex && link.bboxIdB == bboxId)
            },
            suggestedLinks = session.suggestedLinks.filter { link ->
                !(link.sideA == sideIndex && link.bboxIdA == bboxId) &&
                !(link.sideB == sideIndex && link.bboxIdB == bboxId)
            },
            dirty = true,
        )
    }

    // ─── Bbox CRUD ────────────────────────────────────────────────────────────

    /**
     * Add a new UNASSIGNED bbox to a side.
     */
    fun addBbox(session: ActiveSession, sideIndex: Int, x1: Float, y1: Float, x2: Float, y2: Float): ActiveSession {
        val updatedSides = session.sides.map { side ->
            if (side.sideIndex != sideIndex) side
            else {
                val newId = Bbox.nextId(side.bboxes, "b")
                val newBbox = Bbox.unassigned(newId, x1, y1, x2, y2)
                side.copy(bboxes = side.bboxes + newBbox)
            }
        }
        return session.copy(sides = updatedSides, dirty = true)
    }

    /**
     * Update a bbox's coordinates (move/resize).
     */
    fun updateBbox(session: ActiveSession, sideIndex: Int, bboxId: String, x1: Float, y1: Float, x2: Float, y2: Float): ActiveSession {
        val updatedSides = session.sides.map { side ->
            if (side.sideIndex != sideIndex) side
            else {
                val updatedBboxes = side.bboxes.map { bbox ->
                    if (bbox.id == bboxId) bbox.copy(x1 = x1, y1 = y1, x2 = x2, y2 = y2)
                    else bbox
                }
                side.copy(bboxes = updatedBboxes)
            }
        }
        return session.copy(sides = updatedSides, dirty = true)
    }

    /**
     * Delete a bbox and clean up all links involving it.
     */
    fun deleteBbox(session: ActiveSession, sideIndex: Int, bboxId: String): ActiveSession {
        val updatedSides = session.sides.map { side ->
            if (side.sideIndex != sideIndex) side
            else side.copy(bboxes = side.bboxes.filter { it.id != bboxId })
        }
        val updated = session.copy(sides = updatedSides, dirty = true)
        return removeLinksForBbox(updated, sideIndex, bboxId)
    }
}
