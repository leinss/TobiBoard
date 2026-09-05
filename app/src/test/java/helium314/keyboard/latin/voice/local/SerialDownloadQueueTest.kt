// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice.local

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.util.Collections
import java.util.concurrent.Executors
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Two model downloads must not run at once: they fight over bandwidth and disk, and only one model
 * is useful at a time. Every start() used to launch straight into the shared scope, so the serial
 * promise in the service's own comment was not kept. These cases pin the three things a queue can
 * get wrong: running both anyway, leaving the waiting one invisible, and deadlocking the waiter
 * when the running download is cancelled or fails.
 *
 * Timings are real (there is no coroutines-test dependency in this module), so every wait is a
 * handshake through a [CompletableDeferred] with a timeout rather than a sleep.
 */
class SerialDownloadQueueTest {

    // One dedicated thread rather than Dispatchers.IO: everything here suspends rather than
    // blocks, and a pool shared with the rest of the suite outlives the test that started it.
    private val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val states = Collections.synchronizedList(mutableListOf<Pair<String, DownloadState>>())
    private val idleCount = java.util.concurrent.atomic.AtomicInteger(0)

    private val queue = SerialDownloadQueue(
        scope = scope,
        onState = { modelId, state -> states.add(modelId to state) },
        onIdle = { idleCount.incrementAndGet() },
    )

    @AfterTest fun tearDown() {
        scope.cancel()
        dispatcher.close()
    }

    private fun statesOf(modelId: String): List<DownloadState> =
        synchronized(states) { states.filter { it.first == modelId }.map { it.second } }

    /** One download whose body blocks until the test releases it. */
    private class BlockingDownload {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val finished = CompletableDeferred<Unit>()

        suspend fun run() {
            started.complete(Unit)
            try {
                release.await()
            } finally {
                finished.complete(Unit)
            }
        }
    }

    private fun failure(): (Throwable) -> Unit = { }

    @Test
    fun theSecondDownloadWaitsForTheFirstAndReportsQueuedWhileItWaits() = runBlocking {
        val first = BlockingDownload()
        val second = BlockingDownload()

        queue.enqueue("first", failure()) { first.run() }
        withTimeout(TIMEOUT_MS) { first.started.await() }

        queue.enqueue("second", failure()) { second.run() }
        // Queued is reported before the wait, so the UI does not sit on the previous state for as
        // long as the running download takes.
        withTimeout(TIMEOUT_MS) {
            while (statesOf("second").isEmpty()) delay(POLL_MS)
        }
        assertEquals(listOf(DownloadState.Queued), statesOf("second"))
        assertFalse(second.started.isCompleted, "the second download must not run alongside the first")

        first.release.complete(Unit)
        withTimeout(TIMEOUT_MS) { second.started.await() }
        second.release.complete(Unit)
        withTimeout(TIMEOUT_MS) { second.finished.await() }
        assertTrue(first.finished.isCompleted, "the first download ran to completion first")
    }

    @Test
    fun cancellingAQueuedDownloadLeavesTheRunningOneAlone() = runBlocking {
        val first = BlockingDownload()
        val second = BlockingDownload()

        queue.enqueue("first", failure()) { first.run() }
        withTimeout(TIMEOUT_MS) { first.started.await() }
        queue.enqueue("second", failure()) { second.run() }
        withTimeout(TIMEOUT_MS) {
            while (statesOf("second").isEmpty()) delay(POLL_MS)
        }

        queue.cancel("second")
        assertEquals(DownloadState.Cancelled, statesOf("second").last())
        assertFalse(queue.isPending("second"))
        assertTrue(queue.isPending("first"), "cancelling the waiter must not touch the running job")

        first.release.complete(Unit)
        withTimeout(TIMEOUT_MS) { first.finished.await() }
        withTimeout(TIMEOUT_MS) {
            while (queue.isPending("first")) delay(POLL_MS)
        }
        assertFalse(second.started.isCompleted, "a cancelled waiter must never start")
    }

    @Test
    fun cancellingTheRunningDownloadReleasesTheWaitingOne() = runBlocking {
        val first = BlockingDownload()
        val second = BlockingDownload()

        queue.enqueue("first", failure()) { first.run() }
        withTimeout(TIMEOUT_MS) { first.started.await() }
        queue.enqueue("second", failure()) { second.run() }
        withTimeout(TIMEOUT_MS) {
            while (statesOf("second").isEmpty()) delay(POLL_MS)
        }

        queue.cancel("first")

        // Cancelling while the mutex is held must hand it to the waiter, not strand it.
        withTimeout(TIMEOUT_MS) { second.started.await() }
        second.release.complete(Unit)
        withTimeout(TIMEOUT_MS) { second.finished.await() }
    }

    @Test
    fun aFailureInTheFirstDownloadDoesNotDeadlockTheSecond() = runBlocking {
        val first = BlockingDownload()
        val second = BlockingDownload()
        val reported = CompletableDeferred<Throwable>()

        queue.enqueue("first", { reported.complete(it) }) {
            first.started.complete(Unit)
            first.release.await()
            throw IOException("disk full")
        }
        withTimeout(TIMEOUT_MS) { first.started.await() }
        queue.enqueue("second", failure()) { second.run() }
        withTimeout(TIMEOUT_MS) {
            while (statesOf("second").isEmpty()) delay(POLL_MS)
        }

        first.release.complete(Unit)
        assertEquals("disk full", withTimeout(TIMEOUT_MS) { reported.await() }.message)

        withTimeout(TIMEOUT_MS) { second.started.await() }
        second.release.complete(Unit)
        withTimeout(TIMEOUT_MS) { second.finished.await() }
    }

    @Test
    fun aSecondRequestForAModelThatIsAlreadyQueuedIsIgnored() = runBlocking {
        val first = BlockingDownload()
        var secondBodyRuns = 0

        queue.enqueue("first", failure()) { first.run() }
        withTimeout(TIMEOUT_MS) { first.started.await() }
        queue.enqueue("first", failure()) { secondBodyRuns++ }

        first.release.complete(Unit)
        withTimeout(TIMEOUT_MS) { first.finished.await() }
        withTimeout(TIMEOUT_MS) {
            while (queue.isPending("first")) delay(POLL_MS)
        }
        assertEquals(0, secondBodyRuns, "a duplicate request must not start a second job")
    }

    @Test
    fun theQueueGoesIdleOnlyOnceBothDownloadsAreDone() = runBlocking {
        val first = BlockingDownload()
        val second = BlockingDownload()

        queue.enqueue("first", failure()) { first.run() }
        withTimeout(TIMEOUT_MS) { first.started.await() }
        queue.enqueue("second", failure()) { second.run() }
        withTimeout(TIMEOUT_MS) {
            while (statesOf("second").isEmpty()) delay(POLL_MS)
        }

        first.release.complete(Unit)
        withTimeout(TIMEOUT_MS) { second.started.await() }
        // The first job is gone but the second is still running: tearing the foreground service
        // down here would kill the download that is still in flight.
        assertEquals(0, idleCount.get())
        assertFalse(queue.isIdle)

        second.release.complete(Unit)
        withTimeout(TIMEOUT_MS) {
            while (!queue.isIdle) delay(POLL_MS)
        }
        withTimeout(TIMEOUT_MS) {
            while (idleCount.get() == 0) delay(POLL_MS)
        }
        assertEquals(1, idleCount.get())
    }

    companion object {
        private const val TIMEOUT_MS = 5_000L
        private const val POLL_MS = 2L
    }
}
