package dev.sawitulm.palmannotate

import dev.sawitulm.palmannotate.data.export.CaptureSetDescriptor
import dev.sawitulm.palmannotate.data.export.CaptureSetMergePolicy
import dev.sawitulm.palmannotate.data.export.CaptureSetMergePolicy.Entry
import dev.sawitulm.palmannotate.data.export.CaptureSetMergePolicy.Verdict
import dev.sawitulm.palmannotate.data.storage.ArtifactIdentityPolicy
import dev.sawitulm.palmannotate.domain.model.CaptureSetIdentity
import dev.sawitulm.palmannotate.domain.model.CaptureSetPolicy
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// ════════════════════════════════════════════════════════════════════════════════
// WS-12 — capture-set identity derivation and merge safety.
// ════════════════════════════════════════════════════════════════════════════════

class DeviceTokenDerivationTest {

    @Test fun `the token is deterministic for one install`() {
        val installId = "3f2b8a10-7f6e-4c02-9a53-1b0d8e5c2a44"
        assertEquals(
            CaptureSetPolicy.deviceTokenFrom(installId),
            CaptureSetPolicy.deviceTokenFrom(installId),
        )
    }

    @Test fun `different installs get different tokens`() {
        val a = CaptureSetPolicy.deviceTokenFrom("11111111-1111-1111-1111-111111111111")
        val b = CaptureSetPolicy.deviceTokenFrom("22222222-2222-2222-2222-222222222222")
        assertNotEquals(a, b)
    }

    @Test fun `the token leaks nothing back about the install id`() {
        val installId = "3f2b8a10-7f6e-4c02-9a53-1b0d8e5c2a44"
        val token = CaptureSetPolicy.deviceTokenFrom(installId)
        assertFalse(installId.uppercase().contains(token))
    }

    @Test fun `the token uses only unambiguous characters and a fixed length`() {
        // I, L, O and U are excluded so an operator reading a token off the tablet cannot
        // mistype it into a filename.
        for (seed in 0 until 500) {
            val token = CaptureSetPolicy.deviceTokenFrom("install-$seed")
            assertEquals(CaptureSetPolicy.TOKEN_LENGTH, token.length)
            assertNull("'$token' must be a valid token", CaptureSetPolicy.tokenError(token))
            assertFalse("'$token' contains an ambiguous character", token.any { it in "ILOU" })
        }
    }

    @Test fun `an empty token is legal and means legacy naming`() {
        assertNull(CaptureSetPolicy.tokenError(""))
    }

    @Test fun `malformed tokens are rejected`() {
        for (bad in listOf("abc", "TOOLONGTOKEN", "AB_CD1", "K7Q2M", "K7Q2M1X")) {
            assertTrue("'$bad' must be rejected", CaptureSetPolicy.tokenError(bad) != null)
        }
    }
}

class TreeNamingCompatibilityTest {

    // The load-bearing compatibility guarantee: with no naming token, the derivation must
    // produce byte-identical names to the ones already collected in the field.
    @Test fun `no token reproduces the historical name exactly`() {
        assertEquals("DAMIMAS_A21B_0001", CaptureSetPolicy.treeName("DAMIMAS", "A21B", "", 1))
        assertEquals("DAMIMAS_A21B_0042", CaptureSetPolicy.treeName("DAMIMAS", "A21B", "", 42))
        assertEquals("DAMIMAS_A21B_0090", CaptureSetPolicy.treeName("damimas", "a21b", "", 90))
    }

    @Test fun `no token and no block reproduces the historical two-segment name`() {
        assertEquals("DAMIMAS_0007", CaptureSetPolicy.treeName("DAMIMAS", "", "", 7))
        assertEquals("DAMIMAS_0007", CaptureSetPolicy.treeName("DAMIMAS", "  ", "", 7))
    }

    @Test fun `sanitising matches the historical safe and safeBlock rules`() {
        assertEquals("A_B_0001", CaptureSetPolicy.treeName("a b", "", "", 1))
        assertEquals("TREE_0001", CaptureSetPolicy.treeName("///", "", "", 1))
        assertEquals("D_A21B_0001", CaptureSetPolicy.treeName("d", "a-21/b", "", 1))
    }

    @Test fun `the token is inserted before the sequence`() {
        assertEquals("DAMIMAS_A21B_K7Q2M1_0001", CaptureSetPolicy.treeName("DAMIMAS", "A21B", "K7Q2M1", 1))
    }

