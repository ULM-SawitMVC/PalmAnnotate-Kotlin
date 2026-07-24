package dev.sawitulm.palmannotate.data.storage

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
}
