package dev.sawitulm.palmannotate.data.storage

import android.content.Context
import android.net.Uri
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

/**
 * Manages the app-external storage structure for PalmAnnotate.
 *
 * Storage root: /Android/data/dev.sawitulm.palmannotate/files/PalmAnnotate/
 *
 * This is the ONLY location that, on every supported Android version, the app
 * can both write AND read back through image loading without a runtime permission.
 */
class AndroidStorageManager(private val context: Context) {

    companion object {
        private const val TAG = "StorageManager"
        private const val ROOT_DIR = "PalmAnnotate"
    }

    /** Root directory: <app-external>/PalmAnnotate/ */
    val rootDir: File
        get() = File(context.getExternalFilesDir(null), ROOT_DIR).also { it.mkdirs() }

    // ─── Directory structure ──────────────────────────────────────────────────

    val imagesDir get() = File(rootDir, "images/field").also { it.mkdirs() }
    val labelsDir get() = File(rootDir, "labels/field").also { it.mkdirs() }
    val depthDir get() = File(rootDir, "depth/field").also { it.mkdirs() }
    val metadataDir get() = File(rootDir, "metadata").also { it.mkdirs() }
    val manifestsDir get() = File(rootDir, "manifests").also { it.mkdirs() }
    val annotLogDir get() = File(rootDir, "annotlog/field").also { it.mkdirs() }
    val outputJsonDir get() = File(rootDir, "Output JSON").also { it.mkdirs() }
    val outputTxtDir get() = File(rootDir, "Output TXT/field").also { it.mkdirs() }
    val exportsDir get() = File(rootDir, "exports").also { it.mkdirs() }
    val snapshotsDir get() = File(rootDir, "snapshots").also { it.mkdirs() }
    private val captureStagingRoot get() = File(rootDir, ".capture-staging").also { it.mkdirs() }
    private val captureDraftRoot get() = File(rootDir, ".capture-drafts").also { it.mkdirs() }
    private val revisionStagingRoot get() = File(rootDir, ".revision-staging").also { it.mkdirs() }
    private val revisionJournalRoot get() = File(rootDir, ".revision-journal").also { it.mkdirs() }
    private val revisionBackupRoot get() = File(rootDir, ".revision-backup").also { it.mkdirs() }

    // sessions.json index dropped on native (resume is folder-scan based)

    // ─── Image helpers ────────────────────────────────────────────────────────

    fun imageFile(treeName: String, sideIndex: Int): File =
        File(imagesDir, "${treeName}_${sideIndex + 1}.jpg")

    fun imageUri(treeName: String, sideIndex: Int): Uri =
        Uri.fromFile(imageFile(treeName, sideIndex))

    /**
     * Keep the physical dataset filename stable while making the URI identity content-specific.
     *
     * The query parameter is persisted in Room but is not part of the filesystem path. A fresh
     * JPEG written to the same tree/side filename therefore gets a different URI and cannot reuse
     * the previous bitmap from an in-memory image cache.
     */
    fun versionedImageUri(file: File, imageBytes: ByteArray): Uri =
        versionedImageUri(file, DepthArtifactContract.sha256Hex(imageBytes))

    fun versionedImageUri(file: File, imageSha256: String): Uri {
        require(imageSha256.matches(Regex("^[0-9a-fA-F]{64}$"))) {
            "Invalid RGB content identity"
        }
        return Uri.fromFile(file)
            .buildUpon()
            .appendQueryParameter("v", imageSha256.lowercase(Locale.US))
            .build()
    }

    /** Private, non-exportable workspace for assembling a complete capture before DB commit. */
    fun captureStagingDir(captureId: String): File {
        val root = captureStagingRoot.canonicalFile
        val target = File(root, captureId).canonicalFile
        if (target.parentFile != root) {
            throw IOException("Invalid capture staging identity")
        }
        if (!target.exists() && !target.mkdirs()) {
            throw IOException("Cannot create capture staging directory")
        }
        return target
    }

    fun stagedImageFile(stagingDir: File, treeName: String, sideIndex: Int): File =
        File(stagingDir, "images/${treeName}_${sideIndex + 1}.jpg")

    fun stagedDepthRawFile(stagingDir: File, treeName: String, sideIndex: Int): File =
        File(stagingDir, "depth/${treeName}_${sideIndex + 1}.raw")

