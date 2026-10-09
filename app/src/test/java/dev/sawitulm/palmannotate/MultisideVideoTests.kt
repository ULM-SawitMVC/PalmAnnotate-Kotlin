package dev.sawitulm.palmannotate

import dev.sawitulm.palmannotate.data.export.DatasetZipLayout
import dev.sawitulm.palmannotate.data.export.FileKind
import dev.sawitulm.palmannotate.data.storage.ArtifactIdentityPolicy
import dev.sawitulm.palmannotate.data.storage.copyFileSynced
import dev.sawitulm.palmannotate.domain.model.CaptureSetPolicy
import dev.sawitulm.palmannotate.domain.model.DatasetType
import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MultisideVideoTests {

    @Test
    fun `video runs get their own group key and the other modules keep theirs`() {
        assertEquals("DAMIMAS__A21B", DatasetType.MULTISIDE.runGroupKey("DAMIMAS__A21B"))
        assertEquals("BUNCH_WEIGHT__DAMIMAS__A21B", DatasetType.BUNCH_WEIGHT.runGroupKey("DAMIMAS__A21B"))
        assertEquals(
            "MULTISIDE_VIDEO__DAMIMAS__A21B",
            DatasetType.MULTISIDE_VIDEO.runGroupKey("DAMIMAS__A21B"),
        )
        assertEquals(DatasetType.MULTISIDE_VIDEO, DatasetType.fromPersisted("MULTISIDE_VIDEO"))
    }

    @Test
    fun `only the video module requires a recording and it never finishes early`() {
        assertTrue(DatasetType.MULTISIDE_VIDEO.requiresVideo)
        assertFalse(DatasetType.MULTISIDE.requiresVideo)
        assertFalse(DatasetType.BUNCH_WEIGHT.requiresVideo)
        // Every side is required: the recording has to cover all of them.
        assertFalse(DatasetType.MULTISIDE_VIDEO.allowsEarlyFinish(capturedSides = 1, configuredSides = 4))
    }

    @Test
    fun `video trees get their own name namespace while existing names are untouched`() {
        assertEquals(
            "DAMIMAS_QA0905_0001",
            CaptureSetPolicy.treeName("DAMIMAS", "QA0905", "", 1, DatasetType.MULTISIDE),
        )
        assertEquals(
            "DAMIMAS_QA0905_BW_0001",
            CaptureSetPolicy.treeName("DAMIMAS", "QA0905", "", 1, DatasetType.BUNCH_WEIGHT),
        )
        val video = CaptureSetPolicy.treeName("DAMIMAS", "QA0905", "", 1, DatasetType.MULTISIDE_VIDEO)
        assertEquals("DAMIMAS_QA0905_VID_0001", video)
        assertNotEquals(
            video,
            CaptureSetPolicy.treeName("DAMIMAS", "QA0905", "", 1, DatasetType.MULTISIDE),
        )

        // Same position as the bunch-weight marker, so every derivation still holds.
        val tokenised =
            CaptureSetPolicy.treeName("DAMIMAS", "QA0905", "K7Q2M1", 42, DatasetType.MULTISIDE_VIDEO)
        assertEquals("DAMIMAS_QA0905_VID_K7Q2M1_0042", tokenised)
        assertEquals("DAMIMAS_QA0905_VID_0042", CaptureSetPolicy.logicalTreeName(tokenised, "K7Q2M1"))
        assertEquals(42, tokenised.substringAfterLast('_').toInt())
        assertEquals("QA0905", tokenised.split('_')[1])
        assertNull(ArtifactIdentityPolicy.treeNameError(tokenised))

        // The marker is reserved as a block token in every module, like BW.
        assertNotNull(CaptureSetPolicy.blockError("VID"))
        assertNotNull(CaptureSetPolicy.blockError("BW"))
        assertNull(CaptureSetPolicy.blockError("VID2"))
        assertNull(CaptureSetPolicy.blockError("QA0905"))
    }

    @Test
    fun `the recording is one tree-level zip entry`() {
        val specs = DatasetZipLayout.zipEntriesFor("T_VID_0001", 4)
        val video = specs.single { it.kind == FileKind.VIDEO }
        assertEquals("video/T_VID_0001.mp4", video.zipPath)
        assertNull(video.sideIndex)
    }

    @Test
    fun `streamed copy reproduces a file larger than its buffer and leaves no temp file`() {
        val dir = File.createTempFile("video-copy", "").apply { delete(); mkdirs() }
        try {
            val source = File(dir, "draft.mp4")
            val bytes = ByteArray(300_000) { (it % 251).toByte() }
            source.writeBytes(bytes)
            val target = File(dir, "out/T_VID_0001.mp4")
            target.parentFile!!.mkdirs()
            target.writeText("stale")

            copyFileSynced(source, target)

            assertArrayEquals(bytes, target.readBytes())
            assertTrue("the draft must survive the copy", source.isFile)
            assertFalse(File(target.parentFile, "${target.name}.tmp").exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `a failed copy leaves neither a target nor a temp file`() {
        val dir = File.createTempFile("video-copy", "").apply { delete(); mkdirs() }
        try {
            val target = File(dir, "T_VID_0001.mp4")
            val failure = runCatching { copyFileSynced(File(dir, "missing.mp4"), target) }
            assertTrue(failure.isFailure)
            assertFalse(target.exists())
            assertFalse(File(dir, "${target.name}.tmp").exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    /**
     * Folder resume runs on every Home open, for every module. A rejected package fails the whole
     * resume, so one video tree in the export folder would break resume for multiside and bunch
     * weight as well. Video packages must be skipped without being counted as rejected.
     */
    @Test
    fun `video packages are skipped by folder resume instead of rejected`() {
        val source = repoFile(
            "app/src/main/java/dev/sawitulm/palmannotate/data/storage/FolderResumeImporter.kt",
        ).readText()
        assertTrue(source.contains("val rejected = jsonNames.size - scanned.size - skippedVideo.size"))
        assertTrue(source.contains("if (parsed.datasetType.requiresVideo) {"))
    }

    /** The video stage must be reachable only where the capture route knows the mode up front. */
    @Test
    fun `the video capture route is separate and never starts the Orbbec preview`() {
        val nav = repoFile(
            "app/src/main/java/dev/sawitulm/palmannotate/ui/navigation/Navigation.kt",
        ).readText()
        assertTrue(nav.contains("HomeScreen(datasetType = DatasetType.MULTISIDE_VIDEO"))
        assertTrue(nav.contains("videoMode = true"))
        val capture = repoFile(
            "app/src/main/java/dev/sawitulm/palmannotate/ui/capture/CaptureFlowScreen.kt",
        ).readText()
        assertTrue(
            capture.contains(
                "if (!videoMode && viewModel.captureSource == CaptureSource.ORBBEC && viewModel.orbbecAvailable)",
            ),
        )
    }

    @Test
    fun `a saved video tree opens a viewer from the tree list`() {
        val detail = repoFile(
            "app/src/main/java/dev/sawitulm/palmannotate/ui/session/SessionDetailScreen.kt",
        ).readText()
        assertTrue(detail.contains("if (isVideoDataset) viewingKey = tree.treeKey else onOpenTree(tree.treeKey)"))
        assertTrue(detail.contains("VideoTreeViewer("))
        // The row stays tappable in every module.
        assertTrue(detail.contains(".clickable(onClick = onAnnotate)"))
    }
}
