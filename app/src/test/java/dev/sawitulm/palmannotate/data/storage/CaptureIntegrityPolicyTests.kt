package dev.sawitulm.palmannotate.data.storage

import dev.sawitulm.palmannotate.domain.model.CaptureOrigin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureIntegrityPolicyTest {

    @Test fun `Orbbec with a valid depth pair is accepted and requires depth`() {
        val result = evaluate(CaptureOrigin.ORBBEC, hasAnyDepth = true, hasValidDepth = true)

        assertNull(result.error)
        assertEquals(CaptureOrigin.ORBBEC, result.captureOrigin)
        assertTrue(result.depthRequired)
    }

    @Test fun `Orbbec without valid depth is rejected`() {
        val result = evaluate(CaptureOrigin.ORBBEC, hasAnyDepth = false, hasValidDepth = false)

        assertNotNull(result.error)
        assertTrue(result.depthRequired)
    }

    @Test fun `tablet RGB without depth is accepted`() {
        val result = evaluate(
            CaptureOrigin.PHONE_CAMERA,
            hasAnyDepth = false,
            hasValidDepth = false,
        )

        assertNull(result.error)
        assertFalse(result.depthRequired)
    }

    @Test fun `tablet RGB with depth is rejected`() {
        val result = evaluate(
            CaptureOrigin.PHONE_CAMERA,
            hasAnyDepth = true,
            hasValidDepth = true,
        )

        assertNotNull(result.error)
    }

    @Test fun `unknown capture with valid depth is resolved as Orbbec`() {
        val result = evaluate(CaptureOrigin.UNKNOWN, hasAnyDepth = true, hasValidDepth = true)

        assertNull(result.error)
        assertEquals(CaptureOrigin.ORBBEC, result.captureOrigin)
        assertTrue(result.depthRequired)
    }

    @Test fun `unknown depth without content binding remains unverified legacy`() {
        val result = evaluate(
            CaptureOrigin.UNKNOWN,
            hasAnyDepth = true,
            hasValidDepth = true,
            hasVerifiedDepthBinding = false,
        )

        assertNull(result.error)
        assertEquals(CaptureOrigin.IMPORTED_LEGACY, result.captureOrigin)
    }

    @Test fun `declared Orbbec without content binding is rejected`() {
        val result = evaluate(
            CaptureOrigin.ORBBEC,
            hasAnyDepth = true,
            hasValidDepth = true,
            hasVerifiedDepthBinding = false,
        )

        assertNotNull(result.error)
    }

    @Test fun `unknown RGB only remains accessible as unverified legacy`() {
        val result = evaluate(
            CaptureOrigin.UNKNOWN,
            hasAnyDepth = false,
            hasValidDepth = false,
        )

        assertNull(result.error)
        assertEquals(CaptureOrigin.IMPORTED_LEGACY, result.captureOrigin)
        assertFalse(result.depthRequired)
    }

    @Test fun `strict export rejects unverified legacy provenance`() {
        val result = evaluate(
            CaptureOrigin.IMPORTED_LEGACY,
            hasAnyDepth = false,
            hasValidDepth = false,
            rejectUnverifiedLegacy = true,
        )

        assertNotNull(result.error)
    }

    @Test fun `partial or mismatched depth pair is always rejected`() {
        val result = evaluate(
            CaptureOrigin.UNKNOWN,
            hasAnyDepth = true,
            hasValidDepth = false,
        )

        assertNotNull(result.error)
    }

    private fun evaluate(
        origin: CaptureOrigin,
        hasAnyDepth: Boolean,
        hasValidDepth: Boolean,
        hasVerifiedDepthBinding: Boolean = hasValidDepth,
        rejectUnverifiedLegacy: Boolean = false,
    ): CaptureIntegrityDecision = CaptureIntegrityPolicy.evaluate(
        storedOrigin = origin,
        declaredDepthRequired = false,
        hasAnyDepth = hasAnyDepth,
        hasValidDepth = hasValidDepth,
        hasVerifiedDepthBinding = hasVerifiedDepthBinding,
        rejectUnverifiedLegacy = rejectUnverifiedLegacy,
    )
}
