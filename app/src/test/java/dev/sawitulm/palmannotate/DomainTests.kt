package dev.sawitulm.palmannotate

import dev.sawitulm.palmannotate.data.yolo.YoloParser
import dev.sawitulm.palmannotate.domain.dedup.SuggestionEngine
import dev.sawitulm.palmannotate.domain.dedup.UnionFind
import dev.sawitulm.palmannotate.domain.model.*
import dev.sawitulm.palmannotate.domain.quality.QualityCheck
import dev.sawitulm.palmannotate.domain.results.ResultsComputer
import dev.sawitulm.palmannotate.domain.usecase.SessionUseCases
import dev.sawitulm.palmannotate.domain.util.OperationQueue
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

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

    @Test fun `addManualLink generates unique linkIds`() {
        var s = session(listOf(
            side(0, listOf(box("b0"), box("b1"), box("b2"))),
            side(1, listOf(box("b0"), box("b1"), box("b2"))),
        ))
        s = SessionUseCases.addManualLink(s, 0, "b0", 1, "b0")
        s = SessionUseCases.addManualLink(s, 0, "b1", 1, "b1")
        s = SessionUseCases.addManualLink(s, 0, "b2", 1, "b2")
        assertEquals(3, s.confirmedLinks.size)
        val ids = s.confirmedLinks.map { it.linkId }.toSet()
        assertEquals("all linkIds unique", 3, ids.size)
    }

    @Test fun `delete then re-add link does not collide`() {
        var s = session(listOf(
            side(0, listOf(box("b0"), box("b1"))),
            side(1, listOf(box("b0"), box("b1"))),
        ))
        s = SessionUseCases.addManualLink(s, 0, "b0", 1, "b0")
        val firstId = s.confirmedLinks[0].linkId
        s = SessionUseCases.removeLink(s, firstId)
        assertTrue(s.confirmedLinks.isEmpty())
        s = SessionUseCases.addManualLink(s, 0, "b0", 1, "b0")
        assertNotEquals("new linkId must differ", firstId, s.confirmedLinks[0].linkId)
    }

    @Test fun `propagateClassFromBox changes all cluster members`() {
        val s = session(
            sides = listOf(
                side(0, listOf(Bbox("b0", 0, "B1", 0f, 0f, 100f, 100f))),
                side(1, listOf(Bbox("b0", 2, "B3", 0f, 0f, 100f, 100f))),
            ),
            links = listOf(CrossSideLink.create("L0", 0, "b0", 1, "b0")),
        )
        // Change side 0 b0 to B2, should propagate to side 1 b0
        val updated = SessionUseCases.setBboxClass(s, 0, "b0", AnnotationClass.B2)
        assertEquals(1, updated.sides[0].bboxes[0].classId) // B2
        assertEquals(1, updated.sides[1].bboxes[0].classId) // B2 propagated
    }

    @Test fun `setBboxClass with propagate=false does not change cluster`() {
        val s = session(
            sides = listOf(
                side(0, listOf(Bbox("b0", 0, "B1", 0f, 0f, 100f, 100f))),
                side(1, listOf(Bbox("b0", 2, "B3", 0f, 0f, 100f, 100f))),
            ),
            links = listOf(CrossSideLink.create("L0", 0, "b0", 1, "b0")),
        )
        val updated = SessionUseCases.setBboxClass(s, 0, "b0", AnnotationClass.B2, propagate = false)
        assertEquals(1, updated.sides[0].bboxes[0].classId) // B2
        assertEquals(2, updated.sides[1].bboxes[0].classId) // B3 unchanged
    }
}

// ════════════════════════════════════════════════════════════════════════════════
// YoloParser — edge cases (locale, zero dims, NaN/Inf)  [T1]
// ════════════════════════════════════════════════════════════════════════════════

