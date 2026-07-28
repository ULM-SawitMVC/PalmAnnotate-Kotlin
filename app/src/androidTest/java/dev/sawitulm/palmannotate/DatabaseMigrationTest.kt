package dev.sawitulm.palmannotate

import android.content.Context
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.sawitulm.palmannotate.data.db.PalmAnnotateDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DatabaseMigrationTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @get:Rule
    val helper = MigrationTestHelper(
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation(),
        PalmAnnotateDatabase::class.java.canonicalName!!,
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun migrateV2ThroughV6PreservesRowsAndAddsIdentityBackstops() {
        val db = helper.createDatabase("migration-v2", 2)
        db.execSQL("INSERT INTO sessions VALUES ('run', 'V', 'B', 'V__B', 2, 1, 1, 1, 1)")
        db.execSQL("INSERT INTO trees VALUES ('tree-key', 'run', 'V_B_0001', 1, 'field', 2, 0, 'V', 'B', 1, 1)")
        db.close()
        val migrated = helper.runMigrationsAndValidate(
            "migration-v2", 6, true,
            PalmAnnotateDatabase.MIGRATION_2_3,
            PalmAnnotateDatabase.MIGRATION_3_4,
            PalmAnnotateDatabase.MIGRATION_4_5,
            PalmAnnotateDatabase.MIGRATION_5_6,
        )
        migrated.query("SELECT COUNT(*) FROM sessions").use { assertTrue(it.moveToFirst()); assertEquals(1, it.getInt(0)) }
        migrated.query("PRAGMA index_list('sessions')").use { cursor ->
            var found = false
            while (cursor.moveToNext()) if (cursor.getString(1) == "index_sessions_groupKey") found = true
            assertTrue(found)
        }
        migrated.close()
    }

    @Test
    fun migrateV3ThroughV6PreservesRowsAndAddsPhaseATables() {
        val db = helper.createDatabase("migration-v3", 3)
        db.execSQL("INSERT INTO sessions VALUES ('run', 'V', 'B', 'V__B', 2, 1, 1, 1, 1)")
        db.execSQL("INSERT INTO trees VALUES ('tree-key', 'run', 'V_B_0001', 1, 'field', 2, 0, 'V', 'B', 1, 1)")
        db.execSQL("INSERT INTO sides VALUES (1, 'tree-key', 0, 'Side 1', 'file:///side.jpg', 10, 10, NULL)")
        db.close()

        val migrated = helper.runMigrationsAndValidate(
            "migration-v3", 6, true,
            PalmAnnotateDatabase.MIGRATION_3_4,
            PalmAnnotateDatabase.MIGRATION_4_5,
            PalmAnnotateDatabase.MIGRATION_5_6,
        )
        migrated.query("SELECT COUNT(*) FROM trees").use { assertTrue(it.moveToFirst()); assertEquals(1, it.getInt(0)) }
        migrated.query("SELECT revision FROM trees WHERE treeKey='tree-key'").use { assertTrue(it.moveToFirst()); assertEquals(0, it.getLong(0)) }
        migrated.query("SELECT name FROM sqlite_master WHERE type='table' AND name='capture_drafts'").use { assertTrue(it.moveToFirst()) }
        migrated.query("SELECT name FROM sqlite_master WHERE type='table' AND name='mirror_status'").use { assertTrue(it.moveToFirst()) }
        migrated.query("SELECT name FROM sqlite_master WHERE type='table' AND name='mirror_deletions'").use { assertTrue(it.moveToFirst()) }
        migrated.close()
    }

    @Test(expected = IllegalStateException::class)
    fun duplicateLogicalBboxFailsLoudly() {
        val db = helper.createDatabase("migration-duplicate-bbox", 4)
        seedTreeWithTwoSides(db)
        db.execSQL("INSERT INTO bboxes VALUES (1, 1, 'b0', 1, 'B1', 0, 0, 1, 1)")
        db.execSQL("INSERT INTO bboxes VALUES (2, 1, 'b0', 1, 'B1', 0, 0, 1, 1)")
        db.close()
        helper.runMigrationsAndValidate("migration-duplicate-bbox", 5, true, PalmAnnotateDatabase.MIGRATION_4_5)
    }

    @Test(expected = IllegalStateException::class)
    fun duplicateLogicalLinkEndpointFailsLoudly() {
        val db = helper.createDatabase("migration-duplicate-link-endpoint", 4)
        seedTreeWithTwoSides(db)
        db.execSQL("INSERT INTO bboxes VALUES (1, 1, 'b0', 1, 'B1', 0, 0, 1, 1)")
        db.execSQL("INSERT INTO bboxes VALUES (2, 2, 'b0', 1, 'B1', 0, 0, 1, 1)")
        db.execSQL("INSERT INTO confirmed_links VALUES (1, 'tree-key', 'L0', 0, 'b0', 1, 'b0')")
        db.execSQL("INSERT INTO confirmed_links VALUES (2, 'tree-key', 'L1', 0, 'b0', 1, 'b0')")
        db.close()
        helper.runMigrationsAndValidate("migration-duplicate-link-endpoint", 5, true, PalmAnnotateDatabase.MIGRATION_4_5)
    }

    @Test(expected = IllegalStateException::class)
    fun selfLinkFailsLoudly() {
        val db = helper.createDatabase("migration-self-link", 4)
        seedTreeWithTwoSides(db)
        db.execSQL("INSERT INTO bboxes VALUES (1, 1, 'b0', 1, 'B1', 0, 0, 1, 1)")
        db.execSQL("INSERT INTO confirmed_links VALUES (1, 'tree-key', 'L0', 0, 'b0', 0, 'b0')")
        db.close()
        helper.runMigrationsAndValidate("migration-self-link", 5, true, PalmAnnotateDatabase.MIGRATION_4_5)
    }

    @Test(expected = IllegalStateException::class)
    fun reversedLinkFailsLoudly() {
        val db = helper.createDatabase("migration-reversed-link", 4)
        seedTreeWithTwoSides(db)
        db.execSQL("INSERT INTO bboxes VALUES (1, 1, 'b0', 1, 'B1', 0, 0, 1, 1)")
        db.execSQL("INSERT INTO bboxes VALUES (2, 2, 'b0', 1, 'B1', 0, 0, 1, 1)")
        db.execSQL("INSERT INTO confirmed_links VALUES (1, 'tree-key', 'L0', 1, 'b0', 0, 'b0')")
        db.close()
        helper.runMigrationsAndValidate("migration-reversed-link", 5, true, PalmAnnotateDatabase.MIGRATION_4_5)
    }

    private fun seedTreeWithTwoSides(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("INSERT INTO sessions VALUES ('run', 'V', 'B', 'V__B', 2, 1, 1, 1, 1)")
        db.execSQL("INSERT INTO trees VALUES ('tree-key', 'run', 'V_B_0001', 1, 'field', 2, 0, 'V', 'B', 1, 1)")
        db.execSQL("INSERT INTO sides VALUES (1, 'tree-key', 0, 'Side 1', 'a', 10, 10, NULL, '', 'UNKNOWN', 0)")
        db.execSQL("INSERT INTO sides VALUES (2, 'tree-key', 1, 'Side 2', 'b', 10, 10, NULL, '', 'UNKNOWN', 0)")
    }

    @Test(expected = IllegalStateException::class)
    fun downgradeWithoutExplicitMigrationFailsClosed() {
        val db = helper.createDatabase("migration-downgrade", 5)
        db.close()
        helper.runMigrationsAndValidate("migration-downgrade", 4, true)
    }

    @Test(expected = IllegalStateException::class)
    fun duplicateLogicalSideFailsLoudlyWithoutChoosingARow() {
        val db = helper.createDatabase("migration-duplicate-side", 4)
        db.execSQL("INSERT INTO sessions VALUES ('run', 'V', 'B', 'V__B', 2, 1, 1, 1, 1)")
        db.execSQL("INSERT INTO trees VALUES ('tree-key', 'run', 'V_B_0001', 1, 'field', 2, 0, 'V', 'B', 1, 1)")
        db.execSQL("INSERT INTO sides VALUES (1, 'tree-key', 0, 'Side 1', 'a', 10, 10, NULL, '', 'UNKNOWN', 0)")
        db.execSQL("INSERT INTO sides VALUES (2, 'tree-key', 0, 'Side 1 duplicate', 'b', 10, 10, NULL, '', 'UNKNOWN', 0)")
        db.close()
        helper.runMigrationsAndValidate("migration-duplicate-side", 5, true, PalmAnnotateDatabase.MIGRATION_4_5)
    }
}
