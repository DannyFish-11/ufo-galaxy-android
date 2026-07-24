package com.ufo.galaxy.model

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonSyntaxException
import java.io.File
import java.security.MessageDigest

/**
 * Manages local model files for the unified on-device VLM(MAI-UI-2B,Qwen3-VL-2B 底座)。
 *
 * Model files are stored under [modelsDir] (default: [Context.getFilesDir]/models/ for Android).
 * This manager tracks readiness, exposes canonical file paths, and provides SHA-256 checksum
 * verification. Actual inference is delegated to the llama.cpp runtime; this class only
 * manages the file-system contract between the app and that runtime.
 *
 * ## 单模型双职(替换 MobileVLM + SeeClick 的历史组合)
 * 规划(planning)与定位(grounding)由**同一个** MAI-UI-2B 模型承担,
 * 跑在同一个 llama.cpp 服务上。历史组合被替换的实证原因:
 *  - 旧 MobileVLM 只发布了语言模型 GGUF,资产清单从未包含 mmproj 视觉投影文件,
 *    llama.cpp 因此从来无法处理截图 —— "V" 从未生效;
 *  - 旧 SeeClick 走 NCNN,而 SeeClick 本体是 Qwen-VL 9.6B,官方仓不存在 NCNN 端口,
 *    该文件从未成功供给。NCNN 栈随本次替换整体退役。
 *
 * ## Checksum policy
 * 两个模型文件的 SHA-256 均未在本仓预置(trust-on-first-use)。首次成功下载后
 * 调用 [persistComputedChecksum] 计算并持久化摘要;此后所有 [verifyModel] 调用都会
 * 强制校验,防止后续损坏或篡改。TOFU 窗口仅存在于首次下载。
 *
 * Model IDs:
 *  - [MODEL_ID_VLM]        – MAI-UI-2B Q4_K_M GGUF 语言模型权重
 *  - [MODEL_ID_VLM_MMPROJ] – MAI-UI-2B F16 mmproj 视觉投影权重
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

        /** 统一 VLM(MAI-UI-2B,Qwen3-VL-2B 底座的 GUI 专精模型)语言模型 GGUF 的逻辑标识。 */
        const val MODEL_ID_VLM = "mai_ui_2b"

        /**
         * Logical identifier for the MAI-UI-2B mmproj vision-projector weight file.
         * Tracked separately from [MODEL_ID_VLM] (the LLM weights) so that the registry
         * can independently verify presence, checksum, and download status of both files.
         * llama.cpp needs BOTH files to process screenshots (`--mmproj`);缺 mmproj 时
         * 服务只能做纯文本推理 —— 这正是旧 MobileVLM 组合"从未看见过屏幕"的根因。
         */
        const val MODEL_ID_VLM_MMPROJ = "mai_ui_2b_mmproj"

        /** Sub-directory name under [Context.getFilesDir] for all model files. */
        const val MODELS_DIR = "models"

        /**
         * MAI-UI-2B Q4_K_M quantised LLM weight file.
         *
         * 选型依据(所有者要求视觉/定位取 2B 档最强):MAI-UI-2B(通义 Tongyi-MAI,
         * Apache-2.0,Qwen3-VL-2B 底座的 GUI 专精模型)为 2B 档 GUI 定位 SOTA
         * (ScreenSpot-Pro 57.4%;对比:旧 SeeClick 同基准约 1%)。GGUF 来自社区量化仓
         * mradermacher/MAI-UI-2B-GGUF(官方未发 GGUF;该仓 25 个量化档,推荐 Q4_K_M,
         * mmproj 文件名已实证)。若该量化质量有问题,回退选项为官方
         * Qwen/Qwen3-VL-2B-Instruct-GGUF(同架构、同协议,仅需改本文件与
         * [ModelManifest.forKnownModel] 的常量)。
         */
        const val VLM_FILE = "MAI-UI-2B.Q4_K_M.gguf"

        /** MAI-UI-2B F16 mmproj vision-projector file(文件名依 mradermacher 仓实证)。 */
        const val VLM_MMPROJ_FILE = "MAI-UI-2B.mmproj-f16.gguf"

        /**
         * Expected SHA-256 checksums for each model file.
         *
         * 两个文件均未预置摘要(trust-on-first-use):首次成功下载后调用
         * [persistComputedChecksum] 计算并持久化到 [CHECKSUMS_FILE],此后每次
         * [verifyModel] 都会强制校验,防止后续损坏或篡改。
         */
        val VLM_SHA256: String? = null        // populated via persistComputedChecksum after first download
        val VLM_MMPROJ_SHA256: String? = null // populated via persistComputedChecksum after first download

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
         * **Alignment requirement**: [VLM_DOWNLOAD_URL] must resolve to a file whose
         * local name matches [VLM_FILE].
         */
        const val VLM_DOWNLOAD_URL: String =
            "https://huggingface.co/mradermacher/MAI-UI-2B-GGUF/resolve/main/MAI-UI-2B.Q4_K_M.gguf"
        const val VLM_MMPROJ_DOWNLOAD_URL: String =
            "https://huggingface.co/mradermacher/MAI-UI-2B-GGUF/resolve/main/MAI-UI-2B.mmproj-f16.gguf"
    }

    private val registry: MutableMap<String, ModelInfo> = mutableMapOf(
        MODEL_ID_VLM to ModelInfo(
            id = MODEL_ID_VLM,
            fileName = VLM_FILE,
            expectedSha256 = resolveChecksum(MODEL_ID_VLM, VLM_SHA256)
        ),
        MODEL_ID_VLM_MMPROJ to ModelInfo(
            id = MODEL_ID_VLM_MMPROJ,
            fileName = VLM_MMPROJ_FILE,
            expectedSha256 = resolveChecksum(MODEL_ID_VLM_MMPROJ, VLM_MMPROJ_SHA256)
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

    /** Full absolute path to the Qwen3-VL GGUF LLM weight file. */
    val vlmModelPath: String get() = File(modelsDir, VLM_FILE).absolutePath

    /** Full absolute path to the Qwen3-VL mmproj vision-projector file. */
    val vlmMmprojPath: String get() = File(modelsDir, VLM_MMPROJ_FILE).absolutePath

    /**
     * Checks whether [modelId] is present on disk and, if [ModelInfo.expectedSha256] is set,
     * verifies its checksum. Updates and returns the model's [ModelStatus].
     *
     * @param modelId One of [MODEL_ID_VLM] or [MODEL_ID_VLM_MMPROJ].
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
            // will enforce it.
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
     * Returns true when both [MODEL_ID_VLM] and [MODEL_ID_VLM_MMPROJ] are [ModelStatus.LOADED]
     * (即 llama.cpp 服务同时装载了语言模型与视觉投影,具备完整多模态能力)。
     */
    fun areAllModelsLoaded(): Boolean =
        getStatus(MODEL_ID_VLM) == ModelStatus.LOADED &&
            getStatus(MODEL_ID_VLM_MMPROJ) == ModelStatus.LOADED

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
     * The LLM and mmproj files are tracked and checked independently: if only the
     * mmproj file is missing, only its spec is returned.
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
     * SHA-256 constant is null (currently both Qwen3-VL files).
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
            val root = Gson().fromJson(json, JsonObject::class.java) ?: return
            root.entrySet().forEach { (modelId, element) ->
                val sha256 = element.asString
                if (sha256.matches(Regex("[0-9a-fA-F]{64}"))) {
                    persistedChecksums[modelId] = sha256
                } else {
                    Log.w(TAG, "Skipping invalid persisted checksum for '$modelId': $sha256")
                }
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
        } catch (e: JsonSyntaxException) {
            Log.w(TAG, "Persisted checksums file is malformed JSON — ignoring: ${e.message}")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load persisted checksums: ${e.message}")
        }
    }

    /**
     * Writes [persistedChecksums] to [CHECKSUMS_FILE] in [modelsDir].
     */
    private fun writePersistedChecksums() {
        try {
            val root = JsonObject()
            persistedChecksums.forEach { (modelId, sha256) -> root.addProperty(modelId, sha256) }
            File(modelsDir, CHECKSUMS_FILE).writeText(Gson().toJson(root))
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