    fun stagedDepthJsonFile(stagingDir: File, treeName: String, sideIndex: Int): File =
        File(stagingDir, "depth/${treeName}_${sideIndex + 1}.json")

    /** Stable, app-owned capture draft directory scoped to one run. */
    fun captureDraftDir(runId: String): File {
        val root = captureDraftRoot.canonicalFile
        val target = File(root, runId).canonicalFile
        if (target.parentFile != root) throw IOException("Invalid capture draft identity")
        if (!target.exists() && !target.mkdirs()) throw IOException("Cannot create capture draft directory")
        return target
    }

    fun captureDraftImageFile(runId: String, sideIndex: Int): File =
        File(captureDraftDir(runId), "side_${sideIndex + 1}.jpg")

    fun captureDraftDepthRawFile(runId: String, sideIndex: Int): File =
        File(captureDraftDir(runId), "side_${sideIndex + 1}.raw")

    fun captureDraftDepthJsonFile(runId: String, sideIndex: Int): File =
        File(captureDraftDir(runId), "side_${sideIndex + 1}.json")

    fun deleteCaptureDraft(runId: String): Boolean {
        val root = captureDraftRoot.canonicalFile
        val target = File(root, runId).canonicalFile
        if (target.parentFile != root) return false
        return !target.exists() || target.deleteRecursively()
    }

    /** Revision-specific staging and journal paths for local publication. */
    fun revisionStagingDir(treeName: String, revision: Long): File {
        ArtifactIdentityPolicy.treeNameError(treeName)?.let { throw IOException("Invalid tree identity: $it") }
        val root = File(revisionStagingRoot, treeName).canonicalFile
        val target = File(root, "r$revision").canonicalFile
        if (target.parentFile != root) throw IOException("Invalid revision identity")
        if (!target.exists() && !target.mkdirs()) throw IOException("Cannot create revision staging directory")
        return target
    }

    fun revisionJournalFile(treeName: String, revision: Long): File =
        File(File(revisionJournalRoot, treeName), "r$revision.json")

    fun revisionBackupDir(treeName: String, revision: Long): File =
        File(File(revisionBackupRoot, treeName), "r$revision")

    data class PendingRevision(
        val treeName: String,
        val revision: Long,
        val entries: List<Pair<File, File>>, // staged -> canonical target
        val journal: File,
    )

    fun pendingRevisions(): List<PendingRevision> {
        val result = ArrayList<PendingRevision>()
        revisionJournalRoot.listFiles()?.filter { it.isDirectory }?.forEach { treeDir ->
            treeDir.listFiles()?.filter { it.isFile && it.extension == "json" }?.forEach { journal ->
                runCatching { readPendingRevision(journal) }.onSuccess { result.add(it) }
                    .onFailure { Log.w(TAG, "Invalid revision journal ${journal.path}", it) }
            }
        }
        return result
    }

    private fun readPendingRevision(journal: File): PendingRevision {
        val json = JSONObject(journal.readText())
        val treeName = json.getString("treeName")
        val revision = json.getLong("revision")
        val entriesJson = json.getJSONArray("entries")
        val entries = (0 until entriesJson.length()).map { index ->
            val entry = entriesJson.getJSONObject(index)
            File(entry.getString("staged")) to File(entry.getString("target"))
        }
        return PendingRevision(treeName, revision, entries, journal)
    }

    private fun writeSyncedFile(file: File, bytes: ByteArray) {
        file.parentFile?.let { if (!it.exists() && !it.mkdirs()) throw IOException("Cannot create ${it.path}") }
        FileOutputStream(file, false).use { out ->
            out.write(bytes); out.flush(); out.fd.sync()
        }
        if (file.length() != bytes.size.toLong()) throw IOException("Short write for ${file.name}")
    }

    private fun copySynced(source: File, target: File) {
        writeSyncedFile(target, source.readBytes())
    }

    private fun replaceFromStage(source: File, target: File) {
        // Empty YOLO/annot-log text is a valid committed projection (for a side with no
        // annotations). Missing is a path/type error; byte length is not a presence check.
        if (!source.isFile) throw IOException("Missing staged artifact ${source.path}")
        val temp = File(target.parentFile, "${target.name}.revision.tmp")
        deleteFile(temp)
        copySynced(source, temp)
        if (target.exists() && !target.delete()) throw IOException("Cannot replace ${target.path}")
        if (!temp.renameTo(target)) throw IOException("Cannot publish ${target.path}")
    }

