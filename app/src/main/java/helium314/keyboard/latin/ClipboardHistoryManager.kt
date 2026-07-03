// SPDX-License-Identifier: GPL-3.0-only

package helium314.keyboard.latin

import android.content.ClipboardManager
import android.content.Context
import android.text.InputType
import android.text.TextUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import androidx.core.view.isGone
import helium314.keyboard.compat.ClipboardManagerCompat
import helium314.keyboard.event.HapticEvent
import helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode
import helium314.keyboard.latin.common.ColorType
import helium314.keyboard.latin.common.isValidNumber
import helium314.keyboard.latin.database.ClipboardDao
import helium314.keyboard.latin.databinding.ClipboardSuggestionBinding
import androidx.annotation.VisibleForTesting
import helium314.keyboard.latin.utils.InputTypeUtils
import helium314.keyboard.latin.utils.Log
import helium314.keyboard.latin.utils.ToolbarKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ClipboardHistoryManager(
        private val latinIME: LatinIME
) : ClipboardManager.OnPrimaryClipChangedListener {

    private lateinit var clipboardManager: ClipboardManager
    private var clipboardSuggestionView: View? = null
    private var clipboardDao: ClipboardDao? = null
    private val scope = CoroutineScope(SupervisorJob() + loadDispatcher)
    // Description timestamp of the clip most recently read by captureCurrentClipIfEnabled(), used to
    // skip redundant content reads (and their OS clipboard-access toast) on repeated keyboard shows.
    private var lastCatchUpTimestamp = 0L

    fun onCreate() {
        clipboardManager = latinIME.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboardManager.addPrimaryClipChangedListener(this)
        // Constructing the DAO is cheap (opens no DB); the costly part is loading its cache
        // (SQLite open + per-row AES-GCM decrypt). Run that on a background thread so it never
        // blocks the IME main thread during cold start, which was dropping the first keystrokes.
        val dao = ClipboardDao.getInstance(latinIME)
        clipboardDao = dao
        scope.launch {
            try {
                dao?.ensureCacheLoaded()
                // withContext is a cancellation point: if onDestroy() cancelled the scope while the
                // (non-cancellable) DB load was running, this throws before the block runs, so we
                // never touch a torn-down IME.
                withContext(applyDispatcher) {
                    if (latinIME.mSettings.current.mClipboardHistoryEnabled) {
                        dao?.clearOldClips(true)
                        fetchPrimaryClip()
                    } else {
                        dao?.clear()
                    }
                }
            } catch (e: CancellationException) {
                throw e // never swallow cancellation
            } catch (t: Throwable) {
                // The load runs outside ClipboardDao.getInstance()'s try/catch and on a coroutine
                // with no handler, so an unhandled throw here would crash the IME process. Degrade to
                // an empty clipboard history instead.
                Log.e(TAG, "clipboard cache load failed", t)
            }
        }
    }

    fun onDestroy() {
        scope.cancel()
        clipboardManager.removePrimaryClipChangedListener(this)
    }

    override fun onPrimaryClipChanged() {
        // Make sure we read clipboard content only if history settings is set
        if (latinIME.mSettings.current.mClipboardHistoryEnabled) {
            fetchPrimaryClip()
            dontShowCurrentSuggestion = false
        }
    }

    /**
     * Records the current system clipboard entry if history is enabled. Call this when the keyboard
     * becomes visible (window shown / clipboard view opened): on Android 10+ an app may only read
     * the clipboard while it is the focused IME, so a clip copied while the keyboard was hidden is
     * NOT readable from the live [onPrimaryClipChanged] callback and would otherwise be lost — this
     * catch-up read picks it up the moment the keyboard reappears.
     *
     * The content read (which triggers the OS clipboard-access notification on Android 12+) is
     * skipped when the clip is unchanged since the last catch-up, using the description timestamp
     * (metadata only, no access toast). The work runs on the background [scope] and only touches the
     * cache after [ClipboardDao.ensureCacheLoaded], so it never blocks the IME main thread the way a
     * cold synchronous cache load would (which used to swallow the first keystrokes).
     */
    fun captureCurrentClipIfEnabled() {
        if (!latinIME.mSettings.current.mClipboardHistoryEnabled) return
        val timeStamp = ClipboardManagerCompat.getPrimaryClipDescriptionTimestamp(clipboardManager)
        // timeStamp == 0 means "unknown" (no clip, or API < 26): fall through and let addClip dedup
        // by content. A known, unchanged timestamp means we already captured this clip — skip the
        // content read so we don't re-trigger the clipboard-access toast on every keyboard show.
        if (timeStamp != 0L && timeStamp == lastCatchUpTimestamp) return
        lastCatchUpTimestamp = timeStamp
        scope.launch {
            try {
                clipboardDao?.ensureCacheLoaded()
                withContext(applyDispatcher) {
                    if (latinIME.mSettings.current.mClipboardHistoryEnabled)
                        fetchPrimaryClip()
                }
            } catch (e: CancellationException) {
                throw e // never swallow cancellation
            } catch (t: Throwable) {
                Log.e(TAG, "clipboard catch-up capture failed", t)
            }
        }
    }

    private fun fetchPrimaryClip() {
        val clipData = clipboardManager.primaryClip ?: return
        if (clipData.itemCount == 0 || clipData.description?.hasMimeType("text/*") == false) return
        clipData.getItemAt(0)?.let { clipItem ->
            val timeStamp = ClipboardManagerCompat.getClipTimestamp(clipData)
            val content = clipItem.coerceToText(latinIME)
            if (TextUtils.isEmpty(content)) return
            if (ClipboardManagerCompat.getClipSensitivity(clipData.description) == true) return
            clipboardDao?.addClip(timeStamp, false, content.toString())
        }
    }

    fun toggleClipPinned(id: Long) {
        clipboardDao?.togglePinned(id)
    }

    fun incrementUseCount(id: Long) {
        clipboardDao?.incrementUseCount(id)
    }

    fun setAnnotation(id: Long, annotation: String?) {
        clipboardDao?.setAnnotation(id, annotation)
    }

    fun deleteEntryById(id: Long) {
        clipboardDao?.deleteById(id)
    }

    fun clearHistory() {
        clipboardDao?.clearNonPinned()
        ClipboardManagerCompat.clearPrimaryClip(clipboardManager)
        removeClipboardSuggestion()
    }

    fun canRemove(index: Int) = clipboardDao?.isPinned(index) == false

    fun removeEntry(index: Int) {
        if (canRemove(index))
            clipboardDao?.deleteClipAt(index)
    }

    fun sortHistoryEntries() {
        clipboardDao?.sort()
    }

    // We do not want to update history while user is visualizing it, so we check retention only
    // when history is about to be shown
    fun prepareClipboardHistory() = clipboardDao?.clearOldClips(true)

    fun getHistorySize() = clipboardDao?.count() ?: 0

    fun getHistoryEntry(position: Int) = clipboardDao?.getAt(position)

    fun getHistoryEntryContent(id: Long) = clipboardDao?.get(id)

    fun setHistoryChangeListener(listener: ClipboardDao.Listener?) {
        clipboardDao?.listener = listener
    }

    fun retrieveClipboardContent(): CharSequence {
        val clipData = clipboardManager.primaryClip ?: return ""
        if (clipData.itemCount == 0) return ""
        return clipData.getItemAt(0)?.coerceToText(latinIME) ?: ""
    }

    private fun isClipSensitive(inputType: Int): Boolean {
        ClipboardManagerCompat.getClipSensitivity(clipboardManager.primaryClip?.description)?.let { return it }
        return InputTypeUtils.isPasswordInputType(inputType)
    }

    fun getClipboardSuggestionView(editorInfo: EditorInfo?, parent: ViewGroup?): View? {
        // maybe no need to create a new view
        // but a cache has to consider a few possible changes, so better don't implement without need
        clipboardSuggestionView = null

        // get the content, or return null
        if (!latinIME.mSettings.current.mSuggestClipboardContent) return null
        if (dontShowCurrentSuggestion) return null
        if (parent == null) return null
        val clipData = clipboardManager.primaryClip ?: return null
        if (clipData.itemCount == 0 || clipData.description?.hasMimeType("text/*") == false) return null
        val clipItem = clipData.getItemAt(0) ?: return null
        val timeStamp = ClipboardManagerCompat.getClipTimestamp(clipData)
        if (System.currentTimeMillis() - timeStamp > RECENT_TIME_MILLIS) return null
        val content = clipItem.coerceToText(latinIME)
        if (TextUtils.isEmpty(content)) return null
        val inputType = editorInfo?.inputType ?: InputType.TYPE_NULL
        if (InputTypeUtils.isNumberInputType(inputType) && !content.isValidNumber()) return null

        // create the view
        val binding = ClipboardSuggestionBinding.inflate(LayoutInflater.from(latinIME), parent, false)
        val textView = binding.clipboardSuggestionText
        latinIME.mSettings.getCustomTypeface()?.let { textView.typeface = it }
        textView.text = (if (isClipSensitive(inputType)) "*".repeat(content.length) else content)
            .take(200) // truncate displayed text for performance reasons
        val clipIcon = latinIME.mKeyboardSwitcher.keyboard.mIconsSet.getIconDrawable(ToolbarKey.PASTE.name.lowercase())
        textView.setCompoundDrawablesRelativeWithIntrinsicBounds(clipIcon, null, null, null)
        textView.setOnClickListener {
            dontShowCurrentSuggestion = true
            latinIME.onTextInput(content.toString())
            AudioAndHapticFeedbackManager.getInstance().performHapticAndAudioFeedback(KeyCode.NOT_SPECIFIED, it, HapticEvent.KEY_PRESS)
            binding.root.isGone = true
        }
        val closeButton = binding.clipboardSuggestionClose
        closeButton.setImageDrawable(latinIME.mKeyboardSwitcher.keyboard.mIconsSet.getIconDrawable(ToolbarKey.CLOSE_HISTORY.name.lowercase()))
        closeButton.setOnClickListener { removeClipboardSuggestion() }

        val colors = latinIME.mSettings.current.mColors
        textView.setTextColor(colors.get(ColorType.KEY_TEXT))
        clipIcon?.let { colors.setColor(it, ColorType.KEY_ICON) }
        colors.setColor(closeButton, ColorType.REMOVE_SUGGESTION_ICON)
        colors.setBackground(binding.root, ColorType.CLIPBOARD_SUGGESTION_BACKGROUND)

        clipboardSuggestionView = binding.root
        return clipboardSuggestionView
    }

    private fun removeClipboardSuggestion() {
        dontShowCurrentSuggestion = true
        val csv = clipboardSuggestionView ?: return
        if (csv.parent != null && !csv.isGone) {
            // clipboard view is shown ->
            latinIME.setNeutralSuggestionStrip()
            latinIME.mHandler.postResumeSuggestions(false)
        }
        csv.isGone = true
    }

    companion object {
        private const val TAG = "ClipboardHistoryManager"
        private var dontShowCurrentSuggestion: Boolean = false
        const val RECENT_TIME_MILLIS = 3 * 60 * 1000L // 3 minutes (for clipboard suggestions)

        // Dispatchers for the off-main-thread cache load + its main-thread apply step. Overridable
        // in tests (set both to Dispatchers.Unconfined) so the load runs synchronously and doesn't
        // race Robolectric's manual main-looper message pump. Production uses IO + Main.
        @VisibleForTesting var loadDispatcher: CoroutineDispatcher = Dispatchers.IO
        @VisibleForTesting var applyDispatcher: CoroutineDispatcher = Dispatchers.Main
    }
}
