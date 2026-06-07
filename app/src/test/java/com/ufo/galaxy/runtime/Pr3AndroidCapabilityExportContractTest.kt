package com.ufo.galaxy.runtime

import com.ufo.galaxy.capability.AndroidCapabilityVector
import com.ufo.galaxy.data.CapabilityReport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PR-3 — Android capability export contract regression tests.
 *
 * Regression-protection suite for [AndroidCapabilityExportContract].  Failure in any test here
 * means a capability export schema change has occurred without a corresponding
 * [AndroidCapabilityExportContract.CONTRACT_SCHEMA_VERSION] bump and explicit cross-repo review.
 *
 * **Do not mark any test as skipped or suppress any assertion** without:
 *  1. Incrementing [AndroidCapabilityExportContract.CONTRACT_SCHEMA_VERSION].
 *  2. Updating the corresponding V2-side `capability_registry.py` expected version.
 *  3. Recording the change in [AndroidCapabilityExportContract]'s schema versioning section.
 *
 * ## What these tests guard
 *
 * | Test group                         | What drift is detected                                       |
 * |------------------------------------|--------------------------------------------------------------|
 * | Schema version stability           | Accidental [CONTRACT_SCHEMA_VERSION] change.                 |
 * | Required field count stability     | Addition or removal of required top-level / metadata fields. |
 * | Required field name set stability  | Renaming of any required field key.                          |
 * | Field type constraints             | Validate() correctly rejects wrong-typed values.             |
 * | Vocabulary constraints             | Validate() correctly rejects out-of-vocabulary string values.|
 * | Complete-payload happy-path        | Validate() passes for a correctly assembled payload.         |
 * | Metadata validate() isolation      | validateMetadata() independently catches missing/wrong fields.|
 * | Contract/CapabilityReport alignment| REQUIRED_METADATA_KEYS superset of CapabilityReport keys.   |
 *
 * All tests are pure-JVM; no device, emulator, or network is required.
 */
class Pr3AndroidCapabilityExportContractTest {

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Builds a complete, valid top-level capability_report payload.
     * All required fields are present with canonical values.
     */
    private fun completeTopLevel(): Map<String, Any> {
        val metadata = completeMetadata()
        return mapOf(
            "type"              to "capability_report",
            "version"           to "3.0",
            "platform"          to "android",
            "device_id"         to "test-device-pr3",
            "device_type"       to "Android_Agent",
            "trace_id"          to "trace-pr3-001",
            "route_mode"        to "local_only",
            "idempotency_key"   to "idem-pr3-001",
            "supported_actions" to listOf("screen_capture", "app_control"),
            "capabilities"      to listOf("autonomous_goal_execution"),
            "metadata"          to metadata
        )
    }

    /**
     * Builds a complete, valid metadata map with all 29 required fields present and correct.
     */
    private fun completeMetadata(): Map<String, Any> = mapOf(
        // Core readiness signals
        "goal_execution_enabled"        to false,
        "local_model_enabled"           to false,
        "cross_device_enabled"          to false,
        "parallel_execution_enabled"    to false,
        "device_role"                   to "phone",
        "model_ready"                   to false,
        "accessibility_ready"           to false,
        "overlay_ready"                 to false,
        // Scheduling basis signals
        AndroidCapabilityVector.KEY_LOCAL_ELIGIBLE            to false,
        AndroidCapabilityVector.KEY_CROSS_DEVICE_ELIGIBLE     to false,
        AndroidCapabilityVector.KEY_PARALLEL_SUBTASK_ELIGIBLE to false,
        AndroidCapabilityVector.KEY_EXECUTION_DIMENSIONS      to "",
        // Canonical gate signals
        "degraded_mode"                 to true,
        "mode_state"                    to "local_only",
        "mode_readiness_state"          to "degraded",
        "cross_device_eligibility"      to false,
        "goal_execution_eligibility"    to false,
        "parallel_execution_eligibility" to false,
        "local_intelligence_status"     to "disabled",
        "local_inference_ready"         to false,
        "local_inference_available"     to false,
        LocalExecutionModeGate.KEY_EXECUTION_MODE_STATE       to "local_only",
        LocalExecutionModeGate.KEY_ACCEPTS_CROSS_DEVICE_TASKS to false,
        LocalExecutionModeGate.KEY_V2_GOVERNANCE_ACTIVE       to false,
        LocalExecutionModeGate.KEY_IS_HOLD_STATE              to false,
        LocalExecutionModeGate.KEY_DEGRADATION_REASONS        to "",
        LocalExecutionModeGate.KEY_SEMANTIC_TAG               to "local_only_inactive",
        LocalExecutionModeGate.KEY_SCHEMA_VERSION             to LocalExecutionModeGate.SCHEMA_VERSION,
        // Export contract schema version
        AndroidCapabilityExportContract.CONTRACT_SCHEMA_VERSION_KEY to
            AndroidCapabilityExportContract.CONTRACT_SCHEMA_VERSION
    )

