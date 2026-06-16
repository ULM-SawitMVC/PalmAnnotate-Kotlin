package dev.sawitulm.palmannotate

import dev.sawitulm.palmannotate.data.yolo.YoloParser
import dev.sawitulm.palmannotate.domain.dedup.SuggestionEngine
import dev.sawitulm.palmannotate.domain.dedup.UnionFind
import dev.sawitulm.palmannotate.domain.model.*
import dev.sawitulm.palmannotate.domain.results.ResultsComputer
import dev.sawitulm.palmannotate.domain.usecase.SessionUseCases
import org.junit.Assert.*
import org.junit.Test

// ════════════════════════════════════════════════════════════════════════════════
// YoloParser
// ════════════════════════════════════════════════════════════════════════════════

class YoloParserTest {
    @Test fun `parse valid YOLO label`() {
        val bboxes = YoloParser.parse("0 0.5 0.5 0.1 0.2\n1 0.3 0.4 0.05 0.08", 1000, 2000)
        assertEquals(2, bboxes.size)
        assertEquals(0, bboxes[0].classId); assertEquals("B1", bboxes[0].className)
        assertEquals(450f, bboxes[0].x1, 1f); assertEquals(800f, bboxes[0].y1, 1f)
        assertEquals(550f, bboxes[0].x2, 1f); assertEquals(1200f, bboxes[0].y2, 1f)
        assertEquals(1, bboxes[1].classId); assertEquals("B2", bboxes[1].className)
    }
    @Test fun `parse empty string returns empty`() {
        assertEquals(0, YoloParser.parse("", 1000, 1000).size)
        assertEquals(0, YoloParser.parse(null, 1000, 1000).size)
        assertEquals(0, YoloParser.parse("   \n  ", 1000, 1000).size)
    }
    @Test fun `parse skips invalid lines`() {
        assertEquals(1, YoloParser.parse("0 0.5 0.5 0.1\ninvalid\n5 0.5 0.5 0.1 0.2\n0 0.5 0.5 0.1 0.2", 1000, 1000).size)
    }
    @Test fun `parse skips class outside 0-3`() {
        assertEquals(0, YoloParser.parse("4 0.5 0.5 0.1 0.2\n-1 0.5 0.5 0.1 0.2", 1000, 1000).size)
    }
    @Test fun `serialize round-trip`() {
        val original = listOf(Bbox("b0", 0, "B1", 450f, 900f, 550f, 1100f), Bbox("b1", 2, "B3", 100f, 200f, 300f, 400f))
        val parsed = YoloParser.parse(YoloParser.serialize(original, 1000, 2000), 1000, 2000)
        assertEquals(2, parsed.size); assertEquals(0, parsed[0].classId); assertEquals(2, parsed[1].classId)
        assertEquals(450f, parsed[0].x1, 2f)
    }
    @Test fun `serialize excludes UNASSIGNED`() {
        val text = YoloParser.serialize(listOf(Bbox("b0", 0, "B1", 100f, 100f, 200f, 200f), Bbox("b1", -1, "U", 300f, 300f, 400f, 400f)), 1000, 1000)
        assertEquals(1, text.trim().split("\n").size)
    }
    @Test fun `coordinates clamped to image bounds`() {
        val b = YoloParser.parse("0 0.0 0.0 0.5 0.5", 1000, 1000)[0]
        assertTrue(b.x1 >= 0f); assertTrue(b.y1 >= 0f)
    }
}

// ════════════════════════════════════════════════════════════════════════════════
// UnionFind
// ════════════════════════════════════════════════════════════════════════════════

