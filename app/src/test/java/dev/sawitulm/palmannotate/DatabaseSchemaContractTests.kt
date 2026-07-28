package dev.sawitulm.palmannotate

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Guards the exported Room schema that is required to audit future migrations.
 *
 * This is intentionally a JVM test: it catches a missing/stale schema artifact in CI without
 * needing an Android device. It does not replace an on-device MigrationTestHelper test.
 */
class DatabaseSchemaContractTest {

    private fun schemaV6(): JSONObject {
        val relative = "app/schemas/dev.sawitulm.palmannotate.data.db.PalmAnnotateDatabase/6.json"
        val start = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        val schema = generateSequence(start) { it.parentFile }
            .map { File(it, relative) }
            .firstOrNull(File::isFile)
        assertNotNull("Room schema v6 must be committed at $relative", schema)
        return JSONObject(schema!!.readText())
    }

    private fun entity(table: String): JSONObject {
        val entities = schemaV6().getJSONObject("database").getJSONArray("entities")
        return (0 until entities.length())
            .map { entities.getJSONObject(it) }
            .first { it.getString("tableName") == table }
    }

    private fun index(table: String, name: String): JSONObject {
        val indices = entity(table).getJSONArray("indices")
        return (0 until indices.length())
            .map { indices.getJSONObject(it) }
            .first { it.getString("name") == name }
    }

    @Test fun `schema artifact declares database version 6`() {
        assertEquals(1, schemaV6().getInt("formatVersion"))
        assertEquals(6, schemaV6().getJSONObject("database").getInt("version"))
    }

    @Test fun `sessions group key index is unique`() {
        val index = index("sessions", "index_sessions_groupKey")
        assertTrue(index.getBoolean("unique"))
        assertEquals(listOf("groupKey"), index.getJSONArray("columnNames").toStringList())
    }

    @Test fun `tree name index is unique`() {
        val index = index("trees", "index_trees_treeName")
        assertTrue(index.getBoolean("unique"))
        assertEquals(listOf("treeName"), index.getJSONArray("columnNames").toStringList())
    }

    @Test fun `phase A tables and logical identity indices are present`() {
        val entities = schemaV6().getJSONObject("database").getJSONArray("entities")
        val tables = (0 until entities.length()).map { entities.getJSONObject(it).getString("tableName") }
        assertTrue(tables.contains("capture_drafts"))
        assertTrue(tables.contains("capture_draft_sides"))
        assertTrue(tables.contains("mirror_status"))
        assertTrue(tables.contains("mirror_deletions"))
        assertTrue(index("sides", "index_sides_treeKey_sideIndex").getBoolean("unique"))
        assertTrue(index("bboxes", "index_bboxes_sideId_bboxId").getBoolean("unique"))
        assertTrue(index("confirmed_links", "index_confirmed_links_treeKey_linkId").getBoolean("unique"))
    }

    @Test fun `side schema persists capture provenance and integrity fields`() {
        val fields = entity("sides").getJSONArray("fields")
        val byName = (0 until fields.length())
            .map(fields::getJSONObject)
            .associateBy { it.getString("columnName") }
        assertEquals("''", byName.getValue("rgbSha256").getString("defaultValue"))
        assertEquals(
            "'UNKNOWN'",
            byName.getValue("captureOrigin").getString("defaultValue"),
        )
        assertEquals("0", byName.getValue("depthRequired").getString("defaultValue"))
    }

    private fun org.json.JSONArray.toStringList(): List<String> =
        (0 until length()).map(::getString)
}

/**
 * WS-12 + WS-13 — v7 adds capture-set identity and capture provenance.
 *
 * The v6 assertions above are kept and still read 6.json: a committed schema is a historical
 * record, so the guard against a stale v6 artifact stays meaningful after the bump.
 */
class DatabaseSchemaV7ContractTest {

    private fun schemaV7(): JSONObject {
        val relative = "app/schemas/dev.sawitulm.palmannotate.data.db.PalmAnnotateDatabase/7.json"
        val start = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        val schema = generateSequence(start) { it.parentFile }
            .map { File(it, relative) }
            .firstOrNull(File::isFile)
        assertNotNull("Room schema v7 must be committed at $relative", schema)
        return JSONObject(schema!!.readText())
    }