    // ── 1. Schema version stability ───────────────────────────────────────────

    /**
     * CONTRACT_SCHEMA_VERSION must remain "1" until an intentional breaking change.
     *
     * If you are seeing this failure because you added/removed a required field, changed a type
     * constraint, or changed a vocabulary value, increment CONTRACT_SCHEMA_VERSION to "2" (or the
     * next appropriate value) and update the V2-side expected version accordingly.
     */
    @Test
    fun `CONTRACT_SCHEMA_VERSION is stable at 1`() {
        assertEquals(
            "CONTRACT_SCHEMA_VERSION drift detected — increment it and update V2-side schema expectation",
            "1",
            AndroidCapabilityExportContract.CONTRACT_SCHEMA_VERSION
        )
    }

    @Test
    fun `CONTRACT_SCHEMA_VERSION_KEY is stable at contract_schema_version`() {
        assertEquals(
            "contract_schema_version",
            AndroidCapabilityExportContract.CONTRACT_SCHEMA_VERSION_KEY
        )
    }

    @Test
    fun `CONTRACT_SCHEMA_VERSION is non-blank`() {
        assertTrue(
            "CONTRACT_SCHEMA_VERSION must be non-blank",
            AndroidCapabilityExportContract.CONTRACT_SCHEMA_VERSION.isNotBlank()
        )
    }

    // ── 2. Required top-level field count stability ───────────────────────────

    /**
     * The count of required top-level fields is a regression anchor.
     *
     * If this test fails, a required field was added or removed. You must:
     *  1. Increment CONTRACT_SCHEMA_VERSION.
     *  2. Update EXPECTED_REQUIRED_TOP_LEVEL_FIELD_COUNT.
     *  3. Notify V2 consumers of the schema change.
     */
    @Test
    fun `required top-level field count matches regression anchor`() {
        assertEquals(
            "Required top-level field count changed — bump CONTRACT_SCHEMA_VERSION and " +
                "update EXPECTED_REQUIRED_TOP_LEVEL_FIELD_COUNT",
            AndroidCapabilityExportContract.EXPECTED_REQUIRED_TOP_LEVEL_FIELD_COUNT,
            AndroidCapabilityExportContract.REQUIRED_TOP_LEVEL_PAYLOAD_KEYS.size
        )
    }

    @Test
    fun `EXPECTED_REQUIRED_TOP_LEVEL_FIELD_COUNT is 11`() {
        assertEquals(
            "EXPECTED_REQUIRED_TOP_LEVEL_FIELD_COUNT drift detected",
            11,
            AndroidCapabilityExportContract.EXPECTED_REQUIRED_TOP_LEVEL_FIELD_COUNT
        )
    }

    // ── 3. Required metadata field count stability ────────────────────────────

    /**
     * The count of required metadata fields is a regression anchor.
     *
     * If this test fails, a required metadata field was added or removed. You must:
     *  1. Increment CONTRACT_SCHEMA_VERSION.
     *  2. Update EXPECTED_REQUIRED_METADATA_FIELD_COUNT.
     *  3. Notify V2 consumers of the schema change.
     */
    @Test
    fun `required metadata field count matches regression anchor`() {
        assertEquals(
            "Required metadata field count changed — bump CONTRACT_SCHEMA_VERSION and " +
                "update EXPECTED_REQUIRED_METADATA_FIELD_COUNT",
            AndroidCapabilityExportContract.EXPECTED_REQUIRED_METADATA_FIELD_COUNT,
            AndroidCapabilityExportContract.REQUIRED_METADATA_KEYS.size
        )
    }

