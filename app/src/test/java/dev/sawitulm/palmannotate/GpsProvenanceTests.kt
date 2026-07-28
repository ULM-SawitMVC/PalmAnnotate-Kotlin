package dev.sawitulm.palmannotate

import dev.sawitulm.palmannotate.data.storage.PackageProvenanceCodec
import dev.sawitulm.palmannotate.domain.model.CaptureSetIdentity
import dev.sawitulm.palmannotate.domain.model.GpsFreshnessPolicy
import dev.sawitulm.palmannotate.domain.model.GpsFreshnessPolicy.Freshness
import dev.sawitulm.palmannotate.domain.model.GpsProvenance
import dev.sawitulm.palmannotate.domain.model.GpsSource
import dev.sawitulm.palmannotate.domain.model.GpsStatus
import dev.sawitulm.palmannotate.domain.model.TreeMetadata
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// ════════════════════════════════════════════════════════════════════════════════
// WS-13 — GPS freshness, provenance and the round trip through the sidecar.
// ════════════════════════════════════════════════════════════════════════════════

private const val NOW = 1_800_000_000_000L
private const val LAT = -3.44941
private const val LNG = 114.84279

class GpsFreshnessBoundaryTest {

    @Test fun `a fix inside the window is fresh`() {
        assertEquals(Freshness.FRESH, GpsFreshnessPolicy.classify(NOW - 59_999L, NOW))
    }

    @Test fun `exactly at the limit is still fresh`() {
        assertEquals(Freshness.FRESH, GpsFreshnessPolicy.classify(NOW - 60_000L, NOW))
    }

    @Test fun `one millisecond past the limit is stale`() {
        assertEquals(Freshness.STALE, GpsFreshnessPolicy.classify(NOW - 60_001L, NOW))
    }

    // A device clock jump must not read as "extremely fresh".
    @Test fun `a future-dated fix is invalid, not fresh`() {
        assertEquals(Freshness.INVALID_FUTURE, GpsFreshnessPolicy.classify(NOW + 1L, NOW))
    }

    @Test fun `the window is configurable for callers with a different tolerance`() {
        assertEquals(Freshness.STALE, GpsFreshnessPolicy.classify(NOW - 5_000L, NOW, maxAgeMs = 1_000L))
    }
}

class GpsProvenanceConstructionTest {

    @Test fun `a fresh fix records its age, accuracy, provider and source`() {
        val p = GpsFreshnessPolicy.forFix(
            LAT, LNG, 4.5f, NOW - 10_000L, "gps", GpsSource.CURRENT_FIX, NOW,
        )
        assertEquals(GpsStatus.FRESH, p.status)
        assertEquals(10_000L, p.ageMs)
        assertEquals(4.5f, p.accuracyM!!, 0.0001f)
        assertEquals("gps", p.provider)
        assertEquals(GpsSource.CURRENT_FIX, p.source)
        assertEquals(LAT to LNG, p.publishableCoordinates)
    }

    // The 27 Jul failure in one assertion: an old last-known fix keeps its coordinates but can
    // never be published as an unqualified lat/lng pair.
    @Test fun `a stale fix keeps coordinates but is not publishable`() {
        val p = GpsFreshnessPolicy.forFix(
            LAT, LNG, 12f, NOW - 3_600_000L, "network", GpsSource.LAST_KNOWN, NOW,
        )
        assertEquals(GpsStatus.STALE, p.status)
        assertEquals(3_600_000L, p.ageMs)
        assertEquals(LAT, p.latitude!!, 1e-9)
        assertNull("a stale coordinate must never be published bare", p.publishableCoordinates)
        assertFalse(p.isFresh)
    }

    @Test fun `a future-dated fix is stale with an unknown age`() {
        val p = GpsFreshnessPolicy.forFix(
            LAT, LNG, 5f, NOW + 120_000L, "gps", GpsSource.CURRENT_FIX, NOW,
        )
        assertEquals(GpsStatus.STALE, p.status)
        assertNull("the age of a future-dated fix is unknowable", p.ageMs)
    }

