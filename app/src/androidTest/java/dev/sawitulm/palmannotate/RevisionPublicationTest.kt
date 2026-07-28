package dev.sawitulm.palmannotate

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.sawitulm.palmannotate.data.storage.AndroidStorageManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RevisionPublicationTest {
    @Test
    fun journaledPublicationKeepsPreviousPackageUntilManifestReplacement() {
        val storage = AndroidStorageManager(ApplicationProvider.getApplicationContext())
        val treeName = "PHASEA_TEST_${System.nanoTime()}"
        val oldLabel = storage.labelFile(treeName, 0)
        val oldManifest = storage.manifestFile(treeName)
        storage.writeText(oldLabel, "old-label")
        storage.writeText(oldManifest, "old-manifest")
        val stage = storage.revisionStagingDir(treeName, 1)
        val stagedLabel = java.io.File(stage, "labels/field/${treeName}_1.txt")
        val stagedManifest = java.io.File(stage, "manifests/$treeName.json")
        storage.writeText(stagedLabel, "new-label")
        storage.writeText(stagedManifest, "new-manifest")
        val entries = listOf(stagedLabel to oldLabel, stagedManifest to oldManifest)

        storage.prepareRevisionJournal(treeName, 1, entries)
        assertTrue(storage.pendingRevisions().any { it.treeName == treeName && it.revision == 1L })
        assertEquals("old-label", oldLabel.readText())
        assertEquals("old-manifest", oldManifest.readText())

        storage.publishRevision(treeName, 1, entries)
        assertEquals("new-label", oldLabel.readText())
        assertEquals("new-manifest", oldManifest.readText())
        assertTrue(storage.pendingRevisions().none { it.treeName == treeName })
        storage.deleteTree(treeName, 1)
    }

    @Test
    fun emptyTextProjectionIsAValidRevisionArtifact() {
        val storage = AndroidStorageManager(ApplicationProvider.getApplicationContext())
        val treeName = "PHASEA_EMPTY_${System.nanoTime()}"
        val oldLabel = storage.labelFile(treeName, 0)
        val oldManifest = storage.manifestFile(treeName)
        storage.writeText(oldLabel, "old")
        storage.writeText(oldManifest, "old-manifest")
        val stage = storage.revisionStagingDir(treeName, 3)
        val stagedLabel = java.io.File(stage, "labels/field/${treeName}_1.txt")
        val stagedManifest = java.io.File(stage, "manifests/$treeName.json")
        storage.writeText(stagedLabel, "")
        storage.writeText(stagedManifest, "new-manifest")
        val entries = listOf(stagedLabel to oldLabel, stagedManifest to oldManifest)

        storage.publishRevision(treeName, 3, entries)
        assertTrue(oldLabel.isFile)
        assertEquals("", oldLabel.readText())
        assertEquals("new-manifest", oldManifest.readText())
        storage.deleteTree(treeName, 1)
    }

    @Test
    fun rollbackRestoresPreviousFilesAndDeletesNewTargets() {
        val storage = AndroidStorageManager(ApplicationProvider.getApplicationContext())
        val treeName = "PHASEA_ROLLBACK_${System.nanoTime()}"
        val oldLabel = storage.labelFile(treeName, 0)
        storage.writeText(oldLabel, "old")
        val stage = storage.revisionStagingDir(treeName, 2)
        val stagedLabel = java.io.File(stage, "labels/field/${treeName}_1.txt")
        val stagedManifest = java.io.File(stage, "manifests/$treeName.json")
        storage.writeText(stagedLabel, "new")
        storage.writeText(stagedManifest, "new-manifest")
        val manifest = storage.manifestFile(treeName)
        val entries = listOf(stagedLabel to oldLabel, stagedManifest to manifest)
        storage.prepareRevisionJournal(treeName, 2, entries)
        val pending = storage.pendingRevisions().single { it.treeName == treeName }
        storage.rollbackRevision(pending)
        assertEquals("old", oldLabel.readText())
        assertTrue(!manifest.exists())
        storage.deleteTree(treeName, 1)
    }
}