    @Test
    fun `EXPECTED_REQUIRED_METADATA_FIELD_COUNT is 29`() {
        assertEquals(
            "EXPECTED_REQUIRED_METADATA_FIELD_COUNT drift detected",
            29,
            AndroidCapabilityExportContract.EXPECTED_REQUIRED_METADATA_FIELD_COUNT
        )
    }

    // ── 4. Required top-level field name stability ────────────────────────────

    /**
     * The exact set of required top-level field names is a regression anchor.
     *
     * Renaming any field in this set changes the wire format that V2 consumes.
     */
    @Test
    fun `required top-level field names are stable`() {
        val expected = setOf(
            "type", "version", "platform", "device_id", "device_type",
            "trace_id", "route_mode", "idempotency_key",
            "supported_actions", "capabilities", "metadata"
        )
        assertEquals(
            "Required top-level field names changed — bump CONTRACT_SCHEMA_VERSION",
            expected,
            AndroidCapabilityExportContract.REQUIRED_TOP_LEVEL_PAYLOAD_KEYS
        )
    }

    // ── 5. Required metadata field name stability ─────────────────────────────

    /**
     * The exact set of required metadata field names is a regression anchor.
     *
     * Any change to this set changes the wire format that V2's capability ingestion
     * pipeline consumes.
     */
    @Test
    fun `required metadata field names include all 8 core readiness keys`() {
        val coreKeys = CapabilityReport.REQUIRED_METADATA_KEYS
        val contractKeys = AndroidCapabilityExportContract.REQUIRED_METADATA_KEYS
        val missing = coreKeys - contractKeys
        assertTrue(
            "Contract is missing core readiness metadata keys: $missing",
            missing.isEmpty()
        )
    }

    @Test
    fun `required metadata field names include all 4 scheduling basis keys`() {
        val schedulingKeys = CapabilityReport.SCHEDULING_BASIS_METADATA_KEYS
        val contractKeys = AndroidCapabilityExportContract.REQUIRED_METADATA_KEYS
        val missing = schedulingKeys - contractKeys
        assertTrue(
            "Contract is missing scheduling basis metadata keys: $missing",
            missing.isEmpty()
        )
    }

    @Test
    fun `required metadata field names include all 16 canonical gate keys`() {
        val gateKeys = CapabilityReport.CANONICAL_GATE_METADATA_KEYS
        val contractKeys = AndroidCapabilityExportContract.REQUIRED_METADATA_KEYS
        val missing = gateKeys - contractKeys
        assertTrue(
            "Contract is missing canonical gate metadata keys: $missing",
            missing.isEmpty()
        )
    }

    @Test
    fun `required metadata field names include contract_schema_version key`() {
        assertTrue(
            "contract_schema_version must be a required metadata field in the export contract",
            AndroidCapabilityExportContract.CONTRACT_SCHEMA_VERSION_KEY in
                AndroidCapabilityExportContract.REQUIRED_METADATA_KEYS
        )
    }

    @Test
    fun `required metadata field names include execution_mode_state`() {
        assertTrue(
            LocalExecutionModeGate.KEY_EXECUTION_MODE_STATE in
                AndroidCapabilityExportContract.REQUIRED_METADATA_KEYS
        )
    }

    @Test
    fun `required metadata field names include mode_gate_schema_version`() {
        assertTrue(
            LocalExecutionModeGate.KEY_SCHEMA_VERSION in
                AndroidCapabilityExportContract.REQUIRED_METADATA_KEYS
        )
    }

    // ── 6. Vocabulary constraints stability ───────────────────────────────────

    @Test
    fun `execution_mode_state descriptor vocabulary covers all ExecutionModeState wire values`() {
        val expectedVocab = LocalExecutionModeGate.ExecutionModeState.entries
            .map { it.wireValue }.toSet()
        val descriptor = AndroidCapabilityExportContract.METADATA_FIELD_DESCRIPTORS
            .first { it.key == LocalExecutionModeGate.KEY_EXECUTION_MODE_STATE }
        assertEquals(
            "execution_mode_state vocabulary must cover all LocalExecutionModeGate.ExecutionModeState values",
            expectedVocab,
            descriptor.vocabularyValues
        )
    }

