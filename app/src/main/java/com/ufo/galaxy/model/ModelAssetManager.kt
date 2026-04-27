package com.ufo.galaxy.model

import android.content.Context
import android.util.Log
import java.io.File
import java.security.MessageDigest

/**
 * Manages local model files for MobileVLM and SeeClick inference.
 *
 * Model files are stored under [modelsDir] (default: [Context.getFilesDir]/models/ for Android).
 * This manager tracks readiness, exposes canonical file paths, and provides optional
 * SHA-256 checksum verification. Actual inference is delegated to the local servers
 * (llama.cpp for MobileVLM, NCNN for SeeClick); this class only manages the file-system
 * contract between the app and those servers.
 *
 * Model IDs:
 *  - [MODEL_ID_MOBILEVLM] – MobileVLM V2-1.7B GGUF quantised weights
 *  - [MODEL_ID_SEECLICK]  – SeeClick NCNN param/bin pair (tracked via param file)
 *
 * @param modelsDir Root directory for model file storage.
 */
class ModelAssetManager(val modelsDir: File) {

    /**
     * Android convenience constructor; uses [Context.getFilesDir]/models/ as storage root.
     */
    constructor(context: Context) : this(File(context.filesDir, MODELS_DIR))

    init {
        modelsDir.mkdirs()
    }

    enum class ModelStatus {
        /** Model file not present in local storage. */
        MISSING,
        /** Model file present but SHA-256 checksum does not match expected value. */
        CORRUPTED,
        /** Model file present and verified (or verification skipped). */
        READY,
        /** Model is currently loaded by the inference server (confirmed via loadModel). */
        LOADED
    }

    data class ModelInfo(
        val id: String,
        val fileName: String,
        val expectedSha256: String?,
        var status: ModelStatus = ModelStatus.MISSING
    )

    companion object {
        private const val TAG = "ModelAssetManager"

        const val MODEL_ID_MOBILEVLM = "mobilevlm"
        const val MODEL_ID_SEECLICK = "seeclick"

        /**
         * Logical identifier for the SeeClick NCNN binary weight file.
         * Tracked separately from [MODEL_ID_SEECLICK] (the param file) so that the registry
         * can independently verify presence, checksum, and download status of both NCNN files.
         */
        const val MODEL_ID_SEECLICK_BIN = "seeclick_bin"

        /** Sub-directory name under [Context.getFilesDir] for all model files. */
        const val MODELS_DIR = "models"

        /** MobileVLM V2-1.7B GGUF INT4 quantised weight file. */
        const val MOBILEVLM_FILE = "mobilevlm-v2-1.7b.Q4_K_M.gguf"

        /** SeeClick NCNN model parameter file (companion to [SEECLICK_BIN_FILE]). */
        const val SEECLICK_PARAM_FILE = "seeclick.ncnn.param"

        /** SeeClick NCNN model binary weight file. */
        const val SEECLICK_BIN_FILE = "seeclick.ncnn.bin"

        /**
         * Expected SHA-256 checksums for each model file.
         *
         * When non-null, [verifyModel] computes the file's SHA-256 digest and rejects any file
         * whose digest does not match, returning [ModelStatus.CORRUPTED].
         *
         * When null, verification is **explicitly bypassed** and a warning is emitted to logcat.
         * This bypass is intentional during development/prototyping only. Before shipping a
         * production build, replace these nulls with the actual digests of the deployed model
         * files so that [verifyModel] can detect corrupted or tampered weights.
         *
         * Update these constants whenever new model weights are deployed.
         */
        val MOBILEVLM_SHA256: String? = null   // TODO: set before production deployment
        val SEECLICK_SHA256: String? = null    // TODO: set before production deployment
        val SEECLICK_BIN_SHA256: String? = null // TODO: set before production deployment

        /**
         * Remote download URLs for each model file.
         * Set to a non-empty string to enable automatic download when files are missing.
         * Leave empty to require manual installation of model files.
         */
        const val MOBILEVLM_DOWNLOAD_URL: String =
            "https://huggingface.co/ZiangWu/MobileVLM_V2-1.7B-GGUF/resolve/main/mobilevlm-v2-1.7b.gguf"
        const val SEECLICK_PARAM_DOWNLOAD_URL: String =
            "https://huggingface.co/cckevinn/SeeClick/resolve/main/ncnn/seeclick.ncnn.param"
        const val SEECLICK_BIN_DOWNLOAD_URL: String =
            "https://huggingface.co/cckevinn/SeeClick/resolve/main/ncnn/seeclick.ncnn.bin"
    }

