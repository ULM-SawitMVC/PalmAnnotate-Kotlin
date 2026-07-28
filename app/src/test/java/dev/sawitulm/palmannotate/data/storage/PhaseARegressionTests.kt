package dev.sawitulm.palmannotate.data.storage

import dev.sawitulm.palmannotate.repoFile
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArraySet

class PhaseARegressionTest {
    @Test
    fun `provider access failure cannot verify a remote delete`() {
        val result = SafDeleteVerifier.verify(
            paths = listOf("dataset/metadata/tree.json"),
            inspect = { SafPathState.Inaccessible(IllegalStateException("revoked")) },
            delete = { error("delete must not run when inspection failed") },
        )

        assertTrue(result is SafDeleteResult.Failed)
    }

    @Test
    fun `unknown SAF MIME is never classified as a missing path`() {
        assertTrue(SafDocumentTypePolicy.classifyMimeType(null) == SafDocumentType.UNKNOWN)
        assertTrue(SafDocumentTypePolicy.classifyMimeType("") == SafDocumentType.UNKNOWN)
        assertTrue(
            SafDocumentTypePolicy.classifyMimeType("vnd.android.document/directory") ==
                SafDocumentType.DIRECTORY,
        )
        assertTrue(SafDocumentTypePolicy.classifyMimeType("image/jpeg") == SafDocumentType.FILE)
    }

    @Test
    fun `typed resume outcomes distinguish empty folder from provider failure`() {
        val empty = SafListingResult.Success(emptyList())
        val failure = SafListingResult.Inaccessible(IllegalStateException("revoked"))
        assertTrue(empty is SafListingResult.Success && empty.names.isEmpty())
        assertTrue(failure is SafListingResult.Inaccessible)
        assertTrue(runCatching {
            SafResumeOutcomePolicy.requireListing(failure, "Output JSON")
        }.exceptionOrNull() is SafResumeException)
        assertTrue(runCatching {
            SafResumeOutcomePolicy.optionalBytes(
                SafReadResult.Inaccessible(IllegalStateException("revoked")),
                "Output JSON/tree.json",
            )
        }.exceptionOrNull() is SafResumeException)
    }

    @Test
    fun `remote delete is verified only after a fresh absent result`() {
        var present = true
        val result = SafDeleteVerifier.verify(
            paths = listOf("dataset/metadata/tree.json"),
            inspect = { if (present) SafPathState.Present else SafPathState.Absent },
            delete = {
                present = false
                true
            },
        )

        assertTrue(result === SafDeleteResult.Verified)
    }

    @Test
    fun `save gate waits for accepted draft writes including a later registration`() = runBlocking {
        val firstGate = CompletableDeferred<Unit>()
        val secondGate = CompletableDeferred<Unit>()
        val jobs = CopyOnWriteArraySet<Job>()
        val first = launch {
            firstGate.await()
            secondGate.await()
        }
        jobs += first
        first.invokeOnCompletion { jobs.remove(first) }
        var saved = false
        val save = launch {
            DraftWriteAwaiter.awaitAll { jobs.toList() }
            saved = true
        }

        yield()
        assertFalse(saved)
        val second = launch { firstGate.await() }
        jobs += second
        second.invokeOnCompletion { jobs.remove(second) }
        yield()
        assertFalse(saved)

        firstGate.complete(Unit)
        yield()
        assertFalse(saved)
        secondGate.complete(Unit)
        save.join()
        assertTrue(saved)
    }

    @Test
    fun `SAF mirror copies the committed annot log instead of regenerating it`() {
        val source = repoFile(
            "app/src/main/java/dev/sawitulm/palmannotate/data/storage/SessionRepository.kt",
        ).readText()
        val mirror = source.substringAfter("private fun mirrorSafArtifacts(")
            .substringBefore("// ─── Output JSON")

        assertTrue(mirror.contains("storage.annotLogFile(treeName, side.sideIndex)"))
        assertFalse(mirror.contains("buildAnnotLog(treeName, split, side)"))
    }

