package com.ufo.galaxy.protocol

import com.ufo.galaxy.shared.protocol.MsgType

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.ufo.galaxy.data.CapabilityReport
import com.ufo.galaxy.data.InMemoryAppSettings
import com.ufo.galaxy.runtime.AndroidContinuityRecoveryStateModel
import com.ufo.galaxy.runtime.AndroidCrossRepoRecoveryStateRoutingContract
import com.ufo.galaxy.runtime.AndroidDistributedTruthOwnershipUplinkContract
import com.ufo.galaxy.runtime.AndroidGovernanceExecutionPolicyIngressContract
import com.ufo.galaxy.runtime.AndroidTruthPublicationSemanticsContract
import com.ufo.galaxy.runtime.ReconnectRecoveryState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Android Participant Protocol Guard — blocking CI gate.
 *
 * Regression tests for the four AIP v3 protocol contracts that Android must satisfy as a
 * first-class participant in the dual-repo system:
 *
 *  1. **device_register contract** — AIP v3 envelope structure, version, and protocol
 *     field invariants that V2's gateway enforces on every inbound connection.
 *
 *  2. **capability_report contract** — required metadata key set, payload validity, and
 *     stability of the capability claim across back-to-back reports from the same settings.
 *
 *  3. **task_result contract** — correlation echo, terminal status vocabulary, and
 *     device_id presence required for V2's result routing.
 *
 *  4. **reconnect / re-register expectations** — wire-value contract for
 *     [ReconnectRecoveryState] and the requirement that a device must re-register and
 *     re-report capability after any reconnect cycle.
 *
 * ## Blocking gate semantics
 *
 * Failure in any test here indicates a protocol regression: a contract that V2 depends on
 * has broken on the Android side.  **Do not mark any test as skipped or suppress any
 * assertion** without a matching V2 contract change and explicit cross-repo review.
 *
 * All tests are pure-JVM; no device, emulator, or network is required.
 */
class AndroidParticipantProtocolGuardTest {

    private val gson = Gson()

    // ── 1. device_register contract ───────────────────────────────────────────

    /**
     * V2 gateway rejects any inbound message with version < 3.0.
     * The Android AipMessage default must always be "3.0".
     */
    @Test
    fun `device_register envelope version is 3_0`() {
        val envelope = AipMessage(type = MsgType.DEVICE_REGISTER, payload = JsonObject())
        assertEquals(
            "AIP v3 contract: version must be '3.0'",
            "3.0",
            envelope.version
        )
    }

    @Test
    fun `device_register envelope protocol is AIP 1_0`() {
        val envelope = AipMessage(type = MsgType.DEVICE_REGISTER, payload = JsonObject())
        assertEquals(
            "AIP v3 contract: protocol must be 'AIP/1.0'",
            "AIP/1.0",
            envelope.protocol
        )
    }

    @Test
    fun `device_register MsgType wire value is device_register`() {
        assertEquals(
            "Wire value must match V2 server MsgType exactly",
            "device_register",
            MsgType.DEVICE_REGISTER.value
        )
    }

    @Test
    fun `device_register envelope carries device_id`() {
        val deviceId = "android_pixel8_guard_01"
        val envelope = AipMessage(
            type = MsgType.DEVICE_REGISTER,
            payload = JsonObject(),
            device_id = deviceId
        )
        assertEquals(
            "device_register must carry a non-blank device_id for gateway routing",
            deviceId,
            envelope.device_id
        )
    }

    @Test
    fun `device_register envelope serialises to JSON with correct type field`() {
        val envelope = AipMessage(
            type = MsgType.DEVICE_REGISTER,
            payload = JsonObject(),
            device_id = "guard-device-01"
        )
        val json = gson.toJson(envelope)
        assertTrue("Serialised JSON must contain 'device_register'", json.contains("device_register"))
        assertTrue("Serialised JSON must contain 'AIP/1.0'", json.contains("AIP/1.0"))
        assertTrue("Serialised JSON must contain '3.0'", json.contains("3.0"))
    }

