// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.database

import android.content.ContentValues
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.App
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Regression test for the fresh-install DB-creation crash: onCreate used to re-ALTER columns that
 * CREATE_TABLE already defines ("duplicate column name: USE_COUNT"), so ClipboardDao.getInstance
 * caught the SQLiteException and returned null — clipboard history silently never worked on new
 * installs. Also covers what each keystore state does to a write.
 *
 * The cipher is injected: Robolectric has no AndroidKeyStore, so the production one reports a
 * present-but-broken keystore and every clip would be refused, which is one of the three cases
 * under test rather than the only one reachable.
 */
@RunWith(RobolectricTestRunner::class)
class ClipboardDaoTest {

    private val context get() = ApplicationProvider.getApplicationContext<App>()

    /** A key exists and works. Not real crypto: reversible, so the test can assert both ways. */
    private object WorkingCipher : ClipboardEncryption {
        override val keystoreSupported = true
        override fun isAvailable() = true
        override fun encrypt(plaintext: String) = PREFIX + plaintext.reversed()
        override fun decrypt(encoded: String) =
            if (encoded.startsWith(PREFIX)) encoded.removePrefix(PREFIX).reversed() else null

        private const val PREFIX = "enc:"
    }

    /** Below API 23: no AES keys in the platform keystore at all. */
    private object NoKeystoreCipher : ClipboardEncryption {
        override val keystoreSupported = false
        override fun isAvailable() = false
        override fun encrypt(plaintext: String): String? = null
        override fun decrypt(encoded: String): String? = null
    }

    /** API 23+ with a keystore that cannot produce a key: a broken device, not a platform limit. */
    private object BrokenKeystoreCipher : ClipboardEncryption {
        override val keystoreSupported = true
        override fun isAvailable() = false
        override fun encrypt(plaintext: String): String? = null
        override fun decrypt(encoded: String): String? = null
    }

    /**
     * A DAO with an empty table behind it. The table is emptied through SQL rather than
     * [ClipboardDao.clear], which is a no-op when the previous test's rows do not decrypt under
     * this test's cipher and so never reach the cache it counts.
     */
    private fun daoWith(cipher: ClipboardEncryption): ClipboardDao {
        Database.getInstance(context).writableDatabase.delete("CLIPBOARD", null, null)
        return ClipboardDao.createForTest(context, cipher)
    }

    private fun rawTextColumn(): String? =
        Database.getInstance(context).readableDatabase
            .query("CLIPBOARD", arrayOf("TEXT"), null, null, null, null, null)
            .use { if (it.moveToFirst()) it.getString(0) else null }

    @Test
    fun rowsThatNoLongerDecryptAreCountedAndDeletedRatherThanSilentlySkipped() {
        val dao = daoWith(WorkingCipher)
        dao.addClip(2_000L, false, "readable clip")
        // A row flagged encrypted whose ciphertext is nonsense: what a lost or rotated Keystore key
        // leaves behind. It used to be skipped on every load, so the entry vanished from history
        // with no explanation and the dead row stayed in the database for good.
        val cv = ContentValues().apply {
            put("TIMESTAMP", 3_000L)
            put("PINNED", 0)
            put("TEXT", "not-actually-ciphertext")
            put("USE_COUNT", 0)
            put("ENCRYPTED", 1)
        }
        val db = Database.getInstance(context).writableDatabase
        assertTrue(db.insert("CLIPBOARD", null, cv) > 0)

        dao.invalidateCache()
        assertEquals(1, dao.count(), "the readable clip should still be there")
        assertEquals("readable clip", dao.getAt(0).text)
        assertEquals(1, dao.undecryptableCount, "the unreadable row should be reported")

        dao.invalidateCache()
        assertEquals(0, dao.undecryptableCount, "the unreadable row should have been deleted")
    }

    @Test
    fun freshDatabaseIsCreatedRatherThanCrashingOnDuplicateColumns() {
        val dao = ClipboardDao.getInstance(context)
        assertNotNull(dao, "fresh-install DB creation failed (onCreate)")
    }

    @Test
    fun aWorkingKeyStoresCiphertextAndReadsItBack() {
        val dao = daoWith(WorkingCipher)
        dao.addClip(1_000L, false, "hello clipboard")

        assertEquals(1, dao.count())
        assertNotEquals("hello clipboard", rawTextColumn(), "the clip must not be readable on disk")

        dao.invalidateCache()
        assertEquals("hello clipboard", dao.getAt(0).text)
    }

    @Test
    fun withNoKeystoreAtAllTheClipIsStoredInPlaintext() {
        // The documented degradation below API 23: there is no key to fail with, and refusing here
        // would mean the clipboard feature does not exist on those devices at all.
        val dao = daoWith(NoKeystoreCipher)
        dao.addClip(1_000L, false, "hello clipboard")

        assertEquals(1, dao.count())
        assertEquals("hello clipboard", rawTextColumn())
    }

    @Test
    fun aBrokenKeystoreDropsTheClipInsteadOfStoringItReadable() {
        // Same outward symptom as the case above, opposite cause: the platform can hold a key and
        // failed to produce one. Storing readable text here would contradict what the app claims.
        val dao = daoWith(BrokenKeystoreCipher)
        dao.addClip(1_000L, false, "hello clipboard")

        assertEquals(0, dao.count())
        assertEquals(null, rawTextColumn(), "nothing should have reached the database")
    }
}
