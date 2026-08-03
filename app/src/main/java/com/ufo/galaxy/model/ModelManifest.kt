package com.ufo.galaxy.model

/**
 * Describes where a model asset can be obtained.
 *
 * Making the source explicit in the manifest (rather than only in [ModelAssetManager])
 * ensures the governance layer has all information needed to reconstruct or verify a
 * download spec without consulting a separate registry.
 */
sealed class ModelSource {
    /**
     * Model hosted on Hugging Face Hub.
     *
     * @param repoId    Repository identifier in `owner/repo` format (e.g., "Qwen/Qwen3-VL-2B-Instruct-GGUF").
     * @param fileName  File name within the repository (e.g., "Qwen3VL-2B-Instruct-Q4_K_M.gguf").
     * @param branch    Git branch or revision; defaults to "main".
     */
    data class HuggingFace(
        val repoId: String,
        val fileName: String,
        val branch: String = "main"
    ) : ModelSource() {
        /** Canonical HuggingFace resolve URL for this asset. */
        val downloadUrl: String
            get() = "https://huggingface.co/$repoId/resolve/$branch/$fileName"
    }

    /**
     * Model available at an arbitrary URL (CDN, private server, etc.).
     *
     * @param url  Direct download URL for the model file.
     */
    data class CustomUrl(val url: String) : ModelSource()

    /**
     * Model available as a local file path (pre-bundled or side-loaded).
     *
     * @param path  Absolute path to the model file on the device.
     */
    data class LocalPath(val path: String) : ModelSource()
}

/**
 * Lightweight metadata record describing a model's identity, source, runtime requirements, and
 * compatibility constraints.
 *
 * This manifest is the single authority for all governance decisions about a model asset:
 * where to obtain it ([source]), what checksum to verify ([checksum]), which runtime it
 * requires ([runtimeType], [minRuntimeVersion]), and how large / quantized it is.
 *
 * Manifests are resolved by [ModelManifest.forKnownModel] for built-in models, or supplied
 * externally (e.g., from a downloaded config file) for dynamic model registries.
 *
 * @property modelId             Canonical model identifier (e.g., [ModelAssetManager.MODEL_ID_VLM]).
 * @property modelVersion        Human-readable version string (e.g., "mai-ui-2b-Q4_K_M").
 * @property runtimeType         Required inference runtime type.
 * @property source              Where the model file can be obtained; null when not yet resolved.
 * @property minRuntimeVersion   Minimum required runtime version string; null = no constraint.
 * @property checksum            Expected SHA-256 hex string for the primary model file; null = skip.
 * @property quantization        Quantization scheme label (e.g., "Q4_K_M", "INT8"); null = unknown.
 * @property parameterCountM     Approximate parameter count in millions; null = unknown.
 * @property minDiskSpaceBytes   Minimum free disk space required to download and install this model,
 *                               in bytes. The provisioning pipeline uses this to decide whether to
 *                               attempt eviction before downloading. Null means no pre-check is
 *                               enforced.
 */