    private val registry: MutableMap<String, ModelInfo> = mutableMapOf(
        MODEL_ID_MOBILEVLM to ModelInfo(
            id = MODEL_ID_MOBILEVLM,
            fileName = MOBILEVLM_FILE,
            expectedSha256 = MOBILEVLM_SHA256
        ),
        MODEL_ID_SEECLICK to ModelInfo(
            id = MODEL_ID_SEECLICK,
            fileName = SEECLICK_PARAM_FILE,
            expectedSha256 = SEECLICK_SHA256
        ),
        MODEL_ID_SEECLICK_BIN to ModelInfo(
            id = MODEL_ID_SEECLICK_BIN,
            fileName = SEECLICK_BIN_FILE,
            expectedSha256 = SEECLICK_BIN_SHA256
        )
    )

    /** Full absolute path to the MobileVLM GGUF weight file. */
    val mobileVlmPath: String get() = File(modelsDir, MOBILEVLM_FILE).absolutePath

    /** Full absolute path to the SeeClick NCNN param file. */
    val seeClickParamPath: String get() = File(modelsDir, SEECLICK_PARAM_FILE).absolutePath

    /** Full absolute path to the SeeClick NCNN bin file. */
    val seeClickBinPath: String get() = File(modelsDir, SEECLICK_BIN_FILE).absolutePath

    /**
     * Checks whether [modelId] is present on disk and, if [ModelInfo.expectedSha256] is set,
     * verifies its checksum. Updates and returns the model's [ModelStatus].
     *
     * @param modelId One of [MODEL_ID_MOBILEVLM] or [MODEL_ID_SEECLICK].
     * @return Current [ModelStatus] after verification.
     */
    fun verifyModel(modelId: String): ModelStatus {
        val info = registry[modelId] ?: run {
            Log.w(TAG, "verifyModel: unknown model id '$modelId'")
            return ModelStatus.MISSING
        }
        val file = File(modelsDir, info.fileName)
        if (!file.exists()) {
            info.status = ModelStatus.MISSING
            Log.w(TAG, "Model '$modelId' missing: ${file.absolutePath}")
            return ModelStatus.MISSING
        }
        if (info.expectedSha256 != null) {
            val actual = sha256(file)
            if (!actual.equals(info.expectedSha256, ignoreCase = true)) {
                info.status = ModelStatus.CORRUPTED
                Log.e(TAG, "Model '$modelId' checksum mismatch — expected=${info.expectedSha256} actual=$actual")
                return ModelStatus.CORRUPTED
            }
        } else {
            // Verification is explicitly bypassed because no expected SHA-256 is configured.
            // This is acceptable during development but MUST be addressed before production
            // deployment. Set the corresponding *_SHA256 constant in the companion object to
            // enable integrity checking.
            Log.w(TAG, "Model '$modelId' SHA-256 verification SKIPPED — expectedSha256 is null. " +
                "Set the corresponding SHA-256 constant to enable integrity checking.")
        }
        info.status = ModelStatus.READY
        Log.i(TAG, "Model '$modelId' ready at ${file.absolutePath}")
        return ModelStatus.READY
    }

    /**
     * Verifies all registered models and returns a map of id → [ModelStatus].
     */
    fun verifyAll(): Map<String, ModelStatus> =
        registry.keys.associateWith { verifyModel(it) }

    /**
     * Marks [modelId] as [ModelStatus.LOADED] after the inference server confirms readiness.
     */
    fun markLoaded(modelId: String) {
        registry[modelId]?.let {
            it.status = ModelStatus.LOADED
            Log.i(TAG, "Model '$modelId' marked LOADED")
        } ?: Log.w(TAG, "markLoaded: unknown model id '$modelId'")
    }

    /**
     * Marks [modelId] as unloaded; reverts to [ModelStatus.READY] if the file is still present,
     * otherwise [ModelStatus.MISSING].
     */
    fun markUnloaded(modelId: String) {
        registry[modelId]?.let {
            val file = File(modelsDir, it.fileName)
            it.status = if (file.exists()) ModelStatus.READY else ModelStatus.MISSING
            Log.i(TAG, "Model '$modelId' marked ${it.status}")
        } ?: Log.w(TAG, "markUnloaded: unknown model id '$modelId'")
    }

    /**
     * Returns the current [ModelStatus] for [modelId] without performing disk I/O.
     * Call [verifyModel] first to get an accurate result.
     */
    fun getStatus(modelId: String): ModelStatus =
        registry[modelId]?.status ?: ModelStatus.MISSING

