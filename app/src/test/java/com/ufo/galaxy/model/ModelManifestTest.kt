package com.ufo.galaxy.model

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for [ModelManifest] and [ModelSource].
 *
 * Covers manifest schema completeness, [ModelSource] URL generation, and the full
 * [ModelManifest.checkCompatibility] contract including the incompatibility rejection path.
 *
 * All tests are pure JVM — no Android SDK or network access required.
 */
class ModelManifestTest {

    // ── ModelSource ───────────────────────────────────────────────────────────

    @Test
    fun `HuggingFace source constructs correct download URL`() {
        val src = ModelSource.HuggingFace(
            repoId = "owner/repo",
            fileName = "model.gguf",
            branch = "main"
        )
        assertEquals(
            "https://huggingface.co/owner/repo/resolve/main/model.gguf",
            src.downloadUrl
        )
    }

    @Test
    fun `HuggingFace source default branch is main`() {
        val src = ModelSource.HuggingFace(repoId = "a/b", fileName = "f.bin")
        assertTrue("Default branch must be 'main'", src.downloadUrl.contains("/main/"))
    }

    @Test
    fun `HuggingFace source respects custom branch`() {
        val src = ModelSource.HuggingFace(repoId = "a/b", fileName = "f.bin", branch = "v2")
        assertTrue("Branch must appear in URL", src.downloadUrl.contains("/v2/"))
    }

    @Test
    fun `CustomUrl source exposes the provided URL`() {
        val url = "https://cdn.example.com/models/model.bin"
        val src = ModelSource.CustomUrl(url)
        assertEquals(url, src.url)
    }

    @Test
    fun `LocalPath source exposes the provided path`() {
        val path = "/data/local/tmp/model.bin"
        val src = ModelSource.LocalPath(path)
        assertEquals(path, src.path)
    }

    // ── forKnownModel: MobileVLM manifest ────────────────────────────────────

    @Test
    fun `forKnownModel returns non-null manifest for mobilevlm`() {
        val manifest = ModelManifest.forKnownModel(ModelAssetManager.MODEL_ID_MOBILEVLM)
        assertNotNull("Expected a manifest for mobilevlm", manifest)
    }

    @Test
    fun `mobilevlm manifest has correct modelId`() {
        val manifest = ModelManifest.forKnownModel(ModelAssetManager.MODEL_ID_MOBILEVLM)!!
        assertEquals(ModelAssetManager.MODEL_ID_MOBILEVLM, manifest.modelId)
    }

    @Test
    fun `mobilevlm manifest has LLAMA_CPP runtime type`() {
        val manifest = ModelManifest.forKnownModel(ModelAssetManager.MODEL_ID_MOBILEVLM)!!
        assertEquals(ModelManifest.RuntimeType.LLAMA_CPP, manifest.runtimeType)
    }

    @Test
    fun `mobilevlm manifest has HuggingFace source`() {
        val manifest = ModelManifest.forKnownModel(ModelAssetManager.MODEL_ID_MOBILEVLM)!!
        assertNotNull("mobilevlm manifest must carry a source", manifest.source)
        assertTrue(
            "mobilevlm source must be HuggingFace",
            manifest.source is ModelSource.HuggingFace
        )
    }

    @Test
    fun `mobilevlm HuggingFace source download URL references huggingface dot co`() {
        val manifest = ModelManifest.forKnownModel(ModelAssetManager.MODEL_ID_MOBILEVLM)!!
        val src = manifest.source as ModelSource.HuggingFace
        assertTrue(
            "mobilevlm source URL must reference huggingface.co",
            src.downloadUrl.startsWith("https://huggingface.co/")
        )
    }

    @Test
    fun `mobilevlm manifest has non-null modelVersion`() {
        val manifest = ModelManifest.forKnownModel(ModelAssetManager.MODEL_ID_MOBILEVLM)!!
        assertNotNull(manifest.modelVersion)
        assertTrue(manifest.modelVersion.isNotEmpty())
    }

    @Test
    fun `mobilevlm manifest has non-null quantization`() {
        val manifest = ModelManifest.forKnownModel(ModelAssetManager.MODEL_ID_MOBILEVLM)!!
        assertNotNull("mobilevlm manifest must declare quantization", manifest.quantization)
    }

    @Test
    fun `mobilevlm manifest has positive parameterCountM`() {
        val manifest = ModelManifest.forKnownModel(ModelAssetManager.MODEL_ID_MOBILEVLM)!!
        assertNotNull(manifest.parameterCountM)
        assertTrue("parameterCountM must be positive", manifest.parameterCountM!! > 0)
    }

    // ── forKnownModel: SeeClick param manifest ───────────────────────────────

    @Test
    fun `forKnownModel returns non-null manifest for seeclick`() {
        val manifest = ModelManifest.forKnownModel(ModelAssetManager.MODEL_ID_SEECLICK)
        assertNotNull("Expected a manifest for seeclick", manifest)
    }

