package dev.sawitulm.palmannotate.data.export

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.sawitulm.palmannotate.data.db.TreeDao
import dev.sawitulm.palmannotate.data.db.TreeEntity
import dev.sawitulm.palmannotate.data.storage.AndroidStorageManager
import dev.sawitulm.palmannotate.data.storage.ExportFolderRepository
import dev.sawitulm.palmannotate.data.storage.SafMirrorStore
import dev.sawitulm.palmannotate.data.storage.SessionRepository
import kotlinx.coroutines.flow.first
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

    /** Destination abstraction so SAF and FileProvider-fallback share the same zip loop. */
    private class Destination(val shareUri: Uri, val out: OutputStream, val cleanup: () -> Unit)

    /** Export a single run/session. Runs on the IO dispatcher. */
    suspend fun exportRun(
        sessionId: String,
        onProgress: (Progress) -> Unit,
        isCancelled: () -> Boolean,
    ): Outcome {
        val trees = treeDao.getBySession(sessionId)
        val base = trees.firstOrNull()?.let { sanitize("${it.variety}_${it.block}") }?.takeIf { it.isNotBlank() }
            ?: "session"
        return export(trees, "${base}_${timestamp()}", onProgress, isCancelled)
    }

    /** Export every tree across all sessions into one zip. Runs on the IO dispatcher. */
    suspend fun exportAll(
        onProgress: (Progress) -> Unit,
        isCancelled: () -> Boolean,
    ): Outcome {
        val trees = treeDao.getAllOnce()
        return export(trees, "PalmAnnotate_all_${timestamp()}", onProgress, isCancelled)
    }

    // ─── Core ────────────────────────────────────────────────────────────────────

    private suspend fun export(
        trees: List<TreeEntity>,
        zipBaseName: String,
        onProgress: (Progress) -> Unit,
        isCancelled: () -> Boolean,
    ): Outcome {
        // Regenerate every tree's rich Output JSON (v4: images + bunches + _confirmedLinks +
        // summary) straight from the DB BEFORE listing files, so the export always ships a
        // complete, current `json/<tree>.json` — including the operator's cross-side links.
        // Previously the zip only copied whatever the Results screen had written, so a dataset
        // captured + linked but never "computed" exported with NO json/ and the links never left
        // the device. Generating fresh also picks up links added after a tree was marked complete.
        for (t in trees) materializeOutputJson(t)

        // Build the full (source, zipPath, tree) work list up front so total is exact.
        data class Item(val entry: FileEntry, val treeName: String)
        val items = ArrayList<Item>()
        for (t in trees) {
            for (e in entriesForTree(t.treeName, t.sideCount)) items.add(Item(e, t.treeName))
        }
        if (items.isEmpty()) return Outcome.Empty

        val fileName = "$zipBaseName.zip"
        val safUri = exportFolder.folderUri.first()
        val dest = openDestination(safUri, fileName) ?: return Outcome.Failed("Cannot create export file")

        val total = items.size
        var done = 0
        var cancelled = false
        try {
            ZipOutputStream(BufferedOutputStream(dest.out)).use { zip ->
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
        } catch (e: Exception) {
            Log.w(TAG, "zip failed", e)
            dest.cleanup()
            return Outcome.Failed(e.message ?: "Export failed")
        }
        if (cancelled) {
            dest.cleanup()
            return Outcome.Cancelled
        }
        return Outcome.Success(dest.shareUri, fileName)
    }

    /**
     * Rebuild a tree's canonical `json/<tree>.json` from the DB (the source of truth) so the
     * zip's [FileKind.OUTPUT_JSON] entry finds a complete, current file. [ExportManager
     * .generateOutputJson] emits the v4 schema with `_confirmedLinks`; [SessionRepository
     * .loadActiveSession] carries the links straight from the link table. Best-effort: a tree
     * that fails to load is skipped (its other files still export) rather than failing the zip.
     */
    private suspend fun materializeOutputJson(tree: TreeEntity) {
        val session = repo.loadActiveSession(tree.treeKey) ?: return
        runCatching {
            val json = ExportManager.generateOutputJson(session).toString(2)
            storage.writeText(storage.outputJsonFile(tree.treeName), json)
        }.onFailure { Log.w(TAG, "outputJson regen failed for ${tree.treeName}", it) }
    }

    /** Collect the existing local files for one tree, mapped to their zip-internal paths.
     *  The path layout itself is the pure [DatasetZipLayout.zipEntriesFor]; here we resolve each
     *  spec to its on-disk [File] and keep only the ones that actually exist. */
    private fun entriesForTree(treeName: String, sideCount: Int): List<FileEntry> {
        return DatasetZipLayout.zipEntriesFor(treeName, sideCount).mapNotNull { spec ->
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
internal enum class FileKind { IMAGE, LABEL, DEPTH_RAW, DEPTH_JSON, OUTPUT_JSON, METADATA }

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
        return list
    }
}