data class ModelManifest(
    val modelId: String,
    val modelVersion: String,
    val runtimeType: RuntimeType,
    val source: ModelSource? = null,
    val minRuntimeVersion: String? = null,
    val checksum: String? = null,
    val quantization: String? = null,
    val parameterCountM: Long? = null,
    val minDiskSpaceBytes: Long? = null
) {

    /**
     * Supported inference runtime backends.
     */
    enum class RuntimeType {
        /** llama.cpp GGUF runtime (used by the unified MAI-UI-2B model). */
        LLAMA_CPP,

        /** MLC-LLM runtime (alternative GGUF-class runtime). */
        MLC_LLM,

        /** NCNN lightweight CNN framework(已退役:历史 SeeClick 栈,保留枚举值以兼容旧数据)。 */
        NCNN,

        /** MNN inference runtime(已退役:历史备选,保留枚举值以兼容旧数据)。 */
        MNN,

        /** Runtime type is not specified or not recognised. */
        UNKNOWN
    }

    /**
     * Checks whether this manifest is compatible with the provided [runtimeVersion].
     *
     * Currently implements a simple prefix / equality check. Future milestones can
     * replace this with proper semantic-version comparison.
     *
     * @param runtimeVersion  Version string reported by the running inference server;
     *                        null when the server does not report a version.
     * @return [CompatibilityResult.Compatible] when the combination is known-good,
     *         [CompatibilityResult.Incompatible] when a mismatch is detected,
     *         [CompatibilityResult.Unknown] when there is insufficient information.
     */
    fun checkCompatibility(runtimeVersion: String?): CompatibilityResult {
        if (minRuntimeVersion == null) return CompatibilityResult.Unknown
        if (runtimeVersion == null) return CompatibilityResult.Unknown
        return if (runtimeVersion >= minRuntimeVersion) {
            CompatibilityResult.Compatible
        } else {
            CompatibilityResult.Incompatible(
                "Runtime version '$runtimeVersion' is below minimum '$minRuntimeVersion' " +
                    "required by model '$modelId'"
            )
        }
    }

    companion object {
        /**
         * Returns a [ModelManifest] for the well-known built-in models.
         *
         * Returns null for unrecognised [modelId] values so callers can treat the
         * absence of a manifest as [CompatibilityResult.Unknown] rather than an error.
         */
        fun forKnownModel(modelId: String): ModelManifest? = when (modelId) {
            // 统一 VLM:规划 + 定位由同一个 MAI-UI-2B 承担(llama.cpp)。
            // 取代旧的 MobileVLM(缺 mmproj、视觉从未生效)+ SeeClick NCNN(官方仓
            // 无 NCNN 端口、文件从未成功供给)组合。选型:2B 档 GUI 定位 SOTA
            // (ScreenSpot-Pro 57.4%),Qwen3-VL-2B 底座,Apache-2.0;GGUF 见
            // mradermacher/MAI-UI-2B-GGUF(社区量化;回退选项为官方
            // Qwen/Qwen3-VL-2B-Instruct-GGUF,同架构仅需改常量)。
            ModelAssetManager.MODEL_ID_VLM -> ModelManifest(
                modelId = ModelAssetManager.MODEL_ID_VLM,
                modelVersion = "mai-ui-2b-Q4_K_M",
                runtimeType = RuntimeType.LLAMA_CPP,
                source = ModelSource.HuggingFace(
                    repoId = "mradermacher/MAI-UI-2B-GGUF",
                    fileName = ModelAssetManager.VLM_FILE
                ),
                minRuntimeVersion = null,
                checksum = ModelAssetManager.VLM_SHA256,
                quantization = "Q4_K_M",
                parameterCountM = 2000L,
                // 2B Q4_K_M 权重约 1.1~1.3 GB(同架构 4B 官方 Q4_K_M 实证 2.5 GB 按比例推定),
                // 预留下载与解压缓冲。
                minDiskSpaceBytes = 1_600_000_000L
            )
            ModelAssetManager.MODEL_ID_VLM_MMPROJ -> ModelManifest(
                modelId = ModelAssetManager.MODEL_ID_VLM_MMPROJ,
                modelVersion = "mai-ui-2b-mmproj-f16",
                runtimeType = RuntimeType.LLAMA_CPP,
                source = ModelSource.HuggingFace(
                    repoId = "mradermacher/MAI-UI-2B-GGUF",
                    fileName = ModelAssetManager.VLM_MMPROJ_FILE
                ),
                minRuntimeVersion = null,
                checksum = ModelAssetManager.VLM_MMPROJ_SHA256,
                quantization = "F16",
                parameterCountM = 400L,
                // 视觉投影 F16 约数百 MB(同架构 4B 的 mmproj-F16 实证 ~836 MB),预留缓冲。
                minDiskSpaceBytes = 1_000_000_000L
            )
            else -> null
        }
    }
}

/**
 * Result of a [ModelManifest.checkCompatibility] call.
 */
sealed class CompatibilityResult {
    /** The runtime version is compatible with this model manifest. */
    object Compatible : CompatibilityResult()

    /**
     * The runtime version is known to be incompatible.
     * [reason] describes the specific mismatch for diagnostics.
     */
    data class Incompatible(val reason: String) : CompatibilityResult()

    /**
     * Compatibility cannot be determined (missing manifest or runtime version info).
     * The app should proceed with a warning rather than blocking execution.
     */
    object Unknown : CompatibilityResult()
}