    @Test
    fun `seeclick manifest has NCNN runtime type`() {
        val manifest = ModelManifest.forKnownModel(ModelAssetManager.MODEL_ID_SEECLICK)!!
        assertEquals(ModelManifest.RuntimeType.NCNN, manifest.runtimeType)
    }

    @Test
    fun `seeclick manifest has HuggingFace source`() {
        val manifest = ModelManifest.forKnownModel(ModelAssetManager.MODEL_ID_SEECLICK)!!
        assertTrue(manifest.source is ModelSource.HuggingFace)
    }

    // ── forKnownModel: SeeClick bin manifest ─────────────────────────────────

    @Test
    fun `forKnownModel returns non-null manifest for seeclick_bin`() {
        val manifest = ModelManifest.forKnownModel(ModelAssetManager.MODEL_ID_SEECLICK_BIN)
        assertNotNull("Expected a manifest for seeclick_bin", manifest)
    }

    @Test
    fun `seeclick_bin manifest has NCNN runtime type`() {
        val manifest = ModelManifest.forKnownModel(ModelAssetManager.MODEL_ID_SEECLICK_BIN)!!
        assertEquals(ModelManifest.RuntimeType.NCNN, manifest.runtimeType)
    }

    // ── forKnownModel: unknown id ─────────────────────────────────────────────

    @Test
    fun `forKnownModel returns null for unknown model id`() {
        assertNull(ModelManifest.forKnownModel("does_not_exist"))
    }

    // ── checkCompatibility ───────────────────────────────────────────────────

    @Test
    fun `checkCompatibility returns Unknown when minRuntimeVersion is null`() {
        val manifest = ModelManifest(
            modelId = "test",
            modelVersion = "1.0",
            runtimeType = ModelManifest.RuntimeType.LLAMA_CPP,
            minRuntimeVersion = null
        )
        assertTrue(manifest.checkCompatibility("any-version") is CompatibilityResult.Unknown)
    }

    @Test
    fun `checkCompatibility returns Unknown when runtimeVersion is null`() {
        val manifest = ModelManifest(
            modelId = "test",
            modelVersion = "1.0",
            runtimeType = ModelManifest.RuntimeType.LLAMA_CPP,
            minRuntimeVersion = "1.0"
        )
        assertTrue(manifest.checkCompatibility(null) is CompatibilityResult.Unknown)
    }

    @Test
    fun `checkCompatibility returns Compatible when runtimeVersion meets minimum`() {
        val manifest = ModelManifest(
            modelId = "test",
            modelVersion = "1.0",
            runtimeType = ModelManifest.RuntimeType.LLAMA_CPP,
            minRuntimeVersion = "1.0"
        )
        assertTrue(manifest.checkCompatibility("1.0") is CompatibilityResult.Compatible)
    }

    @Test
    fun `checkCompatibility returns Compatible when runtimeVersion exceeds minimum`() {
        val manifest = ModelManifest(
            modelId = "test",
            modelVersion = "1.0",
            runtimeType = ModelManifest.RuntimeType.LLAMA_CPP,
            minRuntimeVersion = "1.0"
        )
        assertTrue(manifest.checkCompatibility("2.0") is CompatibilityResult.Compatible)
    }

    @Test
    fun `checkCompatibility returns Incompatible when runtimeVersion is below minimum`() {
        val manifest = ModelManifest(
            modelId = "test",
            modelVersion = "1.0",
            runtimeType = ModelManifest.RuntimeType.LLAMA_CPP,
            minRuntimeVersion = "2.0"
        )
        val result = manifest.checkCompatibility("1.0")
        assertTrue(
            "Expected Incompatible when runtime is below minimum",
            result is CompatibilityResult.Incompatible
        )
    }

    @Test
    fun `checkCompatibility Incompatible reason mentions model id`() {
        val manifest = ModelManifest(
            modelId = "my_model",
            modelVersion = "1.0",
            runtimeType = ModelManifest.RuntimeType.LLAMA_CPP,
            minRuntimeVersion = "3.0"
        )
        val result = manifest.checkCompatibility("1.0") as CompatibilityResult.Incompatible
        assertTrue(
            "Incompatible reason must mention model id",
            result.reason.contains("my_model")
        )
    }

    @Test
    fun `checkCompatibility Incompatible reason mentions both versions`() {
        val manifest = ModelManifest(
            modelId = "test",
            modelVersion = "1.0",
            runtimeType = ModelManifest.RuntimeType.LLAMA_CPP,
            minRuntimeVersion = "3.0"
        )
        val result = manifest.checkCompatibility("1.0") as CompatibilityResult.Incompatible
        assertTrue("Reason must mention actual runtime version", result.reason.contains("1.0"))
        assertTrue("Reason must mention minimum required version", result.reason.contains("3.0"))
    }
}