    @Test
    fun `carousel finalization performs one checked complete commit`() {
        val source = repoFile(
            "app/src/main/java/dev/sawitulm/palmannotate/ui/carousel/CarouselScreen.kt",
        ).readText()
        val saveAndExit = source.substringAfter("fun saveAndExit(onDone: () -> Unit)")
            .substringBefore("/** Save before opening another editor/viewer")

        assertFalse(saveAndExit.contains("repo.saveSession("))
        assertTrue(saveAndExit.contains("persistLatest(markComplete = true)"))
        assertTrue(saveAndExit.contains("is SaveResult.Success"))
    }

    @Test
    fun `predictive back is enabled and dedup canvas does not steal pager drags`() {
        val manifest = repoFile("app/src/main/AndroidManifest.xml").readText()
        val dedup = repoFile(
            "app/src/main/java/dev/sawitulm/palmannotate/ui/dedup/DeduplicationScreen.kt",
        ).readText()
        val halfCanvas = dedup.substringAfter("private fun DedupHalfCanvas(")

        assertTrue(manifest.contains("android:enableOnBackInvokedCallback=\"true\""))
        assertTrue(halfCanvas.contains("tool = CanvasTool.VIEW"))
        assertFalse(halfCanvas.contains("tool = CanvasTool.SELECT"))
    }

    @Test
    fun `remote mirror requires exclusive tree reservation before overwrite`() {
        val store = repoFile(
            "app/src/main/java/dev/sawitulm/palmannotate/data/storage/SafMirrorStore.kt",
        ).readText()
        val repository = repoFile(
            "app/src/main/java/dev/sawitulm/palmannotate/data/storage/SessionRepository.kt",
        ).readText()

        assertTrue(store.contains("fun createTextExclusively("))
        assertTrue(repository.contains("ensureRemoteReservation(tree, session, uri)"))
        assertTrue(repository.contains("Remote tree path is owned by another capture"))
    }

    @Test
    fun `newer canonical revision never receives an older journal rollback`() {
        val repository = repoFile(
            "app/src/main/java/dev/sawitulm/palmannotate/data/storage/SessionRepository.kt",
        ).readText()
        val recovery = repository.substringAfter("private suspend fun recoverPendingLocalRevisionsLocked()")
            .substringBefore("private fun validateConfirmedLinks")

        assertTrue(recovery.contains("tree.revision < pending.revision"))
        assertTrue(recovery.contains("storage.rollbackRevision(pending)"))
        assertTrue(recovery.contains("storage.discardRevisionState(pending)"))
    }

    @Test
    fun `CameraX writes recoverable unique incoming draft files`() {
        val capture = repoFile(
            "app/src/main/java/dev/sawitulm/palmannotate/ui/capture/CaptureFlowScreen.kt",
        ).readText()
        val repository = repoFile(
            "app/src/main/java/dev/sawitulm/palmannotate/data/storage/SessionRepository.kt",
        ).readText()

        assertTrue(capture.contains("createOutputFile = viewModel::preparePhoneCaptureFile"))
        assertTrue(capture.contains("UUID.randomUUID().toString()"))
        assertTrue(repository.contains("suspend fun recoverIncomingPhoneCaptures(runId: String)"))
    }

    @Test
    fun `a rejected package is reported only after the importable ones are committed`() {
        val source = repoFile(
            "app/src/main/java/dev/sawitulm/palmannotate/data/storage/FolderResumeImporter.kt",
        ).readText()
        val resume = source.substringAfter("suspend fun resumeFromFolder(safTreeUri: Uri): Int")
            .substringBefore("// ─── internals")
        val ingestIndex = resume.indexOf("val ok = ingestTree(")
        val finalReportIndex = resume.lastIndexOf("Rejected \$rejected")

        assertTrue(ingestIndex > 0)
        assertTrue(finalReportIndex > ingestIndex)
    }

    @Test
    fun `valid persisted side upgrades stale preview cursor`() {
        assertTrue(CaptureDraftCursorPolicy.restoreStep("PREVIEW", hasValidSide = true) == "REVIEW")
        assertTrue(CaptureDraftCursorPolicy.restoreStep("PREVIEW", hasValidSide = false) == "PREVIEW")
    }
}
