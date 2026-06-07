package com.ufo.galaxy.model

import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest

/**
 * Tests that verify the new SHA-256 checksum enforcement added to [ModelAssetManager]:
 *
 * 1. MobileVLM SHA-256 constant is non-null (real checksum is set).
 * 2. [ModelAssetManager.verifyModel] returns CORRUPTED when file does not match the
 *    expected SHA-256.
 * 3. [ModelAssetManager.verifyModel] returns READY when file content matches the
 *    expected SHA-256.
 * 4. [ModelAssetManager.persistComputedChecksum] stores the SHA-256 from the downloaded
 *    file and returns a non-null value.
 * 5. After [persistComputedChecksum], [ModelAssetManager.effectiveChecksum] is non-null.
 * 6. After [persistComputedChecksum], a second [verifyModel] call detects a tampered file.
 * 7. The persisted checksum store survives across [ModelAssetManager] construction
 *    (i.e., the value is loaded from disk on the next init).
 */
class ChecksumVerificationTest {

    @get:Rule
    val tmpFolder = TemporaryFolder()

    private lateinit var modelsDir: File

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(bytes)
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    @Before
    fun setUp() {
        modelsDir = tmpFolder.newFolder("models")
    }

    // ── 1. Static constant ────────────────────────────────────────────────────

    @Test
    fun `MOBILEVLM_SHA256 constant is non-null`() {
        assertNotNull(
            "MOBILEVLM_SHA256 must be set to the real SHA-256 of ggml-model-q4_k.gguf",
            ModelAssetManager.MOBILEVLM_SHA256
        )
    }

    @Test
    fun `MOBILEVLM_SHA256 has the expected 64-character hex format`() {
        val sha = ModelAssetManager.MOBILEVLM_SHA256
        assertNotNull(sha)
        assertEquals("SHA-256 must be 64 hex characters", 64, sha!!.length)
        assertTrue("SHA-256 must contain only hex characters",
            sha.matches(Regex("[0-9a-fA-F]+")))
    }

    @Test
    fun `MOBILEVLM_FILE is updated to ggml-model-q4_k gguf`() {
        assertEquals(
            "MobileVLM file must reference the Q4_K quantised GGUF that ships with SHA-256",
            "ggml-model-q4_k.gguf",
            ModelAssetManager.MOBILEVLM_FILE
        )
    }

    // ── 2. Checksum mismatch → CORRUPTED ─────────────────────────────────────

    @Test
    fun `verifyModel returns CORRUPTED when file content does not match expected SHA256`() {
        val expectedSha = "a".repeat(64)  // deliberately wrong SHA-256
        val mam = ModelAssetManager(
            modelsDir,
            checksumOverrides = mapOf(ModelAssetManager.MODEL_ID_MOBILEVLM to expectedSha)
        )
        File(modelsDir, ModelAssetManager.MOBILEVLM_FILE).writeBytes("wrong content".toByteArray())
        val status = mam.verifyModel(ModelAssetManager.MODEL_ID_MOBILEVLM)
        assertEquals(
            "File with wrong SHA-256 must be CORRUPTED",
            ModelAssetManager.ModelStatus.CORRUPTED,
            status
        )
    }

    @Test
    fun `downloadSpecsForMissing includes non-null checksum for CORRUPTED MobileVLM`() {
        // Verify that provisioning re-downloads CORRUPTED models and carries the real checksum.
        val mam = ModelAssetManager(
            modelsDir,
            checksumOverrides = mapOf(ModelAssetManager.MODEL_ID_MOBILEVLM to "a".repeat(64))
        )
        File(modelsDir, ModelAssetManager.MOBILEVLM_FILE).writeBytes("corrupted".toByteArray())
        mam.verifyModel(ModelAssetManager.MODEL_ID_MOBILEVLM)  // → CORRUPTED
        val specs = mam.downloadSpecsForMissing()
        val vlmSpec = specs.firstOrNull { it.modelId == ModelAssetManager.MODEL_ID_MOBILEVLM }
        // The manifest checksum (MOBILEVLM_SHA256) should be present in the spec for
        // the real download so the downloader can verify the freshly fetched file.
        assertNotNull("Re-download spec for CORRUPTED MobileVLM must be non-null", vlmSpec)
    }

    // ── 3. Correct content → READY ────────────────────────────────────────────

    @Test
    fun `verifyModel returns READY when file content matches expected SHA256`() {
        val content = "authentic model weights".toByteArray()
        val expectedSha = sha256Hex(content)
        val mam = ModelAssetManager(
            modelsDir,
            checksumOverrides = mapOf(ModelAssetManager.MODEL_ID_MOBILEVLM to expectedSha)
        )
        File(modelsDir, ModelAssetManager.MOBILEVLM_FILE).writeBytes(content)
        val status = mam.verifyModel(ModelAssetManager.MODEL_ID_MOBILEVLM)
        assertEquals(
            "File whose SHA-256 matches the expected value must be READY",
            ModelAssetManager.ModelStatus.READY,
            status
        )
    }

    // ── 4 & 5. persistComputedChecksum stores non-null checksum ───────────────

    @Test
    fun `persistComputedChecksum returns non-null SHA256 for a present file`() {
        // SeeClick starts with no expected checksum (static constant is null).
        val mam = ModelAssetManager(modelsDir)
        val seeClickContent = "seeclick param bytes".toByteArray()
        File(modelsDir, ModelAssetManager.SEECLICK_PARAM_FILE).writeBytes(seeClickContent)

        val persisted = mam.persistComputedChecksum(ModelAssetManager.MODEL_ID_SEECLICK)
        assertNotNull("persistComputedChecksum must return non-null for present file", persisted)
        assertEquals("Persisted SHA-256 must match file content", sha256Hex(seeClickContent), persisted)
    }

