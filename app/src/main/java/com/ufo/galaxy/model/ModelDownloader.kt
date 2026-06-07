package com.ufo.galaxy.model

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Lightweight model file downloader with progress callbacks and optional SHA-256
 * checksum verification.
 *
 * Downloads run on [Dispatchers.IO] and never block the calling thread.
 * The [HttpFactory] abstraction decouples network I/O from business logic, enabling
 * deterministic JVM unit tests without real network connections.
 *
 * Usage:
 * ```kotlin
 * downloader.enqueueDownload(spec) { status ->
 *     when (status) {
 *         is DownloadStatus.Progress -> updateProgressBar(status.bytesDownloaded, status.totalBytes)
 *         is DownloadStatus.Success  -> onModelReady(status.file)
 *         is DownloadStatus.Failure  -> showError(status.error)
 *     }
 * }
 * ```
 *
 * @param modelsDir   Directory where downloaded files are stored; created if absent.
 * @param scope       Coroutine scope for async download jobs. The caller owns the lifecycle;
 *                    call [cancel] when the scope is no longer needed.
 * @param httpFactory Factory that creates [HttpURLConnection] instances; override in tests.
 */
class ModelDownloader(
    private val modelsDir: File,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
    internal val httpFactory: HttpFactory = DefaultHttpFactory()
) {

    /** Abstracts HTTP connection creation so unit tests can inject a fake. */
    fun interface HttpFactory {
        fun open(url: String): HttpURLConnection
    }

    private class DefaultHttpFactory : HttpFactory {
        override fun open(url: String): HttpURLConnection =
            URL(url).openConnection() as HttpURLConnection
    }

    /**
     * Describes a single model file to be downloaded.
     *
     * @param modelId        Logical identifier (e.g. [ModelAssetManager.MODEL_ID_MOBILEVLM]).
     * @param url            Remote URL of the model file.
     * @param fileName       Local file name within [modelsDir].
     * @param expectedSha256 Expected SHA-256 hex digest; null skips verification.
     */
    data class DownloadSpec(
        val modelId: String,
        val url: String,
        val fileName: String,
        val expectedSha256: String? = null
    )

    /** Represents a download lifecycle event delivered to [ProgressCallback]. */
    sealed class DownloadStatus {
        /**
         * Download is in progress.
         * [totalBytes] is -1 when the server does not report a content-length.
         */
        data class Progress(
            val modelId: String,
            val bytesDownloaded: Long,
            val totalBytes: Long
        ) : DownloadStatus()

        /** File download completed and, when requested, checksum verification passed. */
        data class Success(val modelId: String, val file: File) : DownloadStatus()

        /** Download or checksum verification failed; [error] contains a human-readable message. */
        data class Failure(val modelId: String, val error: String) : DownloadStatus()
    }

    /** Callback invoked on the downloading coroutine for each [DownloadStatus] event. */
    fun interface ProgressCallback {
        fun onStatus(status: DownloadStatus)
    }

    /**
     * Enqueues [spec] for download in the background coroutine [scope].
     * Returns immediately; [callback] is invoked for progress, success, and failure events.
     *
     * If the destination file already exists and passes checksum verification (or no
     * checksum is configured), [DownloadStatus.Success] is reported immediately without
     * issuing a network request.
     */
    fun enqueueDownload(spec: DownloadSpec, callback: ProgressCallback) {
        scope.launch { performDownload(spec, callback) }
    }

    /**
     * Suspending version of [enqueueDownload] that blocks the calling coroutine until
     * the download finishes.
     *
     * @return true on [DownloadStatus.Success]; false on [DownloadStatus.Failure].
     */
    suspend fun downloadSync(spec: DownloadSpec, callback: ProgressCallback): Boolean {
        var succeeded = false
        withContext(Dispatchers.IO) {
            performDownload(spec) { status ->
                callback.onStatus(status)
                if (status is DownloadStatus.Success) succeeded = true
            }
        }
        return succeeded
    }

    /** Cancels all pending downloads and releases the internal coroutine scope. */
    fun cancel() {
        scope.cancel()
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private fun performDownload(spec: DownloadSpec, callback: ProgressCallback) {
        modelsDir.mkdirs()
        val destFile = File(modelsDir, spec.fileName)

        // Fast-path: file already present and valid — skip network round-trip.
        if (destFile.exists() && checksumOk(destFile, spec.expectedSha256)) {
            callback.onStatus(DownloadStatus.Success(spec.modelId, destFile))
            return
        }

        // Remove a potentially corrupted existing file before re-downloading.
        if (destFile.exists()) destFile.delete()

        val tmpFile = File(modelsDir, "${spec.fileName}.tmp")
        try {
            val conn = httpFactory.open(spec.url)
            conn.connectTimeout = 30_000
            conn.readTimeout = 60_000
            try {
                val code = conn.responseCode
                if (code !in 200..299) {
                    throw IOException("HTTP $code downloading ${spec.url}")
                }
                val totalBytes = conn.contentLengthLong
                var downloaded = 0L

                conn.inputStream.use { input ->
                    tmpFile.outputStream().use { out ->
                        val buf = ByteArray(8_192)
                        var n: Int
                        while (input.read(buf).also { n = it } != -1) {
                            out.write(buf, 0, n)
                            downloaded += n
                            callback.onStatus(
                                DownloadStatus.Progress(spec.modelId, downloaded, totalBytes)
                            )
                        }
                    }
                }
            } finally {
                conn.disconnect()
            }

            if (!checksumOk(tmpFile, spec.expectedSha256)) {
                tmpFile.delete()
                callback.onStatus(
                    DownloadStatus.Failure(
                        spec.modelId,
                        "Checksum mismatch for ${spec.fileName}"
                    )
                )
                return
            }

            // Atomically promote temp file to final destination.
            if (!tmpFile.renameTo(destFile)) {
                tmpFile.copyTo(destFile, overwrite = true)
                tmpFile.delete()
            }
            Log.i(TAG, "Downloaded ${spec.modelId} → ${destFile.absolutePath}")
            callback.onStatus(DownloadStatus.Success(spec.modelId, destFile))
        } catch (e: Exception) {
            tmpFile.delete()
            val msg = "Download failed for ${spec.modelId}: ${e.message}"
            Log.e(TAG, msg, e)
            callback.onStatus(DownloadStatus.Failure(spec.modelId, msg))
        }
    }

    private fun checksumOk(file: File, expectedSha256: String?): Boolean {
        if (expectedSha256 == null) return true
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream ->
            val buf = ByteArray(8_192)
            var n: Int
            while (stream.read(buf).also { n = it } != -1) digest.update(buf, 0, n)
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        return actual.equals(expectedSha256, ignoreCase = true)
    }

    companion object {
        private const val TAG = "ModelDownloader"
    }
}