    /** Persist journal and previous-file backups before Room changes its revision token. */
    @Throws(IOException::class)
    fun prepareRevisionJournal(treeName: String, revision: Long, entries: List<Pair<File, File>>) {
        require(entries.isNotEmpty()) { "Revision has no artifacts" }
        val journal = revisionJournalFile(treeName, revision)
        if (journal.exists()) return
        val backup = revisionBackupDir(treeName, revision)
        val jsonEntries = JSONArray()
        entries.forEachIndexed { index, (staged, target) ->
            if (!staged.canonicalPath.startsWith(revisionStagingRoot.canonicalPath + File.separator)) {
                throw IOException("Revision staging escaped app storage")
            }
            jsonEntries.put(JSONObject().apply {
                put("staged", staged.canonicalPath); put("target", target.canonicalPath)
                put("backup", File(backup, "$index-${target.name}").canonicalPath); put("hadPrevious", target.isFile)
            })
        }
        // Backups are made before the journal is exposed, so a journal never claims a backup that
        // was only partially copied. Orphan backups are harmless and are removed on next startup.
        entries.forEachIndexed { index, (_, target) ->
            if (target.isFile) copySynced(target, File(backup, "$index-${target.name}"))
        }
        writeText(journal, JSONObject().apply {
            put("treeName", treeName); put("revision", revision); put("state", "PUBLISHING")
            put("entries", jsonEntries)
        }.toString())
    }

    /**
     * Publish a complete local annotation revision. The manifest is the final replacement. A
     * journal plus backups let startup restore the previous package or finish the new one after
     * process death; this is deliberately not presented as Room/filesystem atomicity.
     */
    @Throws(IOException::class)
    fun publishRevision(treeName: String, revision: Long, entries: List<Pair<File, File>>): Boolean {
        ArtifactIdentityPolicy.treeNameError(treeName)?.let { throw IOException("Invalid tree identity: $it") }
        require(entries.isNotEmpty()) { "Revision has no artifacts" }
        val journal = revisionJournalFile(treeName, revision)
        val backup = revisionBackupDir(treeName, revision)
        val manifestTarget = manifestFile(treeName).canonicalFile
        try {
            prepareRevisionJournal(treeName, revision, entries)
            val pending = readPendingRevision(journal)
            pending.entries.filter { it.second.canonicalFile != manifestTarget }
                .forEach { replaceFromStage(it.first, it.second) }
            pending.entries.firstOrNull { it.second.canonicalFile == manifestTarget }?.let {
                replaceFromStage(it.first, it.second)
            } ?: throw IOException("Revision manifest is not staged")
            writeText(journal, JSONObject(journal.readText()).apply { put("state", "COMMITTED") }.toString())
            backup.deleteRecursively()
            journal.delete()
            revisionStagingDir(treeName, revision).deleteRecursively()
            return true
        } catch (error: Exception) {
            // Restore the last canonical package immediately, but retain journal/stage so a process
            // restart can retry publication against the already-committed Room revision.
            runCatching { restoreRevisionFiles(readPendingRevision(journal)) }
            if (error is IOException) throw error
            throw IOException("Revision publication failed", error)
        }
    }

    private fun restoreRevisionFiles(pending: PendingRevision) {
        val entries = JSONObject(pending.journal.readText()).getJSONArray("entries")
        for (i in 0 until entries.length()) {
            val entry = entries.getJSONObject(i)
            val target = File(entry.getString("target"))
            val backup = File(entry.getString("backup"))
            if (entry.optBoolean("hadPrevious")) {
                check(backup.isFile) { "Missing revision backup for ${target.path}" }
                replaceFromStage(backup, target)
            } else {
                deleteFile(target)
            }
        }
    }

    fun rollbackRevision(pending: PendingRevision) {
        restoreRevisionFiles(pending)
        pending.journal.delete()
        revisionBackupDir(pending.treeName, pending.revision).deleteRecursively()
        revisionStagingDir(pending.treeName, pending.revision).deleteRecursively()
    }

    /**
     * Remove all unpublished revision state for a tree before deleting its Room row. This is
     * deliberately separate from [deleteTree]: a pending journal can otherwise resurrect an
     * intentionally deleted tree during the next startup recovery.
     */
    fun discardPendingRevisions(treeName: String): Boolean {
        ArtifactIdentityPolicy.treeNameError(treeName)?.let { return false }
        val journalDir = File(revisionJournalRoot, treeName)
        val backupDir = File(revisionBackupRoot, treeName)
        val stagingDir = File(revisionStagingRoot, treeName)
        return (!journalDir.exists() || journalDir.deleteRecursively()) &&
            (!backupDir.exists() || backupDir.deleteRecursively()) &&
            (!stagingDir.exists() || stagingDir.deleteRecursively())
    }

