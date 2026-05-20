package com.ufo.galaxy.runtime

/**
 * PR-13(Android) — Final surface convergence contract.
 *
 * Locks the Android final-facing surfaces to canonical runtime authority and canonical
 * cross-repo contracts. Final UI/diagnostics/product-facing layers are consumption and
 * composition surfaces only; they must not re-assemble authority truth/state/dispatch.
 */
object AndroidFinalSurfaceConvergenceContract {

    enum class LayerClass(val wireValue: String) {
        RUNTIME_AUTHORITY_CANONICAL("runtime_authority_canonical"),
        DISTRIBUTED_SUBJECT_CONTRACT_CANONICAL("distributed_subject_contract_canonical"),
        CANONICAL_UPLINK_CONTRACT_ONLY("canonical_uplink_contract_only"),
        LOCAL_FACING_CONSUMPTION_ONLY("local_facing_consumption_only"),
        RUNTIME_VISIBLE_CONSUMPTION_ONLY("runtime_visible_consumption_only"),
        OPERATOR_FACING_CONSUMPTION_ONLY("operator_facing_consumption_only"),
        DIAGNOSTICS_VISIBLE_CONSUMPTION_ONLY("diagnostics_visible_consumption_only"),
        UI_VISIBLE_CONSUMPTION_ONLY("ui_visible_consumption_only"),
        PRODUCT_FACING_COMPOSITION_ONLY("product_facing_composition_only")
    }

    data class FinalSurfaceBoundaryEntry(
        val surfaceId: String,
        val displayName: String,
        val layerClass: LayerClass,
        val canonicalInputs: Set<String>,
        val outputContract: String,
        val v2AlignmentSurface: String?,
        val reassemblesAuthorityTruth: Boolean = false,
        val reassemblesAuthorityState: Boolean = false,
        val reassemblesAuthorityDispatch: Boolean = false
    )

    val requiredCanonicalMainChainModules: Set<String> = setOf(
        "RuntimeController",
        "GalaxyConnectionService",
        "GalaxyWebSocketClient",
        "LocalExecutionModeGate",
        "LocalLoopExecutor",
        "AutonomousExecutionPipeline"
    )

