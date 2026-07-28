package dev.sawitulm.palmannotate.data.export

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.sawitulm.palmannotate.data.db.TreeDao
import dev.sawitulm.palmannotate.data.db.TreeEntity
import dev.sawitulm.palmannotate.data.storage.AndroidStorageManager
import dev.sawitulm.palmannotate.data.storage.ArtifactCoordinator
import dev.sawitulm.palmannotate.data.storage.ArtifactIdentityPolicy
import dev.sawitulm.palmannotate.data.storage.CaptureIntegrityPolicy
import dev.sawitulm.palmannotate.data.storage.DepthArtifactContract
import dev.sawitulm.palmannotate.data.storage.ExportFolderRepository
import dev.sawitulm.palmannotate.data.storage.SafMirrorStore
import dev.sawitulm.palmannotate.data.storage.SaveResult
import dev.sawitulm.palmannotate.data.storage.SessionRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bundles a session's (or the whole dataset's) captured files into a single ZIP for download.
 *
 * ## Why this exists
 * Captured data lives in app-private external storage (`getExternalFilesDir/PalmAnnotate/`),
 * which is wiped by "Clear App Data" / uninstall. A user-triggered export to the SAF folder (or
 * a shared FileProvider file) is the safety net that gets the dataset OUT before any data-clear.
 *
 * ## Why it can't OOM (the whole point)
 * Everything is **streamed**: each source file is copied into the [ZipOutputStream] with a fixed
 * 32 KB buffer, and the zip is written straight to the destination [OutputStream]. Nothing — not a
 * file, not the zip — is ever held in memory. A 10 GB / 250-tree dataset uses the same RAM as one
 * file. Compression is disabled ([Deflater.NO_COMPRESSION]) because JPEG/raw-depth don't shrink;
 * skipping deflate saves CPU and time.
 *
 * The zip's internal layout matches the curated training dataset (`example_dataset`) plus depth:
 * `images/`, `labels/`, `json/`, `depth/`, `metadata/` — flat, with `{tree}_{n}` filenames.
 */
