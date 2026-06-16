package dev.sawitulm.palmannotate.data.storage

import android.net.Uri
import android.util.Log
import androidx.room.withTransaction
import dev.sawitulm.palmannotate.data.db.*
import dev.sawitulm.palmannotate.data.export.ExportManager
import dev.sawitulm.palmannotate.data.yolo.YoloParser
import dev.sawitulm.palmannotate.domain.model.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

/**
 * Central repository — bridges Room (runs + trees + annotations), the filesystem,
 * and the SAF mirror.
 *
 * Vocabulary (matches the JS app):
 *   RUN  (SessionEntity) — variety+block, holds many trees, owns the tree-id counter.
 *   TREE (TreeEntity)    — one tree = N side photos; the unit that is annotated.
 *
 * Across the UI/nav an annotation target is identified by its **treeKey**, which is
 * what [ActiveSession.sessionId] carries.
 */
class SessionRepository(
    private val sessionDao: SessionDao,
    private val treeDao: TreeDao,
    private val sideDao: SideDao,
    private val bboxDao: BboxDao,
    private val linkDao: ConfirmedLinkDao,
    private val storage: AndroidStorageManager,
    private val saf: SafMirrorStore,
    private val db: PalmAnnotateDatabase,
) {
    companion object {
        private const val TAG = "SessionRepo"
        private val ISO_FORMAT = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
    }

    /**
     * Background scope for the best-effort SAF mirror. Single-threaded so mirror
     * jobs serialise (no interleaving writes to the same file) while staying OFF
     * the UI save path — the DB + local files are already the source of truth, so
     * the user never waits ~11 s for DocumentsContract I/O. See [mirrorSafArtifacts].
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val safScope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))

    // ─── Runs (home list) ──────────────────────────────────────────────────────

    /** Observe runs with a derived tree count for the home screen. */
    fun observeRuns(): Flow<List<RunSummary>> =
        sessionDao.observeAll().combine(treeDao.observeAll()) { runs, trees ->
            val counts = trees.groupingBy { it.sessionId }.eachCount()
            runs.map { it.toSummary(counts[it.sessionId] ?: 0) }
        }

    suspend fun getRun(sessionId: String): SessionEntity? = withContext(Dispatchers.IO) {
        sessionDao.getById(sessionId)
    }

    private fun normToken(s: String) = s.uppercase().replace(Regex("[^A-Z0-9]"), "")
    fun groupKeyFor(variety: String, block: String) = "${normToken(variety)}__${normToken(block)}"

    suspend fun createRun(variety: String, block: String, sideCount: Int, autoId: Boolean): String =
        withContext(Dispatchers.IO) {
            val id = UUID.randomUUID().toString()
            val now = System.currentTimeMillis()
            sessionDao.upsert(
                SessionEntity(
                    sessionId = id, variety = variety.trim(), block = block.trim(),
                    groupKey = groupKeyFor(variety, block),
                    sideCount = sideCount.coerceAtLeast(2), autoId = autoId, nextId = 1,
                    createdAt = now, updatedAt = now,
                )
            )
            id
        }

    suspend fun deleteRun(sessionId: String, safTreeUri: Uri? = null) = withContext(Dispatchers.IO) {
        val trees = treeDao.getBySession(sessionId)
        for (t in trees) deleteTreeArtifacts(t, safTreeUri)
        sessionDao.deleteById(sessionId) // cascades trees/sides/bboxes/links
    }

    // ─── Trees within a run ──────────────────────────────────────────────────────

    fun observeTrees(sessionId: String): Flow<List<TreeEntity>> = treeDao.observeBySession(sessionId)
    suspend fun getTrees(sessionId: String): List<TreeEntity> = withContext(Dispatchers.IO) {
        treeDao.getBySession(sessionId)
    }

    /**
     * Add a captured tree to a run: insert the tree + its sides/bboxes, write the
     * YOLO labels + annot-log, and advance the run's nextId. Returns the treeKey.
     */
    suspend fun addTree(
        sessionId: String,
        treeName: String,
        treeId: Int,
        split: String,
        sides: List<TreeSide>,
        metadata: TreeMetadata?,
        safTreeUri: Uri? = null,
    ): String = withContext(Dispatchers.IO) {
        val run = sessionDao.getById(sessionId) ?: throw IllegalStateException("Run not found")
        val now = System.currentTimeMillis()
        val treeKey = UUID.randomUUID().toString()
        // Tree row + its sides in ONE transaction so a concurrent reader can never observe
        // a tree that has no sides yet (which a racing save could then persist as the truth).
        db.withTransaction {
            treeDao.upsert(
                TreeEntity(
                    treeKey = treeKey, sessionId = sessionId, treeName = treeName, treeId = treeId,
                    split = split, sideCount = sides.size.coerceAtLeast(run.sideCount),
                    variety = metadata?.variety ?: run.variety, block = metadata?.block ?: run.block,
                    createdAt = now, updatedAt = now,
                )
            )
            persistSidesDb(treeKey, sides)
        }

        // Persist metadata JSON (and SAF mirror if configured).
        val metaJson = buildMetadataJson(metadata, treeName, run, treeId).toString(2)
        try { storage.writeText(storage.metadataFile(treeName), metaJson) } catch (_: Exception) {}
        if (safTreeUri != null) {
            saf.writeText(safTreeUri, "dataset/metadata/${treeName}.json", metaJson)
        }

        writeSideArtifacts(treeName, split, sides, safTreeUri)

        // Advance the tree-id counter past the highest used id. MUST be an UPDATE,
        // not upsert(REPLACE): replacing the existing run row cascade-deletes the
        // tree we just inserted above (trees FK onDelete=CASCADE).
        val nextId = maxOf(run.nextId, treeId + 1)
        sessionDao.update(run.copy(nextId = nextId, updatedAt = now))
        treeKey
    }

    suspend fun deleteTree(treeKey: String, safTreeUri: Uri? = null) = withContext(Dispatchers.IO) {
        val tree = treeDao.getByKey(treeKey) ?: return@withContext
        treeDao.deleteByKey(treeKey) // cascades sides/bboxes/links
        deleteTreeArtifacts(tree, safTreeUri)
        // Recompute the run's nextId from survivors (frees the lowest id, like JS).
        val run = sessionDao.getById(tree.sessionId)
        if (run != null) {
            val survivors = treeDao.getBySession(tree.sessionId)
            val maxId = survivors.maxOfOrNull { it.treeId } ?: 0
            sessionDao.update(run.copy(nextId = maxId + 1, updatedAt = System.currentTimeMillis()))
        }
    }

    private fun deleteTreeArtifacts(tree: TreeEntity, safTreeUri: Uri?) {
        storage.deleteTree(tree.treeName, tree.sideCount)
        if (safTreeUri != null) saf.deleteDatasetTree(safTreeUri, tree.treeName, tree.sideCount)
    }

    private fun buildMetadataJson(metadata: TreeMetadata?, treeName: String, run: SessionEntity, treeId: Int): JSONObject {
        val ts = ISO_FORMAT.format(Date())
        return JSONObject().apply {
            put("name", treeName)
            put("variety", metadata?.variety ?: run.variety)
            put("blok", metadata?.block ?: run.block)
            put("treeId", treeId)
            put("operator", "")
            put("timestamp", ts)
            metadata?.latitude?.let { put("lat", it) }
            metadata?.longitude?.let { put("lng", it) }
        }
    }

    // ─── Load / save one tree as an ActiveSession ───────────────────────────────

    suspend fun loadActiveSession(treeKey: String): ActiveSession? = withContext(Dispatchers.IO) {
        val tree = treeDao.getByKey(treeKey) ?: return@withContext null
        val sideEntities = sideDao.getByTree(treeKey)
        val sides = sideEntities.map { se ->
            val bboxes = bboxDao.getBySide(se.id).map { it.toBbox() }
            TreeSide(
                sideIndex = se.sideIndex, label = se.label,
                imageUri = se.imageUri.takeIf { it.isNotBlank() }?.let { Uri.parse(it) },
                labelUri = se.labelUri?.let { Uri.parse(it) },
                imageWidth = se.imageWidth, imageHeight = se.imageHeight,
                bboxes = bboxes, originalBboxes = bboxes,
            )
        }
        val links = linkDao.getByTree(treeKey).map {
            CrossSideLink.create(it.linkId, it.sideA, it.bboxIdA, it.sideB, it.bboxIdB)
        }
        ActiveSession(
            sessionId = treeKey, treeName = tree.treeName, split = tree.split,
            sides = sides, suggestedLinks = emptyList(), confirmedLinks = links,
            metadata = TreeMetadata(variety = tree.variety, block = tree.block, treeId = tree.treeId.toString()),
            createdAt = tree.createdAt, updatedAt = tree.updatedAt,
        )
    }

    /** Write-through save of one tree (sides/bboxes/links + label files + annot-log). */
    suspend fun saveSession(session: ActiveSession, safTreeUri: Uri? = null) = withContext(Dispatchers.IO) {
        val saveStart = System.currentTimeMillis()
        Log.d(TAG, "saveSession START - tree=${session.treeName}")
        
        val treeKey = session.sessionId
        val tree = treeDao.getByKey(treeKey) ?: return@withContext
        val now = System.currentTimeMillis()
        // Atomic DB write: tree row + sides + bboxes + links replaced in ONE transaction so a
        // concurrent load (or another screen's save) can never read — nor persist — a partial
        // side list. The old non-atomic delete-then-insert was the "a tree loses a side on
        // reopen" bug: a reader could catch the gap between deleteByTree and the re-inserts.
        var rewroteSides = false
        
        val dbStart = System.currentTimeMillis()
        db.withTransaction {
            val existingSides = sideDao.getByTree(treeKey).size
            // Defence-in-depth: the app never removes a side, so a save carrying FEWER sides
            // than are already stored is a stale/partial in-memory session — persisting it
            // would drop a photo. Keep the stored sides; still update links.
            rewroteSides = session.sides.size >= existingSides
            treeDao.update(tree.copy(updatedAt = now, sideCount = if (rewroteSides) session.sides.size else existingSides))
            if (rewroteSides) {
                persistSidesDb(treeKey, session.sides)
            } else {
                Log.w(TAG, "saveSession: refusing to shrink sides for $treeKey (${session.sides.size} < $existingSides); kept stored sides")
            }
            linkDao.deleteByTree(treeKey)
            linkDao.insertAll(session.confirmedLinks.map {
                ConfirmedLinkEntity(treeKey = treeKey, linkId = it.linkId, sideA = it.sideA, bboxIdA = it.bboxIdA, sideB = it.sideB, bboxIdB = it.bboxIdB)
            })
        }
        val dbTime = System.currentTimeMillis() - dbStart
        Log.d(TAG, "saveSession DB transaction took ${dbTime}ms")
        
        // Slow file/SAF artifacts run OUTSIDE the transaction (never hold the DB lock for the
        // multi-MB SAF image mirror). Skipped when we declined to rewrite sides.
        if (rewroteSides) {
            val artifactsStart = System.currentTimeMillis()
            writeSideArtifacts(session.treeName, session.split, session.sides, safTreeUri)
            val artifactsTime = System.currentTimeMillis() - artifactsStart
            Log.d(TAG, "saveSession writeSideArtifacts took ${artifactsTime}ms")
        }
        
        val totalTime = System.currentTimeMillis() - saveStart
        Log.d(TAG, "saveSession END - total=${totalTime}ms")
    }
    
    /** Save only the DB transaction (fast). Returns after DB commit, before file artifacts.
     *  Used when we need data persisted before navigating but don't want to wait for SAF I/O. */
    suspend fun saveDbOnly(session: ActiveSession) = withContext(Dispatchers.IO) {
        val saveStart = System.currentTimeMillis()
        Log.d(TAG, "saveDbOnly START - tree=${session.treeName}")
        
        val treeKey = session.sessionId
        val tree = treeDao.getByKey(treeKey) ?: return@withContext
        val now = System.currentTimeMillis()
        var rewroteSides = false
        
        db.withTransaction {
            val existingSides = sideDao.getByTree(treeKey).size
            rewroteSides = session.sides.size >= existingSides
            treeDao.update(tree.copy(updatedAt = now, sideCount = if (rewroteSides) session.sides.size else existingSides))
            if (rewroteSides) {
                persistSidesDb(treeKey, session.sides)
            }
            linkDao.deleteByTree(treeKey)
            linkDao.insertAll(session.confirmedLinks.map {
                ConfirmedLinkEntity(treeKey = treeKey, linkId = it.linkId, sideA = it.sideA, bboxIdA = it.bboxIdA, sideB = it.sideB, bboxIdB = it.bboxIdB)
            })
        }
        
        val totalTime = System.currentTimeMillis() - saveStart
        Log.d(TAG, "saveDbOnly END - total=${totalTime}ms")
    }

    /** Replace a tree's sides + bboxes in the DB. Call INSIDE a [db] transaction. */
    private suspend fun persistSidesDb(treeKey: String, sides: List<TreeSide>) {
        sideDao.deleteByTree(treeKey)
        bboxDao.deleteByTree(treeKey)
        for (side in sides) {
            val sideId = sideDao.upsert(
                SideEntity(
                    treeKey = treeKey, sideIndex = side.sideIndex, label = side.label,
                    imageUri = side.imageUri?.toString() ?: "",
                    imageWidth = side.imageWidth, imageHeight = side.imageHeight,
                    labelUri = side.labelUri?.toString(),
                )
            )
            bboxDao.insertAll(side.bboxes.map { it.toEntity(sideId) })
        }
    }

    /**
     * Write a tree's side artifacts. Splits into:
     *  - LOCAL (label .txt + annot-log to internal storage): the source of truth, FAST
     *    (~1–3 ms/side), written synchronously so callers can rely on it on return.
     *  - SAF mirror (label + annot-log + image): slow DocumentsContract I/O
     *    (measured ~11 s for 4 sides), fired on [safScope] so it never blocks the
     *    save/UI. SAF is an explicit best-effort mirror; the local store is the truth.
     */
    private fun writeSideArtifacts(treeName: String, split: String, sides: List<TreeSide>, safTreeUri: Uri?) {
        val t0 = System.currentTimeMillis()
        writeLocalArtifacts(treeName, split, sides)
        Log.d(TAG, "writeLocalArtifacts ${sides.size} sides took ${System.currentTimeMillis() - t0}ms")
        if (safTreeUri != null) {
            safScope.launch { mirrorSafArtifacts(treeName, split, sides, safTreeUri) }
        }
    }

    /** Source-of-truth local files (YOLO label + annot-log). Synchronous, fast. */
    private fun writeLocalArtifacts(treeName: String, split: String, sides: List<TreeSide>) {
        for (side in sides) {
            if (side.imageWidth > 0 && side.imageHeight > 0) {
                val yoloText = YoloParser.serialize(side.bboxes, side.imageWidth, side.imageHeight)
                runCatching { storage.writeText(storage.labelFile(treeName, side.sideIndex), yoloText) }
            }
            runCatching {
                storage.writeText(storage.annotLogFile(treeName, side.sideIndex), buildAnnotLog(treeName, split, side))
            }
        }
    }

    /** Best-effort SAF mirror (label + annot-log + image). Slow; run on [safScope]. */
    private fun mirrorSafArtifacts(treeName: String, split: String, sides: List<TreeSide>, safTreeUri: Uri) {
        val t0 = System.currentTimeMillis()
        for (side in sides) {
            if (side.imageWidth > 0 && side.imageHeight > 0) {
                val yoloText = YoloParser.serialize(side.bboxes, side.imageWidth, side.imageHeight)
                runCatching {
                    saf.writeText(safTreeUri, "Output TXT/field/${treeName}_${side.sideIndex + 1}.txt", yoloText)
                }
            }
            runCatching {
                saf.writeText(safTreeUri, "dataset/annotlog/field/${treeName}_${side.sideIndex + 1}.json",
                    buildAnnotLog(treeName, split, side))
            }
            if (side.imageUri != null) {
                // Images never change after capture — mirror once, then skip.
                val mirrorPath = "dataset/images/field/${treeName}_${side.sideIndex + 1}.jpg"
                if (!saf.exists(safTreeUri, mirrorPath)) {
                    runCatching {
                        val imgFile = File(side.imageUri.path ?: "")
                        if (imgFile.exists()) {
                            saf.writeBytes(safTreeUri, mirrorPath, imgFile.readBytes(), "image/jpeg")
                        }
                    }.onFailure { Log.w(TAG, "SAF mirror image failed for side ${side.sideIndex}", it) }
                }
            }
        }
        Log.d(TAG, "mirrorSafArtifacts $treeName (${sides.size} sides) took ${System.currentTimeMillis() - t0}ms [background]")
    }

    // ─── Output JSON ─────────────────────────────────────────────────────────────

    suspend fun saveOutputJson(session: ActiveSession, result: TreeResults, safTreeUri: Uri? = null) =
        withContext(Dispatchers.IO) {
            val jsonText = ExportManager.generateOutputJson(session, result).toString(2)
            // Local file + DB completion flag are the source of truth — written synchronously
            // so the caller can navigate immediately after this returns.
            storage.writeText(storage.outputJsonFile(session.treeName), jsonText)
            // Mark the tree complete (the JS "Compute & Mark Complete" green check).
            // UPDATE, not upsert(REPLACE): REPLACE would cascade-delete the tree's
            // sides/links (annotations) when flipping the complete flag.
            treeDao.getByKey(session.sessionId)?.let { treeDao.update(it.copy(isComplete = true, updatedAt = System.currentTimeMillis())) }
            // The SAF mirror is best-effort — push it off the critical path so finishing a
            // tree (and moving to the next capture) never waits on DocumentsContract I/O.
            if (safTreeUri != null) {
                safScope.launch { saf.writeText(safTreeUri, "Output JSON/${session.treeName}.json", jsonText) }
            }
        }

    /** The run (session) id that owns [treeKey] — for "next capture" / "back to tree list" nav. */
    suspend fun getTreeRunId(treeKey: String): String? = withContext(Dispatchers.IO) {
        treeDao.getByKey(treeKey)?.sessionId
    }

    // sessions.json index dropped on native (resume is folder-scan based)

    // ─── Folder-scan resume helpers (used by FolderResumeImporter) ───────────────

    /** All tree names currently in Room (for resume de-dupe). */
    suspend fun allTreeNames(): Set<String> = withContext(Dispatchers.IO) {
        treeDao.getAllOnce().map { it.treeName }.toSet()
    }

    /** Map of run groupKey → its sessionId (for reusing a run on resume). */
    suspend fun runGroupKeyToId(): Map<String, String> = withContext(Dispatchers.IO) {
        sessionDao.getAllOnce().associate { it.groupKey to it.sessionId }
    }

    /** Replace a tree's confirmed links (used when resuming a tree from Output JSON). */
    suspend fun replaceConfirmedLinks(treeKey: String, links: List<CrossSideLink>) =
        withContext(Dispatchers.IO) {
            linkDao.deleteByTree(treeKey)
            linkDao.insertAll(links.map {
                ConfirmedLinkEntity(treeKey = treeKey, linkId = it.linkId, sideA = it.sideA, bboxIdA = it.bboxIdA, sideB = it.sideB, bboxIdB = it.bboxIdB)
            })
        }

    /** Build the annot-log JSON text for a side (written to both local + SAF). */
    private fun buildAnnotLog(treeName: String, split: String, side: TreeSide): String {
        val log = JSONObject().apply {
            put("treeName", treeName); put("sideIndex", side.sideIndex); put("split", split)
            put("savedAt", System.currentTimeMillis())
            put("suggestions", annotLogArray(side.originalBboxes))
            put("final", annotLogArray(side.bboxes))
        }
        return log.toString(2)
    }

    private fun annotLogArray(boxes: List<Bbox>): JSONArray = JSONArray().apply {
        for (b in boxes) put(JSONObject().apply {
            put("id", b.id); put("classId", b.classId); put("className", b.className)
            put("bbox_pixel", JSONArray().apply {
                put(Math.round(b.x1)); put(Math.round(b.y1)); put(Math.round(b.x2)); put(Math.round(b.y2))
            })
        })
    }
}

// ─── Mappers + summary types ────────────────────────────────────────────────────

private fun SessionEntity.toSummary(treeCount: Int) = RunSummary(
    sessionId = sessionId, variety = variety, block = block, groupKey = groupKey,
    sideCount = sideCount, autoId = autoId, nextId = nextId,
    createdAt = createdAt, updatedAt = updatedAt, treeCount = treeCount,
)

private fun BboxEntity.toBbox() = Bbox(id = bboxId, classId = classId, className = className, x1 = x1, y1 = y1, x2 = x2, y2 = y2)
private fun Bbox.toEntity(sideId: Long) = BboxEntity(sideId = sideId, bboxId = id, classId = classId, className = className, x1 = x1, y1 = y1, x2 = x2, y2 = y2)

/** Home-screen summary of one run (with derived tree count). */
data class RunSummary(
    val sessionId: String,
    val variety: String,
    val block: String,
    val groupKey: String,
    val sideCount: Int,
    val autoId: Boolean,
    val nextId: Int,
    val createdAt: Long,
    val updatedAt: Long,
    val treeCount: Int,
)