    /**
     * Returns true when both [MODEL_ID_MOBILEVLM] and [MODEL_ID_SEECLICK] are [ModelStatus.LOADED].
     */
    fun areAllModelsLoaded(): Boolean =
        getStatus(MODEL_ID_MOBILEVLM) == ModelStatus.LOADED &&
            getStatus(MODEL_ID_SEECLICK) == ModelStatus.LOADED

    /**
     * Returns a human-readable capabilities error when models are not loaded.
     * Returns null if all models are loaded.
     */
    fun readinessError(): String? {
        if (areAllModelsLoaded()) return null
        val missing = registry.values
            .filter { it.status != ModelStatus.LOADED }
            .joinToString { "'${it.id}' (${it.status})" }
        return "Local models not ready: $missing"
    }

    /**
     * Returns [ModelDownloader.DownloadSpec] entries for every model file that is currently
     * [ModelStatus.MISSING] or [ModelStatus.CORRUPTED] **and** has a non-empty download URL
     * configured in the companion object.
     *
     * The param and binary SeeClick files are tracked and checked independently: if only the
     * binary file is missing, only its spec is returned. If no download URLs are configured,
     * the list is empty and no network access will occur.
     */
    fun downloadSpecsForMissing(): List<ModelDownloader.DownloadSpec> {
        val specs = mutableListOf<ModelDownloader.DownloadSpec>()
        val vlmStatus = getStatus(MODEL_ID_MOBILEVLM)
        if (vlmStatus == ModelStatus.MISSING || vlmStatus == ModelStatus.CORRUPTED) {
            if (MOBILEVLM_DOWNLOAD_URL.isNotEmpty()) {
                specs.add(
                    ModelDownloader.DownloadSpec(
                        modelId = MODEL_ID_MOBILEVLM,
                        url = MOBILEVLM_DOWNLOAD_URL,
                        fileName = MOBILEVLM_FILE,
                        expectedSha256 = MOBILEVLM_SHA256
                    )
                )
            }
        }
        val scParamStatus = getStatus(MODEL_ID_SEECLICK)
        if (scParamStatus == ModelStatus.MISSING || scParamStatus == ModelStatus.CORRUPTED) {
            if (SEECLICK_PARAM_DOWNLOAD_URL.isNotEmpty()) {
                specs.add(
                    ModelDownloader.DownloadSpec(
                        modelId = MODEL_ID_SEECLICK,
                        url = SEECLICK_PARAM_DOWNLOAD_URL,
                        fileName = SEECLICK_PARAM_FILE,
                        expectedSha256 = SEECLICK_SHA256
                    )
                )
            }
        }
        val scBinStatus = getStatus(MODEL_ID_SEECLICK_BIN)
        if (scBinStatus == ModelStatus.MISSING || scBinStatus == ModelStatus.CORRUPTED) {
            if (SEECLICK_BIN_DOWNLOAD_URL.isNotEmpty()) {
                specs.add(
                    ModelDownloader.DownloadSpec(
                        modelId = MODEL_ID_SEECLICK_BIN,
                        url = SEECLICK_BIN_DOWNLOAD_URL,
                        fileName = SEECLICK_BIN_FILE,
                        expectedSha256 = SEECLICK_BIN_SHA256
                    )
                )
            }
        }
        return specs
    }

    /**
     * Deletes orphaned and incomplete files from [modelsDir].
     *
     * The following files are removed:
     * - Any file with a `.tmp` extension (incomplete downloads).
     * - Any file that is not listed in the model registry (unrecognised / stale assets).
     *
     * This method performs synchronous disk I/O. Call it from a background thread or
     * coroutine (e.g., on [kotlinx.coroutines.Dispatchers.IO]) when the inference runtimes
     * are stopped.
     *
     * @return The number of files deleted.
     */
    fun cleanupStaleFiles(): Int {
        val knownFileNames = registry.values.map { it.fileName }.toSet()
        var deleted = 0
        modelsDir.listFiles()?.forEach { file ->
            val isStale = file.name.endsWith(".tmp") || file.name !in knownFileNames
            if (isStale && file.delete()) {
                deleted++
                Log.i(TAG, "Cleaned up stale file: ${file.name}")
            }
        }
        if (deleted > 0) {
            Log.i(TAG, "cleanupStaleFiles: removed $deleted file(s) from ${modelsDir.absolutePath}")
        }
        return deleted
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream ->
            val buffer = ByteArray(8192)
            var read: Int
            while (stream.read(buffer).also { read = it } != -1) {
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