    /** Remove a draft directory only after its Room tree commit has succeeded. */
    fun discardUncommittedDraft(runId: String): Boolean = deleteCaptureDraft(runId)

    /**
     * Validate a staged capture as one package, then publish its files to the canonical working
     * paths. The Room tree row is written only after this returns; therefore a crash or exception
     * during publication leaves uncommitted orphan files, never an exportable mixed tree.
     */
    @Throws(IOException::class)
    fun publishCapturePackage(
        stagingDir: File,
        treeName: String,
        sideIndices: List<Int>,
        requiredDepthSides: Set<Int>,
    ) {
        val root = captureStagingRoot.canonicalFile
        if (stagingDir.canonicalFile.parentFile != root) {
            throw IOException("Capture package is outside the staging root")
        }
        val uniqueSides = sideIndices.distinct().sorted()
        if (uniqueSides.isEmpty()) throw IOException("Capture package has no RGB sides")

        for (sideIndex in uniqueSides) {
            val image = stagedImageFile(stagingDir, treeName, sideIndex)
            if (!image.isFile || image.length() <= 0L) {
                throw IOException("Side ${sideIndex + 1}: staged RGB is missing")
            }
            val raw = stagedDepthRawFile(stagingDir, treeName, sideIndex)
            val json = stagedDepthJsonFile(stagingDir, treeName, sideIndex)
            if (raw.isFile != json.isFile) {
                throw IOException("Side ${sideIndex + 1}: staged depth pair is incomplete")
            }
            if (sideIndex in requiredDepthSides && (!raw.isFile || !json.isFile)) {
                throw IOException("Side ${sideIndex + 1}: required Orbbec depth is missing")
            }
            if (raw.isFile) {
                val metadata = runCatching { json.readText() }
                    .getOrElse { throw IOException("Side ${sideIndex + 1}: depth JSON unreadable", it) }
                DepthArtifactContract.validationError(
                    raw.length(),
                    metadata,
                    actualRawSha256 = DepthArtifactContract.sha256Hex(raw),
                    actualRgbSha256 = DepthArtifactContract.sha256Hex(image),
                )?.let { throw IOException("Side ${sideIndex + 1}: invalid staged depth: $it") }
            }
        }

        for (sideIndex in uniqueSides) {
            val stagedImage = stagedImageFile(stagingDir, treeName, sideIndex)
            writeBytes(imageFile(treeName, sideIndex), stagedImage.readBytes())

            val stagedRaw = stagedDepthRawFile(stagingDir, treeName, sideIndex)
            val stagedJson = stagedDepthJsonFile(stagingDir, treeName, sideIndex)
            if (stagedRaw.isFile && stagedJson.isFile) {
                writeDepthPair(
                    treeName,
                    sideIndex,
                    stagedRaw.readBytes(),
                    stagedJson.readText(),
                )
            } else if (!deleteDepthPair(treeName, sideIndex)) {
                throw IOException("Side ${sideIndex + 1}: stale depth could not be removed")
            }
        }
    }

    fun deleteCaptureStaging(stagingDir: File): Boolean {
        val root = captureStagingRoot.canonicalFile
        val target = stagingDir.canonicalFile
        if (target.parentFile != root) {
            Log.w(TAG, "Refusing to delete non-staging path: ${target.path}")
            return false
        }
        return !target.exists() || target.deleteRecursively()
    }

    // ─── Label helpers ────────────────────────────────────────────────────────

    fun labelFile(treeName: String, sideIndex: Int): File =
        File(labelsDir, "${treeName}_${sideIndex + 1}.txt")

    // ─── Depth helpers ────────────────────────────────────────────────────────

    fun depthRawFile(treeName: String, sideIndex: Int): File =
        File(depthDir, "${treeName}_${sideIndex + 1}.raw")

    fun depthJsonFile(treeName: String, sideIndex: Int): File =
        File(depthDir, "${treeName}_${sideIndex + 1}.json")