class YoloParserEdgeTest {
    /** The B1 bug: f6() must emit '.' decimals regardless of the device locale,
     *  otherwise European locales (',' separator) produce labels YOLO can't parse. */
    @Test fun `serialize is locale-agnostic`() {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY) // ',' decimal separator
            val text = YoloParser.serialize(listOf(Bbox("b0", 0, "B1", 450f, 900f, 550f, 1100f)), 1000, 2000)
            assertFalse("must not contain comma decimals", text.contains(","))
            assertTrue("must use dot decimals", text.contains("."))
            // Round-trips back through parse cleanly.
            assertEquals(1, YoloParser.parse(text, 1000, 2000).size)
        } finally {
            Locale.setDefault(original)
        }
    }

    @Test fun `serialize with zero dimensions returns empty`() {
        val boxes = listOf(Bbox("b0", 0, "B1", 10f, 10f, 20f, 20f))
        assertEquals("", YoloParser.serialize(boxes, 0, 1000))
        assertEquals("", YoloParser.serialize(boxes, 1000, 0))
    }

    @Test fun `parse with zero dimensions returns empty`() {
        assertEquals(0, YoloParser.parse("0 0.5 0.5 0.1 0.2", 0, 1000).size)
        assertEquals(0, YoloParser.parse("0 0.5 0.5 0.1 0.2", 1000, 0).size)
    }

    @Test fun `parse tolerates extra whitespace and tabs`() {
        val bboxes = YoloParser.parse("0\t0.5   0.5  0.1\t0.2", 1000, 1000)
        assertEquals(1, bboxes.size)
    }

    @Test fun `parse skips NaN and Inf coordinates`() {
        assertEquals(0, YoloParser.parse("0 NaN 0.5 0.1 0.2\n0 Infinity 0.5 0.1 0.2", 1000, 1000).size)
    }
}

// ════════════════════════════════════════════════════════════════════════════════
// UnionFind — edge cases  [T2, E6]
// ════════════════════════════════════════════════════════════════════════════════

class UnionFindEdgeTest {
    @Test fun `empty node collection yields no clusters`() {
        val uf = UnionFind(emptyList())
        assertEquals(0, uf.clusters().size)
    }

    @Test fun `unknown node is its own singleton`() {
        val uf = UnionFind(listOf("a"))
        // find on an unseen node should be self-rooted, not crash.
        assertEquals("z", uf.find("z"))
        assertEquals(listOf("z"), uf.getCluster("z"))
    }

    @Test fun `single node cluster`() {
        val uf = UnionFind(listOf("a"))
        assertEquals(1, uf.getCluster("a").size)
        assertEquals(1, uf.clusters().size)
    }

    @Test fun `union is idempotent`() {
        val uf = UnionFind(listOf("a", "b"))
        uf.union("a", "b"); uf.union("a", "b"); uf.union("b", "a")
        assertEquals(1, uf.clusters().size)
        assertEquals(2, uf.getCluster("a").size)
    }

    @Test fun `large chain collapses to one cluster`() {
        val nodes = (0 until 500).map { "n$it" }
        val uf = UnionFind(nodes)
        for (i in 0 until 499) uf.union("n$i", "n${i + 1}")
        assertEquals(1, uf.clusters().size)
        assertEquals(500, uf.getCluster("n0").size)
        assertTrue(uf.connected("n0", "n499"))
    }

    @Test fun `getCluster reflects unions after the fact`() {
        val uf = UnionFind(listOf("a", "b", "c"))
        assertEquals(1, uf.getCluster("a").size)
        uf.union("a", "b")
        assertEquals(2, uf.getCluster("a").size)
        uf.union("b", "c")
        assertEquals(3, uf.getCluster("a").size)
    }
}

// ════════════════════════════════════════════════════════════════════════════════
// SuggestionEngine — edge cases  [T3, E7]
// ════════════════════════════════════════════════════════════════════════════════

class SuggestionEngineEdgeTest {
    private val img = SuggestionEngine.Img(1000, 1000)
    private fun b1(id: String, x1: Float, y1: Float, x2: Float, y2: Float) = Bbox(id, 0, "B1", x1, y1, x2, y2)

    @Test fun `empty side A yields no suggestions`() {
        val b = listOf(b1("b", 850f, 400f, 950f, 500f))
        assertEquals(0, SuggestionEngine.suggestPairs(emptyList(), img, b, img).size)
    }

    @Test fun `empty side B yields no suggestions`() {
        val a = listOf(b1("a", 50f, 400f, 150f, 500f))
        assertEquals(0, SuggestionEngine.suggestPairs(a, img, emptyList(), img).size)
    }

    @Test fun `both sides empty yields no suggestions`() {
        assertEquals(0, SuggestionEngine.suggestPairs(emptyList(), img, emptyList(), img).size)
    }

