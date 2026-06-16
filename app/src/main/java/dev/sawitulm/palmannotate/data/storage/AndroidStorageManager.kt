package dev.sawitulm.palmannotate.data.storage

import android.content.Context
import android.net.Uri
import android.util.Log
import java.io.File
import java.io.FileOutputStream

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
    val annotLogDir get() = File(rootDir, "annotlog/field").also { it.mkdirs() }
    val outputJsonDir get() = File(rootDir, "Output JSON").also { it.mkdirs() }
    val outputTxtDir get() = File(rootDir, "Output TXT/field").also { it.mkdirs() }
    val exportsDir get() = File(rootDir, "exports").also { it.mkdirs() }
    val snapshotsDir get() = File(rootDir, "snapshots").also { it.mkdirs() }

    // sessions.json index dropped on native (resume is folder-scan based)

    // ─── Image helpers ────────────────────────────────────────────────────────

    fun imageFile(treeName: String, sideIndex: Int): File =
        File(imagesDir, "${treeName}_${sideIndex + 1}.jpg")

    fun imageUri(treeName: String, sideIndex: Int): Uri =
        Uri.fromFile(imageFile(treeName, sideIndex))

    // ─── Label helpers ────────────────────────────────────────────────────────

    fun labelFile(treeName: String, sideIndex: Int): File =
        File(labelsDir, "${treeName}_${sideIndex + 1}.txt")

    // ─── Depth helpers ────────────────────────────────────────────────────────

    fun depthRawFile(treeName: String, sideIndex: Int): File =
        File(depthDir, "${treeName}_${sideIndex + 1}.raw")

    fun depthJsonFile(treeName: String, sideIndex: Int): File =
        File(depthDir, "${treeName}_${sideIndex + 1}.json")

    // ─── Metadata helpers ─────────────────────────────────────────────────────

    fun metadataFile(treeName: String): File =
        File(metadataDir, "${treeName}.json")

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
        file.parentFile?.mkdirs()
        file.writeText(text)
    }

    fun writeBytes(file: File, bytes: ByteArray) {
        file.parentFile?.mkdirs()
        FileOutputStream(file).use { it.write(bytes) }
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
        if (deleteFile(outputJsonFile(treeName))) removed++
        return removed
    }
}
