package com.ufo.galaxy.model

import android.content.Context
import android.util.Log
import java.io.File
import java.security.MessageDigest

/**
 * Manages local model files for MobileVLM and SeeClick inference.
 *
 * Model files are stored under [modelsDir] (default: [Context.getFilesDir]/models/ for Android).
 * This manager tracks readiness, exposes canonical file paths, and provides SHA-256 checksum
 * verification. Actual inference is delegated to the native runtimes (llama.cpp for MobileVLM,
 * NCNN for SeeClick); this class only manages the file-system contract between the app and
 * those runtimes.
 *
 * ## Checksum policy
 * - **MobileVLM**: a hardcoded SHA-256 constant is present; every verify call enforces it.
 * - **SeeClick (param + bin)**: SHA-256 is not pre-published. After the first successful
 *   download, call [persistComputedChecksum] to compute and store the digest. All
 *   subsequent [verifyModel] calls will enforce that persisted digest, protecting against
 *   corruption or tampering on the device. The initial trust-on-first-use window exists
 *   only for the very first download; re-downloads are always verified.
 *
 * Model IDs:
 *  - [MODEL_ID_MOBILEVLM] – MobileVLM V2-1.7B GGUF quantised weights
 *  - [MODEL_ID_SEECLICK]  – SeeClick NCNN param/bin pair (tracked via param file)
 *
 * @param modelsDir       Root directory for model file storage.
 * @param checksumOverrides  Per-model expected SHA-256 overrides; a key present with a
 *                           `null` value disables checksum verification for that model.
 *                           Intended for unit tests only; leave empty in production.
 */