class UnionFindTest {
    @Test fun `basic union and find`() {
        val uf = UnionFind(listOf("a", "b", "c", "d")); uf.union("a", "b")
        assertEquals(uf.find("a"), uf.find("b")); assertNotEquals(uf.find("a"), uf.find("c"))
    }
    @Test fun `transitive union`() {
        val uf = UnionFind(listOf("a", "b", "c")); uf.union("a", "b"); uf.union("b", "c")
        assertTrue(uf.connected("a", "c"))
    }
    @Test fun `clusters`() {
        val uf = UnionFind(listOf("a", "b", "c", "d", "e")); uf.union("a", "b"); uf.union("c", "d")
        assertEquals(3, uf.clusters().size); assertEquals(listOf(1, 2, 2), uf.clusters().values.map { it.size }.sorted())
    }
    @Test fun `getCluster returns all members`() {
        val uf = UnionFind(listOf("a", "b", "c")); uf.union("a", "b")
        assertEquals(2, uf.getCluster("a").size); assertTrue("a" in uf.getCluster("a")); assertTrue("b" in uf.getCluster("a"))
    }
}

// ════════════════════════════════════════════════════════════════════════════════
// SuggestionEngine
// ════════════════════════════════════════════════════════════════════════════════

class SuggestionEngineTest {
    private val img = SuggestionEngine.Img(1000, 1000)
    private fun b1(id: String, x1: Float, y1: Float, x2: Float, y2: Float) = Bbox(id, 0, "B1", x1, y1, x2, y2)

    // sideA seam = LEFT (cx<=0.5), sideB seam = RIGHT (cx>=0.5).
    @Test fun `seam-aligned same-size same-height pair is suggested as auto`() {
        val a = listOf(b1("a", 50f, 400f, 150f, 500f))   // cx 0.1, near left seam
        val b = listOf(b1("b", 850f, 400f, 950f, 500f))  // cx 0.9, near right seam, same h/size
        val pairs = SuggestionEngine.suggestPairs(a, img, b, img)
        assertEquals(1, pairs.size)
        assertEquals("a", pairs[0].bboxIdA); assertEquals("b", pairs[0].bboxIdB)
        assertTrue("score should be high", pairs[0].score > 0.85f)
        assertEquals("auto", pairs[0].category)
    }
    @Test fun `boxes far from the seam are gated out`() {
        // A on the RIGHT half (cx>0.5) is not eligible for sideA's left seam.
        val a = listOf(b1("a", 850f, 400f, 950f, 500f))
        val b = listOf(b1("b", 850f, 400f, 950f, 500f))
        assertEquals(0, SuggestionEngine.suggestPairs(a, img, b, img).size)
    }
    @Test fun `wildly different sizes are dropped by the size-ratio gate`() {
        val a = listOf(b1("a", 50f, 400f, 150f, 500f))    // 100x100
        val b = listOf(b1("b", 900f, 450f, 910f, 460f))   // 10x10 near seam
        assertEquals(0, SuggestionEngine.suggestPairs(a, img, b, img).size)
    }
    @Test fun `mutual-best keeps one pair per box`() {
        val a = listOf(b1("a", 50f, 400f, 150f, 500f))
        val b = listOf(b1("b1", 850f, 400f, 950f, 500f), b1("b2", 860f, 405f, 960f, 505f))
        val pairs = SuggestionEngine.suggestPairs(a, img, b, img)
        assertEquals(1, pairs.size) // 'a' can only mutual-best with its single top pick
    }
}

// ════════════════════════════════════════════════════════════════════════════════
// ResultsComputer
// ════════════════════════════════════════════════════════════════════════════════

class ResultsComputerTest {
    private fun makeSide(i: Int, bboxes: List<Bbox>) = TreeSide(i, "Side ${i+1}", null, null, 1000, 1000, bboxes, emptyList())
    private fun bbox(id: String, classId: Int) = Bbox(id, classId, AnnotationClass.fromId(classId).displayName, 0f, 0f, 100f, 100f)