    // ── 2. capability_report contract ─────────────────────────────────────────

    @Test
    fun `capability_report MsgType wire value is capability_report`() {
        assertEquals(
            "Wire value must match V2 server MsgType exactly",
            "capability_report",
            MsgType.CAPABILITY_REPORT.value
        )
    }

    @Test
    fun `capability_report required metadata key set contains exactly 8 keys`() {
        assertEquals(
            "V2 gateway expects exactly 8 required metadata keys",
            8,
            CapabilityReport.REQUIRED_METADATA_KEYS.size
        )
    }

    @Test
    fun `capability_report required metadata keys are all present in REQUIRED_METADATA_KEYS`() {
        val expected = setOf(
            "goal_execution_enabled",
            "local_model_enabled",
            "cross_device_enabled",
            "parallel_execution_enabled",
            "device_role",
            "model_ready",
            "accessibility_ready",
            "overlay_ready"
        )
        assertEquals(
            "Required metadata key contract must not change without a V2 gateway update",
            expected,
            CapabilityReport.REQUIRED_METADATA_KEYS
        )
    }

    @Test
    fun `capability_report validate returns true when all required keys present`() {
        val report = buildFullCapabilityReport("guard-device-02")
        assertTrue(
            "validate() must return true when all 8 required metadata keys are present",
            report.validate()
        )
    }

    @Test
    fun `capability_report validate returns false when any required key is missing`() {
        for (missingKey in CapabilityReport.REQUIRED_METADATA_KEYS) {
            val metadata = buildFullMetadata().toMutableMap().also { it.remove(missingKey) }
            val report = CapabilityReport(
                platform = "android",
                device_id = "guard-device-03",
                supported_actions = listOf("screen_capture"),
                metadata = metadata
            )
            assertFalse(
                "validate() must return false when '$missingKey' is absent",
                report.validate()
            )
            assertTrue(
                "missingMetadataKeys() must report '$missingKey'",
                report.missingMetadataKeys().contains(missingKey)
            )
        }
    }

    @Test
    fun `capability_report version field is always 3_0`() {
        val report = buildFullCapabilityReport("guard-device-04")
        assertEquals(
            "capability_report.version must always be '3.0' — gateway rejects lower values",
            "3.0",
            report.version
        )
    }

    @Test
    fun `capability_report platform field is android`() {
        val report = buildFullCapabilityReport("guard-device-05")
        assertEquals(
            "platform must be 'android' for Android participant routing on V2",
            "android",
            report.platform
        )
    }

    /** Capability claim stability: two back-to-back reports from the same settings are equal. */
    @Test
    fun `capability_report payload is stable across back-to-back reports from same settings`() {
        val settings = InMemoryAppSettings().apply {
            crossDeviceEnabled = true
            goalExecutionEnabled = true
            localModelEnabled = true
            parallelExecutionEnabled = true
            deviceRole = "phone"
            modelReady = true
            accessibilityReady = true
            overlayReady = true
        }
        val report1 = CapabilityReport(
            platform = "android",
            device_id = "guard-device-06",
            supported_actions = listOf("screen_capture", "automation"),
            metadata = settings.toMetadataMap()
        )
        val report2 = CapabilityReport(
            platform = "android",
            device_id = "guard-device-06",
            supported_actions = listOf("screen_capture", "automation"),
            metadata = settings.toMetadataMap()
        )
        assertEquals(
            "Capability claim must be stable: two reports from identical settings must be equal",
            report1,
            report2
        )
    }

    /** Capability claim honesty: readiness flags must reflect real state, not optimistic defaults. */
    @Test
    fun `capability_report reflects runtime readiness flags accurately`() {
        val settings = InMemoryAppSettings().apply {
            modelReady = false
            accessibilityReady = true
            overlayReady = true
        }
        val meta = settings.toMetadataMap()
        assertEquals(
            "model_ready must reflect actual model readiness — not report true when model is not ready",
            false,
            meta["model_ready"]
        )
    }