    @Test
    fun `local_intelligence_status descriptor vocabulary covers all LocalIntelligenceCapabilityStatus wire values`() {
        val expectedVocab = LocalIntelligenceCapabilityStatus.entries
            .map { it.wireValue }.toSet()
        val descriptor = AndroidCapabilityExportContract.METADATA_FIELD_DESCRIPTORS
            .first { it.key == "local_intelligence_status" }
        assertEquals(
            "local_intelligence_status vocabulary must cover all LocalIntelligenceCapabilityStatus wire values",
            expectedVocab,
            descriptor.vocabularyValues
        )
    }

    @Test
    fun `mode_state descriptor vocabulary is cross_device and local_only`() {
        val descriptor = AndroidCapabilityExportContract.METADATA_FIELD_DESCRIPTORS
            .first { it.key == "mode_state" }
        assertEquals(
            setOf("cross_device", "local_only"),
            descriptor.vocabularyValues
        )
    }

    @Test
    fun `contract_schema_version descriptor vocabulary contains only the current version`() {
        val descriptor = AndroidCapabilityExportContract.METADATA_FIELD_DESCRIPTORS
            .first { it.key == AndroidCapabilityExportContract.CONTRACT_SCHEMA_VERSION_KEY }
        assertNotNull("contract_schema_version descriptor must have vocabularyValues", descriptor.vocabularyValues)
        assertEquals(
            "contract_schema_version vocabulary must be exactly {CONTRACT_SCHEMA_VERSION}",
            setOf(AndroidCapabilityExportContract.CONTRACT_SCHEMA_VERSION),
            descriptor.vocabularyValues
        )
    }

    @Test
    fun `route_mode descriptor vocabulary is cross_device and local_only`() {
        val descriptor = AndroidCapabilityExportContract.TOP_LEVEL_FIELD_DESCRIPTORS
            .first { it.key == "route_mode" }
        assertEquals(
            setOf("cross_device", "local_only"),
            descriptor.vocabularyValues
        )
    }

    @Test
    fun `type descriptor vocabulary is capability_report`() {
        val descriptor = AndroidCapabilityExportContract.TOP_LEVEL_FIELD_DESCRIPTORS
            .first { it.key == "type" }
        assertEquals(setOf("capability_report"), descriptor.vocabularyValues)
    }

    @Test
    fun `version descriptor vocabulary is 3_0`() {
        val descriptor = AndroidCapabilityExportContract.TOP_LEVEL_FIELD_DESCRIPTORS
            .first { it.key == "version" }
        assertEquals(setOf("3.0"), descriptor.vocabularyValues)
    }

    // ── 7. validate() happy path ──────────────────────────────────────────────

    @Test
    fun `validate passes for complete valid top-level payload`() {
        val result = AndroidCapabilityExportContract.validate(completeTopLevel())
        assertTrue(
            "Expected validate() to pass for a complete valid payload, but got issues: ${result.issues}",
            result.valid
        )
        assertTrue("Issues list must be empty on a valid payload", result.issues.isEmpty())
    }

    @Test
    fun `validateMetadata passes for complete valid metadata`() {
        val result = AndroidCapabilityExportContract.validateMetadata(completeMetadata())
        assertTrue(
            "Expected validateMetadata() to pass for complete metadata, but got issues: ${result.issues}",
            result.valid
        )
    }

    // ── 8. validate() detects missing top-level fields ────────────────────────

    @Test
    fun `validate fails when type is missing`() {
        val payload = completeTopLevel() - "type"
        val result = AndroidCapabilityExportContract.validate(payload)
        assertFalse("validate() must fail when 'type' is missing", result.valid)
        assertTrue(result.issues.any { "type" in it })
    }

    @Test
    fun `validate fails when device_id is missing`() {
        val payload = completeTopLevel() - "device_id"
        val result = AndroidCapabilityExportContract.validate(payload)
        assertFalse(result.valid)
        assertTrue(result.issues.any { "device_id" in it })
    }

