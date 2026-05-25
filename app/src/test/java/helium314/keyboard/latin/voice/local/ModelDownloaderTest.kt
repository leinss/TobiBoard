// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice.local

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import kotlin.concurrent.thread

@RunWith(RobolectricTestRunner::class)
class ModelDownloaderTest {

    @get:Rule val tmp = TemporaryFolder()

    private val payload = ByteArray(64 * 1024) { (it % 251).toByte() }
    private val payloadSha = sha256Hex(payload)
    private lateinit var server: TinyRangeHttpServer

    @Before fun startServer() {
        server = TinyRangeHttpServer(payload).also { it.start() }
    }

    @After fun stopServer() {
        server.stop()
    }

    private fun urlFor(path: String): String = "http://127.0.0.1:${server.port}$path"

    @Test fun cleanDownloadCompletesAndVerifies() = runBlocking {
        val model = testModel(payloadSha)
        val states = mutableListOf<DownloadState>()
        ModelDownloader().download(tmp.root, model) { states.add(it) }

        assertTrue("expected Ready, got $states", states.last() is DownloadState.Ready)
        val file = File(tmp.root, "payload.bin")
        assertEquals(payload.size.toLong(), file.length())
        assertEquals(payloadSha, sha256Hex(file.readBytes()))
        assertFalse(File(tmp.root, "payload.bin.part").exists())
    }

    @Test fun shaMismatchFailsAndCleansPartFile() = runBlocking {
        val model = testModel(sha256 = "a".repeat(64))
        val states = mutableListOf<DownloadState>()
        ModelDownloader().download(tmp.root, model) { states.add(it) }

        val last = states.last()
        assertTrue("expected Failed, got $last", last is DownloadState.Failed)
        assertTrue((last as DownloadState.Failed).reason.contains("SHA-256 mismatch"))
        assertFalse(File(tmp.root, "payload.bin").exists())
        assertFalse(File(tmp.root, "payload.bin.part").exists())
    }

    @Test fun resumesFromExistingPartFile() = runBlocking {
        val resumeFrom = 30_000
        val part = File(tmp.root, "payload.bin.part")
        part.writeBytes(payload.copyOfRange(0, resumeFrom))

        val model = testModel(payloadSha)
        val states = mutableListOf<DownloadState>()
        ModelDownloader().download(tmp.root, model) { states.add(it) }

        assertTrue("expected Ready, got $states", states.last() is DownloadState.Ready)
        val file = File(tmp.root, "payload.bin")
        assertEquals(payload.size.toLong(), file.length())
        assertEquals(payloadSha, sha256Hex(file.readBytes()))
        assertEquals(resumeFrom.toLong(), server.lastRangeStart)
    }

    @Test fun forwardsAuthorizationHeaderWhenTokenIsProvided() = runBlocking {
        val model = testModel(payloadSha)
        val states = mutableListOf<DownloadState>()
        ModelDownloader().download(tmp.root, model, authToken = "test-token-xyz") { states.add(it) }

        assertTrue("expected Ready, got $states", states.last() is DownloadState.Ready)
        assertEquals("Bearer test-token-xyz", server.lastAuthorizationHeader)
    }

    @Test fun omitsAuthorizationHeaderWhenTokenIsNull() = runBlocking {
        val model = testModel(payloadSha)
        ModelDownloader().download(tmp.root, model) { }

        assertEquals(null, server.lastAuthorizationHeader)
    }

    @Test fun refusesUnpinnedHash() = runBlocking {
        val model = object : ModelInfo {
            override val id = "unpinned"
            override val displayName = "Unpinned"
            override val files = listOf(
                ModelFile("payload.bin", urlFor("/payload.bin"), REQUIRES_HASH_PINNING, payload.size.toLong())
            )
        }
        val states = mutableListOf<DownloadState>()
        ModelDownloader().download(tmp.root, model) { states.add(it) }
        val last = states.last()
        assertTrue("expected Failed, got $last", last is DownloadState.Failed)
        assertTrue((last as DownloadState.Failed).reason.contains("un-pinned"))
    }

    private fun testModel(sha256: String): ModelInfo = object : ModelInfo {
        override val id = "test-model"
        override val displayName = "Test"
        override val files = listOf(
            ModelFile("payload.bin", urlFor("/payload.bin"), sha256, payload.size.toLong())
        )
    }

    companion object {
        private fun sha256Hex(bytes: ByteArray): String {
            val d = MessageDigest.getInstance("SHA-256").digest(bytes)
            return d.joinToString("") { "%02x".format(it) }
        }
    }
}

/**
 * Minimal HTTP/1.1 server that serves a fixed byte payload and honours
 * `Range: bytes=N-` for resume tests. One thread per connection; connection-close
 * semantics so we don't need keep-alive handling.
 */
private class TinyRangeHttpServer(private val payload: ByteArray) {
    private val socket = ServerSocket(0)
    @Volatile var lastRangeStart: Long = -1L
        private set
    @Volatile var lastAuthorizationHeader: String? = null
        private set
    val port: Int get() = socket.localPort
    private var acceptor: Thread? = null
    @Volatile private var stopped = false

    fun start() {
        acceptor = thread(name = "TinyHttpAcceptor", isDaemon = true) {
            while (!stopped) {
                val client = try { socket.accept() } catch (_: Exception) { break }
                thread(isDaemon = true) { handle(client) }
            }
        }
    }

    fun stop() {
        stopped = true
        socket.close()
    }

    private fun handle(client: Socket) {
        client.use { sock ->
            val reader = BufferedReader(InputStreamReader(sock.getInputStream(), StandardCharsets.ISO_8859_1))
            val requestLine = reader.readLine() ?: return
            var rangeStart = 0L
            var hasRange = false
            var authHeader: String? = null
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
                if (line.startsWith("Range:", ignoreCase = true)) {
                    val value = line.substringAfter(":").trim().removePrefix("bytes=").substringBefore("-")
                    rangeStart = value.toLongOrNull() ?: 0L
                    hasRange = true
                }
                if (line.startsWith("Authorization:", ignoreCase = true)) {
                    authHeader = line.substringAfter(":").trim()
                }
            }
            lastAuthorizationHeader = authHeader
            if (!requestLine.startsWith("GET")) {
                writeStatus(sock, 405, "Method Not Allowed")
                return
            }
            lastRangeStart = if (hasRange) rangeStart else 0L
            val slice = payload.copyOfRange(rangeStart.toInt(), payload.size)
            val status = if (hasRange) "206 Partial Content" else "200 OK"
            val headers = buildString {
                append("HTTP/1.1 ").append(status).append("\r\n")
                append("Content-Length: ").append(slice.size).append("\r\n")
                append("Content-Type: application/octet-stream\r\n")
                if (hasRange) {
                    append("Content-Range: bytes ").append(rangeStart)
                        .append("-").append(payload.size - 1)
                        .append("/").append(payload.size).append("\r\n")
                }
                append("Connection: close\r\n\r\n")
            }
            sock.getOutputStream().apply {
                write(headers.toByteArray(StandardCharsets.ISO_8859_1))
                write(slice)
                flush()
            }
        }
    }

    private fun writeStatus(sock: Socket, code: Int, msg: String) {
        sock.getOutputStream().write("HTTP/1.1 $code $msg\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
    }
}