    val entries: List<FinalSurfaceBoundaryEntry> = listOf(
        FinalSurfaceBoundaryEntry(
            surfaceId = "android-runtime-authority-main-chain",
            displayName = "Android Runtime Authority Main Chain",
            layerClass = LayerClass.RUNTIME_AUTHORITY_CANONICAL,
            canonicalInputs = requiredCanonicalMainChainModules,
            outputContract = "Android canonical runtime input-output contract",
            v2AlignmentSurface = "v2 canonical runtime/governance/truth chain"
        ),
        FinalSurfaceBoundaryEntry(
            surfaceId = "android-distributed-subject-contract-anchor",
            displayName = "Android Distributed Subject Contract Anchor",
            layerClass = LayerClass.DISTRIBUTED_SUBJECT_CONTRACT_CANONICAL,
            canonicalInputs = setOf(
                "AndroidBoundedSubjectRuntimeContract",
                "AndroidCrossDeviceDispatchBoundaryContract",
                "AndroidResultUplinkBoundaryContract",
                "AndroidCompletionClosureUplinkContract"
            ),
            outputContract = "distributed subject contract anchoring without authority relocation",
            v2AlignmentSurface = "v2 distributed subject contract / governance closure chain"
        ),
        FinalSurfaceBoundaryEntry(
            surfaceId = "android-canonical-uplink-contract",
            displayName = "Android Canonical Uplink Contract Surface",
            layerClass = LayerClass.CANONICAL_UPLINK_CONTRACT_ONLY,
            canonicalInputs = setOf(
                "AndroidBoundedSubjectRuntimeContract.classifyCanonicalUplink",
                "GalaxyConnectionService.sendGoalResult",
                "GalaxyConnectionService.sendDeviceStateSnapshot",
                "GalaxyWebSocketClient.emitDiagnosticsPayload"
            ),
            outputContract = "canonical truth/result/continuity alignment through uplink contract only",
            v2AlignmentSurface = "v2 canonical truth convergence / result ingress / continuity chain"
        ),
        FinalSurfaceBoundaryEntry(
            surfaceId = "android-local-facing-consumption",
            displayName = "Android Local-facing Consumption Surface",
            layerClass = LayerClass.LOCAL_FACING_CONSUMPTION_ONLY,
            canonicalInputs = setOf(
                "LocalLoopExecutor",
                "RuntimeController",
                "MainViewModel",
                "AndroidBoundedSubjectRuntimeContract.OBSERVABILITY_BOUNDARY_ENTRIES"
            ),
            outputContract = "local-facing runtime consumption without canonical authority override",
            v2AlignmentSurface = "v2 local participant contribution path"
        ),
        FinalSurfaceBoundaryEntry(
            surfaceId = "android-runtime-visible-consumption",
            displayName = "Android Runtime-visible Consumption Surface",
            layerClass = LayerClass.RUNTIME_VISIBLE_CONSUMPTION_ONLY,
            canonicalInputs = setOf(
                "RuntimeController.hostSessionSnapshot",
                "RuntimeController.targetReadinessProjection",
                "RuntimeController.currentDispatchReadiness",
                "HostFacingProjectionContract",
                "RuntimeTruthPrecedenceRules"
            ),
            outputContract = "runtime-visible projection and summary consumption",
            v2AlignmentSurface = "v2 runtime visibility / projection consumption"
        ),
        FinalSurfaceBoundaryEntry(
            surfaceId = "android-diagnostics-visible-consumption",
            displayName = "Android Diagnostics-visible Consumption Surface",
            layerClass = LayerClass.DIAGNOSTICS_VISIBLE_CONSUMPTION_ONLY,
            canonicalInputs = setOf(
                "AndroidOperationalStateSurfaceContract",
                "AndroidRuntimeObservabilityAuditContract",
                "MainViewModel.buildDiagnosticsText",
                "MainUiState.recentErrors",
                "MainUiState.recentTaskIds"
            ),
            outputContract = "diagnostics-visible consumption without authority override",
            v2AlignmentSurface = "v2 operator/board/diagnostics consumption chain"
        ),
        FinalSurfaceBoundaryEntry(
            surfaceId = "android-operator-facing-consumption",
            displayName = "Android Operator-facing Consumption Surface",
            layerClass = LayerClass.OPERATOR_FACING_CONSUMPTION_ONLY,
            canonicalInputs = setOf(
                "AndroidToolActionAuthorizationUplinkContract",
                "AndroidDiagnosticsFailureExplanationUplinkContract",
                "AndroidRuntimeObservabilityAuditContract",
                "MainViewModel.buildDiagnosticsText"
            ),
            outputContract = "operator-facing projection and diagnostics consumption only",
            v2AlignmentSurface = "v2 operator_surface / projection surface consumption chain"
        ),
        FinalSurfaceBoundaryEntry(
            surfaceId = "android-ui-visible-consumption",
            displayName = "Android UI-visible Consumption Surface",
            layerClass = LayerClass.UI_VISIBLE_CONSUMPTION_ONLY,
            canonicalInputs = setOf(
                "MainUiState",
                "MainViewModel",
                "UnifiedResultPresentation",
                "RuntimeController.reconnectRecoveryState",
                "RuntimeController.setupError"
            ),
            outputContract = "ui-visible consumption without authority elevation",
            v2AlignmentSurface = "v2 frontend/operator consumption chain"
        ),
        FinalSurfaceBoundaryEntry(
            surfaceId = "android-ui-visible-product-facing-composition",
            displayName = "Android UI-visible Product-facing Composition Surface",
            layerClass = LayerClass.PRODUCT_FACING_COMPOSITION_ONLY,
            canonicalInputs = setOf(
                "UnifiedResultPresentation.fromLocalResult",
                "UnifiedResultPresentation.fromServerMessage",
                "UnifiedResultPresentation.fromFallbackEvent",
                "MainViewModel.handleServerMessage",
                "MainViewModel.runtimeController observers"
            ),
            outputContract = "product-facing message composition over canonical runtime outputs",
            v2AlignmentSurface = "v2 final integration/outward composition consumption"
        )
    )

    val convergenceInvariants: List<String> = listOf(
        "INVARIANT-1: RuntimeController/GalaxyConnectionService/GalaxyWebSocketClient remain Android runtime authority main-chain modules.",
        "INVARIANT-2: Center authority for final truth/dispatch/closure remains outside Android local/product/operator/diagnostics consumers.",
        "INVARIANT-3: Distributed subject contract and canonical uplink boundaries are anchored explicitly and remain non-authority surfaces.",
        "INVARIANT-4: Local-facing, runtime-visible, diagnostics-visible, operator-facing, and product-facing layers consume bounded outputs only.",
        "INVARIANT-5: No final surface boundary may re-assemble authority truth/state/dispatch semantics outside runtime authority.",
        "INVARIANT-6: Android final surface boundaries retain V2-aligned consumption mapping for runtime, diagnostics, operator, and product integration."
    )

    fun forId(surfaceId: String): FinalSurfaceBoundaryEntry? =
        entries.firstOrNull { it.surfaceId == surfaceId }

    fun byLayerClass(layerClass: LayerClass): List<FinalSurfaceBoundaryEntry> =
        entries.filter { it.layerClass == layerClass }

    fun validateNoAuthorityReassemblyInConsumptionSurfaces(): Boolean =
        entries
            .filter { it.layerClass != LayerClass.RUNTIME_AUTHORITY_CANONICAL }
            .none {
                it.reassemblesAuthorityTruth ||
                    it.reassemblesAuthorityState ||
                    it.reassemblesAuthorityDispatch
            }

    const val INTRODUCED_PR: Int = 13
    const val DESCRIPTION: String =
        "Final Android surface convergence contract: runtime authority remains canonical; " +
            "local/operator/UI/diagnostics/runtime-visible/product-facing layers are consumption-only; " +
            "distributed subject contract and canonical uplink stay aligned to V2 canonical center."
}