    @Test fun `an unavailable provenance carries no coordinates at all`() {
        for (status in listOf(GpsStatus.UNAVAILABLE, GpsStatus.PERMISSION_DENIED, GpsStatus.LOCATION_OFF)) {
            val p = GpsProvenance.unavailable(status)
            assertEquals(status, p.status)
            assertNull(p.latitude)
            assertNull(p.publishableCoordinates)
        }
    }
}

class GpsCommitRecheckTest {

    // Four or eight photos can outlast the freshness window. The fix read when the screen opened
    // must not still claim FRESH at commit.
    @Test fun `a fix that ages out during the capture is downgraded`() {
        val atOpen = GpsFreshnessPolicy.forFix(
            LAT, LNG, 4f, NOW - 30_000L, "gps", GpsSource.CURRENT_FIX, NOW,
        )
        assertTrue(atOpen.isFresh)
        val atCommit = GpsFreshnessPolicy.recheckAtCommit(atOpen, NOW + 300_000L)
        assertEquals(GpsStatus.STALE, atCommit.status)
        assertEquals(330_000L, atCommit.ageMs)
        assertNull(atCommit.publishableCoordinates)
    }

    @Test fun `a fix committed inside the window stays fresh`() {
        val atOpen = GpsFreshnessPolicy.forFix(
            LAT, LNG, 4f, NOW, "gps", GpsSource.CURRENT_FIX, NOW,
        )
        val atCommit = GpsFreshnessPolicy.recheckAtCommit(atOpen, NOW + 20_000L)
        assertEquals(GpsStatus.FRESH, atCommit.status)
        assertEquals(20_000L, atCommit.ageMs)
    }

    // Downgrade-only: a stale fix cannot become fresh again, whatever the clock does.
    @Test fun `the recheck never upgrades a stale fix`() {
        val stale = GpsFreshnessPolicy.forFix(
            LAT, LNG, 4f, NOW - 600_000L, "gps", GpsSource.LAST_KNOWN, NOW,
        )
        val rechecked = GpsFreshnessPolicy.recheckAtCommit(stale, NOW - 600_000L)
        assertEquals(GpsStatus.STALE, rechecked.status)
    }

    @Test fun `statuses without a fix are left untouched`() {
        for (status in listOf(GpsStatus.UNAVAILABLE, GpsStatus.PERMISSION_DENIED, GpsStatus.LOCATION_OFF, GpsStatus.UNKNOWN)) {
            val p = GpsProvenance.unavailable(status)
            assertEquals(p, GpsFreshnessPolicy.recheckAtCommit(p, NOW + 10_000_000L))
        }
    }
}

class GpsSidecarRoundTripTest {

    // Mirrors SessionRepository.buildMetadataJson exactly, including the top-level lat/lng rule.
    private fun sidecar(gps: GpsProvenance, operator: String = "Zainal"): JSONObject =
        JSONObject().apply {
            put("name", "DAMIMAS_A21B_0001")
            put("operator", PackageProvenanceCodec.operatorForOutput(operator))
            put("captureDate", "2026-07-27")
            put("timestamp", "2026-07-27T09:15:00.000Z")
            put("captureSet", PackageProvenanceCodec.identityJson(CaptureSetIdentity("set-a", "K7Q2M1", "K7Q2M1")))
            put("gps", PackageProvenanceCodec.gpsJson(gps))
            gps.recordedCoordinates?.let { (lat, lng) -> put("lat", lat); put("lng", lng) }
        }

    @Test fun `a fresh fix round-trips every field`() {
        val original = GpsFreshnessPolicy.forFix(
            LAT, LNG, 4.25f, NOW - 5_000L, "gps", GpsSource.CURRENT_FIX, NOW,
        )
        val restored = PackageProvenanceCodec.readGps(JSONObject(sidecar(original).toString()))
        assertEquals(GpsStatus.FRESH, restored.status)
        assertEquals(LAT, restored.latitude!!, 1e-9)
        assertEquals(LNG, restored.longitude!!, 1e-9)
        assertEquals(4.25f, restored.accuracyM!!, 0.001f)
        assertEquals(NOW - 5_000L, restored.fixTimeMillis)
        assertEquals(5_000L, restored.ageMs)
        assertEquals("gps", restored.provider)
        assertEquals(GpsSource.CURRENT_FIX, restored.source)
    }

