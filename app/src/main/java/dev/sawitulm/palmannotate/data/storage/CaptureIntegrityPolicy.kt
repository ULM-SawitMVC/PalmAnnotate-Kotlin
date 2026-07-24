package dev.sawitulm.palmannotate.data.storage

import dev.sawitulm.palmannotate.domain.model.CaptureOrigin

internal data class CaptureIntegrityDecision(
    val captureOrigin: CaptureOrigin,
    val depthRequired: Boolean,
    val error: String? = null,
)

/**
 * Pure provenance/depth policy shared by annotation saves and export preflight.
 */
internal object CaptureIntegrityPolicy {
    fun evaluate(
        storedOrigin: CaptureOrigin,
        declaredDepthRequired: Boolean,
        hasAnyDepth: Boolean,
        hasValidDepth: Boolean,
        hasVerifiedDepthBinding: Boolean,
        rejectUnverifiedLegacy: Boolean,
    ): CaptureIntegrityDecision {
        if (hasVerifiedDepthBinding && !hasValidDepth) {
            return CaptureIntegrityDecision(
                storedOrigin,
                declaredDepthRequired,
                "depth binding was reported without a valid depth pair",
            )
        }
        if (hasAnyDepth && !hasValidDepth) {
            return CaptureIntegrityDecision(
                storedOrigin,
                declaredDepthRequired,
                "depth pair is incomplete or mismatched",
            )
        }
        val resolvedOrigin = when {
            storedOrigin == CaptureOrigin.UNKNOWN &&
                hasValidDepth &&
                hasVerifiedDepthBinding -> CaptureOrigin.ORBBEC
            storedOrigin == CaptureOrigin.UNKNOWN -> CaptureOrigin.IMPORTED_LEGACY
            else -> storedOrigin
        }
        if (resolvedOrigin == CaptureOrigin.PHONE_CAMERA && hasAnyDepth) {
            return CaptureIntegrityDecision(
                resolvedOrigin,
                false,
                "tablet RGB unexpectedly contains depth",
            )
        }
        val depthRequired = declaredDepthRequired || resolvedOrigin == CaptureOrigin.ORBBEC
        if (depthRequired && !hasValidDepth) {
            return CaptureIntegrityDecision(
                resolvedOrigin,
                true,
                "Orbbec depth is required",
            )
        }
        if (resolvedOrigin == CaptureOrigin.ORBBEC && !hasVerifiedDepthBinding) {
            return CaptureIntegrityDecision(
                resolvedOrigin,
                true,
                "Orbbec depth is not cryptographically bound to its RGB",
            )
        }
        if (rejectUnverifiedLegacy &&
            (resolvedOrigin == CaptureOrigin.UNKNOWN ||
                resolvedOrigin == CaptureOrigin.IMPORTED_LEGACY)
        ) {
            return CaptureIntegrityDecision(
                resolvedOrigin,
                depthRequired,
                "camera provenance is unverified legacy data",
            )
        }
        return CaptureIntegrityDecision(resolvedOrigin, depthRequired)
    }
}