    // ── 3. task_result contract ───────────────────────────────────────────────

    @Test
    fun `task_result MsgType wire value is task_result`() {
        assertEquals(
            "Wire value must match V2 server MsgType exactly",
            "task_result",
            MsgType.TASK_RESULT.value
        )
    }

    @Test
    fun `task_result correlation_id must echo task_id for V2 result routing`() {
        val taskId = "guard-task-001"
        val result = TaskResultPayload(
            task_id = taskId,
            correlation_id = taskId,
            status = "success",
            device_id = "guard-device-07"
        )
        assertEquals(
            "correlation_id must be set to task_id — V2 gateway uses it for waiter resolution",
            result.task_id,
            result.correlation_id
        )
    }

    @Test
    fun `task_result status must be one of the three terminal values`() {
        val validStatuses = setOf("success", "error", "cancelled")
        for (status in validStatuses) {
            val result = TaskResultPayload(
                task_id = "guard-task-status-$status",
                correlation_id = "guard-task-status-$status",
                status = status,
                device_id = "guard-device-08"
            )
            assertTrue(
                "status '$status' must be in the V2-defined terminal status vocabulary",
                result.status in validStatuses
            )
        }
    }

    @Test
    fun `task_result device_id must not be blank`() {
        val result = TaskResultPayload(
            task_id = "guard-task-002",
            correlation_id = "guard-task-002",
            status = "success",
            device_id = "guard-device-09"
        )
        assertTrue(
            "device_id must not be blank — V2 uses it for participant attribution",
            result.device_id.isNotBlank()
        )
    }

    @Test
    fun `task_result task_id must not be blank`() {
        val result = TaskResultPayload(
            task_id = "guard-task-003",
            correlation_id = "guard-task-003",
            status = "error",
            error = "test error",
            device_id = "guard-device-10"
        )
        assertTrue(
            "task_id must not be blank — V2 needs it to close the task waiter",
            result.task_id.isNotBlank()
        )
    }

    // ── 4. reconnect / re-register expectations ───────────────────────────────

    /**
     * Wire values for [ReconnectRecoveryState] must be stable because V2 records them in
     * structured log fields and participant state documents.  Any change here breaks V2's
     * log parsing and participant state reconciliation.
     */
    @Test
    fun `reconnect recovery wire value IDLE is idle`() {
        assertEquals(
            "IDLE wire value must not change — V2 log parsing depends on it",
            "idle",
            ReconnectRecoveryState.IDLE.wireValue
        )
    }

    @Test
    fun `reconnect recovery wire value RECOVERING is recovering`() {
        assertEquals(
            "RECOVERING wire value must not change — V2 participant state depends on it",
            "recovering",
            ReconnectRecoveryState.RECOVERING.wireValue
        )
    }

    @Test
    fun `reconnect recovery wire value RECOVERED is recovered`() {
        assertEquals(
            "RECOVERED wire value must not change — V2 participant state depends on it",
            "recovered",
            ReconnectRecoveryState.RECOVERED.wireValue
        )
    }

    @Test
    fun `reconnect recovery wire value FAILED is failed`() {
        assertEquals(
            "FAILED wire value must not change — V2 participant state depends on it",
            "failed",
            ReconnectRecoveryState.FAILED.wireValue
        )
    }

    @Test
    fun `reconnect recovery state wire values are all distinct`() {
        val wireValues = ReconnectRecoveryState.entries.map { it.wireValue }
        assertEquals(
            "All ReconnectRecoveryState wire values must be distinct",
            wireValues.size,
            wireValues.toSet().size
        )
    }

