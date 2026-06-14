// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.database

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import helium314.keyboard.latin.utils.Log
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * Encrypts clipboard-history text and annotations at rest with an AES-256-GCM key held in the
 * AndroidKeyStore (hardware-backed where the device supports it). The key is app-bound and does NOT
 * require device-unlock, so the clipboard keeps working while you type.
 *
 * Threat model: protects against offline extraction of the SQLite database (forensic dump, ADB/root
 * file access). A live attacker on an already-unlocked device can read the running keyboard's
 * clipboard regardless — out of scope, and unavoidable for an input method.
 *
 * Unavailable below API 23 (AndroidKeyStore AES keys need it) or if the keystore is transiently
 * unhappy; callers then fall back to plaintext and an `ENCRYPTED = 0` row flag, so the feature
 * degrades gracefully instead of losing clipboard data.
 */
internal object ClipboardCipher {
    private const val TAG = "ClipboardCipher"
    private const val KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "tobiboard_clipboard_v1"

    /** True when new clips can be encrypted. Cheap after the first call (key is cached in the store). */
    fun isAvailable(): Boolean = key() != null

    /** Returns Base64(iv‖ciphertext) or null if encryption is unavailable / failed (caller stores plaintext). */
    fun encrypt(plaintext: String): String? {
        val k = key() ?: return null
        return try {
            GcmCodec.encrypt(k, plaintext)
        } catch (e: Exception) {
            Log.e(TAG, "encrypt failed", e)
            null
        }
    }

    /** Returns the plaintext or null if the value can't be decrypted (key lost / corrupt row). */
    fun decrypt(encoded: String): String? {
        val k = key() ?: return null
        return try {
            GcmCodec.decrypt(k, encoded)
        } catch (e: Exception) {
            Log.e(TAG, "decrypt failed", e)
            null
        }
    }

    private fun key(): SecretKey? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return null
        return try {
            val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
            (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey ?: generateKey()
        } catch (e: Exception) {
            Log.e(TAG, "keystore unavailable", e)
            null
        }
    }

    private fun generateKey(): SecretKey? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return null
        return try {
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
            generator.init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
            )
            generator.generateKey()
        } catch (e: Exception) {
            Log.e(TAG, "key generation failed", e)
            null
        }
    }
}