    /**
     * Persist raw metric depth and its decoder metadata as one fail-closed artifact.
     *
     * Both halves are first fsynced to temporary files and validated. If replacement of either
     * final file fails, every final/temporary file is removed so no stale or partial pair can be
     * mistaken for the current RGB capture.
    */
    @Throws(IOException::class)
    fun writeDepthPair(treeName: String, sideIndex: Int, rawBytes: ByteArray, metadataText: String) {
        val image = imageFile(treeName, sideIndex)
        val rawSha256 = DepthArtifactContract.sha256Hex(rawBytes)
        val rgbSha256 = if (image.isFile) DepthArtifactContract.sha256Hex(image) else null
        DepthArtifactContract.validationError(
            rawBytes.size.toLong(),
            metadataText,
            actualRawSha256 = rawSha256,
            actualRgbSha256 = rgbSha256,
        )?.let {
            throw IOException("Invalid depth artifact: $it")
        }

        val rawFile = depthRawFile(treeName, sideIndex)
        val jsonFile = depthJsonFile(treeName, sideIndex)
        val rawTemp = File(rawFile.parentFile, "${rawFile.name}.tmp")
        val jsonTemp = File(jsonFile.parentFile, "${jsonFile.name}.tmp")
        val metadataBytes = metadataText.toByteArray(Charsets.UTF_8)

        fun writeSynced(file: File, bytes: ByteArray) {
            FileOutputStream(file, false).use { out ->
                out.write(bytes)
                out.flush()
                out.fd.sync()
            }
            if (file.length() != bytes.size.toLong()) {
                throw IOException("Short write for ${file.name}: ${file.length()} != ${bytes.size}")
            }
        }

        try {
            rawFile.parentFile?.let { parent ->
                if (!parent.exists() && !parent.mkdirs()) {
                    throw IOException("Cannot create depth directory: ${parent.path}")
                }
            }
            deleteFile(rawTemp)
            deleteFile(jsonTemp)
            writeSynced(rawTemp, rawBytes)
            writeSynced(jsonTemp, metadataBytes)

            if (!deleteDepthPair(treeName, sideIndex)) {
                throw IOException("Cannot replace existing depth pair")
            }
            if (!rawTemp.renameTo(rawFile)) throw IOException("Cannot publish ${rawFile.name}")
            if (!jsonTemp.renameTo(jsonFile)) throw IOException("Cannot publish ${jsonFile.name}")

            DepthArtifactContract.validationError(
                rawFile.length(),
                jsonFile.readText(),
                actualRawSha256 = DepthArtifactContract.sha256Hex(rawFile),
                actualRgbSha256 = if (image.isFile) DepthArtifactContract.sha256Hex(image) else null,
            )?.let {
                throw IOException("Published depth pair failed validation: $it")
            }
        } catch (e: Exception) {
            // Fail closed. A missing pair is visible to QA; a half-old pair silently poisons data.
            deleteFile(rawFile)
            deleteFile(jsonFile)
            throw if (e is IOException) e else IOException("Depth pair write failed", e)
        } finally {
            deleteFile(rawTemp)
            deleteFile(jsonTemp)
        }
    }

    /** Delete both files without short-circuiting. */
    fun deleteDepthPair(treeName: String, sideIndex: Int): Boolean {
        val rawDeleted = deleteFile(depthRawFile(treeName, sideIndex))
        val jsonDeleted = deleteFile(depthJsonFile(treeName, sideIndex))
        return rawDeleted && jsonDeleted
    }

    /** True only when both files exist and their decoder contract is internally consistent. */
    fun hasValidDepthPair(treeName: String, sideIndex: Int): Boolean {
        val raw = depthRawFile(treeName, sideIndex)
        val json = depthJsonFile(treeName, sideIndex)
        if (!raw.isFile || !json.isFile) return false
        val metadata = readText(json) ?: return false
        val image = imageFile(treeName, sideIndex)
        return try {
            DepthArtifactContract.validationError(
                raw.length(),
                metadata,
                actualRawSha256 = DepthArtifactContract.sha256Hex(raw),
                actualRgbSha256 = if (image.isFile) DepthArtifactContract.sha256Hex(image) else null,
            ) == null
        } catch (e: Exception) {
            Log.w(TAG, "Failed to checksum depth pair for ${treeName}_${sideIndex + 1}", e)
            false
        }
    }

