package dev.sawitulm.palmannotate

import dev.sawitulm.palmannotate.data.storage.FolderResumeImporter
import org.junit.Assert.*
import org.junit.Test

// ════════════════════════════════════════════════════════════════════════════════
// FolderResumeImporter.planRuns — pure grouping + dedupe for folder-scan resume.
// ════════════════════════════════════════════════════════════════════════════════

class FolderResumePlanTest {

    private fun tree(
        name: String,
        variety: String,
        block: String,
        sideCount: Int = 4,
    ): FolderResumeImporter.ScannedTree {
        val sides = (0 until sideCount).map {
            FolderResumeImporter.ParsedSidePlan(it, 100, 100, emptyList())
        }
        return FolderResumeImporter.ScannedTree(
            treeName = name, treeId = 1, split = "field",
            variety = variety, block = block,
            groupKey = "${variety.uppercase()}__${block.uppercase()}",
            sides = sides, confirmedLinks = emptyList(),
        )
    }

    @Test fun `groups trees by variety-block group key`() {
        val plans = FolderResumeImporter.planRuns(
            listOf(
                tree("DAMIMAS_A21B_0001", "DAMIMAS", "A21B"),
                tree("DAMIMAS_A21B_0002", "DAMIMAS", "A21B"),
                tree("DAMIMAS_B30_0001", "DAMIMAS", "B30"),
            ),
            existingTreeNames = emptySet(),
        )
        assertEquals(2, plans.size)
        val a21b = plans.first { it.block == "A21B" }
        assertEquals(2, a21b.trees.size)
        val b30 = plans.first { it.block == "B30" }
        assertEquals(1, b30.trees.size)
    }

    @Test fun `dedupes trees already in Room`() {
        val plans = FolderResumeImporter.planRuns(
            listOf(
                tree("DAMIMAS_A21B_0001", "DAMIMAS", "A21B"),
                tree("DAMIMAS_A21B_0002", "DAMIMAS", "A21B"),
            ),
            existingTreeNames = setOf("DAMIMAS_A21B_0001"),
        )
        assertEquals(1, plans.size)
        assertEquals(1, plans[0].trees.size)
        assertEquals("DAMIMAS_A21B_0002", plans[0].trees[0].treeName)
    }

    @Test fun `run dropped entirely when all its trees already exist`() {
        val plans = FolderResumeImporter.planRuns(
            listOf(tree("DAMIMAS_A21B_0001", "DAMIMAS", "A21B")),
            existingTreeNames = setOf("DAMIMAS_A21B_0001"),
        )
        assertTrue(plans.isEmpty())
    }

    @Test fun `sideCount is max across trees floored at 2`() {
        val plans = FolderResumeImporter.planRuns(
            listOf(
                tree("X_B_0001", "X", "B", sideCount = 2),
                tree("X_B_0002", "X", "B", sideCount = 6),
            ),
            existingTreeNames = emptySet(),
        )
        assertEquals(1, plans.size)
        assertEquals(6, plans[0].sideCount)
    }

    @Test fun `empty scan yields no plans`() {
        assertTrue(FolderResumeImporter.planRuns(emptyList(), emptySet()).isEmpty())
    }
}