    // Every existing derivation downstream must survive the token: variety is the leading
    // letters, block is `_`-segment 1, and the tree number is the trailing digits.
    @Test fun `tokenised names keep every downstream derivation intact`() {
        val name = CaptureSetPolicy.treeName("DAMIMAS", "A21B", "K7Q2M1", 42)
        assertEquals("DAMIMAS", Regex("^([A-Za-z]+)_").find(name)!!.groupValues[1])
        assertEquals("A21B", name.split("_")[1])
        assertEquals(42, Regex("_(\\d+)$").find(name)!!.groupValues[1].toInt())
    }

    @Test fun `tokenised names remain safe artifact identities`() {
        assertNull(
            ArtifactIdentityPolicy.treeNameError(
                CaptureSetPolicy.treeName("DAMIMAS", "A21B", "K7Q2M1", 1),
            ),
        )
    }

    @Test fun `two devices in the same block produce different names`() {
        val a = CaptureSetPolicy.treeName("DAMIMAS", "A21B", CaptureSetPolicy.deviceTokenFrom("tablet-a"), 1)
        val b = CaptureSetPolicy.treeName("DAMIMAS", "A21B", CaptureSetPolicy.deviceTokenFrom("tablet-b"), 1)
        assertNotEquals(a, b)
    }

    @Test fun `the logical name strips the token back to the legacy name`() {
        val token = "K7Q2M1"
        val tokenised = CaptureSetPolicy.treeName("DAMIMAS", "A21B", token, 1)
        assertEquals("DAMIMAS_A21B_0001", CaptureSetPolicy.logicalTreeName(tokenised, token))
        // A legacy name is already logical.
        assertEquals("DAMIMAS_A21B_0001", CaptureSetPolicy.logicalTreeName("DAMIMAS_A21B_0001", ""))
        assertEquals("DAMIMAS_A21B_0001", CaptureSetPolicy.logicalTreeName("DAMIMAS_A21B_0001", token))
    }
}

class CaptureSetMergeSafetyTest {

    private fun entry(
        tree: String,
        set: String = "set-a",
        device: String = "K7Q2M1",
        digest: String = "digest-1",
    ) = Entry(tree, set, device, digest)

    @Test fun `disjoint packages merge`() {
        val a = listOf(entry("DAMIMAS_A21B_0001"), entry("DAMIMAS_A21B_0002"))
        val b = listOf(entry("DAMIMAS_A21C_0001", set = "set-b", device = "P4X9T2"))
        assertTrue(CaptureSetMergePolicy.evaluate(listOf(a, b)) is Verdict.Mergeable)
    }

    // The exact 27 Jul 2026 failure: two tablets, same block, same names, different photos.
    @Test fun `two devices sharing a tree name is a conflict`() {
        val tabletA = listOf(entry("DAMIMAS_A21B_0001", set = "set-a", device = "K7Q2M1"))
        val tabletB = listOf(entry("DAMIMAS_A21B_0001", set = "set-b", device = "P4X9T2", digest = "digest-2"))
        val verdict = CaptureSetMergePolicy.evaluate(listOf(tabletA, tabletB))
        assertTrue(verdict is Verdict.Conflict)
        val collisions = (verdict as Verdict.Conflict).collisions
        assertEquals(1, collisions.size)
        assertEquals("DAMIMAS_A21B_0001", collisions[0].treeName)
        assertTrue(collisions[0].reason.contains("different devices"))
        assertTrue(collisions[0].describe().contains("set-a"))
        assertTrue(collisions[0].describe().contains("set-b"))
    }

    @Test fun `two runs on one device sharing a tree name is a conflict`() {
        val first = listOf(entry("DAMIMAS_A21B_0001", set = "set-morning"))
        val second = listOf(entry("DAMIMAS_A21B_0001", set = "set-afternoon", digest = "digest-2"))
        val verdict = CaptureSetMergePolicy.evaluate(listOf(first, second))
        assertTrue(verdict is Verdict.Conflict)
        assertTrue((verdict as Verdict.Conflict).collisions[0].reason.contains("different runs"))
    }

    // Re-exporting the same ZIP twice is a normal operator action and must stay safe.
    @Test fun `the identical package twice is mergeable`() {
        val pkg = listOf(entry("DAMIMAS_A21B_0001"), entry("DAMIMAS_A21B_0002"))
        assertTrue(CaptureSetMergePolicy.evaluate(listOf(pkg, pkg)) is Verdict.Mergeable)
    }

    @Test fun `same capture set with different content is a conflict`() {
        val before = listOf(entry("DAMIMAS_A21B_0001", digest = "digest-1"))
        val after = listOf(entry("DAMIMAS_A21B_0001", digest = "digest-2"))
        val verdict = CaptureSetMergePolicy.evaluate(listOf(before, after))
        assertTrue(verdict is Verdict.Conflict)
        assertTrue((verdict as Verdict.Conflict).collisions[0].reason.contains("different contents"))
    }

