package dev.sawitulm.palmannotate

import dev.sawitulm.palmannotate.data.export.ExportManager
import dev.sawitulm.palmannotate.domain.model.*
import dev.sawitulm.palmannotate.domain.results.ResultsComputer
import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

// ════════════════════════════════════════════════════════════════════════════════
// ExportManager — the Output JSON / YOLO / CSV contract. These guard the training-data
// format (a silent regression here corrupts every export). Requires real org.json on the
// test classpath (testImplementation org.json:json) — the android.jar stub throws "Stub!".
// ════════════════════════════════════════════════════════════════════════════════

private fun side(i: Int, bboxes: List<Bbox>, w: Int = 1000, h: Int = 2000) =
    TreeSide(i, "Side ${i + 1}", null, null, w, h, bboxes, emptyList())

private fun box(id: String, classId: Int, x1: Float = 100f, y1: Float = 200f, x2: Float = 300f, y2: Float = 600f) =
    Bbox(id, classId, AnnotationClass.fromId(classId).displayName, x1, y1, x2, y2)

private fun session(
    treeName: String,
    sides: List<TreeSide>,
    links: List<CrossSideLink> = emptyList(),
    metadata: TreeMetadata? = null,
    split: String = "field",
) = ActiveSession("s1", treeName, split, sides, emptyList(), links, metadata)

class ExportMetadataTest {

    @Test fun `number is derived from the tree-name numeric suffix`() {
        val out = ExportManager.generateOutputJson(session("DAMIMAS_A21B_0001", listOf(side(0, emptyList()))))
        val meta = out.getJSONObject("metadata")
        assertTrue(meta.has("number"))
        assertEquals(1, meta.getInt("number"))
    }

    @Test fun `number is omitted when the name has no numeric suffix`() {
        val out = ExportManager.generateOutputJson(session("TENERA_BLOCKA", listOf(side(0, emptyList()))))
        assertFalse(out.getJSONObject("metadata").has("number"))
    }

    @Test fun `variety comes from metadata when present`() {
        val out = ExportManager.generateOutputJson(
            session("x_0007", listOf(side(0, emptyList())), metadata = TreeMetadata(variety = "Tenera", block = "A1")),
        )
        assertEquals("Tenera", out.getJSONObject("metadata").getString("variety"))
    }

    @Test fun `variety is derived from the name prefix when metadata is absent`() {
        val out = ExportManager.generateOutputJson(session("damimas_a21b_0001", listOf(side(0, emptyList()))))
        // prefix up to first underscore, uppercased
        assertEquals("DAMIMAS", out.getJSONObject("metadata").getString("variety"))
    }

    @Test fun `date uses stored metadata date when present`() {
        val out = ExportManager.generateOutputJson(
            session("x_1", listOf(side(0, emptyList())), metadata = TreeMetadata(variety = "T", date = "2026-01-15")),
        )
        assertEquals("2026-01-15", out.getJSONObject("metadata").getString("date"))
    }

    @Test fun `date falls back to a YYYY-MM-DD string and generated_at is present`() {
        val meta = ExportManager.generateOutputJson(session("x_1", listOf(side(0, emptyList())))).getJSONObject("metadata")
        assertTrue("date is an ISO calendar day", meta.getString("date").matches(Regex("\\d{4}-\\d{2}-\\d{2}")))
        assertTrue("generated_at present", meta.getString("generated_at").isNotBlank())
    }

    @Test fun `session_id is date-variety-block from metadata`() {
        val out = ExportManager.generateOutputJson(session(
            "DAMIMAS_A21B_0006",
            listOf(side(0, emptyList())),
            metadata = TreeMetadata(variety = "DAMIMAS", block = "A21B", date = "2026-06-18"),
        ))
        assertEquals("20260618-DAMIMAS-A21B", out.getJSONObject("metadata").getString("session_id"))
    }

    @Test fun `session_id falls back to the block segment of the tree name`() {
        // No metadata block → take the 2nd underscore segment (A21B), uppercased.
        val out = ExportManager.generateOutputJson(session(
            "damimas_a21b_0006", listOf(side(0, emptyList())),
            metadata = TreeMetadata(variety = "DAMIMAS", date = "2026-06-18"),
        ))
        assertEquals("20260618-DAMIMAS-A21B", out.getJSONObject("metadata").getString("session_id"))
    }
}

class ExportOutputJsonTest {

    @Test fun `top-level schema fields`() {
        val out = ExportManager.generateOutputJson(session("T_0001", listOf(side(0, listOf(box("b0", 0)))), split = "train"))
        assertEquals(4, out.getInt("version"))
        assertEquals("T_0001", out.getString("tree_id"))
        assertEquals("T_0001", out.getString("tree_name"))
        assertEquals("train", out.getString("split"))
    }

