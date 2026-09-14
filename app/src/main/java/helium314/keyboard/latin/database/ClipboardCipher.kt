// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.database

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import helium314.keyboard.latin.utils.Log
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/** What [ClipboardCipher.writeMode] decided this device does with new clips. */
internal enum class ClipboardWriteMode {
    /** A key exists: clips are stored as ciphertext, and a failure to encrypt drops the clip. */
    ENCRYPT,

    /** No keystore at all (below API 23): plaintext is the documented degradation. */
    PLAINTEXT,

    /** A keystore that cannot produce a key: the clip is dropped rather than stored readable. */
    REFUSE,
}

/**
 * The cipher as [ClipboardDao] sees it. A seam, not an abstraction: Robolectric has no
 * AndroidKeyStore, so a DAO test would otherwise exercise the REFUSE path for every clip and
 * could never reach the encrypted one at all.
 */
internal interface ClipboardEncryption {
    /** True when the platform can hold an AES key in its keystore, whether or not one exists. */
    val keystoreSupported: Boolean

    /** True when a usable key exists right now. */
    fun isAvailable(): Boolean

    fun encrypt(plaintext: String): String?

    fun decrypt(encoded: String): String?
}

/**
 * Encrypts clipboard-history text and annotations at rest with an AES-256-GCM key held in the
 * AndroidKeyStore (hardware-backed where the device supports it). The key is app-bound and does NOT
 * require device-unlock, so the clipboard keeps working while you type.
 *
 * Threat model: protects against offline extraction of the SQLite database (forensic dump, ADB/root
 * file access). A live attacker on an already-unlocked device can read the running keyboard's
 * clipboard regardless — out of scope, and unavoidable for an input method.
 *
 * Two different things used to be one boolean. Below API 23 AndroidKeyStore has no AES keys at
 * all, so plaintext is the documented degradation and there is no failure to report; on API 23+ a
 * keystore that cannot produce a key is broken, and storing the clip in the clear there would
 * quietly contradict what the app tells users. [writeMode] splits them: only the first stores
 * plaintext, the second refuses the clip. Either way [ClipboardDao] tells the user once rather than
 * degrading in silence, and once a key exists an encryption *failure* drops the clip rather than
 * writing it in the clear — see [storedValue].
 */
internal object ClipboardCipher : ClipboardEncryption {
    private const val TAG = "ClipboardCipher"
    private const val KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "tobiboard_clipboard_v1"

    /** What a clipboard row is allowed to hold. See [storedValue]. */
    internal data class Stored(val value: String, val encrypted: Boolean)

    /**
     * How this device stores new clips, from the two independent facts about its keystore.
     *
     * The two false cases are not the same failure. No keystore at all is a platform limit nobody
     * can fix, and refusing the clipboard on it would mean the feature simply does not exist below
     * API 23; a keystore that is present but cannot produce a key is a broken device, and there
     * plaintext would contradict the encryption the app claims.
     *
     * Pure, so it is unit-tested; see ClipboardWriteModeTest.
     */
    @JvmStatic
    internal fun writeMode(keystoreSupported: Boolean, keyAvailable: Boolean): ClipboardWriteMode = when {
        keyAvailable -> ClipboardWriteMode.ENCRYPT
        !keystoreSupported -> ClipboardWriteMode.PLAINTEXT
        else -> ClipboardWriteMode.REFUSE
    }

    /**
     * Decides what to write for one clipboard field, or null for "write nothing, drop the clip".
     *
     * The app tells users the clipboard history is encrypted, so a keystore that is present but
     * fails must not quietly downgrade to plaintext: that produced a row flagged `ENCRYPTED = 0`
     * holding readable text, with nothing shown to the user. Losing a clip is the intended trade.
     *
     * @param encryptionExpected whether this device produced a key at all ([isAvailable]). When it
     *                           is false there is no key to fail with, so plaintext is the
     *                           degradation rather than a failure, and [ClipboardDao] warns the user
     *                           once. It is false below API 23 and on a device whose keystore cannot
     *                           produce a key.
     * @param plaintext          the text the user copied.
     * @param ciphertext         what [encrypt] returned, null if it failed.
     *
     * Pure, so it is unit-tested without a keystore; see ClipboardCipherStoredValueTest.
     */
    @JvmStatic
    internal fun storedValue(encryptionExpected: Boolean, plaintext: String, ciphertext: String?): Stored? = when {
        !encryptionExpected -> Stored(plaintext, encrypted = false)
        ciphertext != null -> Stored(ciphertext, encrypted = true)
        else -> null
    }

    /** True when new clips can be encrypted. Cheap after the first call (key is cached in the store). */
    override fun isAvailable(): Boolean = key() != null

    /** AES keys in AndroidKeyStore start at API 23; minSdk is 21. */
    override val keystoreSupported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M

    /** Returns Base64(iv‖ciphertext) or null if encryption is unavailable / failed (caller stores plaintext). */
    override fun encrypt(plaintext: String): String? {
        val k = key() ?: return null
        return try {
            GcmCodec.encrypt(k, plaintext)
        } catch (e: Exception) {
            Log.e(TAG, "encrypt failed", e)
            null
        }
    }

    /** Returns the plaintext or null if the value can't be decrypted (key lost / corrupt row). */
    override fun decrypt(encoded: String): String? {
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
