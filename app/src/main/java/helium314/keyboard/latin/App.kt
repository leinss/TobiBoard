// SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
package helium314.keyboard.latin

import android.app.Application
import android.os.Build
import helium314.keyboard.keyboard.emoji.SupportedEmojis
import helium314.keyboard.latin.define.DebugFlags
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.LayoutUtilsCustom
import helium314.keyboard.latin.utils.Log
import helium314.keyboard.latin.utils.SubtypeSettings
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.latin.voice.SecretStore
import helium314.keyboard.latin.voice.local.LocalSherpaEngine
import helium314.keyboard.latin.voice.local.ModelDownloadRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        DebugFlags.init(this)
        Settings.init(this)
        SubtypeSettings.init(this)

        val scope = CoroutineScope(Dispatchers.Default)
        scope.launch { // do some uncritical work in background for faster startup
            SupportedEmojis.load(this@App)
            LayoutUtilsCustom.removeMissingLayouts(this@App)
            // Warm up EncryptedSharedPreferences + AndroidKeyStore master key so the first
            // voice/text-fix request doesn't pay the cold-decrypt cost on the IME main thread.
            // Gated on the features that actually read the API key: when neither voice input nor
            // text-fix is enabled we never touch the keystore at startup.
            val prefs = this@App.prefs()
            if (prefs.getBoolean(Settings.PREF_VOICE_INPUT_ENABLED, Defaults.PREF_VOICE_INPUT_ENABLED)
                || prefs.getBoolean(Settings.PREF_TEXT_FIX_ENABLED, Defaults.PREF_TEXT_FIX_ENABLED)
            ) {
                SecretStore.warmUp(this@App)
            }
            ModelDownloadRepository.rehydrate(this@App)
            // Cold-init of the sherpa-onnx recognizer is ~2.7 s; doing it on first user
            // utterance is felt as a hang. Building it here is a no-op if Parakeet is not
            // on disk.
            LocalSherpaEngine.warmUp(this@App)
            val packageInfo = packageManager.getPackageInfo(packageName, 0)
            @Suppress("DEPRECATION")
            Log.i(
                "startup", "Starting ${applicationInfo.processName} version ${packageInfo.versionName} (${
                    packageInfo.versionCode
                }) on Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})"
            )
        }

        RichInputMethodManager.init(this)
        checkVersionUpgrade(this)
        transferOldPinnedClips(this) // todo: remove in a few months, maybe end 2026
        app = this
        Defaults.initDynamicDefaults(this)
    }

    companion object {
        // used so JniUtils can access application once
        private var app: App? = null
        fun getApp(): App? {
            val application = app
            app = null
            return application
        }
    }
}
