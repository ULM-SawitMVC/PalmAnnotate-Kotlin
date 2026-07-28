package dev.sawitulm.palmannotate

import org.json.JSONObject
import org.junit.Assert.assertEquals
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
