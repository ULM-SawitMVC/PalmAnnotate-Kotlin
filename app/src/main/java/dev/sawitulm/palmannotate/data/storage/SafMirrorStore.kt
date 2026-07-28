package dev.sawitulm.palmannotate.data.storage

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
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

    /**
     * Resolved directory DocumentFile per "treeUri|relDir" key.
     * Every cached child directory is a validated tree-capable handle, never SingleDocumentFile.
     */
    private val dirCache = ConcurrentHashMap<String, DocumentFile>()
    /** Per-directory child map (name -> provider-classified child), keyed by the same "treeUri|relDir". */
    private val childCache = ConcurrentHashMap<String, Map<String, ChildEntry>>()
    /** Serializes provider calls and keeps cache values immutable to callers. */
    private val cacheLock = Any()

    private fun dirKey(treeUri: Uri, dirSegments: List<String>) =
        "$treeUri|${dirSegments.joinToString("/")}"

    private data class ChildEntry(
        val documentId: String,
        val document: DocumentFile,
        val mimeType: String,
    ) {
        val isDirectory: Boolean get() = mimeType == DocumentsContract.Document.MIME_TYPE_DIR
    }

    private sealed interface ChildListing {
        data class Success(val children: Map<String, ChildEntry>) : ChildListing
        data class Error(val cause: Throwable) : ChildListing
    }

    /** Query DocumentsProvider directly. MIME type is the provider's authoritative document type. */
    private fun listChildrenFromProvider(treeUri: Uri, dir: DocumentFile): ChildListing {
        return try {
            // Every directory handle used here must retain its own document id. Falling back to
            // the root tree id would silently query the wrong directory instead of failing closed.
            val documentId = DocumentsContract.getDocumentId(dir.uri)
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId)
            val projection = arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
            )
            val cursor = context.contentResolver.query(childrenUri, projection, null, null, null)
                ?: return ChildListing.Error(IllegalStateException("SAF child query returned null"))
            val children = HashMap<String, ChildEntry>()
            cursor.use {
                val idColumn = it.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameColumn = it.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeColumn = it.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
                while (it.moveToNext()) {
                    val childId = it.getString(idColumn)
                    val name = it.getString(nameColumn)
                    val mimeType = it.getString(mimeColumn)
                    if (childId.isNullOrBlank() || name.isNullOrBlank()) {
                        return ChildListing.Error(IllegalStateException("SAF child row is malformed"))
                    }
                    if (SafDocumentTypePolicy.classifyMimeType(mimeType) == SafDocumentType.UNKNOWN) {
                        return ChildListing.Error(IllegalStateException("SAF child row has unknown document type"))
                    }
                    val childUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, childId)
                    val child = DocumentFile.fromSingleUri(context, childUri)
                        ?: return ChildListing.Error(IllegalStateException("SAF child document unavailable"))
                    if (children.containsKey(name)) {
                        return ChildListing.Error(
                            IllegalStateException("SAF directory contains duplicate child name: $name")
                        )
                    }
                    children[name] = ChildEntry(childId, child, mimeType)
                }
            }
            ChildListing.Success(children.toMap())
        } catch (error: Throwable) {
            ChildListing.Error(error)
        }
    }

    /** Convert a provider-listed directory into a tree-capable handle without changing identity. */
    private fun treeDirectoryHandle(treeUri: Uri, child: ChildEntry): DocumentFile {
        check(child.isDirectory) { "SAF child is not a directory" }
        val childUri = child.document.uri
        check(DocumentsContract.isDocumentUri(context, childUri)) {
            "SAF child directory URI is not a document URI"
        }
        val expectedTreeId = DocumentsContract.getTreeDocumentId(treeUri)
        check(DocumentsContract.getTreeDocumentId(childUri) == expectedTreeId) {
            "SAF child directory belongs to a different tree"
        }
        check(DocumentsContract.getDocumentId(childUri) == child.documentId) {
            "SAF child directory identity mismatch"
        }
        val directory = DocumentFile.fromTreeUri(context, childUri)
            ?: throw IllegalStateException("SAF child directory is unavailable")
        check(DocumentsContract.getTreeDocumentId(directory.uri) == expectedTreeId &&
            DocumentsContract.getDocumentId(directory.uri) == child.documentId) {
            "SAF tree directory identity mismatch"
        }
        return directory
    }

    /**
     * Resolve (and optionally create) the directory at [dirSegments] under [treeUri].
     * Caches the resolved [DocumentFile] so repeat saves don't re-walk the chain.
     */
    private fun resolveDir(treeUri: Uri, dirSegments: List<String>, create: Boolean): DocumentFile? {
        val key = dirKey(treeUri, dirSegments)
        dirCache[key]?.let { return it }

        // A tree URI denotes a directory by SAF contract. Child document types are classified
        // exclusively from COLUMN_MIME_TYPE below; never call DocumentFile.isDirectory here.
        var node = DocumentFile.fromTreeUri(context, treeUri) ?: return null
        val acc = mutableListOf<String>()
        for (seg in dirSegments) {
            acc.add(seg)
            val subKey = dirKey(treeUri, acc)
            val cached = dirCache[subKey]
            if (cached != null) {
                node = cached
                continue
            }
            val listed = when (val result = listChildrenFromProvider(treeUri, node)) {
                is ChildListing.Success -> result.children[seg]
                is ChildListing.Error -> throw result.cause
            }
            if (listed != null) {
                // A name collision with a non-directory is not an absent directory. Do not call
                // createDirectory and allow the provider to manufacture a suffixed duplicate.
                if (!listed.isDirectory) {
                    throw IllegalStateException("SAF path segment is not a directory: $seg")
                }
                node = treeDirectoryHandle(treeUri, listed)
            } else {
                val created = if (create) node.createDirectory(seg) else null
                node = created ?: return null
            }
            dirCache[subKey] = node
        }
        return node
    }

    /** Child name -> DocumentFile map for [dir], listed once and cached.
     *  [forceRefresh] rebuilds the entry from a fresh provider query — used by correctness-sensitive
     *  probes where external truth must win over the cache. */
    private fun childrenOf(
        treeUri: Uri,
        dirSegments: List<String>,
        dir: DocumentFile,
        forceRefresh: Boolean = false,
    ): Map<String, ChildEntry> {
        val key = dirKey(treeUri, dirSegments)
        if (!forceRefresh) childCache[key]?.let { return it }
        val map = when (val result = listChildrenFromProvider(treeUri, dir)) {
            is ChildListing.Success -> result.children
            is ChildListing.Error -> throw result.cause
        }
        // Never replace a known-good cache with an error/empty fallback from a failed provider.
        childCache[key] = map
        return map
    }

    private fun putChild(
        treeUri: Uri,
        dirSegments: List<String>,
        name: String,
        document: DocumentFile,
        mimeType: String,
    ) {
        val key = dirKey(treeUri, dirSegments)
        val documentId = DocumentsContract.getDocumentId(document.uri)
        childCache[key] = childCache[key].orEmpty() +
            (name to ChildEntry(documentId, document, mimeType))
    }

    private fun removeChild(treeUri: Uri, dirSegments: List<String>, name: String) {
        val key = dirKey(treeUri, dirSegments)
        childCache[key]?.let { childCache[key] = it - name }
    }

    private sealed interface ProbeDir {
        data class Found(val document: DocumentFile) : ProbeDir
        data object Missing : ProbeDir
        data class Error(val cause: Throwable) : ProbeDir
    }

    /** Fresh provider traversal used only for correctness-sensitive probes and deletion. */
    private fun resolveDirForProbe(treeUri: Uri, dirSegments: List<String>): ProbeDir {
        val root = try {
            DocumentFile.fromTreeUri(context, treeUri)
                ?: return ProbeDir.Error(IllegalStateException("SAF tree is unavailable"))
        } catch (error: Throwable) {
            return ProbeDir.Error(error)
        }
        // A tree URI denotes a directory by SAF contract. Child types are classified by the
        // provider MIME column in listChildrenFromProvider.
        var node = root
        val acc = mutableListOf<String>()
        for (segment in dirSegments) {
            acc += segment
            val child = when (val listing = listChildrenFromProvider(treeUri, node)) {
                is ChildListing.Success -> listing.children[segment]
                is ChildListing.Error -> return ProbeDir.Error(listing.cause)
            } ?: return ProbeDir.Missing
            if (!child.isDirectory) {
                return ProbeDir.Error(IllegalStateException("SAF path segment is not a directory: $segment"))
            }
            node = try {
                treeDirectoryHandle(treeUri, child)
            } catch (error: Exception) {
                return ProbeDir.Error(error)
            }
            dirCache[dirKey(treeUri, acc)] = node
        }
        return ProbeDir.Found(node)
    }

    private fun pathStateLocked(
        treeUri: Uri,
        relPath: String,
        forceRefresh: Boolean,
    ): SafPathState {
        val segments = relPath.split('/').filter { it.isNotBlank() }
        if (segments.isEmpty()) return SafPathState.Inaccessible(IllegalArgumentException("Empty SAF path"))
        val dirSegments = segments.dropLast(1)
        val dir = if (forceRefresh) {
            when (val result = resolveDirForProbe(treeUri, dirSegments)) {
                is ProbeDir.Found -> result.document
                ProbeDir.Missing -> return SafPathState.Absent
                is ProbeDir.Error -> return SafPathState.Inaccessible(result.cause)
            }
        } else {
            val root = try {
                DocumentFile.fromTreeUri(context, treeUri)
                    ?: return SafPathState.Inaccessible(IllegalStateException("SAF tree is unavailable"))
            } catch (error: Throwable) {
                return SafPathState.Inaccessible(error)
            }
            try {
                resolveDir(treeUri, dirSegments, create = false)
                    ?: return SafPathState.Absent
            } catch (error: Throwable) {
                return SafPathState.Inaccessible(error)
            }
        }
        val children = try {
            childrenOf(treeUri, dirSegments, dir, forceRefresh = forceRefresh)
        } catch (error: Throwable) {
            return SafPathState.Inaccessible(error)
        }
        val target = children[segments.last()] ?: return SafPathState.Absent
        if (target.isDirectory) {
            return SafPathState.Inaccessible(IllegalStateException("SAF path is a directory, not a file"))
        }
        // The direct child query succeeded and returned this name, which proves presence. A
        // second DocumentFile.exists() call would reintroduce its Boolean error ambiguity.
        return SafPathState.Present
    }

    /**
     * Verify that the given tree URI is still accessible and writable.
     */
    fun isFolderAccessible(treeUri: Uri): Boolean = synchronized(cacheLock) {
        try {
            val doc = DocumentFile.fromTreeUri(context, treeUri)
            doc != null && doc.canWrite() &&
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
     * Atomically claim a previously absent display name. DocumentsProvider serializes create and
     * suffixes the loser (for example `name (1).json`); the suffix check turns that race into false
     * instead of allowing two devices to believe they own the same tree path.
     */
    fun createTextExclusively(treeUri: Uri, relPath: String, text: String): Boolean =
        synchronized(cacheLock) {
            try {
                val segments = relPath.split('/').filter { it.isNotBlank() }
                if (segments.isEmpty()) return@synchronized false
                val fileName = segments.last()
                val dirSegments = segments.dropLast(1)
                val dir = resolveDir(treeUri, dirSegments, create = true) ?: return@synchronized false
                val children = childrenOf(treeUri, dirSegments, dir, forceRefresh = true)
                if (children.containsKey(fileName)) return@synchronized false

                val created = dir.createFile("application/json", fileName) ?: return@synchronized false
                if (created.name != fileName) {
                    created.delete()
                    childrenOf(treeUri, dirSegments, dir, forceRefresh = true)
                    return@synchronized false
                }
                val written = runCatching {
                    context.contentResolver.openOutputStream(created.uri, "wt")?.use {
                        it.write(text.toByteArray(Charsets.UTF_8))
                        true
                    } ?: false
                }.getOrDefault(false)
                if (!written) {
                    created.delete()
                    childrenOf(treeUri, dirSegments, dir, forceRefresh = true)
                    return@synchronized false
                }
                putChild(treeUri, dirSegments, fileName, created, "application/json")
                true
            } catch (error: Exception) {
                Log.w(TAG, "createTextExclusively failed for $relPath", error)
                false
            }
        }

    /**
     * Write binary data to <treeUri>/<relPath>.
     *
     * Uses the directory + child caches and overwrites an existing file in place
     * (truncate) rather than delete+create, which is what made repeat saves slow.
     */
    fun writeBytes(treeUri: Uri, relPath: String, data: ByteArray, mimeType: String = "application/octet-stream"): Boolean =
        synchronized(cacheLock) {
            val segments = relPath.split('/').filter { it.isNotBlank() }
            if (segments.isEmpty()) return@synchronized false
            val fileName = segments.last()
            val dirSegments = segments.dropLast(1)

            fun writeTo(targetUri: Uri): Boolean = try {
                // "wt" = write+truncate, so overwriting a smaller payload doesn't leave a tail.
                context.contentResolver.openOutputStream(targetUri, "wt")?.use {
                    it.write(data)
                    true
                } ?: false
            } catch (error: Exception) {
                Log.w(TAG, "SAF target became stale for $relPath", error)
                false
            }

            fun resolveTarget(dir: DocumentFile, forceRefresh: Boolean): Uri? {
                val children = childrenOf(treeUri, dirSegments, dir, forceRefresh = forceRefresh)
                val existing = children[fileName]
                if (existing != null) {
                    if (existing.isDirectory) return null
                    return existing.document.uri
                }
                val created = dir.createFile(mimeType, fileName) ?: return null
                putChild(treeUri, dirSegments, fileName, created, mimeType)
                return created.uri
            }

            try {
                val dir = resolveDir(treeUri, dirSegments, create = true) ?: return@synchronized false
                val cachedChildren = childrenOf(treeUri, dirSegments, dir)
                val cached = cachedChildren[fileName]

                // An absent cached name must be confirmed by a fresh provider listing before create;
                // otherwise an externally-created file becomes a provider-suffixed duplicate.
                if (cached == null) {
                    return@synchronized resolveTarget(dir, forceRefresh = true)?.let(::writeTo) == true
                }
                if (cached.isDirectory) return@synchronized false
                if (writeTo(cached.document.uri)) return@synchronized true

                // External replacement/deletion invalidates the cached document ID. Relist once and
                // retry the current provider identity; never loop on the stale handle.
                resolveTarget(dir, forceRefresh = true)?.let(::writeTo) == true
            } catch (e: Exception) {
                Log.w(TAG, "writeBytes failed for $relPath", e)
                false
            }
        }

    /**
     * Create (or replace) a file at <treeUri>/<relPath> and return its document [Uri] for
     * STREAMING writes.
     *
     * Unlike [writeBytes] (which holds the whole payload in memory), the caller opens
     * `contentResolver.openOutputStream(uri)` and streams into it — required for large files
     * (e.g. a multi-GB dataset zip) that must NEVER be buffered. Reuses the directory + child
     * caches and deletes any existing file at the path first, so the returned uri starts empty.
     * The returned content uri is shareable (grant read permission) without FileProvider.
     */
    fun createFileForStreaming(treeUri: Uri, relPath: String, mime: String): Uri? = synchronized(cacheLock) {
        try {
            val segments = relPath.split('/').filter { it.isNotBlank() }
            if (segments.isEmpty()) return@synchronized null
            val fileName = segments.last()
            val dirSegments = segments.dropLast(1)
            val dir = resolveDir(treeUri, dirSegments, create = true) ?: return@synchronized null
            // Streaming destinations are rare and destructive; always re-list before deleting so
            // an externally replaced document is never addressed through a stale cached URI.
            val children = childrenOf(treeUri, dirSegments, dir, forceRefresh = true)
            children[fileName]?.let {
                if (it.isDirectory || !it.document.delete()) return@synchronized null
                removeChild(treeUri, dirSegments, fileName)
            }
            val created = dir.createFile(mime, fileName) ?: return@synchronized null
            putChild(treeUri, dirSegments, fileName, created, mime)
            created.uri
        } catch (e: Exception) {
            Log.w(TAG, "createFileForStreaming failed for $relPath", e)
            null
        }
    }

    /** Typed read for resume-sensitive callers. Null streams and provider failures are inaccessible. */
    fun readTextResult(treeUri: Uri, relPath: String): SafReadResult =
        when (val result = readBytesResult(treeUri, relPath)) {
            is SafReadResult.Success -> SafReadResult.Success(result.bytes)
            SafReadResult.Absent -> SafReadResult.Absent
            is SafReadResult.Inaccessible -> result
        }

    /** Best-effort convenience wrapper for mirror/reconciliation paths. */
    fun readText(treeUri: Uri, relPath: String): String? =
        (readTextResult(treeUri, relPath) as? SafReadResult.Success)
            ?.bytes?.toString(Charsets.UTF_8)

    /** Typed listing for resume-sensitive callers. */
    fun listFilesResult(treeUri: Uri, dirRelPath: String, suffix: String? = null): SafListingResult =
        synchronized(cacheLock) {
            try {
                if (DocumentFile.fromTreeUri(context, treeUri) == null) {
                    return@synchronized SafListingResult.Inaccessible(
                        IllegalStateException("SAF tree is unavailable")
                    )
                }
                val dirSegments = dirRelPath.split('/').filter { it.isNotBlank() }
                val dir = resolveDir(treeUri, dirSegments, create = false)
                    ?: return@synchronized SafListingResult.Absent
                val names = childrenOf(treeUri, dirSegments, dir, forceRefresh = true).values
                    .filter { !it.isDirectory }
                    .map { it.document.name ?: throw IllegalStateException("SAF child has no name") }
                    .filter { suffix == null || it.endsWith(suffix, ignoreCase = true) }
                SafListingResult.Success(names)
            } catch (e: Exception) {
                Log.w(TAG, "listFiles failed for $dirRelPath", e)
                SafListingResult.Inaccessible(e)
            }
        }

    /** Best-effort convenience wrapper; callers that need correctness must use listFilesResult. */
    fun listFiles(treeUri: Uri, dirRelPath: String, suffix: String? = null): List<String> =
        (listFilesResult(treeUri, dirRelPath, suffix) as? SafListingResult.Success)?.names.orEmpty()

    /**
     * Return a typed presence/access result. [forceRefresh] is for collision and delete probes;
     * ordinary mirror writes keep the cached O(1) path and do not relist every label directory.
     */
    fun exists(treeUri: Uri, relPath: String, forceRefresh: Boolean = false): SafPathState =
        synchronized(cacheLock) {
            pathStateLocked(treeUri, relPath, forceRefresh)
        }

    /**
     * Batched `exists(..., forceRefresh = true)` for probes that ask about many paths at once.
     *
     * Per-path semantics are identical to the single-path form — every parent directory is still
     * queried freshly, so the answer can never come from a stale cache. The difference is that each
     * DISTINCT directory is queried **once per batch** instead of once per path. The commit-time
     * collision probe asks about 23 paths spread over 7 directories, and the per-path form also
     * re-listed every ancestor directory on the way down; against an export folder whose depth
     * directory holds 1208 entries that dominated the cost of saving a tree.
     */
    fun existsAll(treeUri: Uri, relPaths: List<String>): Map<String, SafPathState> =
        synchronized(cacheLock) {
            val states = LinkedHashMap<String, SafPathState>()
            // Per parent directory, either its fresh listing or the single verdict that applies to
            // every path beneath it. Both are resolved at most once per batch.
            val listings = HashMap<String, Map<String, ChildEntry>>()
            val dirVerdicts = HashMap<String, SafPathState>()
            for (relPath in relPaths) {
                if (states.containsKey(relPath)) continue
                val segments = relPath.split('/').filter { it.isNotBlank() }
                if (segments.isEmpty()) {
                    states[relPath] =
                        SafPathState.Inaccessible(IllegalArgumentException("Empty SAF path"))
                    continue
                }
                val dirSegments = segments.dropLast(1)
                val key = dirKey(treeUri, dirSegments)
                if (!listings.containsKey(key) && !dirVerdicts.containsKey(key)) {
                    when (val resolved = resolveDirForProbe(treeUri, dirSegments)) {
                        ProbeDir.Missing -> dirVerdicts[key] = SafPathState.Absent
                        is ProbeDir.Error ->
                            dirVerdicts[key] = SafPathState.Inaccessible(resolved.cause)
                        is ProbeDir.Found -> try {
                            listings[key] = childrenOf(
                                treeUri,
                                dirSegments,
                                resolved.document,
                                forceRefresh = true,
                            )
                        } catch (error: Exception) {
                            dirVerdicts[key] = SafPathState.Inaccessible(error)
                        }
                    }
                }
                val children = listings[key]
                states[relPath] = if (children == null) {
                    dirVerdicts[key]
                        ?: SafPathState.Inaccessible(
                            IllegalStateException("SAF directory state unresolved: $relPath")
                        )
                } else {
                    when (val target = children[segments.last()]) {
                        null -> SafPathState.Absent
                        else -> if (target.isDirectory) {
                            SafPathState.Inaccessible(
                                IllegalStateException("SAF path is a directory, not a file")
                            )
                        } else {
                            SafPathState.Present
                        }
                    }
                }
            }
            states
        }

    /**
     * Typed read for resume-sensitive callers.
     *
     * The child listing is served from [childCache] first. That is safe because the cache holds
     * only the child's DocumentFile **handle** — the bytes below are always streamed live from the
     * provider — and every create/delete in this app keeps the map in step. Forcing a fresh
     * directory listing on every read instead made the cost quadratic: resuming the 151-tree
     * export folder re-enumerated the 1208-entry depth directory once per file it read, which is
     * how "Restoring existing trees" went from minutes to over half an hour.
     *
     * Correctness is unchanged because a miss is never trusted: neither [SafReadResult.Absent] nor
     * [SafReadResult.Inaccessible] is returned until the same lookup has been retried against a
     * freshly queried listing.
     */
    fun readBytesResult(treeUri: Uri, relPath: String): SafReadResult = synchronized(cacheLock) {
        try {
            if (DocumentFile.fromTreeUri(context, treeUri) == null) {
                return@synchronized SafReadResult.Inaccessible(
                    IllegalStateException("SAF tree is unavailable")
                )
            }
            val segments = relPath.split('/').filter { it.isNotBlank() }
            if (segments.isEmpty()) {
                return@synchronized SafReadResult.Inaccessible(IllegalArgumentException("Empty SAF path"))
            }
            val dirSegments = segments.dropLast(1)
            val name = segments.last()
            val dir = resolveDir(treeUri, dirSegments, create = false)
                ?: return@synchronized SafReadResult.Absent
            val cached = readChildLocked(treeUri, dirSegments, dir, name, forceRefresh = false)
            if (cached is SafReadResult.Success) return@synchronized cached
            // Both remaining verdicts can also mean "the cached listing is stale": the file was
            // created, or replaced, outside this store since the directory was last listed. Re-list
            // once and let the fresh answer stand.
            readChildLocked(treeUri, dirSegments, dir, name, forceRefresh = true)
        } catch (e: Exception) {
            Log.w(TAG, "readBytes failed for $relPath", e)
            SafReadResult.Inaccessible(e)
        }
    }

    /** Locate [name] under [dir] and stream it; the caller decides whether the listing may be cached. */
    private fun readChildLocked(
        treeUri: Uri,
        dirSegments: List<String>,
        dir: DocumentFile,
        name: String,
        forceRefresh: Boolean,
    ): SafReadResult {
        val target = childrenOf(treeUri, dirSegments, dir, forceRefresh = forceRefresh)[name]
            ?: return SafReadResult.Absent
        if (target.isDirectory) {
            return SafReadResult.Inaccessible(
                IllegalStateException("SAF path is a directory, not a file")
            )
        }
        // A handle from the cache can outlive its document. Treat the failure as a possible stale
        // handle rather than a provider fault; the caller retries with a fresh listing.
        val bytes = try {
            context.contentResolver.openInputStream(target.document.uri)?.use { it.readBytes() }
        } catch (error: Exception) {
            return SafReadResult.Inaccessible(error)
        }
        return bytes?.let { SafReadResult.Success(it) }
            ?: SafReadResult.Inaccessible(IllegalStateException("SAF input stream returned null"))
    }

    /** Best-effort convenience wrapper; null intentionally conflates absent/error here. */
    fun readBytes(treeUri: Uri, relPath: String): ByteArray? =
        (readBytesResult(treeUri, relPath) as? SafReadResult.Success)?.bytes

    private fun deletePathLocked(treeUri: Uri, relPath: String): Boolean {
        return try {
            val segments = relPath.split('/').filter { it.isNotBlank() }
            if (segments.isEmpty()) return false
            val dirSegments = segments.dropLast(1)
            val dir = resolveDir(treeUri, dirSegments, create = false) ?: return false
            val name = segments.last()
            val target = childrenOf(treeUri, dirSegments, dir)[name] ?: return false
            if (target.isDirectory) return false
            val ok = target.document.delete()
            if (ok) removeChild(treeUri, dirSegments, name)
            ok
        } catch (e: Exception) {
            Log.w(TAG, "deletePath failed for $relPath", e)
            false
        }
    }

    /** Delete <treeUri>/<relPath> if it exists, using the ordinary cached mirror path. */
    fun deletePath(treeUri: Uri, relPath: String): Boolean = synchronized(cacheLock) {
        deletePathLocked(treeUri, relPath)
    }

    /**
     * Delete all SAF-mirrored files for a tree. Every path is freshly inspected and every delete is
     * freshly verified. Provider errors are failures, never evidence that a path was absent.
     */
    fun deleteDatasetTree(treeUri: Uri, treeName: String, sideCount: Int): SafDeleteResult =
        synchronized(cacheLock) {
            val paths = buildList {
                for (i in 0 until sideCount) {
                    add("dataset/images/field/${treeName}_${i + 1}.jpg")
                    add("dataset/depth/field/${treeName}_${i + 1}.raw")
                    add("dataset/depth/field/${treeName}_${i + 1}.json")
                    add("dataset/annotlog/field/${treeName}_${i + 1}.json")
                    add("Output TXT/field/${treeName}_${i + 1}.txt")
                }
                add("dataset/metadata/${treeName}.json")
                add("dataset/manifests/${treeName}.json")
                add("dataset/reservations/${treeName}.json")
                add("Output JSON/${treeName}.json")
            }
            SafDeleteVerifier.verify(
                paths = paths,
                inspect = { path -> pathStateLocked(treeUri, path, forceRefresh = true) },
                delete = { path -> deletePathLocked(treeUri, path) },
            )
        }
}
