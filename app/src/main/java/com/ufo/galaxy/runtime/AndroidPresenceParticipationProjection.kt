package com.ufo.galaxy.runtime

enum class AndroidPresenceProjectionLayer(val wireValue: String) {
    STATIC("static"),
    LIMINAL("liminal"),
    MANIFEST("manifest")
}

enum class AndroidPresenceParticipationKind(val wireValue: String) {
    ABSENT("absent"),
    BACKGROUND_PARTICIPANT("background_participant"),
    FOREGROUND_ENGAGED("foreground_engaged"),
    HANDOFF_INTERACTIVE("handoff_interactive")
}

enum class AndroidExecutionPresenceRelation(val wireValue: String) {
    OBSERVATION_ONLY("observation_only"),
    PRESENCE_ONLY("presence_only"),
    EXECUTION_ONLY("execution_only"),
    EXECUTION_AND_PRESENCE("execution_and_presence")
}

enum class AndroidPresenceActionVisibility(val wireValue: String) {
    NOT_VISIBLE("not_visible"),
    EXECUTION_HIDDEN("execution_hidden"),
    BACKGROUND_DEFERRED("background_deferred"),
    FOREGROUND_VISIBLE("foreground_visible"),
    HANDOFF_READY("handoff_ready")
}

data class AndroidPresenceParticipationProjection(
    val layer: AndroidPresenceProjectionLayer,
    val participationKind: AndroidPresenceParticipationKind,
    val executionPresenceRelation: AndroidExecutionPresenceRelation,
    val actionVisibility: AndroidPresenceActionVisibility,
    val manifestationParticipationState: RuntimeNodeManifestationParticipationState,
    val executionParticipationState: RuntimeNodeExecutionParticipationState,
    val collaborationParticipationState: RuntimeNodeCollaborationParticipationState,
    val interactionSurfaceReady: Boolean
) {

    val isPresenceParticipant: Boolean
        get() = participationKind != AndroidPresenceParticipationKind.ABSENT

    fun toMap(): Map<String, Any> = mapOf(
        KEY_LAYER to layer.wireValue,
        KEY_PARTICIPATION_KIND to participationKind.wireValue,
        KEY_EXECUTION_PRESENCE_RELATION to executionPresenceRelation.wireValue,
        KEY_ACTION_VISIBILITY to actionVisibility.wireValue,
        KEY_MANIFESTATION_PARTICIPATION_STATE to manifestationParticipationState.wireValue,
        KEY_EXECUTION_PARTICIPATION_STATE to executionParticipationState.wireValue,
        KEY_COLLABORATION_PARTICIPATION_STATE to collaborationParticipationState.wireValue,
        KEY_INTERACTION_SURFACE_READY to interactionSurfaceReady,
        KEY_IS_PRESENCE_PARTICIPANT to isPresenceParticipant
    )

    companion object {
        const val KEY_LAYER = "presence_layer"
        const val KEY_PARTICIPATION_KIND = "presence_participation_kind"
        const val KEY_EXECUTION_PRESENCE_RELATION = "execution_presence_relation"
        const val KEY_ACTION_VISIBILITY = "presence_action_visibility"
        const val KEY_MANIFESTATION_PARTICIPATION_STATE = "manifestation_participation_state"
        const val KEY_EXECUTION_PARTICIPATION_STATE = "execution_participation_state"
        const val KEY_COLLABORATION_PARTICIPATION_STATE = "collaboration_participation_state"
        const val KEY_INTERACTION_SURFACE_READY = "interaction_surface_ready"
        const val KEY_IS_PRESENCE_PARTICIPANT = "is_presence_participant"

        fun from(
            manifestationParticipationState: RuntimeNodeManifestationParticipationState,
            executionParticipationState: RuntimeNodeExecutionParticipationState,
            collaborationParticipationState: RuntimeNodeCollaborationParticipationState,
            interactionSurfaceReady: Boolean
        ): AndroidPresenceParticipationProjection {
            val layer = when (manifestationParticipationState) {
                RuntimeNodeManifestationParticipationState.CONTROL_ONLY ->
                    AndroidPresenceProjectionLayer.STATIC
                RuntimeNodeManifestationParticipationState.BACKGROUND_CARRIER ->
                    AndroidPresenceProjectionLayer.LIMINAL
                RuntimeNodeManifestationParticipationState.INTERACTIVE_FOREGROUND ->
                    AndroidPresenceProjectionLayer.MANIFEST
            }
            val participationKind = when (layer) {
                AndroidPresenceProjectionLayer.STATIC ->
                    AndroidPresenceParticipationKind.ABSENT
                AndroidPresenceProjectionLayer.LIMINAL ->
                    AndroidPresenceParticipationKind.BACKGROUND_PARTICIPANT
                AndroidPresenceProjectionLayer.MANIFEST ->
                    if (interactionSurfaceReady) {
                        AndroidPresenceParticipationKind.HANDOFF_INTERACTIVE
                    } else {
                        AndroidPresenceParticipationKind.FOREGROUND_ENGAGED
                    }
            }
            val isExecutionParticipant =
                executionParticipationState != RuntimeNodeExecutionParticipationState.BLOCKED
            val isPresenceParticipant =
                participationKind != AndroidPresenceParticipationKind.ABSENT
            val executionPresenceRelation = when {
                isExecutionParticipant && isPresenceParticipant ->
                    AndroidExecutionPresenceRelation.EXECUTION_AND_PRESENCE
                isExecutionParticipant ->
                    AndroidExecutionPresenceRelation.EXECUTION_ONLY
                isPresenceParticipant ->
                    AndroidExecutionPresenceRelation.PRESENCE_ONLY
                else ->
                    AndroidExecutionPresenceRelation.OBSERVATION_ONLY
            }
            val actionVisibility = when (participationKind) {
                AndroidPresenceParticipationKind.ABSENT ->
                    if (isExecutionParticipant) {
                        AndroidPresenceActionVisibility.EXECUTION_HIDDEN
                    } else {
                        AndroidPresenceActionVisibility.NOT_VISIBLE
                    }
                AndroidPresenceParticipationKind.BACKGROUND_PARTICIPANT ->
                    AndroidPresenceActionVisibility.BACKGROUND_DEFERRED
                AndroidPresenceParticipationKind.FOREGROUND_ENGAGED ->
                    AndroidPresenceActionVisibility.FOREGROUND_VISIBLE
                AndroidPresenceParticipationKind.HANDOFF_INTERACTIVE ->
                    AndroidPresenceActionVisibility.HANDOFF_READY
            }
            return AndroidPresenceParticipationProjection(
                layer = layer,
                participationKind = participationKind,
                executionPresenceRelation = executionPresenceRelation,
                actionVisibility = actionVisibility,
                manifestationParticipationState = manifestationParticipationState,
                executionParticipationState = executionParticipationState,
                collaborationParticipationState = collaborationParticipationState,
                interactionSurfaceReady = interactionSurfaceReady
            )
        }
    }
}
