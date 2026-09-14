// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.database

import android.util.Base64
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AES-256-GCM string codec. Output is Base64(`iv ‖ ciphertext+tag`) with a fresh random IV per call,
 * so encrypting the same plaintext twice yields different ciphertext and GCM detects tampering on
 * decrypt.
 *
 * Deliberately pure: the [SecretKey] is supplied by the caller, so this is unit-testable on the JVM
 * with a software key, while [ClipboardCipher] supplies an AndroidKeyStore-backed key in production.
 * `android.util.Base64` is used (not `java.util.Base64`) because the latter requires API 26 > minSdk.
 */
internal object GcmCodec {
    private const val IV_BYTES = 12        // 96-bit nonce: the GCM-recommended size
    private const val TAG_BITS = 128
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private val random = SecureRandom()

    fun encrypt(key: SecretKey, plaintext: String): String {
        val iv = ByteArray(IV_BYTES).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        }
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(iv + ciphertext, Base64.NO_WRAP)
    }

    fun decrypt(key: SecretKey, encoded: String): String {
        val bytes = Base64.decode(encoded, Base64.NO_WRAP)
        require(bytes.size > IV_BYTES) { "ciphertext too short" }
        val iv = bytes.copyOfRange(0, IV_BYTES)
        val ciphertext = bytes.copyOfRange(IV_BYTES, bytes.size)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        }
        return String(cipher.doFinal(ciphertext), Charsets.UTF_8)
    }
}
