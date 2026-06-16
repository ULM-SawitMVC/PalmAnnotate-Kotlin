package dev.sawitulm.palmannotate.data.storage

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import android.util.Log
import androidx.documentfile.provider.DocumentFile

/**
 * Best-effort SAF (Storage Access Framework) mirror.
 *
 * When an export folder is configured, files are ALSO written here so they
 * appear in a user-browsable public folder (Documents / SD card / USB-OTG).
 * The app-external store is ALWAYS the source of truth.
 */
class SafMirrorStore(private val context: Context) {

    companion object {
        private const val TAG = "SafMirror"
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
     */
    fun writeText(treeUri: Uri, relPath: String, text: String): Boolean {
        return writeBytes(treeUri, relPath, text.toByteArray(Charsets.UTF_8), "application/json")
    }

    /**
     * Write binary data to <treeUri>/<relPath>.
     */
    fun writeBytes(treeUri: Uri, relPath: String, data: ByteArray, mimeType: String = "application/octet-stream"): Boolean {
        return try {
            val tree = DocumentFile.fromTreeUri(context, treeUri) ?: return false
            val segments = relPath.split('/').filter { it.isNotBlank() }
            if (segments.isEmpty()) return false
            val fileName = segments.last()

            // Walk/create directory chain
            var dir = tree
            for (i in 0 until segments.size - 1) {
                dir = dir.findFile(segments[i])
                    ?.takeIf { it.isDirectory }
                    ?: dir.createDirectory(segments[i])
                    ?: return false
            }

            // Delete existing file to avoid "name (1)" duplicates
            dir.findFile(fileName)?.delete()

            val file = dir.createFile(mimeType, fileName) ?: return false
            context.contentResolver.openOutputStream(file.uri)?.use { it.write(data) }
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
        return try {
            val tree = DocumentFile.fromTreeUri(context, treeUri) ?: return null
            val segments = relPath.split('/').filter { it.isNotBlank() }
            if (segments.isEmpty()) return null

            var node = tree
            for (i in 0 until segments.size - 1) {
                val child = node.findFile(segments[i])
                if (child == null || !child.isDirectory) return null
                node = child
            }
            val target = node.findFile(segments.last())
            if (target == null || !target.isFile) return null

            context.contentResolver.openInputStream(target.uri)?.use { input ->
                input.readBytes().toString(Charsets.UTF_8)
            }
        } catch (e: Exception) {
            Log.w(TAG, "readText failed for $relPath", e)
            null
        }
    }

    /**
     * List the file names directly inside <treeUri>/<dirRelPath> matching an
     * optional suffix (case-insensitive, e.g. ".json"). Returns base names only
     * (not full paths). Empty list if the directory is missing/unreadable.
     */
    fun listFiles(treeUri: Uri, dirRelPath: String, suffix: String? = null): List<String> {
        return try {
            val tree = DocumentFile.fromTreeUri(context, treeUri) ?: return emptyList()
            val segments = dirRelPath.split('/').filter { it.isNotBlank() }
            var node = tree
            for (seg in segments) {
                val child = node.findFile(seg)
                if (child == null || !child.isDirectory) return emptyList()
                node = child
            }
            node.listFiles()
                .filter { it.isFile }
                .mapNotNull { it.name }
                .filter { suffix == null || it.endsWith(suffix, ignoreCase = true) }
        } catch (e: Exception) {
            Log.w(TAG, "listFiles failed for $dirRelPath", e)
            emptyList()
        }
    }

    /**
     * Cheap presence check: true if <treeUri>/<relPath> exists as a file. Used to skip
     * re-mirroring large unchanged blobs (captured JPEGs) on every save — reading and
     * re-writing several MB through SAF on each save was the main "save feels heavy" cost.
     */
    fun exists(treeUri: Uri, relPath: String): Boolean {
        return try {
            val tree = DocumentFile.fromTreeUri(context, treeUri) ?: return false
            val segments = relPath.split('/').filter { it.isNotBlank() }
            if (segments.isEmpty()) return false
            var node = tree
            for (i in 0 until segments.size - 1) {
                val child = node.findFile(segments[i])
                if (child == null || !child.isDirectory) return false
                node = child
            }
            node.findFile(segments.last())?.isFile == true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Read raw bytes from <treeUri>/<relPath>. Returns null if missing/unreadable.
     */
    fun readBytes(treeUri: Uri, relPath: String): ByteArray? {
        return try {
            val tree = DocumentFile.fromTreeUri(context, treeUri) ?: return null
            val segments = relPath.split('/').filter { it.isNotBlank() }
            if (segments.isEmpty()) return null

            var node = tree
            for (i in 0 until segments.size - 1) {
                val child = node.findFile(segments[i])
                if (child == null || !child.isDirectory) return null
                node = child
            }
            val target = node.findFile(segments.last())
            if (target == null || !target.isFile) return null

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
            val tree = DocumentFile.fromTreeUri(context, treeUri) ?: return false
            val segments = relPath.split('/').filter { it.isNotBlank() }
            if (segments.isEmpty()) return false

            var node = tree
            for (i in 0 until segments.size - 1) {
                val child = node.findFile(segments[i])
                if (child == null || !child.isDirectory) return false
                node = child
            }
            node.findFile(segments.last())?.delete() ?: false
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
