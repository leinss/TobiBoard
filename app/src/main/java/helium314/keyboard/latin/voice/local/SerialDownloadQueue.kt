// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice.local

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * Runs at most one model download at a time, keyed by model id.
 *
 * A second [enqueue] while one download is in flight reports [DownloadState.Queued] straight away
 * and then waits on the mutex, so the UI does not sit on the previous state (usually
 * [DownloadState.NotDownloaded]) for as long as the running download takes. Concurrency would add
 * bandwidth contention without user benefit: only one model is actively useful at a time.
 *
 * [cancel] works the same for a waiting download as for a running one: the job is cancelled while
 * suspended on the mutex, which releases nothing because it never acquired anything.
 *
 * Split out of [ModelDownloadService] so the ordering, the cancel and the failure paths can be
 * exercised without the Android `Service` lifecycle.
 */
internal class SerialDownloadQueue(
    private val scope: CoroutineScope,
    /** Every state transition this queue itself produces (Queued, Cancelled). */
    private val onState: (modelId: String, state: DownloadState) -> Unit,
    /** Called whenever the last outstanding download finishes, is cancelled or fails. */
    private val onIdle: () -> Unit,
) {
    private val jobs = ConcurrentHashMap<String, Job>()
    private val downloadMutex = Mutex()

    val isIdle: Boolean get() = jobs.isEmpty()

    fun isPending(modelId: String): Boolean = jobs.containsKey(modelId)

    /**
     * Queue [download] for [modelId], or do nothing when that model already has a job. [onFailure]
     * gets any non-cancellation exception the download throws; cancellation is rethrown so the
     * scope sees it.
     */
    fun enqueue(modelId: String, onFailure: (Throwable) -> Unit, download: suspend () -> Unit) {
        if (jobs.containsKey(modelId)) return
        // LAZY so the job is in the map before its body can run. Started eagerly, a download that
        // finished or failed immediately removed itself before the map was written, and the stale
        // entry left behind made every later attempt at that model a no-op.
        val job = scope.launch(start = CoroutineStart.LAZY) {
            // Set when this job reached a terminal state on its own, so the finally below can tell
            // "finished" from "the scope was cancelled under us".
            var reportedTerminalState = false
            try {
                onState(modelId, DownloadState.Queued)
                downloadMutex.withLock { download() }
                reportedTerminalState = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onFailure(e)
                reportedTerminalState = true
            } finally {
                // Service.onDestroy cancels the whole scope, and that path never reaches cancel(),
                // so without this the observer keeps showing Downloading for a job that is gone and
                // has no way to tell that from one still running.
                if (!reportedTerminalState && jobs.containsKey(modelId)) {
                    onState(modelId, DownloadState.Cancelled)
                }
                jobs.remove(modelId)
                if (jobs.isEmpty()) onIdle()
            }
        }
        jobs[modelId] = job
        job.start()
    }

    /**
     * Cancels the download for [modelId], or does nothing when it has none.
     *
     * The no-job case matters: a notification action button outlives the download it belongs to, so
     * a stale tap used to overwrite a finished Ready or Failed state with Cancelled.
     */
    fun cancel(modelId: String) {
        val job = jobs.remove(modelId) ?: return
        job.cancel()
        onState(modelId, DownloadState.Cancelled)
        if (jobs.isEmpty()) onIdle()
    }
}
