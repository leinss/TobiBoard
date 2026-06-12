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
        // Offline auto-retry bounds: wait up to MAX_RECONNECT_ATTEMPTS windows of
        // RECONNECT_WAIT_MS_PER_ATTEMPT each, polling connectivity every RECONNECT_POLL_MS.
        private const val MAX_RECONNECT_ATTEMPTS = 3
        private const val RECONNECT_WAIT_MS_PER_ATTEMPT = 30_000L
        private const val RECONNECT_POLL_MS = 2_000L
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
        fun onError(message: String)
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
    private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var audioRecorder: AudioRecorder = AudioRecorder(outputFile = File(cacheAudioDir(), "rec_placeholder.wav"))
    @Volatile private var state = State.IDLE
    private var currentAudioFile: File? = null
    @Volatile private var transcriptionJob: Job? = null
    @Volatile private var transcriptionClient: Cancellable? = null
    private val activeTranscriptionToken = AtomicLong(0L)
    @Volatile private var stopFinalizeJob: Job? = null
    @Volatile private var isStopFinalizing = false
    @Volatile private var currentUseDedicatedStt = false

    fun getState() = state

    /** Exposed so UI can render a live amplitude meter. */
    fun getCurrentAmplitude(): Double = audioRecorder.currentAmplitude

    /** Exposed so UI can render an elapsed-time counter. */
    fun getCurrentDurationMs(): Long = audioRecorder.currentDurationMs

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

        // Fresh cache file per recording; older ones are swept on every start so a process
        // killed mid-recording can't leak audio across sessions.
        sweepOrphanRecordings()
        val audioFile = File(cacheAudioDir(), "rec_${System.currentTimeMillis()}.wav")
        currentAudioFile = audioFile
        audioRecorder = AudioRecorder(
            outputFile = audioFile,
            maxDurationMs = maxDurationSec * 1000L,
            autoStopSilenceMs = if (autoStopEnabled) autoStopSec * 1000L else 0L,
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
            callbacks.onError(context.getString(R.string.voice_error_no_audio))
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
            callbacks.onError(context.getString(R.string.voice_error_too_short))
            return
        }
        if (audioRecorder.lastMeanAmplitude < MIN_SPEECH_MEAN_AMPLITUDE) {
            wavFile.delete()
            currentAudioFile = null
            currentUseDedicatedStt = false
            state = State.IDLE
            callbacks.onFinished()
            callbacks.onError(context.getString(R.string.voice_error_silent))
            return
        }

        state = State.TRANSCRIBING
        callbacks.onTranscribing()

        val prefs = context.prefs()
        val provider = AiProvider.fromPref(prefs.getString(Settings.PREF_AI_PROVIDER, Defaults.PREF_AI_PROVIDER))
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
                callbacks.onError(context.getString(R.string.voice_error_no_model))
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

        transcriptionJob = backgroundScope.launch(CoroutineName("VoiceTranscription")) {
            try {
                val transcription = sanitizeTranscription(transcribeWithReconnect(client, wavFile, offlineRetryEnabled))
                UsageTracker.record(client.lastResponseTokens)
                if (transcription.isBlank()) {
                    finishTranscription(
                        requestToken = requestToken,
                        error = context.getString(R.string.voice_error_transcription_failed),
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
            } catch (e: Exception) {
                Log.e(TAG, "Transcription failed", e)
                finishTranscription(
                    requestToken = requestToken,
                    error = safeUserFacingError(context, e, R.string.voice_error_transcription_failed),
                )
            } finally {
                // Best-effort: delete the audio after the request, whether it succeeded or not.
                if (wavFile.exists()) wavFile.delete()
            }
        }
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
                activeTranscriptionToken.incrementAndGet()
                transcriptionClient?.cancel()
                transcriptionJob?.cancel()
                transcriptionJob = null
                transcriptionClient = null
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
        backgroundScope.cancel()
    }

    private fun cacheAudioDir(): File {
        val dir = File(context.cacheDir, AUDIO_CACHE_SUBDIR)
        dir.mkdirs()
        return dir
    }

    private fun sweepOrphanRecordings() {
        runCatching {
            cacheAudioDir().listFiles()?.forEach { file ->
                if (file.name.startsWith("rec_") && file.extension.equals("wav", ignoreCase = true)) {
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
    ) {
        mainHandler.post {
            if (activeTranscriptionToken.get() != requestToken) {
                return@post
            }
            transcriptionJob = null
            transcriptionClient = null
            currentUseDedicatedStt = false
            state = State.IDLE
            callbacks.onFinished()
            if (!result.isNullOrEmpty()) {
                callbacks.onTranscriptionResult(result)
            } else if (!error.isNullOrEmpty()) {
                callbacks.onError(error)
            }
        }
    }

}
