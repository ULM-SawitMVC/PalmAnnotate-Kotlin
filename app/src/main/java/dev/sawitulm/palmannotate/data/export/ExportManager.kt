package dev.sawitulm.palmannotate.data.export

import dev.sawitulm.palmannotate.domain.model.*
import dev.sawitulm.palmannotate.domain.results.ResultsComputer
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * Export manager — generates Output JSON v4, YOLO .txt, CSV, Identity JSON.
 *
 * Aims for byte-compatibility with the web app (`js/output-schema.js` +
 * `js/results.js`). Notable fidelity points vs the first draft:
 *   - `metadata` is EXACTLY `{ variety, generated_at }` (no block/treeId/gps —
 *     those live in the separate metadata/{tree}.json sidecar, like the JS app).
 *   - `_confirmedLinks` uses box-index-stable ids ("b"+index) oriented to the
 *     adjacent pair and deduped, so re-loading restores links regardless of the
 *     runtime bbox id scheme (matches OutputSchema._orientToAdjacentPair).
 *   - `summary.by_class` includes the `other` bucket.
 */
object ExportManager {

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    // ─── Output JSON v4 ──────────────────────────────────────────────────────

    fun generateOutputJson(session: ActiveSession, results: TreeResults? = null): JSONObject {
        val r = results ?: ResultsComputer.compute(session)
        val totalSides = session.sides.size
        val out = JSONObject()

        out.put("version", 4)
        out.put("tree_id", session.treeName)
        out.put("tree_name", session.treeName)
        out.put("split", session.split)

        // metadata: variety + generated_at ONLY (byte-parity with output-schema.js).
        val variety = session.metadata?.variety?.takeIf { it.isNotBlank() }
            ?: deriveVariety(session.treeName)
        out.put("metadata", JSONObject().apply {
            put("variety", variety)
            put("generated_at", dateFormat.format(Date()))
        })

        // images
        val images = JSONObject()
        for (side in session.sides) {
            val annotations = JSONArray()
            side.bboxes.forEachIndexed { boxIdx, b ->
                annotations.put(JSONObject().apply {
                    put("box_index", boxIdx)
                    put("class_id", b.classId)
                    put("class_name", b.className)
                    put("bbox_yolo", yolo(b, side.imageWidth, side.imageHeight))
                    put("bbox_pixel", pixel(b))
                })
            }
            images.put("side_${side.sideIndex + 1}", JSONObject().apply {
                put("filename", "${session.treeName}_${side.sideIndex + 1}.jpg")
                put("label_file", "${session.treeName}_${side.sideIndex + 1}.txt")
                put("side_index", side.sideIndex)
                put("side_label", side.label)
                put("width", side.imageWidth)
                put("height", side.imageHeight)
                put("bbox_count", side.bboxes.size)
                put("annotations", annotations)
            })
        }
        out.put("images", images)

        // bunches (one per cluster, including singletons; appearances sorted by side_index)
        val bunches = JSONArray()
        var bunchId = 1
        for (members in r.clusters.values) {
            if (members.isEmpty()) continue
            val votes = members.groupBy { it.className }
            val dominant = votes.maxByOrNull { it.value.size }?.key ?: "U"
            val mismatch = votes.keys.size > 1
            val appearances = JSONArray()
            for (m in members.sortedBy { it.sideIndex }) {
                appearances.put(JSONObject().apply {
                    put("side", "side_${m.sideIndex + 1}")
                    put("side_index", m.sideIndex)
                    put("box_index", m.boxIndex)
                    put("class_name", m.className)
                    put("bbox_pixel", JSONArray().apply {
                        put(Math.round(m.x1)); put(Math.round(m.y1)); put(Math.round(m.x2)); put(Math.round(m.y2))
                    })
                })
            }
            bunches.put(JSONObject().apply {
                put("bunch_id", bunchId++)
                put("class", dominant)
                put("class_mismatch", mismatch)
                put("appearance_count", members.size)
                put("appearances", appearances)
            })
        }
        out.put("bunches", bunches)

        // _confirmedLinks — box-index-stable, oriented to adjacent pair, deduped
        out.put("_confirmedLinks", persistConfirmedLinks(session, totalSides))

        // summary
        out.put("summary", JSONObject().apply {
            put("total_unique_bunches", r.uniqueCount)
            put("total_detections", r.rawCount)
            put("duplicates_linked", r.linkedCount)
            put("by_class", JSONObject().apply {
                put("B1", r.classCounts[AnnotationClass.B1] ?: 0)
                put("B2", r.classCounts[AnnotationClass.B2] ?: 0)
                put("B3", r.classCounts[AnnotationClass.B3] ?: 0)
                put("B4", r.classCounts[AnnotationClass.B4] ?: 0)
                put("other", r.classCounts[AnnotationClass.UNASSIGNED] ?: 0)
            })
            put("by_side", JSONObject().apply {
                for (side in session.sides) put("side_${side.sideIndex + 1}", side.bboxes.size)
            })
        })

        return out
    }