    @Test fun `zero-area box near seam does not crash`() {
        val a = listOf(b1("a", 50f, 400f, 50f, 400f))   // degenerate, zero area
        val b = listOf(b1("b", 850f, 400f, 950f, 500f))
        // Must not throw (division by zero guarded by eps); result may be empty.
        val pairs = SuggestionEngine.suggestPairs(a, img, b, img)
        assertTrue(pairs.size <= 1)
    }
}

// ════════════════════════════════════════════════════════════════════════════════
// ResultsComputer — edge cases  [T4, E8]
// ════════════════════════════════════════════════════════════════════════════════

class ResultsComputerEdgeTest {
    private fun makeSide(i: Int, bboxes: List<Bbox>) = TreeSide(i, "Side ${i+1}", null, null, 1000, 1000, bboxes, emptyList())
    private fun bbox(id: String, classId: Int) = Bbox(id, classId, AnnotationClass.fromId(classId).displayName, 0f, 0f, 100f, 100f)
    private fun session(sides: List<TreeSide>, links: List<CrossSideLink> = emptyList()) =
        ActiveSession("s1", "t1", "field", sides, emptyList(), links, null)

    @Test fun `empty session has zero counts`() {
        val r = ResultsComputer.compute(session(emptyList()))
        assertEquals(0, r.rawCount); assertEquals(0, r.uniqueCount); assertEquals(0, r.linkedCount)
    }

    @Test fun `side with no bboxes`() {
        val r = ResultsComputer.compute(session(listOf(makeSide(0, emptyList()))))
        assertEquals(0, r.rawCount); assertEquals(0, r.uniqueCount)
    }

    @Test fun `single side never has links so unique equals raw`() {
        val r = ResultsComputer.compute(session(listOf(makeSide(0, listOf(bbox("b0",0), bbox("b1",1), bbox("b2",2))))))
        assertEquals(3, r.rawCount); assertEquals(3, r.uniqueCount); assertEquals(0, r.linkedCount)
    }

    @Test fun `all unassigned bboxes`() {
        val r = ResultsComputer.compute(session(listOf(makeSide(0, listOf(
            Bbox.unassigned("b0", 0f, 0f, 100f, 100f),
            Bbox.unassigned("b1", 200f, 200f, 300f, 300f),
        )))))
        assertEquals(2, r.rawCount); assertEquals(2, r.unassignedCount)
        assertEquals(2, r.classCounts[AnnotationClass.UNASSIGNED])
    }
}

// ════════════════════════════════════════════════════════════════════════════════
// QualityCheck  [T5, E9]
// ════════════════════════════════════════════════════════════════════════════════

class QualityCheckTest {
    // Note: imageUri is left null (android.net.Uri is unavailable in plain JVM unit tests),
    // so analyzeTree always emits an `image_missing` WARN. Assertions target the specific
    // issue code / status floor under test rather than a pristine OK.
    private fun side(i: Int, bboxes: List<Bbox>) =
        TreeSide(i, "Side ${i + 1}", null, null, 1000, 1000, bboxes, emptyList())
    private fun bbox(id: String, classId: Int, x1: Float = 0f, y1: Float = 0f, x2: Float = 100f, y2: Float = 100f) =
        Bbox(id, classId, AnnotationClass.fromId(classId).displayName, x1, y1, x2, y2)
    private fun session(sides: List<TreeSide>, links: List<CrossSideLink> = emptyList(), metadata: TreeMetadata? = null) =
        ActiveSession("s1", "t1", "field", sides, emptyList(), links, metadata)

    // ─── Capture pre-save ───
    @Test fun `capture all good is OK`() {
        val r = QualityCheck.analyzeCaptureShots(4, 4, 4, hasGps = true, hasVariety = true, hasBlock = true)
        assertEquals(QualityCheck.Level.OK, r.status)
        assertTrue(r.issues.isEmpty())
    }

    @Test fun `capture missing variety is ERROR`() {
        val r = QualityCheck.analyzeCaptureShots(4, 4, 4, hasGps = true, hasVariety = false, hasBlock = true)
        assertEquals(QualityCheck.Level.ERROR, r.status)
        assertTrue(r.issues.any { it.code == "metadata_variety_missing" })
    }

