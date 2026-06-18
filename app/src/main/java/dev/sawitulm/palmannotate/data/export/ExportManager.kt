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

    /** Capture-date formatter (YYYY-MM-DD, device-local). Used for the metadata `date`
     *  fallback when the session has no stored date. Local tz on purpose: a capture date
     *  is a calendar day, distinct from the UTC `generated_at` export instant. */
    private val dateOnlyFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    // ─── Output JSON v4 ──────────────────────────────────────────────────────

    fun generateOutputJson(session: ActiveSession, results: TreeResults? = null): JSONObject {
        val r = results ?: ResultsComputer.compute(session)
        val totalSides = session.sides.size
        val out = JSONObject()

        out.put("version", 4)
        out.put("tree_id", session.treeName)
        out.put("tree_name", session.treeName)
        out.put("split", session.split)

        // metadata: { date, number, generated_at, variety } — matches the curated
        // example_dataset reference. session_id is intentionally omitted (no equivalent in
        // the native data model). block/treeId/gps live in the separate metadata sidecar.
        val variety = session.metadata?.variety?.takeIf { it.isNotBlank() }
            ?: deriveVariety(session.treeName)
        // date: the capture day (YYYY-MM-DD). Prefer the stored metadata date; fall back to
        // today (device-local) so the field is never blank.
        val date = session.metadata?.date?.takeIf { it.isNotBlank() }
            ?: dateOnlyFormat.format(Date())
        out.put("metadata", JSONObject().apply {
            put("date", date)
            // session_id: {YYYYMMDD}-{VARIETY}-{BLOCK} from the capture data — the native model
            // has no run-sequence counter (the reference's trailing "-001"), so the block is the
            // stable batch identifier. Same for every tree in a block, like a session id should be.
            put("session_id", deriveSessionId(date, variety, session))
            // number: per-tree sequence parsed from the tree-name suffix (…_0001 -> 1),
            // matching the metadata sidecar's treeId. Omitted if the name has no suffix.
            deriveTreeNumber(session.treeName)?.let { put("number", it) }
            put("generated_at", dateFormat.format(Date()))
            put("variety", variety)
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

    /** Capture-batch id `{YYYYMMDD}-{VARIETY}-{BLOCK}` (e.g. 20260618-DAMIMAS-A21B). Block comes
     *  from metadata, falling back to the 2nd `_`-segment of the tree name; the block segment is
     *  dropped only when neither is available, never emitting a dangling trailing dash. */
    private fun deriveSessionId(date: String, variety: String, session: ActiveSession): String {
        val ymd = date.filter { it.isDigit() }
        val block = session.metadata?.block?.takeIf { it.isNotBlank() }
            ?: session.treeName.split("_").getOrNull(1)?.takeIf { it.isNotBlank() }
        return buildString {
            append(ymd); append('-'); append(variety.uppercase())
            if (block != null) { append('-'); append(block.uppercase()) }
        }
    }

    private fun deriveVariety(treeName: String): String {
        val m = Regex("^([A-Za-z]+)_").find(treeName)
        return m?.groupValues?.get(1)?.uppercase() ?: "UNKNOWN"
    }

    /** Per-tree sequence number parsed from the tree-name suffix (…_0001 -> 1), matching
     *  the metadata sidecar's treeId. null if the name has no trailing numeric segment. */
    private fun deriveTreeNumber(treeName: String): Int? =
        Regex("_(\\d+)$").find(treeName)?.groupValues?.get(1)?.toIntOrNull()

    private fun yolo(b: Bbox, w: Int, h: Int): JSONArray {
        val cx = ((b.x1 + b.x2) / 2f) / w
        val cy = ((b.y1 + b.y2) / 2f) / h
        val bw = (b.x2 - b.x1) / w
        val bh = (b.y2 - b.y1) / h
        // Emit JSON numbers (not quoted strings) to match the curated example_dataset reference,
        // rounded to 6 decimals via the string formatter then parsed back — keeps the precision
        // cap and stays locale-safe (the dot is fixed by Locale.US in f6) while serializing as a
        // bare number. bbox_pixel stays integer; the importer reads pixels, so this is output-only.
        return JSONArray().apply { put(cx.f6n()); put(cy.f6n()); put(bw.f6n()); put(bh.f6n()) }
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

    /**
     * 6-decimal value as a plain-decimal JSON number (e.g. 0.200000 -> 0.2), matching the
     * reference dataset's numeric bbox_yolo. Returned as a [BigDecimal] on purpose:
     *  - org.json serializes a BigDecimal verbatim via its toString, so it stays a bare number
     *    AND never flips to scientific notation. A raw Double would: Android's Double.toString
     *    renders values < 1e-3 (a small box, e.g. 0.000306) as "3.06E-4", which diverges from the
     *    reference's plain decimals and can break YOLO parsers.
     *  - stripTrailingZeros matches the reference's JS formatting (0.05625, not 0.056250).
     *  - Non-finite inputs (a box on a 0-dimension side → divide-by-zero) degrade to 0, because
     *    org.json's put(double) THROWS on NaN/Infinity, which would abort the whole tree's JSON.
     */
    private fun Float.f6n(): java.math.BigDecimal {
        if (!isFinite()) return java.math.BigDecimal.ZERO
        val bd = java.math.BigDecimal(f6()).stripTrailingZeros()
        // stripTrailingZeros on an exact zero can yield "0E-6"; normalize to a plain 0.
        return if (bd.signum() == 0) java.math.BigDecimal.ZERO else bd
    }
}
