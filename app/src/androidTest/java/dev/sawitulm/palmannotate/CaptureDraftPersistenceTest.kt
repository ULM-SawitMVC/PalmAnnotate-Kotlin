package dev.sawitulm.palmannotate

import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.sawitulm.palmannotate.data.db.CaptureDraftEntity
import dev.sawitulm.palmannotate.data.db.PalmAnnotateDatabase
import dev.sawitulm.palmannotate.data.db.SessionEntity
import dev.sawitulm.palmannotate.data.db.TreeEntity
import dev.sawitulm.palmannotate.data.storage.AndroidStorageManager
import dev.sawitulm.palmannotate.data.storage.ArtifactCoordinator
import dev.sawitulm.palmannotate.data.storage.SafMirrorStore
import dev.sawitulm.palmannotate.data.storage.SessionRepository
import dev.sawitulm.palmannotate.domain.model.CaptureOrigin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CaptureDraftPersistenceTest {
    @Test
    fun cursorUpdatePreservesAllChildSides() = runBlocking {
        val (db, repo) = repository()
        val now = System.currentTimeMillis()
        db.sessionDao().insert(SessionEntity("run", "V", "B", "V__B", 2, true, 1, now, now))
        repo.ensureCaptureDraft("run", 2, "V_B_0001", 1)
        repo.persistCaptureDraftSide("run", 0, repo.beginCaptureDraftSideWrite("run", 0), byteArrayOf(1), 1, 1, CaptureOrigin.PHONE_CAMERA, false)
        repo.persistCaptureDraftSide("run", 1, repo.beginCaptureDraftSideWrite("run", 1), byteArrayOf(2), 1, 1, CaptureOrigin.PHONE_CAMERA, false)
        repo.updateCaptureDraftCursor("run", 1, "SIDES", "PREVIEW", "V_B_0001", 1)

        val draft = repo.loadCaptureDraft("run")
        assertEquals(2, draft?.sides?.size)
        assertEquals(1, draft?.currentSide)
        assertEquals("REVIEW", draft?.step)
        db.close()
    }

    @Test
    fun invalidSideDoesNotHideValidSide() = runBlocking {
        val (db, repo) = repository()
        val now = System.currentTimeMillis()
        db.sessionDao().insert(SessionEntity("run", "V", "B", "V__B", 2, true, 1, now, now))
        repo.ensureCaptureDraft("run", 2, "V_B_0001", 1)
        repo.persistCaptureDraftSide("run", 0, repo.beginCaptureDraftSideWrite("run", 0), byteArrayOf(1), 1, 1, CaptureOrigin.PHONE_CAMERA, false)
        repo.persistCaptureDraftSide("run", 1, repo.beginCaptureDraftSideWrite("run", 1), byteArrayOf(2), 1, 1, CaptureOrigin.PHONE_CAMERA, false)
        val invalidImage = AndroidStorageManager(ApplicationProvider.getApplicationContext())
            .captureDraftImageFile("run", 1)
        invalidImage.writeBytes(byteArrayOf(9, 9, 9))

        val draft = repo.loadCaptureDraft("run")
        assertEquals("INVALID", draft?.status)
        assertEquals(listOf(0), draft?.sides?.map { it.sideIndex })
        assertTrue(db.captureDraftDao().getSides("run").size == 2)
        db.close()
    }

    @Test
    fun acceptedRepairRevalidatesEveryDraftSide() = runBlocking {
        val (db, repo) = repository()
        val now = System.currentTimeMillis()
        db.sessionDao().insert(SessionEntity("run", "V", "B", "V__B", 2, true, 1, now, now))
        repo.ensureCaptureDraft("run", 2, "V_B_0001", 1)
        repo.persistCaptureDraftSide("run", 0, repo.beginCaptureDraftSideWrite("run", 0), byteArrayOf(1), 1, 1, CaptureOrigin.PHONE_CAMERA, false)
        repo.persistCaptureDraftSide("run", 1, repo.beginCaptureDraftSideWrite("run", 1), byteArrayOf(2), 1, 1, CaptureOrigin.PHONE_CAMERA, false)
        val invalidImage = AndroidStorageManager(ApplicationProvider.getApplicationContext())
            .captureDraftImageFile("run", 1)
        invalidImage.writeBytes(byteArrayOf(9, 9, 9))
        assertEquals("INVALID", repo.loadCaptureDraft("run")?.status)

        val repaired = repo.persistCaptureDraftSide(
            "run", 1, repo.beginCaptureDraftSideWrite("run", 1), byteArrayOf(2), 1, 1,
            CaptureOrigin.PHONE_CAMERA, false,
        )
        assertTrue(repaired)
        val restored = repo.loadCaptureDraft("run")
        assertEquals("ACTIVE", restored?.status)
        assertEquals(2, restored?.sides?.size)
        db.close()
    }

    @Test
    fun versionedDraftUriChangesWhenAcceptedBytesChange() {
        val storage = AndroidStorageManager(ApplicationProvider.getApplicationContext())
        val file = storage.captureDraftImageFile("uri-test", 0)
        val first = storage.versionedImageUri(file, byteArrayOf(1))
        val second = storage.versionedImageUri(file, byteArrayOf(2))
        assertNotEquals(first.toString(), second.toString())
        storage.discardUncommittedDraft("uri-test")
    }

    @Test
    fun staleGenerationIsRejectedBeforePublishingSharedDraftPath() = runBlocking {
        val (db, repo) = repository()
        val now = System.currentTimeMillis()
        db.sessionDao().insert(SessionEntity("run", "V", "B", "V__B", 2, true, 1, now, now))
        repo.ensureCaptureDraft("run", 2, "V_B_0001", 1)
        val staleGeneration = repo.beginCaptureDraftSideWrite("run", 0)
        repo.invalidateCaptureDraftSide("run", 0)

        val accepted = repo.persistCaptureDraftSide(
            "run", 0, staleGeneration, byteArrayOf(9), 1, 1,
            CaptureOrigin.PHONE_CAMERA, false,
        )

        assertFalse(accepted)
        assertTrue(db.captureDraftDao().getSides("run").isEmpty())
        db.close()
    }

    @Test
    fun discardInvalidatesQueuedWriteWithoutRecreatingDraft() = runBlocking {
        val (db, repo) = repository()
        val now = System.currentTimeMillis()
        db.sessionDao().insert(SessionEntity("run", "V", "B", "V__B", 2, true, 1, now, now))
        repo.ensureCaptureDraft("run", 2, "V_B_0001", 1)
        val staleGeneration = repo.beginCaptureDraftSideWrite("run", 0)

        repo.discardCaptureDraft("run")
        val accepted = repo.persistCaptureDraftSide(
            "run", 0, staleGeneration, byteArrayOf(9), 1, 1,
            CaptureOrigin.PHONE_CAMERA, false,
        )

        assertFalse(accepted)
        assertTrue(db.captureDraftDao().get("run") == null)
        assertTrue(db.captureDraftDao().getSides("run").isEmpty())
        db.close()
    }

    @Test
    fun roomTransactionRollsBackTreeInsertAfterFailure() = runBlocking {
        val (db, _) = repository()
        val now = System.currentTimeMillis()
        db.sessionDao().insert(SessionEntity("run", "V", "B", "V__B", 2, true, 1, now, now))
        runCatching {
            db.withTransaction {
                db.treeDao().insert(TreeEntity("tree", "run", "V_B_0001", 1, createdAt = now, updatedAt = now))
                error("injected failure")
            }
        }
        assertTrue(db.treeDao().getByKey("tree") == null)
        db.close()
    }

    private fun repository(): Pair<PalmAnnotateDatabase, SessionRepository> {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val db = Room.inMemoryDatabaseBuilder(context, PalmAnnotateDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val storage = AndroidStorageManager(context)
        return db to SessionRepository(
            db.sessionDao(), db.treeDao(), db.sideDao(), db.bboxDao(), db.confirmedLinkDao(),
            storage, SafMirrorStore(context), db, ArtifactCoordinator(),
            db.mirrorStatusDao(), db.captureDraftDao(),
        )
    }
}
