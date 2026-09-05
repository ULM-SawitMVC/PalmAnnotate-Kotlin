package dev.sawitulm.palmannotate.data.storage

import android.net.Uri
import android.graphics.BitmapFactory
import android.util.Log
import dev.sawitulm.palmannotate.data.export.CaptureSetMergePolicy
import dev.sawitulm.palmannotate.domain.model.AnnotationClass
import dev.sawitulm.palmannotate.domain.model.Bbox
import dev.sawitulm.palmannotate.domain.model.CaptureOrigin
import dev.sawitulm.palmannotate.domain.model.CaptureSetIdentity
import dev.sawitulm.palmannotate.domain.model.CaptureSetPolicy
import dev.sawitulm.palmannotate.domain.model.CrossSideLink
import dev.sawitulm.palmannotate.domain.model.DatasetType
import dev.sawitulm.palmannotate.domain.model.GpsProvenance
import dev.sawitulm.palmannotate.domain.model.OutputSchema
import dev.sawitulm.palmannotate.domain.model.TreeMetadata
import dev.sawitulm.palmannotate.domain.model.TreeSide
import dev.sawitulm.palmannotate.domain.model.generateSideLabels
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.UUID
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
    private val inputCache: InputCache,
) {

    companion object {
        private const val TAG = "FolderResume"
        private const val OUTPUT_JSON_DIR = "Output JSON"
        private const val OUTPUT_TXT_DIR = "Output TXT/field"
        private const val IMAGES_DIR = "dataset/images/field"
        private const val DEPTH_DIR = "dataset/depth/field"
        private const val METADATA_DIR = "dataset/metadata"
        private const val MANIFEST_DIR = "dataset/manifests"

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
                    RunPlan(first.variety, first.block, first.groupKey, sideCount, trees, first.datasetType)
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
        // WS-12/WS-13: provenance read back from the package's own sidecar. Defaults keep every
        // existing caller and test compiling, and describe a legacy package honestly: no
        // identity, no operator, no GPS claim.
        val identity: CaptureSetIdentity = CaptureSetIdentity.UNKNOWN,
        val contentDigest: String = "",
        val operatorName: String = "",
        val captureDate: String = "",
        val capturedAtMillis: Long? = null,
        val gps: GpsProvenance = GpsProvenance.UNKNOWN,
        val datasetType: DatasetType = DatasetType.MULTISIDE,
    )

    data class ParsedSidePlan(
        val sideIndex: Int,
        val imageWidth: Int,
        val imageHeight: Int,
        val bboxes: List<Bbox>,
        val rgbSha256: String? = null,
        val captureOrigin: CaptureOrigin = CaptureOrigin.UNKNOWN,
        val depthRequired: Boolean = false,
    )

    private data class RestoredDepth(
        val valid: Boolean = false,
        val contentBound: Boolean = false,
    )

    /** A run to (re)create plus the trees that should be inserted under it. */
    data class RunPlan(
        val variety: String,
        val block: String,
        val groupKey: String,
        val sideCount: Int,
        val trees: List<ScannedTree>,
        val datasetType: DatasetType = DatasetType.MULTISIDE,
    )

    /**
     * Scan the chosen SAF folder, reconstruct prior runs/trees, ingest into Room.
     * Returns the number of trees imported (0 = nothing to resume / new folder).
     * Malformed packages are skipped. Storage failures are reported to the caller so an
     * interrupted import can be retried on the next launch.
     */
    suspend fun resumeFromFolder(safTreeUri: Uri): Int = withContext(Dispatchers.IO) {
        val jsonNames = listFilesForResume(safTreeUri, OUTPUT_JSON_DIR, ".json")
        if (jsonNames.isEmpty()) return@withContext 0

        val imageNames = listFilesForResume(safTreeUri, IMAGES_DIR, ".jpg").toHashSet()

        val scanned = ArrayList<ScannedTree>()
        var scanFailures = 0
        for (name in jsonNames) {
            val scanResult = runCatching { scanOne(safTreeUri, name, imageNames) }
            scanResult.exceptionOrNull()?.let {
                scanFailures++
                Log.w(TAG, "resume scan failed for $name", it)
            }
            val parsed = scanResult.getOrNull()
            if (parsed != null) scanned.add(parsed)
        }
        check(scanFailures == 0) { "Failed to scan $scanFailures tree package(s)" }
        // A rejected package must stay visible instead of looking like "nothing new to resume",
        // but it must not block the packages that ARE importable — that would turn one bad folder
        // entry into a total recovery outage. The report is raised after ingest, below.
        val rejected = jsonNames.size - scanned.size
        if (scanned.isEmpty()) {
            check(rejected == 0) { "Rejected $rejected incomplete or invalid tree package(s)" }
            return@withContext 0
        }

        val blockedByDeletion = repo.pendingDeletionTreeNames(safTreeUri)
            .intersect(scanned.mapTo(mutableSetOf()) { it.treeName })
        if (blockedByDeletion.isNotEmpty()) {
            throw SafResumeException(
                "Resume blocked: remote deletion is still pending for ${blockedByDeletion.sorted().joinToString()}",
            )
        }

        // Dedupe against runs/trees already in Room.
        val existingTreeNames = repo.allTreeNames().toHashSet()
        val existingGroupRuns = repo.runGroupKeyToId()
        // WS-12: a name that already exists locally used to be dropped in silence. That is the
        // exact moment two tablets' DAMIMAS_A21B_0001 meet, so the reason is now stated. The tree
        // is still skipped (importing it would need a name that is already taken); what changes
        // is that the collision is visible instead of looking like "nothing new to resume".
        reportIdentityCollisions(scanned)
        val plans = planRuns(scanned, existingTreeNames)
        if (plans.isEmpty()) {
            check(rejected == 0) { "Rejected $rejected incomplete or invalid tree package(s)" }
            return@withContext 0
        }

        var imported = 0
        var failed = 0
        for (plan in plans) {
            // Reuse an existing run with the same group key, else create one.
            //
            // WS-12: a resumed run gets THIS device's identity, because it is this device that
            // will capture the next tree in it. The trees being imported are unaffected — each
            // keeps the identity recorded in its own sidecar (see ingestTree). nameToken stays
            // blank so the resumed folder's existing filenames continue unchanged; without an
            // identity here the run would stay anonymous forever and every later capture in it
            // would ship with a blank capture set, which is the collision WS-12 exists to close.
            val continuingIdentity = CaptureSetIdentity(
                captureSetId = UUID.randomUUID().toString(),
                deviceToken = runCatching { inputCache.deviceToken }.getOrDefault(""),
                nameToken = "",
            )
            val runId = existingGroupRuns[plan.groupKey]?.also {
                repo.adoptRunProvenance(it, continuingIdentity)
            } ?: repo.createRun(
                plan.variety,
                plan.block,
                plan.sideCount,
                autoId = true,
                identity = continuingIdentity,
                datasetType = plan.datasetType,
            )
            for (tree in plan.trees) {
                val ok = ingestTree(safTreeUri, runId, tree, imageNames)
                if (ok) imported++ else failed++
            }
        }
        check(failed == 0) { "Failed to resume $failed valid tree package(s)" }
        // Everything importable is now committed to Room. Only now is the rejected count raised,
        // so the folder is not marked fully resumed while a package still needs attention.
        check(rejected == 0) { "Rejected $rejected incomplete or invalid tree package(s)" }
        imported
    }

    // ─── internals ───────────────────────────────────────────────────────────

    private fun listFilesForResume(treeUri: Uri, dirPath: String, suffix: String): List<String> =
        SafResumeOutcomePolicy.requireListing(saf.listFilesResult(treeUri, dirPath, suffix), dirPath)

    private fun readBytesForResume(treeUri: Uri, path: String): ByteArray? =
        SafResumeOutcomePolicy.optionalBytes(saf.readBytesResult(treeUri, path), path)

    private fun readTextForResume(treeUri: Uri, path: String): String? =
        readBytesForResume(treeUri, path)?.toString(Charsets.UTF_8)

    /** Parse one Output JSON (+ metadata sidecar) into a ScannedTree, or null if unusable. */
    private fun scanOne(safTreeUri: Uri, jsonName: String, imageNames: Set<String>): ScannedTree? {
        val text = readTextForResume(safTreeUri,"$OUTPUT_JSON_DIR/$jsonName") ?: return null
        val json = runCatching { JSONObject(text) }.getOrNull() ?: return null
        val parsed = runCatching { OutputSchema.toSessionData(json) }.getOrNull() ?: return null
        if (parsed.sides.isEmpty()) return null
        ArtifactIdentityPolicy.treeNameError(parsed.treeName)?.let {
            Log.w(TAG, "resume skipped unsafe tree name '${parsed.treeName}': $it")
            return null
        }
        ArtifactIdentityPolicy.sideSetError(parsed.sides.map { it.sideIndex })?.let {
            Log.w(TAG, "resume skipped ${parsed.treeName}: $it")
            return null
        }
        val manifestText = readTextForResume(safTreeUri,"$MANIFEST_DIR/${parsed.treeName}.json")
        val requiresManifest = parsed.sides.all {
            it.rgbSha256 != null && it.captureOrigin != CaptureOrigin.UNKNOWN
        }
        if (requiresManifest && manifestText == null) {
            Log.w(TAG, "resume skipped ${parsed.treeName}: strict package has no commit manifest")
            return null
        }
        if (manifestText != null &&
            !manifestMatchesMirror(safTreeUri, parsed.treeName, text, manifestText)
        ) {
            Log.w(TAG, "resume skipped ${parsed.treeName}: package manifest mismatch")
            return null
        }

        // A tree is one package. Never reconstruct a partial side list from a JSON that names
        // images missing from the mirror.
        val hasEveryImage = parsed.sides.isNotEmpty() && parsed.sides.all { s ->
            imageNames.contains("${parsed.treeName}_${s.sideIndex + 1}.jpg")
        }
        if (!hasEveryImage) return null

        val variety = json.optJSONObject("metadata")?.optString("variety")?.takeIf { it.isNotBlank() }
            ?: deriveVariety(parsed.treeName)
        // Read the metadata sidecar ONCE and take everything from it. Previously only "blok" was
        // read here, so a resumed tree lost its capture date, operator and GPS record and got a
        // fresh set of blanks on the next export.
        val metaJson = readTextForResume(safTreeUri, "$METADATA_DIR/${parsed.treeName}.json")
            ?.let { runCatching { JSONObject(it) }.getOrNull() }
        val block = resolveBlock(metaJson, parsed.treeName, parsed.datasetType)

        val treeId = parseTreeId(parsed.treeName)
        val sides = parsed.sides.map { s ->
            ParsedSidePlan(
                sideIndex = s.sideIndex,
                imageWidth = s.imageWidth,
                imageHeight = s.imageHeight,
                bboxes = s.bboxes.map {
                    Bbox(it.id, it.classId, it.className, it.x1, it.y1, it.x2, it.y2, it.measurements)
                },
                rgbSha256 = s.rgbSha256,
                captureOrigin = s.captureOrigin,
                depthRequired = s.depthRequired,
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
            variety = variety,
            block = block,
            groupKey = repo.groupKeyFor(variety, block, parsed.datasetType),
            sides = sides, confirmedLinks = links,
            identity = PackageProvenanceCodec.readIdentity(metaJson),
            contentDigest = manifestText?.let {
                runCatching { JSONObject(it).optString("annotationRevision") }.getOrDefault("")
            }.orEmpty(),
            operatorName = PackageProvenanceCodec.readOperator(metaJson),
            captureDate = PackageProvenanceCodec.readCaptureDate(metaJson),
            capturedAtMillis = PackageProvenanceCodec.readCapturedAtMillis(metaJson),
            gps = PackageProvenanceCodec.readGps(metaJson),
            datasetType = parsed.datasetType,
        )
    }

    /**
     * WS-12 — state, per colliding tree name, why a scanned package cannot be merged with what
     * Room already holds.
     *
     * Only a collision that can be *proved* — both sides carry an identity and they disagree, or
     * they agree yet the committed content differs — blocks the resume. A missing identity is not
     * evidence of a second device: every legacy package has none, and the trees this very device
     * imported from such a folder now hold the run's identity locally while their sidecars stay
     * blank. Treating that pairing as a conflict made resume refuse the whole folder (observed on
     * tablet b98cea56: 151 "collisions" against packages it had itself imported), so it stays a
     * warning, exactly as before.
     */
    private suspend fun reportIdentityCollisions(scanned: List<ScannedTree>) {
        val existing = repo.allTreeIdentities()
        if (existing.isEmpty()) return
        val remote = scanned.mapNotNull { tree ->
            val local = existing[tree.treeName] ?: return@mapNotNull null
            if (!local.hasIdentity || tree.identity.captureSetId.isBlank()) {
                if (local.hasIdentity || tree.identity.captureSetId.isNotBlank()) {
                    Log.w(
                        TAG,
                        "resume: ${tree.treeName} pairs a package with no capture-set identity " +
                            "against a local row that has one; keeping the committed tree",
                    )
                }
                return@mapNotNull null
            }
            local to CaptureSetMergePolicy.Entry(
                treeName = tree.treeName,
                captureSetId = tree.identity.captureSetId,
                deviceToken = tree.identity.deviceToken,
                // An older local import may have no manifest to digest. Falling back to the remote
                // digest keeps "same identity, unknown local content" out of the conflict path.
                contentDigest = tree.contentDigest.ifBlank { local.contentDigest },
            )
        }
        if (remote.isEmpty()) return
        val verdict = CaptureSetMergePolicy.evaluate(
            listOf(remote.map { it.first }, remote.map { it.second }),
        )
        if (verdict is CaptureSetMergePolicy.Verdict.Conflict) {
            val details = verdict.collisions.joinToString("; ") { it.describe() }
            throw SafResumeException("Resume identity collision — $details")
        }
    }

    private fun manifestMatchesMirror(
        safTreeUri: Uri,
        treeName: String,
        outputJsonText: String,
        manifestText: String,
    ): Boolean {
        return runCatching {
            val manifest = JSONObject(manifestText)
            val parsed = OutputSchema.toSessionData(JSONObject(outputJsonText))
            if (parsed.treeName != treeName) return@runCatching false
            val parsedBySide = parsed.sides.associateBy { it.sideIndex }
            if (parsedBySide.size != parsed.sides.size) return@runCatching false
            if (manifest.optInt("schemaVersion") != 1) return@runCatching false
            if (manifest.optString("treeName") != treeName) return@runCatching false
            val outputRecord = manifest.optJSONObject("outputJson")
                ?: return@runCatching false
            if (outputRecord.optString("file") != "$treeName.json") {
                return@runCatching false
            }
            val expectedOutput = outputRecord
                ?.optString("sha256")
                ?.takeIf { it.isNotBlank() }
                ?: return@runCatching false
            if (!expectedOutput.equals(
                    DepthArtifactContract.sha256Hex(outputJsonText.toByteArray()),
                    ignoreCase = true,
                )
            ) {
                return@runCatching false
            }
            val metadataRecord = manifest.optJSONObject("metadata")
                ?: return@runCatching false
            if (metadataRecord.optString("file") != "$treeName.json") {
                return@runCatching false
            }
            val metadataExpected = metadataRecord
                ?.optString("sha256")
                ?.takeIf { it.isNotBlank() }
                ?: return@runCatching false
            val metadataText = readTextForResume(safTreeUri,"$METADATA_DIR/$treeName.json")
                ?: return@runCatching false
            if (!metadataExpected.equals(
                    DepthArtifactContract.sha256Hex(metadataText.toByteArray(Charsets.UTF_8)),
                    ignoreCase = true,
                )
            ) {
                return@runCatching false
            }
            val sides = manifest.optJSONArray("sides") ?: return@runCatching false
            if (sides.length() != parsed.sides.size) return@runCatching false
            val seenSides = HashSet<Int>()
            val labelHashes = ArrayList<Pair<Int, String>>()
            val rgbHashes = ArrayList<Pair<Int, String>>()
            for (i in 0 until sides.length()) {
                val side = sides.optJSONObject(i) ?: return@runCatching false
                val sideIndex = side.optInt("sideIndex", -1)
                if (sideIndex < 0 || !seenSides.add(sideIndex)) return@runCatching false
                val parsedSide = parsedBySide[sideIndex] ?: return@runCatching false
                if (CaptureOrigin.fromPersisted(side.optString("captureOrigin")) !=
                    parsedSide.captureOrigin
                ) {
                    return@runCatching false
                }
                if (side.optBoolean("depthRequired") != parsedSide.depthRequired) {
                    return@runCatching false
                }

                val rgb = side.optJSONObject("rgb") ?: return@runCatching false
                val rgbFile = rgb.optString("file")
                val rgbExpected = rgb.optString("sha256")
                if (rgbFile != "${treeName}_${sideIndex + 1}.jpg" ||
                    parsedSide.rgbSha256 == null ||
                    !rgbExpected.equals(parsedSide.rgbSha256, ignoreCase = true)
                ) {
                    return@runCatching false
                }
                val rgbBytes = readBytesForResume(safTreeUri,"$IMAGES_DIR/$rgbFile")
                    ?: return@runCatching false
                if (!rgbExpected.equals(
                        DepthArtifactContract.sha256Hex(rgbBytes),
                        ignoreCase = true,
                    )
                ) {
                    return@runCatching false
                }
                rgbHashes.add(sideIndex to rgbExpected.lowercase())

                val label = side.optJSONObject("label") ?: return@runCatching false
                val labelFile = label.optString("file")
                val labelExpected = label.optString("sha256")
                if (labelFile != "${treeName}_${sideIndex + 1}.txt" ||
                    labelExpected.isBlank()
                ) {
                    return@runCatching false
                }
                val labelBytes = readBytesForResume(safTreeUri,"$OUTPUT_TXT_DIR/$labelFile")
                    ?: return@runCatching false
                if (!labelExpected.equals(
                        DepthArtifactContract.sha256Hex(labelBytes),
                        ignoreCase = true,
                    )
                ) {
                    return@runCatching false
                }
                labelHashes.add(sideIndex to labelExpected.lowercase())

                val depth = side.optJSONObject("depth")
                if (side.optBoolean("depthRequired") && depth == null) {
                    return@runCatching false
                }
                if (parsedSide.captureOrigin == CaptureOrigin.PHONE_CAMERA && depth != null) {
                    return@runCatching false
                }
                if (depth != null) {
                    if (depth.optString("rawFile") != "${treeName}_${sideIndex + 1}.raw" ||
                        depth.optString("jsonFile") != "${treeName}_${sideIndex + 1}.json"
                    ) {
                        return@runCatching false
                    }
                    val raw = readBytesForResume(
                        safTreeUri,
                        "$DEPTH_DIR/${depth.optString("rawFile")}",
                    ) ?: return@runCatching false
                    val depthJson = readBytesForResume(
                        safTreeUri,
                        "$DEPTH_DIR/${depth.optString("jsonFile")}",
                    ) ?: return@runCatching false
                    if (!depth.optString("rawSha256").equals(
                            DepthArtifactContract.sha256Hex(raw),
                            ignoreCase = true,
                        ) ||
                        !depth.optString("jsonSha256").equals(
                            DepthArtifactContract.sha256Hex(depthJson),
                            ignoreCase = true,
                        )
                    ) {
                        return@runCatching false
                    }
                }
            }
            if (seenSides != parsedBySide.keys) return@runCatching false

            val captureSetId = DepthArtifactContract.sha256Hex(
                rgbHashes.sortedBy { it.first }
                    .joinToString("|") { "${it.first}:${it.second}" }
                    .toByteArray(Charsets.UTF_8),
            )
            if (!manifest.optString("captureSetId")
                    .equals(captureSetId, ignoreCase = true)
            ) {
                return@runCatching false
            }
            val annotationRevision = DepthArtifactContract.sha256Hex(
                (
                    expectedOutput.lowercase() + "|" +
                        labelHashes.sortedBy { it.first }
                            .joinToString("|") { it.second }
                    ).toByteArray(Charsets.UTF_8),
            )
            if (!manifest.optString("annotationRevision")
                    .equals(annotationRevision, ignoreCase = true)
            ) {
                return@runCatching false
            }
            true
        }.getOrElse {
            if (it is SafResumeException) throw it
            false
        }
    }

    /** Read block from the metadata sidecar ("blok"), else parse it from the tree name. */
    private fun resolveBlock(
        metaJson: JSONObject?,
        treeName: String,
        datasetType: DatasetType,
    ): String {
        if (metaJson != null) {
            val blok = metaJson.optString("blok").ifBlank { metaJson.optString("block") }
            if (blok.isNotBlank()) return blok
        }
        return deriveBlock(treeName, datasetType)
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
        val stagingDir = runCatching {
            storage.captureStagingDir(UUID.randomUUID().toString())
        }.getOrElse {
            Log.w(TAG, "ingest could not create staging for ${tree.treeName}", it)
            return false
        }
        try {
        val labels = generateSideLabels(tree.sides.size)
        val sides = ArrayList<TreeSide>()
        for (s in tree.sides) {
            val imgUri = copyImageToStaging(
                safTreeUri,
                stagingDir,
                tree.treeName,
                s.sideIndex,
                imageNames,
                s.rgbSha256,
                s.imageWidth,
                s.imageHeight,
            ) ?: run {
                Log.w(TAG, "ingest rejected ${tree.treeName}: side ${s.sideIndex + 1} RGB missing/hash mismatch")
                return false
            }
            // H-04: also copy the depth (.raw + .json) back from SAF so resumed trees keep their
            // depth in later ZIP exports (the exporter reads depth from LOCAL storage only). No-op
            // when the side has no depth in the folder. Runs BEFORE addTree, so the SAF mirror's
            // stale-depth cleanup (H-02) sees the local depth present and won't remove it.
            val restoredDepth = copyDepthToStaging(
                safTreeUri,
                stagingDir,
                tree.treeName,
                s.sideIndex,
            )
            val decision = CaptureIntegrityPolicy.evaluate(
                storedOrigin = s.captureOrigin,
                declaredDepthRequired = s.depthRequired,
                hasAnyDepth = restoredDepth.valid,
                hasValidDepth = restoredDepth.valid,
                hasVerifiedDepthBinding = restoredDepth.contentBound,
                rejectUnverifiedLegacy = false,
            )
            if (decision.error != null) {
                Log.w(
                    TAG,
                    "ingest rejected ${tree.treeName}: side ${s.sideIndex + 1}: " +
                        decision.error,
                )
                return false
            }
            val boxes = s.bboxes
            sides.add(TreeSide(
                sideIndex = s.sideIndex,
                label = labels.getOrElse(s.sideIndex) { "Side ${s.sideIndex + 1}" },
                imageUri = imgUri,
                labelUri = null,
                imageWidth = s.imageWidth,
                imageHeight = s.imageHeight,
                bboxes = boxes,
                originalBboxes = boxes,
                rgbSha256 = imgUri.getQueryParameter("v").orEmpty(),
                captureOrigin = decision.captureOrigin,
                depthRequired = decision.depthRequired,
            ))
        }
        // WS-12/WS-13: carry the CAPTURING device's identity and provenance, not this device's.
        // Re-stamping a resumed package with the resuming tablet's identity would make two
        // physically distinct collections look like one.
        val metadata = TreeMetadata(
            variety = tree.variety,
            block = tree.block,
            treeId = tree.treeId.toString(),
            date = tree.captureDate,
            capturedAtMillis = tree.capturedAtMillis,
            gps = tree.gps,
            operatorName = tree.operatorName,
            identity = tree.identity,
        )
        return runCatching {
            repo.commitTreePackage(
                sessionId = runId,
                treeName = tree.treeName,
                treeId = tree.treeId,
                split = tree.split,
                sides = sides,
                metadata = metadata,
                stagingDir = stagingDir,
                requiredDepthSides = sides
                    .filter { it.depthRequired }
                    .mapTo(mutableSetOf()) { it.sideIndex },
                confirmedLinks = tree.confirmedLinks,
                // Resume is a read-only operation for the selected SAF folder. The imported
                // package is copied into local storage and Room, but its verified remote source
                // must not be deleted and rewritten as if it were a brand-new capture.
                safTreeUri = null,
                // An in-progress capture draft belongs to the NEXT tree, not to this historical
                // package, so it must neither veto nor be consumed by this import.
                consumesCaptureDraft = false,
            )
            true
        }.getOrElse {
            Log.w(TAG, "ingestTree failed for ${tree.treeName}", it)
            false
        }
        } finally {
            if (!storage.deleteCaptureStaging(stagingDir)) {
                Log.w(TAG, "ingest could not clean staging for ${tree.treeName}")
            }
        }
    }

    /** Copy a side image into an uncommitted package; return its eventual canonical URI. */
    private fun copyImageToStaging(
        safTreeUri: Uri,
        stagingDir: File,
        treeName: String,
        sideIndex: Int,
        imageNames: Set<String>,
        expectedRgbSha256: String?,
        expectedWidth: Int,
        expectedHeight: Int,
    ): Uri? {
        val fileName = "${treeName}_${sideIndex + 1}.jpg"
        if (fileName !in imageNames) return null
        val stagedImage = storage.stagedImageFile(stagingDir, treeName, sideIndex)
        val bytes = readBytesForResume(safTreeUri,"$IMAGES_DIR/$fileName") ?: return null
        val actualSha256 = DepthArtifactContract.sha256Hex(bytes)
        if (expectedRgbSha256 != null &&
            !expectedRgbSha256.equals(actualSha256, ignoreCase = true)
        ) {
            Log.w(TAG, "resume RGB checksum mismatch for $fileName")
            return null
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            Log.w(TAG, "resume rejected undecodable RGB $fileName")
            return null
        }
        if ((expectedWidth > 0 && bounds.outWidth != expectedWidth) ||
            (expectedHeight > 0 && bounds.outHeight != expectedHeight)
        ) {
            Log.w(
                TAG,
                "resume RGB dimensions mismatch for $fileName: " +
                    "${bounds.outWidth}x${bounds.outHeight} != ${expectedWidth}x$expectedHeight",
            )
            return null
        }
        runCatching { storage.writeBytes(stagedImage, bytes) }.getOrElse { return null }
        return storage.versionedImageUri(storage.imageFile(treeName, sideIndex), bytes)
    }

    /**
     * H-04: restore one complete, validated depth pair from SAF. Independent raw/json copies are
     * forbidden: an interrupted mirror or an older app may have left only one half behind.
     */
    private fun copyDepthToStaging(
        safTreeUri: Uri,
        stagingDir: File,
        treeName: String,
        sideIndex: Int,
    ): RestoredDepth {
        val stem = "${treeName}_${sideIndex + 1}"
        val raw = readBytesForResume(safTreeUri,"$DEPTH_DIR/$stem.raw")
        val metadata = readTextForResume(safTreeUri,"$DEPTH_DIR/$stem.json")
        if (raw == null && metadata == null) return RestoredDepth()
        if (raw == null || metadata == null) {
            Log.w(TAG, "resume skipped incomplete depth pair for $stem")
            return RestoredDepth()
        }
        val image = storage.stagedImageFile(stagingDir, treeName, sideIndex)
        val validationError = runCatching {
            DepthArtifactContract.validationError(
                raw.size.toLong(),
                metadata,
                actualRawSha256 = DepthArtifactContract.sha256Hex(raw),
                actualRgbSha256 = if (image.isFile) DepthArtifactContract.sha256Hex(image) else null,
            )
        }.getOrElse { "checksum failed: ${it.message}" }
        validationError?.let { error ->
            Log.w(TAG, "resume skipped invalid depth pair for $stem: $error")
            return RestoredDepth()
        }
        val staged = runCatching {
            storage.writeBytes(
                storage.stagedDepthRawFile(stagingDir, treeName, sideIndex),
                raw,
            )
            storage.writeText(
                storage.stagedDepthJsonFile(stagingDir, treeName, sideIndex),
                metadata,
            )
        }
            .onFailure {
                Log.w(TAG, "resume depth pair staging failed for $stem", it)
            }
            .isSuccess
        return if (staged) {
            RestoredDepth(
                valid = true,
                contentBound = DepthArtifactContract.hasContentBindings(metadata),
            )
        } else {
            RestoredDepth()
        }
    }

    private fun deriveVariety(treeName: String): String =
        Regex("^([A-Za-z0-9]+)_").find(treeName)?.groupValues?.get(1)?.uppercase() ?: "UNKNOWN"

    /** Block = the middle token of {VARIETY}_{BLOCK}_{ID}; empty when no clear middle. */
    private fun deriveBlock(treeName: String, datasetType: DatasetType): String {
        val parts = treeName.split('_')
        if (parts.size < 3) return ""
        // A bunch-weight sample with no block is {VARIETY}_BW_{ID}: segment 1 is the dataset
        // marker, not a block. Exact rather than a guess, because CaptureSetPolicy.blockError
        // reserves the marker as a block token, so no weight sample can carry the block "BW".
        // Only reached when the metadata sidecar carries no "blok".
        if (datasetType == DatasetType.BUNCH_WEIGHT &&
            parts[1] == CaptureSetPolicy.WEIGHT_NAME_MARKER
        ) return ""
        return parts[1]
    }

    /** Tree id = trailing numeric token, else 0. */
    private fun parseTreeId(treeName: String): Int {
        val last = treeName.substringAfterLast('_')
        return last.toIntOrNull() ?: last.filter { it.isDigit() }.toIntOrNull() ?: 0
    }
}
