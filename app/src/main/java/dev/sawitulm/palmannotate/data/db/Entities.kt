package dev.sawitulm.palmannotate.data.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

// ─── Entities ─────────────────────────────────────────────────────────────────
//
// Data model matches the JS app:
//   SESSION (run) — locked to one variety+block, holds MANY trees, has an
//                   auto/manual tree-id counter (nextId).
//   TREE          — one tree = N side photos, belongs to a session.
//   SIDE/BBOX/LINK— belong to a TREE (keyed by treeKey).

/** A capture run, locked to a single variety+block, holding many trees. */
@Entity(
    tableName = "sessions",
    // groupKey (variety+block) is a run's unique identity: re-using the same block must fold into
    // the SAME run/folder, never spawn a parallel run (which would collide treeName across runs).
    indices = [Index(value = ["groupKey"], unique = true)],
)
data class SessionEntity(
    @PrimaryKey val sessionId: String,   // UUID (run id)
    val variety: String = "",
    val block: String = "",
    val groupKey: String = "",
    val sideCount: Int = 4,              // default photos-per-tree (4 or 8)
    val autoId: Boolean = true,
    val nextId: Int = 1,                 // next tree sequence number
    val createdAt: Long,
    val updatedAt: Long,
    // ── WS-12 capture-set identity ────────────────────────────────────────────
    // Minted once when the run is created and never rewritten, so every tree in the run — and
    // every re-export of it — carries the same identity. Empty on rows migrated from v6: a run
    // created before WS-12 has no identity, and inventing one would claim provenance it lacks.
    @ColumnInfo(defaultValue = "''") val captureSetId: String = "",
    @ColumnInfo(defaultValue = "''") val deviceToken: String = "",
    /** Naming token actually used in this run's treeNames. Empty = legacy naming. */
    @ColumnInfo(defaultValue = "''") val nameToken: String = "",
    // ── WS-13 operator provenance ─────────────────────────────────────────────
    @ColumnInfo(defaultValue = "''") val operatorName: String = "",
    @ColumnInfo(defaultValue = "'MULTISIDE'") val datasetType: String = "MULTISIDE",
)

/** One tree (N side photos) inside a run. */
@Entity(
    tableName = "trees",
    foreignKeys = [ForeignKey(
        entity = SessionEntity::class,
        parentColumns = ["sessionId"],
        childColumns = ["sessionId"],
        onDelete = ForeignKey.CASCADE,
    )],
    // treeName is globally unique: on disk, files are keyed only by treeName, so a duplicate would
    // let one tree's RGB pair with another tree's depth. This unique index is the DB-level backstop
    // for C-01 (the app-layer guard is SessionRepository.createRun folding same-groupKey runs).
    indices = [Index("sessionId"), Index(value = ["treeName"], unique = true)],
)
data class TreeEntity(
    @PrimaryKey val treeKey: String,     // UUID — the annotation key used across the UI/nav
    val sessionId: String,               // owning run
    val treeName: String,                // e.g. DAMIMAS_A21B_0001
    val treeId: Int,                     // sequence number within the run
    val split: String = "field",
    val sideCount: Int = 4,
    val isComplete: Boolean = false,
    val variety: String = "",
    val block: String = "",
    val revision: Long = 0L,
    val createdAt: Long,
    val updatedAt: Long,
    // ── WS-12: identity copied from the owning run at commit time ─────────────
    // Denormalised on purpose: a resumed tree keeps the identity of the device that CAPTURED it,
    // which is not the run identity of the device doing the resume.
    @ColumnInfo(defaultValue = "''") val captureSetId: String = "",
    @ColumnInfo(defaultValue = "''") val deviceToken: String = "",
    @ColumnInfo(defaultValue = "''") val nameToken: String = "",
    // ── WS-13: capture-time provenance that must survive resume and re-export ──
    /** Device-local capture day (YYYY-MM-DD). Empty on legacy rows; never back-filled with today. */
    @ColumnInfo(defaultValue = "''") val captureDate: String = "",
    @ColumnInfo(defaultValue = "''") val operatorName: String = "",
    /** GpsStatus.name. 'UNKNOWN' on legacy rows — an honest "no claim", not a fabricated FRESH. */
    @ColumnInfo(defaultValue = "'UNKNOWN'") val gpsStatus: String = "UNKNOWN",
    val gpsLatitude: Double? = null,
    val gpsLongitude: Double? = null,
    val gpsAccuracyM: Double? = null,
    /** Epoch millis of the FIX, not of the commit. */
    val gpsFixTimeMillis: Long? = null,
    val gpsAgeMs: Long? = null,
    @ColumnInfo(defaultValue = "''") val gpsProvider: String = "",
    /** GpsSource.name. 'NONE' when no coordinate was recorded at all. */
    @ColumnInfo(defaultValue = "'NONE'") val gpsSource: String = "NONE",
    @ColumnInfo(defaultValue = "'MULTISIDE'") val datasetType: String = "MULTISIDE",
)

