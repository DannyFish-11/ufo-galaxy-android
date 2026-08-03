package com.ufo.galaxy.model

import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest

/**
 * Tests that verify the SHA-256 checksum enforcement in [ModelAssetManager]:
 *
 * 1. Both static SHA-256 constants are null (trust-on-first-use policy).
 * 2. [ModelAssetManager.verifyModel] returns CORRUPTED when file does not match the
 *    expected SHA-256 (supplied via checksumOverrides).
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

    // ── 1. Static constants (trust-on-first-use) ─────────────────────────────

    @Test
    fun `static SHA256 constants are null pending trust-on-first-use`() {
        // 适配说明:旧 MobileVLM 曾预置硬编码 SHA-256(每次强制校验);新契约下两个
        // MAI-UI-2B 文件均为 trust-on-first-use——静态常量为 null,首次下载后由
        // persistComputedChecksum 持久化并强制校验。
        assertNull(
            "VLM_SHA256 must be null until persistComputedChecksum stores the first-download digest",
            ModelAssetManager.VLM_SHA256
        )
        assertNull(
            "VLM_MMPROJ_SHA256 must be null until persistComputedChecksum stores the first-download digest",
            ModelAssetManager.VLM_MMPROJ_SHA256
        )
    }

    @Test
    fun `persisted checksum has the expected 64-character hex format`() {
        // 适配说明:原测试校验硬编码常量的 64 位十六进制格式;TOFU 语义下改为校验
        // persistComputedChecksum 产出的摘要格式。
        val mam = ModelAssetManager(modelsDir)
        File(modelsDir, ModelAssetManager.VLM_FILE).writeBytes("weights".toByteArray())
        val sha = mam.persistComputedChecksum(ModelAssetManager.MODEL_ID_VLM)
        assertNotNull(sha)
        assertEquals("SHA-256 must be 64 hex characters", 64, sha!!.length)
        assertTrue("SHA-256 must contain only hex characters",
            sha.matches(Regex("[0-9a-fA-F]+")))
    }

    @Test
    fun `VLM_FILE is the MAI-UI-2B Q4_K_M gguf`() {
        assertEquals(
            "VLM file must reference the MAI-UI-2B Q4_K_M quantised GGUF",
            "MAI-UI-2B.Q4_K_M.gguf",
            ModelAssetManager.VLM_FILE
        )
    }

    // ── 2. Checksum mismatch → CORRUPTED ─────────────────────────────────────

    @Test
    fun `verifyModel returns CORRUPTED when file content does not match expected SHA256`() {
        val expectedSha = "a".repeat(64)  // deliberately wrong SHA-256
        val mam = ModelAssetManager(
            modelsDir,
            checksumOverrides = mapOf(ModelAssetManager.MODEL_ID_VLM to expectedSha)
        )
        File(modelsDir, ModelAssetManager.VLM_FILE).writeBytes("wrong content".toByteArray())
        val status = mam.verifyModel(ModelAssetManager.MODEL_ID_VLM)
        assertEquals(
            "File with wrong SHA-256 must be CORRUPTED",
            ModelAssetManager.ModelStatus.CORRUPTED,
            status
        )
    }

    @Test
    fun `downloadSpecsForMissing includes spec for CORRUPTED VLM`() {
        // Verify that provisioning re-downloads CORRUPTED models. Under trust-on-first-use
        // the manifest checksum is null, but a re-download spec must still be generated.
        val mam = ModelAssetManager(
            modelsDir,
            checksumOverrides = mapOf(ModelAssetManager.MODEL_ID_VLM to "a".repeat(64))
        )
        File(modelsDir, ModelAssetManager.VLM_FILE).writeBytes("corrupted".toByteArray())
        mam.verifyModel(ModelAssetManager.MODEL_ID_VLM)  // → CORRUPTED
        val specs = mam.downloadSpecsForMissing()
        val vlmSpec = specs.firstOrNull { it.modelId == ModelAssetManager.MODEL_ID_VLM }
        assertNotNull("Re-download spec for CORRUPTED VLM must be non-null", vlmSpec)
    }

    // ── 3. Correct content → READY ────────────────────────────────────────────

    @Test
    fun `verifyModel returns READY when file content matches expected SHA256`() {
        val content = "authentic model weights".toByteArray()
        val expectedSha = sha256Hex(content)
        val mam = ModelAssetManager(
            modelsDir,
            checksumOverrides = mapOf(ModelAssetManager.MODEL_ID_VLM to expectedSha)
        )
        File(modelsDir, ModelAssetManager.VLM_FILE).writeBytes(content)
        val status = mam.verifyModel(ModelAssetManager.MODEL_ID_VLM)
        assertEquals(
            "File whose SHA-256 matches the expected value must be READY",
            ModelAssetManager.ModelStatus.READY,
            status
        )
    }

    // ── 4 & 5. persistComputedChecksum stores non-null checksum ───────────────

    @Test
    fun `persistComputedChecksum returns non-null SHA256 for a present file`() {
        // The VLM starts with no expected checksum (static constant is null, TOFU).
        val mam = ModelAssetManager(modelsDir)
        val vlmContent = "vlm weight bytes".toByteArray()
        File(modelsDir, ModelAssetManager.VLM_FILE).writeBytes(vlmContent)

        val persisted = mam.persistComputedChecksum(ModelAssetManager.MODEL_ID_VLM)
        assertNotNull("persistComputedChecksum must return non-null for present file", persisted)
        assertEquals("Persisted SHA-256 must match file content", sha256Hex(vlmContent), persisted)
    }

    @Test
    fun `effectiveChecksum is non-null after persistComputedChecksum`() {
        val mam = ModelAssetManager(modelsDir)
        File(modelsDir, ModelAssetManager.VLM_FILE).writeBytes("weights".toByteArray())
        mam.persistComputedChecksum(ModelAssetManager.MODEL_ID_VLM)

        assertNotNull(
            "effectiveChecksum must be non-null after persistComputedChecksum",
            mam.effectiveChecksum(ModelAssetManager.MODEL_ID_VLM)
        )
    }

    @Test
    fun `effectiveChecksum for mmproj is non-null after persistComputedChecksum`() {
        val mam = ModelAssetManager(modelsDir)
        File(modelsDir, ModelAssetManager.VLM_MMPROJ_FILE).writeBytes("mmproj weights".toByteArray())
        mam.persistComputedChecksum(ModelAssetManager.MODEL_ID_VLM_MMPROJ)

        assertNotNull(
            "effectiveChecksum for mmproj must be non-null after persistComputedChecksum",
            mam.effectiveChecksum(ModelAssetManager.MODEL_ID_VLM_MMPROJ)
        )
    }

    // ── 6. Tampered file detected after persist ───────────────────────────────

    @Test
    fun `verifyModel detects tampered VLM file after checksum is persisted`() {
        val mam = ModelAssetManager(modelsDir)
        val originalContent = "original vlm weights".toByteArray()
        val vlmFile = File(modelsDir, ModelAssetManager.VLM_FILE)
        vlmFile.writeBytes(originalContent)
        mam.persistComputedChecksum(ModelAssetManager.MODEL_ID_VLM)

        // Tamper with the file
        vlmFile.writeBytes("tampered content".toByteArray())

        val status = mam.verifyModel(ModelAssetManager.MODEL_ID_VLM)
        assertEquals(
            "Tampered VLM file must be CORRUPTED after checksum was persisted",
            ModelAssetManager.ModelStatus.CORRUPTED,
            status
        )
    }

    // ── 7. Persistence across construction ────────────────────────────────────

    @Test
    fun `persisted checksums survive across ModelAssetManager reconstruction`() {
        // First instance: download and persist
        val mam1 = ModelAssetManager(modelsDir)
        val content = "mmproj weights".toByteArray()
        File(modelsDir, ModelAssetManager.VLM_MMPROJ_FILE).writeBytes(content)
        val sha = mam1.persistComputedChecksum(ModelAssetManager.MODEL_ID_VLM_MMPROJ)
        assertNotNull(sha)

        // Second instance: should load persisted checksum from disk
        val mam2 = ModelAssetManager(modelsDir)
        assertEquals(
            "Reconstructed ModelAssetManager must load persisted checksum from .checksums.json",
            sha,
            mam2.effectiveChecksum(ModelAssetManager.MODEL_ID_VLM_MMPROJ)
        )
    }

    @Test
    fun `verifyModel enforces persisted checksum on second ModelAssetManager instance`() {
        // First instance: persist checksum
        val mam1 = ModelAssetManager(modelsDir)
        val content = "vlm bytes".toByteArray()
        val vlmFile = File(modelsDir, ModelAssetManager.VLM_FILE)
        vlmFile.writeBytes(content)
        mam1.persistComputedChecksum(ModelAssetManager.MODEL_ID_VLM)

        // Tamper with the file between app restarts
        vlmFile.writeBytes("tampered after restart".toByteArray())

        // Second instance should enforce the persisted checksum
        val mam2 = ModelAssetManager(modelsDir)
        val status = mam2.verifyModel(ModelAssetManager.MODEL_ID_VLM)
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
        File(modelsDir, ModelAssetManager.VLM_FILE).writeBytes("vlm".toByteArray())
        File(modelsDir, ModelAssetManager.VLM_MMPROJ_FILE).writeBytes("mmproj".toByteArray())
        // Persist a checksum so the .checksums.json file exists
        mam.persistComputedChecksum(ModelAssetManager.MODEL_ID_VLM)

        val checksumsFile = File(modelsDir, ModelAssetManager.CHECKSUMS_FILE)
        assertTrue("Checksums file must exist after persist", checksumsFile.exists())

        mam.cleanupStaleFiles()

        assertTrue(
            "cleanupStaleFiles must NOT delete ${ModelAssetManager.CHECKSUMS_FILE}",
            checksumsFile.exists()
        )
    }
}
