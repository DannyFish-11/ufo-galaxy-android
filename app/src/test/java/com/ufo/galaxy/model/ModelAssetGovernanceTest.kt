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
 * Automated verification tests for the model asset governance system.
 *
 * Covers the five required governance scenarios:
 *  1. **Provision success** — full pipeline reaches [ProvisioningResult.Success] and model is LOADED.
 *  2. **Checksum failure** — [ModelDownloader] checksum mismatch surfaces as
 *     [ProvisioningResult.Failure.ChecksumMismatch]; no file left on disk.
 *  3. **Interrupted download recovery** — a leftover `.tmp` file from a prior run is cleaned up
 *     automatically and a fresh download attempt proceeds.
 *  4. **Cleanup policy** — [ModelAssetManager.cleanupStaleFiles] removes `.tmp` residues and
 *     unrecognised files; recognised files are preserved.
 *  5. **Compatibility rejection** — a manifest with [ModelManifest.minRuntimeVersion] above the
 *     current runtime version produces [ProvisioningResult.Failure.IncompatibleAsset] without
 *     issuing any network request.
 *
 * Additional tests cover storage governance: [ModelAssetManager.storageUsageBytes],
 * [ModelAssetManager.evictForStorage], [ModelAssetManager.fileFor], and rollback semantics
 * from [ModelProvisioningPipeline.rollback].
 *
 * All tests are pure JVM — no Android SDK or real network access required.
 */
class ModelAssetGovernanceTest {

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

    /** Factory that counts how many HTTP connections were opened. */
    private class CountingFactory(
        private val delegate: ModelDownloader.HttpFactory
    ) : ModelDownloader.HttpFactory {
        var openCount = 0
        override fun open(url: String): HttpURLConnection {
            openCount++
            return delegate.open(url)
        }
    }

    private fun makeDownloader(factory: ModelDownloader.HttpFactory) =
        ModelDownloader(modelsDir, httpFactory = factory)

    private fun makePipeline(factory: ModelDownloader.HttpFactory) =
        ModelProvisioningPipeline(mam, makeDownloader(factory))

    // ── 1. Provision success ──────────────────────────────────────────────────

    @Test
    fun `provision success returns ProvisioningResult Success`() = runBlocking {
        val body = "mobilevlm weights content".toByteArray()
        val pipeline = makePipeline(fakeHttp(body = body))
        val spec = ModelDownloader.DownloadSpec(
            modelId = ModelAssetManager.MODEL_ID_MOBILEVLM,
            url = "http://fake.local/model.gguf",
            fileName = ModelAssetManager.MOBILEVLM_FILE
        )

        val result = pipeline.provision(spec)

        assertTrue("Provision must succeed", result is ProvisioningResult.Success)
        val success = result as ProvisioningResult.Success
        assertEquals(ModelAssetManager.MODEL_ID_MOBILEVLM, success.modelId)
        assertTrue("Installed file must exist", success.installedFile.exists())
    }

    @Test
    fun `provision success activates the model in ModelAssetManager`() = runBlocking {
        val body = "weights".toByteArray()
        val pipeline = makePipeline(fakeHttp(body = body))
        val spec = ModelDownloader.DownloadSpec(
            modelId = ModelAssetManager.MODEL_ID_MOBILEVLM,
            url = "http://fake.local/model.gguf",
            fileName = ModelAssetManager.MOBILEVLM_FILE
        )

        pipeline.provision(spec)

        assertEquals(
            "Model must be LOADED after successful provision",
            ModelAssetManager.ModelStatus.LOADED,
            mam.getStatus(ModelAssetManager.MODEL_ID_MOBILEVLM)
        )
    }

    @Test
    fun `provision is idempotent when model is already LOADED`() = runBlocking {
        // Pre-mark model as loaded without touching the network.
        mam.markLoaded(ModelAssetManager.MODEL_ID_MOBILEVLM)
        val counting = CountingFactory(fakeHttp())
        val pipeline = makePipeline(counting)
        val spec = ModelDownloader.DownloadSpec(
            modelId = ModelAssetManager.MODEL_ID_MOBILEVLM,
            url = "http://fake.local/model.gguf",
            fileName = ModelAssetManager.MOBILEVLM_FILE
        )

        val result = pipeline.provision(spec)

        assertTrue("Already-loaded model must yield Success", result is ProvisioningResult.Success)
        assertEquals("No HTTP request must be made for already-loaded model", 0, counting.openCount)
    }