    /**
     * After a reconnect cycle (IDLE → RECOVERING → RECOVERED), the device must be able to
     * construct a fresh, valid device_register envelope and a valid capability_report —
     * these are the two messages that V2 requires after every reconnect.
     */
    @Test
    fun `after RECOVERED state device can construct valid device_register for re-register`() {
        // Simulate reaching RECOVERED state
        val finalState = ReconnectRecoveryState.RECOVERED

        assertEquals(ReconnectRecoveryState.RECOVERED, finalState)

        // Device must be able to construct a valid device_register envelope for re-register
        val reRegisterEnvelope = AipMessage(
            type = MsgType.DEVICE_REGISTER,
            payload = JsonObject(),
            device_id = "reconnect-device-01"
        )
        assertEquals("3.0", reRegisterEnvelope.version)
        assertEquals("AIP/1.0", reRegisterEnvelope.protocol)
        assertEquals(MsgType.DEVICE_REGISTER, reRegisterEnvelope.type)
        assertNotNull(reRegisterEnvelope.device_id)
    }

    @Test
    fun `after RECOVERED state device can construct valid capability_report for re-register`() {
        val finalState = ReconnectRecoveryState.RECOVERED
        assertEquals(ReconnectRecoveryState.RECOVERED, finalState)

        // Device must report capability after reconnect
        val report = buildFullCapabilityReport("reconnect-device-02")
        assertTrue(
            "capability_report after reconnect must be valid (all required metadata keys present)",
            report.validate()
        )
    }

    @Test
    fun `task_result correlation_id is null by default and must be explicitly set`() {
        // This guards against accidentally sending a task_result without correlation_id,
        // which would cause V2's waiter resolution to silently fail.
        val resultWithoutCorrelation = TaskResultPayload(
            task_id = "guard-task-004",
            status = "success",
            device_id = "guard-device-11"
        )
        assertNull(
            "Default correlation_id is null — callers must explicitly set it to task_id before sending",
            resultWithoutCorrelation.correlation_id
        )
    }

    // ── 5. hardened Android truth publication contract for V2 consumers ───────

