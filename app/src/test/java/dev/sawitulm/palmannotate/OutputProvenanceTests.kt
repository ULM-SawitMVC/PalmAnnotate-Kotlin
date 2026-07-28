package dev.sawitulm.palmannotate

import dev.sawitulm.palmannotate.data.export.ExportManager
import dev.sawitulm.palmannotate.domain.model.ActiveSession
import dev.sawitulm.palmannotate.domain.model.CaptureSetIdentity
import dev.sawitulm.palmannotate.domain.model.GpsFreshnessPolicy
import dev.sawitulm.palmannotate.domain.model.GpsSource
import dev.sawitulm.palmannotate.domain.model.GpsStatus
import dev.sawitulm.palmannotate.domain.model.TreeMetadata
import dev.sawitulm.palmannotate.domain.model.TreeSide
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// ════════════════════════════════════════════════════════════════════════════════
// WS-12 + WS-13 in the Output JSON.
//
// The compatibility half matters as much as the feature half: a package with no identity must
// still produce the exact document the 42- and 90-tree collections already contain.
// ════════════════════════════════════════════════════════════════════════════════

private fun sideAt(index: Int) = TreeSide(
    sideIndex = index,
    label = "Side ${index + 1}",
    imageUri = null,
    labelUri = null,
    imageWidth = 1280,
    imageHeight = 800,
    bboxes = emptyList(),
    originalBboxes = emptyList(),
)

private fun sessionWith(metadata: TreeMetadata?) =
    ActiveSession("s1", "DAMIMAS_A21B_0001", "field", listOf(sideAt(0)), emptyList(), emptyList(), metadata)

class OutputJsonIdentityTest {

    @Test fun `a package with no identity keeps the legacy session_id exactly`() {
        val out = ExportManager.generateOutputJson(
            sessionWith(TreeMetadata(variety = "DAMIMAS", block = "A21B", date = "2026-07-27")),
        )
        assertEquals("20260727-DAMIMAS-A21B", out.getJSONObject("metadata").getString("session_id"))
    }

    @Test fun `a package with no identity omits the identity keys entirely`() {
        val meta = ExportManager.generateOutputJson(
            sessionWith(TreeMetadata(variety = "DAMIMAS", block = "A21B", date = "2026-07-27")),
        ).getJSONObject("metadata")
        assertFalse(meta.has("capture_set_id"))
        assertFalse(meta.has("device_token"))
    }

    @Test fun `a device token separates two tablets working the same block`() {
        fun sessionIdFor(token: String) = ExportManager.generateOutputJson(
            sessionWith(
                TreeMetadata(
                    variety = "DAMIMAS",
                    block = "A21B",
                    date = "2026-07-27",
                    identity = CaptureSetIdentity("set-$token", token),
                ),
            ),
        ).getJSONObject("metadata").getString("session_id")

        assertEquals("20260727-DAMIMAS-A21B-K7Q2M1", sessionIdFor("K7Q2M1"))
        assertFalse(sessionIdFor("K7Q2M1") == sessionIdFor("P4X9T2"))
    }

    @Test fun `identity keys are published when the package has an identity`() {
        val meta = ExportManager.generateOutputJson(
            sessionWith(
                TreeMetadata(
                    variety = "DAMIMAS",
                    block = "A21B",
                    date = "2026-07-27",
                    identity = CaptureSetIdentity("set-a", "K7Q2M1", "K7Q2M1"),
                ),
            ),
        ).getJSONObject("metadata")
        assertEquals("set-a", meta.getString("capture_set_id"))
        assertEquals("K7Q2M1", meta.getString("device_token"))
    }
}

class OutputJsonProvenanceTest {

    @Test fun `gps_status is always present so no-claim is explicit`() {
        val meta = ExportManager.generateOutputJson(sessionWith(null)).getJSONObject("metadata")
        assertEquals(GpsStatus.UNKNOWN.name, meta.getString("gps_status"))
    }

    @Test fun `a stale fix is labelled stale in the output`() {
        val stale = GpsFreshnessPolicy.forFix(
            -3.44941, 114.84279, 20f, 1_800_000_000_000L - 3_600_000L,
            "network", GpsSource.LAST_KNOWN, 1_800_000_000_000L,
        )
        val meta = ExportManager.generateOutputJson(
            sessionWith(TreeMetadata(variety = "DAMIMAS", gps = stale)),
        ).getJSONObject("metadata")
        assertEquals(GpsStatus.STALE.name, meta.getString("gps_status"))
    }

    @Test fun `an unset operator is published as UNKNOWN rather than an empty string`() {
        val meta = ExportManager.generateOutputJson(sessionWith(null)).getJSONObject("metadata")
        assertEquals("UNKNOWN", meta.getString("operator"))
    }

    @Test fun `a recorded operator reaches the output`() {
        val meta = ExportManager.generateOutputJson(
            sessionWith(TreeMetadata(variety = "DAMIMAS", operatorName = "Zainal")),
        ).getJSONObject("metadata")
        assertEquals("Zainal", meta.getString("operator"))
    }

    @Test fun `the stored capture date is used rather than the export day`() {
        val meta = ExportManager.generateOutputJson(
            sessionWith(TreeMetadata(variety = "DAMIMAS", date = "2026-07-27")),
        ).getJSONObject("metadata")
        assertEquals("2026-07-27", meta.getString("date"))
        assertTrue("session_id follows the capture date", meta.getString("session_id").startsWith("20260727-"))
    }

    @Test fun `every pre-existing metadata key survives the additions`() {
        val meta = ExportManager.generateOutputJson(
            sessionWith(TreeMetadata(variety = "DAMIMAS", block = "A21B", date = "2026-07-27")),
        ).getJSONObject("metadata")
        for (key in listOf("date", "session_id", "number", "generated_at", "variety")) {
            assertTrue("metadata.$key must still be present", meta.has(key))
        }
    }
}