    @Test fun `capture missing sides is ERROR, missing gps only WARN`() {
        val missingSides = QualityCheck.analyzeCaptureShots(2, 4, 2, hasGps = true, hasVariety = true, hasBlock = true)
        assertEquals(QualityCheck.Level.ERROR, missingSides.status)
        val noGps = QualityCheck.analyzeCaptureShots(4, 4, 4, hasGps = false, hasVariety = true, hasBlock = true)
        assertEquals(QualityCheck.Level.WARN, noGps.status)
    }

    // ─── Tree QA ───
    @Test fun `empty session surfaces variety error and no crash`() {
        val r = QualityCheck.analyzeTree(session(emptyList()))
        assertEquals(QualityCheck.Level.ERROR, r.status) // variety missing
        assertEquals(0, r.metrics["totalSides"])
    }

    @Test fun `tree with metadata and links has no ERROR issues`() {
        val r = QualityCheck.analyzeTree(session(
            sides = listOf(
                side(0, listOf(bbox("b0", 0))),
                side(1, listOf(bbox("b0", 0))),
            ),
            links = listOf(CrossSideLink.create("L0", 0, "b0", 1, "b0")),
            metadata = TreeMetadata(variety = "Tenera", block = "A1"),
        ))
        assertTrue("no error-level issues", r.issues.none { it.level == QualityCheck.Level.ERROR })
        assertEquals(0, r.metrics["mismatches"])
    }

    @Test fun `class mismatch across linked cluster is ERROR`() {
        val r = QualityCheck.analyzeTree(session(
            sides = listOf(
                side(0, listOf(bbox("b0", 0))),  // B1
                side(1, listOf(bbox("b0", 2))),  // B3
            ),
            links = listOf(CrossSideLink.create("L0", 0, "b0", 1, "b0")),
            metadata = TreeMetadata(variety = "Tenera", block = "A1"),
        ))
        assertEquals(QualityCheck.Level.ERROR, r.status)
        assertTrue(r.issues.any { it.code == "annotation_class_mismatch" })
    }

    @Test fun `unassigned bbox raises a warning`() {
        val r = QualityCheck.analyzeTree(session(
            sides = listOf(side(0, listOf(Bbox.unassigned("b0", 0f, 0f, 100f, 100f)))),
            metadata = TreeMetadata(variety = "Tenera", block = "A1"),
        ))
        assertTrue(r.issues.any { it.code == "annotation_unassigned" })
        assertEquals(1, r.metrics["unassigned"])
    }

    @Test fun `tiny bbox raises an info-level issue`() {
        val r = QualityCheck.analyzeTree(session(
            sides = listOf(side(0, listOf(bbox("b0", 0, 0f, 0f, 5f, 5f)))),
            metadata = TreeMetadata(variety = "Tenera", block = "A1"),
        ))
        assertTrue(r.issues.any { it.code == "annotation_tiny_bbox" && it.level == QualityCheck.Level.INFO })
    }
}

// ════════════════════════════════════════════════════════════════════════════════
// OperationQueue  [T7]
// ════════════════════════════════════════════════════════════════════════════════

class OperationQueueTest {
    @Test fun `enqueued blocks run in FIFO order`() = runBlocking {
        val q = OperationQueue()
        val order = mutableListOf<Int>()
        q.enqueue { order.add(1) }
        q.enqueue { order.add(2) }
        q.enqueueAndWait { order.add(3) } // waits for the whole chain
        assertEquals(listOf(1, 2, 3), order)
        q.dispose()
    }

    @Test fun `enqueueAndWait propagates busy flag and clears it`() = runBlocking {
        val q = OperationQueue()
        assertFalse(q.isBusy)
        q.enqueueAndWait("saving") { assertTrue("busy during work", q.isBusy) }
        assertFalse("busy cleared after work", q.isBusy)
        assertEquals("", q.busyLabel.value)
        q.dispose()
    }

    @Test fun `nextLinkId is monotonic and unique`() {
        val q = OperationQueue()
        val ids = (0 until 100).map { q.nextLinkId() }
        assertEquals("all unique", 100, ids.toSet().size)
        assertEquals("lnk-1", ids.first())
        assertEquals("lnk-100", ids.last())
        q.dispose()
    }

    @Test fun `dispose after cancel does not throw`() {
        val q = OperationQueue()
        q.enqueue { /* never awaited */ }
        q.cancel()
        q.dispose() // must be safe to call
    }
}
