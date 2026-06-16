package dev.sawitulm.palmannotate.domain.util

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.atomic.AtomicInteger

/**
 * Serializes async work so navigation/save/compute/next-tree never interleave.
 * Port of JS `_enqueueOperation(fn)` + `_setBusy(flag, label)`.
 */
class OperationQueue {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
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
                block()
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
                block()
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
