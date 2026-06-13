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
        if (finalFile.isFile && finalFile.length() > 0) {
            return
        }
        val partFile = File(targetDir, file.relativePath + ModelStorage.PART_SUFFIX)
        partFile.parentFile?.mkdirs()
        val resumeFrom = if (partFile.isFile) partFile.length() else 0L

        val (connection, partial) = openConnection(file.url, resumeFrom, authToken)
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

            val openMode = if (partial) "rw" else "rw"
            RandomAccessFile(partFile, openMode).use { raf ->
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
        authToken: String?,
    ): Pair<HttpURLConnection, Boolean> {
        val configure: (HttpURLConnection) -> Unit = {
            if (resumeFrom > 0) it.setRequestProperty("Range", "bytes=$resumeFrom-")
        }
        val connection = newConnection(url, authToken, configure)
        val code = connection.responseCode
        return when {
            code == HttpURLConnection.HTTP_PARTIAL -> connection to true
            code == HttpURLConnection.HTTP_OK -> connection to false
            code == 416 && resumeFrom > 0 -> {
                connection.disconnect()
                // Range exceeded — server thinks we already have everything. Restart from 0.
                val fresh = newConnection(url, authToken) {}
                val freshCode = fresh.responseCode
                if (freshCode != HttpURLConnection.HTTP_OK) {
                    fresh.disconnect()
                    throw IOException("HTTP $freshCode for $url")
                }
                fresh to false
            }
            else -> {
                connection.disconnect()
                throw IOException("HTTP $code for $url")
            }
        }
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
