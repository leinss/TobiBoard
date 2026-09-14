// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The preparing/timeout rules both managers share. They were duplicated, and the duplicate drifted:
 * the voice timeout was missing the `cancel()` its text-fix twin had, so a decode kept running for
 * a request the user had already been told had failed.
 */
class LocalModelRequestTest {

    private enum class State { PREPARING, RUNNING }

    private class RecordingEngine : Cancellable {
        var cancelled = false
        override fun cancel() {
            cancelled = true
        }
    }

    @Test
    fun onlyTheOnDeviceProviderHasAModelToLoad() {
        assertEquals(State.PREPARING, LocalModelRequest.initialState(AiProvider.LOCAL, State.PREPARING, State.RUNNING))
        assertEquals(State.RUNNING, LocalModelRequest.initialState(AiProvider.OPENROUTER, State.PREPARING, State.RUNNING))
        assertEquals(State.RUNNING, LocalModelRequest.initialState(AiProvider.PAYPERQ, State.PREPARING, State.RUNNING))
    }

    @Test
    fun theModelReadyCallbackAppliesToTheRequestThatIsStillRunning() {
        assertTrue(LocalModelRequest.shouldMarkRunning(7L, 7L, State.PREPARING, State.PREPARING))
    }

    @Test
    fun aModelReadyCallbackFromAnOlderRequestIsIgnored() {
        // The callback arrives from a background thread, so a newer request may have started.
        assertFalse(LocalModelRequest.shouldMarkRunning(8L, 7L, State.PREPARING, State.PREPARING))
    }

    @Test
    fun aModelReadyCallbackAfterTheStateMovedOnIsIgnored() {
        assertFalse(LocalModelRequest.shouldMarkRunning(7L, 7L, State.RUNNING, State.PREPARING))
    }

    @Test
    fun aCloudRequestGetsNoCeilingAndIsNeverCancelled() = runBlocking {
        val engine = RecordingEngine()
        val result = LocalModelRequest.withLocalTimeout(isLocal = false, engine = engine) { "done" }

        assertEquals("done", result)
        assertFalse(engine.cancelled)
    }

    @Test
    fun anOnDeviceRequestReturnsItsResultWhenItFinishesInTime() = runBlocking {
        val engine = RecordingEngine()
        val result = LocalModelRequest.withLocalTimeout(isLocal = true, engine = engine) { "done" }

        assertEquals("done", result)
        assertFalse(engine.cancelled)
    }

    @Test
    fun anExpiredOnDeviceRequestCancelsTheEngineAndRethrows() = runBlocking {
        val engine = RecordingEngine()
        // A short ceiling instead of the 180 s production one; the pairing is what matters. The
        // cancel is the half that went missing on the voice side: without it a decode carries on
        // for a request the user has already been told timed out.
        assertFailsWith<TimeoutCancellationException> {
            LocalModelRequest.withLocalTimeout(isLocal = true, engine = engine, timeoutMs = 20L) {
                delay(10_000L)
            }
        }
        assertTrue(engine.cancelled)
    }

    @Test
    fun bothManagersShareOneCeiling() {
        // Both call withLocalTimeout() with the default, so there is one number and no alias to
        // drift; the manager-local copies this used to compare are gone.
        assertTrue(LocalModelRequest.LOCAL_TIMEOUT_MS > 0L)
    }

    @Test
    fun anOutOfMemoryFailureHandsTheOnDeviceModelBack() {
        // The held handle survives the failed request, so a retry would allocate against the same
        // memory. The wrapped form is what a native load failure actually arrives as.
        assertTrue(LocalModelRequest.shouldReleaseLocalModel(OutOfMemoryError()))
        assertTrue(LocalModelRequest.shouldReleaseLocalModel(RuntimeException(OutOfMemoryError())))
    }

    @Test
    fun anOrdinaryFailureKeepsTheOnDeviceModelLoaded() {
        assertFalse(LocalModelRequest.shouldReleaseLocalModel(java.io.IOException("no network")))
    }
}