    private fun fields(table: String): Map<String, JSONObject> {
        val entities = schemaV7().getJSONObject("database").getJSONArray("entities")
        val entity = (0 until entities.length())
            .map { entities.getJSONObject(it) }
            .first { it.getString("tableName") == table }
        val fields = entity.getJSONArray("fields")
        return (0 until fields.length())
            .map(fields::getJSONObject)
            .associateBy { it.getString("columnName") }
    }

    @Test fun `schema artifact declares database version 7`() {
        assertEquals(7, schemaV7().getJSONObject("database").getInt("version"))
    }

    @Test fun `run identity columns exist with empty legacy defaults`() {
        val sessions = fields("sessions")
        for (column in listOf("captureSetId", "deviceToken", "nameToken", "operatorName")) {
            assertEquals(
                "sessions.$column must default to empty for pre-WS-12 rows",
                "''",
                sessions.getValue(column).getString("defaultValue"),
            )
            assertTrue(sessions.getValue(column).getBoolean("notNull"))
        }
    }

    @Test fun `tree identity columns exist with empty legacy defaults`() {
        val trees = fields("trees")
        for (column in listOf("captureSetId", "deviceToken", "nameToken", "captureDate", "operatorName", "gpsProvider")) {
            assertEquals("''", trees.getValue(column).getString("defaultValue"))
        }
    }

    // The defaults ARE the compatibility argument: a migrated row must say "no claim", never a
    // fabricated FRESH fix or a back-filled capture date.
    @Test fun `migrated rows default to an explicit unknown GPS provenance`() {
        val trees = fields("trees")
        assertEquals("'UNKNOWN'", trees.getValue("gpsStatus").getString("defaultValue"))
        assertEquals("'NONE'", trees.getValue("gpsSource").getString("defaultValue"))
    }

    @Test fun `coordinate columns are nullable so a missing fix is not zero-zero`() {
        val trees = fields("trees")
        for (column in listOf("gpsLatitude", "gpsLongitude", "gpsAccuracyM", "gpsFixTimeMillis", "gpsAgeMs")) {
            assertTrue("trees.$column must be nullable", !trees.getValue(column).getBoolean("notNull"))
        }
    }

    @Test fun `v7 changes no index and adds no table`() {
        // Additive-only migration: the identity backstops from earlier versions must be intact
        // and unchanged, otherwise the migration is doing more than ALTER TABLE ADD COLUMN.
        val entities = schemaV7().getJSONObject("database").getJSONArray("entities")
        val tables = (0 until entities.length()).map { entities.getJSONObject(it).getString("tableName") }
        assertEquals(
            listOf(
                "sessions", "trees", "sides", "bboxes", "confirmed_links",
                "capture_drafts", "capture_draft_sides", "mirror_status", "mirror_deletions",
            ).sorted(),
            tables.sorted(),
        )
    }

    @Test fun `the migration is registered so an upgrade is never destructive`() {
        val source = repoFile(
            "app/src/main/java/dev/sawitulm/palmannotate/data/db/PalmAnnotateDatabase.kt",
        ).readText()
        assertTrue(source.contains("MIGRATION_6_7"))
        assertTrue("MIGRATION_6_7 must be in ALL_MIGRATIONS", source.substringAfter("ALL_MIGRATIONS").contains("MIGRATION_6_7"))
        assertFalse(
            "a fallback would delete the operator's dataset on upgrade",
            source.contains("fallbackToDestructiveMigration"),
        )
        // Every new column must arrive via ADD COLUMN — a table rebuild would risk the rows.
        val migration = source.substringAfter("val MIGRATION_6_7").substringBefore("val ALL_MIGRATIONS")
        assertFalse(migration.contains("DROP TABLE"))
        assertFalse(migration.contains("CREATE TABLE"))
        assertEquals(17, Regex("ADD COLUMN").findAll(migration).count())
    }
}
