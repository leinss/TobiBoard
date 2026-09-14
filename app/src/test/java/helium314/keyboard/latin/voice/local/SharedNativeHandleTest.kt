// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice.local

import android.content.ComponentCallbacks2
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The lifetime rules both on-device engines now share. They were written once for the text-fix LLM
 * and never for the speech recogniser, which held ~660 MB for the whole process lifetime. The
 * scheduler and the off-thread hop are injected, so this needs no Looper and no Robolectric.
 */
class SharedNativeHandleTest {

    private class FakeScheduler : IdleScheduler {
        var scheduledDelayMs: Long = -1
        private var pending: (() -> Unit)? = null

        override fun schedule(delayMs: Long, action: () -> Unit) {
            scheduledDelayMs = delayMs
            pending = action
        }

        override fun cancel() {
            pending = null
        }

        val isArmed: Boolean get() = pending != null

        /** Run what the production scheduler would have run when the delay elapsed. */
        fun fire() {
            val action = pending
            pending = null
            action?.invoke()
        }
    }

    private class Handle(val name: String)

    private val closed = mutableListOf<String>()
    private val scheduler = FakeScheduler()
    private var built = 0

    private fun newHandle(idleTimeoutMs: Long = 60_000L) = SharedNativeHandle<Handle>(
        tag = "test",
        idleTimeoutMs = idleTimeoutMs,
        scheduler = scheduler,
        runOffThread = { it() },
    ) { closed += it.name }

    private fun build(name: String): () -> Handle? = { built++; Handle(name) }

    @Test
    fun theHandleIsBuiltOnceAndReusedAcrossRequests() {
        val shared = newHandle()
        val first = shared.beginUse("model-a", build("a"))
        shared.endUse()
        val second = shared.beginUse("model-a", build("a"))
        shared.endUse()

        assertSame(first, second)
        assertEquals(1, built)
        assertTrue(closed.isEmpty())
    }

    @Test
    fun aReleaseDuringAnUninterruptibleCallIsDeferredUntilItFinishes() {
        val shared = newHandle()
        shared.beginUse("model-a", build("a"))

        shared.release()
        assertTrue(closed.isEmpty(), "closing mid-call would free memory the native call is reading")
        assertTrue(shared.isLoaded())

        shared.endUse()
        assertEquals(listOf("a"), closed)
        assertFalse(shared.isLoaded())
    }

    @Test
    fun switchingModelClosesTheOldHandleAndBuildsTheNewOne() {
        val shared = newHandle()
        shared.beginUse("model-a", build("a"))
        shared.endUse()

        val second = shared.beginUse("model-b", build("b"))
        shared.endUse()

        assertEquals(listOf("a"), closed)
        assertEquals("b", second?.name)
        assertEquals(2, built)
    }

    @Test
    fun switchingModelWhileInUseServesTheLiveHandleRatherThanClosingIt() {
        val shared = newHandle()
        val first = shared.beginUse("model-a", build("a"))

        val second = shared.beginUse("model-b", build("b"))

        assertSame(first, second, "the switch has to wait: the running call is inside the old handle")
        assertTrue(closed.isEmpty())
        assertEquals(1, built)
    }

    @Test
    fun aModelThatIsNotOnDiskYieldsNullAndLoadsNothing() {
        val shared = newHandle()
        assertNull(shared.beginUse("model-a") { null })
        assertFalse(shared.isLoaded())
        assertFalse(scheduler.isArmed, "nothing was pinned, so nothing has to be released later")
    }

    @Test
    fun theIdleTimerIsArmedOnlyWhenNothingIsUsingTheHandle() {
        val shared = newHandle()
        shared.beginUse("model-a", build("a"))
        assertFalse(scheduler.isArmed, "a running call must never be interrupted by the idle timer")

        shared.endUse()
        assertTrue(scheduler.isArmed)
        assertEquals(60_000L, scheduler.scheduledDelayMs)
    }

    @Test
    fun anIdleHandleIsReleasedOnceTheWindowHasPassed() {
        val shared = newHandle(idleTimeoutMs = 0L)
        shared.beginUse("model-a", build("a"))
        shared.endUse()

        scheduler.fire()
        assertEquals(listOf("a"), closed)
    }

    @Test
    fun aHandleUsedInsideTheWindowIsKept() {
        val shared = newHandle(idleTimeoutMs = 60_000L)
        shared.beginUse("model-a", build("a"))
        shared.endUse()

        scheduler.fire()
        assertTrue(closed.isEmpty(), "releasing here would reintroduce the multi-second cold load")
        assertTrue(shared.isLoaded())
    }

    @Test
    fun warmUpBuildsTheHandleAndArmsTheIdleTimerWithoutPinningIt() {
        val shared = newHandle(idleTimeoutMs = 0L)
        shared.warmUp("model-a", build("a"))

        assertTrue(shared.isLoaded())
        assertTrue(scheduler.isArmed, "a pre-warm nobody uses must not hold the memory forever")
        scheduler.fire()
        assertEquals(listOf("a"), closed)
    }

    @Test
    fun trimReleasesOnRealPressureButNotOnEveryKeyboardHide() {
        // UI_HIDDEN fires on every keyboard hide; reloading for it would cost seconds each time.
        assertFalse(shouldReleaseOnTrim(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN))
        assertFalse(shouldReleaseOnTrim(ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE))
        assertFalse(shouldReleaseOnTrim(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW))
        assertTrue(shouldReleaseOnTrim(ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL))
        assertTrue(shouldReleaseOnTrim(ComponentCallbacks2.TRIM_MEMORY_BACKGROUND))
        assertTrue(shouldReleaseOnTrim(ComponentCallbacks2.TRIM_MEMORY_COMPLETE))
    }

    @Test
    fun bothEnginesUseTheSameIdleWindow() {
        assertEquals(5 * 60 * 1000L, NATIVE_HANDLE_IDLE_TIMEOUT_MS)
    }
}
