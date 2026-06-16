package dev.sawitulm.palmannotate.data.storage

import android.net.Uri
import android.util.Log
import dev.sawitulm.palmannotate.domain.model.AnnotationClass
import dev.sawitulm.palmannotate.domain.model.Bbox
import dev.sawitulm.palmannotate.domain.model.CrossSideLink
import dev.sawitulm.palmannotate.domain.model.OutputSchema
import dev.sawitulm.palmannotate.domain.model.TreeMetadata
import dev.sawitulm.palmannotate.domain.model.TreeSide
import dev.sawitulm.palmannotate.domain.model.generateSideLabels
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Folder-scan resume importer.
 *
 * When the operator picks an export folder that already holds a `PalmAnnotate/`
 * structure, this rebuilds the prior runs + trees into Room from the on-disk
 * `Output JSON` *.json files (no `sessions.json` index is used or needed —
 * resume is purely folder-scan based).
 *
 * Best-effort: a malformed/partial Output JSON is skipped, never crashing the
 * whole import. App-external storage stays the PRIMARY working store — images
 * are copied from the chosen SAF folder into app-external so the resumed trees
 * are viewable; the SAF folder remains a mirror.
 */
@Singleton
class FolderResumeImporter @Inject constructor(
    private val repo: SessionRepository,
    private val storage: AndroidStorageManager,
    private val saf: SafMirrorStore,
) {

    companion object {
        private const val TAG = "FolderResume"
        private const val OUTPUT_JSON_DIR = "Output JSON"
        private const val IMAGES_DIR = "dataset/images/field"
        private const val METADATA_DIR = "dataset/metadata"

        /**
         * Pure grouping + dedupe (no Android deps — unit-testable). Groups scanned
         * trees into runs by groupKey, drops any tree whose name already exists in
         * Room ([existingTreeNames]). A run plan is emitted only when it has at least
         * one new tree. The run's sideCount is the max side count across its (new)
         * trees, floored at 2.
         */
        fun planRuns(
            scanned: List<ScannedTree>,
            existingTreeNames: Set<String>,
        ): List<RunPlan> {
            val byGroup = LinkedHashMap<String, MutableList<ScannedTree>>()
            for (t in scanned) {
                if (t.treeName in existingTreeNames) continue
                byGroup.getOrPut(t.groupKey) { mutableListOf() }.add(t)
            }
            return byGroup.values
                .filter { it.isNotEmpty() }
                .map { trees ->
                    val first = trees.first()
                    val sideCount = (trees.maxOfOrNull { it.sides.size } ?: 2).coerceAtLeast(2)
                    RunPlan(first.variety, first.block, first.groupKey, sideCount, trees)
                }
        }
    }

    /**
     * One tree parsed from an Output JSON (+ its metadata sidecar), pre-grouping.
     * [variety]/[block] resolve the owning run; [groupKey] is the dedupe/grouping key.
     */
    data class ScannedTree(
        val treeName: String,
        val treeId: Int,
        val split: String,
        val variety: String,
        val block: String,
        val groupKey: String,
        val sides: List<ParsedSidePlan>,
        val confirmedLinks: List<CrossSideLink>,
    )

    data class ParsedSidePlan(
        val sideIndex: Int,
        val imageWidth: Int,
        val imageHeight: Int,
        val bboxes: List<Bbox>,
    )

    /** A run to (re)create plus the trees that should be inserted under it. */
    data class RunPlan(
        val variety: String,
        val block: String,
        val groupKey: String,
        val sideCount: Int,
        val trees: List<ScannedTree>,
    )

    /**
     * Scan the chosen SAF folder, reconstruct prior runs/trees, ingest into Room.
     * Returns the number of trees imported (0 = nothing to resume / new folder).
     * Never throws.
     */
    suspend fun resumeFromFolder(safTreeUri: Uri): Int = withContext(Dispatchers.IO) {
        try {
            val jsonNames = saf.listFiles(safTreeUri, OUTPUT_JSON_DIR, ".json")
            if (jsonNames.isEmpty()) return@withContext 0

            val imageNames = saf.listFiles(safTreeUri, IMAGES_DIR, ".jpg").toHashSet()

            val scanned = ArrayList<ScannedTree>()
            for (name in jsonNames) {
                val parsed = runCatching { scanOne(safTreeUri, name, imageNames) }.getOrNull()
                if (parsed != null) scanned.add(parsed)
            }
            if (scanned.isEmpty()) return@withContext 0

            // Dedupe against runs/trees already in Room.
            val existingTreeNames = repo.allTreeNames().toHashSet()
            val existingGroupRuns = repo.runGroupKeyToId()
            val plans = planRuns(scanned, existingTreeNames)
            if (plans.isEmpty()) return@withContext 0

            var imported = 0
            for (plan in plans) {
                // Reuse an existing run with the same group key, else create one.
                val runId = existingGroupRuns[plan.groupKey]
                    ?: repo.createRun(plan.variety, plan.block, plan.sideCount, autoId = true)
                for (tree in plan.trees) {
                    val ok = ingestTree(safTreeUri, runId, tree, imageNames)
                    if (ok) imported++
                }
            }
            imported
        } catch (e: Exception) {
            Log.w(TAG, "resumeFromFolder failed", e)
            0
        }
    }

    // ─── internals ───────────────────────────────────────────────────────────

    /** Parse one Output JSON (+ metadata sidecar) into a ScannedTree, or null if unusable. */
    private fun scanOne(safTreeUri: Uri, jsonName: String, imageNames: Set<String>): ScannedTree? {
        val text = saf.readText(safTreeUri, "$OUTPUT_JSON_DIR/$jsonName") ?: return null
        val json = runCatching { JSONObject(text) }.getOrNull() ?: return null
        val parsed = runCatching { OutputSchema.toSessionData(json) }.getOrNull() ?: return null
        if (parsed.sides.isEmpty()) return null

        // Confirm at least one side image exists in the folder (skip orphaned JSON).
        val hasAnyImage = parsed.sides.any { s ->
            imageNames.contains("${parsed.treeName}_${s.sideIndex + 1}.jpg")
        }
        if (!hasAnyImage) return null

        val variety = json.optJSONObject("metadata")?.optString("variety")?.takeIf { it.isNotBlank() }
            ?: deriveVariety(parsed.treeName)
        val block = resolveBlock(safTreeUri, parsed.treeName)

        val treeId = parseTreeId(parsed.treeName)
        val sides = parsed.sides.map { s ->
            ParsedSidePlan(
                sideIndex = s.sideIndex,
                imageWidth = s.imageWidth,
                imageHeight = s.imageHeight,
                bboxes = s.bboxes.map { Bbox(it.id, it.classId, it.className, it.x1, it.y1, it.x2, it.y2) },
            )
        }
        // createOrNull (not create): one degenerate link in an imported Output JSON must
        // skip only that link — not throw and drop the whole tree from resume (scanOne is
        // wrapped in runCatching upstream, so a throw here = silent loss of the tree).
        val links = parsed.confirmedLinks.mapNotNull {
            CrossSideLink.createOrNull(it.linkId, it.sideA, it.bboxIdA, it.sideB, it.bboxIdB)
        }
        return ScannedTree(
            treeName = parsed.treeName, treeId = treeId, split = parsed.split,
            variety = variety, block = block, groupKey = repo.groupKeyFor(variety, block),
            sides = sides, confirmedLinks = links,
        )
    }

    /** Read block from the metadata sidecar ("blok"), else parse it from the tree name. */
    private fun resolveBlock(safTreeUri: Uri, treeName: String): String {
        val metaText = saf.readText(safTreeUri, "$METADATA_DIR/${treeName}.json")
        if (metaText != null) {
            runCatching {
                val blok = JSONObject(metaText).optString("blok").ifBlank {
                    JSONObject(metaText).optString("block")
                }
                if (blok.isNotBlank()) return blok
            }
        }
        return deriveBlock(treeName)
    }

    /**
     * Persist one resumed tree: copy its side images from SAF into the app-external
     * primary store (so they are viewable), then add it via the repository.
     */
    private suspend fun ingestTree(
        safTreeUri: Uri,
        runId: String,
        tree: ScannedTree,
        imageNames: Set<String>,
    ): Boolean {
        val labels = generateSideLabels(tree.sides.size)
        val sides = tree.sides.map { s ->
            val imgUri = copyImageToPrimary(safTreeUri, tree.treeName, s.sideIndex, imageNames)
            val boxes = s.bboxes
            TreeSide(
                sideIndex = s.sideIndex,
                label = labels.getOrElse(s.sideIndex) { "Side ${s.sideIndex + 1}" },
                imageUri = imgUri,
                labelUri = null,
                imageWidth = s.imageWidth,
                imageHeight = s.imageHeight,
                bboxes = boxes,
                originalBboxes = boxes,
            )
        }
        val metadata = TreeMetadata(variety = tree.variety, block = tree.block, treeId = tree.treeId.toString())
        return runCatching {
            // addTree persists sides/bboxes + labels and advances the run's nextId.
            val treeKey = repo.addTree(runId, tree.treeName, tree.treeId, tree.split, sides, metadata, safTreeUri)
            // Restore confirmed links (addTree does not carry them).
            if (tree.confirmedLinks.isNotEmpty()) {
                repo.replaceConfirmedLinks(treeKey, tree.confirmedLinks)
            }
            true
        }.getOrElse {
            Log.w(TAG, "ingestTree failed for ${tree.treeName}", it)
            false
        }
    }

    /** Copy a side image from the SAF folder into app-external; return its file URI (or null). */
    private fun copyImageToPrimary(
        safTreeUri: Uri,
        treeName: String,
        sideIndex: Int,
        imageNames: Set<String>,
    ): Uri? {
        val fileName = "${treeName}_${sideIndex + 1}.jpg"
        if (fileName !in imageNames) return null
        val dest = storage.imageFile(treeName, sideIndex)
        if (!dest.exists()) {
            val bytes = saf.readBytes(safTreeUri, "$IMAGES_DIR/$fileName") ?: return null
            runCatching { storage.writeBytes(dest, bytes) }.getOrElse { return null }
        }
        return Uri.fromFile(dest)
    }

    private fun deriveVariety(treeName: String): String =
        Regex("^([A-Za-z0-9]+)_").find(treeName)?.groupValues?.get(1)?.uppercase() ?: "UNKNOWN"

    /** Block = the middle token of {VARIETY}_{BLOCK}_{ID}; empty when no clear middle. */
    private fun deriveBlock(treeName: String): String {
        val parts = treeName.split('_')
        return if (parts.size >= 3) parts[1] else ""
    }

    /** Tree id = trailing numeric token, else 0. */
    private fun parseTreeId(treeName: String): Int {
        val last = treeName.substringAfterLast('_')
        return last.toIntOrNull() ?: last.filter { it.isDigit() }.toIntOrNull() ?: 0
    }
}