    @Test fun `no links`() {
        val r = ResultsComputer.compute(ActiveSession("s1","t1","field",listOf(makeSide(0, listOf(bbox("b0",0), bbox("b1",1))), makeSide(1, listOf(bbox("b0",2)))), emptyList(), emptyList(), null))
        assertEquals(3, r.rawCount); assertEquals(0, r.linkedCount); assertEquals(3, r.uniqueCount)
    }
    @Test fun `with confirmed links`() {
        val r = ResultsComputer.compute(ActiveSession("s1","t1","field",listOf(makeSide(0, listOf(bbox("b0",0), bbox("b1",1))), makeSide(1, listOf(bbox("b0",0), bbox("b1",1)))), emptyList(), listOf(CrossSideLink("L0",0,"b0",1,"b0"), CrossSideLink("L1",0,"b1",1,"b1")), null))
        // linkedCount = effective unions (= duplicates collapsed), JS Results.compute semantics.
        assertEquals(4, r.rawCount); assertEquals(2, r.linkedCount); assertEquals(2, r.uniqueCount)
    }
    @Test fun `linkedCount counts merges not members for a 3-box cluster`() {
        val r = ResultsComputer.compute(ActiveSession("s1","t1","field",
            listOf(makeSide(0, listOf(bbox("b0",0))), makeSide(1, listOf(bbox("b0",0))), makeSide(2, listOf(bbox("b0",0)))),
            emptyList(),
            listOf(CrossSideLink.create("L0",0,"b0",1,"b0"), CrossSideLink.create("L1",1,"b0",2,"b0")), null))
        assertEquals(3, r.rawCount); assertEquals(2, r.linkedCount); assertEquals(1, r.uniqueCount)
    }
    @Test fun `with unassigned bboxes - other bucket`() {
        val r = ResultsComputer.compute(ActiveSession("s1","t1","field",listOf(makeSide(0, listOf(bbox("b0",0), Bbox.unassigned("b1",200f,200f,300f,300f)))), emptyList(), emptyList(), null))
        assertEquals(2, r.rawCount); assertEquals(1, r.unassignedCount)
        // An unassigned singleton falls into the "other" bucket (keyed UNASSIGNED), B1 counted once.
        assertEquals(1, r.classCounts[AnnotationClass.B1])
        assertEquals(1, r.classCounts[AnnotationClass.UNASSIGNED])
    }
}

// ════════════════════════════════════════════════════════════════════════════════
// AnnotationClass, Bbox, TreeSide, CrossSideLink
// ════════════════════════════════════════════════════════════════════════════════

class AnnotationClassTest {
    @Test fun `fromId returns correct class`() { assertEquals(AnnotationClass.B1, AnnotationClass.fromId(0)); assertEquals(AnnotationClass.B4, AnnotationClass.fromId(3)); assertEquals(AnnotationClass.UNASSIGNED, AnnotationClass.fromId(-1)); assertEquals(AnnotationClass.UNASSIGNED, AnnotationClass.fromId(99)) }
    @Test fun `fromName returns correct class`() { assertEquals(AnnotationClass.B1, AnnotationClass.fromName("B1")); assertEquals(AnnotationClass.B2, AnnotationClass.fromName("b2")); assertEquals(AnnotationClass.UNASSIGNED, AnnotationClass.fromName("unknown")) }
    @Test fun `assignableEntries excludes UNASSIGNED`() { assertEquals(4, AnnotationClass.assignableEntries.size); assertTrue(AnnotationClass.UNASSIGNED !in AnnotationClass.assignableEntries) }
}

class BboxTest {
    @Test fun `unassigned factory`() { val b = Bbox.unassigned("b0", 10f, 20f, 30f, 40f); assertEquals(-1, b.classId); assertEquals("U", b.className); assertFalse(b.isAssigned) }
    @Test fun `computed properties`() { val b = Bbox("b0", 0, "B1", 10f, 20f, 30f, 40f); assertEquals(20f, b.width); assertEquals(20f, b.height); assertEquals(20f, b.cx); assertEquals(30f, b.cy); assertEquals(400f, b.area); assertTrue(b.isAssigned) }
}

class TreeSideTest {
    @Test fun `generateAdjacentPairs`() { assertEquals(emptyList<Pair<Int,Int>>(), generateAdjacentPairs(1)); assertEquals(listOf(0 to 1), generateAdjacentPairs(2)); assertEquals(listOf(0 to 1, 1 to 2, 2 to 0), generateAdjacentPairs(3)); assertEquals(listOf(0 to 1, 1 to 2, 2 to 3, 3 to 0), generateAdjacentPairs(4)) }
    @Test fun `generateSideLabels`() { assertEquals(listOf("Side 1"), generateSideLabels(1)); assertEquals(listOf("Side 1","Side 2","Side 3","Side 4"), generateSideLabels(4)) }
}

