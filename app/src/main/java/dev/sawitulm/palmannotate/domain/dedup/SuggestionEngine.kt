package dev.sawitulm.palmannotate.domain.dedup

import dev.sawitulm.palmannotate.domain.model.ActiveSession
import dev.sawitulm.palmannotate.domain.model.Bbox
import dev.sawitulm.palmannotate.domain.model.SuggestedPair
import dev.sawitulm.palmannotate.domain.model.SuggestionSignals
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Pure-geometry cross-side duplicate suggestion engine — a faithful port of
 * `js/dedup-utils.js` `suggestPairs`. It is intentionally NOT plain IoU.
 *
 * Photography geometry (clockwise rotation around the tree):
 *   sideA's LEFT edge meets sideB's RIGHT edge at the shared corner.
 *
 * Algorithm:
 *   1. HARD gate by "seam band": only bboxes whose centre lies in the half of the
 *      image closest to the shared edge are candidates (A: cx ≤ band; B: cx ≥ 1−band).
 *   2. HARD gate by size ratio: drop pairs with min(area)/max(area) < sizeRatioMin.
 *   3. Score = (0.45·seam + 0.35·vert + 0.20·size) · classMultiplier, clamped 0..1.
 *      class is a PENALTY multiplier (1.0 same / 0.85 ±1 grade / 0.5 otherwise).
 *   4. MUTUAL-BEST assignment (A's top pick is B and B's top pick is A), else greedy.
 *   Category: "auto" if score ≥ autoMin, else "candidate".
 */
object SuggestionEngine {

    // Defaults mirror dedup-utils.js suggestPairs(opts).
    const val AUTO_MIN = 0.75f
    const val CANDIDATE_MIN = 0.50f
    const val SEAM_BAND_FRACTION = 0.50f
    const val VERT_TOL = 0.20f
    const val SIZE_RATIO_MIN = 0.30f

    /** Normalised bbox in [0,1]. */
    private data class NBox(val x1: Float, val y1: Float, val x2: Float, val y2: Float)

    private fun clamp(v: Float, lo: Float, hi: Float) = if (v < lo) lo else if (v > hi) hi else v

    private fun norm(b: Bbox, w: Int, h: Int) =
        NBox(b.x1 / w, b.y1 / h, b.x2 / w, b.y2 / h)

    /** Vertical-centre proximity within tolerance (gravity keeps bunches at trunk height). */
    private fun vertSig(a: NBox, b: NBox, tol: Float): Float {
        val cyA = (a.y1 + a.y2) / 2f
        val cyB = (b.y1 + b.y2) / 2f
        return 1f - clamp(abs(cyA - cyB) / tol, 0f, 1f)
    }

    /** Class as a PENALTY multiplier: same → 1.0, ±1 grade → 0.85, else → 0.5. */
    private fun classMultiplier(a: Bbox, b: Bbox): Float = when {
        a.classId == b.classId -> 1.0f
        abs(a.classId - b.classId) == 1 -> 0.85f
        else -> 0.5f
    }

    /** Size + aspect similarity (0.6·area + 0.4·aspect). */
    private fun sizeSig(a: NBox, b: NBox): Float {
        val eps = 1e-6f
        val areaA = max((a.x2 - a.x1) * (a.y2 - a.y1), eps)
        val areaB = max((b.x2 - b.x1) * (b.y2 - b.y1), eps)
        val areaSim = 1f - clamp(abs(areaA - areaB) / max(areaA, areaB), 0f, 1f)
        val arA = max((a.x2 - a.x1) / max(a.y2 - a.y1, eps), eps)
        val arB = max((b.x2 - b.x1) / max(b.y2 - b.y1, eps), eps)
        val aspectSim = 1f - clamp(abs(arA - arB) / max(arA, arB), 0f, 1f)
        return 0.6f * areaSim + 0.4f * aspectSim
    }

    private data class Scored(
        val aIdx: Int, val bIdx: Int,
        val bboxIdA: String, val bboxIdB: String,
        val score: Float, val signals: SuggestionSignals,
    )