    @Test
    fun `validate fails when metadata is missing`() {
        val payload = completeTopLevel() - "metadata"
        val result = AndroidCapabilityExportContract.validate(payload)
        assertFalse(result.valid)
        assertTrue(result.issues.any { "metadata" in it })
    }

    @Test
    fun `validate fails when supported_actions is missing`() {
        val payload = completeTopLevel() - "supported_actions"
        val result = AndroidCapabilityExportContract.validate(payload)
        assertFalse(result.valid)
        assertTrue(result.issues.any { "supported_actions" in it })
    }

    @Test
    fun `validate fails when capabilities is missing`() {
        val payload = completeTopLevel() - "capabilities"
        val result = AndroidCapabilityExportContract.validate(payload)
        assertFalse(result.valid)
        assertTrue(result.issues.any { "capabilities" in it })
    }

    // ── 9. validate() detects type violations ────────────────────────────────

    @Test
    fun `validate fails when type field has wrong value`() {
        val payload = completeTopLevel().toMutableMap().apply { put("type", "device_register") }
        val result = AndroidCapabilityExportContract.validate(payload)
        assertFalse(result.valid)
        assertTrue(result.issues.any { "type" in it && "device_register" in it })
    }

    @Test
    fun `validate fails when platform has wrong value`() {
        val payload = completeTopLevel().toMutableMap().apply { put("platform", "ios") }
        val result = AndroidCapabilityExportContract.validate(payload)
        assertFalse(result.valid)
        assertTrue(result.issues.any { "platform" in it && "ios" in it })
    }

    @Test
    fun `validate fails when version has wrong value`() {
        val payload = completeTopLevel().toMutableMap().apply { put("version", "2.0") }
        val result = AndroidCapabilityExportContract.validate(payload)
        assertFalse(result.valid)
        assertTrue(result.issues.any { "version" in it && "2.0" in it })
    }

    @Test
    fun `validate fails when device_type has wrong value`() {
        val payload = completeTopLevel().toMutableMap().apply { put("device_type", "iOS_Agent") }
        val result = AndroidCapabilityExportContract.validate(payload)
        assertFalse(result.valid)
        assertTrue(result.issues.any { "device_type" in it })
    }

    @Test
    fun `validate fails when route_mode has unknown value`() {
        val payload = completeTopLevel().toMutableMap().apply { put("route_mode", "unknown_mode") }
        val result = AndroidCapabilityExportContract.validate(payload)
        assertFalse(result.valid)
        assertTrue(result.issues.any { "route_mode" in it && "unknown_mode" in it })
    }

    @Test
    fun `validate fails when supported_actions is a String instead of List`() {
        val payload = completeTopLevel().toMutableMap().apply {
            put("supported_actions", "screen_capture,app_control")
        }
        val result = AndroidCapabilityExportContract.validate(payload)
        assertFalse(result.valid)
        assertTrue(result.issues.any { "supported_actions" in it })
    }

    // ── 10. validateMetadata() detects missing metadata fields ────────────────

    @Test
    fun `validateMetadata fails when contract_schema_version is missing`() {
        val metadata = completeMetadata() - AndroidCapabilityExportContract.CONTRACT_SCHEMA_VERSION_KEY
        val result = AndroidCapabilityExportContract.validateMetadata(metadata)
        assertFalse(
            "validateMetadata() must fail when contract_schema_version is absent",
            result.valid
        )
        assertTrue(
            result.issues.any {
                AndroidCapabilityExportContract.CONTRACT_SCHEMA_VERSION_KEY in it
            }
        )
    }

    @Test
    fun `validateMetadata fails when goal_execution_enabled is missing`() {
        val metadata = completeMetadata() - "goal_execution_enabled"
        val result = AndroidCapabilityExportContract.validateMetadata(metadata)
        assertFalse(result.valid)
        assertTrue(result.issues.any { "goal_execution_enabled" in it })
    }

    @Test
    fun `validateMetadata fails when execution_mode_state is missing`() {
        val metadata = completeMetadata() - LocalExecutionModeGate.KEY_EXECUTION_MODE_STATE
        val result = AndroidCapabilityExportContract.validateMetadata(metadata)
        assertFalse(result.valid)
        assertTrue(result.issues.any { LocalExecutionModeGate.KEY_EXECUTION_MODE_STATE in it })
    }

