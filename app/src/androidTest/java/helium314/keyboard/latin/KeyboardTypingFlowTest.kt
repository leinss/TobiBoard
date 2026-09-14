// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin

import android.graphics.Point
import android.os.SystemClock
import android.text.InputType
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.activity.ComponentActivity
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import helium314.keyboard.ImeShell
import helium314.keyboard.UiFlowTest
import helium314.keyboard.keyboard.KeyboardSwitcher
import helium314.keyboard.keyboard.MainKeyboardView
import helium314.keyboard.latin.common.Constants
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Drives the keyboard itself: select the IME, focus a text field, type through the keys, long-press
 * the Return key for its action popup.
 *
 * Keys are drawn by a custom view rather than laid out as child views, so nothing here can be
 * addressed by a matcher. The test reads the live keyboard's geometry and injects touch events at
 * the resulting screen coordinates.
 */
@RunWith(AndroidJUnit4::class)
@UiFlowTest
class KeyboardTypingFlowTest {

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val device: UiDevice by lazy { UiDevice.getInstance(instrumentation) }

    private lateinit var previousIme: String
    private lateinit var scenario: ActivityScenario<ComponentActivity>
    private lateinit var editText: EditText

    @Before
    fun setUp() {
        previousIme = ImeShell.enableAndSelect()
        scenario = ActivityScenario.launch(ComponentActivity::class.java)
        scenario.onActivity { activity ->
            // Plain TYPE_CLASS_TEXT, so no auto-capitalisation: the shifted layout would give the
            // letter keys uppercase codes and make the key lookup below miss.
            editText = EditText(activity).apply {
                id = View.generateViewId()
                inputType = InputType.TYPE_CLASS_TEXT
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }
            activity.setContentView(editText)
            editText.requestFocus()
            val imm = activity.getSystemService(InputMethodManager::class.java)
            imm.showSoftInput(editText, InputMethodManager.SHOW_IMPLICIT)
        }
        device.waitForIdle()
        // The first run after an install has to bind the IME service, which sometimes outlasts one
        // showSoftInput. Ask again rather than fail: the alternative is a suite that is red once
        // per fresh install and green on the retry.
        repeat(3) { attempt ->
            if (awaitKeyboardShown()) return@repeat
            if (attempt == 2) fail("the keyboard never appeared; is $IME_HINT")
            scenario.onActivity { activity ->
                editText.requestFocus()
                activity.getSystemService(InputMethodManager::class.java)
                    .showSoftInput(editText, InputMethodManager.SHOW_IMPLICIT)
            }
            device.waitForIdle()
        }
        awaitKeyboardSettled()
    }

    @After
    fun tearDown() {
        // Order matters: hide the keyboard and drop the activity before switching the IME back.
        // Sending the app home first, or waiting for the keyboard view to detach, races the window
        // teardown and crashes the process in InputMethodService.onDestroy.
        if (this::scenario.isInitialized) {
            device.pressBack() // hides the keyboard
            scenario.close()
        }
        if (this::previousIme.isInitialized) ImeShell.restore(previousIme)
    }

    @Test
    fun typingOnTheKeyboardEntersTextIntoTheFocusedField() {
        "hi".forEach { tapKey(it) }
        val typed = readFieldText()
        assertEquals("hi", typed.lowercase())
    }

    @Test
    fun longPressingTheReturnKeyOpensTheActionPopup() {
        val before = readFieldText()
        val enter = keyCenter(Constants.CODE_ENTER)
        assertNotNull("no Return key on the shown keyboard", enter)
        // Guard against a mis-measured position: the neighbouring period key also opens a popup, so
        // hitting it would otherwise satisfy the assertion below for the wrong reason.
        assertEquals(
            "the injected point is not on the Return key (${describeKeyUnder(enter!!)})",
            Constants.CODE_ENTER, keyCodeUnder(enter)
        )

        val downTime = SystemClock.uptimeMillis()
        injectMotion(MotionEvent.ACTION_DOWN, enter, downTime)
        // A long press is a DOWN held past the timeout and then released. A swipe of zero length
        // collapses to a single event pair with no delay between them and never opens the popup.
        val opened = waitFor(TIMEOUT_MS) { onMainThread { keyboardView()?.isShowingPopupKeysPanel == true } }
        val diagnostics = describeKeyUnder(enter)
        // Finish the gesture by sliding off the panel and releasing there, the way a user backs out
        // of a popup. Releasing on the spot would pick whichever popup key sits under the finger.
        val offPanel = Point(keyboardBounds()[0] + 10, enter.y)
        injectMotion(MotionEvent.ACTION_MOVE, offPanel, downTime)
        injectMotion(MotionEvent.ACTION_UP, offPanel, downTime)
        val dismissed = waitFor(TIMEOUT_MS) {
            onMainThread { keyboardView()?.isShowingPopupKeysPanel != true }
        }
        device.waitForIdle()

        assertTrue("long-pressing Return did not open the action popup ($diagnostics)", opened)
        assertTrue("the popup stayed open after the finger left it", dismissed)
        assertEquals("backing out of the popup changed the text ($diagnostics)", before, readFieldText())
    }