    private data class Gated(val b: Bbox, val nb: NBox, val cx: Float)

    /** Image dimensions for one side. */
    data class Img(val w: Int, val h: Int)

    /**
     * Suggest cross-side duplicate pairs between two sides. Side indices on the
     * returned pairs are placeholders (0); [suggestAll] fills the real indices.
     */
    fun suggestPairs(
        bboxesA: List<Bbox>, imgA: Img,
        bboxesB: List<Bbox>, imgB: Img,
        autoMin: Float = AUTO_MIN,
        candidateMin: Float = CANDIDATE_MIN,
        seamBandFraction: Float = SEAM_BAND_FRACTION,
        vertTol: Float = VERT_TOL,
        sizeRatioMin: Float = SIZE_RATIO_MIN,
        mutualBest: Boolean = true,
        clockwise: Boolean = true,
    ): List<SuggestedPair> {
        if (imgA.w <= 0 || imgA.h <= 0 || imgB.w <= 0 || imgB.h <= 0) return emptyList()

        // Which image edge holds the shared seam depends on capture direction:
        //   clockwise  → sideA's LEFT edge meets sideB's RIGHT edge
        //   counter-cw → sideA's RIGHT edge meets sideB's LEFT edge
        val seamOnLeftA = clockwise
        val seamOnLeftB = !clockwise

        // Stage 1: hard seam-band gate (per side, on the seam edge for this direction).
        val gatedA = ArrayList<Gated>()
        for (b in bboxesA) {
            val nb = norm(b, imgA.w, imgA.h)
            val cx = (nb.x1 + nb.x2) / 2f
            val inBand = if (seamOnLeftA) cx <= seamBandFraction else cx >= (1f - seamBandFraction)
            if (inBand) gatedA.add(Gated(b, nb, cx))
        }
        val gatedB = ArrayList<Gated>()
        for (b in bboxesB) {
            val nb = norm(b, imgB.w, imgB.h)
            val cx = (nb.x1 + nb.x2) / 2f
            val inBand = if (seamOnLeftB) cx <= seamBandFraction else cx >= (1f - seamBandFraction)
            if (inBand) gatedB.add(Gated(b, nb, cx))
        }

        // Stage 2: score every surviving cross-side pair.
        val scored = ArrayList<Scored>()
        val eps = 1e-6f
        for (i in gatedA.indices) {
            val ga = gatedA[i]
            for (j in gatedB.indices) {
                val gb = gatedB[j]
                val areaA = max((ga.nb.x2 - ga.nb.x1) * (ga.nb.y2 - ga.nb.y1), eps)
                val areaB = max((gb.nb.x2 - gb.nb.x1) * (gb.nb.y2 - gb.nb.y1), eps)
                val sizeRatio = min(areaA, areaB) / max(areaA, areaB)
                if (sizeRatio < sizeRatioMin) continue

                // Distance of each centre from its seam edge (0 = on the seam).
                val seamA = if (seamOnLeftA) 1f - clamp(ga.cx / seamBandFraction, 0f, 1f)
                            else 1f - clamp((1f - ga.cx) / seamBandFraction, 0f, 1f)
                val seamB = if (seamOnLeftB) 1f - clamp(gb.cx / seamBandFraction, 0f, 1f)
                            else 1f - clamp((1f - gb.cx) / seamBandFraction, 0f, 1f)
                val seamSig = (seamA + seamB) / 2f
                val vSig = vertSig(ga.nb, gb.nb, vertTol)
                val sSig = sizeSig(ga.nb, gb.nb)
                val classMult = classMultiplier(ga.b, gb.b)

                val base = 0.45f * seamSig + 0.35f * vSig + 0.20f * sSig
                val score = clamp(base * classMult, 0f, 1f)
                if (score < candidateMin) continue

                scored.add(
                    Scored(
                        aIdx = i, bIdx = j,
                        bboxIdA = ga.b.id, bboxIdB = gb.b.id,
                        score = score,
                        signals = SuggestionSignals(
                            seam = round3(seamSig), vert = round3(vSig), size = round3(sSig),
                            cls = round2(classMult), sizeRatio = round3(sizeRatio),
                        ),
                    )
                )
            }
        }

        // Stage 3: pair selection.
        val chosen: List<Scored> = if (mutualBest) {
            val bestForA = HashMap<Int, Scored>()
            val bestForB = HashMap<Int, Scored>()
            for (p in scored) {
                val cA = bestForA[p.aIdx]
                if (cA == null || p.score > cA.score) bestForA[p.aIdx] = p
                val cB = bestForB[p.bIdx]
                if (cB == null || p.score > cB.score) bestForB[p.bIdx] = p
            }
            bestForA.values.filter { p -> bestForB[p.bIdx]?.aIdx == p.aIdx }
        } else {
            val usedA = HashSet<Int>(); val usedB = HashSet<Int>()
            val out = ArrayList<Scored>()
            for (p in scored.sortedByDescending { it.score }) {
                if (p.aIdx in usedA || p.bIdx in usedB) continue
                usedA.add(p.aIdx); usedB.add(p.bIdx); out.add(p)
            }
            out
        }

        return chosen.map { p ->
            SuggestedPair(
                sideA = 0, bboxIdA = p.bboxIdA,
                sideB = 0, bboxIdB = p.bboxIdB,
                score = p.score,
                category = if (p.score >= autoMin) "auto" else "candidate",
                signals = p.signals,
            )
        }
    }

