package dev.sawitulm.palmannotate.data.storage

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Serializes mutations and exports of the local source-of-truth dataset.
 *
 * File writes and Room transactions cannot share one native transaction. Holding this lock from
 * staged-package validation through DB commit, and while creating a local ZIP snapshot, closes the
 * in-process race where two individually valid operations could otherwise create a mixed snapshot.
 * SAF publication happens only after the snapshot is complete and this lock is released.
 */
@Singleton
class ArtifactCoordinator @Inject constructor() {
    private val mutex = Mutex()

    suspend fun <T> withExclusiveAccess(block: suspend () -> T): T =
        mutex.withLock { block() }
}
