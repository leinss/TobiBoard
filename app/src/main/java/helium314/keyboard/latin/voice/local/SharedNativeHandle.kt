// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice.local

import android.content.ComponentCallbacks2
import android.os.Handler
import android.os.Looper
import helium314.keyboard.latin.utils.Log

/** Drop a native handle after this long with no use; the next request rebuilds it lazily. */
internal const val NATIVE_HANDLE_IDLE_TIMEOUT_MS = 5 * 60 * 1000L

/**
 * True when a [ComponentCallbacks2] trim level means the process should give its native models
 * back. TRIM_MEMORY_UI_HIDDEN is deliberately not one of them: it fires on every keyboard hide,
 * which is far too frequent to pay a multi-second reload for.
 *
 * Pure, so it is unit-tested.
 */
internal fun shouldReleaseOnTrim(level: Int): Boolean =
    level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL ||
        level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND

/**
 * When and how the idle release is scheduled. Only exists so the lifetime rules below can be
 * unit-tested without a Looper.
 */
internal interface IdleScheduler {
    fun schedule(delayMs: Long, action: () -> Unit)
    fun cancel()
}

/** Production scheduler: the main looper, which every IME thread can post to. */
internal class MainLooperIdleScheduler : IdleScheduler {
    // Built on first use, not at construction: a shared handle is a process-wide object that unit
    // tests instantiate on a thread with no Looper.
    private val handler by lazy { Handler(Looper.getMainLooper()) }
    private var pending: Runnable? = null

    override fun schedule(delayMs: Long, action: () -> Unit) {
        cancel()
        val runnable = Runnable(action)
        pending = runnable
        handler.postDelayed(runnable, delayMs)
    }

    override fun cancel() {
        pending?.let { handler.removeCallbacks(it) }
        pending = null
    }
}

/**
 * One lazily built native handle, shared across requests and reference-counted.
 *
 * Both on-device engines allocate hundreds of megabytes to a gigabyte of native memory that takes
 * seconds to build, so the handle has to outlive a single request, and both of them decode inside
 * an uninterruptible native call, so it must not be closed while a request is running. The rules
 * are identical for the LLM and the recogniser, and were written twice before: only the LLM got
 * them, and the recogniser held its ~660 MB for the whole process lifetime.
 *
 * @param T           the native handle type.
 * @param tag         log tag of the owning engine.
 * @param idleTimeoutMs how long the handle survives with nothing using it.
 * @param closeHandle releases the native memory. Never called while [inUseCount] is above zero.
 */
internal class SharedNativeHandle<T : Any>(
    private val tag: String,
    private val idleTimeoutMs: Long = NATIVE_HANDLE_IDLE_TIMEOUT_MS,
    private val scheduler: IdleScheduler = MainLooperIdleScheduler(),
    private val runOffThread: (() -> Unit) -> Unit = { body -> Thread(body).start() },
    private val closeHandle: (T) -> Unit,
) {
    private val lock = Any()

    @Volatile private var handle: T? = null

    /** Identifies which model the live handle was built for; a change forces a rebuild. */
    private var loadedKey: String? = null

    // All three guarded by lock. inUseCount > 0 means a native call is running, so the handle must
    // not be closed; a release requested while busy is deferred through releasePending.
    private var inUseCount = 0
    private var releasePending = false
    private var lastUsedMs = 0L

    /**
     * Pin the handle for one native call, building it via [create] if needed. Returns null when
     * [create] does (model not on disk). Pair every non-null return with [endUse] in a finally.
     */
    fun beginUse(key: String, create: () -> T?): T? {
        synchronized(lock) {
            val live = acquireLocked(key, create) ?: return null
            inUseCount++
            scheduler.cancel()
            return live
        }
    }

    /** Unpin after a native call; honour a deferred release or re-arm the idle timer. */
    fun endUse() {
        synchronized(lock) {
            if (inUseCount > 0) inUseCount-- else Log.w(tag, "endUse() with inUseCount==0 — unbalanced begin/endUse")
            lastUsedMs = System.currentTimeMillis()
            if (inUseCount != 0) return
            if (releasePending) {
                releasePending = false
                closeLocked()
            } else {
                armIdleTimer()
            }
        }
    }

    /**
     * Build the handle ahead of the first request without pinning it. The pre-warm path: there is
     * no call to pair an [endUse] with, so the idle timer is armed straight away.
     */
    fun warmUp(key: String, create: () -> T?): T? {
        synchronized(lock) {
            val live = acquireLocked(key, create) ?: return null
            lastUsedMs = System.currentTimeMillis()
            if (inUseCount == 0) armIdleTimer()
            return live
        }
    }

    /** Release now, or defer until the in-flight native call completes. */
    fun release() {
        synchronized(lock) {
            if (inUseCount > 0) {
                releasePending = true
                return
            }
            closeLocked()
        }
    }

    /** Release off the caller's thread: freeing hundreds of megabytes of native memory is slow. */
    fun releaseAsync() {
        runOffThread { release() }
    }

    /** True while a handle is live. For tests and logging only. */
    fun isLoaded(): Boolean = handle != null

    private fun acquireLocked(key: String, create: () -> T?): T? {
        handle?.let {
            if (loadedKey == key) return it
            // The user switched the active model. Rebuild, but never close a handle a native call
            // is still inside: serve the current one and let the switch land on the next acquire.
            if (inUseCount > 0) return it
            closeHandle(it)
            handle = null
            loadedKey = null
        }
        val created = create() ?: return null
        handle = created
        loadedKey = key
        lastUsedMs = System.currentTimeMillis()
        return created
    }

    private fun releaseIfIdle() {
        synchronized(lock) {
            if (inUseCount > 0 || handle == null) return
            if (System.currentTimeMillis() - lastUsedMs < idleTimeoutMs) return
            Log.i(tag, "Releasing idle native handle after ${idleTimeoutMs / 1000} s of inactivity")
            closeLocked()
        }
    }

    private fun armIdleTimer() {
        // The idle check itself takes the lock and the free is slow, so neither belongs on the
        // thread the scheduler fires on (the main looper in production).
        scheduler.schedule(idleTimeoutMs) { runOffThread { releaseIfIdle() } }
    }

    /** Caller must hold [lock]. */
    private fun closeLocked() {
        scheduler.cancel()
        handle?.let(closeHandle)
        handle = null
        loadedKey = null
    }
}
