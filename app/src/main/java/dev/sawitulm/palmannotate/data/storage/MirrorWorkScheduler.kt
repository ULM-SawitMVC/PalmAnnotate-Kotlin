package dev.sawitulm.palmannotate.data.storage

import kotlinx.coroutines.Job

/**
 * Serial queue used for best-effort SAF work.
 *
 * The repository deliberately never joins a job returned by this queue while it holds
 * [ArtifactCoordinator]. Tests can replace it with a controllable queue to prove that invariant.
 */
fun interface MirrorWorkScheduler {
    fun enqueue(block: suspend () -> Unit): Job
}