@Entity(
    tableName = "sides",
    foreignKeys = [ForeignKey(
        entity = TreeEntity::class,
        parentColumns = ["treeKey"],
        childColumns = ["treeKey"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [
        Index("treeKey"),
        Index(value = ["treeKey", "sideIndex"], unique = true),
    ],
)
data class SideEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val treeKey: String,
    val sideIndex: Int,
    val label: String,
    val imageUri: String,
    val imageWidth: Int,
    val imageHeight: Int,
    val labelUri: String? = null,
    @ColumnInfo(defaultValue = "''") val rgbSha256: String = "",
    @ColumnInfo(defaultValue = "'UNKNOWN'") val captureOrigin: String = "UNKNOWN",
    @ColumnInfo(defaultValue = "0") val depthRequired: Boolean = false,
)

@Entity(
    tableName = "bboxes",
    foreignKeys = [ForeignKey(
        entity = SideEntity::class,
        parentColumns = ["id"],
        childColumns = ["sideId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [
        Index("sideId"),
        Index(value = ["sideId", "bboxId"], unique = true),
    ],
)
data class BboxEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sideId: Long,
    val bboxId: String,
    val classId: Int,
    val className: String,
    val x1: Float, val y1: Float,
    val x2: Float, val y2: Float,
    val weightKg: Double? = null,
    val heightCm: Double? = null,
    val circumferenceCm: Double? = null,
    val notes: String? = null,
)

@Entity(
    tableName = "confirmed_links",
    foreignKeys = [ForeignKey(
        entity = TreeEntity::class,
        parentColumns = ["treeKey"],
        childColumns = ["treeKey"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [
        Index("treeKey"),
        Index(value = ["treeKey", "linkId"], unique = true),
        Index(
            value = ["treeKey", "sideA", "bboxIdA", "sideB", "bboxIdB"],
            unique = true,
        ),
    ],
)
data class ConfirmedLinkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val treeKey: String,
    val linkId: String,
    val sideA: Int, val bboxIdA: String,
    val sideB: Int, val bboxIdB: String,
)

@Entity(
    tableName = "capture_drafts",
    foreignKeys = [ForeignKey(
        entity = SessionEntity::class,
        parentColumns = ["sessionId"],
        childColumns = ["runId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("runId")],
)
data class CaptureDraftEntity(
    @PrimaryKey val runId: String,
    val expectedTreeName: String = "",
    val expectedTreeId: Int = 0,
    val sideCount: Int,
    val currentSide: Int = 0,
    val phase: String = "SIDES",
    val step: String = "PREVIEW",
    val status: String = "ACTIVE",
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(
    tableName = "capture_draft_sides",
    primaryKeys = ["runId", "sideIndex"],
    foreignKeys = [ForeignKey(
        entity = CaptureDraftEntity::class,
        parentColumns = ["runId"],
        childColumns = ["runId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("runId")],
)
data class CaptureDraftSideEntity(
    val runId: String,
    val sideIndex: Int,
    val imagePath: String,
    val imageSha256: String,
    val imageWidth: Int,
    val imageHeight: Int,
    val depthRawPath: String? = null,
    val depthJsonPath: String? = null,
    val depthRawSha256: String? = null,
    val depthJsonSha256: String? = null,
    val captureOrigin: String,
    val depthRequired: Boolean,
    val updatedAt: Long,
)

@Entity(
    tableName = "mirror_status",
    foreignKeys = [ForeignKey(
        entity = TreeEntity::class,
        parentColumns = ["treeKey"],
        childColumns = ["treeKey"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [
        Index("treeKey"),
        Index(value = ["status", "updatedAt"]),
    ],
)
data class MirrorStatusEntity(
    @PrimaryKey val treeKey: String,
    val treeName: String,
    val remoteUri: String? = null,
    val requestedRevision: Long,
    val requestedHash: String,
    val status: String = "NOT_REQUESTED",
    val lastAttemptAt: Long? = null,
    val errorCode: String? = null,
    val errorMessage: String? = null,
    val verifiedRevision: Long? = null,
    val verifiedHash: String? = null,
    val updatedAt: Long,
)

/** Durable remote-delete intent. It intentionally has no FK to [TreeEntity]: the tree row is
 * deleted immediately, while this tombstone survives until SAF deletion is verified. */
@Entity(
    tableName = "mirror_deletions",
    indices = [Index(value = ["status", "updatedAt"])],
)
data class MirrorDeletionEntity(
    @PrimaryKey val treeKey: String,
    val treeName: String,
    val remoteUri: String,
    val sideCount: Int,
    val status: String = "DELETE_PENDING",
    val lastAttemptAt: Long? = null,
    val errorCode: String? = null,
    val errorMessage: String? = null,
    val updatedAt: Long,
)

// ─── DAOs ───────────────────────────────────────────────────────────────────

@Dao
interface SessionDao {
    @Query("SELECT * FROM sessions ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<SessionEntity>>

    @Query("SELECT * FROM sessions ORDER BY updatedAt DESC")
    suspend fun getAllOnce(): List<SessionEntity>

    @Query("SELECT * FROM sessions WHERE sessionId = :id")
    suspend fun getById(id: String): SessionEntity?

    @Query("SELECT * FROM sessions WHERE sessionId = :id")
    fun observeById(id: String): Flow<SessionEntity?>

    /** Find an existing run for a variety+block key (C-01: one run per groupKey). */
    @Query("SELECT * FROM sessions WHERE groupKey = :groupKey LIMIT 1")
    suspend fun getByGroupKey(groupKey: String): SessionEntity?

    /**
     * New runs must never use INSERT OR REPLACE. A groupKey conflict under rapid double-submit
     * would DELETE the existing parent row and cascade-delete every captured tree in that run.
     */
    @Insert
    suspend fun insert(session: SessionEntity)

    /**
     * Update an EXISTING run in place. Must NOT go through INSERT-OR-REPLACE:
     * REPLACE on an existing PK is a DELETE+INSERT, which cascade-deletes this
     * run's trees (FK onDelete=CASCADE). Use this to advance nextId etc.
     */
    @Update
    suspend fun update(session: SessionEntity)

    @Query("DELETE FROM sessions WHERE sessionId = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM sessions")
    suspend fun deleteAll()
}

@Dao
interface TreeDao {
    @Query("SELECT * FROM trees ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<TreeEntity>>

    @Query("SELECT * FROM trees WHERE sessionId = :sessionId ORDER BY treeId")
    fun observeBySession(sessionId: String): Flow<List<TreeEntity>>

    @Query("SELECT * FROM trees WHERE sessionId = :sessionId ORDER BY treeId")
    suspend fun getBySession(sessionId: String): List<TreeEntity>

    @Query("SELECT * FROM trees WHERE treeKey = :treeKey")
    suspend fun getByKey(treeKey: String): TreeEntity?

    @Query("SELECT * FROM trees WHERE treeName = :treeName LIMIT 1")
    suspend fun getByName(treeName: String): TreeEntity?

    @Query("SELECT * FROM trees ORDER BY updatedAt DESC")
    suspend fun getAllOnce(): List<TreeEntity>

    /**
     * A duplicate treeName must fail. REPLACE would delete the old tree row and make a partially
     * written same-name re-shoot look committed.
     */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(tree: TreeEntity)

    /**
     * Update an EXISTING tree in place. Must NOT use INSERT-OR-REPLACE: REPLACE on
     * an existing treeKey cascade-deletes this tree's sides/links (FK onDelete=CASCADE).
     */
    @Update
    suspend fun update(tree: TreeEntity)

    @Query("DELETE FROM trees WHERE treeKey = :treeKey")
    suspend fun deleteByKey(treeKey: String)
}

@Dao
interface SideDao {
    @Query("SELECT * FROM sides WHERE treeKey = :treeKey ORDER BY sideIndex")
    suspend fun getByTree(treeKey: String): List<SideEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(side: SideEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(side: SideEntity): Long

    @Query("DELETE FROM sides WHERE treeKey = :treeKey")
    suspend fun deleteByTree(treeKey: String)
}

@Dao
interface BboxDao {
    @Query("SELECT * FROM bboxes WHERE sideId = :sideId ORDER BY id")
    suspend fun getBySide(sideId: Long): List<BboxEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAll(bboxes: List<BboxEntity>)

    @Query("DELETE FROM bboxes WHERE sideId = :sideId")
    suspend fun deleteBySide(sideId: Long)

    @Query("DELETE FROM bboxes WHERE sideId IN (SELECT id FROM sides WHERE treeKey = :treeKey)")
    suspend fun deleteByTree(treeKey: String)
}

@Dao
interface ConfirmedLinkDao {
    @Query("SELECT * FROM confirmed_links WHERE treeKey = :treeKey")
    suspend fun getByTree(treeKey: String): List<ConfirmedLinkEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAll(links: List<ConfirmedLinkEntity>)

    @Query("DELETE FROM confirmed_links WHERE treeKey = :treeKey")
    suspend fun deleteByTree(treeKey: String)
}

@Dao
interface CaptureDraftDao {
    @Query("SELECT * FROM capture_drafts WHERE runId = :runId")
    suspend fun get(runId: String): CaptureDraftEntity?

    /** Insert a new parent without REPLACE: SQLite REPLACE would delete the parent first and
     * cascade-delete every durable side draft. Existing parents are updated in place by the
     * repository's compare-then-insert helper. */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(draft: CaptureDraftEntity)

    @Update
    suspend fun update(draft: CaptureDraftEntity): Int

    @Query("DELETE FROM capture_drafts WHERE runId = :runId")
    suspend fun delete(runId: String)

    @Query("SELECT * FROM capture_draft_sides WHERE runId = :runId ORDER BY sideIndex")
    suspend fun getSides(runId: String): List<CaptureDraftSideEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSide(side: CaptureDraftSideEntity)

    @Query("DELETE FROM capture_draft_sides WHERE runId = :runId AND sideIndex = :sideIndex")
    suspend fun deleteSide(runId: String, sideIndex: Int)
}

@Dao
interface MirrorStatusDao {
    @Query("SELECT * FROM mirror_status WHERE treeKey = :treeKey")
    fun observeByTree(treeKey: String): Flow<MirrorStatusEntity?>

    @Query("SELECT * FROM mirror_status ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<MirrorStatusEntity>>

    @Query("SELECT * FROM mirror_status WHERE status IN ('PENDING', 'FAILED') ORDER BY updatedAt")
    suspend fun getPendingOrFailed(): List<MirrorStatusEntity>

    @Query("SELECT * FROM mirror_status WHERE treeKey = :treeKey")
    suspend fun getByTree(treeKey: String): MirrorStatusEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(status: MirrorStatusEntity)

    /** Conditional worker updates. A stale SAF job must never overwrite a newer revision row. */
    @Query("""
        UPDATE mirror_status SET status = 'PENDING', lastAttemptAt = :attemptAt,
            errorCode = NULL, errorMessage = NULL, updatedAt = :updatedAt
        WHERE treeKey = :treeKey
          AND requestedRevision = :requestedRevision
          AND requestedHash = :requestedHash
          AND ((remoteUri = :remoteUri) OR (remoteUri IS NULL AND :remoteUri IS NULL))
    """)
    suspend fun markAttempting(
        treeKey: String,
        requestedRevision: Long,
        requestedHash: String,
        remoteUri: String?,
        attemptAt: Long,
        updatedAt: Long,
    ): Int

    @Query("""
        UPDATE mirror_status SET status = 'VERIFIED', lastAttemptAt = :attemptAt,
            errorCode = NULL, errorMessage = NULL, verifiedRevision = :verifiedRevision,
            verifiedHash = :verifiedHash, updatedAt = :updatedAt
        WHERE treeKey = :treeKey
          AND requestedRevision = :requestedRevision
          AND requestedHash = :requestedHash
          AND ((remoteUri = :remoteUri) OR (remoteUri IS NULL AND :remoteUri IS NULL))
    """)
    suspend fun markVerified(
        treeKey: String,
        requestedRevision: Long,
        requestedHash: String,
        remoteUri: String?,
        verifiedRevision: Long,
        verifiedHash: String,
        attemptAt: Long,
        updatedAt: Long,
    ): Int

    @Query("""
        UPDATE mirror_status SET status = 'FAILED', lastAttemptAt = :attemptAt,
            errorCode = :errorCode, errorMessage = :errorMessage, updatedAt = :updatedAt
        WHERE treeKey = :treeKey
          AND requestedRevision = :requestedRevision
          AND requestedHash = :requestedHash
          AND ((remoteUri = :remoteUri) OR (remoteUri IS NULL AND :remoteUri IS NULL))
    """)
    suspend fun markFailed(
        treeKey: String,
        requestedRevision: Long,
        requestedHash: String,
        remoteUri: String?,
        errorCode: String?,
        errorMessage: String?,
        attemptAt: Long,
        updatedAt: Long,
    ): Int

    @Query("DELETE FROM mirror_status WHERE treeKey = :treeKey")
    suspend fun deleteByTree(treeKey: String)
}

@Dao
interface MirrorDeletionDao {
    @Query("SELECT * FROM mirror_deletions WHERE status IN ('DELETE_PENDING', 'DELETE_FAILED') ORDER BY updatedAt")
    suspend fun getPendingOrFailed(): List<MirrorDeletionEntity>

    @Query("SELECT * FROM mirror_deletions WHERE treeKey = :treeKey")
    suspend fun getByTree(treeKey: String): MirrorDeletionEntity?

    @Query("SELECT DISTINCT treeName FROM mirror_deletions WHERE remoteUri = :remoteUri AND status IN ('DELETE_PENDING', 'DELETE_FAILED')")
    suspend fun getBlockedTreeNames(remoteUri: String): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(tombstone: MirrorDeletionEntity)

    @Query("""
        UPDATE mirror_deletions SET status = 'DELETE_PENDING', lastAttemptAt = :attemptAt,
            errorCode = NULL, errorMessage = NULL, updatedAt = :updatedAt
        WHERE treeKey = :treeKey AND status IN ('DELETE_PENDING', 'DELETE_FAILED')
          AND remoteUri = :remoteUri
    """)
    suspend fun markAttempting(
        treeKey: String,
        remoteUri: String,
        attemptAt: Long,
        updatedAt: Long,
    ): Int

    @Query("""
        UPDATE mirror_deletions SET status = 'DELETE_VERIFIED', lastAttemptAt = :attemptAt,
            errorCode = NULL, errorMessage = NULL, updatedAt = :updatedAt
        WHERE treeKey = :treeKey AND status = 'DELETE_PENDING' AND remoteUri = :remoteUri
    """)
    suspend fun markVerified(
        treeKey: String,
        remoteUri: String,
        attemptAt: Long,
        updatedAt: Long,
    ): Int

    @Query("""
        UPDATE mirror_deletions SET status = 'DELETE_FAILED', lastAttemptAt = :attemptAt,
            errorCode = :errorCode, errorMessage = :errorMessage, updatedAt = :updatedAt
        WHERE treeKey = :treeKey AND status = 'DELETE_PENDING' AND remoteUri = :remoteUri
    """)
    suspend fun markFailed(
        treeKey: String,
        remoteUri: String,
        errorCode: String?,
        errorMessage: String?,
        attemptAt: Long,
        updatedAt: Long,
    ): Int

    @Query("""
        UPDATE mirror_deletions SET status = 'DELETE_SUPERSEDED', updatedAt = :updatedAt
        WHERE treeKey = :treeKey AND remoteUri = :remoteUri
    """)
    suspend fun markSuperseded(treeKey: String, remoteUri: String, updatedAt: Long): Int
}
