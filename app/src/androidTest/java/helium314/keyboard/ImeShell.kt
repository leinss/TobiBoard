// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard

import android.app.UiAutomation
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import java.io.FileInputStream

/**
 * Enables and selects this build's IME from inside a test.
 *
 * `adb install -r` resets `default_input_method` to the system default without saying so, so a test
 * that assumes a `make ime-enable` from before the install runs against the wrong keyboard and
 * fails somewhere far from the cause. Every test that needs the IME therefore sets it itself and
 * puts the previous one back afterwards.
 */
object ImeShell {

    /** The debug build's IME, matching IME_COMPONENT in the Makefile. */
    val component: String
        get() = InstrumentationRegistry.getInstrumentation().targetContext.packageName +
                "/helium314.keyboard.latin.LatinIME"

    private val uiAutomation: UiAutomation
        get() = InstrumentationRegistry.getInstrumentation().uiAutomation

    fun shell(command: String): String {
        val fd = uiAutomation.executeShellCommand(command)
        return FileInputStream(fd.fileDescriptor).use { it.readBytes().toString(Charsets.UTF_8) }
    }

    fun currentIme(): String = shell("settings get secure default_input_method").trim()

    /** @return the IME that was selected before, to hand back to [restore]. */
    fun enableAndSelect(): String {
        val previous = currentIme()
        shell("ime enable $component")
        shell("ime set $component")
        // `ime set` returns before the system has switched, and everything after this reads the
        // setting back, so wait for the value rather than for a fixed delay.
        val deadline = SystemClock.uptimeMillis() + 5_000
        while (SystemClock.uptimeMillis() < deadline && currentIme() != component) {
            SystemClock.sleep(100)
        }
        return previous
    }

    fun restore(previous: String) {
        if (previous.isBlank() || previous == "null" || previous == component) return
        shell("ime set $previous")
    }
}
