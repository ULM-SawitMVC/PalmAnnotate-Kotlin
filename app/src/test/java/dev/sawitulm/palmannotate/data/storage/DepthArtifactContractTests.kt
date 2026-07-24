package dev.sawitulm.palmannotate.data.storage

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class DepthArtifactContractTest {

    private fun metadata(
        width: Int = 640,
        height: Int = 400,
        format: String = "Y16",
        valueScale: Double = 0.1,
        encoding: String? = "uint16le",
    ): String = JSONObject().apply {
        put("width", width)
        put("height", height)
        put("format", format)
        put("valueScale", valueScale)
        if (encoding != null) put("encoding", encoding)
    }.toString()

    @Test fun `valid Y16 pair is accepted`() {
        assertNull(DepthArtifactContract.validationError(640L * 400L * 2L, metadata()))
    }

    @Test fun `legacy metadata is structurally valid but not content bound`() {
        assertNull(DepthArtifactContract.validationError(640L * 400L * 2L, metadata()))
        assertEquals(false, DepthArtifactContract.hasContentBindings(metadata()))
    }

    @Test fun `raw and RGB hashes make metadata content bound`() {
        val hash = "a".repeat(64)
        val bound = JSONObject(metadata()).apply {
            put("rawSha256", hash)
            put("rgbSha256", hash)
        }.toString()

        assertEquals(true, DepthArtifactContract.hasContentBindings(bound))
    }

    @Test fun `legacy metadata without encoding remains readable`() {
        assertNull(
            DepthArtifactContract.validationError(
                640L * 400L * 2L,
                metadata(encoding = null),
            )
        )
    }

    @Test fun `short or oversized raw buffer is rejected`() {
        assertNotNull(DepthArtifactContract.validationError(1L, metadata()))
        assertNotNull(DepthArtifactContract.validationError(640L * 400L * 2L + 2L, metadata()))
    }

    @Test fun `packed depth cannot be mislabeled as uint16`() {
        assertNotNull(
            DepthArtifactContract.validationError(
                640L * 400L * 2L,
                metadata(format = "Y12"),
            )
        )
        assertNotNull(
            DepthArtifactContract.validationError(
                640L * 400L * 2L,
                metadata(encoding = "packed12"),
            )
        )
    }

    @Test fun `missing nonpositive or nonfinite scale is rejected`() {
        val missing = JSONObject(metadata()).apply { remove("valueScale") }.toString()
        assertNotNull(DepthArtifactContract.validationError(640L * 400L * 2L, missing))
        assertNotNull(
            DepthArtifactContract.validationError(
                640L * 400L * 2L,
                metadata(valueScale = 0.0),
            )
        )
        val nonfinite = JSONObject(metadata()).apply { put("valueScale", "NaN") }.toString()
        assertNotNull(DepthArtifactContract.validationError(640L * 400L * 2L, nonfinite))
    }

    @Test fun `bad dimensions and malformed JSON are rejected`() {
        assertNotNull(
            DepthArtifactContract.validationError(
                0L,
                metadata(width = 0, height = 400),
            )
        )
        assertNotNull(DepthArtifactContract.validationError(0L, "{not-json"))
    }

    @Test fun `sha256 implementation matches standard vector`() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            DepthArtifactContract.sha256Hex("abc".toByteArray()),
        )
    }

    @Test fun `checksums bind raw and RGB to their metadata`() {
        val raw = byteArrayOf(1, 0, 2, 0, 3, 0, 4, 0)
        val rgb = "jpeg-content".toByteArray()
        val rawHash = DepthArtifactContract.sha256Hex(raw)
        val rgbHash = DepthArtifactContract.sha256Hex(rgb)
        val checksummed = JSONObject(metadata(width = 2, height = 2)).apply {
            put("rawSha256", rawHash)
            put("rgbSha256", rgbHash)
        }.toString()

        assertNull(
            DepthArtifactContract.validationError(
                raw.size.toLong(),
                checksummed,
                actualRawSha256 = rawHash,
                actualRgbSha256 = rgbHash,
            )
        )
        assertNotNull(
            DepthArtifactContract.validationError(
                raw.size.toLong(),
                checksummed,
                actualRawSha256 = DepthArtifactContract.sha256Hex(raw.copyOf().also { it[0] = 9 }),
                actualRgbSha256 = rgbHash,
            )
        )
        assertNotNull(
            DepthArtifactContract.validationError(
                raw.size.toLong(),
                checksummed,
                actualRawSha256 = rawHash,
                actualRgbSha256 = DepthArtifactContract.sha256Hex("other-rgb".toByteArray()),
            )
        )
        assertNotNull(DepthArtifactContract.validationError(raw.size.toLong(), checksummed))
    }
}