    @Test
    fun `provision emits progress events during download`() = runBlocking {
        val body = ByteArray(16_384) { it.toByte() }
        val pipeline = makePipeline(fakeHttp(body = body))
        val spec = ModelDownloader.DownloadSpec(
            modelId = ModelAssetManager.MODEL_ID_MOBILEVLM,
            url = "http://fake.local/model.gguf",
            fileName = ModelAssetManager.MOBILEVLM_FILE
        )
        val progressEvents = mutableListOf<ModelDownloader.DownloadStatus.Progress>()

        pipeline.provision(spec) { progressEvents.add(it) }

        assertTrue("At least one Progress event expected", progressEvents.isNotEmpty())
        assertEquals(
            "Progress events must carry the correct modelId",
            ModelAssetManager.MODEL_ID_MOBILEVLM,
            progressEvents.first().modelId
        )
    }

    // ── 2. Checksum failure ───────────────────────────────────────────────────

    @Test
    fun `provision checksum failure returns ChecksumMismatch`() = runBlocking {
        val body = "real model bytes".toByteArray()
        val wrongChecksum = "a".repeat(64)  // intentionally wrong SHA-256
        val pipeline = makePipeline(fakeHttp(body = body))
        val spec = ModelDownloader.DownloadSpec(
            modelId = ModelAssetManager.MODEL_ID_MOBILEVLM,
            url = "http://fake.local/model.gguf",
            fileName = ModelAssetManager.MOBILEVLM_FILE,
            expectedSha256 = wrongChecksum
        )

        val result = pipeline.provision(spec)

        assertTrue(
            "Checksum mismatch must produce Failure.ChecksumMismatch",
            result is ProvisioningResult.Failure.ChecksumMismatch
        )
        val failure = result as ProvisioningResult.Failure.ChecksumMismatch
        assertEquals(ModelAssetManager.MODEL_ID_MOBILEVLM, failure.modelId)
        assertEquals(wrongChecksum, failure.expected)
    }

    @Test
    fun `provision checksum failure leaves no file on disk`() = runBlocking {
        val body = "bytes".toByteArray()
        val pipeline = makePipeline(fakeHttp(body = body))
        val spec = ModelDownloader.DownloadSpec(
            modelId = ModelAssetManager.MODEL_ID_MOBILEVLM,
            url = "http://fake.local/model.gguf",
            fileName = ModelAssetManager.MOBILEVLM_FILE,
            expectedSha256 = "0".repeat(64)
        )

        pipeline.provision(spec)

        assertFalse(
            "Final model file must NOT be present after checksum failure",
            File(modelsDir, ModelAssetManager.MOBILEVLM_FILE).exists()
        )
        assertFalse(
            "Temp file must be cleaned up after checksum failure",
            File(modelsDir, "${ModelAssetManager.MOBILEVLM_FILE}.tmp").exists()
        )
    }

    @Test
    fun `provision checksum failure leaves model in MISSING state`() = runBlocking {
        val body = "bytes".toByteArray()
        val pipeline = makePipeline(fakeHttp(body = body))
        val spec = ModelDownloader.DownloadSpec(
            modelId = ModelAssetManager.MODEL_ID_MOBILEVLM,
            url = "http://fake.local/model.gguf",
            fileName = ModelAssetManager.MOBILEVLM_FILE,
            expectedSha256 = "0".repeat(64)
        )

        pipeline.provision(spec)

        assertNotEquals(
            "Model must not be LOADED after checksum failure",
            ModelAssetManager.ModelStatus.LOADED,
            mam.getStatus(ModelAssetManager.MODEL_ID_MOBILEVLM)
        )
    }

    @Test
    fun `provision download network error returns DownloadError`() = runBlocking {
        val pipeline = makePipeline(fakeHttp(responseCode = 503))
        val spec = ModelDownloader.DownloadSpec(
            modelId = ModelAssetManager.MODEL_ID_MOBILEVLM,
            url = "http://fake.local/model.gguf",
            fileName = ModelAssetManager.MOBILEVLM_FILE
        )

        val result = pipeline.provision(spec)

        assertTrue(
            "HTTP error must produce Failure.DownloadError",
            result is ProvisioningResult.Failure.DownloadError
        )
        val failure = result as ProvisioningResult.Failure.DownloadError
        assertEquals(ModelAssetManager.MODEL_ID_MOBILEVLM, failure.modelId)
    }