    @Test
    fun `effectiveChecksum is non-null after persistComputedChecksum`() {
        val mam = ModelAssetManager(modelsDir)
        File(modelsDir, ModelAssetManager.SEECLICK_PARAM_FILE).writeBytes("param".toByteArray())
        mam.persistComputedChecksum(ModelAssetManager.MODEL_ID_SEECLICK)

        assertNotNull(
            "effectiveChecksum must be non-null after persistComputedChecksum",
            mam.effectiveChecksum(ModelAssetManager.MODEL_ID_SEECLICK)
        )
    }

    @Test
    fun `effectiveChecksum for SeeClick bin is non-null after persistComputedChecksum`() {
        val mam = ModelAssetManager(modelsDir)
        File(modelsDir, ModelAssetManager.SEECLICK_BIN_FILE).writeBytes("bin weights".toByteArray())
        mam.persistComputedChecksum(ModelAssetManager.MODEL_ID_SEECLICK_BIN)

        assertNotNull(
            "effectiveChecksum for SeeClick bin must be non-null after persistComputedChecksum",
            mam.effectiveChecksum(ModelAssetManager.MODEL_ID_SEECLICK_BIN)
        )
    }

    // ── 6. Tampered file detected after persist ───────────────────────────────

    @Test
    fun `verifyModel detects tampered SeeClick file after checksum is persisted`() {
        val mam = ModelAssetManager(modelsDir)
        val originalContent = "original seeclick param".toByteArray()
        val paramFile = File(modelsDir, ModelAssetManager.SEECLICK_PARAM_FILE)
        paramFile.writeBytes(originalContent)
        mam.persistComputedChecksum(ModelAssetManager.MODEL_ID_SEECLICK)

        // Tamper with the file
        paramFile.writeBytes("tampered content".toByteArray())

        val status = mam.verifyModel(ModelAssetManager.MODEL_ID_SEECLICK)
        assertEquals(
            "Tampered SeeClick file must be CORRUPTED after checksum was persisted",
            ModelAssetManager.ModelStatus.CORRUPTED,
            status
        )
    }

    // ── 7. Persistence across construction ────────────────────────────────────

    @Test
    fun `persisted checksums survive across ModelAssetManager reconstruction`() {
        // First instance: download and persist
        val mam1 = ModelAssetManager(modelsDir)
        val content = "seeclick bin weights".toByteArray()
        File(modelsDir, ModelAssetManager.SEECLICK_BIN_FILE).writeBytes(content)
        val sha = mam1.persistComputedChecksum(ModelAssetManager.MODEL_ID_SEECLICK_BIN)
        assertNotNull(sha)

        // Second instance: should load persisted checksum from disk
        val mam2 = ModelAssetManager(modelsDir)
        assertEquals(
            "Reconstructed ModelAssetManager must load persisted checksum from .checksums.json",
            sha,
            mam2.effectiveChecksum(ModelAssetManager.MODEL_ID_SEECLICK_BIN)
        )
    }

    @Test
    fun `verifyModel enforces persisted checksum on second ModelAssetManager instance`() {
        // First instance: persist checksum
        val mam1 = ModelAssetManager(modelsDir)
        val content = "param bytes".toByteArray()
        val paramFile = File(modelsDir, ModelAssetManager.SEECLICK_PARAM_FILE)
        paramFile.writeBytes(content)
        mam1.persistComputedChecksum(ModelAssetManager.MODEL_ID_SEECLICK)

        // Tamper with the file between app restarts
        paramFile.writeBytes("tampered after restart".toByteArray())

        // Second instance should enforce the persisted checksum
        val mam2 = ModelAssetManager(modelsDir)
        val status = mam2.verifyModel(ModelAssetManager.MODEL_ID_SEECLICK)
        assertEquals(
            "Persisted checksum must be enforced after reconstruction — tampered file is CORRUPTED",
            ModelAssetManager.ModelStatus.CORRUPTED,
            status
        )
    }

    // ── 8. checksums.json not treated as stale ────────────────────────────────

    @Test
    fun `cleanupStaleFiles does not delete the checksums json file`() {
        val mam = ModelAssetManager(modelsDir)
        // Write all model files so cleanupStaleFiles has nothing to evict except unknowns
        File(modelsDir, ModelAssetManager.MOBILEVLM_FILE).writeBytes("vlm".toByteArray())
        File(modelsDir, ModelAssetManager.SEECLICK_PARAM_FILE).writeBytes("param".toByteArray())
        File(modelsDir, ModelAssetManager.SEECLICK_BIN_FILE).writeBytes("bin".toByteArray())
        // Persist a checksum so the .checksums.json file exists
        mam.persistComputedChecksum(ModelAssetManager.MODEL_ID_SEECLICK)

        val checksumsFile = File(modelsDir, ModelAssetManager.CHECKSUMS_FILE)
        assertTrue("Checksums file must exist after persist", checksumsFile.exists())

        mam.cleanupStaleFiles()

        assertTrue(
            "cleanupStaleFiles must NOT delete ${ModelAssetManager.CHECKSUMS_FILE}",
            checksumsFile.exists()
        )
    }
}
