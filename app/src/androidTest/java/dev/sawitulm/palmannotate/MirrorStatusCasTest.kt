package dev.sawitulm.palmannotate

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.sawitulm.palmannotate.data.db.MirrorStatusEntity
import dev.sawitulm.palmannotate.data.db.PalmAnnotateDatabase
import dev.sawitulm.palmannotate.data.db.SessionEntity
import dev.sawitulm.palmannotate.data.db.TreeEntity
import dev.sawitulm.palmannotate.data.storage.MirrorStates
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MirrorStatusCasTest {
    @Test
    fun staleWorkerCannotCompleteNewerRevision() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val db = Room.inMemoryDatabaseBuilder(context, PalmAnnotateDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val now = System.currentTimeMillis()
        db.sessionDao().insert(SessionEntity("run", "V", "B", "V__B", 2, true, 1, now, now))
        db.treeDao().insert(TreeEntity("tree", "run", "V_B_0001", 1, createdAt = now, updatedAt = now))
        val dao = db.mirrorStatusDao()
        dao.upsert(MirrorStatusEntity("tree", "V_B_0001", "content://old", 1, "hash-1", MirrorStates.PENDING, updatedAt = now))
        dao.upsert(MirrorStatusEntity("tree", "V_B_0001", "content://new", 2, "hash-2", MirrorStates.PENDING, updatedAt = now + 1))

        val staleResult = dao.markVerified(
            treeKey = "tree", requestedRevision = 1, requestedHash = "hash-1", remoteUri = "content://old",
            verifiedRevision = 1, verifiedHash = "hash-1", attemptAt = now + 2, updatedAt = now + 2,
        )
        assertEquals(0, staleResult)
        assertTrue(dao.getByTree("tree")!!.status == MirrorStates.PENDING)
        assertEquals(2L, dao.getByTree("tree")!!.requestedRevision)
        db.close()
    }
}