    // ── 3. Interrupted download recovery ─────────────────────────────────────

    @Test
    fun `provision removes leftover tmp file before starting download`() = runBlocking {
        // Simulate a partial download from a previous run.
        val staleContent = "incomplete download data".toByteArray()
        val tmpFile = File(modelsDir, "${ModelAssetManager.MOBILEVLM_FILE}.tmp")
        tmpFile.writeBytes(staleContent)
        assertTrue("Precondition: tmp file must exist before provision", tmpFile.exists())

        val freshBody = "complete model weights".toByteArray()
        val pipeline = makePipeline(fakeHttp(body = freshBody))
        val spec = ModelDownloader.DownloadSpec(
            modelId = ModelAssetManager.MODEL_ID_MOBILEVLM,
            url = "http://fake.local/model.gguf",
            fileName = ModelAssetManager.MOBILEVLM_FILE
        )

        val result = pipeline.provision(spec)

        assertTrue("Provision after partial-asset cleanup must succeed", result is ProvisioningResult.Success)
        assertFalse("Tmp file must be gone after successful provision", tmpFile.exists())
        assertTrue(
            "Fresh model file must be present after recovery",
            File(modelsDir, ModelAssetManager.MOBILEVLM_FILE).exists()
        )
    }

    @Test
    fun `provision recovers from tmp file and activates model`() = runBlocking {
        // Place a stale .tmp file to simulate an interrupted prior download.
        File(modelsDir, "${ModelAssetManager.MOBILEVLM_FILE}.tmp").writeBytes("partial".toByteArray())

        val freshBody = "full weights".toByteArray()
        val pipeline = makePipeline(fakeHttp(body = freshBody))
        val spec = ModelDownloader.DownloadSpec(
            modelId = ModelAssetManager.MODEL_ID_MOBILEVLM,
            url = "http://fake.local/model.gguf",
            fileName = ModelAssetManager.MOBILEVLM_FILE
        )

        pipeline.provision(spec)

        assertEquals(
            "Model must be LOADED after recovery",
            ModelAssetManager.ModelStatus.LOADED,
            mam.getStatus(ModelAssetManager.MODEL_ID_MOBILEVLM)
        )
    }

    // ── 4. Cleanup policy ─────────────────────────────────────────────────────

    @Test
    fun `cleanupStaleFiles removes tmp residues`() {
        File(modelsDir, "${ModelAssetManager.MOBILEVLM_FILE}.tmp").writeBytes("partial".toByteArray())
        File(modelsDir, "${ModelAssetManager.SEECLICK_PARAM_FILE}.tmp").writeBytes("partial2".toByteArray())

        val removed = mam.cleanupStaleFiles()

        assertEquals("Both tmp files must be removed", 2, removed)
        assertFalse(File(modelsDir, "${ModelAssetManager.MOBILEVLM_FILE}.tmp").exists())
        assertFalse(File(modelsDir, "${ModelAssetManager.SEECLICK_PARAM_FILE}.tmp").exists())
    }

    @Test
    fun `cleanupStaleFiles removes unrecognised orphan files`() {
        File(modelsDir, "orphan_model.bin").writeBytes("unknown asset".toByteArray())
        File(modelsDir, "old_model.gguf").writeBytes("deprecated model".toByteArray())

        val removed = mam.cleanupStaleFiles()

        assertEquals("Both orphan files must be removed", 2, removed)
        assertFalse(File(modelsDir, "orphan_model.bin").exists())
        assertFalse(File(modelsDir, "old_model.gguf").exists())
    }

    @Test
    fun `cleanupStaleFiles preserves recognised model files`() {
        File(modelsDir, ModelAssetManager.MOBILEVLM_FILE).writeBytes("weights".toByteArray())
        File(modelsDir, ModelAssetManager.SEECLICK_PARAM_FILE).writeBytes("param".toByteArray())
        File(modelsDir, ModelAssetManager.SEECLICK_BIN_FILE).writeBytes("bin".toByteArray())

        val removed = mam.cleanupStaleFiles()

        assertEquals("No recognised files must be removed", 0, removed)
        assertTrue(File(modelsDir, ModelAssetManager.MOBILEVLM_FILE).exists())
        assertTrue(File(modelsDir, ModelAssetManager.SEECLICK_PARAM_FILE).exists())
        assertTrue(File(modelsDir, ModelAssetManager.SEECLICK_BIN_FILE).exists())
    }

