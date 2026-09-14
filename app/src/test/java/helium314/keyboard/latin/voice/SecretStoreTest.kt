// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.App
import helium314.keyboard.latin.utils.prefs
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

/**
 * Where an API key is allowed to live. The rules that matter are the one-way migration out of the
 * plaintext preferences file and the refusal to fall back to it: an encrypted store that cannot be
 * opened must read as "no key", never as "here is the readable copy".
 *
 * The two preference files are injected, because Robolectric has no AndroidKeyStore and the real
 * encrypted store is therefore unavailable in every case, which is one of the cases under test
 * rather than the only one reachable.
 */
@RunWith(RobolectricTestRunner::class)
class SecretStoreTest {

    private val context get() = ApplicationProvider.getApplicationContext<App>()
    private lateinit var secure: SharedPreferences
    private lateinit var plain: SharedPreferences

    private val prefKey = "openrouter_api_key"

    @BeforeTest fun clearFiles() {
        secure = context.getSharedPreferences("test_secure", Context.MODE_PRIVATE)
        plain = context.getSharedPreferences("test_plain", Context.MODE_PRIVATE)
        secure.edit().clear().commit()
        plain.edit().clear().commit()
    }

    @Test
    fun aKeyIsWrittenToTheSecureFileAndReadBackFromIt() {
        SecretStore.writeApiKey(secure, plain, prefKey, "not-a-real-key-secret")
        assertEquals("not-a-real-key-secret", secure.getString(prefKey, null))
        assertEquals("not-a-real-key-secret", SecretStore.readApiKey(secure, plain, prefKey, ""))
    }

    @Test
    fun writingAKeyScrubsAnyPlaintextCopyOfIt() {
        plain.edit().putString(prefKey, "not-a-real-key-old").commit()
        SecretStore.writeApiKey(secure, plain, prefKey, "not-a-real-key-new")
        assertNull(plain.getString(prefKey, null), "the plaintext copy must not survive a write")
    }

    @Test
    fun aPlaintextKeyFromAnOlderVersionIsMovedIntoTheSecureFileOnFirstRead() {
        plain.edit().putString(prefKey, "not-a-real-key-legacy").commit()

        assertEquals("not-a-real-key-legacy", SecretStore.readApiKey(secure, plain, prefKey, ""))
        assertEquals("not-a-real-key-legacy", secure.getString(prefKey, null))
        assertNull(plain.getString(prefKey, null), "the plaintext original must be scrubbed")
    }

    @Test
    fun theSecureValueWinsOverALeftoverPlaintextOne() {
        // A stale plaintext copy is only scrubbed by the migration or by the next write; the read
        // never prefers it.
        secure.edit().putString(prefKey, "not-a-real-key-current").commit()
        plain.edit().putString(prefKey, "not-a-real-key-stale").commit()

        assertEquals("not-a-real-key-current", SecretStore.readApiKey(secure, plain, prefKey, ""))
    }

    @Test
    fun aBlankPlaintextValueIsNotMigrated() {
        plain.edit().putString(prefKey, "   ").commit()

        assertEquals("fallback", SecretStore.readApiKey(secure, plain, prefKey, "fallback"))
        assertNull(secure.getString(prefKey, null), "whitespace is not a key worth keeping")
    }

    @Test
    fun anUnsetKeyReadsAsTheDefault() {
        assertEquals("fallback", SecretStore.readApiKey(secure, plain, prefKey, "fallback"))
    }

    @Test
    fun withoutSecureStorageTheReadIgnoresThePlaintextValueEntirely() {
        // Falling back to the readable copy would silently undo the migration: the key would work
        // again, and stay on disk in the clear, with nothing telling the user.
        plain.edit().putString(prefKey, "not-a-real-key-legacy").commit()

        assertEquals("", SecretStore.readApiKey(secure = null, plain = plain, prefKey = prefKey, default = ""))
        assertEquals("not-a-real-key-legacy", plain.getString(prefKey, null), "nothing was migrated, so nothing is scrubbed")
    }

    @Test
    fun withoutSecureStorageTheWriteFailsLoudlyRatherThanStoringPlaintext() {
        val e = assertFailsWith<IllegalStateException> {
            SecretStore.writeApiKey(secure = null, plain = plain, prefKey = prefKey, value = "not-a-real-key-secret")
        }
        assertEquals("Secure storage unavailable", e.message)
        assertNull(plain.getString(prefKey, null))
    }

    @Test
    fun onADeviceWithoutAWorkingKeystoreNothingIsStoredAndNothingIsRead() {
        // Robolectric provides no AndroidKeyStore, so this exercises the real
        // EncryptedSharedPreferences failure path end to end.
        assertFalse(SecretStore.isSecureStorageAvailable(context))
        context.prefs().edit().putString(prefKey, "not-a-real-key-legacy").commit()

        assertEquals("", SecretStore.getApiKey(context, prefKey, ""))
        assertFailsWith<IllegalStateException> { SecretStore.setApiKey(context, prefKey, "not-a-real-key-secret") }

        context.prefs().edit().remove(prefKey).commit()
    }

    @Test
    fun clearingTheSecureStoreIsSafeWhenThereIsNothingToClear() {
        SecretStore.clearSecureStorage(context)
        SecretStore.warmUp(context)
    }
}
