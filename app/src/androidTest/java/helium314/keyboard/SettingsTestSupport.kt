// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard

import android.content.Context
import android.view.inputmethod.EditorInfo
import androidx.test.platform.app.InstrumentationRegistry
import helium314.keyboard.latin.InputAttributes
import helium314.keyboard.compat.locale
import helium314.keyboard.latin.settings.Settings

/** The app context every instrumentation test runs against. */
val testContext: Context
    get() = InstrumentationRegistry.getInstrumentation().targetContext

/**
 * Populates `Settings.getValues()` the way `SettingsActivity.onCreate` does.
 *
 * Every Compose screen under test reads it, directly or through a screen it can open, and a test
 * that skips this crashes on the first read rather than failing with a useful message.
 */
fun initSettingsForTest(context: Context = testContext) {
    Settings.init(context)
    if (Settings.getValues() != null) return
    val inputAttributes = InputAttributes(EditorInfo(), false, context.packageName)
    Settings.getInstance().loadSettings(context, context.resources.configuration.locale(), inputAttributes)
}
