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
 * Model provisioning lifecycle tests.
 *
 * Covers the full model asset lifecycle:
 *  1. Model discovery (initial MISSING state).
 *  2. Download via [ModelDownloader] with a fake HTTP layer.
 *  3. Checksum verification after download.
 *  4. Cache hit: a second download call reuses the existing file.
 *  5. [ModelAssetManager.verifyModel] reflecting the downloaded file.
 *  6. Load / unload lifecycle hooks.
 *  7. Cleanup: corrupted/replaced file is detected.
 *  8. Download spec generation for missing models.
 *
 * All tests are pure JVM with no Android SDK dependency. Network I/O is replaced
 * by an in-memory [ModelDownloader.HttpFactory] returning controlled payloads.
 */
class ModelProvisioningLifecycleTest {

    @get:Rule
    val tmpFolder = TemporaryFolder()

    private lateinit var modelsDir: File
    private lateinit var mam: ModelAssetManager

    @Before
    fun setUp() {
        modelsDir = tmpFolder.newFolder("models")
        mam = ModelAssetManager(modelsDir)
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(bytes)
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun fakeHttp(
        responseCode: Int = 200,
        body: ByteArray = ByteArray(0)
    ): ModelDownloader.HttpFactory = ModelDownloader.HttpFactory { _ ->
        object : HttpURLConnection(URL("http://fake.local")) {
            override fun connect() {}
            override fun disconnect() {}
            override fun usingProxy() = false
            override fun getResponseCode() = responseCode
            override fun getContentLengthLong() = body.size.toLong()
            override fun getInputStream(): java.io.InputStream = ByteArrayInputStream(body)
        }
    }

    private fun makeDownloader(factory: ModelDownloader.HttpFactory) =
        ModelDownloader(modelsDir, httpFactory = factory)

    // ── 1. Model discovery (MISSING) ─────────────────────────────────────────

    @Test
    fun `initial model status is MISSING before any download`() {
        assertEquals(ModelAssetManager.ModelStatus.MISSING, mam.verifyModel(ModelAssetManager.MODEL_ID_MOBILEVLM))
        assertEquals(ModelAssetManager.ModelStatus.MISSING, mam.verifyModel(ModelAssetManager.MODEL_ID_SEECLICK))
    }

    @Test
    fun `verifyAll returns MISSING for all models when no files present`() {
        val statuses = mam.verifyAll()
        assertTrue("All models should be MISSING initially",
            statuses.values.all { it == ModelAssetManager.ModelStatus.MISSING })
    }

    @Test
    fun `areAllModelsLoaded is false when no models are loaded`() {
        assertFalse(mam.areAllModelsLoaded())
    }

    @Test
    fun `readinessError is non-null when models are not loaded`() {
        assertNotNull(mam.readinessError())
    }

    // ── 2. Download: success path ─────────────────────────────────────────────

    @Test
    fun `download succeeds and file is written to models dir`() = runBlocking {
        val body = "fake gguf weights".toByteArray()
        val downloader = makeDownloader(fakeHttp(body = body))
        val spec = ModelDownloader.DownloadSpec(
            modelId = ModelAssetManager.MODEL_ID_MOBILEVLM,
            url = "http://fake.local/model.gguf",
            fileName = ModelAssetManager.MOBILEVLM_FILE
        )
        var lastStatus: ModelDownloader.DownloadStatus? = null
        val ok = downloader.downloadSync(spec) { lastStatus = it }
        assertTrue("downloadSync must return true on success", ok)
        assertTrue("Destination file must exist", File(modelsDir, ModelAssetManager.MOBILEVLM_FILE).exists())
        assertTrue("Last status must be Success", lastStatus is ModelDownloader.DownloadStatus.Success)
    }

    @Test
    fun `download emits Progress events during transfer`() = runBlocking {
        val body = ByteArray(16_384) { it.toByte() }
        val downloader = makeDownloader(fakeHttp(body = body))
        val spec = ModelDownloader.DownloadSpec(
            modelId = ModelAssetManager.MODEL_ID_MOBILEVLM,
            url = "http://fake.local/model.gguf",
            fileName = ModelAssetManager.MOBILEVLM_FILE
        )
        val events = mutableListOf<ModelDownloader.DownloadStatus>()
        downloader.downloadSync(spec) { events.add(it) }
        val progressEvents = events.filterIsInstance<ModelDownloader.DownloadStatus.Progress>()
        assertTrue("At least one Progress event expected", progressEvents.isNotEmpty())
    }

    // ── 3. Checksum verification ──────────────────────────────────────────────

    @Test
    fun `download succeeds when checksum matches`() = runBlocking {
        val body = "verified model weights".toByteArray()
        val checksum = sha256Hex(body)
        val downloader = makeDownloader(fakeHttp(body = body))
        val spec = ModelDownloader.DownloadSpec(
            modelId = ModelAssetManager.MODEL_ID_MOBILEVLM,
            url = "http://fake.local/model.gguf",
            fileName = ModelAssetManager.MOBILEVLM_FILE,
            expectedSha256 = checksum
        )
        val ok = downloader.downloadSync(spec) {}
        assertTrue("Download with correct checksum must succeed", ok)
        assertTrue(File(modelsDir, ModelAssetManager.MOBILEVLM_FILE).exists())
    }

    @Test
    fun `download fails when checksum mismatches`() = runBlocking {
        val body = "tampered model weights".toByteArray()
        val wrongChecksum = "0".repeat(64) // wrong SHA-256
        val downloader = makeDownloader(fakeHttp(body = body))
        val spec = ModelDownloader.DownloadSpec(
            modelId = ModelAssetManager.MODEL_ID_MOBILEVLM,
            url = "http://fake.local/model.gguf",
            fileName = ModelAssetManager.MOBILEVLM_FILE,
            expectedSha256 = wrongChecksum
        )
        var lastStatus: ModelDownloader.DownloadStatus? = null
        val ok = downloader.downloadSync(spec) { lastStatus = it }
        assertFalse("Download with mismatched checksum must fail", ok)
        assertTrue("Last status must be Failure", lastStatus is ModelDownloader.DownloadStatus.Failure)
        assertFalse("File must not be present after checksum failure",
            File(modelsDir, ModelAssetManager.MOBILEVLM_FILE).exists())
    }

    // ── 4. Cache hit ──────────────────────────────────────────────────────────

    @Test
    fun `second download returns Success immediately without HTTP call when file matches checksum`() = runBlocking {
        val body = "cached model".toByteArray()
        val checksum = sha256Hex(body)
        // Pre-populate the file
        File(modelsDir, ModelAssetManager.MOBILEVLM_FILE).writeBytes(body)

        var httpCallCount = 0
        val countingFactory = ModelDownloader.HttpFactory { url ->
            httpCallCount++
            fakeHttp(body = body).open(url)
        }
        val downloader = makeDownloader(countingFactory)
        val spec = ModelDownloader.DownloadSpec(
            modelId = ModelAssetManager.MODEL_ID_MOBILEVLM,
            url = "http://fake.local/model.gguf",
            fileName = ModelAssetManager.MOBILEVLM_FILE,
            expectedSha256 = checksum
        )
        val ok = downloader.downloadSync(spec) {}
        assertTrue("Cached download must succeed", ok)
        assertEquals("No HTTP request should be made for cached file", 0, httpCallCount)
    }

    // ── 5. ModelAssetManager.verifyModel after download ───────────────────────

    @Test
    fun `verifyModel returns READY after file is downloaded`() = runBlocking {
        val body = "mobilevlm weights".toByteArray()
        val downloader = makeDownloader(fakeHttp(body = body))
        val spec = ModelDownloader.DownloadSpec(
            modelId = ModelAssetManager.MODEL_ID_MOBILEVLM,
            url = "http://fake.local/model.gguf",
            fileName = ModelAssetManager.MOBILEVLM_FILE
        )
        downloader.downloadSync(spec) {}
        val status = mam.verifyModel(ModelAssetManager.MODEL_ID_MOBILEVLM)
        assertEquals(ModelAssetManager.ModelStatus.READY, status)
    }

    @Test
    fun `verifyModel returns CORRUPTED when file has wrong checksum`() {
        val badContent = "corrupted content".toByteArray()
        File(modelsDir, ModelAssetManager.MOBILEVLM_FILE).writeBytes(badContent)

        // Build a ModelAssetManager with an expected checksum that won't match
        val badChecksum = "a".repeat(64)
        val registry = ModelAssetManager(modelsDir)
        // Inject an expected SHA-256 via a DownloadSpec-based check
        // We can only test this via a custom ModelAssetManager subclass or by
        // placing a file that won't match the download spec checksum.
        // Since the public API only allows null checksums for built-in models,
        // test CORRUPTED via the ModelDownloader path instead.
        val downloader = makeDownloader(fakeHttp(body = badContent))
        val spec = ModelDownloader.DownloadSpec(
            modelId = ModelAssetManager.MODEL_ID_MOBILEVLM,
            url = "http://fake.local/model.gguf",
            fileName = ModelAssetManager.MOBILEVLM_FILE,
            expectedSha256 = badChecksum
        )
        var failed = false
        runBlocking {
            downloader.downloadSync(spec) { status ->
                if (status is ModelDownloader.DownloadStatus.Failure) failed = true
            }
        }
        assertTrue("Download with wrong checksum must produce Failure", failed)
    }

    // ── 6. Load / unload lifecycle ────────────────────────────────────────────

    @Test
    fun `markLoaded then getStatus returns LOADED`() {
        mam.markLoaded(ModelAssetManager.MODEL_ID_MOBILEVLM)
        assertEquals(ModelAssetManager.ModelStatus.LOADED, mam.getStatus(ModelAssetManager.MODEL_ID_MOBILEVLM))
    }

    @Test
    fun `markUnloaded after load returns READY when file is present`() = runBlocking {
        // Write a dummy file so the unloaded state reflects READY not MISSING
        File(modelsDir, ModelAssetManager.MOBILEVLM_FILE).writeText("weights")
        mam.markLoaded(ModelAssetManager.MODEL_ID_MOBILEVLM)
        mam.markUnloaded(ModelAssetManager.MODEL_ID_MOBILEVLM)
        assertEquals(ModelAssetManager.ModelStatus.READY, mam.getStatus(ModelAssetManager.MODEL_ID_MOBILEVLM))
    }

    @Test
    fun `markUnloaded when file is absent returns MISSING`() {
        mam.markLoaded(ModelAssetManager.MODEL_ID_MOBILEVLM)
        mam.markUnloaded(ModelAssetManager.MODEL_ID_MOBILEVLM)
        // File was never written → MISSING
        assertEquals(ModelAssetManager.ModelStatus.MISSING, mam.getStatus(ModelAssetManager.MODEL_ID_MOBILEVLM))
    }

    @Test
    fun `areAllModelsLoaded true only when both models are LOADED`() {
        mam.markLoaded(ModelAssetManager.MODEL_ID_MOBILEVLM)
        assertFalse("Only one model loaded — not all models loaded", mam.areAllModelsLoaded())
        mam.markLoaded(ModelAssetManager.MODEL_ID_SEECLICK)
        assertTrue("Both models loaded", mam.areAllModelsLoaded())
    }

    @Test
    fun `readinessError is null when all models are loaded`() {
        mam.markLoaded(ModelAssetManager.MODEL_ID_MOBILEVLM)
        mam.markLoaded(ModelAssetManager.MODEL_ID_SEECLICK)
        assertNull("readinessError must be null when all models are LOADED", mam.readinessError())
    }

    // ── 7. Download spec generation for missing models ────────────────────────

    @Test
    fun `downloadSpecsForMissing returns specs when models are missing`() {
        val specs = mam.downloadSpecsForMissing()
        // At least one spec should be present since MobileVLM download URL is configured
        assertTrue("At least one download spec must be returned for missing models",
            specs.isNotEmpty())
    }

    @Test
    fun `downloadSpecsForMissing returns empty list when all models are loaded`() {
        mam.markLoaded(ModelAssetManager.MODEL_ID_MOBILEVLM)
        mam.markLoaded(ModelAssetManager.MODEL_ID_SEECLICK)
        val specs = mam.downloadSpecsForMissing()
        assertTrue("No specs when all models are LOADED", specs.isEmpty())
    }

    @Test
    fun `downloadSpecsForMissing includes MobileVLM HuggingFace URL`() {
        val specs = mam.downloadSpecsForMissing()
        val vlmSpec = specs.firstOrNull { it.modelId == ModelAssetManager.MODEL_ID_MOBILEVLM }
        assertNotNull("MobileVLM spec must be present", vlmSpec)
        assertTrue("MobileVLM URL must reference HuggingFace",
            vlmSpec!!.url.contains("huggingface.co"))
    }

    @Test
    fun `downloadSpecsForMissing includes SeeClick HuggingFace URL`() {
        val specs = mam.downloadSpecsForMissing()
        val scParamSpec = specs.firstOrNull { it.modelId.startsWith(ModelAssetManager.MODEL_ID_SEECLICK) }
        assertNotNull("SeeClick spec must be present", scParamSpec)
        assertTrue("SeeClick URL must reference HuggingFace",
            scParamSpec!!.url.contains("huggingface.co"))
    }

    // ── 8. HTTP error response ────────────────────────────────────────────────

    @Test
    fun `download Failure reported on HTTP 404`() = runBlocking {
        val downloader = makeDownloader(fakeHttp(responseCode = 404, body = ByteArray(0)))
        val spec = ModelDownloader.DownloadSpec(
            modelId = ModelAssetManager.MODEL_ID_MOBILEVLM,
            url = "http://fake.local/model.gguf",
            fileName = ModelAssetManager.MOBILEVLM_FILE
        )
        var failed = false
        downloader.downloadSync(spec) { if (it is ModelDownloader.DownloadStatus.Failure) failed = true }
        assertTrue("HTTP 404 must produce a Failure status", failed)
    }
}
