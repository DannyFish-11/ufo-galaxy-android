package com.ufo.galaxy.runtime

/**
 * PR-120 — outward truth-surface semantics for Android-visible projections.
 *
 * Makes the canonical-vs-local distinction explicit on Android-side outward models so
 * runtime-visible and advisory projections cannot silently masquerade as V2-confirmed truth.
 */
object AndroidOutwardTruthSurfaceSemantics {

    const val KEY_OUTWARD_TRUTH_SURFACE_CLASS =
        AndroidCompletionClosureUplinkContract.KEY_OUTWARD_TRUTH_SURFACE_CLASS
    const val KEY_TRUTH_TIER = "outward_truth_tier"
    const val KEY_SOURCE_AUTHORITY_CLASS = "source_authority_class"
    const val KEY_IS_V2_CONFIRMED_CANONICAL_TRUTH = "is_v2_confirmed_canonical_truth"

    const val SOURCE_AUTHORITY_ANDROID_LOCAL_RUNTIME = "android_local_runtime_authority"
    const val SOURCE_AUTHORITY_V2_CANONICAL = "v2_canonical_truth_authority"

    data class SurfaceSemantics(
        val outwardTruthSurfaceClass: AndroidCompletionClosureUplinkContract.OutwardTruthSurfaceClass,
        val truthTier: RuntimeTruthPrecedenceRules.TruthTier,
        val sourceAuthorityClass: String,
        val isV2ConfirmedCanonicalTruth: Boolean
    ) {
        init {
            require(
                !isV2ConfirmedCanonicalTruth ||
                    outwardTruthSurfaceClass ==
                    AndroidCompletionClosureUplinkContract.OutwardTruthSurfaceClass
                        .V2_CONFIRMED_CANONICAL_TRUTH
            ) {
                "Only v2_confirmed_canonical_truth surfaces may claim V2-confirmed canonical truth"
            }
        }

        fun toMap(): Map<String, Any> = mapOf(
            KEY_OUTWARD_TRUTH_SURFACE_CLASS to outwardTruthSurfaceClass.wireValue,
            KEY_TRUTH_TIER to truthTier.wireValue,
            KEY_SOURCE_AUTHORITY_CLASS to sourceAuthorityClass,
            KEY_IS_V2_CONFIRMED_CANONICAL_TRUTH to isV2ConfirmedCanonicalTruth
        )
    }

    fun runtimeVisibleSnapshot(
        truthTier: RuntimeTruthPrecedenceRules.TruthTier
    ): SurfaceSemantics = SurfaceSemantics(
        outwardTruthSurfaceClass = AndroidCompletionClosureUplinkContract.OutwardTruthSurfaceClass
            .ANDROID_RUNTIME_VISIBLE_STATE,
        truthTier = truthTier,
        sourceAuthorityClass = SOURCE_AUTHORITY_ANDROID_LOCAL_RUNTIME,
        isV2ConfirmedCanonicalTruth = false
    )

    fun advisoryProjection(
        truthTier: RuntimeTruthPrecedenceRules.TruthTier
    ): SurfaceSemantics = SurfaceSemantics(
        outwardTruthSurfaceClass = AndroidCompletionClosureUplinkContract.OutwardTruthSurfaceClass
            .ANDROID_ADVISORY_EVIDENCE,
        truthTier = truthTier,
        sourceAuthorityClass = SOURCE_AUTHORITY_ANDROID_LOCAL_RUNTIME,
        isV2ConfirmedCanonicalTruth = false
    )
}
