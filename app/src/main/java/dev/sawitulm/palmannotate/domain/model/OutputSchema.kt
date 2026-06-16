package dev.sawitulm.palmannotate.domain.model

import org.json.JSONObject

/**
 * Round-trip READER for Output JSON v4 — a port of `output-schema.js`
 * `toSessionJSON`. The WRITER lives in `data.export.ExportManager` (single source
 * of truth); this only parses a saved Output JSON back into session-shaped data so
 * a previously-saved tree resumes from its JSON instead of the raw labels.
 *
 * Bbox ids are reconstructed as `b<box_index>` so they line up with the ids
 * `YoloParser` assigns when re-parsing labels, keeping `confirmedLinks` valid.
 * Confirmed links come from `_confirmedLinks` when present, else are rebuilt from
 * `bunches` appearances (all adjacent pairs within a bunch). Suggestions are NOT
 * restored (the user can re-run them).
 */
object OutputSchema {

    data class ParsedBbox(
        val id: String, val classId: Int, val className: String,
        val x1: Float, val y1: Float, val x2: Float, val y2: Float,
    )

    data class ParsedSide(
        val sideIndex: Int, val imageWidth: Int, val imageHeight: Int,
        val bboxes: List<ParsedBbox>,
    )

    /** A link oriented to its adjacent pair (sideA may be > sideB for the wraparound pair). */
    data class ParsedLink(val linkId: String, val sideA: Int, val bboxIdA: String, val sideB: Int, val bboxIdB: String)

    data class ParsedOutput(
        val treeName: String, val split: String,
        val sides: List<ParsedSide>, val confirmedLinks: List<ParsedLink>,
    )

    fun toSessionData(json: JSONObject): ParsedOutput {
        require(json.optInt("version", 0) == 4) { "Invalid output JSON: missing or wrong version" }
        val treeName = json.optString("tree_name").ifBlank { json.optString("tree_id") }
        require(treeName.isNotEmpty()) { "Invalid output JSON: missing tree_name/tree_id" }
        val split = json.optString("split", "field")

        // Sides (sorted by side_index), bbox ids = b<box_index>.
        val images = json.optJSONObject("images") ?: JSONObject()
        val sides = ArrayList<ParsedSide>()
        for (key in images.keys()) {
            val s = images.getJSONObject(key)
            val sideIndex = s.optInt("side_index", key.removePrefix("side_").toIntOrNull()?.minus(1) ?: continue)
            val anns = s.optJSONArray("annotations") ?: org.json.JSONArray()
            val bboxes = ArrayList<ParsedBbox>()
            for (i in 0 until anns.length()) {
                val a = anns.getJSONObject(i)
                val px = a.optJSONArray("bbox_pixel") ?: continue
                val boxIndex = a.optInt("box_index", i)
                val classId = a.optInt("class_id", AnnotationClass.fromName(a.optString("class_name", "U")).id)
                val cls = AnnotationClass.fromId(classId)
                bboxes.add(
                    ParsedBbox(
                        id = "b$boxIndex", classId = cls.id, className = cls.displayName,
                        x1 = px.optDouble(0, 0.0).toFloat(), y1 = px.optDouble(1, 0.0).toFloat(),
                        x2 = px.optDouble(2, 0.0).toFloat(), y2 = px.optDouble(3, 0.0).toFloat(),
                    )
                )
            }
            sides.add(ParsedSide(sideIndex, s.optInt("width", s.optInt("imageWidth")), s.optInt("height", s.optInt("imageHeight")), bboxes))
        }
        sides.sortBy { it.sideIndex }

        val confirmed = parseConfirmedLinks(json, sides)
        return ParsedOutput(treeName, split, sides, confirmed)
    }

    private fun parseConfirmedLinks(json: JSONObject, sides: List<ParsedSide>): List<ParsedLink> {
        val totalSides = sides.size
        val pairMap = LinkedHashMap<String, Pair<Int, Int>>()
        for ((a, b) in generateAdjacentPairs(totalSides)) pairMap[pairKey(a, b)] = a to b
        val idsBySide = sides.associate { side -> side.sideIndex to side.bboxes.map { it.id }.toSet() }
        val seen = HashSet<String>()
        val out = ArrayList<ParsedLink>()
        var seq = 0

        fun push(sideA: Int, idA: String, sideB: Int, idB: String) {
            val pk = pairKey(sideA, sideB)
            val tuple = pairMap[pk] ?: return
            if (idsBySide[sideA]?.contains(idA) != true) return
            if (idsBySide[sideB]?.contains(idB) != true) return
            val (oSideA, oIdA, oSideB, oIdB) =
                if (sideA == tuple.first) Quad(sideA, idA, sideB, idB) else Quad(sideB, idB, sideA, idA)
            val dedup = endpointKey(oSideA, oIdA, oSideB, oIdB)
            if (!seen.add(dedup)) return
            out.add(ParsedLink("L${seq++}", oSideA, oIdA, oSideB, oIdB))
        }

        val explicit = json.optJSONArray("_confirmedLinks")
        if (explicit != null && explicit.length() > 0) {
            for (i in 0 until explicit.length()) {
                val l = explicit.optJSONObject(i) ?: continue
                val sideA = l.optInt("sideA", Int.MIN_VALUE)
                val sideB = l.optInt("sideB", Int.MIN_VALUE)
                val idA = l.optString("bboxIdA", "")
                val idB = l.optString("bboxIdB", "")
                if (sideA == Int.MIN_VALUE || sideB == Int.MIN_VALUE || idA.isBlank() || idB.isBlank()) continue
                push(sideA, idA, sideB, idB)
            }
        } else {
            // Rebuild from bunches: every adjacent pair within a bunch is a link.
            val bunches = json.optJSONArray("bunches") ?: org.json.JSONArray()
            for (i in 0 until bunches.length()) {
                val apps = bunches.getJSONObject(i).optJSONArray("appearances") ?: continue
                val nodes = (0 until apps.length()).mapNotNull { k ->
                    val ap = apps.getJSONObject(k)
                    val si = ap.optInt("side_index", Int.MIN_VALUE)
                    val bi = ap.optInt("box_index", Int.MIN_VALUE)
                    if (si == Int.MIN_VALUE || bi == Int.MIN_VALUE) null else si to bi
                }.sortedWith(compareBy({ it.first }, { it.second }))
                for (x in nodes.indices) for (y in x + 1 until nodes.size) {
                    push(nodes[x].first, "b${nodes[x].second}", nodes[y].first, "b${nodes[y].second}")
                }
            }
        }
        return out
    }

    // Data class auto-generates component1..4 for the destructuring above.
    private data class Quad(val a: Int, val b: String, val c: Int, val d: String)
    private fun pairKey(a: Int, b: Int) = if (a < b) "$a:$b" else "$b:$a"
    private fun endpointKey(sideA: Int, idA: String, sideB: Int, idB: String): String {
        val a = "$sideA:$idA"; val b = "$sideB:$idB"
        return if (a < b) "$a|$b" else "$b|$a"
    }
}