    @Test
    fun `cleanupStaleFiles returns zero when directory is empty`() {
        val removed = mam.cleanupStaleFiles()
        assertEquals(0, removed)
    }

    @Test
    fun `cleanupStaleFiles removes mix of tmp and orphan files while preserving valid files`() {
        File(modelsDir, ModelAssetManager.MOBILEVLM_FILE).writeBytes("vlm".toByteArray())
        File(modelsDir, "junk.bin").writeBytes("junk".toByteArray())
        File(modelsDir, "${ModelAssetManager.SEECLICK_PARAM_FILE}.tmp").writeBytes("partial".toByteArray())

        val removed = mam.cleanupStaleFiles()

        assertEquals("Exactly 2 stale files must be removed", 2, removed)
        assertTrue("Valid vlm file must survive cleanup", File(modelsDir, ModelAssetManager.MOBILEVLM_FILE).exists())
    }

    // ── 5. Compatibility rejection ────────────────────────────────────────────

    @Test
    fun `provision rejects incompatible manifest without downloading`() = runBlocking {
        val counting = CountingFactory(fakeHttp())
        val pipeline = makePipeline(counting)
        val spec = ModelDownloader.DownloadSpec(
            modelId = "test_model",
            url = "http://fake.local/model.gguf",
            fileName = "model.gguf"
        )
        val manifest = ModelManifest(
            modelId = "test_model",
            modelVersion = "1.0",
            runtimeType = ModelManifest.RuntimeType.LLAMA_CPP,
            minRuntimeVersion = "99.0"  // far above any realistic runtime version
        )

        val result = pipeline.provision(spec, manifest = manifest, runtimeVersion = "1.0")

        assertTrue(
            "Incompatible manifest must produce Failure.IncompatibleAsset",
            result is ProvisioningResult.Failure.IncompatibleAsset
        )
        assertEquals(
            "No HTTP request must be made for incompatible asset",
            0, counting.openCount
        )
    }

    @Test
    fun `provision IncompatibleAsset failure carries reason`() = runBlocking {
        val pipeline = makePipeline(fakeHttp())
        val spec = ModelDownloader.DownloadSpec(
            modelId = "m",
            url = "http://fake.local/m.bin",
            fileName = "m.bin"
        )
        val manifest = ModelManifest(
            modelId = "m",
            modelVersion = "1.0",
            runtimeType = ModelManifest.RuntimeType.NCNN,
            minRuntimeVersion = "5.0"
        )

        val result = pipeline.provision(spec, manifest = manifest, runtimeVersion = "2.0")
        val failure = result as ProvisioningResult.Failure.IncompatibleAsset

        assertFalse("IncompatibleAsset reason must be non-empty", failure.reason.isEmpty())
    }

    @Test
    fun `provision proceeds when manifest compatibility is Unknown (null runtimeVersion)`() = runBlocking {
        val body = "weights".toByteArray()
        val pipeline = makePipeline(fakeHttp(body = body))
        val spec = ModelDownloader.DownloadSpec(
            modelId = ModelAssetManager.MODEL_ID_MOBILEVLM,
            url = "http://fake.local/model.gguf",
            fileName = ModelAssetManager.MOBILEVLM_FILE
        )
        val manifest = ModelManifest(
            modelId = ModelAssetManager.MODEL_ID_MOBILEVLM,
            modelVersion = "1.0",
            runtimeType = ModelManifest.RuntimeType.LLAMA_CPP,
            minRuntimeVersion = "2.0"
        )

        // runtimeVersion = null → Unknown → pipeline must proceed, not reject
        val result = pipeline.provision(spec, manifest = manifest, runtimeVersion = null)

        assertTrue(
            "Unknown compatibility must not block provisioning",
            result is ProvisioningResult.Success
        )
    }

