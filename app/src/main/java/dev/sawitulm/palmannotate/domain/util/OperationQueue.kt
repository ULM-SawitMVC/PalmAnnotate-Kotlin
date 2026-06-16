package dev.sawitulm.palmannotate.domain.util

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.cancellation.CancellationException

/**
 * Serializes async work so navigation/save/compute/next-tree never interleave.
 * Port of JS `_enqueueOperation(fn)` + `_setBusy(flag, label)`.
 */
class OperationQueue {

    private companion object { const val TAG = "OperationQueue" }

    // A handler so an exception that escapes a queued block (e.g. a failed save) is
    // logged, NOT delivered to the thread's default uncaught handler — which on
    // Android crashes the whole app. Belt-and-suspenders with the in-block catch below.
    private val errorHandler = CoroutineExceptionHandler { _, e ->
        Log.e(TAG, "Uncaught exception in queued operation", e)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + errorHandler)

    /** Run [block], swallowing failures (logged) so a single op can never crash the app.
     *  CancellationException is rethrown so structured cancellation still works. */
    private suspend fun runGuarded(label: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Throwable) {
            Log.e(TAG, "Operation '$label' failed", e)
        }
    }
    /** Monotonically incrementing counter for unique linkId generation. */
    private val _nextLinkId = AtomicInteger(0)

    fun nextLinkId(): String = "lnk-${_nextLinkId.incrementAndGet()}"

    private var lastJob: Job = Job().also { it.complete() } // already-completed seed

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy

    private val _busyLabel = MutableStateFlow("")
    val busyLabel: StateFlow<String> = _busyLabel

    fun enqueue(label: String = "", block: suspend () -> Unit) {
        val prev = lastJob
        lastJob = scope.launch {
            prev.join()
            _busy.value = true
            _busyLabel.value = label
            try {
                runGuarded(label, block)
            } finally {
                _busy.value = false
                _busyLabel.value = ""
            }
        }
    }

    suspend fun enqueueAndWait(label: String = "", block: suspend () -> Unit) {
        val prev = lastJob
        val job = scope.launch {
            prev.join()
            _busy.value = true
            _busyLabel.value = label
            try {
                runGuarded(label, block)
            } finally {
                _busy.value = false
                _busyLabel.value = ""
            }
        }
        lastJob = job
        job.join()
    }

    val isBusy: Boolean get() = _busy.value

    fun cancel() {
        lastJob.cancel()
        lastJob = Job().also { it.complete() }
        _busy.value = false
        _busyLabel.value = ""
    }

    /** Cancel all pending work and release the coroutine scope. Call when the
     *  owning ViewModel/screen is disposed to prevent coroutine leaks. */
    fun dispose() {
        cancel()
        scope.cancel()
    }
}

/**
 * Monotonic sequence token to guard against stale loads.
 * Port of JS `_loadSeq`.
 */
class LoadSequence {
    private val counter = AtomicInteger(0)
    fun next(): Int = counter.incrementAndGet()
    fun isValid(seq: Int): Boolean = counter.get() == seq
}
