package com.ufo.galaxy.model

import android.util.Log
import java.io.File

/**
 * Formal provisioning pipeline for Android local-intelligence model assets.
 *
 * This class is the single entry point for bringing a model from "absent or stale" to
 * "installed, verified, and activated". It coordinates the following stages in order:
 *
 * 1. **Compatibility check** — rejects manifests incompatible with the current runtime before
 *    issuing any network request ([CompatibilityResult.Incompatible] → [ProvisioningResult.Failure.IncompatibleAsset]).
 * 2. **Low-storage check** — when the resolved manifest declares [ModelManifest.minDiskSpaceBytes],
 *    the pipeline verifies that sufficient space is available. If space is short it attempts
 *    [ModelAssetManager.evictForStorage] before proceeding; if space is still insufficient after
 *    eviction, [ProvisioningResult.Failure.InsufficientStorage] is returned.
 * 3. **Partial-asset cleanup** — removes any leftover `.tmp` file from a prior interrupted
 *    download so the subsequent download starts from a clean state.
 * 4. **Download** — fetches the model file from the source URL via [ModelDownloader],
 *    streaming progress events to the optional [onProgress] callback.
 * 5. **Checksum verify** — SHA-256 validation is performed inside [ModelDownloader];
 *    a mismatch surfaces as [ProvisioningResult.Failure.ChecksumMismatch].
 * 6. **Atomic install** — [ModelDownloader] promotes the temp file to the final path via
 *    rename, ensuring the destination is never partially written.
 * 7. **Activation** — marks the model as [ModelAssetManager.ModelStatus.LOADED] in the
 *    registry via [ModelAssetManager.markLoaded].
 * 8. **Rollback** — if activation throws, the model is reverted to
 *    [ModelAssetManager.ModelStatus.READY] and [ProvisioningResult.Failure.ActivationError]
 *    is returned. Callers can call [rollback] explicitly to also delete the installed file.
 *
 * The pipeline is idempotent: if the target model is already
 * [ModelAssetManager.ModelStatus.LOADED], [ProvisioningResult.Success] is returned
 * immediately without disk or network I/O.
 *
 * When [manifest] is null, all manifest-driven pipeline stages are skipped. Pass a manifest
 * explicitly (or via [ModelManifest.forKnownModel]) to enable compatibility checking,
 * low-storage pre-check, and governance enforcement.
 *
 * @param modelAssetManager  Model file registry; consulted for status and updated after install.
 * @param modelDownloader    Network downloader; provides download + checksum verification.
 */