    // Backward compatibility, deliberately: every already-delivered sidecar has top-level
    // lat/lng, and folder resume REWRITES that file. Suppressing the keys for a non-fresh fix
    // would have removed a coordinate from packages that are already in the ML engineer's hands
    // — and, since the 60 s window is shorter than one tree's capture, from ~100% of new ones.
    // The freshness claim lives in gps.status; the coordinate itself is never withheld.
    @Test fun `a stale fix keeps the legacy lat lng keys and is labelled stale`() {
        val original = GpsFreshnessPolicy.forFix(
            LAT, LNG, 30f, NOW - 7_200_000L, "network", GpsSource.LAST_KNOWN, NOW,
        )
        val json = sidecar(original)
        assertTrue("a recorded coordinate must still reach the legacy lat key", json.has("lat"))
        assertEquals(LAT, json.getDouble("lat"), 1e-9)
        assertEquals(LNG, json.getDouble("lng"), 1e-9)
        val restored = PackageProvenanceCodec.readGps(JSONObject(json.toString()))
        assertEquals(GpsStatus.STALE, restored.status)
        assertEquals(LAT, restored.latitude!!, 1e-9)
        assertEquals("the coordinate is kept, the freshness claim is not", 7_200_000L, restored.ageMs)
        assertNull("but it is never offered as a trustworthy fix", restored.publishableCoordinates)
    }

    @Test fun `an unavailable fix writes no coordinate keys at all`() {
        val json = sidecar(GpsProvenance.unavailable(GpsStatus.UNAVAILABLE))
        assertFalse("no fix must never fabricate a coordinate", json.has("lat"))
        assertFalse(json.has("lng"))
    }

    @Test fun `an unavailable fix round-trips with no coordinates`() {
        val json = sidecar(GpsProvenance.unavailable(GpsStatus.LOCATION_OFF))
        val restored = PackageProvenanceCodec.readGps(JSONObject(json.toString()))
        assertEquals(GpsStatus.LOCATION_OFF, restored.status)
        assertNull(restored.latitude)
        assertNull(restored.longitude)
    }

    // Backward compatibility with the already-collected 42/90-tree packages.
    @Test fun `a legacy sidecar keeps its coordinate and claims nothing about it`() {
        val legacy = JSONObject(
            """{"name":"DAMIMAS_A21B_0001","variety":"DAMIMAS","blok":"A21B","treeId":1,
                "operator":"","timestamp":"2026-07-27T09:15:00.000Z","lat":$LAT,"lng":$LNG}""",
        )
        val restored = PackageProvenanceCodec.readGps(legacy)
        assertEquals(GpsStatus.UNKNOWN, restored.status)
        assertEquals(GpsSource.LEGACY_SIDECAR, restored.source)
        assertEquals(LAT, restored.latitude!!, 1e-9)
        assertNull("an unknown fix must not be published as fresh", restored.publishableCoordinates)
        assertNull(restored.fixTimeMillis)
    }

    // optDouble()'s default is 0.0, which would silently place every GPS-less tree at null island.
    @Test fun `a legacy sidecar with no coordinates does not fabricate zero-zero`() {
        val legacy = JSONObject("""{"name":"DAMIMAS_A21B_0001","operator":""}""")
        val restored = PackageProvenanceCodec.readGps(legacy)
        assertEquals(GpsStatus.UNKNOWN, restored.status)
        assertNull(restored.latitude)
        assertNull(restored.longitude)
    }

    @Test fun `an unknown status string degrades to UNKNOWN rather than throwing`() {
        val json = JSONObject("""{"gps":{"status":"SOMETHING_NEW","source":"?"}}""")
        val restored = PackageProvenanceCodec.readGps(json)
        assertEquals(GpsStatus.UNKNOWN, restored.status)
        assertEquals(GpsSource.NONE, restored.source)
    }
}

class OperatorProvenanceTest {

    @Test fun `a blank operator is written as UNKNOWN, not an empty string`() {
        assertEquals("UNKNOWN", PackageProvenanceCodec.operatorForOutput(""))
        assertEquals("UNKNOWN", PackageProvenanceCodec.operatorForOutput(null))
        assertEquals("UNKNOWN", PackageProvenanceCodec.operatorForOutput("   "))
    }

