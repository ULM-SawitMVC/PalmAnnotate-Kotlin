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
    ],
    version = 3,
    exportSchema = true,
)
abstract class PalmAnnotateDatabase : RoomDatabase() {
    abstract fun sessionDao(): SessionDao
    abstract fun treeDao(): TreeDao
    abstract fun sideDao(): SideDao
    abstract fun bboxDao(): BboxDao
    abstract fun confirmedLinkDao(): ConfirmedLinkDao

    companion object {
        const val DB_NAME = "palmannotate.db"

        /**
         * v2 → v3: add the unique indices that enforce data integrity (C-01 backstop):
         *   - sessions.groupKey unique  → one run per variety+block
         *   - trees.treeName   unique  → no cross-run file-name collision
         *
         * Index names + uniqueness MUST match what Room derives from the @Index annotations in
         * [SessionEntity]/[TreeEntity], or Room's identity-hash validation will reject the DB.
         *
         * NOTE: if the on-device DB already holds duplicate groupKey/treeName rows (leftover from
         * pre-fix testing), CREATE UNIQUE INDEX fails and this migration throws — that is a LOUD,
         * intentional failure (never a silent wipe). Clear app data once before installing v3.
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_sessions_groupKey` ON `sessions` (`groupKey`)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_trees_treeName` ON `trees` (`treeName`)")
            }
        }

        fun create(context: Context): PalmAnnotateDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                PalmAnnotateDatabase::class.java,
                DB_NAME,
            )
                // Explicit, NON-destructive migration. fallbackToDestructiveMigration() was removed:
                // it silently dropped every table on any schema bump (or downgrade), which would
                // wipe all annotations/links in the field. A missing migration now fails loud.
                .addMigrations(MIGRATION_2_3)
                .build()
    }
}