    @Test fun `images side block carries filenames, dims and annotations`() {
        val out = ExportManager.generateOutputJson(session("T_0001", listOf(side(0, listOf(box("b0", 0), box("b1", 2))))))
        val s1 = out.getJSONObject("images").getJSONObject("side_1")
        assertEquals("T_0001_1.jpg", s1.getString("filename"))
        assertEquals("T_0001_1.txt", s1.getString("label_file"))
        assertEquals(0, s1.getInt("side_index"))
        assertEquals(2, s1.getInt("bbox_count"))
        val ann0 = s1.getJSONArray("annotations").getJSONObject(0)
        assertEquals(0, ann0.getInt("box_index"))
        assertEquals(0, ann0.getInt("class_id"))
        assertEquals("B1", ann0.getString("class_name"))
        assertEquals(4, ann0.getJSONArray("bbox_yolo").length())
        assertEquals(4, ann0.getJSONArray("bbox_pixel").length())
    }

    @Test fun `bbox_yolo is normalized center-form numbers with 6-decimal precision`() {
        // box 100..300 x, 200..600 y on a 1000x2000 image → cx .2 cy .2 w .2 h .2.
        // Emitted as JSON numbers (not strings), matching the curated example_dataset reference.
        val out = ExportManager.generateOutputJson(session("T_0001", listOf(side(0, listOf(box("b0", 0))))))
        val yolo = out.getJSONObject("images").getJSONObject("side_1")
            .getJSONArray("annotations").getJSONObject(0).getJSONArray("bbox_yolo")
        assertTrue("bbox_yolo elements are JSON numbers, not strings", yolo.get(0) is Number)
        assertEquals(0.2, yolo.getDouble(0), 1e-9)
        assertEquals(0.2, yolo.getDouble(1), 1e-9)
        assertEquals(0.2, yolo.getDouble(2), 1e-9)
        assertEquals(0.2, yolo.getDouble(3), 1e-9)
    }

    @Test fun `bbox_yolo on a zero-dimension side degrades to finite numbers, not a crash`() {
        // A box on a side with width/height 0 makes coord/dim = Infinity/NaN. org.json's
        // put(double) throws on those, which would abort the tree's whole JSON. Must stay finite.
        val out = ExportManager.generateOutputJson(session("T_0001",
            listOf(side(0, listOf(box("b0", 0)), w = 0, h = 0))))
        val yolo = out.getJSONObject("images").getJSONObject("side_1")
            .getJSONArray("annotations").getJSONObject(0).getJSONArray("bbox_yolo")
        for (i in 0 until 4) {
            val v = yolo.getDouble(i)
            assertTrue("bbox_yolo[$i] is finite", v.isFinite())
        }
    }

    @Test fun `bbox_yolo caps precision at six decimals`() {
        // 1/3 width box → 0.333333… must round to 6 decimals (no long float tail).
        val out = ExportManager.generateOutputJson(session("T_0001",
            listOf(side(0, listOf(box("b0", 0, x1 = 0f, x2 = 1000f, y1 = 0f, y2 = 2000f / 3f)), w = 3000))))
        val cx = out.getJSONObject("images").getJSONObject("side_1")
            .getJSONArray("annotations").getJSONObject(0).getJSONArray("bbox_yolo").getDouble(0)
        // center x of 0..1000 on width 3000 = 500/3000 = 0.16666… → 0.166667
        assertEquals(0.166667, cx, 1e-9)
    }

    @Test fun `summary by_class includes the other bucket and by_side`() {
        val out = ExportManager.generateOutputJson(session("T_0001", listOf(
            side(0, listOf(box("b0", 0), Bbox.unassigned("b1", 0f, 0f, 50f, 50f))),
        )))
        val summary = out.getJSONObject("summary")
        val byClass = summary.getJSONObject("by_class")
        assertTrue(byClass.has("B1"))
        assertTrue(byClass.has("other"))
        assertEquals(1, byClass.getInt("B1"))
        assertEquals(1, byClass.getInt("other"))
        assertEquals(2, summary.getJSONObject("by_side").getInt("side_1"))
    }

    @Test fun `bunch records a class mismatch across a linked cluster`() {
        val out = ExportManager.generateOutputJson(session(
            "T_0001",
            sides = listOf(side(0, listOf(box("b0", 0))), side(1, listOf(box("b0", 2)))),
            links = listOf(CrossSideLink.create("L0", 0, "b0", 1, "b0")),
        ))
        val bunches = out.getJSONArray("bunches")
        assertEquals(1, bunches.length())
        assertTrue(bunches.getJSONObject(0).getBoolean("class_mismatch"))
        assertEquals(2, bunches.getJSONObject(0).getInt("appearance_count"))
    }

