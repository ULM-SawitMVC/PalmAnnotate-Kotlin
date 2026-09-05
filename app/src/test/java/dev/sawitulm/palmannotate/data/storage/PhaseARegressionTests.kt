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
        assertTrue(saveAndExit.contains("persistAndExit(markComplete = true"))
        assertTrue(saveAndExit.contains("persistLatest(markComplete)"))
        assertTrue(saveAndExit.contains("is SaveResult.Success"))
    }

    /**
     * BUG-001: `returnToReviewAll` gated the phase switch on `allCaptured`, so a bunch-weight
     * sample deliberately finished with one photo had a permanently null second slot and Done
     * stranded the operator on the single-side review. Retake must use the same dataset photo
     * rule the Use-1-photo button uses.
     */
    @Test
    fun `retake returns to review all through the dataset photo count rule`() {
        val source = repoFile(
            "app/src/main/java/dev/sawitulm/palmannotate/ui/capture/CaptureFlowScreen.kt",
        ).readText()
        val returnToReviewAll = source.substringAfter("fun returnToReviewAll()")
            .substringBefore("private fun persistDraftCursor(")
        val finishEarly = source.substringAfter("private val canFinishEarly: Boolean")
            .substringBefore("fun retakeSide(")

        assertTrue(returnToReviewAll.contains("allCaptured || canFinishEarly"))
        assertTrue(returnToReviewAll.contains("CapturePhase.REVIEW_ALL"))
        assertTrue(returnToReviewAll.contains("SideStep.REVIEW"))
        // One rule, one implementation: Done and Use-1-photo cannot drift apart.
        assertTrue(finishEarly.contains("allowsEarlyFinish("))
        assertTrue(finishEarly.contains("if (!canFinishEarly) return false"))
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
    fun `capture preview owns side navigation while metadata stays in the top bar`() {
        val capture = repoFile(
            "app/src/main/java/dev/sawitulm/palmannotate/ui/capture/CaptureFlowScreen.kt",
        ).readText()
        val screen = capture.substringAfter("fun CaptureFlowScreen(")
            .substringBefore("private fun CapturedThumbnails(")
        val topBarStart = screen.indexOf("TopAppBar(")
        val contentStart = screen.indexOf(") { padding ->", topBarStart)
        val previewStart = screen.indexOf("Box(", contentStart)
        val previewContentStart = screen.indexOf("if (viewModel.captureSource", previewStart)
        val metadata = screen.indexOf("R.string.capture_locked_format")
        val sideNavigation = screen.indexOf("CapturedThumbnails(")

        assertTrue(metadata in topBarStart until contentStart)
        assertTrue(sideNavigation in previewStart until previewContentStart)
    }

    @Test
    fun `application starts at the dataset module chooser`() {
        val navigation = repoFile(
            "app/src/main/java/dev/sawitulm/palmannotate/ui/navigation/Navigation.kt",
        ).readText()

        assertTrue(navigation.contains("startDestination = Routes.MODULES"))
        assertTrue(navigation.contains("ModuleHubScreen("))
        assertTrue(navigation.contains("HomeScreen(datasetType = DatasetType.MULTISIDE"))
        assertTrue(navigation.contains("HomeScreen(datasetType = DatasetType.BUNCH_WEIGHT"))
    }

    @Test
    fun `an open capture draft cannot veto or be consumed by a resume import`() {
        val repository = repoFile(
            "app/src/main/java/dev/sawitulm/palmannotate/data/storage/SessionRepository.kt",
        ).readText()
        val importer = repoFile(
            "app/src/main/java/dev/sawitulm/palmannotate/data/storage/FolderResumeImporter.kt",
        ).readText()

        assertTrue(repository.contains("consumesCaptureDraft: Boolean = true"))
        assertTrue(
            repository.contains("captureDraftDao.get(sessionId).takeIf { consumesCaptureDraft }"),
        )
        assertTrue(repository.contains("if (consumesCaptureDraft &&"))
        assertTrue(importer.contains("consumesCaptureDraft = false"))
    }

    @Test
    fun `an unknown identity on either side never blocks the whole folder resume`() {
        val source = repoFile(
            "app/src/main/java/dev/sawitulm/palmannotate/data/storage/FolderResumeImporter.kt",
        ).readText()
        val report = source.substringAfter("private suspend fun reportIdentityCollisions(")
            .substringBefore("private fun manifestMatchesMirror(")

        // A blank identity on either side leaves the mapping before any conflict is built.
        assertTrue(
            report.contains("if (!local.hasIdentity || tree.identity.captureSetId.isBlank())"),
        )
        assertTrue(report.contains("return@mapNotNull null"))
        // A proved conflict still fails closed.
        assertTrue(report.contains("throw SafResumeException"))
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
