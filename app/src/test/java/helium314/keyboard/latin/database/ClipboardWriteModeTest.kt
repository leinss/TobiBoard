// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.database

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * One boolean used to answer two different questions, and both answers were "store it in the
 * clear": a platform with no keystore at all (below API 23, where that is the documented
 * degradation) and a device whose keystore is present but cannot produce a key (where plaintext
 * silently contradicts what the app tells the user). Pure, no Robolectric.
 */
class ClipboardWriteModeTest {

    @Test
    fun aUsableKeyMeansClipsAreEncrypted() {
        assertEquals(
            ClipboardWriteMode.ENCRYPT,
            ClipboardCipher.writeMode(keystoreSupported = true, keyAvailable = true),
        )
    }

    @Test
    fun noKeystoreAtAllStoresPlaintext() {
        // Below API 23 there is no AES key to fail with; refusing here would mean the clipboard
        // feature simply does not exist on those devices.
        assertEquals(
            ClipboardWriteMode.PLAINTEXT,
            ClipboardCipher.writeMode(keystoreSupported = false, keyAvailable = false),
        )
    }

    @Test
    fun aKeystoreThatCannotProduceAKeyRefusesTheClip() {
        assertEquals(
            ClipboardWriteMode.REFUSE,
            ClipboardCipher.writeMode(keystoreSupported = true, keyAvailable = false),
        )
    }

    @Test
    fun aKeyThatExistsWinsOverAnUnsupportedPlatformReading() {
        // Contradictory inputs cannot happen on a real device; if they ever did, having a key is
        // the fact that matters, and encrypting is the safe half of the contradiction.
        assertEquals(
            ClipboardWriteMode.ENCRYPT,
            ClipboardCipher.writeMode(keystoreSupported = false, keyAvailable = true),
        )
    }
}
