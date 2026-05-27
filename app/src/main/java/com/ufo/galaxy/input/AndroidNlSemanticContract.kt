package com.ufo.galaxy.input

import com.ufo.galaxy.runtime.AndroidNlInitiationContract
import com.ufo.galaxy.runtime.SourceRuntimePosture
import java.util.Locale

/**
 * Canonical Android-side metadata contract for natural-language input routing.
 *
 * This contract keeps Android local NL structure/planning roles explicit while
 * avoiding ambiguity about system-level semantic authority:
 * - Local path: Android executes locally; authority is device-local only.
 * - Handoff path: Android acts as source/carrier and hands semantic authority to V2.
 *
 * PR-993: [handoffToV2Metadata] and [nlInitiationMetadata] now include the full
 * Android NL initiation metadata as defined by [AndroidNlInitiationContract], so that
 * V2's intake layer can identify and admit Android-originated NL initiations into the
 * main intent/governance/execution/truth/reconciliation/closure chain.
 */
internal object AndroidNlSemanticContract {
    const val KEY_NL_SOURCE = "nl_source"
    const val KEY_NL_CARRIER = "nl_carrier"
    const val KEY_NL_PATH = "nl_path"
    const val KEY_SEMANTIC_AUTHORITY = "semantic_authority"
    const val KEY_LOCAL_NL_LAYER_ROLE = "local_nl_layer_role"
    const val KEY_CANONICAL_SUBJECT_INPUT = "android_canonical_subject_input"
    const val KEY_REQUEST_PACKAGING_STRATEGY = "android_request_packaging_strategy"
    const val KEY_CANONICAL_INPUT_MODALITIES = "android_canonical_input_modalities"

    const val SOURCE_ANDROID_LOCAL_INPUT = "android_local_input"
    const val CARRIER_ANDROID = "android_device"

    const val PATH_ANDROID_LOCAL_NL = "android_local_nl_path"
    const val PATH_ANDROID_HANDOFF_TO_V2 = "android_handoff_to_v2_path"

    const val SEMANTIC_AUTHORITY_ANDROID_LOCAL = "android_local_execution"
    const val SEMANTIC_AUTHORITY_V2 = "v2"

    // GoalNormalizer + LocalPlannerService scope: local structuring/decomposition only.
    const val LOCAL_NL_LAYER_ROLE = "normalization_and_task_decomposition_only"

    enum class RequestPackagingStrategy(val wireValue: String) {
        CONTROLLED_HANDOFF_BASELINE("controlled_handoff_baseline"),
        STATEFUL_PARTICIPANT_FUSION("stateful_participant_fusion")
    }

    data class CanonicalNaturalLanguageInput(
        val text: String,
        val textLength: Int
    )

    data class CanonicalDeviceContextInput(
        val deviceId: String,
        val localeTag: String,
        val sourceRuntimePosture: String,
        val runtimeSessionId: String?,
        val crossDeviceEnabled: Boolean
    )

    data class CanonicalStateSnapshotInput(
        val websocketConnected: Boolean,
        val runtimeSessionAttached: Boolean
    )

    data class CanonicalMixedContextInput(
        val modalities: List<String>,
        val participantRole: String
    )

    data class CanonicalSubjectInput(
        val schemaVersion: String,
        val naturalLanguage: CanonicalNaturalLanguageInput,
        val deviceContext: CanonicalDeviceContextInput,
        val stateSnapshot: CanonicalStateSnapshotInput,
        val mixedContext: CanonicalMixedContextInput
    )

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

    /**
     * Produces the merged context-extra metadata map for an Android NL cross-device
     * initiation — combining the standard handoff metadata with the PR-993 NL
     * initiation contract wire fields.
     *
     * When [initiationMetadata] is non-null (i.e. `cross_device_enabled = true` and
     * [AndroidNlInitiationContract.build] succeeded), its [AndroidNlInitiationContract.NlInitiationMetadata.toWireMap]
     * entries are merged in, enriching the [com.ufo.galaxy.protocol.TaskSubmitContext.extra]
     * map that V2's intake layer reads.
     *
     * When [initiationMetadata] is `null` (local path or gate not met), only the base
     * handoff metadata is returned — preserving backwards compatibility.
     *
     * @param posture            Source-device participation posture wire value.
     * @param initiationMetadata PR-993 NL initiation metadata, or `null` when the gate was
     *                           not met (cross_device_enabled=false).
     */
    fun nlInitiationMetadata(
        posture: String,
        initiationMetadata: AndroidNlInitiationContract.NlInitiationMetadata?
    ): Map<String, String> {
        val base = handoffToV2Metadata(posture)
        return if (initiationMetadata != null) base + initiationMetadata.toWireMap() else base
    }

    fun buildCanonicalSubjectInput(
        text: String,
        deviceId: String,
        posture: String,
        crossDeviceEnabled: Boolean,
        websocketConnected: Boolean,
        runtimeSessionId: String?
    ): CanonicalSubjectInput {
        val locale = Locale.getDefault().toLanguageTag().ifBlank { "und" }
        val modalities = listOf("android_natural_language", "android_device_context", "android_state_snapshot")
        return CanonicalSubjectInput(
            schemaVersion = "1",
            naturalLanguage = CanonicalNaturalLanguageInput(
                text = text,
                textLength = text.length
            ),
            deviceContext = CanonicalDeviceContextInput(
                deviceId = deviceId,
                localeTag = locale,
                sourceRuntimePosture = posture,
                runtimeSessionId = runtimeSessionId,
                crossDeviceEnabled = crossDeviceEnabled
            ),
            stateSnapshot = CanonicalStateSnapshotInput(
                websocketConnected = websocketConnected,
                runtimeSessionAttached = !runtimeSessionId.isNullOrBlank()
            ),
            mixedContext = CanonicalMixedContextInput(
                modalities = modalities,
                participantRole = if (posture == SourceRuntimePosture.JOIN_RUNTIME) {
                    "distributed_runtime_participant"
                } else {
                    "controller_handoff_participant"
                }
            )
        )
    }

    fun deriveRequestPackagingStrategy(posture: String): RequestPackagingStrategy =
        if (posture == SourceRuntimePosture.JOIN_RUNTIME) {
            RequestPackagingStrategy.STATEFUL_PARTICIPANT_FUSION
        } else {
            RequestPackagingStrategy.CONTROLLED_HANDOFF_BASELINE
        }

    fun deriveConversationSessionId(taskId: String, strategy: RequestPackagingStrategy): String =
        when (strategy) {
            RequestPackagingStrategy.STATEFUL_PARTICIPANT_FUSION -> "android-participant-$taskId"
            RequestPackagingStrategy.CONTROLLED_HANDOFF_BASELINE -> "android-control-$taskId"
        }

    fun idempotencyKeyPrefix(strategy: RequestPackagingStrategy): String =
        when (strategy) {
            RequestPackagingStrategy.STATEFUL_PARTICIPANT_FUSION -> "participant-fusion"
            RequestPackagingStrategy.CONTROLLED_HANDOFF_BASELINE -> "controlled-handoff"
        }

    fun canonicalSubjectInputMetadata(
        subjectInputJson: String,
        strategy: RequestPackagingStrategy,
        modalities: List<String>
    ): Map<String, String> = mapOf(
        KEY_CANONICAL_SUBJECT_INPUT to subjectInputJson,
        KEY_REQUEST_PACKAGING_STRATEGY to strategy.wireValue,
        KEY_CANONICAL_INPUT_MODALITIES to modalities.joinToString(separator = ",")
    )
}
