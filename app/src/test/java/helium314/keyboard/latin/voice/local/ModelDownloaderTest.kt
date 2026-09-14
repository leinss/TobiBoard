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
import java.util.Collections
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

    @Test fun aPreExistingFileThatDoesNotMatchThePinnedHashIsReplaced() = runBlocking {
        // Non-empty used to be enough, so a truncated or tampered file from an earlier run was
        // adopted as finished and only surfaced later as an unexplained native load failure.
        File(tmp.root, "payload.bin").writeBytes(ByteArray(payload.size) { 0 })

        val states = mutableListOf<DownloadState>()
        ModelDownloader().download(tmp.root, testModel(payloadSha)) { states.add(it) }

        assertTrue("expected Ready, got $states", states.last() is DownloadState.Ready)
        assertEquals(payloadSha, sha256Hex(File(tmp.root, "payload.bin").readBytes()))
    }

    @Test fun aPreExistingFileThatMatchesThePinnedHashIsNotDownloadedAgain() = runBlocking {
        File(tmp.root, "payload.bin").writeBytes(payload)

        ModelDownloader().download(tmp.root, testModel(payloadSha)) { }

        assertEquals("the file was already correct; nothing should have been fetched", -1L, server.lastRangeStart)
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

    @Test fun aRedirectIsFollowedAndTheAuthorizationHeaderIsNotSentToTheSecondHost() = runBlocking {
        // The presigned CDN URL HuggingFace redirects to carries its own signature and answers 401
        // when an extra Authorization header arrives with the request.
        val model = testModel(payloadSha, path = TinyRangeHttpServer.REDIRECT_PATH)
        val states = mutableListOf<DownloadState>()
        ModelDownloader().download(tmp.root, model, authToken = "test-token-xyz") { states.add(it) }

        assertTrue("expected Ready, got $states", states.last() is DownloadState.Ready)
        assertEquals(payloadSha, sha256Hex(File(tmp.root, "payload.bin").readBytes()))

        val hops = server.requests.toList()
        assertEquals("expected exactly two hops, got $hops", 2, hops.size)
        assertEquals(TinyRangeHttpServer.REDIRECT_PATH, hops[0].path)
        assertEquals("Bearer test-token-xyz", hops[0].authorization)
        assertEquals(TinyRangeHttpServer.PAYLOAD_PATH, hops[1].path)
        assertEquals("the token must not reach the redirect target", null, hops[1].authorization)
    }

    @Test fun aContentRangeThatDoesNotContinueOurPartFileRestartsTheDownloadFromScratch() = runBlocking {
        // The file behind the URL changed: the bytes on disk belong to an older revision, and
        // appending to them would only be caught by the SHA-256 check after the whole download.
        val part = File(tmp.root, "payload.bin.part")
        part.writeBytes(ByteArray(30_000) { 0 })
        server.rangeBehavior = TinyRangeHttpServer.RangeBehavior.MISMATCHED_CONTENT_RANGE

        val states = mutableListOf<DownloadState>()
        ModelDownloader().download(tmp.root, testModel(payloadSha)) { states.add(it) }

        assertTrue("expected Ready, got $states", states.last() is DownloadState.Ready)
        assertEquals(payloadSha, sha256Hex(File(tmp.root, "payload.bin").readBytes()))

        val hops = server.requests.toList()
        assertEquals("expected a ranged request and one restart, got $hops", 2, hops.size)
        assertEquals(30_000L, hops[0].rangeStart)
        assertEquals("the restart must not ask for a range", null, hops[1].rangeStart)
    }

    @Test fun a416OnAResumeRestartsTheDownloadFromScratch() = runBlocking {
        // The server says our offset is past the end of the file, so what is on disk cannot be a
        // prefix of it.
        File(tmp.root, "payload.bin.part").writeBytes(ByteArray(30_000) { 0 })
        server.rangeBehavior = TinyRangeHttpServer.RangeBehavior.NOT_SATISFIABLE

        val states = mutableListOf<DownloadState>()
        ModelDownloader().download(tmp.root, testModel(payloadSha)) { states.add(it) }

        assertTrue("expected Ready, got $states", states.last() is DownloadState.Ready)
        assertEquals(payloadSha, sha256Hex(File(tmp.root, "payload.bin").readBytes()))

        val hops = server.requests.toList()
        assertEquals("expected a ranged request and one restart, got $hops", 2, hops.size)
        assertEquals(30_000L, hops[0].rangeStart)
        assertEquals(null, hops[1].rangeStart)
    }

    private fun testModel(sha256: String, path: String = TinyRangeHttpServer.PAYLOAD_PATH): ModelInfo = object : ModelInfo {
        override val id = "test-model"
        override val displayName = "Test"
        override val files = listOf(
            ModelFile("payload.bin", urlFor(path), sha256, payload.size.toLong())
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
    /** How the server answers a request that carries a `Range:` header. */
    enum class RangeBehavior {
        /** RFC-compliant 206 with a matching `Content-Range`. */
        HONOUR,

        /** 206 whose `Content-Range` describes a different offset and a different total size. */
        MISMATCHED_CONTENT_RANGE,

        /** 416: the server believes the requested offset is past the end of the file. */
        NOT_SATISFIABLE,
    }

    /** One request as the server saw it. [rangeStart] is null when the request carried no Range. */
    data class Request(val path: String, val authorization: String?, val rangeStart: Long?)

    private val socket = ServerSocket(0)
    @Volatile var rangeBehavior: RangeBehavior = RangeBehavior.HONOUR
    @Volatile var lastRangeStart: Long = -1L
        private set
    @Volatile var lastAuthorizationHeader: String? = null
        private set
    val requests: MutableList<Request> = Collections.synchronizedList(mutableListOf<Request>())
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
            val path = requestLine.split(" ").getOrElse(1) { PAYLOAD_PATH }
            requests.add(Request(path, authHeader, if (hasRange) rangeStart else null))

            // The HuggingFace shape: the model URL 302s to a presigned CDN URL that carries its own
            // signature and rejects an extra Authorization header with a 401.
            if (path == REDIRECT_PATH) {
                writeHeaders(sock, "302 Found", listOf("Location: $PAYLOAD_PATH", "Content-Length: 0"))
                return
            }

            lastRangeStart = if (hasRange) rangeStart else 0L
            if (hasRange && rangeBehavior != RangeBehavior.HONOUR) {
                if (rangeBehavior == RangeBehavior.NOT_SATISFIABLE) {
                    writeStatus(sock, 416, "Range Not Satisfiable")
                } else {
                    // A 206 for a different file: right status, wrong bytes.
                    writeHeaders(
                        sock,
                        "206 Partial Content",
                        listOf(
                            "Content-Range: bytes 0-${payload.size}/${payload.size + 1}",
                            "Content-Length: 0",
                        ),
                    )
                }
                return
            }

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

    private fun writeHeaders(sock: Socket, status: String, headers: List<String>) {
        val text = buildString {
            append("HTTP/1.1 ").append(status).append("\r\n")
            headers.forEach { append(it).append("\r\n") }
            append("Connection: close\r\n\r\n")
        }
        sock.getOutputStream().apply {
            write(text.toByteArray(StandardCharsets.ISO_8859_1))
            flush()
        }
    }

    private fun writeStatus(sock: Socket, code: Int, msg: String) {
        sock.getOutputStream().write("HTTP/1.1 $code $msg\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
    }

    companion object {
        const val PAYLOAD_PATH = "/payload.bin"
        const val REDIRECT_PATH = "/redirect.bin"
    }
}