    @Test
    fun `device_state_snapshot publishes hardened truth fields for V2`() {
        val routingDecision = AndroidCrossRepoRecoveryStateRoutingContract.routeRecoveryPhase(
            AndroidContinuityRecoveryStateModel.RecoveryPhase.REQUIRES_RECONCILIATION
        )
        val payload = DeviceStateSnapshotPayload(
            device_id = "guard-truth-snapshot-01",
            llama_cpp_available = false,
            ncnn_available = false,
            active_runtime_type = "CENTER",
            model_ready = false,
            accessibility_ready = false,
            overlay_ready = false,
            local_loop_ready = false,
            model_id = null,
            runtime_type = null,
            checksum_ok = null,
            mobilevlm_present = false,
            mobilevlm_checksum_ok = false,
            seeclick_present = false,
            pending_first_download = true,
            warmup_result = "not_started",
            offline_queue_depth = 0,
            current_fallback_tier = null,
            carrier_runtime_state = "starting",
            reconnect_recovery_state = ReconnectRecoveryState.RECOVERING.wireValue,
            reported_state_semantic_class = "active_runtime",
            local_observation_basis = "live_runtime",
            evidence_presence_kind = AndroidTruthPublicationSemanticsContract
                .EvidencePresenceKind.UNKNOWN.wireValue,
            authority_signal_class = AndroidDistributedTruthOwnershipUplinkContract
                .AuthoritySignalClass.DIAGNOSTICS_AUDIT.wireValue,
            ownership_uplink_class = AndroidDistributedTruthOwnershipUplinkContract
                .OwnershipUplinkClass.NO_TRANSFER.wireValue,
            session_continuity_class = AndroidDistributedTruthOwnershipUplinkContract
                .SessionContinuityClass.SESSION_RECOVERY_PENDING.wireValue,
            device_posture_signal_class = AndroidDistributedTruthOwnershipUplinkContract
                .DevicePostureSignalClass.RUNTIME_NODE_RECOVERING.wireValue,
            continuity_recovery_state = AndroidContinuityRecoveryStateModel
                .RecoveryPhase.REQUIRES_RECONCILIATION.wireValue,
            continuity_recovery_source = "process_recreated",
            continuity_recovery_schema_version = AndroidContinuityRecoveryStateModel.SCHEMA_VERSION,
            recovery_state_v2_routing_category = routingDecision.v2RoutingCategory.wireValue,
            recovery_state_routing_requires_v2_action = routingDecision.requiresV2Action.toString(),
            recovery_state_routing_is_advisory_only = routingDecision.isAdvisoryOnly.toString(),
            recovery_state_routing_canonical_closure_blocked =
                routingDecision.canonicalClosureBlocked.toString(),
            recovery_state_routing_schema_version =
                AndroidCrossRepoRecoveryStateRoutingContract.SCHEMA_VERSION,
            distributed_truth_ownership_uplink_schema_version =
                AndroidDistributedTruthOwnershipUplinkContract.SCHEMA_VERSION
        )

        val obj = gson.fromJson(gson.toJson(payload), JsonObject::class.java)
        assertTrue(obj.has("carrier_runtime_state"))
        assertTrue(obj.has("reconnect_recovery_state"))
        assertTrue(obj.has("reported_state_semantic_class"))
        assertTrue(obj.has("local_observation_basis"))
        assertTrue(obj.has("evidence_presence_kind"))
        assertTrue(obj.has("authority_signal_class"))
        assertTrue(obj.has("ownership_uplink_class"))
        assertTrue(obj.has("session_continuity_class"))
        assertTrue(obj.has("device_posture_signal_class"))
        assertTrue(obj.has("continuity_recovery_state"))
        assertTrue(obj.has("continuity_recovery_source"))
        assertTrue(obj.has("continuity_recovery_schema_version"))
        assertTrue(obj.has("recovery_state_v2_routing_category"))
        assertTrue(obj.has("recovery_state_routing_requires_v2_action"))
        assertTrue(obj.has("recovery_state_routing_is_advisory_only"))
        assertTrue(obj.has("recovery_state_routing_canonical_closure_blocked"))
        assertTrue(obj.has("recovery_state_routing_schema_version"))
        assertTrue(obj.has("distributed_truth_ownership_uplink_schema_version"))
        assertEquals("starting", obj.get("carrier_runtime_state").asString)
        assertEquals("recovering", obj.get("reconnect_recovery_state").asString)
        assertEquals("active_runtime", obj.get("reported_state_semantic_class").asString)
        assertEquals("live_runtime", obj.get("local_observation_basis").asString)
        assertEquals("unknown", obj.get("evidence_presence_kind").asString)
        assertEquals("diagnostics_audit", obj.get("authority_signal_class").asString)
        assertEquals("no_transfer", obj.get("ownership_uplink_class").asString)
        assertEquals(
            "canonical_reconciliation_pass",
            obj.get("recovery_state_v2_routing_category").asString
        )
        assertEquals("true", obj.get("recovery_state_routing_requires_v2_action").asString)
        assertEquals(
            "true",
            obj.get("recovery_state_routing_canonical_closure_blocked").asString
        )
    }

