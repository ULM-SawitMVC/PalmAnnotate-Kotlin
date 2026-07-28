package dev.sawitulm.palmannotate.data.storage

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class ArtifactCoordinatorTest {

    @Test fun `dataset operations never overlap`() = runBlocking {
        val coordinator = ArtifactCoordinator()
        val active = AtomicInteger(0)
        val maximum = AtomicInteger(0)

        coroutineScope {
            (0 until 12).map {
                async(Dispatchers.Default) {
                    coordinator.withExclusiveAccess {
                        val now = active.incrementAndGet()
                        maximum.updateAndGet { previous -> maxOf(previous, now) }
                        delay(5)
                        active.decrementAndGet()
                    }
                }
            }.awaitAll()
        }

        assertEquals(1, maximum.get())
        assertEquals(0, active.get())
    }

    @Test fun `nested acquisition times out so export cannot call public repository load`() = runBlocking {
        val coordinator = ArtifactCoordinator()
        try {
            withTimeout(150) {
                coordinator.withExclusiveAccess {
                    // This is the old export call graph. The lock is deliberately non-reentrant;
                    // export code must use the repository's WhileExclusive helper instead.
                    coordinator.withExclusiveAccess { }
                }
            }
            fail("A nested ArtifactCoordinator acquisition unexpectedly completed")
        } catch (_: TimeoutCancellationException) {
            // Regression guard: the old call graph blocks deterministically instead of silently
            // passing and hanging a real export indefinitely.
        }
    }
}
