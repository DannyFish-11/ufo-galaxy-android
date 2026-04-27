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
     * @param repoId    Repository identifier in `owner/repo` format (e.g., "ZiangWu/MobileVLM_V2-1.7B-GGUF").
     * @param fileName  File name within the repository (e.g., "mobilevlm-v2-1.7b.Q4_K_M.gguf").
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
 * @property modelId             Canonical model identifier (e.g., [ModelAssetManager.MODEL_ID_MOBILEVLM]).
 * @property modelVersion        Human-readable version string (e.g., "v2-1.7b-Q4_K_M").
 * @property runtimeType         Required inference runtime type.
 * @property source              Where the model file can be obtained; null when not yet resolved.
 * @property minRuntimeVersion   Minimum required runtime version string; null = no constraint.
 * @property checksum            Expected SHA-256 hex string for the primary model file; null = skip.
 * @property quantization        Quantization scheme label (e.g., "Q4_K_M", "INT8"); null = unknown.
 * @property parameterCountM     Approximate parameter count in millions; null = unknown.
 */
data class ModelManifest(
    val modelId: String,
    val modelVersion: String,
    val runtimeType: RuntimeType,
    val source: ModelSource? = null,
    val minRuntimeVersion: String? = null,
    val checksum: String? = null,
    val quantization: String? = null,
    val parameterCountM: Long? = null
) {

    /**
     * Supported inference runtime backends.
     */
    enum class RuntimeType {
        /** llama.cpp GGUF runtime (used by MobileVLM). */
        LLAMA_CPP,

        /** MLC-LLM runtime (alternative to llama.cpp for MobileVLM). */
        MLC_LLM,

        /** NCNN lightweight CNN framework (used by SeeClick). */
        NCNN,

        /** MNN inference runtime (alternative to NCNN for SeeClick). */
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
            ModelAssetManager.MODEL_ID_MOBILEVLM -> ModelManifest(
                modelId = ModelAssetManager.MODEL_ID_MOBILEVLM,
                modelVersion = "v2-1.7b-Q4_K_M",
                runtimeType = RuntimeType.LLAMA_CPP,
                source = ModelSource.HuggingFace(
                    repoId = "ZiangWu/MobileVLM_V2-1.7B-GGUF",
                    fileName = ModelAssetManager.MOBILEVLM_FILE
                ),
                minRuntimeVersion = null,
                checksum = ModelAssetManager.MOBILEVLM_SHA256,
                quantization = "Q4_K_M",
                parameterCountM = 1700L
            )
            ModelAssetManager.MODEL_ID_SEECLICK -> ModelManifest(
                modelId = ModelAssetManager.MODEL_ID_SEECLICK,
                modelVersion = "seeclick-ncnn",
                runtimeType = RuntimeType.NCNN,
                source = ModelSource.HuggingFace(
                    repoId = "cckevinn/SeeClick",
                    fileName = "ncnn/${ModelAssetManager.SEECLICK_PARAM_FILE}"
                ),
                minRuntimeVersion = null,
                checksum = ModelAssetManager.SEECLICK_SHA256,
                quantization = null,
                parameterCountM = null
            )
            ModelAssetManager.MODEL_ID_SEECLICK_BIN -> ModelManifest(
                modelId = ModelAssetManager.MODEL_ID_SEECLICK_BIN,
                modelVersion = "seeclick-ncnn",
                runtimeType = RuntimeType.NCNN,
                source = ModelSource.HuggingFace(
                    repoId = "cckevinn/SeeClick",
                    fileName = "ncnn/${ModelAssetManager.SEECLICK_BIN_FILE}"
                ),
                minRuntimeVersion = null,
                checksum = ModelAssetManager.SEECLICK_BIN_SHA256,
                quantization = null,
                parameterCountM = null
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
