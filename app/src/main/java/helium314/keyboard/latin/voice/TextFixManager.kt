// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.text.InputType
import androidx.annotation.StringRes
import helium314.keyboard.latin.R
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.Log
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.latin.voice.local.LocalLiteRtEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible

/**
 * Orchestrates text-fix requests to the selected AI provider. Reads the text to fix via a callback
 * (the current selection, or the whole field when nothing is selected), validates preconditions,
 * runs the HTTP call on a background thread, and posts the proposed replacement back on the main
 * thread. Stateless across requests — a new fix starts fresh.
 */
class TextFixManager(
    private val context: Context,
    private val callbacks: Callbacks,
) {
    companion object {
        private const val TAG = "TextFixManager"
        private const val MAX_INPUT_LENGTH = 10_000
        private const val MAX_OUTPUT_LENGTH = 10_000
        const val SETTINGS_TEXT_FIX = "text_fix"
        const val SETTINGS_LOCAL_MODELS = "local_models"

        @JvmStatic
        @StringRes
        fun getBlockedErrorResId(
            inputType: Int,
            isPasswordField: Boolean,
            noLearning: Boolean,
            incognitoModeEnabled: Boolean,
            imeOptions: Int,
        ): Int? {
            // Sensitive signals first — shared with voice input so the two guards cannot diverge.
            if (SensitiveField.isSensitive(inputType, isPasswordField, noLearning, incognitoModeEnabled, imeOptions)) {
                return R.string.text_fix_error_sensitive_field
            }
            // TYPE_TEXT_FLAG_NO_SUGGESTIONS means "disable autocomplete strip" — it is widely set
            // by cross-platform frameworks (React Native, Flutter) for non-privacy reasons and is
            // NOT a signal that the field content is sensitive. Do not block text fix on it.
            when (inputType and InputType.TYPE_MASK_CLASS) {
                InputType.TYPE_CLASS_TEXT -> { /* always allowed */ }
                InputType.TYPE_CLASS_NUMBER -> {
                    // The password variation is already handled above as sensitive; everything else
                    // numeric is simply unsupported.
                    return R.string.text_fix_error_unsupported_field
                }
                else -> {
                    // DATETIME / PHONE / etc. — unsupported.
                    return R.string.text_fix_error_unsupported_field
                }
            }
            return null
        }

        /**
         * True when the model handed back what it was given, so there is nothing to propose.
         *
         * The 1B-class on-device models echo the input routinely (see the commentary-stripping
         * rules in `LocalLiteRtEngine`). Only a blank reply used to count as a failure, so an echo
         * became a Replace/Discard offer whose diff was empty and whose Replace button did nothing.
         *
         * Leading and trailing whitespace is ignored because the field text is read with it and the
         * model does not reproduce it reliably; whitespace *inside* the text is compared, so a fix
         * that only re-spaces the input still counts as a change.
         *
         * Pure, so it is unit-tested.
         */
        @JvmStatic
        fun isNoChange(input: String, proposed: String): Boolean = input.trim() == proposed.trim()

        /**
         * The state a request starts in. Only the on-device provider has a model to load, so only
         * it gets [State.PREPARING]; a cloud request goes straight to [State.WORKING].
         * Pure, so it is unit-tested.
         */
        @JvmStatic
        fun initialWorkingState(provider: AiProvider): State =
            LocalModelRequest.initialState(provider, State.PREPARING, State.WORKING)

        /** See [LocalModelRequest.shouldReleaseLocalModel]; both managers apply the same rule. */
        @JvmStatic
        internal fun shouldReleaseLocalModel(t: Throwable): Boolean =
            LocalModelRequest.shouldReleaseLocalModel(t)
    }

    /**
     * PREPARING covers the on-device model load, WORKING the request itself. They used to be one
     * state, so a multi-second first-use load and a warm run rendered identically.
     */
    enum class State { IDLE, PREPARING, WORKING }

    enum class Variant(val enabledPref: String, val enabledDefault: Boolean, val promptPref: String, val promptDefault: String) {
        PRIMARY(Settings.PREF_TEXT_FIX_ENABLED, Defaults.PREF_TEXT_FIX_ENABLED, Settings.PREF_TEXT_FIX_PROMPT, Defaults.PREF_TEXT_FIX_PROMPT),
        SECONDARY(Settings.PREF_TEXT_FIX_2_ENABLED, Defaults.PREF_TEXT_FIX_2_ENABLED, Settings.PREF_TEXT_FIX_2_PROMPT, Defaults.PREF_TEXT_FIX_2_PROMPT),
    }

    interface Callbacks {
        @StringRes
        fun getBlockedErrorResId(): Int?
        /**
         * Return the text to fix: the current selection, or the whole field when nothing is
         * selected. Null/empty only when the field has no text at all.
         */
        fun getTextToFix(): CharSequence?
        /** On-device model load has started. Defaults to [onWorking] for implementers that
         *  do not distinguish the two. */
        fun onPreparing() = onWorking()
        fun onWorking()
        fun onFinished()
        fun onResult(originalText: String, proposedText: String)
        fun onError(message: String)
        /**
         * Called when a required setup step is missing. [reason] names the missing step in one
         * sentence and must be shown before the keyboard hides, otherwise settings opening on its
         * own is indistinguishable from a crash. See [VoiceInputManager.Callbacks.onOpenSettings].
         */
        fun onOpenSettings(settingsDestination: String, reason: String) {}
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Last-resort backstop for one attempt, mirroring the one in [VoiceInputManager]. Without it,
     * any Throwable escaping a [backgroundScope] coroutine reaches the default handler and kills
     * the whole IME process. The on-device fix path loads a MediaPipe LLM of roughly a gigabyte
     * ([helium314.keyboard.latin.voice.local.LocalLiteRtEngine]), so OutOfMemoryError is a realistic
     * outcome on a mid-RAM device, and a `catch (e: Exception)` does not see it. The user gets the
     * ordinary text-fix failure message instead of losing the keyboard mid-sentence.
     *
     * It is built per launch and closes over that attempt's [activeToken]: a crash reported after
     * the user has already started a newer fix must not tear the newer one down. A scope-level
     * handler had no way to tell the two apart.
     */
    private fun crashGuardFor(token: Long) = CoroutineExceptionHandler { _, t ->
        Log.e(TAG, "Uncaught failure in a text-fix coroutine", t)
        if (shouldReleaseLocalModel(t)) LocalLiteRtEngine.releaseSharedAsync()
        mainHandler.post { failCurrentAttempt(token, context.getString(R.string.text_fix_error_failed)) }
    }
    private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var activeClient: TextFixEngine? = null
    @Volatile private var activeJob: Job? = null
    @Volatile private var activeToken = 0L
    @Volatile private var state = State.IDLE

    fun getState() = state

    @Synchronized
    fun startTextFix(variant: Variant = Variant.PRIMARY) {
        Log.d(TAG, "startTextFix: state=$state")
        if (state != State.IDLE) { Log.d(TAG, "startTextFix: blocked — state=$state"); return }
        val prefs = context.prefs()

        if (!prefs.getBoolean(variant.enabledPref, variant.enabledDefault)) {
            Log.d(TAG, "startTextFix: disabled — navigating to text_fix settings")
            openSettingsFor(SetupGap.TEXT_FIX_DISABLED)
            return
        }
        val blocked = callbacks.getBlockedErrorResId()
        Log.d(TAG, "startTextFix: blockedResId=$blocked")
        blocked?.let {
            callbacks.onError(context.getString(it))
            return
        }
        val provider = AiProvider.fromPref(prefs.getString(Settings.PREF_AI_PROVIDER, Defaults.PREF_AI_PROVIDER))
        Log.d(TAG, "startTextFix: provider=$provider")

        // Text retrieval must stay on the main thread (reads the input connection); cheap, no I/O.
        val input = callbacks.getTextToFix()?.toString().orEmpty()
        if (input.isBlank()) {
            callbacks.onError(context.getString(R.string.text_fix_error_no_selection))
            return
        }
        if (input.length > MAX_INPUT_LENGTH) {
            callbacks.onError(context.getString(R.string.text_fix_error_too_long))
            return
        }

        // Cheap in-memory pref reads.
        val selectedModel = prefs.getString(Settings.PREF_TEXT_FIX_MODEL, Defaults.PREF_TEXT_FIX_MODEL) ?: Defaults.PREF_TEXT_FIX_MODEL
        val customModel = prefs.getString(Settings.PREF_TEXT_FIX_MODEL_CUSTOM, Defaults.PREF_TEXT_FIX_MODEL_CUSTOM) ?: ""
        val prompt = (prefs.getString(variant.promptPref, variant.promptDefault) ?: variant.promptDefault)
            .trim().ifEmpty { variant.promptDefault }
        val useZdr = provider == AiProvider.OPENROUTER &&
            prefs.getBoolean(Settings.PREF_OPENROUTER_ZDR_ENABLED, Defaults.PREF_OPENROUTER_ZDR_ENABLED)

        // Show the working state immediately, then run the keystore / model-file / network
        // preconditions off the IME main thread. Failures post the same navigation/error back.
        // The on-device path loads a model before it can do anything; the cloud path does not.
        state = initialWorkingState(provider)
        if (state == State.PREPARING) callbacks.onPreparing() else callbacks.onWorking()
        val token = activeToken + 1
        activeToken = token

        activeJob = backgroundScope.launch(CoroutineName("TextFixRequest") + crashGuardFor(token)) {
            // SecretStore is only needed for cloud API keys; LOCAL provider never touches it.
            if (provider.isCloud && !SecretStore.isSecureStorageAvailable(context)) {
                Log.d(TAG, "startTextFix: SecretStore unavailable — navigating to text_fix settings")
                finishWithSettings(token, SetupGap.TEXT_FIX_NO_SECURE_STORAGE)
                return@launch
            }
            val apiKey = if (provider.isCloud) {
                SecretStore.getApiKey(context, provider.apiKeyPrefKey(), provider.defaultApiKey())
            } else ""
            if (provider.isCloud && apiKey.isBlank()) {
                Log.d(TAG, "startTextFix: no API key — navigating to text_fix settings")
                finishWithSettings(token, SetupGap.TEXT_FIX_NO_API_KEY)
                return@launch
            }
            if (provider.isCloud && !isNetworkAvailable(context)) {
                finish(token, error = context.getString(R.string.voice_error_no_network))
                return@launch
            }
            if (provider == AiProvider.LOCAL) {
                val activeModel = helium314.keyboard.latin.voice.local.ModelRegistry.activeTextFix(context)
                val modelReady = helium314.keyboard.latin.voice.local.ModelStorage.isReady(context, activeModel)
                Log.d(TAG, "startTextFix: LOCAL model=${activeModel.id} ready=$modelReady")
                if (!modelReady) {
                    Log.d(TAG, "startTextFix: model not ready — navigating to local_models")
                    finishWithSettings(token, SetupGap.TEXT_FIX_MODEL_NOT_DOWNLOADED, activeModel.displayName)
                    return@launch
                }
            }
            val model = if (provider.isCloud) {
                resolveProviderModel(selectedModel, customModel) ?: run {
                    finishWithSettings(token, SetupGap.TEXT_FIX_NO_MODEL_SELECTED)
                    return@launch
                }
            } else ""

            // Bail before any heavy work if a cancel raced in during the preflight.
            if (activeToken != token) return@launch

            // Make the fix aware of the user's personal dictionary so it does not "correct" their
            // custom words away. Reads the user-dictionary provider (I/O) — fine here on Dispatchers.IO.
            val effectivePrompt = PersonalDictionaryPrompt.augment(context, prompt)

            val client: TextFixEngine = when (provider) {
                AiProvider.LOCAL -> helium314.keyboard.latin.voice.local.LocalLiteRtEngine(
                    context, effectivePrompt, onModelReady = { markWorking(token) },
                )
                AiProvider.OPENROUTER, AiProvider.PAYPERQ -> OpenRouterClient(
                    apiKey = apiKey,
                    model = model,
                    systemPrompt = effectivePrompt,
                    runtimeInstruction = null,
                    provider = provider,
                    useZeroDataRetention = useZdr,
                )
            }
            activeClient = client
            // Re-check after publishing activeClient: a cancel() that landed between the check above
            // and this assignment couldn't have called client.cancel() (activeClient was still null),
            // so bail now rather than run a full uninterruptible generation whose result is discarded.
            if (activeToken != token) return@launch
            try {
                // The local path has no ceiling of its own: generateResponse is a native call
                // that never returns an error for taking too long. The timeout does not stop it
                // (see the class comment on LocalLiteRtEngine), it stops the user waiting behind a
                // label that will never change. withLocalTimeout also cancels the engine.
                val proposed = sanitize(
                    LocalModelRequest.withLocalTimeout(provider == AiProvider.LOCAL, client) {
                        runInterruptible { client.fixText(input) }
                    }
                )
                UsageTracker.record(client.lastResponseTokens)
                if (proposed.isBlank()) {
                    finish(token, error = context.getString(R.string.text_fix_error_empty))
                    return@launch
                }
                if (isNoChange(input, proposed)) {
                    finish(token, error = context.getString(R.string.text_fix_no_change_needed))
                    return@launch
                }
                finish(token, original = input, result = proposed)
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                // Must precede the CancellationException branch: a timeout is one. The engine has
                // already been cancelled by withLocalTimeout.
                finish(token, error = context.getString(R.string.text_fix_error_timeout))
            } catch (e: CancellationException) {
                finish(token)
            } catch (e: InterruptedException) {
                finish(token)
            } catch (t: Throwable) {
                // Throwable, not Exception: the on-device engine allocates around a gigabyte over
                // JNI and can raise OutOfMemoryError / UnsatisfiedLinkError, which must surface as a
                // text-fix error rather than unwinding out of the coroutine and killing the IME.
                Log.e(TAG, "Text fix failed", t)
                if (shouldReleaseLocalModel(t)) LocalLiteRtEngine.releaseSharedAsync()
                finish(token, error = safeUserFacingError(context, t, R.string.text_fix_error_failed))
            }
        }
    }

    /** Moves out of PREPARING once the engine reports its model is loaded. Any thread. */
    private fun markWorking(token: Long) {
        mainHandler.post {
            if (!LocalModelRequest.shouldMarkRunning(activeToken, token, state, State.PREPARING)) return@post
            state = State.WORKING
            callbacks.onWorking()
        }
    }

    @Synchronized
    fun cancel() {
        if (state == State.IDLE) return
        activeToken += 1
        activeClient?.cancel()
        activeJob?.cancel()
        resetToIdle()
    }

    /**
     * The teardown every terminal path shares. Each field of the state machine used to be cleared
     * by hand in four places, so adding one meant remembering all four. Callers that end an attempt
     * the manager did not finish itself invalidate [activeToken] and cancel the engine first.
     */
    private fun resetToIdle() {
        activeJob = null
        activeClient = null
        state = State.IDLE
        callbacks.onFinished()
    }

    /** Cancel any in-flight work and tear down the background scope. Call from IME onDestroy. */
    fun release() {
        cancel()
        backgroundScope.cancel()
    }

    private fun finish(
        token: Long,
        original: String? = null,
        result: String? = null,
        error: String? = null,
    ) {
        mainHandler.post {
            if (activeToken != token) return@post
            resetToIdle()
            if (original != null && !result.isNullOrEmpty()) {
                callbacks.onResult(original, result)
            } else if (!error.isNullOrEmpty()) {
                callbacks.onError(error)
            }
        }
    }

    /**
     * Resets to idle and reports [message] after a failure that bypassed the normal error path (the
     * [crashGuardFor] backstop). Does nothing when [token] is not the attempt that is running: a
     * crash reported late must not tear down the fix the user started after it. Main thread only.
     */
    @Synchronized
    private fun failCurrentAttempt(token: Long, message: String) {
        if (state == State.IDLE || activeToken != token) return
        activeToken += 1
        // Same teardown as cancel(): an engine that threw still holds its native handle, and on the
        // on-device path that is a gigabyte the next fix would have to allocate around.
        activeClient?.cancel()
        resetToIdle()
        callbacks.onError(message)
    }

    /** Reset to idle and route the user to settings — used when a background precondition fails. */
    private fun finishWithSettings(token: Long, gap: SetupGap, vararg formatArgs: Any) {
        mainHandler.post {
            if (activeToken != token) return@post
            resetToIdle()
            openSettingsFor(gap, *formatArgs)
        }
    }

    /** Names the missing step, then hands the destination over. Main thread only. */
    private fun openSettingsFor(gap: SetupGap, vararg formatArgs: Any) {
        val reason = if (formatArgs.isEmpty()) context.getString(gap.messageResId)
        else context.getString(gap.messageResId, *formatArgs)
        callbacks.onOpenSettings(gap.settingsDestination, reason)
    }

    private fun sanitize(raw: String): String = sanitizeModelOutput(raw, MAX_OUTPUT_LENGTH)
}
