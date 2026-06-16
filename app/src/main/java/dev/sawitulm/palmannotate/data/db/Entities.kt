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
@Entity(tableName = "sessions")
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
    indices = [Index("sessionId")],
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
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(
    tableName = "sides",
    foreignKeys = [ForeignKey(
        entity = TreeEntity::class,
        parentColumns = ["treeKey"],
        childColumns = ["treeKey"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("treeKey")],
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
)

@Entity(
    tableName = "bboxes",
    foreignKeys = [ForeignKey(
        entity = SideEntity::class,
        parentColumns = ["id"],
        childColumns = ["sideId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("sideId")],
)
data class BboxEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sideId: Long,
    val bboxId: String,
    val classId: Int,
    val className: String,
    val x1: Float, val y1: Float,
    val x2: Float, val y2: Float,
)

@Entity(
    tableName = "confirmed_links",
    foreignKeys = [ForeignKey(
        entity = TreeEntity::class,
        parentColumns = ["treeKey"],
        childColumns = ["treeKey"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("treeKey")],
)
data class ConfirmedLinkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val treeKey: String,
    val linkId: String,
    val sideA: Int, val bboxIdA: String,
    val sideB: Int, val bboxIdB: String,
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

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(session: SessionEntity)

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

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(tree: TreeEntity)

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

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(side: SideEntity): Long

    @Query("DELETE FROM sides WHERE treeKey = :treeKey")
    suspend fun deleteByTree(treeKey: String)
}

@Dao
interface BboxDao {
    @Query("SELECT * FROM bboxes WHERE sideId = :sideId ORDER BY id")
    suspend fun getBySide(sideId: Long): List<BboxEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
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

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(links: List<ConfirmedLinkEntity>)

    @Query("DELETE FROM confirmed_links WHERE treeKey = :treeKey")
    suspend fun deleteByTree(treeKey: String)
}
