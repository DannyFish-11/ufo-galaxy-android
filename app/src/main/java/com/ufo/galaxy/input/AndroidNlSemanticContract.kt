package com.ufo.galaxy.input

/**
 * Canonical Android-side metadata contract for natural-language input routing.
 *
 * This contract keeps Android local NL structure/planning roles explicit while
 * avoiding ambiguity about system-level semantic authority:
 * - Local path: Android executes locally; authority is device-local only.
 * - Handoff path: Android acts as source/carrier and hands semantic authority to V2.
 */
internal object AndroidNlSemanticContract {
    const val KEY_NL_SOURCE = "nl_source"
    const val KEY_NL_CARRIER = "nl_carrier"
    const val KEY_NL_PATH = "nl_path"
    const val KEY_SEMANTIC_AUTHORITY = "semantic_authority"
    const val KEY_LOCAL_NL_LAYER_ROLE = "local_nl_layer_role"

    const val SOURCE_ANDROID_LOCAL_INPUT = "android_local_input"
    const val CARRIER_ANDROID = "android_device"

    const val PATH_ANDROID_LOCAL_NL = "android_local_nl_path"
    const val PATH_ANDROID_HANDOFF_TO_V2 = "android_handoff_to_v2_path"

    const val SEMANTIC_AUTHORITY_ANDROID_LOCAL = "android_local_execution"
    const val SEMANTIC_AUTHORITY_V2 = "v2"

    // GoalNormalizer + LocalPlannerService scope: local structuring/decomposition only.
    const val LOCAL_NL_LAYER_ROLE = "normalization_and_task_decomposition_only"

    fun localRouteMetadata(posture: String): Map<String, String> = mapOf(
        KEY_NL_SOURCE to SOURCE_ANDROID_LOCAL_INPUT,
        KEY_NL_CARRIER to CARRIER_ANDROID,
        KEY_NL_PATH to PATH_ANDROID_LOCAL_NL,
        KEY_SEMANTIC_AUTHORITY to SEMANTIC_AUTHORITY_ANDROID_LOCAL,
        KEY_LOCAL_NL_LAYER_ROLE to LOCAL_NL_LAYER_ROLE,
        "source_runtime_posture" to posture
    )

    fun handoffToV2Metadata(posture: String): Map<String, String> = mapOf(
        KEY_NL_SOURCE to SOURCE_ANDROID_LOCAL_INPUT,
        KEY_NL_CARRIER to CARRIER_ANDROID,
        KEY_NL_PATH to PATH_ANDROID_HANDOFF_TO_V2,
        KEY_SEMANTIC_AUTHORITY to SEMANTIC_AUTHORITY_V2,
        KEY_LOCAL_NL_LAYER_ROLE to LOCAL_NL_LAYER_ROLE,
        "source_runtime_posture" to posture
    )
}
