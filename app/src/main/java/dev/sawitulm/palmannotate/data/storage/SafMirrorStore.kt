package dev.sawitulm.palmannotate.data.storage

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import java.util.concurrent.ConcurrentHashMap

/**
 * Best-effort SAF (Storage Access Framework) mirror.
 *
 * When an export folder is configured, files are ALSO written here so they
 * appear in a user-browsable public folder (Documents / SD card / USB-OTG).
 * The app-external store is ALWAYS the source of truth.
 *
 * ## Why the caches exist (measured)
 *
 * `DocumentFile.findFile(name)` enumerates the WHOLE directory (one
 * ContentResolver query returning every child) and linearly name-matches.
 * On a real device the `Output TXT/field` directory held 228 files; writing a
 * single small label was measured at **~2,000 ms** because the delete-existing
 * `findFile` + `createFile` each re-enumerated all 228 entries — and that cost
 * grows with every tree added. A full 4-side save spent **11.6 s** entirely in
 * SAF (the DB write was 10 ms).
 *
 * Fixes:
 *  1. **Directory-handle cache** — resolve each relative directory's
 *     [DocumentFile] once and reuse it (no re-walk per call).
 *  2. **Child listing cache** — list each directory's children once into a
 *     `name -> DocumentFile` map so `findFile`/`exists`/`delete` are O(1).
 *  3. **Overwrite-in-place** — if the target file already exists, truncate-write
 *     to its existing uri instead of delete+create (skips two enumerations and
 *     the framework's "name (1)" churn).
 *
 * Caches are kept consistent because every create/delete in this app goes
 * through this store; we update the maps on each mutation.
 */
class SafMirrorStore(private val context: Context) {

    companion object {
        private const val TAG = "SafMirror"
    }

    /** Resolved directory DocumentFile per "treeUri|relDir" key. */
    private val dirCache = ConcurrentHashMap<String, DocumentFile>()
    /** Per-directory child map (name -> DocumentFile), keyed by the same "treeUri|relDir". */
    private val childCache = ConcurrentHashMap<String, MutableMap<String, DocumentFile>>()

    private fun dirKey(treeUri: Uri, dirSegments: List<String>) =
        "$treeUri|${dirSegments.joinToString("/")}"

    /**
     * Resolve (and optionally create) the directory at [dirSegments] under [treeUri].
     * Caches the resolved [DocumentFile] so repeat saves don't re-walk the chain.
     */
    private fun resolveDir(treeUri: Uri, dirSegments: List<String>, create: Boolean): DocumentFile? {
        val key = dirKey(treeUri, dirSegments)
        dirCache[key]?.let { if (it.isDirectory) return it else dirCache.remove(key) }

        var node = DocumentFile.fromTreeUri(context, treeUri) ?: return null
        val acc = mutableListOf<String>()
        for (seg in dirSegments) {
            acc.add(seg)
            val subKey = dirKey(treeUri, acc)
            val cached = dirCache[subKey]?.takeIf { it.isDirectory }
            node = cached
                ?: node.findFile(seg)?.takeIf { it.isDirectory }
                ?: (if (create) node.createDirectory(seg) else null)
                ?: return null
            dirCache[subKey] = node
        }
        return node
    }

    /** Child name -> DocumentFile map for [dir], listed once and cached.
     *  [forceRefresh] rebuilds the entry from a fresh `listFiles()` — used by the
     *  import/resume read path where external truth must win over the cache. */
    private fun childrenOf(
        treeUri: Uri,
        dirSegments: List<String>,
        dir: DocumentFile,
        forceRefresh: Boolean = false,
    ): MutableMap<String, DocumentFile> {
        val key = dirKey(treeUri, dirSegments)
        if (!forceRefresh) childCache[key]?.let { return it }
        val map = HashMap<String, DocumentFile>()
        for (f in dir.listFiles()) f.name?.let { map[it] = f }
        childCache[key] = map
        return map
    }