    /**
     * Suggest duplicates across all adjacent side pairs in a session, using each
     * side's real image dimensions. Mirrors JS `ActiveSession.runSuggestions()`.
     */
    fun suggestAll(session: ActiveSession, clockwise: Boolean = true): List<SuggestedPair> {
        val byIndex = session.sides.associateBy { it.sideIndex }
        val out = ArrayList<SuggestedPair>()
        for ((iA, iB) in session.adjacentPairs) {
            val sA = byIndex[iA] ?: continue
            val sB = byIndex[iB] ?: continue
            if (sA.imageWidth <= 0 || sB.imageWidth <= 0) continue
            val pairs = suggestPairs(
                sA.bboxes, Img(sA.imageWidth, sA.imageHeight),
                sB.bboxes, Img(sB.imageWidth, sB.imageHeight),
                clockwise = clockwise,
            )
            for (p in pairs) {
                // Skip pairs already confirmed (matches runSuggestions dedupe).
                val alreadyConfirmed = session.confirmedLinks.any {
                    it.sideA == iA && it.bboxIdA == p.bboxIdA && it.sideB == iB && it.bboxIdB == p.bboxIdB ||
                    it.sideA == iB && it.bboxIdA == p.bboxIdB && it.sideB == iA && it.bboxIdB == p.bboxIdA
                }
                if (alreadyConfirmed) continue
                out.add(p.copy(sideA = iA, sideB = iB))
            }
        }
        return out
    }

    /**
     * Overload kept for callers that only have raw bbox lists per side index
     * (no image dims): assumes already-normalised coords are unavailable, so it
     * requires dims via [Img]. Prefer [suggestAll](session).
     */
    fun suggestAll(
        sides: Map<Int, List<Bbox>>,
        sideDims: Map<Int, Img>,
        adjacentPairs: List<Pair<Int, Int>>,
    ): List<SuggestedPair> {
        val out = ArrayList<SuggestedPair>()
        for ((iA, iB) in adjacentPairs) {
            val a = sides[iA] ?: emptyList()
            val b = sides[iB] ?: emptyList()
            val da = sideDims[iA] ?: continue
            val db = sideDims[iB] ?: continue
            suggestPairs(a, da, b, db).forEach { out.add(it.copy(sideA = iA, sideB = iB)) }
        }
        return out
    }

    private fun round3(v: Float): Float = Math.round(v * 1000f) / 1000f
    private fun round2(v: Float): Float = Math.round(v * 100f) / 100f
}