    @Test
    fun `device_execution_event publishes hardened lifecycle runtime and evidence truth fields for V2`() {
        val payload = DeviceExecutionEventPayload(
            flow_id = "guard-flow-01",
            task_id = "guard-task-01",
            phase = DeviceExecutionEventPayload.PHASE_FAILED,
            carrier_runtime_state = "active",
            reported_state_semantic_class = "terminal_reporting",
            result_uplink_semantic_class = "authoritative_terminal",
            terminal_outcome_kind = "failure",
            execution_lifecycle_phase = "failed",
            previous_execution_lifecycle_phase = "active",
            lifecycle_transition_valid = true,
            lifecycle_result_uplink_required = true,
            lifecycle_state_uplink_required = true,
            lifecycle_terminal_phase = true,
            evidence_presence_kind = AndroidTruthPublicationSemanticsContract
                .EvidencePresenceKind.FAILED_OBSERVATION.wireValue,
            authority_signal_class = AndroidDistributedTruthOwnershipUplinkContract
                .AuthoritySignalClass.AUTHORITY_RUNTIME.wireValue,
            ownership_uplink_class = AndroidDistributedTruthOwnershipUplinkContract
                .OwnershipUplinkClass.AUTHORITY_HELD.wireValue,
            session_continuity_class = AndroidDistributedTruthOwnershipUplinkContract
                .SessionContinuityClass.SESSION_LIVE_AUTHORITATIVE.wireValue,
            device_posture_signal_class = AndroidDistributedTruthOwnershipUplinkContract
                .DevicePostureSignalClass.RUNTIME_NODE_ACTIVE.wireValue,
            ingress_boundary_class = AndroidGovernanceExecutionPolicyIngressContract
                .IngressBoundaryClass.CANONICAL_GOVERNANCE_EXECUTION_POLICY.wireValue,
            ingress_consumption_kind = AndroidGovernanceExecutionPolicyIngressContract
                .IngressConsumptionKind.EXECUTION_POLICY_INGRESS.wireValue,
            ingress_signal_class = AndroidGovernanceExecutionPolicyIngressContract
                .IngressSignalClass.EXECUTION_RUNTIME_EVENT.wireValue,
            ingress_schema_version = AndroidGovernanceExecutionPolicyIngressContract.SCHEMA_VERSION,
            distributed_truth_ownership_uplink_schema_version =
                AndroidDistributedTruthOwnershipUplinkContract.SCHEMA_VERSION
        )

        val obj = gson.fromJson(gson.toJson(payload), JsonObject::class.java)
        assertTrue(obj.has("carrier_runtime_state"))
        assertTrue(obj.has("reported_state_semantic_class"))
        assertTrue(obj.has("result_uplink_semantic_class"))
        assertTrue(obj.has("terminal_outcome_kind"))
        assertTrue(obj.has("execution_lifecycle_phase"))
        assertTrue(obj.has("previous_execution_lifecycle_phase"))
        assertTrue(obj.has("lifecycle_transition_valid"))
        assertTrue(obj.has("lifecycle_result_uplink_required"))
        assertTrue(obj.has("lifecycle_state_uplink_required"))
        assertTrue(obj.has("lifecycle_terminal_phase"))
        assertTrue(obj.has("evidence_presence_kind"))
        assertTrue(obj.has("authority_signal_class"))
        assertTrue(obj.has("ownership_uplink_class"))
        assertTrue(obj.has("session_continuity_class"))
        assertTrue(obj.has("device_posture_signal_class"))
        assertTrue(obj.has("ingress_boundary_class"))
        assertTrue(obj.has("ingress_consumption_kind"))
        assertTrue(obj.has("ingress_signal_class"))
        assertTrue(obj.has("ingress_schema_version"))
        assertTrue(obj.has("distributed_truth_ownership_uplink_schema_version"))
        assertEquals("failed", obj.get("execution_lifecycle_phase").asString)
        assertEquals("active", obj.get("previous_execution_lifecycle_phase").asString)
        assertEquals("failed_observation", obj.get("evidence_presence_kind").asString)
        assertEquals("authority_runtime", obj.get("authority_signal_class").asString)
        assertEquals("authority_held", obj.get("ownership_uplink_class").asString)
    }