    /** Persist confirmed links with box-index-stable ids oriented to adjacent pairs. */
    private fun persistConfirmedLinks(session: ActiveSession, totalSides: Int): JSONArray {
        val pairMap = adjacentPairMap(totalSides)
        val pairSet = pairMap.keys
        // sideIndex -> (bboxId -> boxIndex)
        val indexMap = HashMap<Int, Map<String, Int>>()
        for (side in session.sides) {
            indexMap[side.sideIndex] = side.bboxes.mapIndexed { i, b -> b.id to i }.toMap()
        }
        val seen = HashSet<String>()
        val arr = JSONArray()
        for (link in session.confirmedLinks) {
            val idxA = indexMap[link.sideA]?.get(link.bboxIdA) ?: continue
            val idxB = indexMap[link.sideB]?.get(link.bboxIdB) ?: continue
            val pk = pairKey(link.sideA, link.sideB)
            if (pk !in pairSet) continue
            val (oSideA, oIdxA, oSideB, oIdxB) = orient(link.sideA, idxA, link.sideB, idxB, pairMap[pk]!!)
            val dedup = endpointKey(oSideA, "b$oIdxA", oSideB, "b$oIdxB")
            if (!seen.add(dedup)) continue
            arr.put(JSONObject().apply {
                put("linkId", link.linkId)
                put("sideA", oSideA); put("bboxIdA", "b$oIdxA")
                put("sideB", oSideB); put("bboxIdB", "b$oIdxB")
            })
        }
        return arr
    }

    private fun adjacentPairMap(totalSides: Int): Map<String, Pair<Int, Int>> {
        val m = LinkedHashMap<String, Pair<Int, Int>>()
        for ((a, b) in generateAdjacentPairs(totalSides)) m[pairKey(a, b)] = a to b
        return m
    }

    private fun pairKey(a: Int, b: Int) = if (a < b) "$a:$b" else "$b:$a"

    private fun endpointKey(sideA: Int, idA: String, sideB: Int, idB: String): String {
        val a = "$sideA:$idA"; val b = "$sideB:$idB"
        return if (a < b) "$a|$b" else "$b|$a"
    }

    private data class Oriented(val sideA: Int, val idxA: Int, val sideB: Int, val idxB: Int)

    private fun orient(sideA: Int, idxA: Int, sideB: Int, idxB: Int, tuple: Pair<Int, Int>): Oriented {
        return when {
            sideA == tuple.first && sideB == tuple.second -> Oriented(sideA, idxA, sideB, idxB)
            sideA == tuple.second && sideB == tuple.first -> Oriented(sideB, idxB, sideA, idxA)
            else -> Oriented(sideA, idxA, sideB, idxB)
        }
    }

    private fun deriveVariety(treeName: String): String {
        val m = Regex("^([A-Za-z]+)_").find(treeName)
        return m?.groupValues?.get(1)?.uppercase() ?: "UNKNOWN"
    }

    private fun yolo(b: Bbox, w: Int, h: Int): JSONArray {
        val cx = ((b.x1 + b.x2) / 2f) / w
        val cy = ((b.y1 + b.y2) / 2f) / h
        val bw = (b.x2 - b.x1) / w
        val bh = (b.y2 - b.y1) / h
        return JSONArray().apply { put(cx.f6()); put(cy.f6()); put(bw.f6()); put(bh.f6()) }
    }

    private fun pixel(b: Bbox): JSONArray = JSONArray().apply {
        put(Math.round(b.x1)); put(Math.round(b.y1)); put(Math.round(b.x2)); put(Math.round(b.y2))
    }

