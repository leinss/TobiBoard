// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.database

import android.util.Base64
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

/**
 * Pins the AES-GCM clipboard codec. Uses a software key (Robolectric has no AndroidKeyStore); the
 * production [ClipboardCipher] supplies a Keystore-backed key but the encrypt/decrypt logic is the
 * same [GcmCodec] code path tested here.
 */
@RunWith(RobolectricTestRunner::class)
class GcmCodecTest {

    private fun softwareKey(): SecretKey =
        KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    @Test
    fun roundTripsIncludingUnicode() {
        val key = softwareKey()
        val plain = "secret clip 😀 — symbols & \"quotes\"\nnewline"
        assertEquals(plain, GcmCodec.decrypt(key, GcmCodec.encrypt(key, plain)))
    }

    @Test
    fun sameInputProducesDifferentCiphertext() {
        // Random per-call IV → identical plaintext must not yield identical ciphertext.
        val key = softwareKey()
        assertNotEquals(GcmCodec.encrypt(key, "repeat"), GcmCodec.encrypt(key, "repeat"))
    }

    @Test
    fun tamperedCiphertextIsRejected() {
        val key = softwareKey()
        val raw = Base64.decode(GcmCodec.encrypt(key, "important"), Base64.NO_WRAP)
        raw[raw.size - 1] = (raw[raw.size - 1] + 1).toByte() // corrupt the GCM authentication tag
        assertFailsWith<Exception> { GcmCodec.decrypt(key, Base64.encodeToString(raw, Base64.NO_WRAP)) }
    }

    @Test
    fun wrongKeyIsRejected() {
        val encoded = GcmCodec.encrypt(softwareKey(), "data")
        assertFailsWith<Exception> { GcmCodec.decrypt(softwareKey(), encoded) }
    }
}