    // --- helpers ---------------------------------------------------------

    private fun keyboardView(): MainKeyboardView? = KeyboardSwitcher.getInstance().mainKeyboardView

    private fun <T> onMainThread(block: () -> T): T {
        var result: T? = null
        instrumentation.runOnMainSync { result = block() }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    private fun readFieldText(): String = onMainThread { editText.text.toString() }

    private fun awaitKeyboardShown(): Boolean = waitFor(TIMEOUT_MS) {
        onMainThread {
            val view = keyboardView()
            view != null && view.isShown && view.keyboard != null
        }
    }

    private fun awaitKeyboardSettled() {
        // isShown turns true while the keyboard is still sliding in, and a touch injected at a
        // position read during the animation lands on a neighbouring key. Wait for the geometry to
        // stop moving instead.
        var previous = keyboardBounds()
        val settled = waitFor(TIMEOUT_MS) {
            SystemClock.sleep(200)
            val current = keyboardBounds()
            (current == previous).also { previous = current }
        }
        assertTrue("the keyboard never stopped moving", settled)
        device.waitForIdle()
    }

    /** Screen position and size of the keyboard view, used to tell "settled" from "animating". */
    private fun keyboardBounds(): List<Int> = onMainThread {
        val view = keyboardView() ?: return@onMainThread emptyList()
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        listOf(location[0], location[1], view.width, view.height)
    }

    private fun waitFor(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return true
            SystemClock.sleep(100)
        }
        return false
    }

    /** Screen coordinates of the centre of the key with this code, or null if it is not shown. */
    private fun keyCodeUnder(point: Point): Int? = onMainThread {
        val view = keyboardView() ?: return@onMainThread null
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        view.keyboard?.sortedKeys?.firstOrNull {
            it.isOnKey(point.x - location[0] - view.paddingLeft, point.y - location[1] - view.paddingTop)
        }?.code
    }

    /** Which key the injected point actually lands on, for failure messages. */
    private fun describeKeyUnder(point: Point): String = onMainThread {
        val view = keyboardView() ?: return@onMainThread "no keyboard view"
        val keyboard = view.keyboard ?: return@onMainThread "no keyboard"
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        val key = keyboard.sortedKeys.firstOrNull {
            it.isOnKey(point.x - location[0] - view.paddingLeft, point.y - location[1] - view.paddingTop)
        }
        "point=$point hits code=${key?.code} popupKeys=${key?.popupKeys?.size} " +
            "panel=${view.isShowingPopupKeysPanel}"
    }

    private fun keyCenter(code: Int): Point? = onMainThread {
        val view = keyboardView() ?: return@onMainThread null
        val keyboard = view.keyboard ?: return@onMainThread null
        val key = keyboard.getKey(code) ?: return@onMainThread null
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        // KeyboardView draws each key at key.x/key.y offset by its own padding, so the same offset
        // turns a key position back into a screen position.
        Point(
            location[0] + view.paddingLeft + key.x + key.width / 2,
            location[1] + view.paddingTop + key.y + key.height / 2
        )
    }

    private fun tapKey(character: Char) {
        val keyboard = onMainThread { keyboardView()?.keyboard }
        assertNotNull("no keyboard shown", keyboard)
        val point = keyCenter(character.lowercaseChar().code)
            ?: keyCenter(character.uppercaseChar().code)
        assertNotNull("no key for '$character' on the shown keyboard", point)
        val downTime = SystemClock.uptimeMillis()
        injectMotion(MotionEvent.ACTION_DOWN, point!!, downTime)
        SystemClock.sleep(50)
        injectMotion(MotionEvent.ACTION_UP, point, downTime)
        device.waitForIdle()
    }

    private fun injectMotion(action: Int, point: Point, downTime: Long = SystemClock.uptimeMillis()) {
        val now = SystemClock.uptimeMillis()
        val event = MotionEvent.obtain(downTime, now, action, point.x.toFloat(), point.y.toFloat(), 0)
        event.source = InputDevice.SOURCE_TOUCHSCREEN
        instrumentation.uiAutomation.injectInputEvent(event, true)
        event.recycle()
    }

    private companion object {
        const val TIMEOUT_MS = 10_000L
        const val IME_HINT = "the debug IME selected? `adb install -r` silently resets it"
    }
}