    /**
     * Verify that the given tree URI is still accessible and writable.
     */
    fun isFolderAccessible(treeUri: Uri): Boolean {
        return try {
            val doc = DocumentFile.fromTreeUri(context, treeUri)
            doc != null && doc.isDirectory && doc.canWrite() &&
                context.contentResolver.persistedUriPermissions.any {
                    it.uri == treeUri && it.isWritePermission
                }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Write text data to <treeUri>/<relPath>.
     * Creates intermediate directories as needed. Overwrites existing file.
     *
     * The MIME type is inferred from the file extension. This matters: SAF's
     * `createFile(mime, name)` appends an extension derived from the MIME when it
     * doesn't match the name. Writing a `.txt` with `application/json` produced
     * `name.txt.json` files — and because the delete-existing lookup then missed
     * the real (renamed) file, every save spawned a `name (N).json` duplicate.
     * On the test device that grew `Output TXT/field` to 228 stale files (32
     * `(N)` dupes per label), which is also why SAF got slower with every save.
     */
    fun writeText(treeUri: Uri, relPath: String, text: String): Boolean {
        val mime = when (relPath.substringAfterLast('.', "").lowercase()) {
            "txt" -> "text/plain"
            "json" -> "application/json"
            "csv" -> "text/csv"
            else -> "application/octet-stream"
        }
        return writeBytes(treeUri, relPath, text.toByteArray(Charsets.UTF_8), mime)
    }

    /**
     * Write binary data to <treeUri>/<relPath>.
     *
     * Uses the directory + child caches and overwrites an existing file in place
     * (truncate) rather than delete+create, which is what made repeat saves slow.
     */
    fun writeBytes(treeUri: Uri, relPath: String, data: ByteArray, mimeType: String = "application/octet-stream"): Boolean {
        return try {
            val segments = relPath.split('/').filter { it.isNotBlank() }
            if (segments.isEmpty()) return false
            val fileName = segments.last()
            val dirSegments = segments.dropLast(1)

            val dir = resolveDir(treeUri, dirSegments, create = true) ?: return false
            val children = childrenOf(treeUri, dirSegments, dir)

            val existing = children[fileName]?.takeIf { it.exists() }
            val targetUri = if (existing != null) {
                existing.uri
            } else {
                val created = dir.createFile(mimeType, fileName) ?: return false
                children[fileName] = created
                created.uri
            }
            // "wt" = write+truncate, so overwriting a smaller payload doesn't leave a tail.
            context.contentResolver.openOutputStream(targetUri, "wt")?.use { it.write(data) }
                ?: return false
            true
        } catch (e: Exception) {
            Log.w(TAG, "writeBytes failed for $relPath", e)
            false
        }
    }

    /**
     * Read text from <treeUri>/<relPath>. Returns null if missing/unreadable.
     */
    fun readText(treeUri: Uri, relPath: String): String? {
        val bytes = readBytes(treeUri, relPath) ?: return null
        return bytes.toString(Charsets.UTF_8)
    }

    /**
     * List the file names directly inside <treeUri>/<dirRelPath> matching an
     * optional suffix (case-insensitive, e.g. ".json"). Returns base names only.
     */
    fun listFiles(treeUri: Uri, dirRelPath: String, suffix: String? = null): List<String> {
        return try {
            val dirSegments = dirRelPath.split('/').filter { it.isNotBlank() }
            val dir = resolveDir(treeUri, dirSegments, create = false) ?: return emptyList()
            childrenOf(treeUri, dirSegments, dir, forceRefresh = true).values
                .filter { it.isFile }
                .mapNotNull { it.name }
                .filter { suffix == null || it.endsWith(suffix, ignoreCase = true) }
        } catch (e: Exception) {
            Log.w(TAG, "listFiles failed for $dirRelPath", e)
            emptyList()
        }
    }

    /**
     * Cheap presence check: true if <treeUri>/<relPath> exists as a file.
     * O(1) after the directory has been listed once.
     */
    fun exists(treeUri: Uri, relPath: String): Boolean {
        return try {
            val segments = relPath.split('/').filter { it.isNotBlank() }
            if (segments.isEmpty()) return false
            val dirSegments = segments.dropLast(1)
            val dir = resolveDir(treeUri, dirSegments, create = false) ?: return false
            childrenOf(treeUri, dirSegments, dir)[segments.last()]?.let { it.exists() && it.isFile } == true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Read raw bytes from <treeUri>/<relPath>. Returns null if missing/unreadable.
     */
    fun readBytes(treeUri: Uri, relPath: String): ByteArray? {
        return try {
            val segments = relPath.split('/').filter { it.isNotBlank() }
            if (segments.isEmpty()) return null
            val dirSegments = segments.dropLast(1)
            val dir = resolveDir(treeUri, dirSegments, create = false) ?: return null
            val target = childrenOf(treeUri, dirSegments, dir)[segments.last()]?.takeIf { it.isFile } ?: return null
            context.contentResolver.openInputStream(target.uri)?.use { it.readBytes() }
        } catch (e: Exception) {
            Log.w(TAG, "readBytes failed for $relPath", e)
            null
        }
    }

    /**
     * Delete <treeUri>/<relPath> if it exists.
     */
    fun deletePath(treeUri: Uri, relPath: String): Boolean {
        return try {
            val segments = relPath.split('/').filter { it.isNotBlank() }
            if (segments.isEmpty()) return false
            val dirSegments = segments.dropLast(1)
            val dir = resolveDir(treeUri, dirSegments, create = false) ?: return false
            val name = segments.last()
            val children = childrenOf(treeUri, dirSegments, dir)
            val target = children[name] ?: dir.findFile(name) ?: return false
            val ok = target.delete()
            if (ok) children.remove(name)
            ok
        } catch (e: Exception) {
            Log.w(TAG, "deletePath failed for $relPath", e)
            false
        }
    }

    /**
     * Delete all SAF-mirrored files for a tree.
     */
    fun deleteDatasetTree(treeUri: Uri, treeName: String, sideCount: Int) {
        for (i in 0 until sideCount) {
            deletePath(treeUri, "dataset/images/field/${treeName}_${i + 1}.jpg")
            deletePath(treeUri, "dataset/depth/field/${treeName}_${i + 1}.raw")
            deletePath(treeUri, "dataset/depth/field/${treeName}_${i + 1}.json")
            deletePath(treeUri, "Output TXT/field/${treeName}_${i + 1}.txt")
        }
        deletePath(treeUri, "dataset/metadata/${treeName}.json")
        deletePath(treeUri, "Output JSON/${treeName}.json")
    }
}
