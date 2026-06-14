// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.database

import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.App
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

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
    fun freshDatabaseCreatesAndRoundTripsAClip() {
        val dao = ClipboardDao.getInstance(context)
        assertNotNull(dao, "fresh-install DB creation failed (onCreate)")
        dao.clear()
        dao.addClip(1_000L, false, "hello clipboard")
        assertEquals(1, dao.count())
        assertEquals("hello clipboard", dao.getAt(0).text)
    }
}