    @Test
    fun `validateMetadata fails when scheduling_local_eligible is missing`() {
        val metadata = completeMetadata() - AndroidCapabilityVector.KEY_LOCAL_ELIGIBLE
        val result = AndroidCapabilityExportContract.validateMetadata(metadata)
        assertFalse(result.valid)
        assertTrue(result.issues.any { AndroidCapabilityVector.KEY_LOCAL_ELIGIBLE in it })
    }

    // ── 11. validateMetadata() detects type and vocabulary violations ──────────

    @Test
    fun `validateMetadata fails when goal_execution_enabled is String not Boolean`() {
        val metadata = completeMetadata().toMutableMap().apply {
            put("goal_execution_enabled", "true")
        }
        val result = AndroidCapabilityExportContract.validateMetadata(metadata)
        assertFalse(result.valid)
        assertTrue(result.issues.any { "goal_execution_enabled" in it })
    }

    @Test
    fun `validateMetadata fails when local_intelligence_status has unknown value`() {
        val metadata = completeMetadata().toMutableMap().apply {
            put("local_intelligence_status", "supercharged")
        }
        val result = AndroidCapabilityExportContract.validateMetadata(metadata)
        assertFalse(result.valid)
        assertTrue(result.issues.any { "local_intelligence_status" in it && "supercharged" in it })
    }

    @Test
    fun `validateMetadata fails when contract_schema_version has wrong version`() {
        val metadata = completeMetadata().toMutableMap().apply {
            put(AndroidCapabilityExportContract.CONTRACT_SCHEMA_VERSION_KEY, "99")
        }
        val result = AndroidCapabilityExportContract.validateMetadata(metadata)
        assertFalse(result.valid)
        assertTrue(
            result.issues.any {
                AndroidCapabilityExportContract.CONTRACT_SCHEMA_VERSION_KEY in it && "99" in it
            }
        )
    }

    @Test
    fun `validateMetadata fails when execution_mode_state has unknown value`() {
        val metadata = completeMetadata().toMutableMap().apply {
            put(LocalExecutionModeGate.KEY_EXECUTION_MODE_STATE, "turbo_mode")
        }
        val result = AndroidCapabilityExportContract.validateMetadata(metadata)
        assertFalse(result.valid)
        assertTrue(
            result.issues.any {
                LocalExecutionModeGate.KEY_EXECUTION_MODE_STATE in it && "turbo_mode" in it
            }
        )
    }

    @Test
    fun `validateMetadata fails when mode_state has wrong value`() {
        val metadata = completeMetadata().toMutableMap().apply {
            put("mode_state", "hybrid_mode")
        }
        val result = AndroidCapabilityExportContract.validateMetadata(metadata)
        assertFalse(result.valid)
        assertTrue(result.issues.any { "mode_state" in it && "hybrid_mode" in it })
    }

    @Test
    fun `validateMetadata fails when mode_gate_schema_version has wrong value`() {
        val metadata = completeMetadata().toMutableMap().apply {
            put(LocalExecutionModeGate.KEY_SCHEMA_VERSION, "9.9")
        }
        val result = AndroidCapabilityExportContract.validateMetadata(metadata)
        assertFalse(result.valid)
        assertTrue(result.issues.any { LocalExecutionModeGate.KEY_SCHEMA_VERSION in it })
    }

    // ── 12. Contract/CapabilityReport alignment ───────────────────────────────

    @Test
    fun `contract REQUIRED_METADATA_KEYS is a superset of CapabilityReport REQUIRED_METADATA_KEYS`() {
        val missing = CapabilityReport.REQUIRED_METADATA_KEYS -
            AndroidCapabilityExportContract.REQUIRED_METADATA_KEYS
        assertTrue(
            "AndroidCapabilityExportContract.REQUIRED_METADATA_KEYS must be a superset of " +
                "CapabilityReport.REQUIRED_METADATA_KEYS. Missing: $missing",
            missing.isEmpty()
        )
    }

