package com.ufo.galaxy.model

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Unit tests for [ModelDownloader].
 *
 * All network I/O is replaced by a fake [ModelDownloader.HttpFactory] that serves content
 * from in-memory byte arrays. Tests are fully JVM-runnable with no Android SDK required.
 *
 * Async [ModelDownloader.enqueueDownload] tests use [downloadSync] (via [runBlocking])
 * for determinism. The fire-and-forget path is covered separately.
 */
class ModelDownloaderTest {

    @get:Rule
    val tmpFolder = TemporaryFolder()

    private lateinit var modelsDir: File

    @Before
    fun setUp() {
        modelsDir = tmpFolder.newFolder("models")
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Creates a fake [ModelDownloader.HttpFactory] that serves [body] with [responseCode]. */
    private fun fakeHttp(
        responseCode: Int = 200,
        body: ByteArray = ByteArray(0),
        contentLength: Long = -1L
    ): ModelDownloader.HttpFactory = ModelDownloader.HttpFactory { _ ->
        FakeHttpConnection(responseCode, body, if (contentLength >= 0) contentLength else body.size.toLong())
    }

    /** [HttpURLConnection] stub backed by an in-memory byte array. */
    private class FakeHttpConnection(
        private val code: Int,
        private val body: ByteArray,
        private val length: Long
    ) : HttpURLConnection(URL("http://fake.local")) {
        override fun connect() {}
        override fun disconnect() {}
        override fun usingProxy(): Boolean = false
        override fun getResponseCode(): Int = code
        override fun getContentLengthLong(): Long = length
        override fun getInputStream() = ByteArrayInputStream(body)
    }

    /** Tracks whether the HTTP factory was ever called. */
    private class TrackingFactory(private val delegate: ModelDownloader.HttpFactory) : ModelDownloader.HttpFactory {
        var opened = false
        override fun open(url: String): HttpURLConnection {
            opened = true
            return delegate.open(url)
        }
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(bytes)
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun makeDownloader(
        factory: ModelDownloader.HttpFactory
    ) = ModelDownloader(modelsDir, httpFactory = factory)

    // ── Success path ──────────────────────────────────────────────────────────

    @Test
    fun `downloadSync writes file to modelsDir on HTTP 200`() {
        val content = "mobilevlm weights content".toByteArray()
        val downloader = makeDownloader(fakeHttp(body = content))
        val spec = ModelDownloader.DownloadSpec("vlm", "http://fake/vlm.gguf", "vlm.gguf")

        val statuses = mutableListOf<ModelDownloader.DownloadStatus>()
        val result = runBlocking { downloader.downloadSync(spec) { statuses.add(it) } }

        assertTrue("downloadSync must return true", result)
        val dest = File(modelsDir, "vlm.gguf")
        assertTrue("Destination file must exist", dest.exists())
        assertArrayEquals("File content must match", content, dest.readBytes())
    }

    @Test
    fun `downloadSync returns true and reports Success`() {
        val downloader = makeDownloader(fakeHttp(body = "weights".toByteArray()))
        val spec = ModelDownloader.DownloadSpec("m", "http://fake/m.bin", "m.bin")

        var success: ModelDownloader.DownloadStatus.Success? = null
        val result = runBlocking {
            downloader.downloadSync(spec) { if (it is ModelDownloader.DownloadStatus.Success) success = it }
        }

        assertTrue(result)
        assertNotNull("Success status must be reported", success)
        assertEquals("m", success!!.modelId)
    }

    // ── HTTP error path ───────────────────────────────────────────────────────

    @Test
    fun `downloadSync returns false on HTTP 404`() {
        val downloader = makeDownloader(fakeHttp(responseCode = 404))
        val spec = ModelDownloader.DownloadSpec("vlm", "http://fake/vlm.gguf", "vlm.gguf")

        val result = runBlocking { downloader.downloadSync(spec) {} }

        assertFalse("Must return false on HTTP error", result)
        assertFalse("Destination file must not exist", File(modelsDir, "vlm.gguf").exists())
    }

    @Test
    fun `downloadSync reports Failure on HTTP 500 with model id in message`() {
        val downloader = makeDownloader(fakeHttp(responseCode = 500))
        val spec = ModelDownloader.DownloadSpec("mymodel", "http://fake/m.bin", "m.bin")

        var failure: ModelDownloader.DownloadStatus.Failure? = null
        runBlocking {
            downloader.downloadSync(spec) { if (it is ModelDownloader.DownloadStatus.Failure) failure = it }
        }

        assertNotNull("Failure status must be reported", failure)
        assertTrue("Error must reference model id", failure!!.error.contains("mymodel"))
    }

    // ── Checksum verification ─────────────────────────────────────────────────

    @Test
    fun `downloadSync succeeds when checksum matches`() {
        val content = "exact model bytes".toByteArray()
        val checksum = sha256Hex(content)
        val downloader = makeDownloader(fakeHttp(body = content))
        val spec = ModelDownloader.DownloadSpec("m", "http://fake/m.bin", "m.bin", checksum)

        val result = runBlocking { downloader.downloadSync(spec) {} }

        assertTrue("Must succeed with correct checksum", result)
        assertTrue(File(modelsDir, "m.bin").exists())
    }

    @Test
    fun `downloadSync reports Failure on checksum mismatch`() {
        val content = "model bytes".toByteArray()
        val wrongChecksum = "a".repeat(64)
        val downloader = makeDownloader(fakeHttp(body = content))
        val spec = ModelDownloader.DownloadSpec("m", "http://fake/m.bin", "m.bin", wrongChecksum)

        var failure: ModelDownloader.DownloadStatus.Failure? = null
        val result = runBlocking {
            downloader.downloadSync(spec) { if (it is ModelDownloader.DownloadStatus.Failure) failure = it }
        }

        assertFalse("Must return false on checksum failure", result)
        assertNotNull(failure)
        assertTrue("Error must mention checksum", failure!!.error.contains("Checksum"))
        assertFalse("Temp file must be cleaned up", File(modelsDir, "m.bin.tmp").exists())
    }

    @Test
    fun `downloadSync skips checksum when expectedSha256 is null`() {
        val content = "arbitrary bytes".toByteArray()
        val downloader = makeDownloader(fakeHttp(body = content))
        val spec = ModelDownloader.DownloadSpec("m", "http://fake/m.bin", "m.bin", null)

        val result = runBlocking { downloader.downloadSync(spec) {} }

        assertTrue("Must succeed without checksum requirement", result)
    }

    // ── Fast-path: existing valid file ────────────────────────────────────────

    @Test
    fun `skips network if file already exists with no checksum`() {
        val existing = "pre-existing content".toByteArray()
        File(modelsDir, "m.bin").writeBytes(existing)

        val tracking = TrackingFactory(fakeHttp(body = "other".toByteArray()))
        val downloader = makeDownloader(tracking)
        val spec = ModelDownloader.DownloadSpec("m", "http://fake/m.bin", "m.bin", null)

        val result = runBlocking { downloader.downloadSync(spec) {} }

        assertTrue("Must succeed for existing file", result)
        assertFalse("Must not open HTTP connection for existing valid file", tracking.opened)
        assertArrayEquals("File must not be overwritten", existing, File(modelsDir, "m.bin").readBytes())
    }

    @Test
    fun `skips network if file exists and checksum matches`() {
        val content = "verified content".toByteArray()
        val checksum = sha256Hex(content)
        File(modelsDir, "m.bin").writeBytes(content)

        val tracking = TrackingFactory(fakeHttp())
        val downloader = makeDownloader(tracking)
        val spec = ModelDownloader.DownloadSpec("m", "http://fake/m.bin", "m.bin", checksum)

        runBlocking { downloader.downloadSync(spec) {} }

        assertFalse("Must not open HTTP connection when checksum passes", tracking.opened)
    }

    @Test
    fun `re-downloads if existing file has wrong checksum`() {
        val existing = "corrupted content".toByteArray()
        val newContent = "fresh model".toByteArray()
        File(modelsDir, "m.bin").writeBytes(existing)

        val goodChecksum = sha256Hex(newContent)
        val tracking = TrackingFactory(fakeHttp(body = newContent))
        val downloader = makeDownloader(tracking)
        val spec = ModelDownloader.DownloadSpec("m", "http://fake/m.bin", "m.bin", goodChecksum)

        val result = runBlocking { downloader.downloadSync(spec) {} }

        assertTrue("Must succeed after re-download", result)
        assertTrue("HTTP connection must be opened for corrupted file", tracking.opened)
        assertArrayEquals("File must be replaced with fresh content", newContent, File(modelsDir, "m.bin").readBytes())
    }

    // ── Progress events ───────────────────────────────────────────────────────

    @Test
    fun `reports Progress events during download`() {
        val content = ByteArray(20_000) { it.toByte() }
        val downloader = makeDownloader(fakeHttp(body = content, contentLength = content.size.toLong()))
        val spec = ModelDownloader.DownloadSpec("m", "http://fake/m.bin", "m.bin")

        val progressEvents = mutableListOf<ModelDownloader.DownloadStatus.Progress>()
        runBlocking {
            downloader.downloadSync(spec) {
                if (it is ModelDownloader.DownloadStatus.Progress) progressEvents.add(it)
            }
        }

        assertTrue("At least one Progress event expected", progressEvents.isNotEmpty())
        val last = progressEvents.last()
        assertEquals("Final downloaded count must match content size", content.size.toLong(), last.bytesDownloaded)
        assertEquals("Total bytes must match content-length", content.size.toLong(), last.totalBytes)
    }

    @Test
    fun `progress modelId matches spec modelId`() {
        val content = "bytes".toByteArray()
        val downloader = makeDownloader(fakeHttp(body = content, contentLength = content.size.toLong()))
        val spec = ModelDownloader.DownloadSpec("check_id", "http://fake/f.bin", "f.bin")

        val ids = mutableSetOf<String>()
        runBlocking {
            downloader.downloadSync(spec) {
                when (it) {
                    is ModelDownloader.DownloadStatus.Progress -> ids.add(it.modelId)
                    is ModelDownloader.DownloadStatus.Success -> ids.add(it.modelId)
                    is ModelDownloader.DownloadStatus.Failure -> ids.add(it.modelId)
                }
            }
        }

        assertTrue("All status events must carry the spec's modelId", ids.all { it == "check_id" })
    }

    // ── enqueueDownload (async fire-and-forget) ───────────────────────────────

    @Test
    fun `enqueueDownload writes file asynchronously`() {
        val content = "async weights".toByteArray()
        val downloader = makeDownloader(fakeHttp(body = content))
        val spec = ModelDownloader.DownloadSpec("async", "http://fake/a.bin", "a.bin")

        var success: ModelDownloader.DownloadStatus.Success? = null
        downloader.enqueueDownload(spec) {
            if (it is ModelDownloader.DownloadStatus.Success) success = it
        }

        // Allow background coroutine to complete (500 ms is generous for in-memory I/O).
        Thread.sleep(500)
        assertNotNull("enqueueDownload must eventually report Success", success)
        assertTrue("File must exist after async download", File(modelsDir, "a.bin").exists())
    }

    // ── Temp file cleanup ─────────────────────────────────────────────────────

    @Test
    fun `no temp file left after successful download`() {
        val content = "ok".toByteArray()
        val downloader = makeDownloader(fakeHttp(body = content))
        val spec = ModelDownloader.DownloadSpec("m", "http://fake/m.bin", "m.bin")

        runBlocking { downloader.downloadSync(spec) {} }

        assertFalse("Temp file must be removed after success", File(modelsDir, "m.bin.tmp").exists())
    }

    @Test
    fun `no temp file left after checksum failure`() {
        val content = "bad".toByteArray()
        val downloader = makeDownloader(fakeHttp(body = content))
        val spec = ModelDownloader.DownloadSpec("m", "http://fake/m.bin", "m.bin", "a".repeat(64))

        runBlocking { downloader.downloadSync(spec) {} }

        assertFalse("Temp file must be removed after checksum failure", File(modelsDir, "m.bin.tmp").exists())
    }
}