class CrossSideLinkTest {
    @Test fun `link auto-orients`() { val link = CrossSideLink.create("L0", 2, "b1", 0, "b0"); assertEquals(0, link.sideA); assertEquals("b0", link.bboxIdA); assertEquals(2, link.sideB); assertEquals("b1", link.bboxIdB) }
    @Test(expected = IllegalArgumentException::class) fun `link rejects sideA greater than sideB`() { CrossSideLink("L0", 2, "b0", 0, "b1") }
}

// ════════════════════════════════════════════════════════════════════════════════
// Bbox.nextId — never-reused ids
// ════════════════════════════════════════════════════════════════════════════════

class BboxNextIdTest {
    private fun b(id: String) = Bbox.unassigned(id, 0f, 0f, 10f, 10f)

    @Test fun `nextId on empty list starts at zero`() {
        assertEquals("b0", Bbox.nextId(emptyList(), "b"))
        assertEquals("det0", Bbox.nextId(emptyList(), "det"))
    }

    @Test fun `nextId is one past the max numeric suffix`() {
        assertEquals("b3", Bbox.nextId(listOf(b("b0"), b("b1"), b("b2")), "b"))
    }

    @Test fun `nextId never collides after a delete shrinks the list`() {
        // Start with b0, b1, b2 → delete b1 → surviving {b0, b2}. size==2 would
        // have produced "b2" (collision); nextId must produce a fresh id instead.
        val surviving = listOf(b("b0"), b("b2"))
        val newId = Bbox.nextId(surviving, "b")
        assertTrue("new id must not collide", surviving.none { it.id == newId })
        assertEquals("b3", newId)
    }

    @Test fun `nextId shares one sequence across prefixes`() {
        // A detect id must not collide with a manually-added box and vice versa.
        val existing = listOf(b("b0"), b("det1"))
        assertEquals("det2", Bbox.nextId(existing, "det"))
        assertEquals("b2", Bbox.nextId(existing, "b"))
    }
}

// ════════════════════════════════════════════════════════════════════════════════
// SessionUseCases — add/delete bbox correctness
// ════════════════════════════════════════════════════════════════════════════════

class SessionUseCasesBboxTest {
    private fun side(i: Int, bboxes: List<Bbox>) =
        TreeSide(i, "Side ${i + 1}", null, null, 1000, 1000, bboxes, emptyList())
    private fun box(id: String) = Bbox.unassigned(id, 0f, 0f, 100f, 100f)
    private fun session(sides: List<TreeSide>, links: List<CrossSideLink> = emptyList()) =
        ActiveSession("s1", "t1", "field", sides, emptyList(), links, null)

    @Test fun `delete then add yields a non-colliding id`() {
        var s = session(listOf(side(0, listOf(box("b0"), box("b1"), box("b2")))))
        s = SessionUseCases.deleteBbox(s, 0, "b1")          // surviving: b0, b2
        s = SessionUseCases.addBbox(s, 0, 5f, 5f, 50f, 50f) // must not reuse "b2"
        val ids = s.sides[0].bboxes.map { it.id }
        assertEquals("no duplicate ids", ids.size, ids.toSet().size)
        assertEquals(listOf("b0", "b2", "b3"), ids)
    }

    @Test fun `delete prunes confirmed links touching the box`() {
        val s = session(
            sides = listOf(
                side(0, listOf(box("b0"), box("b1"))),
                side(1, listOf(box("b0"), box("b1"))),
            ),
            links = listOf(
                CrossSideLink.create("L0", 0, "b0", 1, "b0"),
                CrossSideLink.create("L1", 0, "b1", 1, "b1"),
            ),
        )
        val after = SessionUseCases.deleteBbox(s, 0, "b0") // removes (0,b0) → drops L0
        assertEquals(1, after.confirmedLinks.size)
        assertEquals("L1", after.confirmedLinks[0].linkId)
        assertTrue(after.sides[0].bboxes.none { it.id == "b0" })
    }
}
