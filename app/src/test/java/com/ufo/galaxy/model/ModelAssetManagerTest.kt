package com.ufo.galaxy.model

import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Unit tests for [ModelAssetManager] verifying file status tracking, checksum
 * verification, load/unload lifecycle hooks, and download spec generation.
 *
 * Uses a [TemporaryFolder] rule as a stand-in for the Android models directory.
 * Tests use the [ModelAssetManager(File)] constructor directly to avoid the
 * Android [android.content.Context] dependency.
 */
class ModelAssetManagerTest {

    @get:Rule
    val tmpFolder = TemporaryFolder()

    private lateinit var mam: ModelAssetManager
    private lateinit var modelsDir: File

    @Before
    fun setUp() {
        modelsDir = tmpFolder.newFolder("models")
        // Use null checksumOverrides for all models so tests writing fake content work
        // correctly regardless of the static SHA-256 constants. Tests that specifically
        // validate checksum enforcement supply their own overrides.
        mam = ModelAssetManager(
            modelsDir,
            checksumOverrides = mapOf(
                ModelAssetManager.MODEL_ID_VLM to null,
                ModelAssetManager.MODEL_ID_VLM_MMPROJ to null
            )
        )
    }

    // ── verifyModel: MISSING ─────────────────────────────────────────────────

    @Test
    fun `verifyModel returns MISSING when file does not exist`() {
        val status = mam.verifyModel(ModelAssetManager.MODEL_ID_VLM)
        assertEquals(ModelAssetManager.ModelStatus.MISSING, status)
    }

    @Test
    fun `getStatus returns MISSING initially`() {
        assertEquals(ModelAssetManager.ModelStatus.MISSING, mam.getStatus(ModelAssetManager.MODEL_ID_VLM))
        assertEquals(ModelAssetManager.ModelStatus.MISSING, mam.getStatus(ModelAssetManager.MODEL_ID_VLM_MMPROJ))
    }

    @Test
    fun `verifyModel returns MISSING for unknown model id`() {
        val status = mam.verifyModel("unknown_model")
        assertEquals(ModelAssetManager.ModelStatus.MISSING, status)
    }

    // ── verifyModel: READY ───────────────────────────────────────────────────

    @Test
    fun `verifyModel returns READY when file exists and checksum override is null`() {
        File(modelsDir, ModelAssetManager.VLM_FILE).writeText("fake weights")
        val status = mam.verifyModel(ModelAssetManager.MODEL_ID_VLM)
        assertEquals(ModelAssetManager.ModelStatus.READY, status)
    }

    @Test
    fun `verifyAll returns READY for vlm and MISSING for mmproj when only vlm present`() {
        File(modelsDir, ModelAssetManager.VLM_FILE).writeText("fake weights")
        val statuses = mam.verifyAll()
        assertEquals(ModelAssetManager.ModelStatus.READY, statuses[ModelAssetManager.MODEL_ID_VLM])
        assertEquals(ModelAssetManager.ModelStatus.MISSING, statuses[ModelAssetManager.MODEL_ID_VLM_MMPROJ])
    }

    // ── markLoaded / markUnloaded ─────────────────────────────────────────────

    @Test
    fun `markLoaded transitions status to LOADED`() {
        File(modelsDir, ModelAssetManager.VLM_FILE).writeText("fake weights")
        mam.verifyModel(ModelAssetManager.MODEL_ID_VLM)
        mam.markLoaded(ModelAssetManager.MODEL_ID_VLM)
        assertEquals(ModelAssetManager.ModelStatus.LOADED, mam.getStatus(ModelAssetManager.MODEL_ID_VLM))
    }

    @Test
    fun `markUnloaded reverts to READY when file still present`() {
        File(modelsDir, ModelAssetManager.VLM_FILE).writeText("fake weights")
        mam.markLoaded(ModelAssetManager.MODEL_ID_VLM)
        mam.markUnloaded(ModelAssetManager.MODEL_ID_VLM)
        assertEquals(ModelAssetManager.ModelStatus.READY, mam.getStatus(ModelAssetManager.MODEL_ID_VLM))
    }

    @Test
    fun `markUnloaded reverts to MISSING when file absent`() {
        mam.markLoaded(ModelAssetManager.MODEL_ID_VLM)
        mam.markUnloaded(ModelAssetManager.MODEL_ID_VLM)
        assertEquals(ModelAssetManager.ModelStatus.MISSING, mam.getStatus(ModelAssetManager.MODEL_ID_VLM))
    }

    // ── areAllModelsLoaded ────────────────────────────────────────────────────

