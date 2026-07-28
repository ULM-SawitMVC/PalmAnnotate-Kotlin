package dev.sawitulm.palmannotate.data.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtifactIdentityPolicyTest {

    @Test fun `generated-style tree names are accepted`() {
        assertNull(ArtifactIdentityPolicy.treeNameError("DXP_BLOCK01_0007"))
    }

    @Test fun `path traversal and separators are rejected`() {
        assertNotNull(ArtifactIdentityPolicy.treeNameError("../TREE_0001"))
        assertNotNull(ArtifactIdentityPolicy.treeNameError("folder/TREE_0001"))
        assertNotNull(ArtifactIdentityPolicy.treeNameError("folder\\TREE_0001"))
    }

    @Test fun `empty negative and duplicate side sets are rejected`() {
        assertNotNull(ArtifactIdentityPolicy.sideSetError(emptyList()))
        assertNotNull(ArtifactIdentityPolicy.sideSetError(listOf(-1)))
        assertNotNull(ArtifactIdentityPolicy.sideSetError(listOf(0, 0)))
    }

    @Test fun `partial and gapped side sets remain valid`() {
        assertNull(ArtifactIdentityPolicy.sideSetError(listOf(0, 2)))
    }

    @Test fun `collision probes cover every artifact that a new capture could overwrite`() {
        val paths = ArtifactIdentityPolicy.collisionProbePaths("DXP_A01_0007", listOf(0))
        assertTrue(paths.contains("dataset/manifests/DXP_A01_0007.json"))
        assertTrue(paths.contains("dataset/metadata/DXP_A01_0007.json"))
        assertTrue(paths.contains("Output JSON/DXP_A01_0007.json"))
        assertTrue(paths.contains("dataset/images/field/DXP_A01_0007_1.jpg"))
        assertTrue(paths.contains("dataset/depth/field/DXP_A01_0007_1.raw"))
        assertTrue(paths.contains("dataset/depth/field/DXP_A01_0007_1.json"))
        assertTrue(paths.contains("dataset/annotlog/field/DXP_A01_0007_1.json"))
        assertTrue(paths.contains("Output TXT/field/DXP_A01_0007_1.txt"))
    }

    /**
     * `commitTreePackage` probes these paths through `SafMirrorStore.existsAll`, which queries each
     * DISTINCT parent directory once per batch. That only pays off while the probe set stays
     * concentrated in a handful of directories and free of duplicates — a duplicate would be
     * silently collapsed, and a new directory would add a full listing to every tree save.
     */
    @Test fun `collision probes are distinct and span a small fixed set of directories`() {
        val paths = ArtifactIdentityPolicy.collisionProbePaths("DXP_A01_0007", listOf(0, 1, 2, 3))
        assertEquals(23, paths.size)
        assertEquals(paths.size, paths.toSet().size)
        assertEquals(
            setOf(
                "dataset/manifests",
                "dataset/metadata",
                "Output JSON",
                "dataset/images/field",
                "dataset/depth/field",
                "dataset/annotlog/field",
                "Output TXT/field",
            ),
            paths.map { it.substringBeforeLast('/') }.toSet(),
        )
    }
}