    /**
     * Binding-field check only. Combine with [hasValidDepthPair]; keeping this separate avoids
     * hashing the multi-megabyte raw/RGB files a second time.
     */
    fun depthMetadataHasContentBindings(treeName: String, sideIndex: Int): Boolean {
        val metadata = readText(depthJsonFile(treeName, sideIndex)) ?: return false
        return DepthArtifactContract.hasContentBindings(metadata)
    }

    // ─── Metadata helpers ─────────────────────────────────────────────────────

    fun metadataFile(treeName: String): File =
        File(metadataDir, "${treeName}.json")

    fun manifestFile(treeName: String): File =
        File(manifestsDir, "${treeName}.json")

    // ─── Output helpers ───────────────────────────────────────────────────────

    fun outputJsonFile(treeName: String): File =
        File(outputJsonDir, "${treeName}.json")

    fun outputTxtFile(treeName: String, sideIndex: Int): File =
        File(outputTxtDir, "${treeName}_${sideIndex + 1}.txt")

    // ─── Annot-log helpers ────────────────────────────────────────────────────

    fun annotLogFile(treeName: String, sideIndex: Int): File =
        File(annotLogDir, "${treeName}_${sideIndex + 1}.json")

    // ─── Generic file operations ──────────────────────────────────────────────

    fun writeText(file: File, text: String) {
        writeBytes(file, text.toByteArray(Charsets.UTF_8))
    }

    fun writeBytes(file: File, bytes: ByteArray) {
        val parent = file.parentFile
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw IOException("Cannot create directory: ${parent.path}")
        }
        val temp = File(parent, "${file.name}.tmp")
        try {
            deleteFile(temp)
            FileOutputStream(temp, false).use { out ->
                out.write(bytes)
                out.flush()
                out.fd.sync()
            }
            if (temp.length() != bytes.size.toLong()) {
                throw IOException("Short write for ${file.name}: ${temp.length()} != ${bytes.size}")
            }
            if (file.exists() && !file.delete()) throw IOException("Cannot replace ${file.name}")
            if (!temp.renameTo(file)) throw IOException("Cannot publish ${file.name}")
        } finally {
            deleteFile(temp)
        }
    }

    fun readText(file: File): String? = try {
        if (file.exists()) file.readText() else null
    } catch (e: Exception) {
        Log.w(TAG, "Failed to read ${file.path}", e)
        null
    }

    fun deleteFile(file: File): Boolean {
        return try {
            if (file.exists()) file.delete() else true
        } catch (e: Exception) {
            Log.w(TAG, "Failed to delete ${file.path}", e)
            false
        }
    }

    /**
     * Remove leftovers for a treeName that has no committed Room row. This is deliberately
     * prefix-scoped and extension-checked; it must never touch another tree with a similar name.
     */
    @Throws(IOException::class)
    fun clearUncommittedTreeArtifacts(treeName: String) {
        val sidePattern = Regex("^${Regex.escape(treeName)}_[0-9]+\\.[A-Za-z0-9]+$")
        val sideDirs = listOf(imagesDir, labelsDir, depthDir, annotLogDir, outputTxtDir)
        val targets = ArrayList<File>()
        for (dir in sideDirs) {
            dir.listFiles()?.filterTo(targets) { it.isFile && sidePattern.matches(it.name) }
        }
        targets.add(metadataFile(treeName))
        targets.add(manifestFile(treeName))
        targets.add(outputJsonFile(treeName))
        for (file in targets.distinctBy { it.absolutePath }) {
            if (!deleteFile(file)) {
                throw IOException("Cannot remove stale uncommitted artifact: ${file.path}")
            }
        }
    }

    /**
     * Delete all files related to a tree (images, labels, depth, metadata,
     * outputs, annot-logs).
     */
    fun deleteTree(treeName: String, sideCount: Int): Int {
        var removed = 0
        for (i in 0 until sideCount) {
            if (deleteFile(imageFile(treeName, i))) removed++
            if (deleteFile(labelFile(treeName, i))) removed++
            if (deleteFile(depthRawFile(treeName, i))) removed++
            if (deleteFile(depthJsonFile(treeName, i))) removed++
            if (deleteFile(annotLogFile(treeName, i))) removed++
            if (deleteFile(outputTxtFile(treeName, i))) removed++
        }
        if (deleteFile(metadataFile(treeName))) removed++
        if (deleteFile(manifestFile(treeName))) removed++
        if (deleteFile(outputJsonFile(treeName))) removed++
        return removed
    }
}
