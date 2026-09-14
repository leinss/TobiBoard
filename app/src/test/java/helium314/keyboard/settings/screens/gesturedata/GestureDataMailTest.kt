// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens.gesturedata

import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.App
import helium314.keyboard.latin.R
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins the obfuscated [R.string.gesture_data_mail] resource to the configured privacy address.
 * The address is stored Caesar-shifted (+2 per char, see Share.kt#deobfuscateEmail) to keep it out
 * of plaintext APK scrapes, which makes it easy to mistype and silently misroute users' shared
 * gesture data. This guards the repoint to inquiry@leinss.xyz.
 */
@RunWith(RobolectricTestRunner::class)
class GestureDataMailTest {

    private val context get() = ApplicationProvider.getApplicationContext<App>()

    /** Mirrors Share.kt#deobfuscateEmail (kept in sync deliberately, not shared, so a change there is caught). */
    private fun deobfuscate(obfuscated: String): String =
        obfuscated.map { Char(it.code - 2) }.joinToString(separator = "")

    @Test
    fun gestureDataMailDecodesToTheConfiguredPrivacyAddress() {
        val decoded = deobfuscate(context.getString(R.string.gesture_data_mail))
        assertEquals("inquiry@leinss.xyz", decoded)
    }
}
