package com.ufo.galaxy.runtime

object AndroidCanonicalOwnershipIngressContract {

    const val KEY_OWNERSHIP_STATUS = "canonical_ownership_status"
    const val KEY_TRUTH_INGRESS_CLASS = "truth_ingress_class"
    const val KEY_CANONICAL_SESSION_IDENTITY = "canonical_session_identity"
    const val KEY_CONTROL_SESSION_ID = "control_session_id"
    const val KEY_RUNTIME_ATTACHMENT_SESSION_ID = "runtime_attachment_session_id"
    const val KEY_CANONICALIZATION_READY = "is_canonicalization_ready"
    const val KEY_DIVERGENCE_MARKER = "ownership_divergence_marker"
    const val KEY_SCHEMA_VERSION = "canonical_ownership_schema_version"
    const val KEY_PARTICIPANT_LOCAL_RUNTIME_TRUTH = "participant_local_runtime_truth"
    const val SCHEMA_VERSION = "1.0"

    enum class OwnershipStatus(val wireValue: String) {
        CANONICAL_BOUND("canonical_bound"),
        PARTICIPANT_LOCAL_ONLY("participant_local_only"),
        DIVERGED_FALLBACK("diverged_fallback")
    }

    enum class TruthIngressClass(val wireValue: String) {
        CANONICALIZATION_CANDIDATE("canonicalization_candidate"),
        PARTICIPANT_LOCAL_TRUTH("participant_local_truth")
    }

    data class Input(
        val signalKind: ReconciliationSignal.Kind,
        val participantId: String,
        val controlSessionId: String?,
        val durableSessionId: String?,
        val sessionContinuityEpoch: Int?,
        val runtimeAttachmentSessionId: String?,
        val deliveryDisposition: AndroidRuntimeEmissionTruthSemantics.DeliveryDisposition
    )

    data class Resolution(
        val ownershipStatus: OwnershipStatus,
        val truthIngressClass: TruthIngressClass,
        val canonicalSessionIdentity: String?,
        val controlSessionId: String?,
        val runtimeAttachmentSessionId: String?,
        val divergenceMarker: String?
    ) {
        val isCanonicalizationReady: Boolean
            get() = ownershipStatus == OwnershipStatus.CANONICAL_BOUND &&
                truthIngressClass == TruthIngressClass.CANONICALIZATION_CANDIDATE

        fun toWireMap(): Map<String, Any?> = mapOf(
            KEY_OWNERSHIP_STATUS to ownershipStatus.wireValue,
            KEY_TRUTH_INGRESS_CLASS to truthIngressClass.wireValue,
            KEY_CANONICAL_SESSION_IDENTITY to canonicalSessionIdentity,
            KEY_CONTROL_SESSION_ID to controlSessionId,
            KEY_RUNTIME_ATTACHMENT_SESSION_ID to runtimeAttachmentSessionId,
            KEY_CANONICALIZATION_READY to isCanonicalizationReady,
            KEY_DIVERGENCE_MARKER to divergenceMarker,
            KEY_SCHEMA_VERSION to SCHEMA_VERSION
        )
    }

    fun derive(input: Input): Resolution {
        val hasCanonicalIdentity =
            input.participantId.isNotBlank() &&
                !input.controlSessionId.isNullOrBlank() &&
                !input.durableSessionId.isNullOrBlank() &&
                input.sessionContinuityEpoch != null

        val requiresRuntimeAttachment = input.signalKind == ReconciliationSignal.Kind.RUNTIME_TRUTH_SNAPSHOT
        val hasRuntimeAttachment = !input.runtimeAttachmentSessionId.isNullOrBlank()
        val truthIngressClass =
            if (hasCanonicalIdentity && (!requiresRuntimeAttachment || hasRuntimeAttachment)) {
                TruthIngressClass.CANONICALIZATION_CANDIDATE
            } else {
                TruthIngressClass.PARTICIPANT_LOCAL_TRUTH
            }

        val ownershipStatus = when {
            !hasCanonicalIdentity -> OwnershipStatus.PARTICIPANT_LOCAL_ONLY
            input.deliveryDisposition != AndroidRuntimeEmissionTruthSemantics.DeliveryDisposition.DIRECT_SENT &&
                input.deliveryDisposition != AndroidRuntimeEmissionTruthSemantics.DeliveryDisposition.REPLAYED_FORWARDED ->
                OwnershipStatus.DIVERGED_FALLBACK
            else -> OwnershipStatus.CANONICAL_BOUND
        }

        val canonicalSessionIdentity = if (hasCanonicalIdentity) {
            "${input.participantId}:${input.durableSessionId}:${input.sessionContinuityEpoch}"
        } else {
            null
        }

        val divergenceMarker = when (input.deliveryDisposition) {
            AndroidRuntimeEmissionTruthSemantics.DeliveryDisposition.OFFLINE_QUEUED ->
                "offline_queue_pending_canonicalization"
            AndroidRuntimeEmissionTruthSemantics.DeliveryDisposition.SEND_FAILED ->
                "transport_send_failed"
            AndroidRuntimeEmissionTruthSemantics.DeliveryDisposition.LOCAL_SIGNAL_EMITTED ->
                "local_signal_not_externally_delivered"
            AndroidRuntimeEmissionTruthSemantics.DeliveryDisposition.DUPLICATE_SUPPRESSED ->
                "duplicate_suppressed"
            else -> null
        }

        return Resolution(
            ownershipStatus = ownershipStatus,
            truthIngressClass = truthIngressClass,
            canonicalSessionIdentity = canonicalSessionIdentity,
            controlSessionId = input.controlSessionId,
            runtimeAttachmentSessionId = input.runtimeAttachmentSessionId,
            divergenceMarker = divergenceMarker
        )
    }
}