    // Fail-closed: a pre-WS-12 package cannot prove it is or is not the same tree.
    @Test fun `an identity-less legacy package colliding by name is a conflict`() {
        val legacy = listOf(entry("DAMIMAS_A21B_0001", set = "", device = ""))
        val current = listOf(entry("DAMIMAS_A21B_0001"))
        val verdict = CaptureSetMergePolicy.evaluate(listOf(legacy, current))
        assertTrue(verdict is Verdict.Conflict)
        assertTrue((verdict as Verdict.Conflict).collisions[0].reason.contains("no capture-set identity"))
    }

    @Test fun `two legacy packages that never collide by name still merge`() {
        val legacyA = listOf(entry("DAMIMAS_A21B_0001", set = "", device = ""))
        val legacyB = listOf(entry("DAMIMAS_A21B_0002", set = "", device = ""))
        assertTrue(CaptureSetMergePolicy.evaluate(listOf(legacyA, legacyB)) is Verdict.Mergeable)
    }

    @Test fun `a package that collides with itself is reported`() {
        val broken = listOf(
            entry("DAMIMAS_A21B_0001", digest = "digest-1"),
            entry("DAMIMAS_A21B_0001", digest = "digest-2"),
        )
        assertTrue(CaptureSetMergePolicy.evaluate(listOf(broken)) is Verdict.Conflict)
    }

    @Test fun `every colliding tree is reported, not just the first`() {
        val a = listOf(entry("T_0001", set = "a"), entry("T_0002", set = "a"), entry("T_0003", set = "a"))
        val b = listOf(entry("T_0001", set = "b"), entry("T_0002", set = "b"))
        val verdict = CaptureSetMergePolicy.evaluate(listOf(a, b))
        assertEquals(2, (verdict as Verdict.Conflict).collisions.size)
    }
}

class CaptureSetDescriptorTest {

    private val entries = listOf(
        CaptureSetMergePolicy.Entry("DAMIMAS_A21B_0002", "set-a", "K7Q2M1", "digest-2"),
        CaptureSetMergePolicy.Entry("DAMIMAS_A21B_0001", "set-a", "K7Q2M1", "digest-1"),
    )

    private fun descriptor() = CaptureSetDescriptor.build(
        identity = CaptureSetIdentity("set-a", "K7Q2M1", "K7Q2M1"),
        appVersion = "0.3.99",
        generatedAt = "2026-07-28T04:05:06Z",
        entries = entries,
    )

    @Test fun `the descriptor round-trips through JSON`() {
        val parsed = CaptureSetDescriptor.entriesOf(JSONObject(descriptor().toString()))
        assertEquals(entries.sortedBy { it.treeName }, parsed)
    }

    @Test fun `the descriptor is deterministic regardless of input order`() {
        val reversed = CaptureSetDescriptor.build(
            identity = CaptureSetIdentity("set-a", "K7Q2M1", "K7Q2M1"),
            appVersion = "0.3.99",
            generatedAt = "2026-07-28T04:05:06Z",
            entries = entries.reversed(),
        )
        assertEquals(descriptor().toString(), reversed.toString())
    }

    @Test fun `the descriptor carries the identity, count and merge rule`() {
        val json = descriptor()
        assertEquals("set-a", json.getString("captureSetId"))
        assertEquals("K7Q2M1", json.getString("deviceToken"))
        assertEquals(2, json.getInt("treeCount"))
        assertEquals(CaptureSetPolicy.SCHEMA_VERSION, json.getInt("schema"))
        assertTrue(json.getString("mergeRule").contains("captureSetId"))
    }

    @Test fun `entries inherit the archive identity when a row omits it`() {
        val json = JSONObject(
            """
            {"captureSetId":"set-a","deviceToken":"K7Q2M1",
             "trees":[{"treeName":"T_0001","contentDigest":"d1"}]}
            """.trimIndent(),
        )
        val parsed = CaptureSetDescriptor.entriesOf(json)
        assertEquals(1, parsed.size)
        assertEquals("set-a", parsed[0].captureSetId)
        assertEquals("K7Q2M1", parsed[0].deviceToken)
    }

    @Test fun `a descriptor with no trees array yields nothing rather than throwing`() {
        assertTrue(CaptureSetDescriptor.entriesOf(JSONObject("{}")).isEmpty())
    }

    @Test fun `the descriptor file sits at the archive root beside the legacy folders`() {
        // A new root file is additive: existing consumers walk images/ labels/ json/ … and are
        // unaffected by a sibling they do not read.
        assertEquals("capture_set.json", CaptureSetDescriptor.FILE_NAME)
        assertFalse(CaptureSetDescriptor.FILE_NAME.contains("/"))
    }
}