    @Test
    fun `provision proceeds when manifest is null (no compatibility check)`() = runBlocking {
        val body = "weights".toByteArray()
        val pipeline = makePipeline(fakeHttp(body = body))
        val spec = ModelDownloader.DownloadSpec(
            modelId = ModelAssetManager.MODEL_ID_MOBILEVLM,
            url = "http://fake.local/model.gguf",
            fileName = ModelAssetManager.MOBILEVLM_FILE
        )

        val result = pipeline.provision(spec, manifest = null)

        assertTrue(result is ProvisioningResult.Success)
    }

    // ── Storage governance: fileFor ────────────────────────────────────────────

    @Test
    fun `fileFor returns non-null file for known model ids`() {
        assertNotNull(mam.fileFor(ModelAssetManager.MODEL_ID_MOBILEVLM))
        assertNotNull(mam.fileFor(ModelAssetManager.MODEL_ID_SEECLICK))
        assertNotNull(mam.fileFor(ModelAssetManager.MODEL_ID_SEECLICK_BIN))
    }

    @Test
    fun `fileFor returns null for unknown model id`() {
        assertNull(mam.fileFor("not_a_real_model"))
    }

    @Test
    fun `fileFor returns file inside modelsDir`() {
        val file = mam.fileFor(ModelAssetManager.MODEL_ID_MOBILEVLM)!!
        assertEquals(modelsDir.absolutePath, file.parentFile?.absolutePath)
    }

    // ── Storage governance: storageUsageBytes ─────────────────────────────────

    @Test
    fun `storageUsageBytes returns zero when no model files present`() {
        assertEquals(0L, mam.storageUsageBytes())
    }

    @Test
    fun `storageUsageBytes reflects size of present model files`() {
        val content = "1234567890".toByteArray()
        File(modelsDir, ModelAssetManager.MOBILEVLM_FILE).writeBytes(content)

        val usage = mam.storageUsageBytes()
        assertEquals("Usage must match written file size", content.size.toLong(), usage)
    }

    @Test
    fun `storageUsageBytes sums all present model files`() {
        val vlmBytes = "vlm_weights".toByteArray()
        val paramBytes = "sc_param".toByteArray()
        File(modelsDir, ModelAssetManager.MOBILEVLM_FILE).writeBytes(vlmBytes)
        File(modelsDir, ModelAssetManager.SEECLICK_PARAM_FILE).writeBytes(paramBytes)

        val expected = vlmBytes.size.toLong() + paramBytes.size.toLong()
        assertEquals(expected, mam.storageUsageBytes())
    }

    // ── Storage governance: evictForStorage ───────────────────────────────────

    @Test
    fun `evictForStorage returns empty list when no READY models exist`() {
        // No files written → no READY models to evict.
        val evicted = mam.evictForStorage(requiredBytes = 1L)
        assertTrue("Nothing to evict when no files present", evicted.isEmpty())
    }

    @Test
    fun `evictForStorage removes READY model file from disk`() {
        File(modelsDir, ModelAssetManager.MOBILEVLM_FILE).writeBytes("weights".toByteArray())
        mam.verifyModel(ModelAssetManager.MODEL_ID_MOBILEVLM)  // puts it in READY state

        mam.evictForStorage(requiredBytes = Long.MAX_VALUE)

        assertFalse(
            "READY model file must be deleted by eviction",
            File(modelsDir, ModelAssetManager.MOBILEVLM_FILE).exists()
        )
    }

    @Test
    fun `evictForStorage transitions model to MISSING after removal`() {
        File(modelsDir, ModelAssetManager.MOBILEVLM_FILE).writeBytes("weights".toByteArray())
        mam.verifyModel(ModelAssetManager.MODEL_ID_MOBILEVLM)

        mam.evictForStorage(requiredBytes = Long.MAX_VALUE)

        assertEquals(
            "Evicted model must transition to MISSING",
            ModelAssetManager.ModelStatus.MISSING,
            mam.getStatus(ModelAssetManager.MODEL_ID_MOBILEVLM)
        )
    }