class ModelProvisioningPipeline(
    private val modelAssetManager: ModelAssetManager,
    private val modelDownloader: ModelDownloader
) {

    companion object {
        private const val TAG = "ModelProvisioningPipeline"
    }

    /**
     * Runs the full provisioning pipeline for a single model.
     *
     * When [manifest] is null, all manifest-driven pipeline stages (compatibility check,
     * low-storage pre-check) are skipped. Pass a manifest explicitly — or obtain one from
     * [ModelManifest.forKnownModel] — to enable full governance enforcement.
     *
     * Suspend-safe: the download runs on [Dispatchers.IO] inside [ModelDownloader.downloadSync];
     * this function can be called from any coroutine context.
     *
     * @param spec              Download spec: model id, URL, local file name, expected SHA-256.
     * @param manifest          Manifest for compatibility and storage pre-checks; null skips both.
     * @param runtimeVersion    Running runtime version string; used with the resolved manifest to
     *                          evaluate [ModelManifest.checkCompatibility]. Null yields
     *                          [CompatibilityResult.Unknown], which is treated as "proceed".
     * @param onProgress        Invoked for each [ModelDownloader.DownloadStatus.Progress] event
     *                          during the download stage; null disables progress callbacks.
     * @return                  Terminal [ProvisioningResult] describing success or the specific
     *                          failure stage.
     */
    suspend fun provision(
        spec: ModelDownloader.DownloadSpec,
        manifest: ModelManifest? = null,
        runtimeVersion: String? = null,
        onProgress: ((ModelDownloader.DownloadStatus.Progress) -> Unit)? = null
    ): ProvisioningResult {
        val modelId = spec.modelId

        // ── Fast-path: already loaded ─────────────────────────────────────────
        if (modelAssetManager.getStatus(modelId) == ModelAssetManager.ModelStatus.LOADED) {
            val file = File(modelAssetManager.modelsDir, spec.fileName)
            Log.d(TAG, "provision: '$modelId' already LOADED — skipping pipeline")
            return ProvisioningResult.Success(modelId, file)
        }

        // ── Stage 1: Compatibility check ──────────────────────────────────────
        if (manifest != null) {
            val compat = manifest.checkCompatibility(runtimeVersion)
            if (compat is CompatibilityResult.Incompatible) {
                Log.w(TAG, "provision: '$modelId' rejected — incompatible: ${compat.reason}")
                return ProvisioningResult.Failure.IncompatibleAsset(modelId, compat.reason)
            }
        }

        // ── Stage 2: Low-storage pre-check ────────────────────────────────────
        val requiredBytes = manifest?.minDiskSpaceBytes
        if (requiredBytes != null && requiredBytes > 0L) {
            if (!modelAssetManager.hasEnoughStorageFor(requiredBytes)) {
                Log.w(TAG, "provision: '$modelId' — low storage; attempting eviction to free ${requiredBytes} bytes")
                modelAssetManager.evictForStorage(requiredBytes)
                if (!modelAssetManager.hasEnoughStorageFor(requiredBytes)) {
                    val available = modelAssetManager.modelsDir.usableSpace
                    val msg = "Insufficient storage for '$modelId': required=$requiredBytes available=$available"
                    Log.e(TAG, msg)
                    return ProvisioningResult.Failure.InsufficientStorage(modelId, requiredBytes, available)
                }
                Log.i(TAG, "provision: '$modelId' — eviction freed enough space; proceeding with download")
            }
        }

        // ── Stage 3: Partial-asset cleanup ────────────────────────────────────
        val tmpFile = File(modelAssetManager.modelsDir, "${spec.fileName}.tmp")
        if (tmpFile.exists()) {
            Log.w(TAG, "provision: removing leftover partial asset '${tmpFile.name}' before download")
            tmpFile.delete()
        }

        // ── Stages 4–6: Download → verify → atomic install ────────────────────
        var downloadFailure: ProvisioningResult.Failure? = null
        val downloadOk = modelDownloader.downloadSync(spec) { status ->
            when (status) {
                is ModelDownloader.DownloadStatus.Progress -> onProgress?.invoke(status)
                is ModelDownloader.DownloadStatus.Failure -> {
                    val isChecksum = status.error.contains("Checksum", ignoreCase = true)
                    downloadFailure = if (isChecksum) {
                        ProvisioningResult.Failure.ChecksumMismatch(
                            modelId = modelId,
                            expected = spec.expectedSha256 ?: "unknown",
                            actual = "mismatch"
                        )
                    } else {
                        ProvisioningResult.Failure.DownloadError(modelId, status.error)
                    }
                }
                else -> Unit
            }
        }

        if (!downloadOk) {
            // If a .tmp residue appeared during this download attempt (interrupted mid-stream),
            // clean it up and surface PartialAsset to distinguish from a clean DownloadError.
            if (tmpFile.exists()) {
                tmpFile.delete()
                val cause = when (val f = downloadFailure) {
                    is ProvisioningResult.Failure.DownloadError -> f.cause
                    else -> "interrupted download"
                }
                return ProvisioningResult.Failure.PartialAsset(modelId, cause)
            }
            return downloadFailure
                ?: ProvisioningResult.Failure.DownloadError(modelId, "unknown download error")
        }

        // ── Stage 7: Activation ───────────────────────────────────────────────
        val installedFile = File(modelAssetManager.modelsDir, spec.fileName)
        return try {
            modelAssetManager.markLoaded(modelId)
            Log.i(TAG, "provision: '$modelId' provisioned and activated at ${installedFile.absolutePath}")
            ProvisioningResult.Success(modelId, installedFile)
        } catch (e: Exception) {
            // ── Stage 8: Rollback ─────────────────────────────────────────────
            val msg = "Activation failed for '$modelId': ${e.message}"
            Log.e(TAG, msg, e)
            // Revert status to reflect actual disk state (file is present but not loaded).
            modelAssetManager.markUnloaded(modelId)
            ProvisioningResult.Failure.ActivationError(modelId, msg)
        }
    }

    /**
     * Rolls back a previously activated (or partially provisioned) model:
     * - Marks the model as unloaded ([ModelAssetManager.ModelStatus.READY] if the file is
     *   still present, [ModelAssetManager.ModelStatus.MISSING] otherwise).
     * - When [deleteFile] is true, also removes the installed model file from disk, freeing
     *   storage and preventing the model from being re-activated without a fresh provision.
     *
     * @param modelId    Logical model identifier to roll back.
     * @param deleteFile If true, deletes the installed model file.
     */
    fun rollback(modelId: String, deleteFile: Boolean = false) {
        if (deleteFile) {
            val file = modelAssetManager.fileFor(modelId)
            if (file != null && file.exists() && file.delete()) {
                Log.i(TAG, "rollback: deleted installed file '${file.name}' for '$modelId'")
            }
        }
        modelAssetManager.markUnloaded(modelId)
        Log.i(TAG, "rollback: '$modelId' reverted to ${modelAssetManager.getStatus(modelId)}")
    }
}
