// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.database

import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.App
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Regression test for the fresh-install DB-creation crash: onCreate used to re-ALTER columns that
 * CREATE_TABLE already defines ("duplicate column name: USE_COUNT"), so ClipboardDao.getInstance
 * caught the SQLiteException and returned null — clipboard history silently never worked on new
 * installs. Also exercises the clip write/read round-trip (plaintext path; the AndroidKeyStore
 * cipher is unavailable under Robolectric, so this verifies the graceful fallback).
 */
@RunWith(RobolectricTestRunner::class)
class ClipboardDaoTest {

    private val context get() = ApplicationProvider.getApplicationContext<App>()

    @Test
    fun rowsThatNoLongerDecryptAreCountedAndDeletedRatherThanSilentlySkipped() {
        val dao = ClipboardDao.getInstance(context)
        assertNotNull(dao)
        dao.clear()
        dao.addClip(2_000L, false, "readable clip")
        // A row flagged encrypted whose ciphertext is nonsense: what a lost or rotated Keystore key
        // leaves behind. It used to be skipped on every load, so the entry vanished from history
        // with no explanation and the dead row stayed in the database for good.
        val cv = android.content.ContentValues().apply {
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
    fun freshDatabaseCreatesAndRoundTripsAClip() {
        val dao = ClipboardDao.getInstance(context)
        assertNotNull(dao, "fresh-install DB creation failed (onCreate)")
        dao.clear()
        dao.addClip(1_000L, false, "hello clipboard")
        assertEquals(1, dao.count())
        assertEquals("hello clipboard", dao.getAt(0).text)
    }
}
