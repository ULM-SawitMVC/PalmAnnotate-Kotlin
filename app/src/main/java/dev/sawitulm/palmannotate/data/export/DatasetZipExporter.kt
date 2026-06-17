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

    /** Collect the existing local files for one tree, mapped to their zip-internal paths. */
    private fun entriesForTree(treeName: String, sideCount: Int): List<FileEntry> {
        val list = ArrayList<FileEntry>()
        for (i in 0 until sideCount) {
            val n = i + 1
            addIfExists(list, storage.imageFile(treeName, i), "images/${treeName}_$n.jpg")
            addIfExists(list, storage.labelFile(treeName, i), "labels/${treeName}_$n.txt")
            addIfExists(list, storage.depthRawFile(treeName, i), "depth/${treeName}_$n.raw")
            addIfExists(list, storage.depthJsonFile(treeName, i), "depth/${treeName}_$n.json")
        }
        addIfExists(list, storage.outputJsonFile(treeName), "json/$treeName.json")
        addIfExists(list, storage.metadataFile(treeName), "metadata/$treeName.json")
        return list
    }

    private fun addIfExists(list: MutableList<FileEntry>, f: File, zipPath: String) {
        if (f.exists() && f.isFile) list.add(FileEntry(f, zipPath))
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

    private fun sanitize(raw: String): String =
        raw.replace(Regex("[^A-Za-z0-9_-]"), "").trim('_')
}
