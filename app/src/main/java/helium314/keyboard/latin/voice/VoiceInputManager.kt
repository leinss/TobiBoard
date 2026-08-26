// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import android.Manifest
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import helium314.keyboard.latin.BuildConfig
import helium314.keyboard.latin.R
import helium314.keyboard.latin.permissions.PermissionsUtil
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.Log
import helium314.keyboard.latin.utils.prefs
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext

/**
 * Orchestrates voice recording, transcription via the selected AI provider, and text insertion.
 * All state transitions happen on the main thread.
 */
class VoiceInputManager(
    private val context: Context,
    private val callbacks: Callbacks
) {
    companion object {
        private const val TAG = "VoiceInputManager"
        private const val MAX_TRANSCRIPTION_LENGTH = 10_000
        // Settings nav-route strings for onOpenSettings — must match SettingsDestination constants.
        const val SETTINGS_VOICE = "voice"
        const val SETTINGS_LOCAL_MODELS = "local_models"
        private const val AUDIO_CACHE_SUBDIR = "voice_audio"
        private const val MIN_RECORDING_DURATION_MS = 500L
        private const val MIN_SPEECH_MEAN_AMPLITUDE = 80.0
        // Only sweep recordings old enough that they cannot belong to an in-flight session — a
        // rapid stop→record could otherwise delete the previous recording's file mid-finalize.
        private const val ORPHAN_RECORDING_MAX_AGE_MS = 60_000L
        // How long a failed clip is kept so the user can tap Retry. Deliberately short: audio is the
        // most sensitive thing this app touches, so it is deleted the moment a retry succeeds and
        // expires on its own otherwise. Long enough to read an error and tap once.
        private const val RETRY_AUDIO_RETENTION_MS = 120_000L
        // Offline auto-retry bounds: wait up to MAX_RECONNECT_ATTEMPTS windows of
        // RECONNECT_WAIT_MS_PER_ATTEMPT each, polling connectivity every RECONNECT_POLL_MS.
        private const val MAX_RECONNECT_ATTEMPTS = 3
        private const val RECONNECT_WAIT_MS_PER_ATTEMPT = 30_000L
        private const val RECONNECT_POLL_MS = 2_000L

        /**
         * True when a lifecycle-driven cancel (window hidden / input view finishing) should be
         * ignored so an in-progress on-device transcription can finish and commit. Pure, so it is
         * unit-tested without the recording/coroutine machinery. Cloud uploads and any state other
         * than TRANSCRIBING are never spared — they keep the cancel-on-dismiss behaviour.
         */
        internal fun letLocalTranscriptionFinish(state: State, provider: AiProvider?): Boolean =
            state == State.TRANSCRIBING && provider == AiProvider.LOCAL
    }

    enum class State { IDLE, RECORDING, TRANSCRIBING }

    /**
     * Snapshot of text immediately adjacent to the cursor, used for spacing heuristics.
     * Values are Unicode code points (surrogate-pair safe), or null if no text on that side.
     */
    data class SpacingContext(val charBefore: Int?, val charAfter: Int?)

    interface Callbacks {
        fun onRecordingStarted()
        fun onTranscribing()
        fun onFinished()
        fun onTranscriptionResult(text: String)
        /**
         * [canRetry] means the audio survived and [retryLastTranscription] would re-run it, so the
         * error should be shown with a Retry action rather than as a bare toast.
         */
        fun onError(message: String, canRetry: Boolean)
        fun onMaxDurationReached()
        /** Called when a transcription is paused, waiting for the network to come back. */
        fun onWaitingForNetwork() {}
        /**
         * Called instead of a toast when voice can't start because a required setup step is
         * missing (model not downloaded, feature not enabled, no API key). [settingsDestination]
         * is the nav-route string the settings activity should open to (e.g. "local_models",
         * "voice"). The IME should hide itself and launch settings at that destination.
         */
        fun onOpenSettings(settingsDestination: String) {}
        /** Optional IME subtype locale; used as a hint to the transcription model. */
        fun getLocaleHint(): Locale? = null
        /** Optional surrounding-text snapshot; used to decide whether to insert spaces. */
        fun getSpacingContext(): SpacingContext? = null
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Last-resort backstop. Without it, any Throwable escaping a [backgroundScope] coroutine reaches
     * the default handler and kills the whole IME process, taking the user's recording with it. The
     * on-device decode path runs sherpa-onnx over JNI ([helium314.keyboard.latin.voice.local.LocalSherpaEngine]),
     * which can raise Errors (OutOfMemoryError, UnsatisfiedLinkError) that a `catch (e: Exception)`
     * does not see. Note this cannot save a genuine native abort inside the shared library.
     */
    private val crashGuard = CoroutineExceptionHandler { _, t ->
        Log.e(TAG, "Uncaught failure in a voice coroutine", t)
        mainHandler.post { failCurrentAttempt(context.getString(R.string.voice_error_transcription_failed)) }
    }
    private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO + crashGuard)
    private var audioRecorder: AudioRecorder = AudioRecorder(outputFile = File(cacheAudioDir(), "rec_placeholder.wav"))
    @Volatile private var state = State.IDLE
    private var currentAudioFile: File? = null
    @Volatile private var transcriptionJob: Job? = null
    @Volatile private var transcriptionClient: Cancellable? = null
    private val activeTranscriptionToken = AtomicLong(0L)
    @Volatile private var stopFinalizeJob: Job? = null
    @Volatile private var isStopFinalizing = false
    @Volatile private var currentUseDedicatedStt = false
    // The provider backing the in-flight transcription, so a lifecycle-driven cancel can spare a
    // local decode (see cancelForLifecycle). Only meaningful while state == TRANSCRIBING.
    @Volatile private var currentProvider: AiProvider? = null
    // The one failed clip kept for [retryLastTranscription]. Held only as long as
    // RETRY_AUDIO_RETENTION_MS and dropped the moment it is no longer useful; see purgeRetryAudio.
    private val retryAudio = RetryAudioRetention()
    private val purgeRetryAudioRunnable = Runnable { purgeRetryAudio() }

    fun getState() = state

    /** Exposed so UI can render a live amplitude meter. */
    fun getCurrentAmplitude(): Double = audioRecorder.currentAmplitude

    /** Exposed so UI can render an elapsed-time counter. */
    fun getCurrentDurationMs(): Long = audioRecorder.currentDurationMs

    /** Recording ceiling for the active recorder, so the overlay can show elapsed against limit. */
    fun getMaxDurationMs(): Long = audioRecorder.maxDurationMs

    /** Maps the mic-sensitivity preference to a linear capture gain. "normal" leaves audio untouched. */
    private fun micSensitivityGain(value: String?): Float = when (value) {
        "high" -> 2f
        "max" -> 4f
        else -> 1f
    }

    @Synchronized
    fun startRecording(useDedicatedStt: Boolean = false) {
        if (state != State.IDLE) return
        currentUseDedicatedStt = useDedicatedStt

        val prefs = context.prefs()

        if (!prefs.getBoolean(Settings.PREF_VOICE_INPUT_ENABLED, Defaults.PREF_VOICE_INPUT_ENABLED)) {
            callbacks.onOpenSettings(SETTINGS_VOICE)
            return
        }

        val provider = AiProvider.fromPref(prefs.getString(Settings.PREF_AI_PROVIDER, Defaults.PREF_AI_PROVIDER))
        if (provider.isCloud) {
            // SecretStore is only needed for cloud API keys; LOCAL voice input never touches it.
            if (!SecretStore.isSecureStorageAvailable(context)) {
                callbacks.onOpenSettings(SETTINGS_VOICE)
                return
            }
            val apiKey = SecretStore.getApiKey(context, provider.apiKeyPrefKey(), provider.defaultApiKey())
            if (apiKey.isBlank()) {
                callbacks.onOpenSettings(SETTINGS_VOICE)
                return
            }
        }

        if (!PermissionsUtil.checkAllPermissionsGranted(context, Manifest.permission.RECORD_AUDIO)) {
            Toast.makeText(context, R.string.voice_error_no_permission, Toast.LENGTH_SHORT).show()
            return
        }

        if (provider.isCloud && !isNetworkAvailable(context)) {
            Toast.makeText(context, R.string.voice_error_no_network, Toast.LENGTH_SHORT).show()
            return
        }

        if (provider == AiProvider.LOCAL && !helium314.keyboard.latin.voice.local.ModelStorage.isReady(
                context, helium314.keyboard.latin.voice.local.SttModelInfo.ParakeetTdt06b
        )) {
            callbacks.onOpenSettings(SETTINGS_LOCAL_MODELS)
            return
        }

        if (currentUseDedicatedStt && provider != AiProvider.OPENROUTER) {
            currentUseDedicatedStt = false
            callbacks.onOpenSettings(SETTINGS_VOICE)
            return
        }

        val maxDurationSec = prefs.getInt(Settings.PREF_VOICE_MAX_DURATION_SECONDS, Defaults.PREF_VOICE_MAX_DURATION_SECONDS)
            .coerceIn(15, 300)
        val autoStopEnabled = prefs.getBoolean(Settings.PREF_VOICE_AUTO_STOP_SILENCE, Defaults.PREF_VOICE_AUTO_STOP_SILENCE)
        val autoStopSec = prefs.getInt(Settings.PREF_VOICE_AUTO_STOP_SILENCE_SECONDS, Defaults.PREF_VOICE_AUTO_STOP_SILENCE_SECONDS)
            .coerceIn(1, 10)
        val micGain = micSensitivityGain(
            prefs.getString(Settings.PREF_VOICE_MIC_SENSITIVITY, Defaults.PREF_VOICE_MIC_SENSITIVITY)
        )

        // A new dictation supersedes the failed one, so the retained clip is dead weight, so drop it
        // before the sweep rather than letting it sit out its retention window.
        purgeRetryAudio()
        // Fresh cache file per recording; older ones are swept on every start so a process
        // killed mid-recording can't leak audio across sessions.
        sweepOrphanRecordings()
        val audioFile = File(cacheAudioDir(), "rec_${System.currentTimeMillis()}.wav")
        currentAudioFile = audioFile
        // Tear down the previous recorder (including the placeholder created at construction) so its
        // coroutine scope doesn't leak for the lifetime of the IME process.
        audioRecorder.release()
        audioRecorder = AudioRecorder(
            outputFile = audioFile,
            maxDurationMs = maxDurationSec * 1000L,
            autoStopSilenceMs = if (autoStopEnabled) autoStopSec * 1000L else 0L,
            inputGain = micGain,
        )
        audioRecorder.onMaxDurationReached = {
            mainHandler.post {
                callbacks.onMaxDurationReached()
                stopRecording()
            }
        }
        audioRecorder.onAutoStopSilence = {
            mainHandler.post { stopRecording() }
        }

        if (!audioRecorder.start()) {
            Toast.makeText(context, R.string.voice_error_transcription_failed, Toast.LENGTH_SHORT).show()
            return
        }

        state = State.RECORDING
        callbacks.onRecordingStarted()
    }

    @Synchronized
    fun stopRecording() {
        if (state != State.RECORDING || isStopFinalizing) return
        isStopFinalizing = true

        // Kicking the WAV finalization off the main thread: AudioRecorder.stop() returns a
        // Deferred that completes once the recording loop drains and the file header is
        // written. Awaiting it here on the IME main thread used to ANR for up to 2s.
        val deferred = audioRecorder.stop()
        stopFinalizeJob = backgroundScope.launch(CoroutineName("VoiceFinalize")) {
            val wavFile = deferred.await()
            withContext(Dispatchers.Main.immediate) { onRecordingFinalized(wavFile) }
        }
    }

    @Synchronized
    private fun onRecordingFinalized(wavFile: File?) {
        isStopFinalizing = false
        stopFinalizeJob = null
        // The user may have cancelled while we were waiting for the recorder to drain.
        if (state != State.RECORDING) {
            wavFile?.takeIf { it.exists() }?.delete()
            return
        }

        if (wavFile == null || !wavFile.exists() || wavFile.length() <= 44L) {
            wavFile?.delete()
            currentAudioFile = null
            currentUseDedicatedStt = false
            state = State.IDLE
            callbacks.onFinished()
            callbacks.onError(context.getString(R.string.voice_error_no_audio), false)
            return
        }
        if (BuildConfig.DEBUG) {
            Log.i(
                TAG,
                "Uploading voice clip: durationMs=${audioRecorder.lastDurationMs}, meanAmplitude=${audioRecorder.lastMeanAmplitude}, bytes=${wavFile.length()}"
            )
        }
        if (audioRecorder.lastDurationMs < MIN_RECORDING_DURATION_MS) {
            wavFile.delete()
            currentAudioFile = null
            currentUseDedicatedStt = false
            state = State.IDLE
            callbacks.onFinished()
            callbacks.onError(context.getString(R.string.voice_error_too_short), false)
            return
        }
        if (audioRecorder.lastMeanAmplitude < MIN_SPEECH_MEAN_AMPLITUDE) {
            wavFile.delete()
            currentAudioFile = null
            currentUseDedicatedStt = false
            state = State.IDLE
            callbacks.onFinished()
            callbacks.onError(context.getString(R.string.voice_error_silent), false)
            return
        }

        state = State.TRANSCRIBING
        callbacks.onTranscribing()
        beginTranscription(wavFile)
    }

    /**
     * Runs transcription over [wavFile]. Split out of [onRecordingFinalized] so
     * [retryLastTranscription] can re-enter it with a retained clip. Callers must already have set
     * `state = TRANSCRIBING` and notified [Callbacks.onTranscribing].
     */
    @Synchronized
    private fun beginTranscription(wavFile: File) {
        val prefs = context.prefs()
        val provider = AiProvider.fromPref(prefs.getString(Settings.PREF_AI_PROVIDER, Defaults.PREF_AI_PROVIDER))
        currentProvider = provider
        val apiKey = if (provider.isCloud) {
            SecretStore.getApiKey(context, provider.apiKeyPrefKey(), provider.defaultApiKey())
        } else ""
        val selectedModel = prefs.getString(Settings.PREF_VOICE_MODEL, Defaults.PREF_VOICE_MODEL) ?: Defaults.PREF_VOICE_MODEL
        val customModel = prefs.getString(Settings.PREF_VOICE_MODEL_CUSTOM, Defaults.PREF_VOICE_MODEL_CUSTOM) ?: ""
        val selectedSttModel = prefs.getString(Settings.PREF_VOICE_STT_MODEL, Defaults.PREF_VOICE_STT_MODEL) ?: Defaults.PREF_VOICE_STT_MODEL
        val customSttModel = prefs.getString(Settings.PREF_VOICE_STT_MODEL_CUSTOM, Defaults.PREF_VOICE_STT_MODEL_CUSTOM) ?: ""
        val useDedicatedStt = currentUseDedicatedStt
        // STT has its own prompt, dictionary, and expected-languages prefs so users can tune
        // the dedicated transcription endpoint independently of the chat-audio path. Falling
        // back to the chat-audio defaults would re-couple the two flows, so we read each set
        // from its own keys.
        val savedPrompt = if (useDedicatedStt) {
            prefs.getString(Settings.PREF_VOICE_STT_PROMPT, Defaults.PREF_VOICE_STT_PROMPT)
                ?: Defaults.PREF_VOICE_STT_PROMPT
        } else {
            prefs.getString(Settings.PREF_VOICE_TRANSCRIPTION_PROMPT, Defaults.PREF_VOICE_TRANSCRIPTION_PROMPT)
                ?: Defaults.PREF_VOICE_TRANSCRIPTION_PROMPT
        }
        val transcriptionDictionary = if (useDedicatedStt) {
            prefs.getString(Settings.PREF_VOICE_STT_DICTIONARY, Defaults.PREF_VOICE_STT_DICTIONARY)
                ?: Defaults.PREF_VOICE_STT_DICTIONARY
        } else {
            prefs.getString(Settings.PREF_VOICE_TRANSCRIPTION_DICTIONARY, Defaults.PREF_VOICE_TRANSCRIPTION_DICTIONARY)
                ?: Defaults.PREF_VOICE_TRANSCRIPTION_DICTIONARY
        }
        val expectedLanguages = if (useDedicatedStt) {
            prefs.getString(Settings.PREF_VOICE_STT_EXPECTED_LANGUAGES, Defaults.PREF_VOICE_STT_EXPECTED_LANGUAGES)
                ?: Defaults.PREF_VOICE_STT_EXPECTED_LANGUAGES
        } else {
            prefs.getString(Settings.PREF_VOICE_EXPECTED_LANGUAGES, Defaults.PREF_VOICE_EXPECTED_LANGUAGES)
                ?: Defaults.PREF_VOICE_EXPECTED_LANGUAGES
        }
        val languageHintEnabled = prefs.getBoolean(Settings.PREF_VOICE_LANGUAGE_HINT, Defaults.PREF_VOICE_LANGUAGE_HINT)
        val spaceHeuristicEnabled = prefs.getBoolean(Settings.PREF_VOICE_SPACE_HEURISTIC, Defaults.PREF_VOICE_SPACE_HEURISTIC)
        val offlineRetryEnabled = prefs.getBoolean(Settings.PREF_VOICE_OFFLINE_RETRY, Defaults.PREF_VOICE_OFFLINE_RETRY)
        val useZdr = provider == AiProvider.OPENROUTER &&
            prefs.getBoolean(Settings.PREF_OPENROUTER_ZDR_ENABLED, Defaults.PREF_OPENROUTER_ZDR_ENABLED)

        // Two-pass auto-polish: after the raw transcription comes back, optionally pipe it
        // through a second, text-only LLM call that cleans it up to the chosen level. Resolved
        // here on the main thread so the background job receives plain values.
        // Auto-polish runs through OpenRouter / PayPerQ only — it would need a second on-device
        // model loaded simultaneously to work for LOCAL, which we don't ship today.
        val polishEnabled = provider.isCloud &&
            prefs.getBoolean(Settings.PREF_VOICE_AUTO_POLISH_ENABLED, Defaults.PREF_VOICE_AUTO_POLISH_ENABLED)
        val polishLevel = PolishLevel.fromPref(prefs.getString(Settings.PREF_VOICE_POLISH_LEVEL, Defaults.PREF_VOICE_POLISH_LEVEL))
        val polishSystemPrompt = polishPromptForLevel(polishLevel)
        val polishModelSelected = prefs.getString(Settings.PREF_VOICE_POLISH_MODEL, Defaults.PREF_VOICE_POLISH_MODEL) ?: Defaults.PREF_VOICE_POLISH_MODEL
        val polishModelCustom = prefs.getString(Settings.PREF_VOICE_POLISH_MODEL_CUSTOM, Defaults.PREF_VOICE_POLISH_MODEL_CUSTOM) ?: ""
        val polishModel = if (polishEnabled && polishSystemPrompt != null) {
            resolveProviderModel(polishModelSelected, polishModelCustom)
        } else null

        val model = if (provider.isCloud) {
            val resolved = if (useDedicatedStt) {
                resolveVoiceSttModel(selectedSttModel, customSttModel)
            } else {
                resolveProviderModel(selectedModel, customModel)
            }
            if (resolved == null) {
                wavFile.delete()
                currentUseDedicatedStt = false
                state = State.IDLE
                callbacks.onFinished()
                callbacks.onError(context.getString(R.string.voice_error_no_model), false)
                return
            }
            resolved
        } else ""
        val localeHint = if (languageHintEnabled) callbacks.getLocaleHint() else null
        val prompt = resolveVoicePrompt(savedPrompt, localeHint, transcriptionDictionary, expectedLanguages)
        val spacingContext = if (spaceHeuristicEnabled) callbacks.getSpacingContext() else null

        val client: SttEngine = when (provider) {
            AiProvider.LOCAL -> helium314.keyboard.latin.voice.local.LocalSherpaEngine(context)
            AiProvider.OPENROUTER, AiProvider.PAYPERQ -> OpenRouterClient(
                apiKey = apiKey,
                model = model,
                systemPrompt = prompt.systemPrompt,
                runtimeInstruction = prompt.runtimeInstruction,
                provider = provider,
                useZeroDataRetention = useZdr,
                transcriptionMode = if (useDedicatedStt) VoiceTranscriptionMode.OPENROUTER_STT else VoiceTranscriptionMode.CHAT_AUDIO,
                transcriptionLanguage = localeHint?.toOpenRouterSttLanguage(),
            )
        }
        val requestToken = activeTranscriptionToken.incrementAndGet()
        transcriptionClient = client
        val useDedicatedSttForRetry = useDedicatedStt

        transcriptionJob = backgroundScope.launch(CoroutineName("VoiceTranscription")) {
            // Set on any failure path, so the finally below keeps the clip for one Retry instead of
            // deleting it. A success or an explicit cancel leaves it false and the audio goes.
            var keepForRetry = false
            try {
                val transcription = sanitizeTranscription(transcribeWithReconnect(client, wavFile, offlineRetryEnabled))
                UsageTracker.record(client.lastResponseTokens)
                if (transcription.isBlank()) {
                    keepForRetry = true
                    // Retain before reporting: finishTranscription only posts to the main thread,
                    // and that post asks hasRetryableRecording() whether to offer Retry.
                    retainForRetry(wavFile, useDedicatedSttForRetry)
                    finishTranscription(
                        requestToken = requestToken,
                        error = context.getString(R.string.voice_error_transcription_failed),
                        canRetry = true,
                    )
                    return@launch
                }
                // Auto-polish stage. We swap transcriptionClient over so the manager-wide cancel
                // path tears down the polish connection if the user backs out. Any failure here
                // is non-fatal: we keep the raw transcription rather than dropping the user's
                // recording on the floor.
                val polished = if (polishEnabled && polishSystemPrompt != null && polishModel != null) {
                    val polishClient: TextFixEngine = OpenRouterClient(
                        apiKey = apiKey,
                        model = polishModel,
                        systemPrompt = polishSystemPrompt,
                        runtimeInstruction = null,
                        provider = provider,
                        useZeroDataRetention = useZdr,
                    )
                    transcriptionClient = polishClient
                    try {
                        val raw = runInterruptible { polishClient.fixText(transcription) }
                        UsageTracker.record(polishClient.lastResponseTokens)
                        sanitizeTranscription(raw).takeIf { it.isNotBlank() } ?: transcription
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (ie: InterruptedException) {
                        throw ie
                    } catch (pe: Exception) {
                        if (BuildConfig.DEBUG) Log.w(TAG, "Polish failed; falling back to raw transcription", pe)
                        transcription
                    } finally {
                        transcriptionClient = client
                    }
                } else transcription
                val finalText = applySpacing(polished, spacingContext)
                finishTranscription(requestToken = requestToken, result = finalText)
            } catch (e: CancellationException) {
                if (BuildConfig.DEBUG) Log.i(TAG, "Transcription cancelled")
                finishTranscription(requestToken = requestToken)
            } catch (e: InterruptedException) {
                if (BuildConfig.DEBUG) Log.i(TAG, "Transcription cancelled")
                finishTranscription(requestToken = requestToken)
            } catch (t: Throwable) {
                // Throwable, not Exception: the on-device decode goes through sherpa-onnx JNI, which
                // can raise Errors (OutOfMemoryError, UnsatisfiedLinkError). Those used to escape
                // this handler and kill the IME process mid-dictation.
                Log.e(TAG, "Transcription failed", t)
                keepForRetry = true
                // Retain before reporting, for the same ordering reason as the blank-result path.
                retainForRetry(wavFile, useDedicatedSttForRetry)
                finishTranscription(
                    requestToken = requestToken,
                    error = safeUserFacingError(context, t, R.string.voice_error_transcription_failed),
                    canRetry = true,
                )
            } finally {
                // Success or an explicit cancel: the clip has served its purpose, delete it now.
                if (!keepForRetry && wavFile.exists()) wavFile.delete()
            }
        }
    }

    /** True while a failed clip is still on disk and [retryLastTranscription] would do something. */
    @Synchronized
    fun hasRetryableRecording(): Boolean = retryAudio.hasRetainable()

    /**
     * Re-runs transcription over the clip kept by the last failed attempt. Returns false when there
     * is nothing to retry (expired, already consumed, or a recording is in flight), so the caller
     * can drop its Retry affordance.
     */
    @Synchronized
    fun retryLastTranscription(): Boolean {
        if (state != State.IDLE) return false
        // consume() hands ownership to this attempt without deleting: the transcription path deletes
        // on success and re-retains on another failure. Disarm the expiry first so it cannot delete
        // the file out from under the in-flight attempt.
        mainHandler.removeCallbacks(purgeRetryAudioRunnable)
        val consumed = retryAudio.consume() ?: return false
        currentAudioFile = consumed.file
        currentUseDedicatedStt = consumed.useDedicatedStt
        state = State.TRANSCRIBING
        callbacks.onTranscribing()
        beginTranscription(consumed.file)
        return true
    }

    /**
     * Keeps [wavFile] so one Retry is possible, replacing any previously retained clip, and arms the
     * expiry timer. At most one clip is ever held, for at most [RETRY_AUDIO_RETENTION_MS].
     */
    @Synchronized
    private fun retainForRetry(wavFile: File, useDedicatedStt: Boolean) {
        retryAudio.retain(wavFile, useDedicatedStt)
        if (!retryAudio.hasRetainable()) return
        mainHandler.removeCallbacks(purgeRetryAudioRunnable)
        mainHandler.postDelayed(purgeRetryAudioRunnable, RETRY_AUDIO_RETENTION_MS)
    }

    /** Deletes the retained clip now and disarms the expiry timer. Safe to call repeatedly. */
    @Synchronized
    private fun purgeRetryAudio() {
        mainHandler.removeCallbacks(purgeRetryAudioRunnable)
        retryAudio.purge()
    }

    /**
     * Resets to IDLE and reports [message] after a failure that bypassed the normal error path (the
     * [crashGuard] backstop). Keeps whatever clip is retained so Retry still works.
     */
    @Synchronized
    private fun failCurrentAttempt(message: String) {
        if (state == State.IDLE) return
        activeTranscriptionToken.incrementAndGet()
        transcriptionJob = null
        transcriptionClient = null
        currentProvider = null
        currentUseDedicatedStt = false
        state = State.IDLE
        callbacks.onFinished()
        callbacks.onError(message, hasRetryableRecording())
    }

    /**
     * Cancel triggered by the IME window hiding or the input view finishing — NOT the user tapping
     * the cancel (X) button or the IME being destroyed. An on-device transcription already in
     * progress is allowed to finish and commit: it's fast (~1 s) and offline, so aborting it here
     * just silently discards the user's utterance (there is no network request to stop). A live
     * recording and any cloud upload are still cancelled, so a dismissed keyboard doesn't leak a
     * pending network request that inserts text into a stale field seconds later.
     */
    @Synchronized
    fun cancelForLifecycle() {
        if (letLocalTranscriptionFinish(state, currentProvider)) {
            Log.i(TAG, "Lifecycle cancel ignored; letting local transcription finish")
            return
        }
        cancelRecording()
    }

    /** Cancel either a live recording or an in-flight upload. */
    @Synchronized
    fun cancelRecording() {
        when (state) {
            State.RECORDING -> {
                audioRecorder.cancel()
                // If a stop() was already in flight, its finalize callback will see state==IDLE
                // and discard the resulting file. Otherwise, the loop's finally deletes it.
                stopFinalizeJob?.cancel()
                stopFinalizeJob = null
                isStopFinalizing = false
                currentUseDedicatedStt = false
                currentAudioFile = null
                state = State.IDLE
                callbacks.onFinished()
            }
            State.TRANSCRIBING -> {
                // Always logged (not DEBUG-gated) so a transcription dropped before it commits is
                // diagnosable from a release build's log.
                Log.i(TAG, "Cancelling in-flight transcription (provider=$currentProvider)")
                activeTranscriptionToken.incrementAndGet()
                transcriptionClient?.cancel()
                transcriptionJob?.cancel()
                transcriptionJob = null
                transcriptionClient = null
                currentProvider = null
                currentUseDedicatedStt = false
                // The transcription thread's finally block will handle file deletion; only
                // reach in here if it couldn't start.
                currentAudioFile?.takeIf { it.exists() }?.delete()
                currentAudioFile = null
                state = State.IDLE
                callbacks.onFinished()
            }
            State.IDLE -> Unit
        }
    }

    /** Cancel any in-flight work and tear down the background scope. Call from IME onDestroy. */
    fun release() {
        cancelRecording()
        // Nothing can retry once the manager is gone, so don't leave a clip on disk for its full
        // retention window.
        purgeRetryAudio()
        audioRecorder.release()
        backgroundScope.cancel()
    }

    private fun cacheAudioDir(): File {
        val dir = File(context.cacheDir, AUDIO_CACHE_SUBDIR)
        dir.mkdirs()
        return dir
    }

    private fun sweepOrphanRecordings() {
        runCatching {
            val cutoff = System.currentTimeMillis() - ORPHAN_RECORDING_MAX_AGE_MS
            cacheAudioDir().listFiles()?.forEach { file ->
                if (file.name.startsWith("rec_") && file.extension.equals("wav", ignoreCase = true)
                    && file.lastModified() < cutoff
                    // The retained clip has its own expiry; the age sweep must not delete it out
                    // from under a Retry the user is about to tap.
                    && !retryAudio.isRetained(file)
                ) {
                    file.delete()
                }
            }
        }
    }

    /**
     * Runs the transcription, and — when [offlineRetryEnabled] — survives a network drop: if the
     * request fails while the device has no connectivity, it waits (bounded) for the network to
     * return and retries with the same retained audio, rather than discarding the recording. A
     * failure while connected is a real error and is rethrown immediately. Fully cancellable: a
     * back-out cancels the job, the awaited delay throws, and the caller's finally deletes the audio.
     */
    private suspend fun transcribeWithReconnect(
        client: SttEngine,
        wavFile: File,
        offlineRetryEnabled: Boolean,
    ): String {
        var reconnectAttempts = 0
        while (true) {
            try {
                return runInterruptible { client.transcribe(wavFile) }
            } catch (ce: CancellationException) {
                throw ce
            } catch (ie: InterruptedException) {
                throw ie
            } catch (e: Exception) {
                val offline = offlineRetryEnabled &&
                    reconnectAttempts < MAX_RECONNECT_ATTEMPTS &&
                    !isNetworkAvailable(context)
                if (!offline) throw e
                reconnectAttempts++
                if (BuildConfig.DEBUG) Log.i(TAG, "Offline; awaiting reconnect (attempt $reconnectAttempts)")
                withContext(Dispatchers.Main) { callbacks.onWaitingForNetwork() }
                if (!awaitNetwork(RECONNECT_WAIT_MS_PER_ATTEMPT)) throw e
                // Back online — return the UI to the transcribing state and retry the request.
                withContext(Dispatchers.Main) { callbacks.onTranscribing() }
            }
        }
    }

    /** Suspends until the device reports connectivity or [maxWaitMs] elapses; returns the final state. */
    private suspend fun awaitNetwork(maxWaitMs: Long): Boolean {
        var waited = 0L
        while (waited < maxWaitMs) {
            if (isNetworkAvailable(context)) return true
            delay(RECONNECT_POLL_MS)
            waited += RECONNECT_POLL_MS
        }
        return isNetworkAvailable(context)
    }

    private fun sanitizeTranscription(raw: String): String =
        sanitizeModelOutput(raw, MAX_TRANSCRIPTION_LENGTH)

    private fun finishTranscription(
        requestToken: Long,
        result: String? = null,
        error: String? = null,
        canRetry: Boolean = false,
    ) {
        mainHandler.post {
            if (activeTranscriptionToken.get() != requestToken) {
                return@post
            }
            transcriptionJob = null
            transcriptionClient = null
            currentProvider = null
            currentUseDedicatedStt = false
            state = State.IDLE
            callbacks.onFinished()
            if (!result.isNullOrEmpty()) {
                // The attempt produced text, so nothing is left to retry. Drop the audio now rather
                // than waiting for the retention timer.
                purgeRetryAudio()
                callbacks.onTranscriptionResult(result)
            } else if (!error.isNullOrEmpty()) {
                // The finally in the transcription job runs after this post is queued, so re-check
                // the file rather than trusting canRetry alone.
                callbacks.onError(error, canRetry && hasRetryableRecording())
            }
        }
    }

}