    @Test fun `confirmed links are oriented to the adjacent pair with box-index ids`() {
        // Link given as (side1 -> side0); persisted form must be oriented sideA < sideB
        // with stable b{index} ids.
        val out = ExportManager.generateOutputJson(session(
            "T_0001",
            sides = listOf(side(0, listOf(box("b0", 0))), side(1, listOf(box("b0", 0)))),
            links = listOf(CrossSideLink.create("L0", 1, "b0", 0, "b0")),
        ))
        val links = out.getJSONArray("_confirmedLinks")
        assertEquals(1, links.length())
        val l = links.getJSONObject(0)
        assertTrue("sideA < sideB", l.getInt("sideA") < l.getInt("sideB"))
        assertEquals(0, l.getInt("sideA"))
        assertEquals(1, l.getInt("sideB"))
        assertEquals("b0", l.getString("bboxIdA"))
        assertEquals("b0", l.getString("bboxIdB"))
    }

    @Test fun `all cross-side links survive the export - the four-link ring scenario`() {
        // The exact case the operator hit: 4 sides x 2 boxes = 8 boxes, one link on each
        // adjacent pair of the ring (0-1, 1-2, 2-3, 3-0). Every link must appear in the
        // exported _confirmedLinks — none dropped — so the dedup work actually ships.
        val sides = listOf(
            side(0, listOf(box("b0", 0), box("b1", 0))),
            side(1, listOf(box("b0", 0), box("b1", 0))),
            side(2, listOf(box("b0", 0), box("b1", 0))),
            side(3, listOf(box("b0", 0), box("b1", 0))),
        )
        val links = listOf(
            CrossSideLink.create("L0", 0, "b0", 1, "b0"),
            CrossSideLink.create("L1", 1, "b1", 2, "b0"),
            CrossSideLink.create("L2", 2, "b1", 3, "b0"),
            CrossSideLink.create("L3", 3, "b1", 0, "b1"),
        )
        val out = ExportManager.generateOutputJson(session("T_0001", sides, links = links))
        val arr = out.getJSONArray("_confirmedLinks")
        assertEquals("all four ring links exported", 4, arr.length())
        // Each link is on a distinct adjacent pair, oriented to the ring tuple from
        // generateAdjacentPairs (the wrap-around pair is canonically (3,0), not (0,3)).
        val pairs = (0 until arr.length()).map {
            val l = arr.getJSONObject(it); l.getInt("sideA") to l.getInt("sideB")
        }.toSet()
        assertEquals(setOf(0 to 1, 1 to 2, 2 to 3, 3 to 0), pairs)
    }
}

class ExportYoloTxtTest {

    @Test fun `yolo txt emits assigned boxes only`() {
        val s = side(0, listOf(box("b0", 0), Bbox.unassigned("b1", 0f, 0f, 50f, 50f), box("b2", 3)))
        val txt = ExportManager.generateYoloTxt(s)
        val lines = txt.trim().split("\n")
        assertEquals(2, lines.size)
        assertTrue("first line is class 0", lines[0].startsWith("0 "))
        assertTrue("second line is class 3", lines[1].startsWith("3 "))
    }

    @Test fun `yolo txt is locale-agnostic`() {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY) // comma decimal separator
            val txt = ExportManager.generateYoloTxt(side(0, listOf(box("b0", 0))))
            assertFalse("no comma decimals", txt.contains(","))
            assertTrue("dot decimals", txt.contains("."))
        } finally {
            Locale.setDefault(original)
        }
    }

    @Test fun `mismatch txt filters to the given id set`() {
        val s = side(0, listOf(box("b0", 0), box("b1", 1), box("b2", 2)))
        val txt = ExportManager.generateYoloMismatchTxt(s, setOf("b1"))
        val lines = txt.trim().split("\n").filter { it.isNotBlank() }
        assertEquals(1, lines.size)
        assertTrue(lines[0].startsWith("1 "))
    }
}

class ExportCsvIdentityTest {

    @Test fun `csv has the header row and one data row`() {
        val s = session("T_0001", listOf(side(0, listOf(box("b0", 0), box("b1", 0)))))
        val csv = ExportManager.generateCsv(s, ResultsComputer.compute(s))
        val lines = csv.trim().split("\n")
        assertEquals("tree_name,split,unique,raw,B1,B2,B3,B4", lines[0])
        assertTrue(lines[1].startsWith("T_0001,field,"))
    }

    @Test fun `identity json reports unique bunch and mismatch counts`() {
        val s = session(
            "T_0001",
            sides = listOf(side(0, listOf(box("b0", 0))), side(1, listOf(box("b0", 2)))),
            links = listOf(CrossSideLink.create("L0", 0, "b0", 1, "b0")),
        )
        val out = ExportManager.generateIdentityJson(s, ResultsComputer.compute(s))
        assertEquals("T_0001", out.getString("tree_name"))
        assertEquals(1, out.getInt("totalUniqueBunches"))
        assertEquals(1, out.getInt("classMismatchCount"))
        assertEquals(1, out.getJSONArray("bunches").length())
    }
}
