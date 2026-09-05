// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.database

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.Toast
import androidx.annotation.StringRes
import helium314.keyboard.latin.ClipboardHistoryEntry
import helium314.keyboard.latin.R
import helium314.keyboard.latin.define.DebugFlags
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.Log
import java.util.concurrent.atomic.AtomicBoolean

/*
 possible extension for later: allow non-text
 setting whether to allow it at all (because it could be slow with large files)
 separate retention time setting
 add mime type column
 add file name column
 add hash column (sha 256) for quick unique check (check full content on hash conflict)
 more sophisticated content loading: some getContent that reads the file, with cache
 async file reads and writes
 caches should be dropped on low memory
 */

/** Class providing cached access to the clipboard table */
// currently we should not need to worry about synchronizing access (though maybe we could addClip in a coroutine, then it might be relevant)
class ClipboardDao private constructor(
    private val db: Database,
    private val appContext: Context,
) {
    interface Listener {
        fun onClipInserted(position: Int)
        fun onClipsRemoved(position: Int, count: Int)
        fun onClipMoved(oldPosition: Int, newPosition: Int)
    }

    var listener: Listener? = null

    // we clean up old clips when a new clip is added, but not too frequently
    private var lastClearOldClips = 0L

    // Whether new clips are written encrypted. Fixed for the DAO's lifetime; reads honor each row's
    // own ENCRYPTED flag so plaintext (legacy / API < 23) and encrypted rows can coexist.
    private val encrypting = ClipboardCipher.isAvailable()

    init {
        // A device that cannot produce a key at all stores clips in the clear. Below API 23 that is
        // expected; above it the keystore is broken. Either way the user is told once, because the
        // app claims the clipboard is encrypted and silence here is the part that misleads.
        if (!encrypting) {
            Log.w(TAG, "clipboard encryption unavailable; clips are stored in plaintext")
            warnOnce(R.string.clipboard_encryption_unavailable)
        }
    }

    // The cache is loaded lazily on first access to the [cache] accessor and never dropped.
    // The load (SQLite open + per-row AES-GCM decrypt) is the expensive part; call
    // ensureCacheLoaded() from a background thread so it does not run on the IME main thread
    // during LatinIME.onCreate() — that synchronous cost was swallowing the first keystrokes.
    @Volatile private var loadedCache: MutableList<ClipboardHistoryEntry>? = null
    private val cacheLoadLock = Any()

    private val cache: MutableList<ClipboardHistoryEntry>
        get() {
            loadedCache?.let { return it }
            synchronized(cacheLoadLock) {
                loadedCache?.let { return it }
                return readAllFromDb().also { loadedCache = it }
            }
        }

    /**
     * Forces the one-time cache load (DB query + decrypt). Idempotent and safe to call
     * concurrently; intended to be invoked off the main thread so consumers that touch the cache
     * later find it already populated instead of paying the load synchronously.
     */
    fun ensureCacheLoaded() {
        cache // touching the accessor triggers the load if it hasn't happened yet
    }

    private fun readAllFromDb(): MutableList<ClipboardHistoryEntry> {
        val started = if (DebugFlags.DEBUG_ENABLED) SystemClock.elapsedRealtime() else 0L
        val list = mutableListOf<ClipboardHistoryEntry>()
        // A SQLite open / migration / decrypt failure must degrade to an empty (or partial) cache,
        // never propagate: ensureCacheLoaded() may run on a coroutine with no exception handler, so
        // an uncaught throw here would crash the IME process.
        try {
            db.readableDatabase.query(
                TABLE,
                arrayOf(COLUMN_ID, COLUMN_TIMESTAMP, COLUMN_PINNED, COLUMN_TEXT, COLUMN_USE_COUNT, COLUMN_ANNOTATION, COLUMN_ENCRYPTED),
                null,
                null,
                null,
                null,
                "$COLUMN_PINNED, $COLUMN_TIMESTAMP DESC"
            ).use {
                while (it.moveToNext()) {
                    val isEncrypted = it.getInt(6) != 0
                    // A row flagged encrypted whose content won't decrypt (key lost / corrupt) is dropped
                    // rather than surfaced as ciphertext.
                    val text = if (isEncrypted)
                        (it.getString(3)?.let { stored -> ClipboardCipher.decrypt(stored) } ?: continue)
                    else it.getString(3)
                    val storedAnnotation = it.getString(5)
                    val annotation = if (isEncrypted && storedAnnotation != null)
                        ClipboardCipher.decrypt(storedAnnotation) else storedAnnotation
                    list.add(ClipboardHistoryEntry(
                        id = it.getLong(0),
                        timeStamp = it.getLong(1),
                        isPinned = it.getInt(2) != 0,
                        text = text,
                        useCount = it.getInt(4),
                        annotation = annotation,
                    ))
                }
            }
            list.sort()
        } catch (t: Throwable) {
            Log.e(TAG, "failed to load clipboard cache; degrading to ${list.size} entries", t)
        }
        if (DebugFlags.DEBUG_ENABLED)
            Log.d(TAG, "loaded ${list.size} clips in ${SystemClock.elapsedRealtime() - started} ms on ${Thread.currentThread().name}")
        return list
    }

    fun addClip(timestamp: Long, pinned: Boolean, text: String) {
        clearOldClips()
        val existingIndex = cache.indexOfFirst { it.text == text }
        if (existingIndex >= 0 && cache[existingIndex].timeStamp == timestamp)
            return // nothing to do
        if (existingIndex >= 0) {
            updateTimestampAt(existingIndex, timestamp)
            return
        }
        insertNewEntry(timestamp, pinned, text)
    }

    private fun insertNewEntry(timestamp: Long, pinned: Boolean, text: String) {
        // Fail closed: a device that can encrypt but fails to must not leave the clip readable on
        // disk under an ENCRYPTED = 0 flag. Drop it and say so once.
        val stored = ClipboardCipher.storedValue(
            encryptionExpected = encrypting,
            plaintext = text,
            ciphertext = if (encrypting) ClipboardCipher.encrypt(text) else null,
        )
        if (stored == null) {
            Log.e(TAG, "clip not stored: encryption failed")
            warnOnce(R.string.clipboard_encryption_failed)
            return
        }
        val cv = ContentValues(6)
        cv.put(COLUMN_TIMESTAMP, timestamp)
        cv.put(COLUMN_PINNED, pinned)
        cv.put(COLUMN_TEXT, stored.value)
        cv.put(COLUMN_ENCRYPTED, if (stored.encrypted) 1 else 0)
        cv.put(COLUMN_USE_COUNT, 0)
        cv.putNull(COLUMN_ANNOTATION)
        val rowId = db.writableDatabase.insert(TABLE, null, cv)

        val entry = ClipboardHistoryEntry(rowId, timestamp, pinned, text)
        cache.add(entry)
        cache.sort()
        listener?.onClipInserted(cache.indexOf(entry))
    }

    private fun updateTimestampAt(index: Int, timestamp: Long) {
        val entry = cache[index]
        entry.timeStamp = timestamp
        cache.sort()
        listener?.onClipMoved(index, cache.indexOf(entry))
        val cv = ContentValues(1)
        cv.put(COLUMN_TIMESTAMP, timestamp)
        db.writableDatabase.update(TABLE, cv, "$COLUMN_ID = ${entry.id}", null)
    }

    fun isPinned(index: Int) = cache[index].isPinned

    fun getAt(index: Int) = cache[index]

    fun get(id: Long) = cache.first { it.id == id }

    fun count() = cache.size

    fun sort() = cache.sort()

    fun togglePinned(id: Long) {
        val entry = cache.first { it.id == id }
        entry.isPinned = !entry.isPinned
        entry.timeStamp = System.currentTimeMillis()
        if (listener != null) {
            val oldPos = cache.indexOf(entry)
            cache.sort()
            val newPos = cache.indexOf(entry)
            listener?.onClipMoved(oldPos, newPos)
        } else {
            cache.sort()
        }
        val cv = ContentValues(2)
        cv.put(COLUMN_PINNED, entry.isPinned)
        cv.put(COLUMN_TIMESTAMP, entry.timeStamp)
        db.writableDatabase.update(TABLE, cv, "$COLUMN_ID = ${entry.id}", null)
    }

    fun incrementUseCount(id: Long) {
        val entry = cache.firstOrNull { it.id == id } ?: return
        entry.useCount++
        val cv = ContentValues(1)
        cv.put(COLUMN_USE_COUNT, entry.useCount)
        db.writableDatabase.update(TABLE, cv, "$COLUMN_ID = $id", null)
    }

    fun setAnnotation(id: Long, annotation: String?) {
        val entry = cache.firstOrNull { it.id == id } ?: return
        val previousAnnotation = entry.annotation
        entry.annotation = annotation
        val cv = ContentValues(1)
        if (annotation == null) {
            cv.putNull(COLUMN_ANNOTATION)
        } else {
            // Match the row's at-rest format: an encrypted row keeps its annotation encrypted too,
            // so the cache-load decrypt path reads it back correctly. Same fail-closed rule as the
            // clip itself — never write readable text into a row flagged encrypted.
            val rowEncrypted = isRowEncrypted(id)
            val stored = ClipboardCipher.storedValue(
                encryptionExpected = rowEncrypted,
                plaintext = annotation,
                ciphertext = if (rowEncrypted) ClipboardCipher.encrypt(annotation) else null,
            )
            if (stored == null) {
                Log.e(TAG, "annotation not stored: encryption failed")
                entry.annotation = previousAnnotation
                warnOnce(R.string.clipboard_encryption_failed)
                return
            }
            cv.put(COLUMN_ANNOTATION, stored.value)
        }
        db.writableDatabase.update(TABLE, cv, "$COLUMN_ID = $id", null)
    }

    /**
     * Shows [messageResId] once per process. Once: the clipboard listener fires on every copy, and a
     * toast per copy would be its own bug. `compareAndSet` because [addClip] is not synchronised, so
     * two copies landing together would otherwise both get through the check.
     */
    private fun warnOnce(@StringRes messageResId: Int) {
        if (!encryptionWarningShown.compareAndSet(false, true)) return
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(appContext, messageResId, Toast.LENGTH_LONG).show()
        }
    }

    private fun isRowEncrypted(id: Long): Boolean =
        db.readableDatabase.query(TABLE, arrayOf(COLUMN_ENCRYPTED), "$COLUMN_ID = $id", null, null, null, null).use {
            it.moveToFirst() && it.getInt(0) != 0
        }

    fun deleteById(id: Long) {
        val entry = cache.firstOrNull { it.id == id } ?: return
        val index = cache.indexOf(entry)
        cache.remove(entry)
        listener?.onClipsRemoved(index, 1)
        db.writableDatabase.delete(TABLE, "$COLUMN_ID = $id", null)
    }

    // RecyclerView initiates this, so we don't call listener (or we'll get an IndexOutOfRangeException from RecyclerView)
    fun deleteClipAt(index: Int) {
        val entry = cache[index]
        cache.remove(entry)
        db.writableDatabase.delete(TABLE, "$COLUMN_ID = ${entry.id}", null)
    }

    fun clearOldClips(now: Boolean = false) {
        if (listener != null)
            return // never clear when clipboard is visible
        if (!now && lastClearOldClips > SystemClock.elapsedRealtime() - 5 * 1000)
            return

        lastClearOldClips = SystemClock.elapsedRealtime()
        val retentionTime = Settings.getValues()?.mClipboardHistoryRetentionTime
            ?: Settings.CLIPBOARD_RETENTION_NO_LIMIT_MINUTES.toLong()
        if (retentionTime >= Settings.CLIPBOARD_RETENTION_NO_LIMIT_MINUTES) return
        val minTime = System.currentTimeMillis() - retentionTime * 60 * 1000L
        if (!cache.removeAll { it.timeStamp < minTime && !it.isPinned })
            return // nothing was removed

        db.writableDatabase.delete(TABLE, "$COLUMN_TIMESTAMP < $minTime AND $COLUMN_PINNED = 0", null)
    }

    fun clearNonPinned() {
        if (listener != null) {
            val indicesToRemove = mutableListOf<Int>()
            cache.forEachIndexed { idx, clip ->
                if (!clip.isPinned)
                    indicesToRemove.add(idx)
            }
            if (indicesToRemove.isEmpty())
                return // nothing to remove
            cache.removeAll { !it.isPinned }
            listener?.onClipsRemoved(indicesToRemove[0], indicesToRemove.size)
        } else if (!cache.removeAll { !it.isPinned }) {
            return // no listener, nothing to remove
        }
        db.writableDatabase.delete(TABLE, "$COLUMN_PINNED = 0", null)
    }

    fun clear() {
        val removed = count()
        if (removed == 0) return
        cache.clear()
        listener?.onClipsRemoved(0, removed)
        db.writableDatabase.delete(TABLE, null, null)
    }

    companion object {
        private const val TAG = "ClipboardDao"

        private val encryptionWarningShown = AtomicBoolean(false)

        private const val TABLE = "CLIPBOARD"
        // it's possible timestamp is not unique, so we use a separate ID
        // ID is generated and returned on insert, see https://sqlite.org/rowidtable.html
        private const val COLUMN_ID = "ID"
        private const val COLUMN_TIMESTAMP = "TIMESTAMP"
        private const val COLUMN_PINNED = "PINNED"
        private const val COLUMN_TEXT = "TEXT" // we could enforce unique text, but that's only necessary if we can drop the cache (later)
        private const val COLUMN_USE_COUNT = "USE_COUNT"
        private const val COLUMN_ANNOTATION = "ANNOTATION"
        // 1 when COLUMN_TEXT / COLUMN_ANNOTATION hold AES-GCM ciphertext (see ClipboardCipher), else 0.
        private const val COLUMN_ENCRYPTED = "ENCRYPTED"
        const val CREATE_TABLE = """
            CREATE TABLE $TABLE (
                $COLUMN_ID INTEGER PRIMARY KEY,
                $COLUMN_TIMESTAMP INTEGER NOT NULL,
                $COLUMN_PINNED TINYINT NOT NULL,
                $COLUMN_TEXT TEXT,
                $COLUMN_USE_COUNT INTEGER NOT NULL DEFAULT 0,
                $COLUMN_ANNOTATION TEXT,
                $COLUMN_ENCRYPTED INTEGER NOT NULL DEFAULT 0
            )
        """

        /**
         * v3 → v4 migration: encrypt existing plaintext clip rows in place. No-op when the keystore
         * is unavailable (rows stay plaintext, flag 0). Runs once from [Database.onUpgrade], which
         * already wraps it in a transaction.
         */
        fun encryptExistingRows(db: SQLiteDatabase) {
            if (!ClipboardCipher.isAvailable()) return
            db.query(TABLE, arrayOf(COLUMN_ID, COLUMN_TEXT, COLUMN_ANNOTATION), "$COLUMN_ENCRYPTED = 0", null, null, null, null).use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    val plainText = c.getString(1)
                    val plainAnnotation = c.getString(2)
                    val encText = plainText?.let { ClipboardCipher.encrypt(it) }
                    val encAnnotation = plainAnnotation?.let { ClipboardCipher.encrypt(it) }
                    // Skip a row if a present field failed to encrypt — never flag a row encrypted
                    // while leaving plaintext in it.
                    if ((plainText != null && encText == null) || (plainAnnotation != null && encAnnotation == null)) continue
                    val cv = ContentValues()
                    if (encText != null) cv.put(COLUMN_TEXT, encText)
                    if (encAnnotation != null) cv.put(COLUMN_ANNOTATION, encAnnotation)
                    cv.put(COLUMN_ENCRYPTED, 1)
                    db.update(TABLE, cv, "$COLUMN_ID = ?", arrayOf(id.toString()))
                }
            }
        }

        private var instance: ClipboardDao? = null

        /** Returns the instance or creates a new one. Returns null if instance can't be created (e.g. no access to db due to device being locked) */
        fun getInstance(context: Context): ClipboardDao? {
            if (instance == null)
                try {
                    instance = ClipboardDao(Database.getInstance(context), context.applicationContext)
                } catch (e: Throwable) {
                    Log.e(TAG, "can't create ClipboardDao", e)
                }
            return instance
        }
    }
}
