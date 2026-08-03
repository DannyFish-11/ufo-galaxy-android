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

    // ── forKnownModel: VLM (MAI-UI-2B LLM weights) manifest ──────────────────

    @Test
    fun `forKnownModel returns non-null manifest for vlm`() {
        val manifest = ModelManifest.forKnownModel(ModelAssetManager.MODEL_ID_VLM)
        assertNotNull("Expected a manifest for mai_ui_2b", manifest)
    }

    @Test
    fun `vlm manifest has correct modelId`() {
        val manifest = ModelManifest.forKnownModel(ModelAssetManager.MODEL_ID_VLM)!!
        assertEquals(ModelAssetManager.MODEL_ID_VLM, manifest.modelId)
    }

    @Test
    fun `vlm manifest has LLAMA_CPP runtime type`() {
        val manifest = ModelManifest.forKnownModel(ModelAssetManager.MODEL_ID_VLM)!!
        assertEquals(ModelManifest.RuntimeType.LLAMA_CPP, manifest.runtimeType)
    }

    @Test
    fun `vlm manifest has HuggingFace source`() {
        val manifest = ModelManifest.forKnownModel(ModelAssetManager.MODEL_ID_VLM)!!
        assertNotNull("vlm manifest must carry a source", manifest.source)
        assertTrue(
            "vlm source must be HuggingFace",
            manifest.source is ModelSource.HuggingFace
        )
    }

    @Test
    fun `vlm HuggingFace source download URL references huggingface dot co`() {
        val manifest = ModelManifest.forKnownModel(ModelAssetManager.MODEL_ID_VLM)!!
        val src = manifest.source as ModelSource.HuggingFace
        assertTrue(
            "vlm source URL must reference huggingface.co",
            src.downloadUrl.startsWith("https://huggingface.co/")
        )
    }

    @Test
    fun `vlm manifest has non-null modelVersion`() {
        val manifest = ModelManifest.forKnownModel(ModelAssetManager.MODEL_ID_VLM)!!
        assertNotNull(manifest.modelVersion)
        assertTrue(manifest.modelVersion.isNotEmpty())
    }

    @Test
    fun `vlm manifest has non-null quantization`() {
        val manifest = ModelManifest.forKnownModel(ModelAssetManager.MODEL_ID_VLM)!!
        assertNotNull("vlm manifest must declare quantization", manifest.quantization)
    }

    @Test
    fun `vlm manifest has positive parameterCountM`() {
        val manifest = ModelManifest.forKnownModel(ModelAssetManager.MODEL_ID_VLM)!!
        assertNotNull(manifest.parameterCountM)
        assertTrue("parameterCountM must be positive", manifest.parameterCountM!! > 0)
    }

    // ── forKnownModel: mmproj manifest ───────────────────────────────────────

    @Test
    fun `forKnownModel returns non-null manifest for mmproj`() {
        val manifest = ModelManifest.forKnownModel(ModelAssetManager.MODEL_ID_VLM_MMPROJ)
        assertNotNull("Expected a manifest for mai_ui_2b_mmproj", manifest)
    }

    @Test
    fun `mmproj manifest has LLAMA_CPP runtime type`() {
        // 适配说明:旧注册表第二模型走 NCNN 运行时;新契约下 mmproj 与 LLM 权重
        // 同属 llama.cpp 运行时(NCNN 栈已退役),故断言 LLAMA_CPP。
        val manifest = ModelManifest.forKnownModel(ModelAssetManager.MODEL_ID_VLM_MMPROJ)!!
        assertEquals(ModelManifest.RuntimeType.LLAMA_CPP, manifest.runtimeType)
    }

    @Test
    fun `mmproj manifest has HuggingFace source`() {
        val manifest = ModelManifest.forKnownModel(ModelAssetManager.MODEL_ID_VLM_MMPROJ)!!
        assertTrue(manifest.source is ModelSource.HuggingFace)
    }

    // ── forKnownModel: retired legacy ids ────────────────────────────────────

    @Test
    fun `forKnownModel returns null for retired legacy model ids`() {
        // 适配说明:旧三模型注册表已被 MAI-UI-2B 双文件替换,旧 id 不再拥有 manifest;
        // 原"旧 id 有 manifest"的断言改为断言旧 id 一律返回 null。
        assertNull(ModelManifest.forKnownModel("mobilevlm"))
        assertNull(ModelManifest.forKnownModel("seeclick"))
        assertNull(ModelManifest.forKnownModel("seeclick_bin"))
    }

    @Test
    fun `retired NCNN and MNN runtime type enum values are retained`() {
        // 适配说明:原测试断言旧第三模型使用 NCNN 运行时;该模型已退役,但契约要求
        // RuntimeType 枚举值保留以兼容旧数据,故改为断言枚举值仍可解析。
        assertEquals(ModelManifest.RuntimeType.NCNN, ModelManifest.RuntimeType.valueOf("NCNN"))
        assertEquals(ModelManifest.RuntimeType.MNN, ModelManifest.RuntimeType.valueOf("MNN"))
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

    // ── minDiskSpaceBytes ─────────────────────────────────────────────────────

    @Test
    fun `vlm manifest has positive minDiskSpaceBytes`() {
        val manifest = ModelManifest.forKnownModel(ModelAssetManager.MODEL_ID_VLM)!!
        assertNotNull("vlm manifest must declare minDiskSpaceBytes", manifest.minDiskSpaceBytes)
        assertTrue("vlm minDiskSpaceBytes must be positive", manifest.minDiskSpaceBytes!! > 0L)
    }

    @Test
    fun `mmproj manifest has positive minDiskSpaceBytes`() {
        val manifest = ModelManifest.forKnownModel(ModelAssetManager.MODEL_ID_VLM_MMPROJ)!!
        assertNotNull("mmproj manifest must declare minDiskSpaceBytes", manifest.minDiskSpaceBytes)
        assertTrue("mmproj minDiskSpaceBytes must be positive", manifest.minDiskSpaceBytes!! > 0L)
    }

    @Test
    fun `both known model manifests have null checksum (trust-on-first-use)`() {
        // 适配说明:原测试针对旧第三模型的 minDiskSpaceBytes;该模型已退役,改为断言
        // 新契约的 TOFU 语义——两个新模型的 manifest checksum 均为 null(首次下载后
        // 由 persistComputedChecksum 持久化)。
        assertNull(ModelManifest.forKnownModel(ModelAssetManager.MODEL_ID_VLM)!!.checksum)
        assertNull(ModelManifest.forKnownModel(ModelAssetManager.MODEL_ID_VLM_MMPROJ)!!.checksum)
    }

    @Test
    fun `vlm minDiskSpaceBytes is larger than mmproj minDiskSpaceBytes`() {
        val vlmBytes = ModelManifest.forKnownModel(ModelAssetManager.MODEL_ID_VLM)!!.minDiskSpaceBytes!!
        val mmprojBytes = ModelManifest.forKnownModel(ModelAssetManager.MODEL_ID_VLM_MMPROJ)!!.minDiskSpaceBytes!!
        assertTrue(
            "VLM LLM weights (Q4_K_M GGUF ~1.2 GB) must declare more disk space than the mmproj file (~hundreds of MB)",
            vlmBytes > mmprojBytes
        )
    }

    @Test
    fun `manifest with null minDiskSpaceBytes compiles and defaults to null`() {
        val manifest = ModelManifest(
            modelId = "test",
            modelVersion = "1.0",
            runtimeType = ModelManifest.RuntimeType.UNKNOWN
        )
        assertNull("Default minDiskSpaceBytes must be null", manifest.minDiskSpaceBytes)
    }
}
