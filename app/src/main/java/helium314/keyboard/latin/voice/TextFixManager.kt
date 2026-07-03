// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.inputmethod.EditorInfo
import androidx.annotation.StringRes
import helium314.keyboard.latin.R
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.Log
import helium314.keyboard.latin.utils.prefs
import kotlinx.coroutines.CancellationException
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
            // Sensitive signals first — caller-supplied flags and IME-level hints.
            if (isPasswordField || noLearning || incognitoModeEnabled) {
                return R.string.text_fix_error_sensitive_field
            }
            if ((imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) != 0) {
                return R.string.text_fix_error_sensitive_field
            }
            // TYPE_TEXT_FLAG_NO_SUGGESTIONS means "disable autocomplete strip" — it is widely set
            // by cross-platform frameworks (React Native, Flutter) for non-privacy reasons and is
            // NOT a signal that the field content is sensitive. Do not block text fix on it.
            when (inputType and InputType.TYPE_MASK_CLASS) {
                InputType.TYPE_CLASS_TEXT -> { /* always allowed */ }
                InputType.TYPE_CLASS_NUMBER -> {
                    // Preventative: today we reject NUMBER as unsupported, but check password
                    // variation first so it registers as sensitive rather than unsupported.
                    if ((inputType and InputType.TYPE_MASK_VARIATION) ==
                        InputType.TYPE_NUMBER_VARIATION_PASSWORD) {
                        return R.string.text_fix_error_sensitive_field
                    }
                    return R.string.text_fix_error_unsupported_field
                }
                else -> {
                    // DATETIME / PHONE / etc. — unsupported.
                    return R.string.text_fix_error_unsupported_field
                }
            }
            return null
        }
    }

    enum class State { IDLE, WORKING }

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
        fun onWorking()
        fun onFinished()
        fun onResult(originalText: String, proposedText: String)
        fun onError(message: String)
        /** Called instead of a toast when a required setup step is missing. See [VoiceInputManager.Callbacks.onOpenSettings]. */
        fun onOpenSettings(settingsDestination: String) {}
    }

    private val mainHandler = Handler(Looper.getMainLooper())
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
            callbacks.onOpenSettings(SETTINGS_TEXT_FIX)
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
        state = State.WORKING
        callbacks.onWorking()
        val token = activeToken + 1
        activeToken = token

        activeJob = backgroundScope.launch(CoroutineName("TextFixRequest")) {
            // SecretStore is only needed for cloud API keys; LOCAL provider never touches it.
            if (provider.isCloud && !SecretStore.isSecureStorageAvailable(context)) {
                Log.d(TAG, "startTextFix: SecretStore unavailable — navigating to text_fix settings")
                finishWithSettings(token, SETTINGS_TEXT_FIX)
                return@launch
            }
            val apiKey = if (provider.isCloud) {
                SecretStore.getApiKey(context, provider.apiKeyPrefKey(), provider.defaultApiKey())
            } else ""
            if (provider.isCloud && apiKey.isBlank()) {
                Log.d(TAG, "startTextFix: no API key — navigating to text_fix settings")
                finishWithSettings(token, SETTINGS_TEXT_FIX)
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
                    finishWithSettings(token, SETTINGS_LOCAL_MODELS)
                    return@launch
                }
            }
            val model = if (provider.isCloud) {
                resolveProviderModel(selectedModel, customModel) ?: run {
                    finishWithSettings(token, SETTINGS_TEXT_FIX)
                    return@launch
                }
            } else ""

            // Bail before any heavy work if a cancel raced in during the preflight.
            if (activeToken != token) return@launch

            // Make the fix aware of the user's personal dictionary so it does not "correct" their
            // custom words away. Reads the user-dictionary provider (I/O) — fine here on Dispatchers.IO.
            val effectivePrompt = PersonalDictionaryPrompt.augment(context, prompt)

            val client: TextFixEngine = when (provider) {
                AiProvider.LOCAL -> helium314.keyboard.latin.voice.local.LocalLiteRtEngine(context, effectivePrompt)
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
                val proposed = sanitize(runInterruptible { client.fixText(input) })
                UsageTracker.record(client.lastResponseTokens)
                if (proposed.isBlank()) {
                    finish(token, error = context.getString(R.string.text_fix_error_empty))
                    return@launch
                }
                finish(token, original = input, result = proposed)
            } catch (e: CancellationException) {
                finish(token)
            } catch (e: InterruptedException) {
                finish(token)
            } catch (e: Exception) {
                Log.e(TAG, "Text fix failed", e)
                finish(token, error = safeUserFacingError(context, e, R.string.text_fix_error_failed))
            }
        }
    }

    @Synchronized
    fun cancel() {
        if (state != State.WORKING) return
        activeToken += 1
        activeClient?.cancel()
        activeJob?.cancel()
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
            activeJob = null
            activeClient = null
            state = State.IDLE
            callbacks.onFinished()
            if (original != null && !result.isNullOrEmpty()) {
                callbacks.onResult(original, result)
            } else if (!error.isNullOrEmpty()) {
                callbacks.onError(error)
            }
        }
    }

    /** Reset to idle and route the user to settings — used when a background precondition fails. */
    private fun finishWithSettings(token: Long, settingsDestination: String) {
        mainHandler.post {
            if (activeToken != token) return@post
            activeJob = null
            activeClient = null
            state = State.IDLE
            callbacks.onFinished()
            callbacks.onOpenSettings(settingsDestination)
        }
    }

    private fun sanitize(raw: String): String = sanitizeModelOutput(raw, MAX_OUTPUT_LENGTH)
}
