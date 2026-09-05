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
    /** Injected so a test can supply a cipher; Robolectric has no AndroidKeyStore. */
    private val cipher: ClipboardEncryption = ClipboardCipher,
) {
    interface Listener {
        fun onClipInserted(position: Int)
        fun onClipsRemoved(position: Int, count: Int)
        fun onClipMoved(oldPosition: Int, newPosition: Int)
    }

    var listener: Listener? = null

    // we clean up old clips when a new clip is added, but not too frequently
    private var lastClearOldClips = 0L

    // How new clips are written. Fixed for the DAO's lifetime; reads honor each row's own ENCRYPTED
    // flag so plaintext (legacy / API < 23) and encrypted rows can coexist.
    private val writeMode = ClipboardCipher.writeMode(cipher.keystoreSupported, cipher.isAvailable())
    private val encrypting = writeMode == ClipboardWriteMode.ENCRYPT
    private val unreadableWarningShown = AtomicBoolean(false)

    /** How many rows the last cache load had to drop because they no longer decrypt. Test seam. */
    @Volatile
    var undecryptableCount: Int = 0
        private set

    init {
        // The user is told once either way, because the app claims the clipboard is encrypted and
        // silence here is the part that misleads.
        when (writeMode) {
            ClipboardWriteMode.ENCRYPT -> {}
            ClipboardWriteMode.PLAINTEXT -> {
                Log.w(TAG, "no keystore on this platform; clips are stored in plaintext")
                warnOnce(R.string.clipboard_encryption_unavailable)
            }
            ClipboardWriteMode.REFUSE -> {
                // A keystore that exists but cannot produce a key is a broken device, not a
                // platform limit. Storing the clip readable there would contradict the claim.
                Log.e(TAG, "keystore present but no key available; clipboard history is disabled")
                warnOnce(R.string.clipboard_encryption_broken)
            }
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
     * Drops the in-memory cache so the next access re-reads the database. Test seam: the cache is
     * loaded once per process in production, and the undecryptable-row handling only runs on a load.
     */
    @androidx.annotation.VisibleForTesting
    internal fun invalidateCache() {
        synchronized(cacheLoadLock) {
            loadedCache = null
            undecryptableCount = 0
        }
    }

    /**
     * Forces the one-time cache load (DB query + decrypt). Idempotent and safe to call
     * concurrently; intended to be invoked off the main thread so consumers that touch the cache
     * later find it already populated instead of paying the load synchronously.
     */
    fun ensureCacheLoaded() {
        cache // touching the accessor triggers the load if it hasn't happened yet
        vacuumIfRequested(appContext, db)
    }

    private fun readAllFromDb(): MutableList<ClipboardHistoryEntry> {
        val started = if (DebugFlags.DEBUG_ENABLED) SystemClock.elapsedRealtime() else 0L
        val list = mutableListOf<ClipboardHistoryEntry>()
        val undecryptableIds = mutableListOf<Long>()
        // Proof that the AES key is usable in this process. Without it a transient keystore failure
        // (the service not yet warm after boot, a momentary KeyStoreException) looks exactly like a
        // lost key, and deleting on that reading would take the whole encrypted history with it.
        var anyRowDecrypted = false
        var undecryptableAnnotations = 0
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
                    // A row flagged encrypted whose content won't decrypt cannot be shown as
                    // ciphertext, so it is dropped from the cache and counted. Skipping it silently,
                    // as this used to, made entries vanish from history with no explanation.
                    val text = if (isEncrypted)
                        (it.getString(3)?.let { stored -> cipher.decrypt(stored) }
                            ?.also { _ -> anyRowDecrypted = true }
                            ?: run { undecryptableIds.add(it.getLong(0)); continue })
                    else it.getString(3)
                    val storedAnnotation = it.getString(5)
                    val annotation = if (isEncrypted && storedAnnotation != null) {
                        // The clip itself decrypted, so a null here is this one value, not the key.
                        cipher.decrypt(storedAnnotation)
                            ?.also { _ -> anyRowDecrypted = true }
                            ?: run { undecryptableAnnotations += 1; null }
                    } else storedAnnotation
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
        if (undecryptableAnnotations > 0)
            Log.w(TAG, "$undecryptableAnnotations clipboard annotations could not be decrypted")
        if (undecryptableIds.isNotEmpty()) reportUndecryptable(undecryptableIds, keyProven = anyRowDecrypted)
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
        // disk under an ENCRYPTED = 0 flag, and neither must one whose keystore is broken. Only
        // PLAINTEXT, which is the platform having no keystore at all, writes readable text.
        val stored = ClipboardCipher.storedValue(
            encryptionExpected = writeMode != ClipboardWriteMode.PLAINTEXT,
            plaintext = text,
            ciphertext = if (encrypting) cipher.encrypt(text) else null,
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
                ciphertext = if (rowEncrypted) cipher.encrypt(annotation) else null,
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
        warnOnce(encryptionWarningShown, appContext.getString(messageResId))
    }

    private fun warnOnce(flag: AtomicBoolean, message: String) {
        if (!flag.compareAndSet(false, true)) return
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(appContext, message, Toast.LENGTH_LONG).show()
        }
    }

    /**
     * Tells the user how many rows could not be decrypted, and deletes them only when the key is
     * known to work.
     *
     * [keyProven] is true when some other encrypted row in the same load decrypted, which is the
     * only evidence available that the AES key is usable right now. Without it the rows are left
     * alone: [ClipboardCipher.decrypt] also returns null when the keystore itself fails, and that
     * failure is often transient, so deleting on it would destroy a readable history for good.
     */
    private fun reportUndecryptable(ids: List<Long>, keyProven: Boolean) {
        undecryptableCount = ids.size
        if (!keyProven) {
            Log.w(TAG, "${ids.size} clipboard rows did not decrypt and no row did; keeping them")
            warnOnce(unreadableWarningShown, appContext.getString(R.string.clipboard_entries_unreadable_kept, ids.size))
            return
        }
        Log.w(TAG, "dropping ${ids.size} clipboard rows that no longer decrypt")
        val deleted = try {
            db.writableDatabase.delete(TABLE, "$COLUMN_ID IN (${ids.joinToString(",")})", null) > 0
        } catch (t: Throwable) {
            Log.e(TAG, "failed to delete undecryptable clipboard rows", t)
            false
        }
        val message = if (deleted) R.string.clipboard_entries_unreadable else R.string.clipboard_entries_unreadable_kept
        warnOnce(unreadableWarningShown, appContext.getString(message, ids.size))
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

    /**
     * Replaces the clipboard history with the rows of [source], an imported database already
     * migrated to the current schema.
     *
     * A row's ENCRYPTED flag decides how its text is read. The v3 to v4 migration encrypts an
     * imported plaintext backup with *this* device's key, so those rows decrypt here; a row that
     * arrived already encrypted on another device does not, and is dropped. Reading every row as
     * plaintext, as this used to, stored the raw ciphertext as if it were the clip.
     *
     * Returns the number of rows dropped because they could not be read.
     */
    fun importFrom(source: SQLiteDatabase): Int {
        var dropped = 0
        source.query(
            TABLE,
            arrayOf(COLUMN_TIMESTAMP, COLUMN_PINNED, COLUMN_TEXT, COLUMN_ENCRYPTED),
            null, null, null, null, null,
        ).use { c ->
            clear()
            while (c.moveToNext()) {
                val stored = c.getString(2)
                val text = if (c.getInt(3) != 0) stored?.let { cipher.decrypt(it) } else stored
                if (text == null) {
                    dropped++
                    continue
                }
                addClip(c.getLong(0), c.getInt(1) != 0, text)
            }
        }
        return dropped
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

        // Own preferences file, so the one-time flag needs no key in the shared settings surface.
        private const val DB_PREFS = "clipboard_db"
        private const val PREF_VACUUM_PENDING = "vacuum_after_encryption_migration"

        /**
         * Ask for the one-time VACUUM. Called from the v3 → v4 migration, which encrypts existing
         * rows in place: SQLite keeps the old plaintext in free pages until the file is rewritten,
         * so the readable copy the migration was meant to remove survives in the database file.
         * The migration itself cannot do it, because it runs inside a transaction and VACUUM
         * cannot.
         */
        fun requestVacuum(context: Context) {
            context.applicationContext.getSharedPreferences(DB_PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(PREF_VACUUM_PENDING, true).apply()
        }

        /**
         * Run the requested VACUUM once, then never again. Rewriting the whole database can take
         * seconds on a large clipboard history, so this must be called off the main thread; the
         * flag is cleared first, because a VACUUM that fails is not worth retrying on every load.
         */
        private fun vacuumIfRequested(context: Context, db: Database) {
            val prefs = context.applicationContext.getSharedPreferences(DB_PREFS, Context.MODE_PRIVATE)
            if (!prefs.getBoolean(PREF_VACUUM_PENDING, false)) return
            prefs.edit().putBoolean(PREF_VACUUM_PENDING, false).apply()
            try {
                val started = SystemClock.elapsedRealtime()
                db.writableDatabase.execSQL("VACUUM")
                Log.i(TAG, "vacuumed the database in ${SystemClock.elapsedRealtime() - started} ms")
            } catch (t: Throwable) {
                Log.e(TAG, "VACUUM after the clipboard encryption migration failed", t)
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

        /**
         * A DAO over the real database with [cipher] injected, bypassing the process-wide instance.
         * Test seam: the production cipher needs an AndroidKeyStore, which Robolectric has not, so
         * every write mode except REFUSE would be unreachable from a test.
         */
        @androidx.annotation.VisibleForTesting
        internal fun createForTest(context: Context, cipher: ClipboardEncryption): ClipboardDao =
            ClipboardDao(Database.getInstance(context), context.applicationContext, cipher)
    }
}