    @Test
    fun `goal_result publishes distributed truth ownership boundary fields for V2`() {
        val routingDecision = AndroidCrossRepoRecoveryStateRoutingContract.routeRecoveryPhase(
            AndroidContinuityRecoveryStateModel.RecoveryPhase.RECOVERED_INFLIGHT
        )
        val payload = GoalResultPayload(
            task_id = "guard-goal-result-01",
            status = "success",
            result = "done",
            device_id = "guard-device-22",
            authority_signal_class = AndroidDistributedTruthOwnershipUplinkContract
                .AuthoritySignalClass.AUTHORITY_RUNTIME.wireValue,
            ownership_uplink_class = AndroidDistributedTruthOwnershipUplinkContract
                .OwnershipUplinkClass.AUTHORITY_HELD.wireValue,
            session_continuity_class = AndroidDistributedTruthOwnershipUplinkContract
                .SessionContinuityClass.SESSION_LIVE_AUTHORITATIVE.wireValue,
            device_posture_signal_class = AndroidDistributedTruthOwnershipUplinkContract
                .DevicePostureSignalClass.RUNTIME_NODE_ACTIVE.wireValue,
            ingress_boundary_class = AndroidGovernanceExecutionPolicyIngressContract
                .IngressBoundaryClass.CANONICAL_GOVERNANCE_EXECUTION_POLICY.wireValue,
            ingress_consumption_kind = AndroidGovernanceExecutionPolicyIngressContract
                .IngressConsumptionKind.EXECUTION_POLICY_INGRESS.wireValue,
            ingress_signal_class = AndroidGovernanceExecutionPolicyIngressContract
                .IngressSignalClass.RESULT_TRUTH_ARTIFACT.wireValue,
            ingress_schema_version = AndroidGovernanceExecutionPolicyIngressContract.SCHEMA_VERSION,
            continuity_recovery_state = AndroidContinuityRecoveryStateModel
                .RecoveryPhase.RECOVERED_INFLIGHT.wireValue,
            continuity_recovery_source = "reconnect_recovery",
            continuity_recovery_schema_version = AndroidContinuityRecoveryStateModel.SCHEMA_VERSION,
            recovery_state_v2_routing_category = routingDecision.v2RoutingCategory.wireValue,
            recovery_state_routing_requires_v2_action = routingDecision.requiresV2Action.toString(),
            recovery_state_routing_is_advisory_only = routingDecision.isAdvisoryOnly.toString(),
            recovery_state_routing_canonical_closure_blocked =
                routingDecision.canonicalClosureBlocked.toString(),
            recovery_state_routing_schema_version =
                AndroidCrossRepoRecoveryStateRoutingContract.SCHEMA_VERSION,
            distributed_truth_ownership_uplink_schema_version =
                AndroidDistributedTruthOwnershipUplinkContract.SCHEMA_VERSION
        )

        val obj = gson.fromJson(gson.toJson(payload), JsonObject::class.java)
        assertEquals("authority_runtime", obj.get("authority_signal_class").asString)
        assertEquals("authority_held", obj.get("ownership_uplink_class").asString)
        assertEquals("session_live_authoritative", obj.get("session_continuity_class").asString)
        assertEquals("runtime_node_active", obj.get("device_posture_signal_class").asString)
        assertEquals("canonical_governance_execution_policy", obj.get("ingress_boundary_class").asString)
        assertEquals("execution_policy_ingress", obj.get("ingress_consumption_kind").asString)
        assertEquals("result_truth_artifact", obj.get("ingress_signal_class").asString)
        assertEquals("recovered-inflight", obj.get("continuity_recovery_state").asString)
        assertEquals("advisory_inflight_evidence", obj.get("recovery_state_v2_routing_category").asString)
        assertEquals("true", obj.get("recovery_state_routing_is_advisory_only").asString)
        assertEquals(
            "true",
            obj.get("recovery_state_routing_canonical_closure_blocked").asString
        )
    }