    // ─── YOLO .txt ────────────────────────────────────────────────────────────

    /** YOLO label content for a side — assigned classes only, 6 decimals. */
    fun generateYoloTxt(side: TreeSide): String =
        side.bboxes.filter { it.isAssigned }.joinToString("\n") { b ->
            val cx = ((b.x1 + b.x2) / 2f) / side.imageWidth
            val cy = ((b.y1 + b.y2) / 2f) / side.imageHeight
            val w = (b.x2 - b.x1) / side.imageWidth
            val h = (b.y2 - b.y1) / side.imageHeight
            "${b.classId} ${cx.f6()} ${cy.f6()} ${w.f6()} ${h.f6()}"
        }

    /** Mismatch YOLO label content — assigned boxes whose id is in [mismatchBboxIds]. */
    fun generateYoloMismatchTxt(side: TreeSide, mismatchBboxIds: Set<String>): String =
        side.bboxes.filter { it.isAssigned && it.id in mismatchBboxIds }.joinToString("\n") { b ->
            val cx = ((b.x1 + b.x2) / 2f) / side.imageWidth
            val cy = ((b.y1 + b.y2) / 2f) / side.imageHeight
            val w = (b.x2 - b.x1) / side.imageWidth
            val h = (b.y2 - b.y1) / side.imageHeight
            "${b.classId} ${cx.f6()} ${cy.f6()} ${w.f6()} ${h.f6()}"
        }

    // ─── CSV ────────────────────────────────────────────────────────────────────

    fun generateCsv(session: ActiveSession, results: TreeResults): String {
        val b1 = results.classCounts[AnnotationClass.B1] ?: 0
        val b2 = results.classCounts[AnnotationClass.B2] ?: 0
        val b3 = results.classCounts[AnnotationClass.B3] ?: 0
        val b4 = results.classCounts[AnnotationClass.B4] ?: 0
        return "tree_name,split,unique,raw,B1,B2,B3,B4\n" +
            "${session.treeName},${session.split},${results.uniqueCount},${results.rawCount},$b1,$b2,$b3,$b4"
    }

    // ─── Identity JSON ──────────────────────────────────────────────────────────

    fun generateIdentityJson(session: ActiveSession, results: TreeResults): JSONObject {
        val out = JSONObject()
        out.put("tree_name", session.treeName)
        out.put("totalUniqueBunches", results.uniqueCount)
        var mismatchCount = 0
        val bunches = JSONArray()
        var bunchId = 1
        for (members in results.clusters.values) {
            if (members.isEmpty()) continue
            val classes = members.map { it.className }.toSet()
            val hasMismatch = classes.size > 1
            if (hasMismatch) mismatchCount++
            bunches.put(JSONObject().apply {
                put("id", bunchId++)
                put("classMismatch", hasMismatch)
                put("detections", JSONArray().apply {
                    for (m in members) put(JSONObject().apply {
                        put("side", m.sideIndex)
                        put("bboxId", m.bboxId)
                        put("class", m.className)
                        put("coords", JSONArray().apply { put(m.x1); put(m.y1); put(m.x2); put(m.y2) })
                    })
                })
            })
        }
        out.put("classMismatchCount", mismatchCount)
        out.put("bunches", bunches)
        return out
    }

    // ─── File writing ─────────────────────────────────────────────────────────

    fun writeExports(session: ActiveSession, outputDir: File, results: TreeResults? = null) {
        val r = results ?: ResultsComputer.compute(session)
        outputDir.mkdirs()
        File(outputDir, "${session.treeName}.json").writeText(generateOutputJson(session, r).toString(2))
        for (side in session.sides) {
            val yolo = generateYoloTxt(side)
            if (yolo.isNotBlank()) {
                val labelDir = File(outputDir, "Output TXT/${if (session.split == "unknown") "field" else session.split}")
                labelDir.mkdirs()
                File(labelDir, "${session.treeName}_${side.sideIndex + 1}.txt").writeText(yolo)
            }
        }
        File(outputDir, "${session.treeName}_result.csv").writeText(generateCsv(session, r))
        File(outputDir, "${session.treeName}_identity.json").writeText(generateIdentityJson(session, r).toString(2))
    }

    private fun Float.f6(): String = String.format(Locale.US, "%.6f", this)
}
