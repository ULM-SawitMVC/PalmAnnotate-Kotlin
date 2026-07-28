package dev.sawitulm.palmannotate.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        SessionEntity::class,
        TreeEntity::class,
        SideEntity::class,
        BboxEntity::class,
        ConfirmedLinkEntity::class,
        CaptureDraftEntity::class,
        CaptureDraftSideEntity::class,
        MirrorStatusEntity::class,
        MirrorDeletionEntity::class,
    ],
    version = 7,
    exportSchema = true,
)
abstract class PalmAnnotateDatabase : RoomDatabase() {
    abstract fun sessionDao(): SessionDao
    abstract fun treeDao(): TreeDao
    abstract fun sideDao(): SideDao
    abstract fun bboxDao(): BboxDao
    abstract fun confirmedLinkDao(): ConfirmedLinkDao
    abstract fun captureDraftDao(): CaptureDraftDao
    abstract fun mirrorStatusDao(): MirrorStatusDao
    abstract fun mirrorDeletionDao(): MirrorDeletionDao

    companion object {
        const val DB_NAME = "palmannotate.db"

        private fun failIfDuplicates(
            db: SupportSQLiteDatabase,
            table: String,
            columns: List<String>,
            label: String,
        ) {
            val projection = columns.joinToString(", ") { "`$it`" }
            val groupBy = columns.joinToString(", ") { "`$it`" }
            db.query(
                "SELECT $projection, COUNT(*) AS duplicateCount FROM `$table` " +
                    "GROUP BY $groupBy HAVING COUNT(*) > 1 LIMIT 1",
            ).use { cursor ->
                if (cursor.moveToFirst()) {
                    val key = columns.map { column ->
                        val index = cursor.getColumnIndexOrThrow(column)
                        "$column=${cursor.getString(index)}"
                    }.joinToString(", ")
                    val count = cursor.getInt(cursor.getColumnIndexOrThrow("duplicateCount"))
                    throw IllegalStateException(
                        "Room migration 4->5 aborted: duplicate $label ($key, rows=$count). " +
                            "Export or remove the ambiguous rows, then retry; no rows were deleted.",
                    )
                }
            }
        }

        private fun failIfBrokenLinkEndpoints(db: SupportSQLiteDatabase) {
            db.query(
                "SELECT l.id FROM confirmed_links l " +
                    "LEFT JOIN sides sa ON sa.treeKey = l.treeKey AND sa.sideIndex = l.sideA " +
                    "LEFT JOIN bboxes ba ON ba.sideId = sa.id AND ba.bboxId = l.bboxIdA " +
                    "LEFT JOIN sides sb ON sb.treeKey = l.treeKey AND sb.sideIndex = l.sideB " +
                    "LEFT JOIN bboxes bb ON bb.sideId = sb.id AND bb.bboxId = l.bboxIdB " +
                    "WHERE ba.id IS NULL OR bb.id IS NULL LIMIT 1",
            ).use { cursor ->
                if (cursor.moveToFirst()) {
                    throw IllegalStateException(
                        "Room migration 4->5 aborted: confirmed_links contains a missing side/bbox endpoint " +
                            "(id=${cursor.getLong(0)}). No rows were deleted.",
                    )
                }
            }
            db.query(
                "SELECT id, sideA, sideB FROM confirmed_links WHERE sideA >= sideB LIMIT 1",
            ).use { cursor ->
                if (cursor.moveToFirst()) {
                    throw IllegalStateException(
                        "Room migration 4->5 aborted: confirmed_links contains a self-link or " +
                            "non-canonical endpoint order (id=${cursor.getLong(0)}, " +
                            "sideA=${cursor.getInt(1)}, sideB=${cursor.getInt(2)}). No rows were deleted.",
                    )
                }
            }
            db.query(
                "SELECT a.id FROM confirmed_links a " +
                    "JOIN confirmed_links b ON a.treeKey = b.treeKey AND a.id < b.id " +
                    "AND a.sideA = b.sideB AND a.bboxIdA = b.bboxIdB " +
                    "AND a.sideB = b.sideA AND a.bboxIdB = b.bboxIdA LIMIT 1",
            ).use { cursor ->
                if (cursor.moveToFirst()) {
                    throw IllegalStateException(
                        "Room migration 4->5 aborted: confirmed_links contains reciprocal endpoint rows " +
                            "(id=${cursor.getLong(0)}). No rows were deleted.",
                    )
                }
            }
        }

        /** v2 -> v3: the historical group/tree identity backstops. */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                failIfDuplicates(db, "sessions", listOf("groupKey"), "session groupKey")
                failIfDuplicates(db, "trees", listOf("treeName"), "tree name")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_sessions_groupKey` ON `sessions` (`groupKey`)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_trees_treeName` ON `trees` (`treeName`)")
            }
        }

        /** v3 -> v4: per-side capture provenance and RGB content identity. */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `sides` ADD COLUMN `rgbSha256` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `sides` ADD COLUMN `captureOrigin` TEXT NOT NULL DEFAULT 'UNKNOWN'")
                db.execSQL("ALTER TABLE `sides` ADD COLUMN `depthRequired` INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * v4 -> v5 is one additive migration for Phase A. Unique indices are deliberately created
         * only after deterministic duplicate and endpoint checks. SQLite aborts the migration on
         * the first ambiguous legacy key; it never chooses or deletes an annotation.
         */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                failIfDuplicates(db, "sides", listOf("treeKey", "sideIndex"), "logical side")
                failIfDuplicates(db, "bboxes", listOf("sideId", "bboxId"), "logical bbox")
                failIfDuplicates(db, "confirmed_links", listOf("treeKey", "linkId"), "link id")
                failIfDuplicates(
                    db,
                    "confirmed_links",
                    listOf("treeKey", "sideA", "bboxIdA", "sideB", "bboxIdB"),
                    "link endpoint pair",
                )
                failIfBrokenLinkEndpoints(db)

                db.execSQL("ALTER TABLE `trees` ADD COLUMN `revision` INTEGER NOT NULL DEFAULT 0")
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_sides_treeKey_sideIndex` " +
                        "ON `sides` (`treeKey`, `sideIndex`)",
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_bboxes_sideId_bboxId` " +
                        "ON `bboxes` (`sideId`, `bboxId`)",
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_confirmed_links_treeKey_linkId` " +
                        "ON `confirmed_links` (`treeKey`, `linkId`)",
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_confirmed_links_treeKey_sideA_bboxIdA_sideB_bboxIdB` " +
                        "ON `confirmed_links` (`treeKey`, `sideA`, `bboxIdA`, `sideB`, `bboxIdB`)",
                )

                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `capture_drafts` (" +
                        "`runId` TEXT NOT NULL, `expectedTreeName` TEXT NOT NULL, " +
                        "`expectedTreeId` INTEGER NOT NULL, `sideCount` INTEGER NOT NULL, " +
                        "`currentSide` INTEGER NOT NULL, `phase` TEXT NOT NULL, `step` TEXT NOT NULL, " +
                        "`status` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`runId`), FOREIGN KEY(`runId`) REFERENCES `sessions`(`sessionId`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE)",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_capture_drafts_runId` ON `capture_drafts` (`runId`)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `capture_draft_sides` (" +
                        "`runId` TEXT NOT NULL, `sideIndex` INTEGER NOT NULL, `imagePath` TEXT NOT NULL, " +
                        "`imageSha256` TEXT NOT NULL, `imageWidth` INTEGER NOT NULL, `imageHeight` INTEGER NOT NULL, " +
                        "`depthRawPath` TEXT, `depthJsonPath` TEXT, `depthRawSha256` TEXT, `depthJsonSha256` TEXT, " +
                        "`captureOrigin` TEXT NOT NULL, `depthRequired` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`runId`, `sideIndex`), FOREIGN KEY(`runId`) REFERENCES `capture_drafts`(`runId`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE)",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_capture_draft_sides_runId` ON `capture_draft_sides` (`runId`)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `mirror_status` (" +
                        "`treeKey` TEXT NOT NULL, `treeName` TEXT NOT NULL, `remoteUri` TEXT, " +
                        "`requestedRevision` INTEGER NOT NULL, `requestedHash` TEXT NOT NULL, " +
                        "`status` TEXT NOT NULL, `lastAttemptAt` INTEGER, `errorCode` TEXT, " +
                        "`errorMessage` TEXT, `verifiedRevision` INTEGER, `verifiedHash` TEXT, " +
                        "`updatedAt` INTEGER NOT NULL, PRIMARY KEY(`treeKey`), " +
                        "FOREIGN KEY(`treeKey`) REFERENCES `trees`(`treeKey`) ON UPDATE NO ACTION ON DELETE CASCADE)",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_mirror_status_treeKey` ON `mirror_status` (`treeKey`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_mirror_status_status_updatedAt` ON `mirror_status` (`status`, `updatedAt`)")
            }
        }

        /** v5 -> v6: durable remote-delete tombstones without a tree FK. */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `mirror_deletions` (" +
                        "`treeKey` TEXT NOT NULL, `treeName` TEXT NOT NULL, `remoteUri` TEXT NOT NULL, " +
                        "`sideCount` INTEGER NOT NULL, `status` TEXT NOT NULL, `lastAttemptAt` INTEGER, " +
                        "`errorCode` TEXT, `errorMessage` TEXT, `updatedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`treeKey`))",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_mirror_deletions_status_updatedAt` " +
                        "ON `mirror_deletions` (`status`, `updatedAt`)",
                )
            }
        }

        /**
         * v6 -> v7: WS-12 capture-set identity + WS-13 capture/GPS/operator provenance.
         *
         * Additive only: ALTER TABLE ADD COLUMN, no table rebuild, no index change, no row
         * touched. Every existing run/tree keeps its data and lands on the "unknown provenance"
         * defaults — '' for identity and operator, 'UNKNOWN'/'NONE' for GPS, NULL for the
         * coordinates. Nothing is back-filled: a tree captured before this migration genuinely
         * has no recorded operator or fix age, and claiming otherwise would be worse than
         * admitting it. The columns are nullable/defaulted so a downgrade path is not needed.
         */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `sessions` ADD COLUMN `captureSetId` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `sessions` ADD COLUMN `deviceToken` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `sessions` ADD COLUMN `nameToken` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `sessions` ADD COLUMN `operatorName` TEXT NOT NULL DEFAULT ''")

                db.execSQL("ALTER TABLE `trees` ADD COLUMN `captureSetId` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `trees` ADD COLUMN `deviceToken` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `trees` ADD COLUMN `nameToken` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `trees` ADD COLUMN `captureDate` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `trees` ADD COLUMN `operatorName` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `trees` ADD COLUMN `gpsStatus` TEXT NOT NULL DEFAULT 'UNKNOWN'")
                db.execSQL("ALTER TABLE `trees` ADD COLUMN `gpsLatitude` REAL")
                db.execSQL("ALTER TABLE `trees` ADD COLUMN `gpsLongitude` REAL")
                db.execSQL("ALTER TABLE `trees` ADD COLUMN `gpsAccuracyM` REAL")
                db.execSQL("ALTER TABLE `trees` ADD COLUMN `gpsFixTimeMillis` INTEGER")
                db.execSQL("ALTER TABLE `trees` ADD COLUMN `gpsAgeMs` INTEGER")
                db.execSQL("ALTER TABLE `trees` ADD COLUMN `gpsProvider` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `trees` ADD COLUMN `gpsSource` TEXT NOT NULL DEFAULT 'NONE'")
            }
        }

        val ALL_MIGRATIONS: Array<Migration> = arrayOf(
            MIGRATION_2_3,
            MIGRATION_3_4,
            MIGRATION_4_5,
            MIGRATION_5_6,
            MIGRATION_6_7,
        )

        fun create(context: Context): PalmAnnotateDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                PalmAnnotateDatabase::class.java,
                DB_NAME,
            )
                .addMigrations(*ALL_MIGRATIONS)
                .build()
    }
}
