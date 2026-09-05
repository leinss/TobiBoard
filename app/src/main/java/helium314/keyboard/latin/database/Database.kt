// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.database

import android.content.Context
import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.core.database.sqlite.transaction
import helium314.keyboard.latin.utils.GestureDataDao
import helium314.keyboard.latin.utils.Log
import java.io.File

class Database private constructor(
    private val appContext: Context,
    name: String = NAME,
) : SQLiteOpenHelper(appContext, name, null, VERSION) {

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        // Overwrite deleted rows with zeros instead of leaving them in free pages. Without it a
        // deleted clip stays readable in the database file until SQLite happens to reuse the page,
        // which is exactly the offline extraction the clipboard encryption exists to stop.
        // A PRAGMA that returns a value has to go through rawQuery: execSQL rejects it with
        // "Queries can be performed using SQLiteDatabase query or rawQuery methods only".
        db.rawQuery("PRAGMA secure_delete = ON", null).use { it.moveToFirst() }
    }

    override fun onCreate(db: SQLiteDatabase) {
        // Create the full current schema directly. Previously this ran onUpgrade(db, 0, VERSION),
        // which re-ALTERed columns CREATE_TABLE already defines ("duplicate column name: USE_COUNT"),
        // so a fresh install crashed DB creation and clipboard history silently never worked.
        db.execSQL(ClipboardDao.CREATE_TABLE)
        db.execSQL(GestureDataDao.CREATE_TABLE)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion <= 1) {
            db.execSQL(GestureDataDao.CREATE_TABLE)
        }
        if (oldVersion <= 2) {
            db.execSQL("ALTER TABLE CLIPBOARD ADD COLUMN USE_COUNT INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE CLIPBOARD ADD COLUMN ANNOTATION TEXT")
        }
        if (oldVersion <= 3) {
            db.execSQL("ALTER TABLE CLIPBOARD ADD COLUMN ENCRYPTED INTEGER NOT NULL DEFAULT 0")
            ClipboardDao.encryptExistingRows(db)
            // Encrypting in place leaves the plaintext in free pages; only rewriting the file
            // removes it. This runs in a transaction, and VACUUM cannot, so it is deferred to the
            // next background cache load. See ClipboardDao.requestVacuum.
            ClipboardDao.requestVacuum(appContext)
        }
    }

    companion object {
        private val TAG = Database::class.java.simpleName
        private const val VERSION = 4
        const val NAME = "heliboard.db"
        private var instance: Database? = null
        @Synchronized
        fun getInstance(context: Context): Database {
            if (instance == null)
                instance = Database(context.applicationContext)
            return instance!!
        }

        // needs to be in sync with db version
        fun copyFromDb(file: File, context: Context) {
            if (!file.exists())
                return
            val otherDb = Database(context.applicationContext, file.name) // this upgrades the DB if necessary
            val clipDao = ClipboardDao.getInstance(context) // insert to dao because of cache
            if (clipDao == null) {
                Log.e(TAG, "can't transfer clipboard data because ClipboardDao is null")
            } else {
                otherDb.readableDatabase.rawQuery("SELECT TIMESTAMP, PINNED, TEXT FROM CLIPBOARD", null)
                    .use {
                        clipDao.clear()
                        while (it.moveToNext())
                            clipDao.addClip(it.getLong(0), it.getInt(1) != 0, it.getString(2))
                    }
            }
            val db = getInstance(context)
            otherDb.readableDatabase.rawQuery("SELECT TIMESTAMP, WORD, EXPORTED, SOURCE_ACTIVE, DATA FROM GESTURE_DATA", null)
                .use { c ->
                    db.writableDatabase.transaction {
                        // Wipe inside the transaction so a failed insert rolls back the delete too,
                        // instead of leaving the destination emptied with no replacement data.
                        execSQL("DELETE FROM GESTURE_DATA")
                        while (c.moveToNext()) {
                            val cv = ContentValues(5)
                            cv.put("TIMESTAMP", c.getLong(0))
                            cv.put("WORD", c.getString(1))
                            cv.put("EXPORTED", c.getInt(2))
                            cv.put("SOURCE_ACTIVE", c.getInt(3))
                            cv.put("DATA", c.getString(4))
                            // throw on failure so the transaction rolls back instead of silently dropping rows
                            insertOrThrow("GESTURE_DATA", null, cv)
                        }
                    }
                }
            otherDb.close()
            file.delete()
        }
    }
}