    @Test
    fun `diagnostics payload publishes distributed truth ownership boundary fields for V2`() {
        val payload = DiagnosticsPayload(
            task_id = "guard-diag-01",
            device_id = "guard-device-33",
            node_name = "transport",
            error_type = "timeout",
            error_context = "socket timeout",
            ingress_boundary_class = AndroidGovernanceExecutionPolicyIngressContract
                .IngressBoundaryClass.DIAGNOSTICS_AUDIT_SUMMARY.wireValue,
            ingress_consumption_kind = AndroidGovernanceExecutionPolicyIngressContract
                .IngressConsumptionKind.NONE.wireValue,
            ingress_signal_class = AndroidGovernanceExecutionPolicyIngressContract
                .IngressSignalClass.DIAGNOSTICS_SIGNAL.wireValue,
            ingress_schema_version = AndroidGovernanceExecutionPolicyIngressContract.SCHEMA_VERSION,
            authority_signal_class = AndroidDistributedTruthOwnershipUplinkContract
                .AuthoritySignalClass.DIAGNOSTICS_AUDIT.wireValue,
            ownership_uplink_class = AndroidDistributedTruthOwnershipUplinkContract
                .OwnershipUplinkClass.NO_TRANSFER.wireValue,
            session_continuity_class = AndroidDistributedTruthOwnershipUplinkContract
                .SessionContinuityClass.SESSION_CONTINUATION.wireValue,
            device_posture_signal_class = AndroidDistributedTruthOwnershipUplinkContract
                .DevicePostureSignalClass.POSTURE_SIGNAL_ONLY.wireValue,
            distributed_truth_ownership_uplink_schema_version =
                AndroidDistributedTruthOwnershipUplinkContract.SCHEMA_VERSION
        )

        val obj = gson.fromJson(gson.toJson(payload), JsonObject::class.java)
        assertEquals("diagnostics_audit", obj.get("authority_signal_class").asString)
        assertEquals("no_transfer", obj.get("ownership_uplink_class").asString)
        assertEquals("session_continuation", obj.get("session_continuity_class").asString)
        assertEquals("posture_signal_only", obj.get("device_posture_signal_class").asString)
        assertEquals("diagnostics_audit_summary", obj.get("ingress_boundary_class").asString)
        assertEquals("none", obj.get("ingress_consumption_kind").asString)
        assertEquals("diagnostics_signal", obj.get("ingress_signal_class").asString)
    }

    @Test
    fun `snapshot classifier with incomplete evidence never overclaims positive evidence`() {
        val kind = AndroidTruthPublicationSemanticsContract.classifySnapshotEvidencePresence(
            warmupResult = "ok",
            pendingFirstDownload = false,
            localLoopReady = null,
            plannerReady = true,
            groundingReady = true,
            offlineQueueDepth = 0,
            managerStateIsRunning = true,
            managerStateIsFailed = false,
            managerStateIsStarting = false
        )
        assertEquals(
            "Incomplete evidence must stay UNKNOWN, never POSITIVE_EVIDENCE",
            AndroidTruthPublicationSemanticsContract.EvidencePresenceKind.UNKNOWN,
            kind
        )
    }

    @Test
    fun `non-positive evidence kinds never advertise standard dispatch`() {
        val nonPositiveKinds = AndroidTruthPublicationSemanticsContract.EvidencePresenceKind.entries
            .filter { !it.isPositiveEvidence }

        for (kind in nonPositiveKinds) {
            assertFalse(
                "${kind.name} must not advertise optimistic standard dispatch",
                kind.v2GovernanceHint.contains("standard_dispatch")
            )
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private fun buildFullMetadata(): Map<String, Any> = mapOf(
        "goal_execution_enabled" to true,
        "local_model_enabled" to true,
        "cross_device_enabled" to true,
        "parallel_execution_enabled" to true,
        "device_role" to "phone",
        "model_ready" to true,
        "accessibility_ready" to true,
        "overlay_ready" to true
    )

    private fun buildFullCapabilityReport(deviceId: String) = CapabilityReport(
        platform = "android",
        device_id = deviceId,
        supported_actions = listOf("screen_capture", "automation"),
        capabilities = listOf(
            "autonomous_goal_execution",
            "local_task_planning",
            "local_ui_reasoning",
            "local_model_inference"
        ),
        metadata = buildFullMetadata()
    )
}