    @Test
    fun `contract REQUIRED_METADATA_KEYS is a superset of CapabilityReport SCHEDULING_BASIS_METADATA_KEYS`() {
        val missing = CapabilityReport.SCHEDULING_BASIS_METADATA_KEYS -
            AndroidCapabilityExportContract.REQUIRED_METADATA_KEYS
        assertTrue(
            "AndroidCapabilityExportContract.REQUIRED_METADATA_KEYS must be a superset of " +
                "CapabilityReport.SCHEDULING_BASIS_METADATA_KEYS. Missing: $missing",
            missing.isEmpty()
        )
    }

    @Test
    fun `contract REQUIRED_METADATA_KEYS is a superset of CapabilityReport CANONICAL_GATE_METADATA_KEYS`() {
        val missing = CapabilityReport.CANONICAL_GATE_METADATA_KEYS -
            AndroidCapabilityExportContract.REQUIRED_METADATA_KEYS
        assertTrue(
            "AndroidCapabilityExportContract.REQUIRED_METADATA_KEYS must be a superset of " +
                "CapabilityReport.CANONICAL_GATE_METADATA_KEYS. Missing: $missing",
            missing.isEmpty()
        )
    }

    // ── 13. ValidationResult structure ────────────────────────────────────────

    @Test
    fun `ValidationResult is valid when issues are empty`() {
        val result = AndroidCapabilityExportContract.ValidationResult(
            valid = true,
            issues = emptyList()
        )
        assertTrue(result.valid)
        assertTrue(result.issues.isEmpty())
    }

    @Test
    fun `ValidationResult is invalid when issues are non-empty`() {
        val result = AndroidCapabilityExportContract.ValidationResult(
            valid = false,
            issues = listOf("missing required field 'device_id'")
        )
        assertFalse(result.valid)
        assertEquals(1, result.issues.size)
    }

    // ── 14. FieldDescriptor structure ────────────────────────────────────────

    @Test
    fun `all required top-level descriptors have non-blank keys`() {
        AndroidCapabilityExportContract.TOP_LEVEL_FIELD_DESCRIPTORS
            .filter { it.required }
            .forEach { descriptor ->
                assertTrue(
                    "Descriptor key must be non-blank: $descriptor",
                    descriptor.key.isNotBlank()
                )
            }
    }

    @Test
    fun `all required metadata descriptors have non-blank keys`() {
        AndroidCapabilityExportContract.METADATA_FIELD_DESCRIPTORS
            .filter { it.required }
            .forEach { descriptor ->
                assertTrue(
                    "Metadata descriptor key must be non-blank: $descriptor",
                    descriptor.key.isNotBlank()
                )
            }
    }

    @Test
    fun `no duplicate keys in METADATA_FIELD_DESCRIPTORS`() {
        val keys = AndroidCapabilityExportContract.METADATA_FIELD_DESCRIPTORS.map { it.key }
        val duplicates = keys.groupBy { it }.filter { it.value.size > 1 }.keys
        assertTrue(
            "Duplicate keys found in METADATA_FIELD_DESCRIPTORS: $duplicates",
            duplicates.isEmpty()
        )
    }

    @Test
    fun `no duplicate keys in TOP_LEVEL_FIELD_DESCRIPTORS`() {
        val keys = AndroidCapabilityExportContract.TOP_LEVEL_FIELD_DESCRIPTORS.map { it.key }
        val duplicates = keys.groupBy { it }.filter { it.value.size > 1 }.keys
        assertTrue(
            "Duplicate keys found in TOP_LEVEL_FIELD_DESCRIPTORS: $duplicates",
            duplicates.isEmpty()
        )
    }

    @Test
    fun `all vocabulary-constrained descriptors have non-empty vocabulary sets`() {
        (AndroidCapabilityExportContract.TOP_LEVEL_FIELD_DESCRIPTORS +
            AndroidCapabilityExportContract.METADATA_FIELD_DESCRIPTORS)
            .forEach { descriptor ->
                val vocab = descriptor.vocabularyValues
                if (vocab != null) {
                    assertTrue(
                        "Vocabulary for '${descriptor.key}' must be non-empty",
                        vocab.isNotEmpty()
                    )
                }
            }
    }
}
