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
        mam = ModelAssetManager(modelsDir)
    }

    // ── verifyModel: MISSING ─────────────────────────────────────────────────

    @Test
    fun `verifyModel returns MISSING when file does not exist`() {
        val status = mam.verifyModel(ModelAssetManager.MODEL_ID_MOBILEVLM)
        assertEquals(ModelAssetManager.ModelStatus.MISSING, status)
    }

    @Test
    fun `getStatus returns MISSING initially`() {
        assertEquals(ModelAssetManager.ModelStatus.MISSING, mam.getStatus(ModelAssetManager.MODEL_ID_MOBILEVLM))
        assertEquals(ModelAssetManager.ModelStatus.MISSING, mam.getStatus(ModelAssetManager.MODEL_ID_SEECLICK))
    }

    @Test
    fun `verifyModel returns MISSING for unknown model id`() {
        val status = mam.verifyModel("unknown_model")
        assertEquals(ModelAssetManager.ModelStatus.MISSING, status)
    }

    // ── verifyModel: READY ───────────────────────────────────────────────────

    @Test
    fun `verifyModel returns READY when file exists and no checksum required`() {
        File(modelsDir, ModelAssetManager.MOBILEVLM_FILE).writeText("fake weights")
        val status = mam.verifyModel(ModelAssetManager.MODEL_ID_MOBILEVLM)
        assertEquals(ModelAssetManager.ModelStatus.READY, status)
    }

    @Test
    fun `verifyAll returns READY for mobilevlm and MISSING for seeclick when only mobilevlm present`() {
        File(modelsDir, ModelAssetManager.MOBILEVLM_FILE).writeText("fake weights")
        val statuses = mam.verifyAll()
        assertEquals(ModelAssetManager.ModelStatus.READY, statuses[ModelAssetManager.MODEL_ID_MOBILEVLM])
        assertEquals(ModelAssetManager.ModelStatus.MISSING, statuses[ModelAssetManager.MODEL_ID_SEECLICK])
    }

    // ── markLoaded / markUnloaded ─────────────────────────────────────────────

    @Test
    fun `markLoaded transitions status to LOADED`() {
        File(modelsDir, ModelAssetManager.MOBILEVLM_FILE).writeText("fake weights")
        mam.verifyModel(ModelAssetManager.MODEL_ID_MOBILEVLM)
        mam.markLoaded(ModelAssetManager.MODEL_ID_MOBILEVLM)
        assertEquals(ModelAssetManager.ModelStatus.LOADED, mam.getStatus(ModelAssetManager.MODEL_ID_MOBILEVLM))
    }

    @Test
    fun `markUnloaded reverts to READY when file still present`() {
        File(modelsDir, ModelAssetManager.MOBILEVLM_FILE).writeText("fake weights")
        mam.markLoaded(ModelAssetManager.MODEL_ID_MOBILEVLM)
        mam.markUnloaded(ModelAssetManager.MODEL_ID_MOBILEVLM)
        assertEquals(ModelAssetManager.ModelStatus.READY, mam.getStatus(ModelAssetManager.MODEL_ID_MOBILEVLM))
    }

    @Test
    fun `markUnloaded reverts to MISSING when file absent`() {
        mam.markLoaded(ModelAssetManager.MODEL_ID_MOBILEVLM)
        mam.markUnloaded(ModelAssetManager.MODEL_ID_MOBILEVLM)
        assertEquals(ModelAssetManager.ModelStatus.MISSING, mam.getStatus(ModelAssetManager.MODEL_ID_MOBILEVLM))
    }

    // ── areAllModelsLoaded ────────────────────────────────────────────────────

    @Test
    fun `areAllModelsLoaded returns false when models are missing`() {
        assertFalse(mam.areAllModelsLoaded())
    }

    @Test
    fun `areAllModelsLoaded returns true only when both models are loaded`() {
        mam.markLoaded(ModelAssetManager.MODEL_ID_MOBILEVLM)
        assertFalse("Should be false with only planner loaded", mam.areAllModelsLoaded())
        mam.markLoaded(ModelAssetManager.MODEL_ID_SEECLICK)
        assertTrue("Should be true with both loaded", mam.areAllModelsLoaded())
    }

    // ── readinessError ────────────────────────────────────────────────────────

    @Test
    fun `readinessError returns null when all models are loaded`() {
        mam.markLoaded(ModelAssetManager.MODEL_ID_MOBILEVLM)
        mam.markLoaded(ModelAssetManager.MODEL_ID_SEECLICK)
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
    fun `mobileVlmPath points inside models directory`() {
        assertTrue(mam.mobileVlmPath.contains(ModelAssetManager.MOBILEVLM_FILE))
    }

    @Test
    fun `seeClickParamPath points inside models directory`() {
        assertTrue(mam.seeClickParamPath.contains(ModelAssetManager.SEECLICK_PARAM_FILE))
    }

    @Test
    fun `seeClickBinPath points inside models directory`() {
        assertTrue(mam.seeClickBinPath.contains(ModelAssetManager.SEECLICK_BIN_FILE))
    }

    // ── models directory ──────────────────────────────────────────────────────

    @Test
    fun `models directory exists after construction`() {
        assertTrue("Models directory must exist after construction", mam.modelsDir.exists())
        assertTrue("Models directory must be a directory", mam.modelsDir.isDirectory)
    }

    // ── checksum skip (null expected) ─────────────────────────────────────────

    @Test
    fun `verifyModel returns READY when file exists and null checksum skips verification`() {
        File(modelsDir, ModelAssetManager.MOBILEVLM_FILE).writeBytes(byteArrayOf(1, 2, 3, 4))
        val status = mam.verifyModel(ModelAssetManager.MODEL_ID_MOBILEVLM)
        assertEquals(
            "With null expected checksum, any file content should yield READY",
            ModelAssetManager.ModelStatus.READY,
            status
        )
    }

    // ── downloadSpecsForMissing ───────────────────────────────────────────────

    @Test
    fun `downloadSpecsForMissing returns empty list when download URLs are not configured`() {
        // Default URL constants are empty strings; no download should be triggered
        // even when models are MISSING.
        val specs = mam.downloadSpecsForMissing()
        assertTrue(
            "No download specs expected when URL constants are empty",
            specs.isEmpty()
        )
    }

    @Test
    fun `downloadSpecsForMissing returns empty list when all models are loaded`() {
        mam.markLoaded(ModelAssetManager.MODEL_ID_MOBILEVLM)
        mam.markLoaded(ModelAssetManager.MODEL_ID_SEECLICK)
        val specs = mam.downloadSpecsForMissing()
        assertTrue("No specs when all models are already loaded", specs.isEmpty())
    }

    @Test
    fun `downloadSpecsForMissing returns empty list when models are READY`() {
        File(modelsDir, ModelAssetManager.MOBILEVLM_FILE).writeText("weights")
        File(modelsDir, ModelAssetManager.SEECLICK_PARAM_FILE).writeText("param")
        mam.verifyAll()
        val specs = mam.downloadSpecsForMissing()
        assertTrue("No specs when models are READY (only MISSING/CORRUPTED trigger download)", specs.isEmpty())
    }
}
