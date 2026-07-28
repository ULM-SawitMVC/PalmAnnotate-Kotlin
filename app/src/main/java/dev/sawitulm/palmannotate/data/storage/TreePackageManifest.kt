package dev.sawitulm.palmannotate.data.storage

import dev.sawitulm.palmannotate.domain.model.CaptureSetIdentity
import dev.sawitulm.palmannotate.domain.model.TreeSide
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Commit record for one coherent local/remote tree package revision. */
internal object TreePackageManifest {

    fun materialize(
        storage: AndroidStorageManager,
        treeName: String,
        sides: List<TreeSide>,
        identity: CaptureSetIdentity = CaptureSetIdentity.UNKNOWN,
    ): String = materializeAt(storage, treeName, sides, storage.rootDir, identity)

    /** Build a manifest from staged annotation files without touching canonical files. */
    fun materializeAt(
        storage: AndroidStorageManager,
        treeName: String,
        sides: List<TreeSide>,
        artifactRoot: File,
        identity: CaptureSetIdentity = CaptureSetIdentity.UNKNOWN,
    ): String {
        ArtifactIdentityPolicy.treeNameError(treeName)?.let {
            throw IllegalArgumentException("Invalid manifest tree name: $it")
        }
        ArtifactIdentityPolicy.sideSetError(sides.map { it.sideIndex })?.let {
            throw IllegalArgumentException("Invalid manifest side set: $it")
        }
        val labelHashes = ArrayList<Pair<Int, String>>()
        val rgbHashes = ArrayList<Pair<Int, String>>()
        val sideArray = JSONArray()
        for (side in sides.sortedBy { it.sideIndex }) {
            val image = storage.imageFile(treeName, side.sideIndex)
            check(image.isFile && image.length() > 0L) {
                "Side ${side.sideIndex + 1}: RGB is missing while creating manifest"
            }
            val actualRgbSha = DepthArtifactContract.sha256Hex(image)
            check(side.rgbSha256.isNotBlank() && side.rgbSha256.equals(actualRgbSha, ignoreCase = true)) {
                "Side ${side.sideIndex + 1}: RGB identity changed before manifest commit"
            }
            rgbHashes.add(side.sideIndex to actualRgbSha)

            val label = File(artifactRoot, "labels/field/${treeName}_${side.sideIndex + 1}.txt")
            check(label.isFile) { "Side ${side.sideIndex + 1}: annotation TXT is missing" }
            val labelSha = DepthArtifactContract.sha256Hex(label)
            labelHashes.add(side.sideIndex to labelSha)
            val raw = storage.depthRawFile(treeName, side.sideIndex)
            val depthJson = storage.depthJsonFile(treeName, side.sideIndex)
            val hasAnyDepth = raw.exists() || depthJson.exists()
            val hasValidDepth = storage.hasValidDepthPair(treeName, side.sideIndex)
            val hasVerifiedDepthBinding = hasValidDepth && storage.depthMetadataHasContentBindings(treeName, side.sideIndex)
            val decision = CaptureIntegrityPolicy.evaluate(
                storedOrigin = side.captureOrigin,
                declaredDepthRequired = side.depthRequired,
                hasAnyDepth = hasAnyDepth,
                hasValidDepth = hasValidDepth,
                hasVerifiedDepthBinding = hasVerifiedDepthBinding,
                rejectUnverifiedLegacy = false,
            )
            check(decision.error == null) { "Side ${side.sideIndex + 1}: ${decision.error}" }
            check(decision.captureOrigin == side.captureOrigin && decision.depthRequired == side.depthRequired) {
                "Side ${side.sideIndex + 1}: unresolved capture provenance"
            }
            sideArray.put(JSONObject().apply {
                put("sideIndex", side.sideIndex)
                put("captureOrigin", side.captureOrigin.name)
                put("depthRequired", side.depthRequired)
                put("rgb", JSONObject().apply {
                    put("file", "${treeName}_${side.sideIndex + 1}.jpg")
                    put("sha256", actualRgbSha)
                })
                put("label", JSONObject().apply {
                    put("file", "${treeName}_${side.sideIndex + 1}.txt")
                    put("sha256", labelSha)
                })
                if (hasValidDepth) put("depth", JSONObject().apply {
                    put("rawFile", raw.name)
                    put("rawSha256", DepthArtifactContract.sha256Hex(raw))
                    put("jsonFile", depthJson.name)
                    put("jsonSha256", DepthArtifactContract.sha256Hex(depthJson))
                })
            })
        }
        val output = File(artifactRoot, "Output JSON/$treeName.json")
        val metadata = File(artifactRoot, "metadata/$treeName.json")
        check(output.isFile && output.length() > 0L) { "Output JSON is missing" }
        check(metadata.isFile && metadata.length() > 0L) { "Metadata JSON is missing" }
        val outputSha = DepthArtifactContract.sha256Hex(output)
        val metadataSha = DepthArtifactContract.sha256Hex(metadata)
        val captureSetId = DepthArtifactContract.sha256Hex(
            rgbHashes.joinToString("|") { "${it.first}:${it.second}" }.toByteArray(),
        )
        val annotationRevision = DepthArtifactContract.sha256Hex(
            (outputSha + "|" + labelHashes.sortedBy { it.first }.joinToString("|") { it.second }).toByteArray(),
        )
        val text = JSONObject().apply {
            put("schemaVersion", 1)
            put("treeName", treeName)
            // NOTE: this pre-existing `captureSetId` is a CONTENT digest of the RGB set, not the
            // WS-12 cross-device identity. It keeps its name and meaning because
            // FolderResumeImporter.manifestMatchesMirror already validates against it. The
            // cross-device identity lives in the additive `captureSet` object below.
            put("captureSetId", captureSetId)
            put("captureSet", PackageProvenanceCodec.identityJson(identity))
            put("annotationRevision", annotationRevision)
            put("sides", sideArray)
            put("outputJson", JSONObject().apply { put("file", "$treeName.json"); put("sha256", outputSha) })
            put("metadata", JSONObject().apply { put("file", "$treeName.json"); put("sha256", metadataSha) })
        }.toString(2)
        val manifest = File(artifactRoot, "manifests/$treeName.json")
        storage.writeText(manifest, text)
        return text
    }
}
