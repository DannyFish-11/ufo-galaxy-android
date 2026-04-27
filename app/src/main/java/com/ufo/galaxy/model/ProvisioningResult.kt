package com.ufo.galaxy.model

import java.io.File

/**
 * Sealed result type for a single model asset provisioning attempt.
 *
 * Each leaf type captures the precise failure mode so callers can:
 *  - log structured diagnostics
 *  - trigger the appropriate recovery path (retry, rollback, skip, reject)
 *  - surface honest error state to the capability-reporting layer
 *
 * The provisioning stages covered are:
 *
 *  ```
 *  compatibility check
 *       │ IncompatibleAsset
 *  low-storage check ─── InsufficientStorage
 *       │
 *  partial-asset cleanup
 *       │
 *  download ─── DownloadError
 *       │
 *  checksum verify ─── ChecksumMismatch
 *       │
 *  atomic install
 *       │ PartialAsset  (if re-download of a tmp-residue also fails)
 *  activation ─── ActivationError
 *       │
 *  Success
 *  ```
 */
sealed class ProvisioningResult {

    /**
     * Provisioning completed successfully.
     *
     * @param modelId       Logical identifier of the provisioned model.
     * @param installedFile Final installed model file (temp was atomically renamed).
     */
    data class Success(
        val modelId: String,
        val installedFile: File
    ) : ProvisioningResult()

    /**
     * Provisioning did not complete. Each sub-type carries the specific failure mode.
     */
    sealed class Failure : ProvisioningResult() {

        /**
         * The manifest's runtime type or version is incompatible with the current device
         * runtime. The asset was **not** downloaded.
         *
         * @param modelId  Logical model identifier from the manifest.
         * @param reason   Human-readable description of the incompatibility.
         */
        data class IncompatibleAsset(
            val modelId: String,
            val reason: String
        ) : Failure()

        /**
         * The HTTP download failed (non-2xx response, network timeout, I/O error).
         *
         * @param modelId  Logical model identifier from the spec.
         * @param cause    Human-readable description of the network/IO error.
         */
        data class DownloadError(
            val modelId: String,
            val cause: String
        ) : Failure()

        /**
         * The downloaded file's SHA-256 digest does not match the expected value in the
         * manifest / spec. The temp file has been deleted by the downloader.
         *
         * @param modelId  Logical model identifier.
         * @param expected Expected SHA-256 hex digest (from manifest or spec).
         * @param actual   Actual digest computed from the downloaded bytes, or "mismatch"
         *                 when the downloader did not expose the computed value.
         */
        data class ChecksumMismatch(
            val modelId: String,
            val expected: String,
            val actual: String
        ) : Failure()

        /**
         * A prior download was interrupted and a residual `.tmp` file was detected and
         * cleaned up. This leaf is emitted when the automatic re-download that follows
         * the cleanup also fails.
         *
         * @param modelId  Logical model identifier.
         * @param cause    Reason the re-download after cleanup failed.
         */
        data class PartialAsset(
            val modelId: String,
            val cause: String
        ) : Failure()

        /**
         * The model file was installed on disk but the inference runtime refused to
         * activate it (i.e., [ModelAssetManager.markLoaded] threw an exception). The model
         * remains on disk in [ModelAssetManager.ModelStatus.READY] state; the caller should
         * invoke [ModelProvisioningPipeline.rollback] if it wants to remove the file.
         *
         * @param modelId  Logical model identifier.
         * @param cause    Human-readable description of the activation failure.
         */
        data class ActivationError(
            val modelId: String,
            val cause: String
        ) : Failure()

        /**
         * There is not enough free disk space to download and install the model asset, even
         * after [ModelAssetManager.evictForStorage] attempted to free space by removing
         * [ModelAssetManager.ModelStatus.READY] models.
         *
         * The download was **not** attempted. Callers should surface this to the user as a
         * low-storage notification rather than retrying immediately.
         *
         * @param modelId        Logical model identifier.
         * @param requiredBytes  Minimum space declared by the manifest ([ModelManifest.minDiskSpaceBytes]).
         * @param availableBytes Actual usable space in the models directory at the time of the check.
         */
        data class InsufficientStorage(
            val modelId: String,
            val requiredBytes: Long,
            val availableBytes: Long
        ) : Failure()
    }
}