@Singleton
class DatasetZipExporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val storage: AndroidStorageManager,
    private val saf: SafMirrorStore,
    private val treeDao: TreeDao,
    private val exportFolder: ExportFolderRepository,
    private val repo: SessionRepository,
    private val artifactCoordinator: ArtifactCoordinator,
) {

    companion object {
        private const val TAG = "ZipExport"
        private const val BUFFER = 32 * 1024
    }

    /** Progress tick: [done]/[total] files written; [currentTree] is the tree being zipped. */
    data class Progress(val done: Int, val total: Int, val currentTree: String)

    sealed class Outcome {
        /** Zip written; [uri] is shareable (SAF content uri or FileProvider uri). */
        data class Success(val uri: Uri, val fileName: String) : Outcome()
        /** No files found to export (empty/blank session). */
        object Empty : Outcome()
        /** User cancelled; the partial zip was removed. */
        object Cancelled : Outcome()
        data class Failed(val message: String) : Outcome()
    }

    /** One file to place in the zip: [source] on disk → [zipPath] inside the archive. */
    private data class FileEntry(val source: File, val zipPath: String)

    /** Destination abstraction used only after the local snapshot is complete. */
    private class Destination(val shareUri: Uri, val out: OutputStream, val cleanup: () -> Unit)

    private sealed class PreparedExport {
        data class Ready(val zipFile: File, val fileName: String) : PreparedExport()
        data class Finished(val outcome: Outcome) : PreparedExport()
    }

    /** Export a single run/session. Local snapshotting is exclusive; SAF publication is not. */
    suspend fun exportRun(
        sessionId: String,
        onProgress: (Progress) -> Unit,
        isCancelled: () -> Boolean,
    ): Outcome {
        val prepared = artifactCoordinator.withExclusiveAccess {
            val trees = treeDao.getBySession(sessionId)
            val base = trees.firstOrNull()
                ?.let { sanitize("${it.variety}_${it.block}") }
                ?.takeIf { it.isNotBlank() }
                ?: "session"
            exportLocked(trees, "${base}_${timestamp()}", onProgress, isCancelled)
        }
        return publishPrepared(prepared, isCancelled)
    }

    /** Export every tree across all sessions into one zip. Local snapshotting is exclusive. */
    suspend fun exportAll(
        onProgress: (Progress) -> Unit,
        isCancelled: () -> Boolean,
    ): Outcome {
        val prepared = artifactCoordinator.withExclusiveAccess {
            val trees = treeDao.getAllOnce()
            exportLocked(trees, "PalmAnnotate_all_${timestamp()}", onProgress, isCancelled)
        }
        return publishPrepared(prepared, isCancelled)
    }

    // ─── Core ────────────────────────────────────────────────────────────────────

    private suspend fun exportLocked(
        trees: List<TreeEntity>,
        zipBaseName: String,
        onProgress: (Progress) -> Unit,
        isCancelled: () -> Boolean,
    ): PreparedExport {
        repo.recoverLocalArtifactsWhileExclusive()
        // Rebuild every mutable annotation artifact from Room, bind it to the current RGB hashes,
        // and reject the whole export if any committed tree is incomplete or cross-generation.
        for (t in trees) {
            materializeAndValidateTree(t)?.let { error ->
                return PreparedExport.Finished(Outcome.Failed("${t.treeName}: $error"))
            }
        }

        // Build the full (source, zipPath, tree) work list up front so total is exact.
        data class Item(val entry: FileEntry, val treeName: String)
        val items = ArrayList<Item>()
        for (t in trees) {
            val committedSideIndices = repo.loadActiveSessionWhileExclusive(t.treeKey)
                ?.sides
                ?.mapTo(mutableSetOf()) { it.sideIndex }
                ?: return PreparedExport.Finished(Outcome.Failed("${t.treeName}: committed side set disappeared"))
            for (e in entriesForTree(t.treeName, t.sideCount, committedSideIndices)) {
                items.add(Item(e, t.treeName))
            }
        }
        if (items.isEmpty()) return PreparedExport.Finished(Outcome.Empty)

        val fileName = "$zipBaseName.zip"
        val snapshotFile = try {
            File.createTempFile("PalmAnnotate-export-", ".zip", storage.exportsDir)
        } catch (e: Exception) {
            Log.w(TAG, "local export snapshot could not be created", e)
            return PreparedExport.Finished(Outcome.Failed(e.message ?: "Export failed"))
        }

        val total = items.size
        var done = 0
        var cancelled = false
        try {
            withContext(Dispatchers.IO) {
                ZipOutputStream(BufferedOutputStream(FileOutputStream(snapshotFile))).use { zip ->
                    zip.setLevel(Deflater.NO_COMPRESSION)
                    for (item in items) {
                        if (isCancelled()) { cancelled = true; break }
                        zip.putNextEntry(ZipEntry(item.entry.zipPath))
                        FileInputStream(item.entry.source).use { it.copyTo(zip, BUFFER) }
                        zip.closeEntry()
                        done++
                        onProgress(Progress(done, total, item.treeName))
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "zip snapshot failed", e)
            snapshotFile.delete()
            return PreparedExport.Finished(Outcome.Failed(e.message ?: "Export failed"))
        }
        if (cancelled) {
            snapshotFile.delete()
            return PreparedExport.Finished(Outcome.Cancelled)
        }
        return PreparedExport.Ready(snapshotFile, fileName)
    }

    /** Publish a coherent local snapshot after releasing ArtifactCoordinator. */
    private suspend fun publishPrepared(
        prepared: PreparedExport,
        isCancelled: () -> Boolean,
    ): Outcome = when (prepared) {
        is PreparedExport.Finished -> prepared.outcome
        is PreparedExport.Ready -> publishSnapshot(prepared, isCancelled)
    }

    private suspend fun publishSnapshot(
        prepared: PreparedExport.Ready,
        isCancelled: () -> Boolean,
    ): Outcome = withContext(Dispatchers.IO) {
        val dest = try {
            val safUri = exportFolder.folderUri.first()
            openDestination(safUri, prepared.fileName)
        } catch (e: Exception) {
            Log.w(TAG, "export destination unavailable", e)
            prepared.zipFile.delete()
            return@withContext Outcome.Failed(e.message ?: "Cannot create export file")
        }
        if (dest == null) {
            prepared.zipFile.delete()
            return@withContext Outcome.Failed("Cannot create export file")
        }

        var cancelled = false
        var copied = false
        try {
            FileInputStream(prepared.zipFile).use { input ->
                dest.out.use { output ->
                    val buffer = ByteArray(BUFFER)
                    while (true) {
                        if (isCancelled()) {
                            cancelled = true
                            break
                        }
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                    }
                    if (!cancelled) {
                        output.flush()
                        copied = true
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "export snapshot publication failed", e)
            dest.cleanup()
            prepared.zipFile.delete()
            return@withContext Outcome.Failed(e.message ?: "Export failed")
        }

        prepared.zipFile.delete()
        if (cancelled || !copied) {
            dest.cleanup()
            return@withContext Outcome.Cancelled
        }
        Outcome.Success(dest.shareUri, prepared.fileName)
    }

    /**
     * Treat one Room tree as a committed package. TXT/Output JSON are mutable projections of the
     * annotation DB, so they are regenerated together. The manifest is written last and binds
     * their hashes to the immutable RGB/depth capture.
     */
    private suspend fun materializeAndValidateTree(tree: TreeEntity): String? {
        return try {
            ArtifactIdentityPolicy.treeNameError(tree.treeName)?.let {
                return "unsafe tree identity: $it"
            }
            val session = repo.loadActiveSessionWhileExclusive(tree.treeKey)
                ?: return "committed tree could not be loaded"
            val sideIndices = session.sides.map { it.sideIndex }
            if (sideIndices.isEmpty() ||
                sideIndices.distinct().size != sideIndices.size ||
                sideIndices.any { it !in 0 until tree.sideCount }
            ) {
                return "side set is empty, duplicated, or outside the declared range"
            }

            val verifiedSides = session.sides.sortedBy { it.sideIndex }.map { side ->
                val image = storage.imageFile(tree.treeName, side.sideIndex)
                if (!image.isFile || image.length() <= 0L) {
                    return "side ${side.sideIndex + 1} RGB is missing"
                }
                val actualRgbSha256 = DepthArtifactContract.sha256Hex(image)
                val expectedRgbSha256 = side.rgbSha256.takeIf { it.isNotBlank() }
                    ?: side.imageUri?.getQueryParameter("v")
                if (expectedRgbSha256 != null &&
                    !expectedRgbSha256.equals(actualRgbSha256, ignoreCase = true)
                ) {
                    return "side ${side.sideIndex + 1} RGB checksum mismatch"
                }

                val raw = storage.depthRawFile(tree.treeName, side.sideIndex)
                val json = storage.depthJsonFile(tree.treeName, side.sideIndex)
                val hasAnyDepth = raw.exists() || json.exists()
                val hasValidDepth = storage.hasValidDepthPair(tree.treeName, side.sideIndex)
                val hasVerifiedDepthBinding =
                    hasValidDepth &&
                        storage.depthMetadataHasContentBindings(tree.treeName, side.sideIndex)
                val decision = CaptureIntegrityPolicy.evaluate(
                    storedOrigin = side.captureOrigin,
                    declaredDepthRequired = side.depthRequired,
                    hasAnyDepth = hasAnyDepth,
                    hasValidDepth = hasValidDepth,
                    hasVerifiedDepthBinding = hasVerifiedDepthBinding,
                    rejectUnverifiedLegacy = true,
                )
                decision.error?.let {
                    return "side ${side.sideIndex + 1}: $it"
                }
                side.copy(
                    rgbSha256 = actualRgbSha256,
                    captureOrigin = decision.captureOrigin,
                    depthRequired = decision.depthRequired,
                )
            }
            val verifiedSession = session.copy(sides = verifiedSides)

            // Rebuild projections through the same journaled revision publisher used by annotation
            // saves. ZIP creation must never invalidate a valid manifest before replacement.
            // exportRun/exportAll already hold ArtifactCoordinator. Calling the public saveSession
            // here would try to acquire the same non-reentrant Mutex and deadlock before the ZIP
            // stream starts.
            when (val result = repo.saveSessionWhileExclusive(verifiedSession)) {
                is SaveResult.Success -> null
                is SaveResult.Conflict -> "tree changed during ZIP preparation (current revision r${result.actualRevision})"
                is SaveResult.Failure -> result.message
            }
        } catch (e: Exception) {
            Log.w(TAG, "package materialization failed for ${tree.treeName}", e)
            e.message ?: "package materialization failed"
        }
    }

    /** Collect the existing local files for one tree, mapped to their zip-internal paths.
     *  Depth is all-or-nothing; invalid/partial pairs were already rejected by preflight. */
    private fun entriesForTree(
        treeName: String,
        sideCount: Int,
        committedSideIndices: Set<Int>,
    ): List<FileEntry> {
        val validDepthSides = HashSet<Int>()
        for (sideIndex in committedSideIndices.sorted()) {
            if (storage.hasValidDepthPair(treeName, sideIndex)) {
                validDepthSides.add(sideIndex)
            } else {
                val hasRaw = storage.depthRawFile(treeName, sideIndex).exists()
                val hasJson = storage.depthJsonFile(treeName, sideIndex).exists()
                check(!hasRaw && !hasJson) {
                    "Preflight allowed invalid depth for ${treeName}_${sideIndex + 1}"
                }
            }
        }
        return DatasetZipLayout.zipEntriesFor(treeName, sideCount).mapNotNull { spec ->
            if (spec.sideIndex != null && spec.sideIndex !in committedSideIndices) {
                return@mapNotNull null
            }
            if ((spec.kind == FileKind.DEPTH_RAW || spec.kind == FileKind.DEPTH_JSON) &&
                spec.sideIndex !in validDepthSides
            ) {
                return@mapNotNull null
            }
            val f = sourceFileFor(spec, treeName)
            if (f.exists() && f.isFile) FileEntry(f, spec.zipPath) else null
        }
    }

    private fun sourceFileFor(spec: ZipPathSpec, treeName: String): File = when (spec.kind) {
        FileKind.IMAGE -> storage.imageFile(treeName, spec.sideIndex!!)
        FileKind.LABEL -> storage.labelFile(treeName, spec.sideIndex!!)
        FileKind.DEPTH_RAW -> storage.depthRawFile(treeName, spec.sideIndex!!)
        FileKind.DEPTH_JSON -> storage.depthJsonFile(treeName, spec.sideIndex!!)
        FileKind.OUTPUT_JSON -> storage.outputJsonFile(treeName)
        FileKind.METADATA -> storage.metadataFile(treeName)
        FileKind.MANIFEST -> storage.manifestFile(treeName)
    }

    /**
     * Resolve a streaming destination: the configured SAF folder's `exports/<name>` when set,
     * else a local `PalmAnnotate/exports/<name>` shared via the existing FileProvider. Both return
     * a shareable uri + an [OutputStream] the caller streams the zip into.
     */
    private fun openDestination(safUri: Uri?, fileName: String): Destination? {
        if (safUri != null) {
            val uri = saf.createFileForStreaming(safUri, "exports/$fileName", "application/zip")
            val os = uri?.let { runCatching { context.contentResolver.openOutputStream(it) }.getOrNull() }
            if (uri != null && os != null) {
                return Destination(uri, os) { runCatching { saf.deletePath(safUri, "exports/$fileName") } }
            }
            Log.w(TAG, "SAF destination unavailable, falling back to local exports/")
        }
        return try {
            val file = File(storage.exportsDir, fileName)
            if (file.exists()) file.delete()
            val os = FileOutputStream(file)
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            Destination(uri, os) { runCatching { file.delete() } }
        } catch (e: Exception) {
            Log.w(TAG, "local destination failed", e)
            null
        }
    }

    private fun timestamp(): String =
        SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())

    private fun sanitize(raw: String): String = DatasetZipLayout.sanitize(raw)
}

/** Kind of source file, so the exporter can resolve a [ZipPathSpec] back to its on-disk [File]. */
internal enum class FileKind {
    IMAGE,
    LABEL,
    DEPTH_RAW,
    DEPTH_JSON,
    OUTPUT_JSON,
    METADATA,
    MANIFEST,
}

/** One candidate zip entry: its [kind], the owning [sideIndex] (null for tree-level files), and
 *  the path it occupies inside the archive. */
internal data class ZipPathSpec(val kind: FileKind, val sideIndex: Int?, val zipPath: String)

/**
 * Pure (I/O-free) zip-naming + layout, extracted so it can be unit-tested without a device.
 * The archive layout mirrors the curated training dataset (`example_dataset`) plus depth.
 */
internal object DatasetZipLayout {

    /** Strip everything that isn't a safe filename char, then trim stray underscores. */
    fun sanitize(raw: String): String = raw.replace(Regex("[^A-Za-z0-9_-]"), "").trim('_')

    /** Candidate zip entries for a tree, independent of which files exist on disk. */
    fun zipEntriesFor(treeName: String, sideCount: Int): List<ZipPathSpec> {
        val list = ArrayList<ZipPathSpec>()
        for (i in 0 until sideCount) {
            val n = i + 1
            list.add(ZipPathSpec(FileKind.IMAGE, i, "images/${treeName}_$n.jpg"))
            list.add(ZipPathSpec(FileKind.LABEL, i, "labels/${treeName}_$n.txt"))
            list.add(ZipPathSpec(FileKind.DEPTH_RAW, i, "depth/${treeName}_$n.raw"))
            list.add(ZipPathSpec(FileKind.DEPTH_JSON, i, "depth/${treeName}_$n.json"))
        }
        list.add(ZipPathSpec(FileKind.OUTPUT_JSON, null, "json/$treeName.json"))
        list.add(ZipPathSpec(FileKind.METADATA, null, "metadata/$treeName.json"))
        list.add(ZipPathSpec(FileKind.MANIFEST, null, "manifests/$treeName.json"))
        return list
    }
}
