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
import kotlinx.coroutines.async
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
    private val artifactCoordinator: ArtifactCoordinator,
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
            val groupKey = groupKeyFor(variety, block)
            // C-01: one run per variety+block. Re-using the same block must fold into the SAME run
            // (so nextId keeps counting and treeName can never collide across runs), never spawn a
            // parallel run. Mirrors FolderResumeImporter's groupKey reuse. When an existing run is
            // reused, its sideCount/autoId are kept (the dialog's values for this call are ignored).
            // Query + insert are one Room transaction. Without this, two fast taps can both miss
            // the row; INSERT OR REPLACE on the unique groupKey then deletes the first run and
            // cascades all of its trees. Plain INSERT is an additional fail-closed backstop.
            db.withTransaction {
                sessionDao.getByGroupKey(groupKey)?.sessionId ?: run {
                    val id = UUID.randomUUID().toString()
                    val now = System.currentTimeMillis()
                    sessionDao.insert(
                        SessionEntity(
                            sessionId = id, variety = variety.trim(), block = block.trim(),
                            groupKey = groupKey,
                            sideCount = sideCount.coerceAtLeast(2), autoId = autoId, nextId = 1,
                            createdAt = now, updatedAt = now,
                        )
                    )
                    id
                }
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
                sessionDao.deleteById(sessionId) // cascades trees/sides/bboxes/links
                // Room is the commit marker. Remove it first so a process death can only leave
                // ignored orphan files, never a committed tree whose RGB was already deleted.
                for (t in trees) deleteTreeArtifacts(t, safTreeUri)
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
    ): String = artifactCoordinator.withExclusiveAccess {
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

        // Name check, canonical-file publication, and Room commit share one process-wide lock.
        storage.publishCapturePackage(
            stagingDir = stagingDir,
            treeName = treeName,
            sideIndices = sides.map { it.sideIndex },
            requiredDepthSides = requiredDepthSides,
        )

        // Prepare every synchronous local sidecar before Room exposes the tree. The DB row is
        // the commit marker, so failed/partial files can never become an exportable tree.
        val metaJson = buildMetadataJson(metadata, treeName, run, treeId, sides).toString(2)
        storage.writeText(storage.metadataFile(treeName), metaJson)
        writeLocalArtifacts(treeName, split, sides)

        // Tree row + its sides in ONE transaction so a concurrent reader can never observe
        // a tree that has no sides yet (which a racing save could then persist as the truth).
        // The nextId advance MUST also be inside this transaction: if the app crashes after
        // the tree is committed but before nextId is updated, the next "Add Tree" would
        // reuse the same id and create a duplicate.
        val nextId = maxOf(run.nextId, treeId + 1)
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
            // Advance the tree-id counter. MUST be an UPDATE, not upsert(REPLACE): replacing
            // the existing run row cascade-deletes the tree we just inserted (FK onDelete=CASCADE).
            sessionDao.update(run.copy(nextId = nextId, updatedAt = now))
        }

        // Mirror one committed package serially. Output JSON is mirrored later after annotation,
        // and carries the same RGB hashes so resume can reject cross-generation combinations.
        if (safTreeUri != null) {
            safScope.launch {
                // A new committed capture has no annotation revision yet. Remove any orphaned
                // same-name JSON/manifest before publishing its mirror so consumers cannot see an
                // old annotation package beside the new RGB.
                runCatching {
                    saf.deleteDatasetTree(
                        safTreeUri,
                        treeName,
                        maxOf(
                            run.sideCount,
                            (sides.maxOfOrNull { it.sideIndex } ?: -1) + 1,
                        ),
                    )
                }
                runCatching {
                    check(saf.writeText(safTreeUri, "dataset/metadata/${treeName}.json", metaJson)) {
                        "SAF metadata write returned false"
                    }
                }.onFailure { Log.w(TAG, "SAF mirror metadata failed for $treeName", it) }
                if (!mirrorSafArtifacts(
                        treeName,
                        split,
                        sides,
                        safTreeUri,
                        forceMediaOverwrite = true,
                    )
                ) {
                    Log.w(TAG, "Initial SAF capture mirror incomplete for $treeName")
                }
            }
        }

        treeKey
        }
    }

    suspend fun deleteTree(treeKey: String, safTreeUri: Uri? = null) =
        artifactCoordinator.withExclusiveAccess {
            withContext(Dispatchers.IO) {
                val tree = treeDao.getByKey(treeKey) ?: return@withContext
                ArtifactIdentityPolicy.treeNameError(tree.treeName)?.let {
                    throw IllegalArgumentException("Refusing to delete unsafe tree identity: $it")
                }
                treeDao.deleteByKey(treeKey) // cascades sides/bboxes/links
                deleteTreeArtifacts(tree, safTreeUri)
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

    private suspend fun deleteTreeArtifacts(tree: TreeEntity, safTreeUri: Uri?) {
        ArtifactIdentityPolicy.treeNameError(tree.treeName)?.let {
            throw IllegalArgumentException("Refusing to delete unsafe tree identity: $it")
        }
        storage.deleteTree(tree.treeName, tree.sideCount)
        if (safTreeUri != null) {
            // Queue behind every earlier mirror write; a direct concurrent delete could otherwise
            // be undone when an older pending job recreates the same files.
            safScope.launch {
                saf.deleteDatasetTree(safTreeUri, tree.treeName, tree.sideCount)
            }.join()
        }
    }

    private fun buildMetadataJson(
        metadata: TreeMetadata?,
        treeName: String,
        run: SessionEntity,
        treeId: Int,
        sides: List<TreeSide>,
    ): JSONObject {
        val ts = ISO_FORMAT.format(Date())
        return JSONObject().apply {
            put("artifactSchemaVersion", 1)
            put("name", treeName)
            put("variety", metadata?.variety ?: run.variety)
            put("blok", metadata?.block ?: run.block)
            put("treeId", treeId)
            put("operator", "")
            put("timestamp", ts)
            metadata?.latitude?.let { put("lat", it) }
            metadata?.longitude?.let { put("lng", it) }
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
            metadata = TreeMetadata(variety = tree.variety, block = tree.block, treeId = tree.treeId.toString()),
            createdAt = tree.createdAt, updatedAt = tree.updatedAt,
        )
    }

    /** Write-through save of one tree (sides/bboxes/links + label files + annot-log). */
    suspend fun saveSession(session: ActiveSession, safTreeUri: Uri? = null) =
        artifactCoordinator.withExclusiveAccess {
            withContext(Dispatchers.IO) {
        val saveStart = System.currentTimeMillis()
        // Log.d(TAG, "saveSession START - tree=${session.treeName}")
        
        val treeKey = session.sessionId
        val tree = treeDao.getByKey(treeKey) ?: return@withContext
        check(session.treeName == tree.treeName) {
            "Session identity does not match its committed tree"
        }
        val verifiedSession = validateSessionCapture(session)
        // Invalidate the old file-level commit marker BEFORE the DB revision changes. A process
        // death after the transaction must not leave the previous manifest looking current.
        check(storage.deleteFile(storage.manifestFile(session.treeName))) {
            "Cannot invalidate previous annotation manifest"
        }
        val now = System.currentTimeMillis()
        // Atomic DB write: tree row + sides + bboxes + links replaced in ONE transaction so a
        // concurrent load (or another screen's save) can never read — nor persist — a partial
        // side list. The old non-atomic delete-then-insert was the "a tree loses a side on
        // reopen" bug: a reader could catch the gap between deleteByTree and the re-inserts.
        val dbStart = System.currentTimeMillis()
        db.withTransaction {
            val existingSideIndices = sideDao.getByTree(treeKey)
                .mapTo(mutableSetOf()) { it.sideIndex }
            val incomingSideIndices = verifiedSession.sides
                .mapTo(mutableSetOf()) { it.sideIndex }
            // Compare identities, not only counts. A stale session with side {0,2} must not
            // replace committed sides {0,1} merely because both lists contain two elements.
            check(incomingSideIndices.containsAll(existingSideIndices)) {
                "Refusing to save a stale session that omits committed sides"
            }
            val declaredSideCount = maxOf(
                tree.sideCount,
                (verifiedSession.sides.maxOfOrNull { it.sideIndex } ?: -1) + 1,
            )
            treeDao.update(tree.copy(updatedAt = now, sideCount = declaredSideCount))
            persistSidesDb(treeKey, verifiedSession.sides)
            linkDao.deleteByTree(treeKey)
            linkDao.insertAll(verifiedSession.confirmedLinks.map {
                ConfirmedLinkEntity(treeKey = treeKey, linkId = it.linkId, sideA = it.sideA, bboxIdA = it.bboxIdA, sideB = it.sideB, bboxIdB = it.bboxIdB)
            })
        }
        val dbTime = System.currentTimeMillis() - dbStart
        // Log.d(TAG, "saveSession DB transaction took ${dbTime}ms")
        
        // Slow file/SAF artifacts run OUTSIDE the transaction (never hold the DB lock for the
        // multi-MB SAF image mirror).
        val artifactsStart = System.currentTimeMillis()
        writeAnnotationRevision(
            verifiedSession,
            safTreeUri = safTreeUri,
            verifySaf = false,
            awaitSaf = false,
        )
        val artifactsTime = System.currentTimeMillis() - artifactsStart
        // Log.d(TAG, "saveSession writeAnnotationRevision took ${artifactsTime}ms")

        val totalTime = System.currentTimeMillis() - saveStart
        // Log.d(TAG, "saveSession END - total=${totalTime}ms")
            }
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
                    rgbSha256 = side.rgbSha256,
                    captureOrigin = side.captureOrigin.name,
                    depthRequired = side.depthRequired,
                )
            )
            bboxDao.insertAll(side.bboxes.map { it.toEntity(sideId) })
        }
    }

    /** Source-of-truth local files (YOLO label + annot-log). Synchronous, fast. */
    private fun writeLocalArtifacts(treeName: String, split: String, sides: List<TreeSide>) {
        for (side in sides) {
            if (side.imageWidth > 0 && side.imageHeight > 0) {
                val yoloText = YoloParser.serialize(side.bboxes, side.imageWidth, side.imageHeight)
                storage.writeText(storage.labelFile(treeName, side.sideIndex), yoloText)
            }
            storage.writeText(
                storage.annotLogFile(treeName, side.sideIndex),
                buildAnnotLog(treeName, split, side),
            )
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

    /**
     * Commit one coherent annotation revision. Labels and Output JSON are generated from the same
     * [session] snapshot; the manifest is written last locally and in the SAF mirror.
     */
    private suspend fun writeAnnotationRevision(
        session: ActiveSession,
        safTreeUri: Uri?,
        verifySaf: Boolean,
        awaitSaf: Boolean,
    ) {
        check(storage.deleteFile(storage.manifestFile(session.treeName))) {
            "Cannot invalidate previous annotation manifest"
        }
        writeLocalArtifacts(session.treeName, session.split, session.sides)
        // Recompute results from this exact verified snapshot. Accepting a UI-cached TreeResults
        // could make summary/bunches disagree with the TXT and annotations committed beside it.
        val jsonText = ExportManager.generateOutputJson(session).toString(2)
        storage.writeText(storage.outputJsonFile(session.treeName), jsonText)
        val manifestText = TreePackageManifest.materialize(storage, session.treeName, session.sides)
        if (safTreeUri != null) {
            val mirror = safScope.async {
                val result = runCatching {
                    val remoteManifest = "dataset/manifests/${session.treeName}.json"
                    check(
                        !saf.exists(safTreeUri, remoteManifest) ||
                            saf.deletePath(safTreeUri, remoteManifest)
                    ) {
                        "Could not invalidate previous SAF manifest"
                    }
                    check(
                        mirrorSafArtifacts(
                            session.treeName,
                            session.split,
                            session.sides,
                            safTreeUri,
                            forceMediaOverwrite = awaitSaf,
                        )
                    ) {
                        "One or more SAF package artifacts failed to mirror"
                    }
                    val metadataText = storage.readText(
                        storage.metadataFile(session.treeName),
                    ) ?: error("Local metadata is missing")
                    check(
                        saf.writeText(
                            safTreeUri,
                            "dataset/metadata/${session.treeName}.json",
                            metadataText,
                        )
                    ) {
                        "SAF metadata write returned false"
                    }
                    check(
                        saf.writeText(
                            safTreeUri,
                            "Output JSON/${session.treeName}.json",
                            jsonText,
                        )
                    ) {
                        "SAF Output JSON write returned false"
                    }
                    check(
                        saf.writeText(
                            safTreeUri,
                            remoteManifest,
                            manifestText,
                        )
                    ) {
                        "SAF manifest write returned false"
                    }
                    if (verifySaf) {
                        check(
                            verifySafPackageReadBack(
                                session,
                                safTreeUri,
                                jsonText,
                                manifestText,
                                metadataText,
                            )
                        ) {
                            "SAF package failed read-back verification"
                        }
                    }
                }
                result.onFailure {
                    runCatching {
                        saf.deletePath(
                            safTreeUri,
                            "dataset/manifests/${session.treeName}.json",
                        )
                    }
                    Log.w(
                        TAG,
                        "SAF annotation package mirror failed for ${session.treeName}",
                        it,
                    )
                }
                result
            }
            if (awaitSaf) {
                mirror.await().getOrThrow()
            }
        }
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
                saf.exists(safTreeUri, "dataset/depth/field/$stem.raw") ||
                saf.exists(safTreeUri, "dataset/depth/field/$stem.json")
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
            runCatching {
                saf.writeText(safTreeUri, "dataset/annotlog/field/${treeName}_${side.sideIndex + 1}.json",
                    buildAnnotLog(treeName, split, side))
            }
            if (side.imageUri != null) {
                // A manual-ID re-shoot deliberately reuses the name, so addTree forces overwrite.
                val mirrorPath = "dataset/images/field/${treeName}_${side.sideIndex + 1}.jpg"
                if (forceMediaOverwrite || !saf.exists(safTreeUri, mirrorPath)) {
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
                val needsWrite = forceMediaOverwrite ||
                    !saf.exists(safTreeUri, rawPath) ||
                    !saf.exists(safTreeUri, metaPath)
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
                val rawRemoved = !saf.exists(safTreeUri, rawPath) ||
                    saf.deletePath(safTreeUri, rawPath)
                val metaRemoved = !saf.exists(safTreeUri, metaPath) ||
                    saf.deletePath(safTreeUri, metaPath)
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
    ) =
        artifactCoordinator.withExclusiveAccess {
            withContext(Dispatchers.IO) {
            val tree = treeDao.getByKey(session.sessionId)
                ?: throw IllegalStateException("Committed tree no longer exists")
            check(session.treeName == tree.treeName) {
                "Session identity does not match its committed tree"
            }
            val verifiedSession = validateSessionCapture(session)
            writeAnnotationRevision(
                verifiedSession,
                safTreeUri,
                verifySaf = safTreeUri != null,
                awaitSaf = safTreeUri != null && awaitSafVerification,
            )
            // Mark the tree complete (the JS "Compute & Mark Complete" green check).
            // UPDATE, not upsert(REPLACE): REPLACE would cascade-delete the tree's
            // sides/links (annotations) when flipping the complete flag.
            treeDao.update(tree.copy(isComplete = true, updatedAt = System.currentTimeMillis()))
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

    suspend fun treeNameExists(treeName: String): Boolean = withContext(Dispatchers.IO) {
        treeDao.getByName(treeName) != null
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
