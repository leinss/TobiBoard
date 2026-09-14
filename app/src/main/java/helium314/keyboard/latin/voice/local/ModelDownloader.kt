// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice.local

import android.os.Build
import helium314.keyboard.latin.utils.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Pulls a [ModelInfo]'s files from HuggingFace, resumable via the `Range:` header, and
 * verifies each file's SHA-256 against the pinned digest before renaming the `.part`
 * file to its final name. No retries — the caller (typically [ModelDownloadService])
 * decides retry policy.
 *
 * Context-free by design; the service resolves [ModelStorage.dirFor] up front and
 * passes the resolved `targetDir`. This keeps the downloader testable without
 * Robolectric or a mock Context.
 */
internal class ModelDownloader(
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 30_000,
) {
    companion object {
        private const val TAG = "ModelDownloader"
        private const val BUFFER_SIZE = 64 * 1024
        private const val PROGRESS_BYTE_INTERVAL = 256L * 1024L
        private const val PROGRESS_TIME_INTERVAL_MS = 200L
        private const val MAX_REDIRECTS = 5
        private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
    }

    /**
     * Run the download to completion. Calls [onUpdate] with each [DownloadState]
     * transition; the final state is either [DownloadState.Ready] or
     * [DownloadState.Failed]. Cancellation throws [CancellationException] — the
     * caller's `try/catch` should treat that as [DownloadState.Cancelled].
     *
     * When [authToken] is non-null it is sent as `Authorization: Bearer <token>` on every
     * request. The caller is responsible for refusing the download up front if
     * [ModelInfo.requiresAuth] is set but no token is available — the downloader does
     * not inspect that flag.
     */
    suspend fun download(
        targetDir: File,
        model: ModelInfo,
        authToken: String? = null,
        onUpdate: (DownloadState) -> Unit,
    ) {
        if (model.files.any { it.sha256 == REQUIRES_HASH_PINNING || it.sha256.length != 64 }) {
            onUpdate(DownloadState.Failed("Model ${model.id} has un-pinned SHA-256; refusing to download."))
            return
        }
        targetDir.mkdirs()
        onUpdate(DownloadState.Queued)
        withContext(Dispatchers.IO) {
            try {
                model.files.forEachIndexed { index, file ->
                    downloadFile(targetDir, file, index, model.files.size, authToken, onUpdate)
                }
                onUpdate(DownloadState.Ready)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Download failed for ${model.id}", e)
                onUpdate(DownloadState.Failed(e.message ?: e.javaClass.simpleName))
            }
        }
    }

    private suspend fun downloadFile(
        targetDir: File,
        file: ModelFile,
        index: Int,
        count: Int,
        authToken: String?,
        onUpdate: (DownloadState) -> Unit,
    ) {
        val finalFile = File(targetDir, file.relativePath)
        if (finalFile.isFile) {
            // Hash it, do not trust its length. A non-empty file was accepted outright, so a
            // truncated, half-written or tampered model file from an earlier run was adopted as
            // finished and only failed later as an unexplained native load error.
            onUpdate(DownloadState.Verifying(file.relativePath))
            if (ModelStorage.isFileIntact(finalFile, file.sha256, file.sizeBytes)) return
            Log.w(TAG, "existing ${file.relativePath} does not match the pinned size/SHA-256; re-downloading")
            finalFile.delete()
        }
        val partFile = File(targetDir, file.relativePath + ModelStorage.PART_SUFFIX)
        partFile.parentFile?.mkdirs()
        val resumeFrom = resumeOffsetFor(if (partFile.isFile) partFile.length() else 0L, file.sizeBytes)

        val (connection, partial) = openConnection(file.url, resumeFrom, file.sizeBytes, authToken)
        try {
            // getContentLengthLong requires API 24; fall back to the int variant on older devices.
            val rawLength = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N)
                connection.contentLengthLong
            else
                connection.contentLength.toLong()
            val streamLength = rawLength.takeIf { it >= 0 } ?: -1L
            val totalBytes = if (streamLength >= 0) {
                if (partial) resumeFrom + streamLength else streamLength
            } else {
                file.sizeBytes
            }

            // "rw" for both fresh and resumed downloads; the resume offset is applied via the
            // setLength/seek calls below.
            RandomAccessFile(partFile, "rw").use { raf ->
                raf.setLength(if (partial) resumeFrom else 0L)
                raf.seek(if (partial) resumeFrom else 0L)
                connection.inputStream.use { input ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var written = if (partial) resumeFrom else 0L
                    var lastEmitBytes = written
                    var lastEmitMs = System.currentTimeMillis()
                    onUpdate(DownloadState.Downloading(written, totalBytes, file.relativePath, index, count))
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read <= 0) break
                        raf.write(buffer, 0, read)
                        written += read
                        val now = System.currentTimeMillis()
                        if (written - lastEmitBytes >= PROGRESS_BYTE_INTERVAL ||
                            now - lastEmitMs >= PROGRESS_TIME_INTERVAL_MS) {
                            lastEmitBytes = written
                            lastEmitMs = now
                            onUpdate(DownloadState.Downloading(written, totalBytes, file.relativePath, index, count))
                        }
                    }
                }
            }
        } finally {
            connection.disconnect()
        }

        onUpdate(DownloadState.Verifying(file.relativePath))
        val digest = sha256(partFile)
        if (!digest.equals(file.sha256, ignoreCase = true)) {
            partFile.delete()
            throw IOException("SHA-256 mismatch for ${file.relativePath}: expected ${file.sha256}, got $digest")
        }
        if (!partFile.renameTo(finalFile)) {
            throw IOException("Failed to finalise ${partFile.name} → ${finalFile.name}")
        }
    }

    private fun openConnection(
        url: String,
        resumeFrom: Long,
        expectedTotalBytes: Long,
        authToken: String?,
    ): Pair<HttpURLConnection, Boolean> {
        val configure: (HttpURLConnection) -> Unit = {
            if (resumeFrom > 0) it.setRequestProperty("Range", "bytes=$resumeFrom-")
        }
        val connection = newConnection(url, authToken, configure)
        val code = connection.responseCode
        return when {
            code == HttpURLConnection.HTTP_PARTIAL -> {
                // A 206 alone does not say the bytes we already hold belong to this file. Make the
                // server confirm the offset and the total before appending to a `.part` whose
                // provenance we never checked.
                val range = connection.getHeaderField("Content-Range")
                if (contentRangeContinues(range, resumeFrom, expectedTotalBytes)) {
                    connection to true
                } else {
                    Log.w(TAG, "refusing to resume $url: Content-Range '$range' does not continue $resumeFrom/$expectedTotalBytes")
                    connection.disconnect()
                    restartFromScratch(url, authToken) to false
                }
            }
            code == HttpURLConnection.HTTP_OK -> connection to false
            code == 416 && resumeFrom > 0 -> {
                connection.disconnect()
                // Range exceeded — server thinks we already have everything. Restart from 0.
                restartFromScratch(url, authToken) to false
            }
            else -> {
                connection.disconnect()
                throw IOException("HTTP $code for $url")
            }
        }
    }

    /** A fresh, un-ranged GET, for when the `.part` on disk cannot be trusted as a prefix. */
    private fun restartFromScratch(url: String, authToken: String?): HttpURLConnection {
        val fresh = newConnection(url, authToken) {}
        val freshCode = fresh.responseCode
        if (freshCode != HttpURLConnection.HTTP_OK) {
            fresh.disconnect()
            throw IOException("HTTP $freshCode for $url")
        }
        return fresh
    }

    private fun newConnection(
        url: String,
        authToken: String?,
        configure: (HttpURLConnection) -> Unit,
    ): HttpURLConnection {
        // Follow redirects manually so the Authorization header is dropped on the second
        // hop. HF returns 302 to a presigned CDN URL on cas-bridge.xethub.hf.co; that
        // URL carries its own AWS signature and rejects extra auth headers with HTTP 401.
        var current = url
        var hopsTaken = 0
        repeat(MAX_REDIRECTS) {
            val conn = (URL(current).openConnection() as HttpURLConnection).apply {
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                requestMethod = "GET"
                instanceFollowRedirects = false
                if (authToken != null && hopsTaken == 0) {
                    setRequestProperty("Authorization", "Bearer $authToken")
                }
                configure(this)
            }
            val code = conn.responseCode
            if (code !in REDIRECT_CODES) return conn
            val location = conn.getHeaderField("Location") ?: run {
                conn.disconnect(); throw IOException("HTTP $code without Location for $current")
            }
            conn.disconnect()
            current = URL(URL(current), location).toString()
            hopsTaken++
        }
        throw IOException("Too many redirects starting at $url")
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

/**
 * Byte offset a `.part` file may be resumed from, or 0 to start over.
 *
 * A `.part` is only trusted when its length is a strict prefix of the file we pinned: one that is
 * already as long as (or longer than) the expected total cannot be a prefix of that content, so it
 * is a leftover from a different revision and resuming would append fresh bytes onto stale ones.
 * The SHA-256 check at the end would catch that, after re-downloading hundreds of megabytes.
 *
 * Pure, so it is unit-tested.
 */
internal fun resumeOffsetFor(partLength: Long, expectedTotalBytes: Long): Long =
    if (partLength > 0 && expectedTotalBytes > 0 && partLength < expectedTotalBytes) partLength else 0L

/**
 * True when a `Content-Range` header confirms that a 206 response really continues our `.part`:
 * it must start at exactly the offset we asked for and describe a file of the size we pinned.
 *
 * A missing or unparseable header is not a confirmation, so it returns false and the caller
 * restarts the file. `bytes <start>-<end>/<total>` is the only form RFC 7233 allows for a 206.
 *
 * Pure, so it is unit-tested.
 */
internal fun contentRangeContinues(header: String?, expectedStart: Long, expectedTotalBytes: Long): Boolean {
    val match = CONTENT_RANGE_REGEX.find(header?.trim().orEmpty()) ?: return false
    val (start, _, total) = match.destructured
    return start.toLongOrNull() == expectedStart && total.toLongOrNull() == expectedTotalBytes
}

private val CONTENT_RANGE_REGEX = Regex("^bytes\\s+(\\d+)-(\\d+)/(\\d+)$", RegexOption.IGNORE_CASE)
