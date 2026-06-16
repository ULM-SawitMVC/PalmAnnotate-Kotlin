package dev.sawitulm.palmannotate.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        SessionEntity::class,
        TreeEntity::class,
        SideEntity::class,
        BboxEntity::class,
        ConfirmedLinkEntity::class,
    ],
    version = 2,
    exportSchema = false,
)
abstract class PalmAnnotateDatabase : RoomDatabase() {
    abstract fun sessionDao(): SessionDao
    abstract fun treeDao(): TreeDao
    abstract fun sideDao(): SideDao
    abstract fun bboxDao(): BboxDao
    abstract fun confirmedLinkDao(): ConfirmedLinkDao

    companion object {
        const val DB_NAME = "palmannotate.db"

        fun create(context: Context): PalmAnnotateDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                PalmAnnotateDatabase::class.java,
                DB_NAME,
            )
                // Debug app: the v1 (session==tree) schema is replaced by the v2
                // (session=run with many trees) schema. No production data to migrate.
                .fallbackToDestructiveMigration()
                .build()
    }
}
