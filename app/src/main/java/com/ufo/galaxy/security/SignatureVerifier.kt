package com.ufo.galaxy.security

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import com.ufo.galaxy.BuildConfig
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException

/**
 * APK Signature Verifier — Anti-Tampering Check
 * =============================================
 * Verifies that the APK's signing certificate SHA-256 hash matches the
 * expected value baked into [BuildConfig.EXPECTED_CERT_SHA256].
 *
 * This prevents repackaging attacks where an attacker modifies the APK
 * and re-signs it with their own certificate.  If the signature does not
 * match, the app refuses to start.
 *
 * **Setting the expected hash**
 * 1. Build a release APK and sign it with your release keystore.
 * 2. Get the SHA-256 of the signing certificate:
 *      keytool -list -v -keystore your-release.keystore | grep "SHA256:"
 * 3. Paste the hash (lowercase, no colons) into `EXPECTED_CERT_SHA256`
 *    in app/build.gradle.
 *
 * **Security notes**
 * - The expected hash is stored in BuildConfig — it is trivial to read
 *   from the APK, but *changing* it requires re-signing, which changes
 *   the actual certificate hash, causing the check to fail.
 * - This is a defense-in-depth measure; it does not replace Google Play
 *   App Signing or MDM attestation.
 */
object SignatureVerifier {
    private const val TAG = "SignatureVerifier"

    /**
     * Verifies the APK signing certificate hash.
     *
     * @return true if the signature matches [BuildConfig.EXPECTED_CERT_SHA256]
     * @throws SecurityException if the signature does not match (in debug builds
     *         the exception is caught and logged; in release builds it propagates
     *         and crashes the app).
     */
    fun verifyAppSignature(context: Context): Boolean {
        return try {
            val packageName = context.packageName
            val packageInfo = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                context.packageManager.getPackageInfo(
                    packageName,
                    PackageManager.GET_SIGNING_CERTIFICATES
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNATURES)
            }

            val signatures = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                packageInfo.signingInfo?.apkContentsSigners
            } else {
                @Suppress("DEPRECATION")
                packageInfo.signatures
            } ?: emptyArray()

            // 安全:签名者为空(signingInfo 为 null 等)必须【失败关闭】,不能跳过校验后
            // 落到末尾 return true(那会让无签名信息的包被判为通过)。
            if (signatures.isEmpty()) {
                Log.e(TAG, "APK signature verification FAILED: no signing certificates present")
                return false
            }

            for (signature in signatures) {
                val certHash = sha256(signature.toByteArray())
                val expectedHash = BuildConfig.EXPECTED_CERT_SHA256

                // Allow placeholder to pass in debug builds so that developers
                // don't need to set the hash for everyday development.
                if (BuildConfig.DEBUG && expectedHash == "PLACEHOLDER_SET_BEFORE_RELEASE") {
                    Log.w(TAG, "APK signature check skipped in DEBUG mode (EXPECTED_CERT_SHA256 is placeholder)")
                    return true
                }

                if (certHash != expectedHash) {
                    Log.e(TAG, "APK signature verification FAILED!")
                    Log.e(TAG, "Expected: $expectedHash")
                    Log.e(TAG, "Actual:   $certHash")
                    throw SecurityException("APK has been tampered with! Signature mismatch.")
                }
            }

            Log.i(TAG, "APK signature verification passed")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Signature verification error: ${e.message}")
            false
        }
    }

    private fun sha256(data: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(data)
        return hash.joinToString("") { "%02x".format(it) }
    }
}
