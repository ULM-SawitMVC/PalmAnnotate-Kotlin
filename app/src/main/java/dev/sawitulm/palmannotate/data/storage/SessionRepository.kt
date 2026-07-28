package dev.sawitulm.palmannotate.data.storage

import android.net.Uri
import android.util.Log
import androidx.room.withTransaction
import dev.sawitulm.palmannotate.data.db.*
import dev.sawitulm.palmannotate.data.export.CaptureSetMergePolicy
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

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
    private val artifactCoordinator: ArtifactCoordinator,
    private val mirrorDao: MirrorStatusDao,
    private val captureDraftDao: CaptureDraftDao,
    private val injectedMirrorWorkScheduler: MirrorWorkScheduler? = null,
    private val injectedMirrorDeletionDao: MirrorDeletionDao? = null,
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
    private val mirrorWorkScheduler = injectedMirrorWorkScheduler
        ?: MirrorWorkScheduler { block -> safScope.launch { block() } }
    private val mirrorDeletionDao = injectedMirrorDeletionDao ?: db.mirrorDeletionDao()
    private val draftGenerations = ConcurrentHashMap<String, AtomicLong>()
    private val draftPersistedGenerations = ConcurrentHashMap<String, AtomicLong>()
    private val draftLocks = ConcurrentHashMap<String, Mutex>()
    private val draftSideLocks = ConcurrentHashMap<String, Mutex>()

    private fun draftSideKey(runId: String, sideIndex: Int) = "$runId:$sideIndex"
    private fun draftLock(runId: String) = draftLocks.computeIfAbsent(runId) { Mutex() }

    /** Begin a capture write before launching any asynchronous camera/file work. */
    fun beginCaptureDraftSideWrite(runId: String, sideIndex: Int): Long =
        draftGenerations.computeIfAbsent(draftSideKey(runId, sideIndex)) { AtomicLong() }.incrementAndGet()

    /** Invalidate an in-flight capture before retake/remove is launched. */
    fun invalidateCaptureDraftSide(runId: String, sideIndex: Int): Long =
        beginCaptureDraftSideWrite(runId, sideIndex)

    private fun isCurrentDraftGeneration(runId: String, sideIndex: Int, generation: Long): Boolean =
        draftGenerations[draftSideKey(runId, sideIndex)]?.get() == generation

    /** Check a capture callback before it publishes UI state for a shared draft path. */
    fun isCaptureDraftSideWriteCurrent(runId: String, sideIndex: Int, generation: Long): Boolean =
        isCurrentDraftGeneration(runId, sideIndex, generation)

    private fun draftSideLock(runId: String, sideIndex: Int) =
        draftSideLocks.computeIfAbsent(draftSideKey(runId, sideIndex)) { kotlinx.coroutines.sync.Mutex() }

    init {
        // Recovery is deliberately asynchronous and never blocks app startup. It uses the same
        // coordinator as save/delete so a journal can never restore bytes while a tree is being
        // removed or another revision is being published.
        mirrorWorkScheduler.enqueue {
            artifactCoordinator.withExclusiveAccess { recoverPendingLocalRevisionsLocked() }
        }
        mirrorWorkScheduler.enqueue { reconcileMirrorQueue() }
    }

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

    /**
     * Create (or fold into) a run.
     *
     * WS-12/WS-13: [identity] and [operatorName] are minted/entered by the caller and applied to
     * the resolved run — including an EXISTING one. Writing them only on INSERT was the defect
     * that made both workstreams inert in the field: the collection tablet already holds a
     * `DAMIMAS__A21B` run (folder resume, or a row migrated from v6), so the branch that stores
     * the identity would never have run and every package would still have shipped with a blank
     * capture set, a blank device token and `operator: UNKNOWN`.
     *
     * What may be adopted onto an existing run, and what may not:
     *  - `captureSetId`/`deviceToken` — adopted only while the run has NONE. A run that already
     *    carries an identity keeps it, so the trees it has written stay attributable.
     *  - `operatorName` — adopted whenever a non-blank name is supplied. Already-committed trees
     *    froze their own operator at commit time and are untouched; this only labels what is
     *    captured from now on, which is correct when a different person continues a block.
     *  - `nameToken` — adopted only when the run has written nothing at all
     *    ([CaptureSetPolicy.resolveNameToken]). A started run keeps its filename format.
     */
    suspend fun createRun(
        variety: String,
        block: String,
        sideCount: Int,
        autoId: Boolean,
        identity: CaptureSetIdentity = CaptureSetIdentity.UNKNOWN,
        operatorName: String = "",
    ): String =
        withContext(Dispatchers.IO) {
            val groupKey = groupKeyFor(variety, block)
            // C-01: one run per variety+block. Re-using the same block must fold into the SAME run
            // (so nextId keeps counting and treeName can never collide across runs), never spawn a
            // parallel run. Mirrors FolderResumeImporter's groupKey reuse. When an existing run is
            // reused, its sideCount/autoId are kept (the dialog's values for this call are ignored).
            // Query + insert are one Room transaction. Without this, two fast taps can both miss
            // the row; INSERT OR REPLACE on the unique groupKey then deletes the first run and
            // cascades all of its trees. Plain INSERT is an additional fail-closed backstop.
            db.withTransaction {
                val existing = sessionDao.getByGroupKey(groupKey)
                if (existing != null) {
                    adoptRunProvenanceLocked(existing, identity, operatorName)
                    existing.sessionId
                } else {
                    val id = UUID.randomUUID().toString()
                    val now = System.currentTimeMillis()
                    sessionDao.insert(
                        SessionEntity(
                            sessionId = id, variety = variety.trim(), block = block.trim(),
                            groupKey = groupKey,
                            sideCount = sideCount.coerceAtLeast(2), autoId = autoId, nextId = 1,
                            createdAt = now, updatedAt = now,
                            captureSetId = identity.captureSetId,
                            deviceToken = identity.deviceToken,
                            nameToken = identity.nameToken,
                            operatorName = operatorName.trim(),
                        )
                    )
                    id
                }
            }
        }

    /**
     * Apply the adoption rules above to an existing run row. Runs inside `createRun`'s
     * transaction; writes nothing when there is nothing to change, so re-opening a fully
     * provisioned run does not churn `updatedAt` (which orders the home list).
     */
    private suspend fun adoptRunProvenanceLocked(
        existing: SessionEntity,
        identity: CaptureSetIdentity,
        operatorName: String,
    ) {
        // "Started" = anything already written under this run's naming. A capture draft counts:
        // its expectedTreeName is pinned (ensureCaptureDraftLocked never rewrites a non-blank
        // one) and commitTreePackage refuses a treeName that disagrees with it, so changing the
        // token underneath an in-flight capture would make that capture uncommittable.
        val runHasStarted = treeDao.getBySession(existing.sessionId).isNotEmpty() ||
            captureDraftDao.get(existing.sessionId)?.expectedTreeName?.isNotBlank() == true
        val adoptIdentity = existing.captureSetId.isBlank() && identity.isKnown
        val resolvedToken = CaptureSetPolicy.resolveNameToken(
            existingToken = existing.nameToken,
            runHasStarted = runHasStarted,
            requestedToken = identity.nameToken,
        )
        val trimmedOperator = operatorName.trim()
        val updated = existing.copy(
            captureSetId = if (adoptIdentity) identity.captureSetId else existing.captureSetId,
            deviceToken = if (adoptIdentity) identity.deviceToken else existing.deviceToken,
            nameToken = resolvedToken,
            operatorName = if (trimmedOperator.isNotEmpty()) trimmedOperator else existing.operatorName,
        )
        if (updated != existing) {
            sessionDao.update(updated.copy(updatedAt = System.currentTimeMillis()))
        }
    }

    suspend fun deleteRun(sessionId: String, safTreeUri: Uri? = null) =
        artifactCoordinator.withExclusiveAccess {
            withContext(Dispatchers.IO) {
                val trees = treeDao.getBySession(sessionId)
                trees.forEach { tree ->
                    ArtifactIdentityPolicy.treeNameError(tree.treeName)?.let {
                        throw IllegalArgumentException(
                            "Refusing to delete run containing an unsafe tree identity: $it",
                        )
                    }
                }
                // Remove unpublished revision state before deleting the Room rows. Otherwise a
                // process death between the two operations lets startup recovery resurrect a tree
                // that the operator intentionally deleted.
                trees.forEach { tree ->
                    check(storage.discardPendingRevisions(tree.treeName)) {
                        "Cannot discard pending local revisions for ${tree.treeName}"
                    }
                }
                val tombstones = trees.mapNotNull { deletionTombstone(it, safTreeUri) }
                // The tombstones and Room deletion are one DB commit. A crash after this point can
                // leave only local orphans and a durable remote-delete intent, never a resurrected
                // mirror job with no knowledge that the tree was intentionally deleted.
                db.withTransaction {
                    tombstones.forEach { mirrorDeletionDao.upsert(it) }
                    sessionDao.deleteById(sessionId) // cascades trees/sides/bboxes/links/mirror status
                }
                // Local deletion is deterministic. Remote deletion is queued and never joined while
                // ArtifactCoordinator is held; reconciliation retries any task lost at process death.
                for (tree in trees) {
                    deleteTreeArtifacts(tree, tombstones.firstOrNull { it.treeKey == tree.treeKey })
                }
            }
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
    suspend fun commitTreePackage(
        sessionId: String,
        treeName: String,
        treeId: Int,
        split: String,
        sides: List<TreeSide>,
        metadata: TreeMetadata?,
        stagingDir: File,
        requiredDepthSides: Set<Int>,
        confirmedLinks: List<CrossSideLink> = emptyList(),
        safTreeUri: Uri? = null,
    ): String {
        ArtifactIdentityPolicy.treeNameError(treeName)?.let {
            throw IllegalArgumentException("Invalid tree name: $it")
        }
        ArtifactIdentityPolicy.sideSetError(sides.map { it.sideIndex })?.let {
            throw IllegalArgumentException("Invalid capture package: $it")
        }
        // SAF collision probing is advisory and deliberately outside ArtifactCoordinator. A slow
        // or revoked DocumentsProvider must never block local Room/filesystem commits. The local
        // tree-name uniqueness check below remains the definitive in-process identity guard.
        if (safTreeUri != null) {
            val remoteCollision = withContext(Dispatchers.IO) {
                runCatching {
                    // One batched probe, not one probe per path: existsAll queries each distinct
                    // directory freshly exactly once. The verdict is read back in probe order so
                    // the reported colliding path is the same one the per-path loop reported.
                    val probePaths = ArtifactIdentityPolicy.collisionProbePaths(
                        treeName,
                        sides.map { it.sideIndex },
                    )
                    val states = saf.existsAll(safTreeUri, probePaths)
                    probePaths.firstOrNull { states[it] == SafPathState.Present }
                }.getOrNull()
            }
            check(remoteCollision == null) {
                "Tree name already exists in the export folder: $treeName"
            }
        }
        return artifactCoordinator.withExclusiveAccess {
        withContext(Dispatchers.IO) {
        val run = sessionDao.getById(sessionId) ?: throw IllegalStateException("Run not found")
        val now = System.currentTimeMillis()
        val treeKey = UUID.randomUUID().toString()
        ArtifactIdentityPolicy.treeNameError(treeName)?.let {
            throw IllegalArgumentException("Invalid tree name: $it")
        }
        ArtifactIdentityPolicy.sideSetError(sides.map { it.sideIndex })?.let {
            throw IllegalArgumentException("Invalid capture package: $it")
        }
        val captureDraft = captureDraftDao.get(sessionId)
        if (captureDraft != null && captureDraft.expectedTreeName.isNotBlank()) {
            check(captureDraft.expectedTreeName == treeName) {
                "Capture draft belongs to ${captureDraft.expectedTreeName}, not $treeName"
            }
            val draftSides = captureDraftDao.getSides(sessionId).associateBy { it.sideIndex }
            for (side in sides) {
                val draftSide = draftSides[side.sideIndex]
                    ?: throw IllegalStateException("Capture draft is missing side ${side.sideIndex + 1}")
                check(File(draftSide.imagePath).isFile &&
                    DepthArtifactContract.sha256Hex(File(draftSide.imagePath)).equals(side.rgbSha256, ignoreCase = true)) {
                    "Capture draft RGB identity mismatch for side ${side.sideIndex + 1}"
                }
            }
        }
        check(sides.none { it.captureOrigin == CaptureOrigin.UNKNOWN }) {
            "Capture package contains unresolved camera provenance"
        }
        if (treeDao.getByName(treeName) != null) {
            throw IllegalStateException("Tree name already exists: $treeName")
        }
        val declaredRequiredDepth = sides
            .filter { it.depthRequired || it.captureOrigin == CaptureOrigin.ORBBEC }
            .mapTo(mutableSetOf()) { it.sideIndex }
        check(declaredRequiredDepth == requiredDepthSides) {
            "Depth requirement set does not match side provenance"
        }
        validateConfirmedLinks(sides, confirmedLinks)
        for (side in sides) {
            val stagedImage = storage.stagedImageFile(stagingDir, treeName, side.sideIndex)
            check(stagedImage.isFile) { "Side ${side.sideIndex + 1}: staged RGB is missing" }
            check(
                side.rgbSha256.equals(
                    DepthArtifactContract.sha256Hex(stagedImage),
                    ignoreCase = true,
                )
            ) {
                "Side ${side.sideIndex + 1}: staged RGB identity mismatch"
            }
            val hasStagedDepth =
                storage.stagedDepthRawFile(stagingDir, treeName, side.sideIndex).exists() ||
                    storage.stagedDepthJsonFile(stagingDir, treeName, side.sideIndex).exists()
            check(side.captureOrigin != CaptureOrigin.PHONE_CAMERA || !hasStagedDepth) {
                "Side ${side.sideIndex + 1}: tablet capture must not contain depth"
            }
            if (side.captureOrigin == CaptureOrigin.ORBBEC) {
                val stagedDepthJson =
                    storage.stagedDepthJsonFile(stagingDir, treeName, side.sideIndex)
                check(
                    stagedDepthJson.isFile &&
                        DepthArtifactContract.hasContentBindings(stagedDepthJson.readText())
                ) {
                    "Side ${side.sideIndex + 1}: Orbbec depth lacks RGB/raw content binding"
                }
            }
        }

        // No committed row owns this name, so every canonical same-name file is an orphan from a
        // failed/deleted attempt. Clear it before publication; otherwise an old Output JSON or an
        // extra old side could coexist with the newly committed capture.
        storage.clearUncommittedTreeArtifacts(treeName)

        // The definitive local name check, canonical-file publication, and Room commit share one
        // process-wide lock. The remote probe above is advisory and already outside this section.
        storage.publishCapturePackage(
            stagingDir = stagingDir,
            treeName = treeName,
            sideIndices = sides.map { it.sideIndex },
            requiredDepthSides = requiredDepthSides,
        )

        // WS-12/WS-13: resolve the provenance that will be frozen into BOTH the sidecar and the
        // Room row, so the two can never disagree.
        //  - identity: the tree's own (a resumed package carries the capturing device's) falling
        //    back to this run's. Never re-stamped with the local device on resume.
        //  - GPS: stored verbatim. Freshness is judged by the CAPTURE screen against its own
        //    commit instant (CaptureFlowViewModel.save). Re-judging it here would also re-judge a
        //    package being resumed days later and turn a genuinely fresh historical fix into
        //    STALE — the resume is not a new measurement.
        //  - operator: tree, then run. Empty stays empty here; it becomes "UNKNOWN" only at the
        //    output boundary, so an unset operator is never confused with someone named UNKNOWN.
        //  - captureDate: never defaulted to today. An unknown capture day stays unknown rather
        //    than being relabelled with the day the folder happened to be resumed.
        val committedIdentity = metadata?.identity?.takeIf { it.isKnown }
            ?: CaptureSetIdentity(run.captureSetId, run.deviceToken, run.nameToken)
        val committedGps = metadata?.gps ?: GpsProvenance.UNKNOWN
        val committedOperator = (metadata?.operatorName?.trim()?.takeIf { it.isNotEmpty() }
            ?: run.operatorName.trim())
        val captureDate = metadata?.date?.trim().orEmpty()

        // Prepare every synchronous local sidecar before Room exposes the tree. The DB row is
        // the commit marker, so failed/partial files can never become an exportable tree.
        val metaJson = buildMetadataJson(
            metadata, treeName, run, treeId, sides,
            identity = committedIdentity,
            gps = committedGps,
            operatorName = committedOperator,
            captureDate = captureDate,
            atMillis = now,
        ).toString(2)
        storage.writeText(storage.metadataFile(treeName), metaJson)
        writeLocalArtifacts(treeName, split, sides)

        // Tree row + its sides in ONE transaction so a concurrent reader can never observe
        // a tree that has no sides yet (which a racing save could then persist as the truth).
        // The nextId advance MUST also be inside this transaction: if the app crashes after
        // the tree is committed but before nextId is updated, the next "Add Tree" would
        // reuse the same id and create a duplicate.
        val nextId = maxOf(run.nextId, treeId + 1)
        val initialMirror = safTreeUri?.let { remoteUri ->
            val hash = DepthArtifactContract.sha256Hex(
                sides.sortedBy { it.sideIndex }
                    .joinToString("|") { "${it.sideIndex}:${it.rgbSha256}" }
                    .toByteArray(),
            )
            MirrorStatusEntity(
                treeKey = treeKey,
                treeName = treeName,
                remoteUri = remoteUri.toString(),
                requestedRevision = 0L,
                requestedHash = hash,
                status = MirrorStates.PENDING,
                updatedAt = now,
            )
        }
        val declaredSideCount = maxOf(
            run.sideCount,
            (sides.maxOfOrNull { it.sideIndex } ?: -1) + 1,
        )
        db.withTransaction {
            treeDao.insert(
                TreeEntity(
                    treeKey = treeKey, sessionId = sessionId, treeName = treeName, treeId = treeId,
                    split = split, sideCount = declaredSideCount,
                    variety = metadata?.variety ?: run.variety, block = metadata?.block ?: run.block,
                    createdAt = now, updatedAt = now,
                    // WS-12/WS-13: identity and provenance are frozen with the capture. A resumed
                    // tree keeps the identity recorded in ITS sidecar (metadata.identity), which
                    // is why the run's identity is only the fallback for a fresh local capture.
                    captureSetId = committedIdentity.captureSetId,
                    deviceToken = committedIdentity.deviceToken,
                    nameToken = committedIdentity.nameToken,
                    captureDate = captureDate,
                    operatorName = committedOperator,
                    gpsStatus = committedGps.status.name,
                    gpsLatitude = committedGps.latitude,
                    gpsLongitude = committedGps.longitude,
                    gpsAccuracyM = committedGps.accuracyM?.toDouble(),
                    gpsFixTimeMillis = committedGps.fixTimeMillis,
                    gpsAgeMs = committedGps.ageMs,
                    gpsProvider = committedGps.provider,
                    gpsSource = committedGps.source.name,
                )
            )
            persistSidesDb(treeKey, sides)
            linkDao.insertAll(confirmedLinks.map {
                ConfirmedLinkEntity(
                    treeKey = treeKey,
                    linkId = it.linkId,
                    sideA = it.sideA,
                    bboxIdA = it.bboxIdA,
                    sideB = it.sideB,
                    bboxIdB = it.bboxIdB,
                )
            })
            initialMirror?.let { mirrorDao.upsert(it) }
            // Advance the tree-id counter. MUST be an UPDATE, not upsert(REPLACE): replacing
            // the existing run row cascade-deletes the tree we just inserted (FK onDelete=CASCADE).
            sessionDao.update(run.copy(nextId = nextId, updatedAt = now))
        }

        // The local Room/filesystem commit is complete before the non-blocking mirror worker is
        // launched. Its queue row was committed atomically with the tree. A draft is deleted only
        // after that local commit succeeded.
        val draftAfterCommit = captureDraftDao.get(sessionId)
        if (draftAfterCommit == null || draftAfterCommit.expectedTreeName.isBlank() || draftAfterCommit.expectedTreeName == treeName) {
            captureDraftDao.delete(sessionId)
            storage.discardUncommittedDraft(sessionId)
        }
        // The queue row was committed atomically with the tree. Only the worker launch is
        // best-effort; restart reconciliation will pick up PENDING if the process dies here.
        initialMirror?.let { queued ->
            mirrorWorkScheduler.enqueue { runInitialMirrorJob(queued, run.sideCount, sides, split, metaJson) }
        }

        treeKey
        }
        }
    }

    suspend fun deleteTree(treeKey: String, safTreeUri: Uri? = null) =
        artifactCoordinator.withExclusiveAccess {
            withContext(Dispatchers.IO) {
                val tree = treeDao.getByKey(treeKey) ?: return@withContext
                ArtifactIdentityPolicy.treeNameError(tree.treeName)?.let {
                    throw IllegalArgumentException("Refusing to delete unsafe tree identity: $it")
                }
                check(storage.discardPendingRevisions(tree.treeName)) {
                    "Cannot discard pending local revisions for ${tree.treeName}"
                }
                val tombstone = deletionTombstone(tree, safTreeUri)
                db.withTransaction {
                    tombstone?.let { mirrorDeletionDao.upsert(it) }
                    treeDao.deleteByKey(treeKey) // cascades sides/bboxes/links/mirror status
                }
                deleteTreeArtifacts(tree, tombstone)
                // Recompute the run's nextId from survivors (frees the lowest id, like JS).
                val run = sessionDao.getById(tree.sessionId)
                if (run != null) {
                    val survivors = treeDao.getBySession(tree.sessionId)
                    val maxId = survivors.maxOfOrNull { it.treeId } ?: 0
                    sessionDao.update(
                        run.copy(
                            nextId = maxId + 1,
                            updatedAt = System.currentTimeMillis(),
                        )
                    )
                }
            }
        }

    /** Capture the remote identity before the tree's FK-cascaded mirror row disappears. */
    private suspend fun deletionTombstone(
        tree: TreeEntity,
        explicitRemoteUri: Uri?,
    ): MirrorDeletionEntity? {
        // The persisted mirror URI identifies where the package actually exists. The caller's
        // current export-folder URI may have changed since the last mirror, so it is only a
        // fallback for trees that were never mirrored.
        val persistedRemoteUri = mirrorDao.getByTree(tree.treeKey)?.remoteUri
        val remote = persistedRemoteUri ?: explicitRemoteUri?.toString()
        return remote?.let {
            MirrorDeletionEntity(
                treeKey = tree.treeKey,
                treeName = tree.treeName,
                remoteUri = it,
                sideCount = tree.sideCount,
                status = MirrorStates.DELETE_PENDING,
                updatedAt = System.currentTimeMillis(),
            )
        }
    }

    private suspend fun deleteTreeArtifacts(tree: TreeEntity, tombstone: MirrorDeletionEntity?) {
        ArtifactIdentityPolicy.treeNameError(tree.treeName)?.let {
            throw IllegalArgumentException("Refusing to delete unsafe tree identity: $it")
        }
        storage.deleteTree(tree.treeName, tree.sideCount)
        // Do not join: the queue may already contain a mirror task that is waiting for this lock.
        // The tombstone makes that older task a no-op and gives restart reconciliation a durable
        // delete request if this enqueue is lost with the process.
        tombstone?.let { queued ->
            mirrorWorkScheduler.enqueue { runDeleteMirrorJob(queued) }
        }
    }

    /**
     * The metadata sidecar.
     *
     * WS-12/WS-13 additions are strictly additive — `artifactSchemaVersion` stays 1 because the
     * `artifacts` block it versions is unchanged, and every historical key keeps its name AND its
     * population rule. New readers get `captureSet`, `gps` and `captureDate`; old readers see the
     * same document they always did.
     *
     * Top-level `lat`/`lng` are written for ANY recorded coordinate, exactly as before. Gating
     * them on freshness was tried and rejected: the freshness window is 60 s while one tree takes
     * minutes, so the keys would have vanished from essentially every new package — and folder
     * resume rewrites this file, so they would also have been stripped from the already-collected
     * 42/90-tree sidecars. The 27 Jul complaint was that nothing in the data said the fix was one
     * stale last-known reading; `gps.status`/`ageMs`/`source` say it, without removing anything.
     */
    private fun buildMetadataJson(
        metadata: TreeMetadata?,
        treeName: String,
        run: SessionEntity,
        treeId: Int,
        sides: List<TreeSide>,
        identity: CaptureSetIdentity = CaptureSetIdentity.UNKNOWN,
        gps: GpsProvenance = GpsProvenance.UNKNOWN,
        operatorName: String = "",
        captureDate: String = "",
        atMillis: Long = System.currentTimeMillis(),
    ): JSONObject {
        val ts = ISO_FORMAT.format(Date(atMillis))
        return JSONObject().apply {
            put("artifactSchemaVersion", 1)
            put("name", treeName)
            put("variety", metadata?.variety ?: run.variety)
            put("blok", metadata?.block ?: run.block)
            put("treeId", treeId)
            put("operator", PackageProvenanceCodec.operatorForOutput(operatorName))
            put("timestamp", ts)
            // Empty means "not recorded". Never back-filled with the commit/export day.
            put("captureDate", captureDate)
            put("captureSet", PackageProvenanceCodec.identityJson(identity))
            put("gps", PackageProvenanceCodec.gpsJson(gps))
            // Legacy coordinate keys: unchanged population rule (see the KDoc above). A resumed
            // legacy package therefore keeps the exact lat/lng it arrived with.
            gps.recordedCoordinates?.let { (lat, lng) -> put("lat", lat); put("lng", lng) }
            put("artifacts", JSONObject().apply {
                put("sides", JSONArray().apply {
                    for (side in sides.sortedBy { it.sideIndex }) {
                        put(JSONObject().apply {
                            put("sideIndex", side.sideIndex)
                            put("filename", "${treeName}_${side.sideIndex + 1}.jpg")
                            put("rgbSha256", side.rgbSha256)
                            put("captureOrigin", side.captureOrigin.name)
                            put("depthRequired", side.depthRequired)
                        })
                    }
                })
            })
        }
    }

    // ─── Load / save one tree as an ActiveSession ───────────────────────────────

    suspend fun loadActiveSession(treeKey: String): ActiveSession? =
        artifactCoordinator.withExclusiveAccess { loadActiveSessionWhileExclusive(treeKey) }

    /** Caller must already hold [ArtifactCoordinator]. Used by the ZIP exporter. */
    internal suspend fun recoverLocalArtifactsWhileExclusive() = recoverPendingLocalRevisionsLocked()

    /** Caller must already hold [ArtifactCoordinator]. Used by the ZIP exporter. */
    internal suspend fun loadActiveSessionWhileExclusive(treeKey: String): ActiveSession? =
        withContext(Dispatchers.IO) {
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
                    rgbSha256 = se.rgbSha256,
                    captureOrigin = CaptureOrigin.fromPersisted(se.captureOrigin),
                    depthRequired = se.depthRequired,
                )
            }
            // createOrNull (not create): a degenerate persisted link — e.g. a self-link from
            // legacy/corrupt data — must skip that row, never crash opening the tree.
            val links = linkDao.getByTree(treeKey).mapNotNull {
                CrossSideLink.createOrNull(it.linkId, it.sideA, it.bboxIdA, it.sideB, it.bboxIdB)
            }
            ActiveSession(
                sessionId = treeKey, treeName = tree.treeName, split = tree.split,
                sides = sides, suggestedLinks = emptyList(), confirmedLinks = links,
                // WS-12/WS-13: rebuild the FULL provenance, not just variety/block. Everything
                // downstream (Output JSON, re-export, ZIP descriptor) reads it from here, so
                // dropping it on load is how a round-trip silently erases it.
                metadata = tree.toTreeMetadata(),
                revision = tree.revision,
                createdAt = tree.createdAt, updatedAt = tree.updatedAt,
            )
        }

    /** Write-through save with an optimistic revision check and journaled local publication. */
    suspend fun saveSession(session: ActiveSession, safTreeUri: Uri? = null): SaveResult =
        commitAnnotationRevision(session, safTreeUri, markComplete = false)

    /** Caller must already hold [ArtifactCoordinator]. Used by export materialization. */
    internal suspend fun saveSessionWhileExclusive(
        session: ActiveSession,
        safTreeUri: Uri? = null,
    ): SaveResult = commitAnnotationRevisionLocked(session, safTreeUri, markComplete = false)

    private suspend fun commitAnnotationRevision(
        session: ActiveSession,
        safTreeUri: Uri?,
        markComplete: Boolean,
    ): SaveResult = artifactCoordinator.withExclusiveAccess {
        commitAnnotationRevisionLocked(session, safTreeUri, markComplete)
    }

    private suspend fun commitAnnotationRevisionLocked(
        session: ActiveSession,
        safTreeUri: Uri?,
        markComplete: Boolean,
    ): SaveResult = withContext(Dispatchers.IO) {
            recoverPendingLocalRevisionsLocked()
            val tree = treeDao.getByKey(session.sessionId)
                ?: return@withContext SaveResult.Failure("Committed tree no longer exists")
            check(session.treeName == tree.treeName) { "Session identity does not match its committed tree" }
            // This compare-and-set is intentionally before checksum reads, manifest invalidation,
            // staging, and every other file operation. A stale screen cannot touch newer bytes.
            if (session.revision != tree.revision) {
                return@withContext SaveResult.Conflict(session.revision, tree.revision)
            }
            val verifiedSession = runCatching { validateSessionCapture(session) }
                .getOrElse { return@withContext SaveResult.Failure(it.message ?: "Capture validation failed", it) }
            validateConfirmedLinks(verifiedSession.sides, verifiedSession.confirmedLinks)
            val newRevision = tree.revision + 1L
            val stage = storage.revisionStagingDir(tree.treeName, newRevision)
            val now = System.currentTimeMillis()
            try {
                prepareRevisionStage(verifiedSession, tree, stage)
                val stagedEntries = revisionEntries(tree.treeName, verifiedSession.sides, stage)
                // Persist the journal and old-file backups before Room changes its revision token.
                // A process death between these resources is therefore recoverable either by
                // rollback (Room stayed old) or by finishing publication (Room is new).
                storage.prepareRevisionJournal(tree.treeName, newRevision, stagedEntries)
                val stagedRevisionHash = DepthArtifactContract.sha256Hex(
                    File(stage, "manifests/${tree.treeName}.json").readBytes(),
                )
                val previousMirror = mirrorDao.getByTree(tree.treeKey)
                val requestedRemote = safTreeUri?.toString() ?: previousMirror?.remoteUri
                val mirrorStatus = MirrorStatusEntity(
                    treeKey = tree.treeKey,
                    treeName = tree.treeName,
                    remoteUri = requestedRemote,
                    requestedRevision = newRevision,
                    requestedHash = stagedRevisionHash,
                    status = if (requestedRemote != null) MirrorStates.PENDING else MirrorStates.NOT_REQUESTED,
                    updatedAt = now,
                )
                db.withTransaction {
                    val current = treeDao.getByKey(tree.treeKey)
                        ?: throw IllegalStateException("Tree disappeared during save")
                    if (current.revision != session.revision) {
                        throw RevisionConflictException(session.revision, current.revision)
                    }
                    val existingSideIndices = sideDao.getByTree(tree.treeKey).mapTo(mutableSetOf()) { it.sideIndex }
                    val incomingSideIndices = verifiedSession.sides.mapTo(mutableSetOf()) { it.sideIndex }
                    check(incomingSideIndices.containsAll(existingSideIndices)) {
                        "Refusing to save a snapshot that omits committed sides"
                    }
                    treeDao.update(
                        current.copy(
                            revision = newRevision,
                            isComplete = if (markComplete) true else current.isComplete,
                            updatedAt = now,
                            sideCount = maxOf(current.sideCount, (verifiedSession.sides.maxOfOrNull { it.sideIndex } ?: -1) + 1),
                        ),
                    )
                    persistSidesDb(tree.treeKey, verifiedSession.sides)
                    linkDao.deleteByTree(tree.treeKey)
                    linkDao.insertAll(verifiedSession.confirmedLinks.map {
                        ConfirmedLinkEntity(treeKey = tree.treeKey, linkId = it.linkId, sideA = it.sideA,
                            bboxIdA = it.bboxIdA, sideB = it.sideB, bboxIdB = it.bboxIdB)
                    })
                    // Revision and mirror intent commit together. A process death here leaves a
                    // durable PENDING row instead of a newer tree that still says VERIFIED.
                    mirrorDao.upsert(mirrorStatus)
                }
                storage.publishRevision(tree.treeName, newRevision, stagedEntries)
                // The queue row is already durable. Launching this worker is best-effort; startup
                // reconciliation retries it when the process dies before launch.
                if (mirrorStatus.status == MirrorStates.PENDING) {
                    mirrorWorkScheduler.enqueue { runMirrorJob(mirrorStatus) }
                }
                SaveResult.Success(newRevision)
            } catch (conflict: RevisionConflictException) {
                runCatching { stage.deleteRecursively() }
                SaveResult.Conflict(conflict.expected, conflict.actual)
            } catch (failure: Exception) {
                // Keep the journal/stage if publication was interrupted; startup can retry. The
                // publish method restores the old canonical package on a normal write failure.
                SaveResult.Failure(failure.message ?: "Local revision save failed", failure)
            }
        }

    private class RevisionConflictException(val expected: Long, val actual: Long) : Exception()

    /** Committed row → the provenance record every export path reads. */
    private fun TreeEntity.toTreeMetadata(): TreeMetadata = TreeMetadata(
        variety = variety,
        block = block,
        treeId = treeId.toString(),
        date = captureDate,
        gps = GpsProvenance(
            status = GpsStatus.fromPersisted(gpsStatus),
            latitude = gpsLatitude,
            longitude = gpsLongitude,
            accuracyM = gpsAccuracyM?.toFloat(),
            fixTimeMillis = gpsFixTimeMillis,
            ageMs = gpsAgeMs,
            provider = gpsProvider,
            source = GpsSource.fromPersisted(gpsSource),
        ),
        operatorName = operatorName,
        identity = CaptureSetIdentity(captureSetId, deviceToken, nameToken),
    )

    /** Stage all local text artifacts; RGB/depth remain the already-validated capture set. */
    private fun prepareRevisionStage(session: ActiveSession, tree: TreeEntity, stage: File) {
        writeLocalArtifacts(session.treeName, session.split, session.sides, stage)
        val outputText = ExportManager.generateOutputJson(session).toString(2)
        storage.writeText(File(stage, "Output JSON/${session.treeName}.json"), outputText)
        // The committed sidecar is the capture-time record; re-saving annotations must never
        // rewrite it (that would restamp the capture date and GPS with the edit instant). It is
        // only rebuilt when the file is genuinely absent, and then from the tree's OWN stored
        // provenance — not from "now".
        val metadataSource = storage.metadataFile(session.treeName)
        val metadataText = storage.readText(metadataSource) ?: buildMetadataJson(
            session.metadata,
            session.treeName,
            SessionEntity("", session.metadata?.variety ?: tree.variety, session.metadata?.block ?: tree.block,
                "", tree.sideCount, true, tree.treeId + 1, tree.createdAt, tree.updatedAt),
            tree.treeId,
            session.sides,
            identity = CaptureSetIdentity(tree.captureSetId, tree.deviceToken, tree.nameToken),
            gps = session.metadata?.gps ?: GpsProvenance.UNKNOWN,
            operatorName = session.metadata?.operatorName?.takeIf { it.isNotBlank() } ?: tree.operatorName,
            captureDate = session.metadata?.date?.takeIf { it.isNotBlank() } ?: tree.captureDate,
            atMillis = tree.createdAt,
        ).toString(2)
        storage.writeText(File(stage, "metadata/${session.treeName}.json"), metadataText)
        TreePackageManifest.materializeAt(
            storage,
            session.treeName,
            session.sides,
            stage,
            CaptureSetIdentity(tree.captureSetId, tree.deviceToken, tree.nameToken),
        )
    }

    private fun revisionEntries(treeName: String, sides: List<TreeSide>, stage: File): List<Pair<File, File>> {
        val entries = ArrayList<Pair<File, File>>()
        sides.forEach { side ->
            entries += File(stage, "labels/field/${treeName}_${side.sideIndex + 1}.txt") to labelFile(treeName, side.sideIndex)
            entries += File(stage, "annotlog/field/${treeName}_${side.sideIndex + 1}.json") to storage.annotLogFile(treeName, side.sideIndex)
        }
        entries += File(stage, "Output JSON/$treeName.json") to storage.outputJsonFile(treeName)
        entries += File(stage, "metadata/$treeName.json") to storage.metadataFile(treeName)
        entries += File(stage, "manifests/$treeName.json") to storage.manifestFile(treeName)
        return entries
    }

    private fun labelFile(treeName: String, sideIndex: Int): File = storage.labelFile(treeName, sideIndex)

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
                    rgbSha256 = side.rgbSha256,
                    captureOrigin = side.captureOrigin.name,
                    depthRequired = side.depthRequired,
                )
            )
            bboxDao.insertAll(side.bboxes.map { it.toEntity(sideId) })
        }
    }

    /** Source-of-truth local files (YOLO label + annot-log). Synchronous, fast. */
    private fun writeLocalArtifacts(
        treeName: String,
        split: String,
        sides: List<TreeSide>,
        root: File = storage.rootDir,
    ) {
        for (side in sides) {
            if (side.imageWidth > 0 && side.imageHeight > 0) {
                val yoloText = YoloParser.serialize(side.bboxes, side.imageWidth, side.imageHeight)
                val label = if (root == storage.rootDir) storage.labelFile(treeName, side.sideIndex)
                else File(root, "labels/field/${treeName}_${side.sideIndex + 1}.txt")
                storage.writeText(label, yoloText)
            }
            val annotLog = if (root == storage.rootDir) storage.annotLogFile(treeName, side.sideIndex)
            else File(root, "annotlog/field/${treeName}_${side.sideIndex + 1}.json")
            storage.writeText(annotLog, buildAnnotLog(treeName, split, side))
        }
    }

    /**
     * Resolve legacy provenance conservatively and verify that a mutable annotation session still
     * points at the exact committed RGB/depth bytes before changing DB or sidecars.
     */
    private fun validateSessionCapture(session: ActiveSession): ActiveSession {
        ArtifactIdentityPolicy.treeNameError(session.treeName)?.let {
            throw IllegalArgumentException("Invalid tree name: $it")
        }
        ArtifactIdentityPolicy.sideSetError(session.sides.map { it.sideIndex })?.let {
            throw IllegalArgumentException("Invalid committed side set: $it")
        }
        val verifiedSides = session.sides.map { side ->
            val image = storage.imageFile(session.treeName, side.sideIndex)
            check(image.isFile && image.length() > 0L) {
                "Side ${side.sideIndex + 1}: committed RGB is missing"
            }
            val actualRgbSha256 = DepthArtifactContract.sha256Hex(image)
            if (side.rgbSha256.isNotBlank()) {
                check(side.rgbSha256.equals(actualRgbSha256, ignoreCase = true)) {
                    "Side ${side.sideIndex + 1}: committed RGB checksum mismatch"
                }
            }
            val raw = storage.depthRawFile(session.treeName, side.sideIndex)
            val json = storage.depthJsonFile(session.treeName, side.sideIndex)
            val hasAnyDepth = raw.exists() || json.exists()
            val hasValidDepth = storage.hasValidDepthPair(session.treeName, side.sideIndex)
            val hasVerifiedDepthBinding =
                hasValidDepth &&
                    storage.depthMetadataHasContentBindings(session.treeName, side.sideIndex)
            val decision = CaptureIntegrityPolicy.evaluate(
                storedOrigin = side.captureOrigin,
                declaredDepthRequired = side.depthRequired,
                hasAnyDepth = hasAnyDepth,
                hasValidDepth = hasValidDepth,
                hasVerifiedDepthBinding = hasVerifiedDepthBinding,
                rejectUnverifiedLegacy = false,
            )
            check(decision.error == null) {
                "Side ${side.sideIndex + 1}: ${decision.error}"
            }
            side.copy(
                imageUri = storage.versionedImageUri(image, actualRgbSha256),
                rgbSha256 = actualRgbSha256,
                captureOrigin = decision.captureOrigin,
                depthRequired = decision.depthRequired,
            )
        }
        return session.copy(sides = verifiedSides)
    }

    /** Final/export actions read every mirrored byte back before reporting success. */
    private fun verifySafPackageReadBack(
        session: ActiveSession,
        safTreeUri: Uri,
        outputJsonText: String,
        manifestText: String,
        metadataText: String,
    ): Boolean {
        return runCatching {
        fun remoteMatches(path: String, expectedSha256: String): Boolean {
            val bytes = saf.readBytes(safTreeUri, path) ?: return false
            return DepthArtifactContract.sha256Hex(bytes)
                .equals(expectedSha256, ignoreCase = true)
        }

        for (side in session.sides) {
            val stem = "${session.treeName}_${side.sideIndex + 1}"
            if (!remoteMatches("dataset/images/field/$stem.jpg", side.rgbSha256)) return false

            val localLabel = storage.labelFile(session.treeName, side.sideIndex)
            if (!remoteMatches(
                    "Output TXT/field/$stem.txt",
                    DepthArtifactContract.sha256Hex(localLabel),
                )
            ) {
                return false
            }

            val localAnnotLog = storage.annotLogFile(session.treeName, side.sideIndex)
            if (!remoteMatches(
                    "dataset/annotlog/field/$stem.json",
                    DepthArtifactContract.sha256Hex(localAnnotLog),
                )
            ) {
                return false
            }

            val localRaw = storage.depthRawFile(session.treeName, side.sideIndex)
            val localDepthJson = storage.depthJsonFile(session.treeName, side.sideIndex)
            if (storage.hasValidDepthPair(session.treeName, side.sideIndex)) {
                if (!remoteMatches(
                        "dataset/depth/field/$stem.raw",
                        DepthArtifactContract.sha256Hex(localRaw),
                    ) ||
                    !remoteMatches(
                        "dataset/depth/field/$stem.json",
                        DepthArtifactContract.sha256Hex(localDepthJson),
                    )
                ) {
                    return false
                }
            } else if (
                listOf("dataset/depth/field/$stem.raw", "dataset/depth/field/$stem.json").any {
                    saf.exists(safTreeUri, it, forceRefresh = true) != SafPathState.Absent
                }
            ) {
                return false
            }
        }

        remoteMatches(
            "Output JSON/${session.treeName}.json",
            DepthArtifactContract.sha256Hex(outputJsonText.toByteArray(Charsets.UTF_8)),
        ) &&
            remoteMatches(
                "dataset/metadata/${session.treeName}.json",
                DepthArtifactContract.sha256Hex(metadataText.toByteArray(Charsets.UTF_8)),
            ) &&
            remoteMatches(
                "dataset/manifests/${session.treeName}.json",
                DepthArtifactContract.sha256Hex(manifestText.toByteArray(Charsets.UTF_8)),
            )
        }.getOrDefault(false)
    }

    /**
     * Mirror every file referenced by the package manifest. Returns false on any required write
     * failure; callers must not publish a SAF manifest in that case.
     */
    private fun mirrorSafArtifacts(
        treeName: String,
        split: String,
        sides: List<TreeSide>,
        safTreeUri: Uri,
        forceMediaOverwrite: Boolean,
    ): Boolean {
        var packageFilesOk = true

        fun deleteRemoteIfPresent(path: String): Boolean = when (
            val state = saf.exists(safTreeUri, path, forceRefresh = true)
        ) {
            SafPathState.Absent -> true
            is SafPathState.Inaccessible -> false
            SafPathState.Present ->
                saf.deletePath(safTreeUri, path) &&
                    saf.exists(safTreeUri, path, forceRefresh = true) == SafPathState.Absent
        }

        for (side in sides) {
            if (side.imageWidth > 0 && side.imageHeight > 0) {
                val yoloText = YoloParser.serialize(
                    side.bboxes,
                    side.imageWidth,
                    side.imageHeight,
                )
                val labelOk = runCatching {
                    saf.writeText(
                        safTreeUri,
                        "Output TXT/field/${treeName}_${side.sideIndex + 1}.txt",
                        yoloText,
                    )
                }.getOrDefault(false)
                packageFilesOk = packageFilesOk && labelOk
            }
            val annotLogOk = runCatching {
                saf.writeText(
                    safTreeUri,
                    "dataset/annotlog/field/${treeName}_${side.sideIndex + 1}.json",
                    buildAnnotLog(treeName, split, side),
                )
            }.getOrDefault(false)
            packageFilesOk = packageFilesOk && annotLogOk
            if (side.imageUri != null) {
                // A manual-ID re-shoot deliberately reuses the name, so addTree forces overwrite.
                val mirrorPath = "dataset/images/field/${treeName}_${side.sideIndex + 1}.jpg"
                val shouldWriteImage = if (forceMediaOverwrite) {
                    true
                } else {
                    when (saf.exists(safTreeUri, mirrorPath)) {
                        SafPathState.Absent -> true
                        SafPathState.Present -> false
                        is SafPathState.Inaccessible -> {
                            packageFilesOk = false
                            false
                        }
                    }
                }
                if (shouldWriteImage) {
                    val imageOk = runCatching {
                        val imgFile = File(side.imageUri.path ?: "")
                        check(imgFile.isFile) { "Local RGB is missing" }
                        saf.writeBytes(
                            safTreeUri,
                            mirrorPath,
                            imgFile.readBytes(),
                            "image/jpeg",
                        )
                    }.getOrElse {
                        Log.w(TAG, "SAF mirror image failed for side ${side.sideIndex}", it)
                        false
                    }
                    packageFilesOk = packageFilesOk && imageOk
                }
            } else {
                packageFilesOk = false
            }
            // Mirror depth only as a validated pair. If either write fails, remove both remote
            // halves; FolderResumeImporter must never see fresh raw with stale metadata.
            val depthRaw = storage.depthRawFile(treeName, side.sideIndex)
            val depthMeta = storage.depthJsonFile(treeName, side.sideIndex)
            val rawPath = "dataset/depth/field/${treeName}_${side.sideIndex + 1}.raw"
            val metaPath = "dataset/depth/field/${treeName}_${side.sideIndex + 1}.json"
            if (storage.hasValidDepthPair(treeName, side.sideIndex)) {
                val needsWrite = if (forceMediaOverwrite) {
                    true
                } else {
                    val rawState = saf.exists(safTreeUri, rawPath)
                    val metaState = saf.exists(safTreeUri, metaPath)
                    if (rawState is SafPathState.Inaccessible || metaState is SafPathState.Inaccessible) {
                        packageFilesOk = false
                        false
                    } else {
                        rawState == SafPathState.Absent || metaState == SafPathState.Absent
                    }
                }
                if (needsWrite) {
                    val mirrored = runCatching {
                        val rawOk = saf.writeBytes(
                            safTreeUri,
                            rawPath,
                            depthRaw.readBytes(),
                            "application/octet-stream",
                        )
                        val metaOk = rawOk && saf.writeText(safTreeUri, metaPath, depthMeta.readText())
                        rawOk && metaOk
                    }.getOrElse {
                        Log.w(TAG, "SAF mirror depth pair failed for side ${side.sideIndex}", it)
                        false
                    }
                    if (!mirrored) {
                        runCatching { saf.deletePath(safTreeUri, rawPath) }
                        runCatching { saf.deletePath(safTreeUri, metaPath) }
                        Log.w(TAG, "Removed partial SAF depth pair for side ${side.sideIndex}")
                    }
                    packageFilesOk = packageFilesOk && mirrored
                }
            } else {
                // H-02: local has no depth for this side (never captured, or a re-shoot dropped it).
                // Remove any depth previously mirrored to SAF so resume/import can't bring the stale
                // depth back and pair it with the new RGB. Only touches SAF when something is there.
                val rawRemoved = deleteRemoteIfPresent(rawPath)
                val metaRemoved = deleteRemoteIfPresent(metaPath)
                packageFilesOk = packageFilesOk && rawRemoved && metaRemoved
            }
        }
        return packageFilesOk
    }

    // ─── Output JSON ─────────────────────────────────────────────────────────────

    suspend fun saveOutputJson(
        session: ActiveSession,
        safTreeUri: Uri? = null,
        awaitSafVerification: Boolean = true,
    ): SaveResult = commitAnnotationRevision(session, safTreeUri, markComplete = true)

    /** The run (session) id that owns [treeKey] — for "next capture" / "back to tree list" nav. */
    suspend fun getTreeRunId(treeKey: String): String? = withContext(Dispatchers.IO) {
        treeDao.getByKey(treeKey)?.sessionId
    }

    // sessions.json index dropped on native (resume is folder-scan based)

    // ─── Durable capture drafts ────────────────────────────────────────────────

    /** Update a draft parent in place; never use SQLite REPLACE because its delete phase cascades
     * all capture_draft_sides before inserting the replacement row. */
    private suspend fun saveCaptureDraft(draft: CaptureDraftEntity) {
        db.withTransaction {
            if (captureDraftDao.update(draft) == 0) captureDraftDao.insert(draft)
        }
    }

    private suspend fun captureDraftMatchesCommittedTree(
        runId: String,
        draft: CaptureDraftEntity,
        tree: TreeEntity,
    ): Boolean {
        if (draft.expectedTreeId > 0 && draft.expectedTreeId != tree.treeId) return false
        val draftSides = captureDraftDao.getSides(runId)
        val committedSides = sideDao.getByTree(tree.treeKey)
        if (draftSides.isEmpty() || draftSides.size != committedSides.size) return false
        val committedByIndex = committedSides.associateBy { it.sideIndex }
        return draftSides.all { draftSide ->
            val committedSide = committedByIndex[draftSide.sideIndex]
            val committedImage = storage.imageFile(tree.treeName, draftSide.sideIndex)
            committedSide != null && committedImage.isFile &&
                draftSide.imageSha256.equals(committedSide.rgbSha256, ignoreCase = true) &&
                draftSide.imageSha256.equals(
                    DepthArtifactContract.sha256Hex(committedImage),
                    ignoreCase = true,
                )
        }
    }

    suspend fun ensureCaptureDraft(
        runId: String,
        sideCount: Int,
        expectedTreeName: String = "",
        expectedTreeId: Int = 0,
    ): CaptureDraftSnapshot = withContext(Dispatchers.IO) {
        draftLock(runId).withLock {
            ensureCaptureDraftLocked(runId, sideCount, expectedTreeName, expectedTreeId)
        }
    }

    private suspend fun ensureCaptureDraftLocked(
        runId: String,
        sideCount: Int,
        expectedTreeName: String,
        expectedTreeId: Int,
    ): CaptureDraftSnapshot {
        val now = System.currentTimeMillis()
        var existing = captureDraftDao.get(runId)
        if (existing?.expectedTreeName?.isNotBlank() == true) {
            val committed = treeDao.getByName(existing.expectedTreeName)
            if (committed != null && captureDraftMatchesCommittedTree(runId, existing, committed)) {
                // Process death after the local tree commit but before draft cleanup: only this
                // exact RGB identity match may reclaim the draft. A same-name unrelated tree must
                // never delete the operator's still-uncommitted capture.
                captureDraftDao.delete(runId)
                storage.discardUncommittedDraft(runId)
                existing = null
            } else if (committed != null) {
                saveCaptureDraft(existing.copy(status = "INVALID", updatedAt = now))
            }
        }
        if (existing == null) {
            saveCaptureDraft(
                CaptureDraftEntity(
                    runId = runId,
                    expectedTreeName = expectedTreeName,
                    expectedTreeId = expectedTreeId,
                    sideCount = sideCount,
                    createdAt = now,
                    updatedAt = now,
                ),
            )
        } else if (existing.expectedTreeName.isBlank() && expectedTreeName.isNotBlank()) {
            // Keep a persisted draft's own identity. The run's nextId can be stale after a manual-ID
            // capture, so re-opening the run must not relabel that draft as another tree.
            saveCaptureDraft(existing.copy(
                expectedTreeName = expectedTreeName,
                expectedTreeId = if (expectedTreeId > 0) expectedTreeId else existing.expectedTreeId,
                sideCount = sideCount,
                updatedAt = now,
            ))
        } else if (existing.sideCount != sideCount) {
            saveCaptureDraft(existing.copy(
                expectedTreeName = existing.expectedTreeName.ifBlank { expectedTreeName },
                expectedTreeId = if (existing.expectedTreeId > 0) existing.expectedTreeId else expectedTreeId,
                sideCount = sideCount,
                updatedAt = now,
            ))
        }
        return loadCaptureDraftLocked(runId) ?: error("Capture draft could not be created")
    }

    suspend fun loadCaptureDraft(runId: String): CaptureDraftSnapshot? = withContext(Dispatchers.IO) {
        draftLock(runId).withLock { loadCaptureDraftLocked(runId) }
    }

    private suspend fun loadCaptureDraftLocked(runId: String): CaptureDraftSnapshot? {
        val draft = captureDraftDao.get(runId) ?: return null
        // INVALID describes at least one bad child, not that every child is unusable. Keep
        // validating all rows so process recreation can still restore valid sides individually.
        val rows = captureDraftDao.getSides(runId)
        val ownedRoot = storage.captureDraftDir(runId).canonicalPath + File.separator
        var invalid = false
        val valid = rows.mapNotNull { row ->
            val image = File(row.imagePath)
            val imageOwned = image.canonicalPath.startsWith(ownedRoot)
            val imageOk = imageOwned && image.isFile && image.length() > 0L && runCatching {
                DepthArtifactContract.sha256Hex(image).equals(row.imageSha256, ignoreCase = true)
            }.getOrDefault(false)
            val raw = row.depthRawPath?.let(::File)
            val json = row.depthJsonPath?.let(::File)
            val depthOwned = (raw == null || raw.canonicalPath.startsWith(ownedRoot)) &&
                (json == null || json.canonicalPath.startsWith(ownedRoot))
            val depthExpected = raw != null || json != null
            val depthOk = if (!depthExpected) true else depthOwned && raw?.isFile == true && json?.isFile == true &&
                row.depthRawSha256 != null && row.depthJsonSha256 != null &&
                runCatching {
                    DepthArtifactContract.sha256Hex(raw).equals(row.depthRawSha256, ignoreCase = true) &&
                        DepthArtifactContract.sha256Hex(json).equals(row.depthJsonSha256, ignoreCase = true) &&
                        DepthArtifactContract.validationError(
                            raw.length(), json.readText(),
                            actualRawSha256 = DepthArtifactContract.sha256Hex(raw),
                            actualRgbSha256 = DepthArtifactContract.sha256Hex(image),
                        ) == null
                }.getOrDefault(false)
            if (!imageOk || !depthOk) {
                invalid = true
                null
            } else {
                CaptureDraftSideSnapshot(
                    row.sideIndex, row.imagePath, row.imageSha256, row.imageWidth, row.imageHeight,
                    row.depthRawPath, row.depthJsonPath, row.depthRawSha256, row.depthJsonSha256,
                    row.captureOrigin, row.depthRequired,
                )
            }
        }
        if (invalid && draft.status != "INVALID") {
            saveCaptureDraft(draft.copy(status = "INVALID", updatedAt = System.currentTimeMillis()))
        }
        return CaptureDraftSnapshot(
            runId = draft.runId,
            expectedTreeName = draft.expectedTreeName,
            expectedTreeId = draft.expectedTreeId,
            sideCount = draft.sideCount,
            currentSide = draft.currentSide,
            phase = draft.phase,
            step = draft.step,
            status = if (invalid) "INVALID" else draft.status,
            sides = valid,
        )
    }

    suspend fun persistCaptureDraftSide(
        runId: String,
        sideIndex: Int,
        generation: Long,
        imageBytes: ByteArray,
        imageWidth: Int,
        imageHeight: Int,
        captureOrigin: CaptureOrigin,
        depthRequired: Boolean,
        depthRawBytes: ByteArray? = null,
        depthJsonText: String? = null,
    ): Boolean = withContext(Dispatchers.IO) {
        draftLock(runId).withLock {
            draftSideLock(runId, sideIndex).withLock {
                if (!isCurrentDraftGeneration(runId, sideIndex, generation)) return@withLock false
                ensureCaptureDraftLocked(
                    runId,
                    maxOf(sideIndex + 1, captureDraftDao.get(runId)?.sideCount ?: 2),
                    expectedTreeName = "",
                    expectedTreeId = 0,
                )
            val image = storage.captureDraftImageFile(runId, sideIndex)
            storage.writeBytes(image, imageBytes)
            val rawPath: String?
            val jsonPath: String?
            if (depthRawBytes != null && depthJsonText != null) {
                val raw = storage.captureDraftDepthRawFile(runId, sideIndex)
                val json = storage.captureDraftDepthJsonFile(runId, sideIndex)
                storage.writeBytes(raw, depthRawBytes)
                storage.writeText(json, depthJsonText)
                rawPath = raw.absolutePath; jsonPath = json.absolutePath
            } else {
                rawPath = null; jsonPath = null
                storage.deleteFile(storage.captureDraftDepthRawFile(runId, sideIndex))
                storage.deleteFile(storage.captureDraftDepthJsonFile(runId, sideIndex))
            }
            // Retake may have happened while the camera/file operation was in progress. Do not
            // publish the stale bytes or reactivate an invalidated parent draft.
            if (!isCurrentDraftGeneration(runId, sideIndex, generation)) return@withLock false
            val now = System.currentTimeMillis()
            captureDraftDao.upsertSide(
                CaptureDraftSideEntity(
                    runId = runId,
                    sideIndex = sideIndex,
                    imagePath = image.absolutePath,
                    imageSha256 = DepthArtifactContract.sha256Hex(imageBytes),
                    imageWidth = imageWidth,
                    imageHeight = imageHeight,
                    depthRawPath = rawPath,
                    depthJsonPath = jsonPath,
                    depthRawSha256 = depthRawBytes?.let(DepthArtifactContract::sha256Hex),
                    depthJsonSha256 = depthJsonText?.let { DepthArtifactContract.sha256Hex(it.toByteArray()) },
                    captureOrigin = captureOrigin.name,
                    depthRequired = depthRequired,
                    updatedAt = now,
                ),
            )
            draftPersistedGenerations[draftSideKey(runId, sideIndex)] =
                AtomicLong(generation)
            captureDraftDao.get(runId)?.let {
                saveCaptureDraft(it.copy(status = "ACTIVE", updatedAt = now))
            }
            true
            }
        }
    }

    suspend fun updateCaptureDraftCursor(
        runId: String,
        currentSide: Int,
        phase: String,
        step: String,
        expectedTreeName: String = "",
        expectedTreeId: Int = 0,
    ) = withContext(Dispatchers.IO) {
        draftLock(runId).withLock {
            captureDraftDao.get(runId)?.let {
                // A cursor write queued before an accepted side write must not regress that side
                // from REVIEW to PREVIEW after the side row is durable.
                val hasSide = captureDraftDao.getSides(runId).any { it.sideIndex == currentSide }
                val safeStep = CaptureDraftCursorPolicy.restoreStep(step, hasSide)
                saveCaptureDraft(it.copy(
                    currentSide = currentSide,
                    phase = phase,
                    step = safeStep,
                    expectedTreeName = expectedTreeName.ifBlank { it.expectedTreeName },
                    expectedTreeId = if (expectedTreeId > 0) expectedTreeId else it.expectedTreeId,
                    updatedAt = System.currentTimeMillis(),
                ))
            }
        }
    }

    suspend fun removeCaptureDraftSide(runId: String, sideIndex: Int, generation: Long) = withContext(Dispatchers.IO) {
        draftLock(runId).withLock {
            draftSideLock(runId, sideIndex).withLock {
                val key = draftSideKey(runId, sideIndex)
                val currentGeneration = draftGenerations[key]?.get()
                val persistedGeneration = draftPersistedGenerations[key]?.get()
                // If a newer generation has already published its row while this stale cleanup was
                // queued, preserve it. If the newer capture failed before publication, remove the
                // old row/files so a failed retake cannot resurrect the previous side on restart.
                if (currentGeneration != null && currentGeneration != generation && persistedGeneration == currentGeneration) {
                    return@withLock
                }
                captureDraftDao.deleteSide(runId, sideIndex)
                storage.deleteFile(storage.captureDraftImageFile(runId, sideIndex))
                storage.deleteFile(storage.captureDraftDepthRawFile(runId, sideIndex))
                storage.deleteFile(storage.captureDraftDepthJsonFile(runId, sideIndex))
                draftPersistedGenerations.remove(key)
            }
        }
    }

    suspend fun discardCaptureDraft(runId: String) = withContext(Dispatchers.IO) {
        draftLock(runId).withLock {
            val draft = captureDraftDao.get(runId)
            val runSideCount = sessionDao.getById(runId)?.sideCount ?: 0
            val sideCount = maxOf(2, draft?.sideCount ?: 0, runSideCount)
            // Invalidate every known and declared side while holding the same lock as persistence.
            // A queued obsolete write therefore returns before ensureCaptureDraft can recreate rows.
            draftGenerations.keys
                .filter { it.startsWith("$runId:") }
                .mapNotNull { it.substringAfterLast(':').toIntOrNull() }
                .plus(0 until sideCount)
                .distinct()
                .forEach { sideIndex -> invalidateCaptureDraftSide(runId, sideIndex) }
            captureDraftDao.delete(runId)
            check(storage.discardUncommittedDraft(runId)) {
                "Capture draft files could not be discarded"
            }
        }
    }

    // ─── Durable SAF mirror status and recovery ────────────────────────────────

    fun observeMirrorStatuses(): Flow<List<MirrorStatusEntity>> = mirrorDao.observeAll()
    fun observeMirrorStatus(treeKey: String): Flow<MirrorStatusEntity?> = mirrorDao.observeByTree(treeKey)
    suspend fun getMirrorStatus(treeKey: String): MirrorStatusEntity? = withContext(Dispatchers.IO) { mirrorDao.getByTree(treeKey) }

    suspend fun retryMirror(treeKey: String): Boolean = withContext(Dispatchers.IO) {
        val old = mirrorDao.getByTree(treeKey) ?: return@withContext false
        if (old.remoteUri == null) return@withContext false
        // Conditional update prevents a retry tap from restoring an obsolete request after a newer
        // annotation revision has already replaced this row.
        if (!markMirrorAttempting(old)) return@withContext false
        mirrorWorkScheduler.enqueue { runMirrorJob(old) }
        true
    }

    // Mirror intent is persisted in the same Room transaction as each local revision. Workers are
    // launched only after that transaction commits and are recovered from mirror_status on restart.
    private suspend fun markMirrorAttempting(requested: MirrorStatusEntity): Boolean =
        mirrorDao.markAttempting(
            treeKey = requested.treeKey,
            requestedRevision = requested.requestedRevision,
            requestedHash = requested.requestedHash,
            remoteUri = requested.remoteUri,
            attemptAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(),
        ) > 0

    private suspend fun markMirrorVerified(requested: MirrorStatusEntity, revision: Long): Boolean =
        mirrorDao.markVerified(
            treeKey = requested.treeKey,
            requestedRevision = requested.requestedRevision,
            requestedHash = requested.requestedHash,
            remoteUri = requested.remoteUri,
            verifiedRevision = revision,
            verifiedHash = requested.requestedHash,
            attemptAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(),
        ) > 0

    private suspend fun markMirrorFailed(requested: MirrorStatusEntity, error: Throwable): Boolean =
        mirrorDao.markFailed(
            treeKey = requested.treeKey,
            requestedRevision = requested.requestedRevision,
            requestedHash = requested.requestedHash,
            remoteUri = requested.remoteUri,
            errorCode = error::class.simpleName,
            errorMessage = error.message,
            attemptAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(),
        ) > 0

    private suspend fun deletionBlocksMirror(treeKey: String): Boolean =
        artifactCoordinator.withExclusiveAccess {
            recoverPendingLocalRevisionsLocked()
            mirrorDeletionDao.getByTree(treeKey)?.status in setOf(
                MirrorStates.DELETE_PENDING,
                MirrorStates.DELETE_FAILED,
            )
        }

    private suspend fun markDeletionAttempting(requested: MirrorDeletionEntity): Boolean =
        mirrorDeletionDao.markAttempting(
            treeKey = requested.treeKey,
            remoteUri = requested.remoteUri,
            attemptAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(),
        ) > 0

    private suspend fun markDeletionVerified(requested: MirrorDeletionEntity): Boolean =
        mirrorDeletionDao.markVerified(
            treeKey = requested.treeKey,
            remoteUri = requested.remoteUri,
            attemptAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(),
        ) > 0

    private suspend fun markDeletionFailed(requested: MirrorDeletionEntity, error: Throwable): Boolean =
        mirrorDeletionDao.markFailed(
            treeKey = requested.treeKey,
            remoteUri = requested.remoteUri,
            errorCode = error::class.simpleName,
            errorMessage = error.message,
            attemptAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(),
        ) > 0

    private suspend fun runDeleteMirrorJob(requested: MirrorDeletionEntity) {
        // If a replacement tree with the same on-disk identity was committed before this queued
        // delete ran, never delete its remote package. Its own mirror request will converge SAF.
        val superseded = artifactCoordinator.withExclusiveAccess {
            recoverPendingLocalRevisionsLocked()
            val replacement = treeDao.getByName(requested.treeName)
            val replacementMirror = replacement?.let { mirrorDao.getByTree(it.treeKey) }
            if (replacementMirror?.remoteUri == requested.remoteUri) {
                mirrorDeletionDao.markSuperseded(
                    requested.treeKey,
                    requested.remoteUri,
                    System.currentTimeMillis(),
                )
                true
            } else {
                false
            }
        }
        if (superseded || !markDeletionAttempting(requested)) return
        try {
            when (val result = saf.deleteDatasetTree(Uri.parse(requested.remoteUri), requested.treeName, requested.sideCount)) {
                SafDeleteResult.Verified -> markDeletionVerified(requested)
                is SafDeleteResult.Failed ->
                    throw IllegalStateException(result.message, result.cause)
            }
        } catch (error: Throwable) {
            markDeletionFailed(requested, error)
            Log.w(TAG, "SAF delete failed for ${requested.treeName}", error)
        }
    }

    private suspend fun runInitialMirrorJob(
        requested: MirrorStatusEntity,
        sideCount: Int,
        sides: List<TreeSide>,
        split: String,
        metadataText: String,
    ) {
        val uri = requested.remoteUri?.let(Uri::parse) ?: return
        // A durable delete tombstone wins over every queued mirror request for this tree.
        if (deletionBlocksMirror(requested.treeKey)) return
        // CAS before I/O: an r0 worker that lost to an annotation revision must not touch SAF.
        if (!markMirrorAttempting(requested)) return
        if (deletionBlocksMirror(requested.treeKey)) return
        try {
            check(mirrorSafArtifacts(requested.treeName, split, sides, uri, forceMediaOverwrite = true)) {
                "Initial package mirror incomplete"
            }
            check(saf.writeText(uri, "dataset/metadata/${requested.treeName}.json", metadataText)) {
                "SAF metadata write failed"
            }
            // Capture commit intentionally has no Output JSON/manifest yet. Keep the queue PENDING;
            // strict resume must not treat this partial package as a verified backup. The first
            // annotation revision replaces this row with a revision-aware request.
            if (storage.outputJsonFile(requested.treeName).isFile && storage.manifestFile(requested.treeName).isFile) {
                // The annotation save transaction should already have replaced r0 with r1. Leave
                // this stale worker pending rather than claiming a revision it cannot verify.
                return
            }
        } catch (error: Throwable) {
            markMirrorFailed(requested, error)
            Log.w(TAG, "Initial SAF mirror failed for ${requested.treeName}", error)
        }
    }

    private suspend fun runMirrorJob(requested: MirrorStatusEntity) {
        if (deletionBlocksMirror(requested.treeKey)) return
        val uri = requested.remoteUri?.let(Uri::parse) ?: return
        if (!markMirrorAttempting(requested)) return
        if (deletionBlocksMirror(requested.treeKey)) return
        val session = loadActiveSession(requested.treeKey) ?: return
        val metadata = storage.readText(storage.metadataFile(session.treeName))
        if (requested.requestedRevision == 0L) {
            try {
                check(mirrorSafArtifacts(session.treeName, session.split, session.sides, uri, forceMediaOverwrite = true)) {
                    "Initial package mirror incomplete"
                }
                check(metadata != null && saf.writeText(uri, "dataset/metadata/${session.treeName}.json", metadata)) {
                    "SAF metadata write failed"
                }
                // r0 is a capture-only queue item. It remains pending until saveOutputJson creates
                // a complete revision with Output JSON and manifest.
                if (storage.outputJsonFile(session.treeName).isFile && storage.manifestFile(session.treeName).isFile) {
                    // The annotation save transaction should have replaced r0 with r1. Keep this
                    // stale worker non-verified and let the revision-aware request do the work.
                    return
                }
            } catch (error: Throwable) {
                markMirrorFailed(requested, error)
                Log.w(TAG, "Initial SAF mirror failed for ${requested.treeName}", error)
            }
            return
        }
        val output = storage.readText(storage.outputJsonFile(session.treeName))
        val manifest = storage.readText(storage.manifestFile(session.treeName))
        try {
            check(output != null && manifest != null && metadata != null) { "Local revision artifacts are incomplete" }
            check(DepthArtifactContract.sha256Hex(manifest.toByteArray(Charsets.UTF_8)).equals(requested.requestedHash, ignoreCase = true)) {
                "Local manifest revision does not match queued SAF revision"
            }
            val remoteManifest = "dataset/manifests/${session.treeName}.json"
            check(
                when (saf.exists(uri, remoteManifest, forceRefresh = true)) {
                    SafPathState.Absent -> true
                    is SafPathState.Inaccessible -> false
                    SafPathState.Present ->
                        saf.deletePath(uri, remoteManifest) &&
                            saf.exists(uri, remoteManifest, forceRefresh = true) == SafPathState.Absent
                }
            ) { "Could not invalidate remote manifest" }
            check(mirrorSafArtifacts(session.treeName, session.split, session.sides, uri, forceMediaOverwrite = true)) {
                "SAF package mirror incomplete"
            }
            check(saf.writeText(uri, "dataset/metadata/${session.treeName}.json", metadata)) { "SAF metadata write failed" }
            check(saf.writeText(uri, "Output JSON/${session.treeName}.json", output)) { "SAF Output JSON write failed" }
            check(saf.writeText(uri, "dataset/manifests/${session.treeName}.json", manifest)) { "SAF manifest write failed" }
            check(verifySafPackageReadBack(session, uri, output, manifest, metadata)) { "SAF read-back verification failed" }
            // Conditional completion is the final stale-worker guard. If a newer revision replaced
            // the request while SAF I/O was running, this update affects zero rows and is ignored.
            markMirrorVerified(requested, requested.requestedRevision)
        } catch (error: Throwable) {
            markMirrorFailed(requested, error)
            Log.w(TAG, "SAF mirror failed for ${requested.treeName}", error)
        }
    }

    private suspend fun reconcileMirrorQueue() {
        // Revision mirrors are reconciled first, but every delete tombstone is durable and is
        // retried in the same serial queue. A tombstone always suppresses a stale mirror worker.
        mirrorDao.getPendingOrFailed().forEach { runMirrorJob(it) }
        mirrorDeletionDao.getPendingOrFailed().forEach { runDeleteMirrorJob(it) }
    }

    /** Caller must hold [ArtifactCoordinator]. */
    private suspend fun recoverPendingLocalRevisionsLocked() {
        storage.pendingRevisions().forEach { pending ->
            val tree = treeDao.getByName(pending.treeName)
            if (tree != null && tree.revision == pending.revision) {
                runCatching { storage.publishRevision(pending.treeName, pending.revision, pending.entries) }
                    .onFailure { Log.w(TAG, "Local revision recovery failed for ${pending.treeName}", it) }
            } else {
                runCatching { storage.rollbackRevision(pending) }
                    .onFailure { Log.w(TAG, "Local revision rollback failed for ${pending.treeName}", it) }
            }
        }
    }

    private fun validateConfirmedLinks(sides: List<TreeSide>, links: List<CrossSideLink>) {
        val boxes = sides.associate { side -> side.sideIndex to side.bboxes.map { it.id }.toSet() }
        val seenIds = HashSet<String>()
        val seenEndpoints = HashSet<String>()
        links.forEach { link ->
            require(link.linkId.isNotBlank()) { "Confirmed link id must not be blank" }
            require(link.sideA in boxes && link.sideB in boxes) { "Confirmed link references a missing side" }
            require(link.bboxIdA in boxes.getValue(link.sideA) && link.bboxIdB in boxes.getValue(link.sideB)) {
                "Confirmed link ${link.linkId} references a missing bbox endpoint"
            }
            require(seenIds.add(link.linkId)) { "Duplicate confirmed link id: ${link.linkId}" }
            require(seenEndpoints.add(link.dedupKey)) { "Duplicate confirmed link endpoint pair: ${link.dedupKey}" }
        }
    }

    // ─── Folder-scan resume helpers (used by FolderResumeImporter) ───────────────

    /** All tree names currently in Room (for resume de-dupe). */
    suspend fun allTreeNames(): Set<String> = withContext(Dispatchers.IO) {
        treeDao.getAllOnce().map { it.treeName }.toSet()
    }

    suspend fun treeNameExists(treeName: String): Boolean = withContext(Dispatchers.IO) {
        treeDao.getByName(treeName) != null
    }

    /**
     * WS-12: committed tree name → the identity it was captured under, for merge-safety checks.
     * `contentDigest` deliberately reuses the capture-set id: at this level identity is what
     * decides whether two same-named trees are the same tree, and reading every manifest would
     * turn a resume pre-check into a full disk scan.
     */
    suspend fun allTreeIdentities(): Map<String, CaptureSetMergePolicy.Entry> =
        withContext(Dispatchers.IO) {
            treeDao.getAllOnce().associate { tree ->
                tree.treeName to CaptureSetMergePolicy.Entry(
                    treeName = tree.treeName,
                    captureSetId = tree.captureSetId,
                    deviceToken = tree.deviceToken,
                    contentDigest = tree.captureSetId,
                )
            }
        }

    /** Map of run groupKey → its sessionId (for reusing a run on resume). */
    suspend fun runGroupKeyToId(): Map<String, String> = withContext(Dispatchers.IO) {
        sessionDao.getAllOnce().associate { it.groupKey to it.sessionId }
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
    nameToken = nameToken, operatorName = operatorName,
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
    /** WS-12: the naming token this run actually writes. Empty = legacy `VARIETY_BLOCK_0001`. */
    val nameToken: String = "",
    /** WS-13: operator recorded on the run; blank until one is entered. */
    val operatorName: String = "",
)
