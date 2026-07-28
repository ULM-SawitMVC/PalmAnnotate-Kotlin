package dev.sawitulm.palmannotate.data.storage

import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll

/** Durable SAF state values. `isComplete` is intentionally not one of these states. */
object MirrorStates {
    const val NOT_REQUESTED = "NOT_REQUESTED"
    const val PENDING = "PENDING"
    const val VERIFIED = "VERIFIED"
    const val FAILED = "FAILED"

    // Durable delete-tombstone states. They are separate from mirror revision states so a deleted
    // tree remains retryable after its Room row and ordinary mirror_status row are gone.
    const val DELETE_PENDING = "DELETE_PENDING"
    const val DELETE_VERIFIED = "DELETE_VERIFIED"
    const val DELETE_FAILED = "DELETE_FAILED"
    const val DELETE_SUPERSEDED = "DELETE_SUPERSEDED"
}

/** Result of a provider lookup. An error is never treated as an absent remote file. */
sealed interface SafPathState {
    data object Present : SafPathState
    data object Absent : SafPathState
    data class Inaccessible(val cause: Throwable? = null) : SafPathState
}

sealed interface SafListingResult {
    data class Success(val names: List<String>) : SafListingResult
    data object Absent : SafListingResult
    data class Inaccessible(val cause: Throwable? = null) : SafListingResult
}

sealed interface SafReadResult {
    data class Success(val bytes: ByteArray) : SafReadResult
    data object Absent : SafReadResult
    data class Inaccessible(val cause: Throwable? = null) : SafReadResult
}

class SafResumeException(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)

enum class SafDocumentType { DIRECTORY, FILE, UNKNOWN }

object SafDocumentTypePolicy {
    fun classifyMimeType(mimeType: String?): SafDocumentType = when {
        mimeType.isNullOrBlank() -> SafDocumentType.UNKNOWN
        mimeType == "vnd.android.document/directory" -> SafDocumentType.DIRECTORY
        else -> SafDocumentType.FILE
    }
}

object SafResumeOutcomePolicy {
    fun requireListing(result: SafListingResult, path: String): List<String> = when (result) {
        is SafListingResult.Success -> result.names
        SafListingResult.Absent -> throw SafResumeException("SAF resume directory is missing: $path")
        is SafListingResult.Inaccessible -> throw SafResumeException(
            "SAF resume directory is inaccessible: $path",
            result.cause,
        )
    }

    fun optionalBytes(result: SafReadResult, path: String): ByteArray? = when (result) {
        is SafReadResult.Success -> result.bytes
        SafReadResult.Absent -> null
        is SafReadResult.Inaccessible -> throw SafResumeException(
            "SAF resume file is inaccessible: $path",
            result.cause,
        )
    }
}

sealed interface SafDeleteResult {
    data object Verified : SafDeleteResult
    data class Failed(val message: String, val cause: Throwable? = null) : SafDeleteResult
}

/** Provider-independent delete verification used by [SafMirrorStore] and JVM regression tests. */
object SafDeleteVerifier {
    fun verify(
        paths: Iterable<String>,
        inspect: (String) -> SafPathState,
        delete: (String) -> Boolean,
    ): SafDeleteResult {
        for (path in paths) {
            when (val before = inspect(path)) {
                SafPathState.Absent -> Unit
                is SafPathState.Inaccessible ->
                    return SafDeleteResult.Failed("Cannot inspect remote path $path", before.cause)
                SafPathState.Present -> {
                    if (!delete(path)) {
                        return SafDeleteResult.Failed("Cannot delete remote path $path")
                    }
                    when (val after = inspect(path)) {
                        SafPathState.Absent -> Unit
                        is SafPathState.Inaccessible ->
                            return SafDeleteResult.Failed("Cannot verify remote deletion of $path", after.cause)
                        SafPathState.Present ->
                            return SafDeleteResult.Failed("Remote path remains after deletion: $path")
                    }
                }
            }
        }
        return SafDeleteResult.Verified
    }
}

/** A valid side takes precedence over a stale PREVIEW cursor persisted before its write. */
object CaptureDraftCursorPolicy {
    fun restoreStep(persistedStep: String, hasValidSide: Boolean): String =
        if (hasValidSide && persistedStep == "PREVIEW") "REVIEW" else persistedStep
}

/** Waits for every accepted draft write, including writes registered while the first batch drains. */
object DraftWriteAwaiter {
    suspend fun awaitAll(jobProvider: () -> List<Job>) {
        while (true) {
            val jobs = jobProvider()
            if (jobs.isEmpty()) return
            jobs.joinAll()
        }
    }
}

sealed interface SaveResult {
    data class Success(val revision: Long) : SaveResult
    data class Conflict(val expectedRevision: Long, val actualRevision: Long) : SaveResult
    data class Failure(val message: String, val cause: Throwable? = null) : SaveResult
}

data class CaptureDraftSideSnapshot(
    val sideIndex: Int,
    val imagePath: String,
    val imageSha256: String,
    val imageWidth: Int,
    val imageHeight: Int,
    val depthRawPath: String?,
    val depthJsonPath: String?,
    val depthRawSha256: String?,
    val depthJsonSha256: String?,
    val captureOrigin: String,
    val depthRequired: Boolean,
)

data class CaptureDraftSnapshot(
    val runId: String,
    val expectedTreeName: String,
    val expectedTreeId: Int,
    val sideCount: Int,
    val currentSide: Int,
    val phase: String,
    val step: String,
    val status: String,
    val sides: List<CaptureDraftSideSnapshot>,
)