    @Test
    fun `evictForStorage does not evict LOADED models`() {
        File(modelsDir, ModelAssetManager.MOBILEVLM_FILE).writeBytes("weights".toByteArray())
        mam.markLoaded(ModelAssetManager.MODEL_ID_MOBILEVLM)  // LOADED, not READY

        mam.evictForStorage(requiredBytes = Long.MAX_VALUE)

        assertTrue(
            "LOADED model file must NOT be evicted",
            File(modelsDir, ModelAssetManager.MOBILEVLM_FILE).exists()
        )
        assertEquals(
            "LOADED status must be preserved",
            ModelAssetManager.ModelStatus.LOADED,
            mam.getStatus(ModelAssetManager.MODEL_ID_MOBILEVLM)
        )
    }

    @Test
    fun `evictForStorage returns list of evicted model ids`() {
        File(modelsDir, ModelAssetManager.MOBILEVLM_FILE).writeBytes("w".toByteArray())
        mam.verifyModel(ModelAssetManager.MODEL_ID_MOBILEVLM)

        val evicted = mam.evictForStorage(requiredBytes = Long.MAX_VALUE)

        assertTrue(
            "Evicted list must include the removed model id",
            evicted.contains(ModelAssetManager.MODEL_ID_MOBILEVLM)
        )
    }

    // ── Rollback semantics ────────────────────────────────────────────────────

    @Test
    fun `rollback without deleteFile reverts LOADED model to READY when file is present`() {
        File(modelsDir, ModelAssetManager.MOBILEVLM_FILE).writeBytes("weights".toByteArray())
        mam.markLoaded(ModelAssetManager.MODEL_ID_MOBILEVLM)

        val pipeline = makePipeline(fakeHttp())
        pipeline.rollback(ModelAssetManager.MODEL_ID_MOBILEVLM, deleteFile = false)

        assertEquals(
            "Model must be READY after rollback without deleteFile",
            ModelAssetManager.ModelStatus.READY,
            mam.getStatus(ModelAssetManager.MODEL_ID_MOBILEVLM)
        )
        assertTrue(
            "Installed file must still exist after rollback without deleteFile",
            File(modelsDir, ModelAssetManager.MOBILEVLM_FILE).exists()
        )
    }

    @Test
    fun `rollback with deleteFile removes model file and marks MISSING`() {
        File(modelsDir, ModelAssetManager.MOBILEVLM_FILE).writeBytes("weights".toByteArray())
        mam.markLoaded(ModelAssetManager.MODEL_ID_MOBILEVLM)

        val pipeline = makePipeline(fakeHttp())
        pipeline.rollback(ModelAssetManager.MODEL_ID_MOBILEVLM, deleteFile = true)

        assertFalse(
            "Installed file must be removed after rollback with deleteFile=true",
            File(modelsDir, ModelAssetManager.MOBILEVLM_FILE).exists()
        )
        assertEquals(
            "Model must be MISSING after rollback with deleteFile=true",
            ModelAssetManager.ModelStatus.MISSING,
            mam.getStatus(ModelAssetManager.MODEL_ID_MOBILEVLM)
        )
    }

    // ── ProvisioningResult structure ──────────────────────────────────────────

    @Test
    fun `ProvisioningResult Success carries modelId and installedFile`() {
        val file = File(modelsDir, "model.bin")
        val result = ProvisioningResult.Success(modelId = "m", installedFile = file)
        assertEquals("m", result.modelId)
        assertEquals(file, result.installedFile)
    }

    @Test
    fun `ProvisioningResult Failure ChecksumMismatch carries expected and actual`() {
        val result = ProvisioningResult.Failure.ChecksumMismatch(
            modelId = "m", expected = "abc", actual = "def"
        )
        assertEquals("abc", result.expected)
        assertEquals("def", result.actual)
    }

    @Test
    fun `ProvisioningResult Failure IncompatibleAsset carries reason`() {
        val result = ProvisioningResult.Failure.IncompatibleAsset(
            modelId = "m", reason = "version too old"
        )
        assertFalse(result.reason.isEmpty())
    }

    @Test
    fun `ProvisioningResult Failure PartialAsset carries cause`() {
        val result = ProvisioningResult.Failure.PartialAsset(
            modelId = "m", cause = "network interrupted"
        )
        assertFalse(result.cause.isEmpty())
    }

    @Test
    fun `ProvisioningResult Failure ActivationError carries cause`() {
        val result = ProvisioningResult.Failure.ActivationError(
            modelId = "m", cause = "runtime refused"
        )
        assertFalse(result.cause.isEmpty())
    }
}
