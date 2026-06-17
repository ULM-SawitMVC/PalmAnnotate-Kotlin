package dev.sawitulm.palmannotate

import dev.sawitulm.palmannotate.data.export.DatasetZipLayout
import dev.sawitulm.palmannotate.data.export.FileKind
import org.junit.Assert.*
import org.junit.Test

// ════════════════════════════════════════════════════════════════════════════════
// DatasetZipLayout — the pure (I/O-free) naming + archive layout of the ZIP exporter.
// The streaming/SAF/FileProvider parts stay device-verified; this guards the names.
// ════════════════════════════════════════════════════════════════════════════════

class ZipSanitizeTest {
    @Test fun `strips unsafe characters`() {
        assertEquals("TeneraA1", DatasetZipLayout.sanitize("Tenera A1/"))
        assertEquals("abcde", DatasetZipLayout.sanitize("a/b\\c:d*e"))
    }

    @Test fun `trims stray underscores but keeps inner ones and dashes`() {
        assertEquals("abc", DatasetZipLayout.sanitize("__abc__"))
        assertEquals("A-21_B", DatasetZipLayout.sanitize("A-21_B"))
    }
}

class ZipLayoutTest {
    @Test fun `entry count is four per side plus two tree-level files`() {
        assertEquals(4 * 2 + 2, DatasetZipLayout.zipEntriesFor("T_0001", 2).size)
        assertEquals(4 * 4 + 2, DatasetZipLayout.zipEntriesFor("T_0001", 4).size)
    }

    @Test fun `paths follow the flat training layout with 1-based side numbers`() {
        val paths = DatasetZipLayout.zipEntriesFor("T_0001", 2).map { it.zipPath }.toSet()
        assertTrue(paths.contains("images/T_0001_1.jpg"))
        assertTrue(paths.contains("images/T_0001_2.jpg"))
        assertTrue(paths.contains("labels/T_0001_2.txt"))
        assertTrue(paths.contains("depth/T_0001_1.raw"))
        assertTrue(paths.contains("depth/T_0001_2.json"))
        assertTrue(paths.contains("json/T_0001.json"))
        assertTrue(paths.contains("metadata/T_0001.json"))
    }

    @Test fun `per-side specs carry a side index and tree-level specs do not`() {
        val specs = DatasetZipLayout.zipEntriesFor("T_0001", 3)
        assertTrue(specs.filter { it.kind == FileKind.IMAGE }.all { it.sideIndex != null })
        assertEquals(listOf(0, 1, 2), specs.filter { it.kind == FileKind.LABEL }.map { it.sideIndex })
        assertNull(specs.first { it.kind == FileKind.OUTPUT_JSON }.sideIndex)
        assertNull(specs.first { it.kind == FileKind.METADATA }.sideIndex)
    }
}