class ModelAssetManager(
    val modelsDir: File,
    private val checksumOverrides: Map<String, String?> = emptyMap()
) {

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
        var expectedSha256: String?,
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
        const val MOBILEVLM_FILE = "ggml-model-q4_k.gguf"

        /** SeeClick NCNN model parameter file (companion to [SEECLICK_BIN_FILE]). */
        const val SEECLICK_PARAM_FILE = "seeclick.ncnn.param"

        /** SeeClick NCNN model binary weight file. */
        const val SEECLICK_BIN_FILE = "seeclick.ncnn.bin"

        /**
         * Expected SHA-256 checksums for each model file.
         *
         * **MobileVLM**: the digest is hardcoded for `ggml-model-q4_k.gguf` as published
         * in the ZiangWu/MobileVLM_V2-1.7B-GGUF Hugging Face repository. [verifyModel]
         * enforces this on every call and returns [ModelStatus.CORRUPTED] on mismatch.
         *
         * **SeeClick (param + bin)**: no pre-published digest exists for these files.
         * The constants are intentionally `null` here; after the first successful download,
         * call [persistComputedChecksum] to compute the SHA-256 from the file on disk
         * and persist it to [CHECKSUMS_FILE]. [verifyModel] will then load the persisted
         * digest and enforce it on all subsequent calls, protecting against later corruption.
         *
         * Update [MOBILEVLM_SHA256] whenever new MobileVLM weights are deployed to the
         * HuggingFace repository.
         */
        val MOBILEVLM_SHA256: String = "15d4bd09293404831902c23dd898aa2cc7b4b223b6c39a64e330601ef72d99db"
        val SEECLICK_SHA256: String? = null     // populated via persistComputedChecksum after first download
        val SEECLICK_BIN_SHA256: String? = null // populated via persistComputedChecksum after first download

        /**
         * File name for the persisted checksum store inside [modelsDir].
         * Stores SHA-256 values computed after the first download of each model file.
         */
        const val CHECKSUMS_FILE = ".checksums.json"

        /**
         * Remote download URLs for each model file.
         *
         * These constants are retained for direct reference and must remain aligned with the
         * [ModelSource.HuggingFace] entries in [ModelManifest.forKnownModel]. The canonical
         * source of truth for download URLs in provisioning code is the manifest; these
         * constants serve as documentation anchors and fallbacks only.
         *
         * **Alignment requirement**: [MOBILEVLM_DOWNLOAD_URL] must resolve to a file whose
         * local name matches [MOBILEVLM_FILE].
         */
        const val MOBILEVLM_DOWNLOAD_URL: String =
            "https://huggingface.co/ZiangWu/MobileVLM_V2-1.7B-GGUF/resolve/main/ggml-model-q4_k.gguf"
        const val SEECLICK_PARAM_DOWNLOAD_URL: String =
            "https://huggingface.co/cckevinn/SeeClick/resolve/main/ncnn/seeclick.ncnn.param"
        const val SEECLICK_BIN_DOWNLOAD_URL: String =
            "https://huggingface.co/cckevinn/SeeClick/resolve/main/ncnn/seeclick.ncnn.bin"
    }

    private val registry: MutableMap<String, ModelInfo> = mutableMapOf(
        MODEL_ID_MOBILEVLM to ModelInfo(
            id = MODEL_ID_MOBILEVLM,
            fileName = MOBILEVLM_FILE,
            expectedSha256 = resolveChecksum(MODEL_ID_MOBILEVLM, MOBILEVLM_SHA256)
        ),
        MODEL_ID_SEECLICK to ModelInfo(
            id = MODEL_ID_SEECLICK,
            fileName = SEECLICK_PARAM_FILE,
            expectedSha256 = resolveChecksum(MODEL_ID_SEECLICK, SEECLICK_SHA256)
        ),
        MODEL_ID_SEECLICK_BIN to ModelInfo(
            id = MODEL_ID_SEECLICK_BIN,
            fileName = SEECLICK_BIN_FILE,
            expectedSha256 = resolveChecksum(MODEL_ID_SEECLICK_BIN, SEECLICK_BIN_SHA256)
        )
    )

    /**
     * In-memory snapshot of persisted checksums loaded from [CHECKSUMS_FILE].
     * Updated by [persistComputedChecksum] and written back to disk.
     */
    private val persistedChecksums: MutableMap<String, String> = mutableMapOf()

    // Second init block: runs after registry and persistedChecksums are fully initialised.
    init {
        loadPersistedChecksums()
    }

    /**
     * Resolves the initial expected SHA-256 for a registry entry at construction time.
     * Priority: [checksumOverrides] (if key present) > [staticDefault].
     * Persisted checksums are applied separately in [loadPersistedChecksums].
     */
    private fun resolveChecksum(modelId: String, staticDefault: String?): String? {
        if (checksumOverrides.containsKey(modelId)) return checksumOverrides[modelId]
        return staticDefault
    }

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
            // No expected SHA-256 is configured for this model. This is acceptable on the
            // very first download (trust-on-first-use). Call persistComputedChecksum()
            // immediately after download to store the digest; all subsequent verify calls
            // will enforce it. For MobileVLM this branch should never be reached because
            // MOBILEVLM_SHA256 is always set.
            Log.w(TAG, "Model '$modelId' SHA-256 not yet available — " +
                "call persistComputedChecksum() after first download to enable verification.")
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
     * [ModelStatus.MISSING] or [ModelStatus.CORRUPTED] **and** has a resolvable remote source
     * declared in its [ModelManifest].
     *
     * The canonical download URL and checksum are derived exclusively from the model's
     * [ModelManifest] (via [ModelManifest.forKnownModel]), making the manifest the single
     * source of truth. Models without a manifest, or whose manifest source is a
     * [ModelSource.LocalPath], are silently skipped (no download needed).
     *
     * The param and binary SeeClick files are tracked and checked independently: if only the
     * binary file is missing, only its spec is returned.
     */
    fun downloadSpecsForMissing(): List<ModelDownloader.DownloadSpec> {
        val specs = mutableListOf<ModelDownloader.DownloadSpec>()
        for (info in registry.values) {
            if (info.status != ModelStatus.MISSING && info.status != ModelStatus.CORRUPTED) continue
            val manifest = ModelManifest.forKnownModel(info.id) ?: continue
            val url: String = when (val src = manifest.source) {
                is ModelSource.HuggingFace -> src.downloadUrl
                is ModelSource.CustomUrl   -> src.url
                is ModelSource.LocalPath   -> continue  // local path: no download required
                null                       -> continue  // no source configured
            }
            specs.add(
                ModelDownloader.DownloadSpec(
                    modelId = info.id,
                    url = url,
                    fileName = info.fileName,
                    expectedSha256 = manifest.checksum
                )
            )
        }
        return specs
    }

    /**
     * Returns the model file on disk for [modelId], or null if [modelId] is unrecognised.
     * Does not check whether the file exists.
     */
    fun fileFor(modelId: String): File? =
        registry[modelId]?.let { File(modelsDir, it.fileName) }

    /**
     * Returns the total number of bytes consumed by all recognised model files currently
     * present in [modelsDir]. Temporary (`.tmp`) files are excluded.
     *
     * Performs synchronous disk I/O; call from a background thread or coroutine.
     */
    fun storageUsageBytes(): Long =
        registry.values.sumOf { info ->
            val file = File(modelsDir, info.fileName)
            if (file.isFile) file.length() else 0L
        }

    /**
     * Returns true when [modelsDir] has at least [requiredBytes] of usable free space available.
     *
     * The provisioning pipeline calls this before starting a download so that a shortage can
     * be addressed (via [evictForStorage]) rather than failing mid-download with a disk-full
     * error. A null or non-positive [requiredBytes] value always returns true.
     */
    fun hasEnoughStorageFor(requiredBytes: Long?): Boolean {
        if (requiredBytes == null || requiredBytes <= 0L) return true
        return modelsDir.usableSpace >= requiredBytes
    }

    /**
     * Evicts [ModelStatus.READY] (not [ModelStatus.LOADED]) models from disk until
     * [requiredBytes] of free space is available in [modelsDir] or all evictable models
     * have been removed.
     *
     * Models are evicted in ascending order of parameter count (smallest first), so the
     * highest-value assets are retained the longest. [ModelStatus.LOADED] models are never
     * evicted because they are in active use by the inference runtime.
     *
     * @param requiredBytes  Target free-space threshold in bytes.
     * @return               IDs of the models that were removed.
     */
    fun evictForStorage(requiredBytes: Long): List<String> {
        // Identify evictable models sorted smallest-first.
        val evictable = registry.values
            .filter { it.status == ModelStatus.READY }
            .sortedBy { info ->
                ModelManifest.forKnownModel(info.id)?.parameterCountM ?: Long.MAX_VALUE
            }

        val evicted = mutableListOf<String>()
        for (info in evictable) {
            val file = File(modelsDir, info.fileName)
            if (file.exists()) {
                val fileSize = file.length()
                if (file.delete()) {
                    info.status = ModelStatus.MISSING
                    evicted.add(info.id)
                    Log.i(TAG, "evictForStorage: evicted '${info.id}' ($fileSize bytes freed)")
                }
            }
            // Re-check whether we have freed enough space.
            val usableBytes = modelsDir.usableSpace
            if (usableBytes >= requiredBytes) break
        }
        if (evicted.isEmpty()) {
            Log.d(TAG, "evictForStorage: nothing evicted (requiredBytes=$requiredBytes, " +
                "usable=${modelsDir.usableSpace})")
        }
        return evicted
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
            if (!file.isFile) return@forEach  // skip directories
            val isStale = file.name.endsWith(".tmp") ||
                (file.name !in knownFileNames && file.name != CHECKSUMS_FILE)
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

    /**
     * Computes the SHA-256 of the model file for [modelId] and persists it to
     * [CHECKSUMS_FILE] inside [modelsDir]. After this call, [verifyModel] will enforce
     * the stored digest on every subsequent invocation, protecting against corruption
     * or tampering. If the model file is absent, this method returns null without
     * writing anything.
     *
     * Call this immediately after a successful first download for any model whose
     * SHA-256 constant is null (currently SeeClick param and bin).
     *
     * @return The computed SHA-256 hex string, or null if the file was not found.
     */
    fun persistComputedChecksum(modelId: String): String? {
        val info = registry[modelId] ?: run {
            Log.w(TAG, "persistComputedChecksum: unknown model id '$modelId'")
            return null
        }
        val file = File(modelsDir, info.fileName)
        if (!file.exists()) {
            Log.w(TAG, "persistComputedChecksum: file absent for '$modelId'")
            return null
        }
        val sha256 = sha256(file)
        persistedChecksums[modelId] = sha256
        info.expectedSha256 = sha256
        writePersistedChecksums()
        Log.i(TAG, "Persisted checksum for '$modelId': $sha256")
        return sha256
    }

    /**
     * Returns the effective expected SHA-256 for [modelId]: the registry entry value
     * which reflects the priority order (override > static constant > persisted store).
     * Returns null if no checksum is currently available (first-download window).
     */
    fun effectiveChecksum(modelId: String): String? = registry[modelId]?.expectedSha256

    // ── Persisted checksum store ───────────────────────────────────────────────

    /**
     * Loads previously-persisted SHA-256 values from [CHECKSUMS_FILE] in [modelsDir]
     * and applies them to the registry for any model whose static constant is null.
     * Called once from [init].
     */
    private fun loadPersistedChecksums() {
        val file = File(modelsDir, CHECKSUMS_FILE)
        if (!file.exists()) return
        try {
            val json = file.readText()
            // Simple JSON parsing: {"key":"value",...}
            val pattern = Regex(""""([\w]+)"\s*:\s*"([0-9a-fA-F]{64})"""")
            pattern.findAll(json).forEach { match ->
                val modelId = match.groupValues[1]
                val sha256 = match.groupValues[2]
                persistedChecksums[modelId] = sha256
            }
            // Apply persisted checksums to registry entries where the static constant is null
            // and no override was supplied.
            for ((modelId, sha256) in persistedChecksums) {
                val info = registry[modelId] ?: continue
                if (!checksumOverrides.containsKey(modelId) && info.expectedSha256 == null) {
                    info.expectedSha256 = sha256
                }
            }
            Log.d(TAG, "Loaded ${persistedChecksums.size} persisted checksum(s) from $CHECKSUMS_FILE")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load persisted checksums: ${e.message}")
        }
    }

    /**
     * Writes [persistedChecksums] to [CHECKSUMS_FILE] in [modelsDir].
     */
    private fun writePersistedChecksums() {
        try {
            val json = buildString {
                append("{")
                persistedChecksums.entries.joinToString(",") { (k, v) ->
                    "\"$k\":\"$v\""
                }.also { append(it) }
                append("}")
            }
            File(modelsDir, CHECKSUMS_FILE).writeText(json)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to write persisted checksums: ${e.message}")
        }
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