    @Test fun `a real operator survives the round trip`() {
        val json = JSONObject().put("operator", PackageProvenanceCodec.operatorForOutput("Zainal"))
        assertEquals("Zainal", PackageProvenanceCodec.readOperator(json))
    }

    @Test fun `UNKNOWN reads back as unset rather than as a person named UNKNOWN`() {
        assertEquals("", PackageProvenanceCodec.readOperator(JSONObject().put("operator", "UNKNOWN")))
        assertEquals("", PackageProvenanceCodec.readOperator(JSONObject().put("operator", "")))
        assertEquals("", PackageProvenanceCodec.readOperator(null))
    }

    @Test fun `capture date is read explicitly, else from the legacy commit timestamp`() {
        assertEquals(
            "2026-07-27",
            PackageProvenanceCodec.readCaptureDate(JSONObject().put("captureDate", "2026-07-27")),
        )
        assertEquals(
            "2026-07-27",
            PackageProvenanceCodec.readCaptureDate(
                JSONObject().put("timestamp", "2026-07-27T09:15:00.000Z"),
            ),
        )
        assertEquals("", PackageProvenanceCodec.readCaptureDate(JSONObject()))
        assertEquals("", PackageProvenanceCodec.readCaptureDate(null))
    }

    @Test fun `identity round-trips through the sidecar`() {
        val identity = CaptureSetIdentity("set-a", "K7Q2M1", "K7Q2M1")
        val json = JSONObject().put("captureSet", PackageProvenanceCodec.identityJson(identity))
        assertEquals(identity, PackageProvenanceCodec.readIdentity(JSONObject(json.toString())))
    }

    @Test fun `a sidecar without an identity block reads as UNKNOWN identity`() {
        assertEquals(CaptureSetIdentity.UNKNOWN, PackageProvenanceCodec.readIdentity(JSONObject()))
        assertEquals(CaptureSetIdentity.UNKNOWN, PackageProvenanceCodec.readIdentity(null))
        assertFalse(CaptureSetIdentity.UNKNOWN.isKnown)
    }
}

class TreeMetadataCoordinateExposureTest {

    // TreeMetadata.latitude/longitude are the accessors older call sites reach for, and they
    // deliberately report the recorded coordinate whatever its freshness. Gating them was tried
    // and reverted: the window is 60 s while one tree takes minutes, so a gate would have emptied
    // lat/lng on ~100% of new packages AND stripped it from the already-delivered 42/90-tree
    // sidecars, which folder resume rewrites. The freshness claim lives in gps.status; withholding
    // a fix from consumers is publishableCoordinates' job, not this accessor's.
    @Test fun `metadata reports the recorded coordinate and defers the freshness claim to gps`() {
        val fresh = TreeMetadata(
            gps = GpsFreshnessPolicy.forFix(LAT, LNG, 4f, NOW, "gps", GpsSource.CURRENT_FIX, NOW),
        )
        assertEquals(LAT, fresh.latitude!!, 1e-9)
        assertEquals(LNG, fresh.longitude!!, 1e-9)
        assertEquals(LAT to LNG, fresh.gps.publishableCoordinates)

        val stale = TreeMetadata(
            gps = GpsFreshnessPolicy.forFix(
                LAT, LNG, 4f, NOW - 600_000L, "gps", GpsSource.LAST_KNOWN, NOW,
            ),
        )
        assertEquals("a stale fix must not lose its coordinate", LAT, stale.latitude!!, 1e-9)
        assertEquals(LNG, stale.longitude!!, 1e-9)
        assertEquals(GpsStatus.STALE, stale.gps.status)
        assertNull("but it is never offered as trustworthy", stale.gps.publishableCoordinates)
    }

    @Test fun `metadata without a fix reports no coordinate`() {
        val none = TreeMetadata(gps = GpsProvenance.unavailable(GpsStatus.LOCATION_OFF))
        assertNull(none.latitude)
        assertNull(none.longitude)
    }

    @Test fun `default metadata makes no GPS or identity claim`() {
        val metadata = TreeMetadata()
        assertEquals(GpsStatus.UNKNOWN, metadata.gps.status)
        assertFalse(metadata.identity.isKnown)
        assertEquals("", metadata.operatorName)
        assertEquals("", metadata.date)
    }
}
