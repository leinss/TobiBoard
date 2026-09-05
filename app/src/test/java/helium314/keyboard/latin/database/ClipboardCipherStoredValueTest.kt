// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.database

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fail-closed rule for clipboard rows: an encryption failure on a device that can encrypt drops the
 * clip. It used to be stored in the clear under an ENCRYPTED = 0 flag, contradicting what the app
 * tells users.
 */
class ClipboardCipherStoredValueTest {
    @Test
    fun encryptedValueIsStoredAndFlagged() {
        val stored = ClipboardCipher.storedValue(encryptionExpected = true, plaintext = "secret", ciphertext = "cipher")
        assertEquals("cipher", stored?.value)
        assertTrue(stored!!.encrypted)
    }

    @Test
    fun encryptFailureDropsTheClip() {
        assertNull(ClipboardCipher.storedValue(encryptionExpected = true, plaintext = "secret", ciphertext = null))
    }

    @Test
    fun encryptFailureNeverFallsBackToThePlaintext() {
        val stored = ClipboardCipher.storedValue(encryptionExpected = true, plaintext = "secret", ciphertext = null)
        assertNull(stored)
    }

    @Test
    fun deviceWithoutAKeystoreStoresPlaintext() {
        // Below API 23 there is no AES key to fail with; plaintext is the documented degradation.
        val stored = ClipboardCipher.storedValue(encryptionExpected = false, plaintext = "note", ciphertext = null)
        assertEquals("note", stored?.value)
        assertEquals(false, stored?.encrypted)
    }
}