    @Test
    fun `areAllModelsLoaded returns false when models are missing`() {
        assertFalse(mam.areAllModelsLoaded())
    }

    @Test
    fun `areAllModelsLoaded returns true only when both models are loaded`() {
        mam.markLoaded(ModelAssetManager.MODEL_ID_VLM)
        assertFalse("Should be false with only the LLM weights loaded", mam.areAllModelsLoaded())
        mam.markLoaded(ModelAssetManager.MODEL_ID_VLM_MMPROJ)
        assertTrue("Should be true with both loaded", mam.areAllModelsLoaded())
    }

    // ── readinessError ────────────────────────────────────────────────────────

    @Test
    fun `readinessError returns null when all models are loaded`() {
        mam.markLoaded(ModelAssetManager.MODEL_ID_VLM)
        mam.markLoaded(ModelAssetManager.MODEL_ID_VLM_MMPROJ)
        assertNull(mam.readinessError())
    }

    @Test
    fun `readinessError returns non-null message when models are not loaded`() {
        val error = mam.readinessError()
        assertNotNull(error)
        assertTrue("Error should mention model status", error!!.isNotEmpty())
    }

    // ── path helpers ──────────────────────────────────────────────────────────

    @Test
    fun `vlmModelPath points inside models directory`() {
        assertTrue(mam.vlmModelPath.contains(ModelAssetManager.VLM_FILE))
    }

    @Test
    fun `vlmMmprojPath points inside models directory`() {
        assertTrue(mam.vlmMmprojPath.contains(ModelAssetManager.VLM_MMPROJ_FILE))
    }

    @Test
    fun `vlmModelPath and vlmMmprojPath are distinct files`() {
        // 适配说明:旧三模型注册表有第三个路径 helper(NCNN bin 文件),新契约只有两个
        // 文件;改为断言两个 helper 指向不同文件,防止 LLM 与 mmproj 路径互相覆盖。
        assertNotEquals(mam.vlmModelPath, mam.vlmMmprojPath)
    }

    // ── models directory ──────────────────────────────────────────────────────

    @Test
    fun `models directory exists after construction`() {
        assertTrue("Models directory must exist after construction", mam.modelsDir.exists())
        assertTrue("Models directory must be a directory", mam.modelsDir.isDirectory)
    }

    // ── checksum null override ────────────────────────────────────────────────

    @Test
    fun `verifyModel returns READY when file exists and null checksum skips verification`() {
        // This test exercises the explicit null-override path. The setUp() method already
        // configures null overrides for all models; the test below confirms the behaviour.
        File(modelsDir, ModelAssetManager.VLM_FILE).writeBytes(byteArrayOf(1, 2, 3, 4))
        val status = mam.verifyModel(ModelAssetManager.MODEL_ID_VLM)
        assertEquals(
            "With null checksum override, any file content should yield READY",
            ModelAssetManager.ModelStatus.READY,
            status
        )
    }

    // ── downloadSpecsForMissing ───────────────────────────────────────────────

    @Test
    fun `downloadSpecsForMissing returns non-empty list when models are MISSING and URLs are configured`() {
        // VLM and mmproj download URLs are configured via the ModelManifest entries.
        // All models start as MISSING, so specs should be returned.
        val specs = mam.downloadSpecsForMissing()
        assertTrue(
            "Download specs must be returned for MISSING models when URLs are configured",
            specs.isNotEmpty()
        )
    }

    @Test
    fun `downloadSpecsForMissing returns empty list when all models are loaded`() {
        // 适配说明:registry 现有两个条目(MAI-UI-2B LLM 权重与 mmproj 视觉投影,
        // 两者独立跟踪),downloadSpecsForMissing() 遍历全部条目,故两者都需标记 LOADED。
        mam.markLoaded(ModelAssetManager.MODEL_ID_VLM)
        mam.markLoaded(ModelAssetManager.MODEL_ID_VLM_MMPROJ)
        val specs = mam.downloadSpecsForMissing()
        assertTrue("No specs when all models are already loaded", specs.isEmpty())
    }

    @Test
    fun `downloadSpecsForMissing returns empty list when models are READY`() {
        File(modelsDir, ModelAssetManager.VLM_FILE).writeText("weights")
        File(modelsDir, ModelAssetManager.VLM_MMPROJ_FILE).writeText("mmproj")
        mam.verifyAll()
        val specs = mam.downloadSpecsForMissing()
        assertTrue("No specs when models are READY (only MISSING/CORRUPTED trigger download)", specs.isEmpty())
    }
}
