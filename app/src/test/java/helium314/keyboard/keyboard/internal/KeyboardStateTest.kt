package helium314.keyboard.keyboard.internal

import android.text.TextUtils
import helium314.keyboard.event.Event
import helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode
import helium314.keyboard.latin.common.Constants
import helium314.keyboard.latin.utils.RecapitalizeMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class KeyboardStateTest {
    private val actions = FakeSwitchActions()
    private val state = KeyboardState(actions)

    @Test
    fun shiftTapAppliesToOneLetterOnly() {
        load()

        tap(KeyCode.SHIFT)
        assertEquals(Layout.MANUAL_SHIFTED, actions.layout)

        typeLetter('A', Constants.TextUtils.CAP_MODE_OFF)

        assertEquals(Layout.ALPHABET, actions.layout)
        assertFalse(actions.everShiftLocked)
    }

    @Test
    fun shiftTapWhileAutoShiftedWaitsForNextLetter() {
        load()
        state.onUpdateShiftState(TextUtils.CAP_MODE_SENTENCES, null)
        assertEquals(Layout.AUTOMATIC_SHIFTED, actions.layout)

        tap(KeyCode.SHIFT, TextUtils.CAP_MODE_SENTENCES)

        assertEquals(Layout.MANUAL_SHIFTED, actions.layout)
        assertFalse(actions.everShiftLocked)
    }

    @Test
    fun shiftUpdateAfterShiftTapDoesNotRestoreAutomaticShift() {
        load()
        state.onUpdateShiftState(TextUtils.CAP_MODE_SENTENCES, null)
        assertEquals(Layout.AUTOMATIC_SHIFTED, actions.layout)

        tap(KeyCode.SHIFT, TextUtils.CAP_MODE_SENTENCES)
        state.onUpdateShiftState(TextUtils.CAP_MODE_SENTENCES, null)
        assertEquals(Layout.MANUAL_SHIFTED, actions.layout)

        typeLetter('A', TextUtils.CAP_MODE_SENTENCES)

        assertEquals(Layout.ALPHABET, actions.layout)
        assertFalse(actions.everShiftLocked)
    }

    @Test
    fun automaticSentenceCapsStillUsesOneLetterOnly() {
        load()
        state.onUpdateShiftState(TextUtils.CAP_MODE_SENTENCES, null)
        assertEquals(Layout.AUTOMATIC_SHIFTED, actions.layout)

        typeLetter('A', Constants.TextUtils.CAP_MODE_OFF)

        assertEquals(Layout.ALPHABET, actions.layout)
        assertFalse(actions.everShiftLocked)
    }

    @Test
    fun capsLockRemainsLockedAfterTypingLetter() {
        load()

        tap(KeyCode.CAPS_LOCK)
        assertEquals(Layout.SHIFT_LOCKED, actions.layout)

        typeLetter('A', Constants.TextUtils.CAP_MODE_OFF)

        assertEquals(Layout.SHIFT_LOCKED, actions.layout)
    }

    @Test
    fun capsLockViaLongPressTurnsOffOnNextTap() {
        load()

        // Mirror PointerTracker.onLongPressed for a no-panel-auto-popup key: the shift touch-down runs
        // a normal shift press, then the long-press dispatches the CAPS_LOCK popup and cancels shift
        // tracking, so the shift key never receives its own release (it stays PRESSING).
        state.onPressKey(KeyCode.SHIFT, true, Constants.TextUtils.CAP_MODE_OFF, null)
        state.onPressKey(KeyCode.CAPS_LOCK, true, Constants.TextUtils.CAP_MODE_OFF, null)
        state.onEvent(Event.createSoftwareKeypressEvent(KeyCode.CAPS_LOCK, 0, 0, 0, false), Constants.TextUtils.CAP_MODE_OFF, null)
        state.onReleaseKey(KeyCode.CAPS_LOCK, false, Constants.TextUtils.CAP_MODE_OFF, null)
        assertEquals(Layout.SHIFT_LOCKED, actions.layout)

        tap(KeyCode.SHIFT)
        assertEquals(Layout.ALPHABET, actions.layout)
    }

    @Test
    fun capsLockTurnsOffOnShiftPressEvenWhenReleaseIsDropped() {
        load()

        // Enable caps lock the way long-pressing shift does (a CAPS_LOCK key event).
        tap(KeyCode.CAPS_LOCK)
        assertEquals(Layout.SHIFT_LOCKED, actions.layout)

        // On a real device, pressing Shift while shift-locked moves to shift-lock-shifted, which
        // changes the keyboard layout and cancels the pointer tracker -- so the Shift release event
        // is dropped and onReleaseKey(SHIFT) never arrives (confirmed via logcat). The turn-off must
        // therefore happen on the press alone, with no release to follow.
        state.onPressKey(KeyCode.SHIFT, true, Constants.TextUtils.CAP_MODE_OFF, null)
        assertEquals(Layout.ALPHABET, actions.layout)
    }

    @Test
    fun capsLockTurnsOffOnSingleShiftTap() {
        load()

        // Enable caps lock the way long-pressing shift does (a CAPS_LOCK key event).
        tap(KeyCode.CAPS_LOCK)
        assertEquals(Layout.SHIFT_LOCKED, actions.layout)

        // A deliberate (post-timeout) single tap turns it back off.
        tap(KeyCode.SHIFT)
        assertEquals(Layout.ALPHABET, actions.layout)
    }

    @Test
    fun capsLockTurnsOffOnQuickShiftTapWithinDoubleTapWindow() {
        load()

        tap(KeyCode.CAPS_LOCK)
        assertEquals(Layout.SHIFT_LOCKED, actions.layout)

        // A quick tap (still inside the double-tap window after enabling) must also turn it off,
        // not be swallowed by double-tap detection.
        actions.inDoubleTapTimeout = true
        tap(KeyCode.SHIFT)
        assertEquals(Layout.ALPHABET, actions.layout)
    }

    @Test
    fun capsLockTurnsOffWhenShiftUpdateFiresDuringCodeInputWithAutoCaps() {
        load()

        // Enable caps lock the way long-pressing shift does (a CAPS_LOCK key event).
        tap(KeyCode.CAPS_LOCK)
        assertEquals(Layout.SHIFT_LOCKED, actions.layout)

        // Real KeyboardSwitcher.requestUpdatingShiftState synchronously re-enters
        // KeyboardState.onUpdateShiftState. InputLogic fires SHIFT_UPDATE_NOW during
        // onCodeInput(SHIFT) -- i.e. AFTER onEvent(SHIFT) but BEFORE onReleaseKey(SHIFT).
        // Reproduce that ordering here, with auto-caps wanting characters (harshest case).
        state.onPressKey(KeyCode.SHIFT, true, TextUtils.CAP_MODE_CHARACTERS, null)
        state.onEvent(Event.createSoftwareKeypressEvent(KeyCode.SHIFT, 0, 0, 0, false),
            TextUtils.CAP_MODE_CHARACTERS, null)
        state.onUpdateShiftState(TextUtils.CAP_MODE_CHARACTERS, null)
        state.onReleaseKey(KeyCode.SHIFT, false, TextUtils.CAP_MODE_CHARACTERS, null)

        assertEquals(Layout.ALPHABET, actions.layout)
    }

    @Test
    fun capsLockViaLongPressTurnsOffWhenShiftUpdateFiresDuringCodeInputWithAutoCaps() {
        load()

        // Mirror PointerTracker.onLongPressed for a no-panel-auto-popup key: the shift touch-down
        // runs a normal shift press, then the long-press dispatches the CAPS_LOCK popup and cancels
        // shift tracking, so the shift key never receives its own release (it stays PRESSING).
        state.onPressKey(KeyCode.SHIFT, true, Constants.TextUtils.CAP_MODE_OFF, null)
        state.onPressKey(KeyCode.CAPS_LOCK, true, Constants.TextUtils.CAP_MODE_OFF, null)
        state.onEvent(Event.createSoftwareKeypressEvent(KeyCode.CAPS_LOCK, 0, 0, 0, false),
            Constants.TextUtils.CAP_MODE_OFF, null)
        state.onReleaseKey(KeyCode.CAPS_LOCK, false, Constants.TextUtils.CAP_MODE_OFF, null)
        assertEquals(Layout.SHIFT_LOCKED, actions.layout)

        // Turn-off tap, but with the real InputLogic-driven shift update firing between
        // onCodeInput(SHIFT) and onReleaseKey(SHIFT), and auto-caps wanting uppercase.
        state.onPressKey(KeyCode.SHIFT, true, TextUtils.CAP_MODE_CHARACTERS, null)
        state.onEvent(Event.createSoftwareKeypressEvent(KeyCode.SHIFT, 0, 0, 0, false),
            TextUtils.CAP_MODE_CHARACTERS, null)
        state.onUpdateShiftState(TextUtils.CAP_MODE_CHARACTERS, null)
        state.onReleaseKey(KeyCode.SHIFT, false, TextUtils.CAP_MODE_CHARACTERS, null)

        assertEquals(Layout.ALPHABET, actions.layout)
    }

    // --- symbols layer must never stay stuck on the shifted (second) page ---

    @Test
    fun symbolsShiftedIsForgottenAfterPlainReturnToAlphabet() {
        load()

        tap(KeyCode.SYMBOL_ALPHA)
        assertEquals(Layout.SYMBOLS, actions.layout)
        tap(KeyCode.SHIFT)
        assertEquals(Layout.SYMBOLS_SHIFTED, actions.layout)
        typeSymbol('€')

        tap(KeyCode.SYMBOL_ALPHA)
        assertEquals(Layout.ALPHABET, actions.layout)

        tap(KeyCode.SYMBOL_ALPHA)
        assertEquals(Layout.SYMBOLS, actions.layout)
    }

    @Test
    fun symbolsShiftedIsForgottenWhenTheAbcKeyReleaseIsSwallowedByAFingerDrag() {
        load()

        tap(KeyCode.SYMBOL_ALPHA)
        tap(KeyCode.SHIFT)
        assertEquals(Layout.SYMBOLS_SHIFTED, actions.layout)

        // SYMBOL_ALPHA is a modifier key (KeyCode.isModifier), so PointerTracker drops its release
        // when the finger drifts off the key while down, a sloppy everyday tap. The reset used to
        // live only in onReleaseAlphaSymbol, so the shifted page stayed remembered.
        state.onPressKey(KeyCode.SYMBOL_ALPHA, true, Constants.TextUtils.CAP_MODE_OFF, null)
        state.onEvent(
            Event.createSoftwareKeypressEvent(KeyCode.SYMBOL_ALPHA, 0, 0, 0, false),
            Constants.TextUtils.CAP_MODE_OFF, null
        )
        state.onFinishSlidingInput(Constants.TextUtils.CAP_MODE_OFF, null)
        assertEquals(Layout.ALPHABET, actions.layout)

        tap(KeyCode.SYMBOL_ALPHA)
        assertEquals(Layout.SYMBOLS, actions.layout)
    }

    @Test
    fun symbolsShiftedIsForgottenWhenTheAppRestartsInputOnTheSameField() {
        load()

        tap(KeyCode.SYMBOL_ALPHA)
        tap(KeyCode.SHIFT)
        assertEquals(Layout.SYMBOLS_SHIFTED, actions.layout)

        // A chat composer that clears itself on send makes the framework restart input on the same
        // field; LatinIME answers with resetKeyboardStateToAlphabet. This used to record the shifted
        // page and never clear it, so the next symbols key opened the second layer.
        state.onResetKeyboardStateToAlphabet(Constants.TextUtils.CAP_MODE_OFF, null)
        assertEquals(Layout.ALPHABET, actions.layout)

        tap(KeyCode.SYMBOL_ALPHA)
        assertEquals(Layout.SYMBOLS, actions.layout)
    }

    @Test
    fun symbolsShiftedIsForgottenWhenTheNumpadForcesAReturnToAlphabet() {
        load()

        tap(KeyCode.SYMBOL_ALPHA)
        tap(KeyCode.SHIFT)
        assertEquals(Layout.SYMBOLS_SHIFTED, actions.layout)

        state.toggleNumpad(false, Constants.TextUtils.CAP_MODE_OFF, null, false, true)
        assertEquals(Layout.NUMPAD, actions.layout)
        // Space in the numpad with "alphabet after numpad and space" enabled.
        state.toggleNumpad(false, Constants.TextUtils.CAP_MODE_OFF, null, true, false)
        assertEquals(Layout.ALPHABET, actions.layout)

        tap(KeyCode.SYMBOL_ALPHA)
        assertEquals(Layout.SYMBOLS, actions.layout)
    }

    @Test
    fun numpadStillReturnsToTheSymbolsPageItCameFrom() {
        load()

        tap(KeyCode.SYMBOL_ALPHA)
        tap(KeyCode.SHIFT)
        assertEquals(Layout.SYMBOLS_SHIFTED, actions.layout)

        // The one restore that must survive: numpad and back, without passing through alphabet.
        state.toggleNumpad(false, Constants.TextUtils.CAP_MODE_OFF, null, false, true)
        assertEquals(Layout.NUMPAD, actions.layout)
        state.toggleNumpad(false, Constants.TextUtils.CAP_MODE_OFF, null, false, false)
        assertEquals(Layout.SYMBOLS_SHIFTED, actions.layout)
    }

    private fun load() {
        state.onLoadKeyboard(Constants.TextUtils.CAP_MODE_OFF, null, false)
        actions.resetHistory()
    }

    private fun tap(code: Int) {
        tap(code, Constants.TextUtils.CAP_MODE_OFF)
    }

    private fun tap(code: Int, autoCapsFlags: Int) {
        state.onPressKey(code, true, autoCapsFlags, null)
        state.onEvent(Event.createSoftwareKeypressEvent(code, 0, 0, 0, false), autoCapsFlags, null)
        state.onReleaseKey(code, false, autoCapsFlags, null)
    }

    /** Type a printable non-space character, i.e. what a symbols key produces. */
    private fun typeSymbol(symbol: Char) = typeLetter(symbol, Constants.TextUtils.CAP_MODE_OFF)

    private fun typeLetter(letter: Char, autoCapsFlags: Int) {
        state.onPressKey(letter.code, true, autoCapsFlags, null)
        state.onEvent(Event.createEventForCodePointFromUnknownSource(letter.code), autoCapsFlags, null)
        state.onReleaseKey(letter.code, false, autoCapsFlags, null)
    }

    private class FakeSwitchActions : KeyboardState.SwitchActions {
        var layout = Layout.ALPHABET
            private set
        var everShiftLocked = false
            private set

        fun resetHistory() {
            everShiftLocked = false
        }

        override fun setAlphabetKeyboard() {
            layout = Layout.ALPHABET
        }

        override fun setAlphabetManualShiftedKeyboard() {
            layout = Layout.MANUAL_SHIFTED
        }

        override fun setAlphabetAutomaticShiftedKeyboard() {
            layout = Layout.AUTOMATIC_SHIFTED
        }

        override fun setAlphabetShiftLockedKeyboard() {
            layout = Layout.SHIFT_LOCKED
            everShiftLocked = true
        }

        override fun setAlphabetShiftLockShiftedKeyboard() {
            layout = Layout.SHIFT_LOCK_SHIFTED
            everShiftLocked = true
        }

        override fun setEmojiKeyboard() {
            layout = Layout.EMOJI
        }

        override fun setClipboardKeyboard() {
            layout = Layout.CLIPBOARD
        }

        override fun setNumpadKeyboard() {
            layout = Layout.NUMPAD
        }

        override fun toggleNumpad(withSliding: Boolean, autoCapsFlags: Int, recapitalizeMode: RecapitalizeMode?, forceReturnToAlpha: Boolean) {}

        override fun setSymbolsKeyboard() {
            layout = Layout.SYMBOLS
        }

        override fun setSymbolsShiftedKeyboard() {
            layout = Layout.SYMBOLS_SHIFTED
        }

        override fun requestUpdatingShiftState(autoCapsFlags: Int, recapitalizeMode: RecapitalizeMode?) {}
        override fun startDoubleTapShiftKeyTimer() {}
        var inDoubleTapTimeout = false
        override val isInDoubleTapShiftKeyTimeout get() = inDoubleTapTimeout
        override fun cancelDoubleTapShiftKeyTimer() {}
        override fun setOneHandedModeEnabled(enabled: Boolean) {}
        override fun switchOneHandedMode() {}
    }

    private enum class Layout {
        ALPHABET,
        MANUAL_SHIFTED,
        AUTOMATIC_SHIFTED,
        SHIFT_LOCKED,
        SHIFT_LOCK_SHIFTED,
        SYMBOLS,
        SYMBOLS_SHIFTED,
        NUMPAD,
        EMOJI,
        CLIPBOARD,
    }
}
