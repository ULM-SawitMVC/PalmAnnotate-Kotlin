package dev.sawitulm.palmannotate

import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.sawitulm.palmannotate.data.db.MirrorStatusEntity
import dev.sawitulm.palmannotate.data.db.PalmAnnotateDatabase
import dev.sawitulm.palmannotate.data.db.SessionEntity
import dev.sawitulm.palmannotate.data.db.TreeEntity
import dev.sawitulm.palmannotate.data.storage.AndroidStorageManager
import dev.sawitulm.palmannotate.data.storage.ArtifactCoordinator
import dev.sawitulm.palmannotate.data.storage.MirrorStates
import dev.sawitulm.palmannotate.data.storage.MirrorWorkScheduler
import dev.sawitulm.palmannotate.data.storage.SafMirrorStore
import dev.sawitulm.palmannotate.data.storage.SessionRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicBoolean

/** Proves delete never waits behind a queued mirror task that is waiting for the same lock. */
@RunWith(AndroidJUnit4::class)
class SessionRepositoryDeleteConcurrencyTest {
    @Test
    fun deleteCommitsLocallyWithoutJoiningQueuedRemoteDelete() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val db = Room.inMemoryDatabaseBuilder(context, PalmAnnotateDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val coordinator = ArtifactCoordinator()
        val blockerGate = CompletableDeferred<Unit>()
        val blockerStarted = CompletableDeferred<Unit>()
        // `armed` is written on the test thread and read on the Dispatchers.IO thread that runs
        // deleteTree, so it must carry its own happens-before. A plain captured `var` could stay
        // false on the reader, leaving the blocker suspended forever.
        val schedulerArmed = AtomicBoolean(false)
        val scheduler = ControlledScheduler {
            if (schedulerArmed.get()) {
                // This callback runs from deleteTree while ArtifactCoordinator is held. Releasing
                // the earlier worker makes it contend for that lock, exactly the old inversion.
                blockerGate.complete(Unit)
            }
        }
        val storage = AndroidStorageManager(context)
        val repo = SessionRepository(
            db.sessionDao(), db.treeDao(), db.sideDao(), db.bboxDao(), db.confirmedLinkDao(),
            storage, SafMirrorStore(context), db, coordinator, db.mirrorStatusDao(),
            db.captureDraftDao(), scheduler,
        )
        // Constructor recovery/reconciliation is deliberately not run in this controlled queue.
        scheduler.clear()
        val now = System.currentTimeMillis()
        db.sessionDao().insert(SessionEntity("run", "V", "B", "V__B", 2, true, 2, now, now))
        db.treeDao().insert(TreeEntity("tree", "run", "V_B_0001", 1, sideCount = 2, createdAt = now, updatedAt = now))
        db.mirrorStatusDao().upsert(
            MirrorStatusEntity(
                treeKey = "tree", treeName = "V_B_0001", remoteUri = "content://remote",
                requestedRevision = 1, requestedHash = "hash", status = MirrorStates.PENDING,
                updatedAt = now,
            ),
        )

        // A prior queued mirror has started and is waiting for the coordinator.
        scheduler.enqueue {
            blockerStarted.complete(Unit)
            blockerGate.await()
            coordinator.withExclusiveAccess { }
        }
        val blocker = launch(Dispatchers.Default) { scheduler.runNext() }
        withTimeout(LIVENESS_TIMEOUT_MS) { blockerStarted.await() }
        schedulerArmed.set(true)

        // The old implementation joined this queued delete job while still holding the lock.
        withTimeout(LIVENESS_TIMEOUT_MS) { repo.deleteTree("tree") }
        assertNull(db.treeDao().getByKey("tree"))
        val tombstone = db.mirrorDeletionDao().getByTree("tree")
        assertNotNull(tombstone)
        assertEquals(MirrorStates.DELETE_PENDING, tombstone!!.status)
        assertEquals(1, scheduler.pendingCount())

        // Releasing the coordinator must let the queued worker finish. Bounded so a regression
        // reports a failure instead of hanging the whole instrumentation run.
        withTimeout(LIVENESS_TIMEOUT_MS) { blocker.join() }
        db.close()
    }

    @Test
    fun deleteUsesPersistedMirrorUriWhenExportFolderChanged() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val db = Room.inMemoryDatabaseBuilder(context, PalmAnnotateDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val scheduler = ControlledScheduler {}
        val now = System.currentTimeMillis()
        db.sessionDao().insert(SessionEntity("run", "V", "B", "V__B", 2, true, 2, now, now))
        db.treeDao().insert(TreeEntity("tree", "run", "V_B_0001", 1, sideCount = 2, createdAt = now, updatedAt = now))
        db.mirrorStatusDao().upsert(
            MirrorStatusEntity(
                treeKey = "tree", treeName = "V_B_0001", remoteUri = "content://folder-a",
                requestedRevision = 1, requestedHash = "hash", status = MirrorStates.VERIFIED,
                updatedAt = now,
            ),
        )
        val repo = SessionRepository(
            db.sessionDao(), db.treeDao(), db.sideDao(), db.bboxDao(), db.confirmedLinkDao(),
            AndroidStorageManager(context), SafMirrorStore(context), db, ArtifactCoordinator(),
            db.mirrorStatusDao(), db.captureDraftDao(), scheduler,
        )
        scheduler.clear()

        repo.deleteTree("tree", Uri.parse("content://folder-b"))

        assertEquals("content://folder-a", db.mirrorDeletionDao().getByTree("tree")?.remoteUri)
        db.close()
    }

    private class ControlledScheduler(
        private val onEnqueue: () -> Unit,
    ) : MirrorWorkScheduler {
        // enqueue() runs on the repository's Dispatchers.IO thread while runNext()/pendingCount()
        // run on other threads, so every touch of the deque is guarded.
        private val queue = ArrayDeque<Pair<suspend () -> Unit, CompletableDeferred<Unit>>>()

        override fun enqueue(block: suspend () -> Unit): Job {
            val completion = CompletableDeferred<Unit>()
            synchronized(queue) { queue.addLast(block to completion) }
            onEnqueue()
            return completion
        }

        suspend fun runNext() {
            val (block, completion) = synchronized(queue) { queue.removeFirst() }
            try {
                block()
                completion.complete(Unit)
            } catch (error: Throwable) {
                completion.completeExceptionally(error)
                throw error
            }
        }

        fun clear() = synchronized(queue) { queue.clear() }
        fun pendingCount() = synchronized(queue) { queue.size }
    }

    private companion object {
        /**
         * Generous on purpose. The assertion is liveness — delete must not wait on a queued mirror
         * task — not latency. The CI emulator is a 2-core swiftshader image running the whole
         * instrumentation suite, where a filesystem delete plus a Room transaction can take
         * seconds; a 2 s budget failed there while the production path was correct.
         */
        const val LIVENESS_TIMEOUT_MS = 30_000L
    }
}
