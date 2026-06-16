package dev.sawitulm.palmannotate.domain.quality

import dev.sawitulm.palmannotate.domain.model.ActiveSession
import dev.sawitulm.palmannotate.domain.model.AnnotationClass
import dev.sawitulm.palmannotate.domain.model.TreeSide
import dev.sawitulm.palmannotate.domain.usecase.SessionUseCases

/**
 * Annotation quality checker.
 * Port of js/quality-check.js.
 *
 * Provides analyzeCaptureShots (pre-save) and analyzeTree (editor QA card + export gate).
 */
object QualityCheck {

    enum class Level { OK, INFO, WARN, ERROR }

    data class Issue(
        val code: String,
        val level: Level,
        val message: String,
    )

    data class CaptureReport(
        val status: Level,
        val metrics: Map<String, Int>,
        val issues: List<Issue>,
    )

    data class TreeReport(
        val status: Level,
        val metrics: Map<String, Int>,
        val issues: List<Issue>,
    )

    // ─── Capture Pre-Save Check ───────────────────────────────────────────────

    /**
     * Analyze capture shots before saving.
     * Port of QualityCheck.analyzeCaptureShots from quality-check.js.
     */
    fun analyzeCaptureShots(
        capturedSides: Int,
        expectedSides: Int,
        depthSides: Int,
        hasGps: Boolean,
        hasVariety: Boolean,
        hasBlock: Boolean,
    ): CaptureReport {
        val issues = mutableListOf<Issue>()

        if (capturedSides < expectedSides) {
            issues.add(Issue("capture_view_missing", Level.ERROR,
                "Hanya $capturedSides dari $expectedSides sisi yang berhasil ditangkap."))
        }
        if (depthSides < capturedSides) {
            issues.add(Issue("capture_rgb_depth_incomplete", Level.WARN,
                "Hanya $depthSides dari $capturedSides foto yang memiliki data depth."))
        }
        if (!hasGps) {
            issues.add(Issue("metadata_gps_missing", Level.WARN,
                "Lokasi GPS tidak tersedia. Tambahkan GPS untuk metadata yang lebih lengkap."))
        }
        if (!hasVariety) {
            issues.add(Issue("metadata_variety_missing", Level.ERROR,
                "Metadata variety belum terisi."))
        }
        if (!hasBlock) {
            issues.add(Issue("metadata_block_missing", Level.WARN,
                "Metadata block belum terisi."))
        }

        val status = if (issues.any { it.level == Level.ERROR }) Level.ERROR
            else if (issues.any { it.level == Level.WARN }) Level.WARN
            else Level.OK

        return CaptureReport(
            status = status,
            metrics = mapOf(
                "capturedSides" to capturedSides,
                "expectedSides" to expectedSides,
                "depthSides" to depthSides,
            ),
            issues = issues,
        )
    }

    // ─── Tree Quality Check ───────────────────────────────────────────────────

    /**
     * Analyze a tree's annotation quality.
     * Port of QualityCheck.analyzeTree from quality-check.js.
     * Indonesian messages match the original.
     */
    fun analyzeTree(session: ActiveSession): TreeReport {
        val issues = mutableListOf<Issue>()
        val sides = session.sides

        // Metadata checks
        if (session.metadata?.variety.isNullOrBlank()) {
            issues.add(Issue("metadata_variety_missing", Level.ERROR,
                "Metadata variety belum terisi."))
        }
        if (session.metadata?.block.isNullOrBlank()) {
            issues.add(Issue("metadata_block_missing", Level.WARN,
                "Metadata block belum terisi."))
        }

        // Image checks
        val sidesWithImages = sides.count { it.imageUri != null }
        if (sidesWithImages < sides.size) {
            issues.add(Issue("image_missing", Level.WARN,
                "${sides.size - sidesWithImages} sisi tidak memiliki gambar."))
        }

        // Empty annotations
        val emptySides = sides.count { it.bboxes.isEmpty() }
        if (emptySides > 0) {
            issues.add(Issue("annotation_empty", Level.WARN,
                "$emptySides sisi belum memiliki anotasi bbox."))
        }

        // Unassigned bboxes
        val totalUnassigned = sides.sumOf { it.unassignedBboxCount }
        if (totalUnassigned > 0) {
            issues.add(Issue("annotation_unassigned", Level.WARN,
                "$totalUnassigned bbox belum diberi kelas (unassigned)."))
        }

        // Very small bboxes (potential errors)
        val verySmall = sides.sumOf { side ->
            side.bboxes.count { bbox ->
                val area = (bbox.x2 - bbox.x1) * (bbox.y2 - bbox.y1)
                area < 100 // less than 10x10 pixels
            }
        }
        if (verySmall > 0) {
            issues.add(Issue("annotation_tiny_bbox", Level.INFO,
                "$verySmall bbox sangat kecil (< 10×10 px). Periksa apakah sudah benar."))
        }

        // Class mismatch in clusters
        val mismatches = SessionUseCases.getMismatchedClusters(session)
        if (mismatches.isNotEmpty()) {
            issues.add(Issue("annotation_class_mismatch", Level.ERROR,
                "${mismatches.size} bunch memiliki kelas yang tidak konsisten antar sisi."))
        }

        // No links when multi-side + multi-box
        if (sides.size > 1 && sides.sumOf { it.bboxes.size } > 1 && session.confirmedLinks.isEmpty()) {
            issues.add(Issue("annotation_no_links", Level.WARN,
                "Belum ada link cross-side. Gunakan Dedup untuk menghubungkan bunch yang sama."))
        }

        val status = if (issues.any { it.level == Level.ERROR }) Level.ERROR
            else if (issues.any { it.level == Level.WARN }) Level.WARN
            else Level.OK

        return TreeReport(
            status = status,
            metrics = mapOf(
                "totalSides" to sides.size,
                "totalBboxes" to sides.sumOf { it.bboxes.size },
                "unassigned" to totalUnassigned,
                "links" to session.confirmedLinks.size,
                "mismatches" to mismatches.size,
            ),
            issues = issues,
        )
    }
}
