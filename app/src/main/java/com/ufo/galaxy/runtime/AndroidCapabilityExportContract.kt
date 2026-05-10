package com.ufo.galaxy.runtime

import com.ufo.galaxy.capability.AndroidCapabilityVector

/**
 * PR-3 — Canonical Android capability export contract for cross-repository V2 validation.
 *
 * [AndroidCapabilityExportContract] is the single, versioned, machine-verifiable declaration
 * of the complete `capability_report` wire payload that Android emits to V2.  It replaces the
 * previously scattered contract definition (split across [CapabilityReport.REQUIRED_METADATA_KEYS],
 * [CapabilityReport.SCHEDULING_BASIS_METADATA_KEYS], [CapabilityReport.CANONICAL_GATE_METADATA_KEYS],
 * [CapabilityAuthoritySnapshot], and implicit payload construction in GalaxyWebSocketClient) with a
 * single, stable, testable contract object that V2 can use for canonical validation.
 *
 * ## Problem addressed
 *
 * Before PR-3, Android's capability export was loosely coupled across multiple sources:
 *  - Required metadata keys were split across [CapabilityReport.REQUIRED_METADATA_KEYS] (8 keys),
 *    [CapabilityReport.SCHEDULING_BASIS_METADATA_KEYS] (4 keys), and
 *    [CapabilityReport.CANONICAL_GATE_METADATA_KEYS] (16 keys) with no shared version marker.
 *  - The top-level payload structure was implicit in GalaxyWebSocketClient with no single
 *    canonical declaration of what every field is and what it must contain.
 *  - V2's `capability_registry.py` had no machine-readable schema version to compare against.
 *  - Schema drift between Android and V2 could only be detected by manual code review.
 *
 * [AndroidCapabilityExportContract] closes all gaps:
 *  1. [CONTRACT_SCHEMA_VERSION] — a stable, incrementable schema version that V2 reads from the
 *     [CONTRACT_SCHEMA_VERSION_KEY] metadata field to detect Android-side schema changes without
 *     having to diff individual field lists.
 *  2. [TOP_LEVEL_FIELD_DESCRIPTORS] — typed descriptors for every top-level field in the
 *     `capability_report` wire payload, with vocabulary constraints for enum-typed fields.
 *  3. [METADATA_FIELD_DESCRIPTORS] — typed descriptors for every required metadata field,
 *     combining core readiness signals, scheduling basis signals, canonical gate signals,
 *     and the new export contract schema version.
 *  4. [validate] / [validateMetadata] — produce [ValidationResult] objects that catch
 *     missing, malformed, or vocabulary-violating fields before they reach V2.
 *  5. [REQUIRED_TOP_LEVEL_PAYLOAD_KEYS] / [REQUIRED_METADATA_KEYS] — stable, count-stable sets
 *     that tests compare against hard-coded expected values to detect field drift.
 *  6. [EXPECTED_REQUIRED_TOP_LEVEL_FIELD_COUNT] / [EXPECTED_REQUIRED_METADATA_FIELD_COUNT] —
 *     regression-anchor constants; if these counts change without a [CONTRACT_SCHEMA_VERSION]
 *     bump, tests fail immediately.
 *
 * ## V2 consumption model
 *
 * V2's capability ingestion pipeline should:
 *  1. Read `metadata[contract_schema_version]` from the capability_report payload.
 *  2. Compare it against the expected version stored in V2's `capability_registry.py`.
 *  3. If the versions differ, apply compatibility logic or reject the payload.
 *  4. Use [REQUIRED_TOP_LEVEL_PAYLOAD_KEYS] to validate the outer payload envelope.
 *  5. Use [METADATA_FIELD_DESCRIPTORS] to validate each metadata field's type and vocabulary.
 *
 * ## Schema versioning rules
 *
 * **Increment [CONTRACT_SCHEMA_VERSION] when:**
 *  - A required field is added, removed, or renamed in either [TOP_LEVEL_FIELD_DESCRIPTORS]
 *    or [METADATA_FIELD_DESCRIPTORS].
 *  - A field's [FieldType] constraint changes.
 *  - A vocabulary value is added or removed from a [FieldDescriptor.vocabularyValues] set.
 *  - The semantic meaning of an existing field changes in a way that affects V2 consumption.
 *
 * **Do NOT increment for:**
 *  - Adding optional fields V2 can safely ignore when absent.
 *  - Changes to [FieldDescriptor.description] only.
 *
 * **After incrementing:** Update the V2-side expected version in `capability_registry.py` and
 * update [EXPECTED_REQUIRED_METADATA_FIELD_COUNT] / [EXPECTED_REQUIRED_TOP_LEVEL_FIELD_COUNT]
 * if the counts changed.
 *
 * @see CapabilityReport
 * @see AndroidCapabilityVector
 * @see CapabilityAuthoritySnapshot
 */
object AndroidCapabilityExportContract {

    // ── Schema version ────────────────────────────────────────────────────────

    /**
     * Stable schema version for V2 cross-repository drift detection.
     *
     * Emitted in the [CONTRACT_SCHEMA_VERSION_KEY] key of every `capability_report` metadata
     * map so that V2's `capability_registry.py` can detect Android-side schema changes without
     * having to diff individual field lists across releases.
     */
    const val CONTRACT_SCHEMA_VERSION: String = "1"

    /**
     * The wire metadata key under which [CONTRACT_SCHEMA_VERSION] is emitted.
     *
     * V2 reads `metadata[CONTRACT_SCHEMA_VERSION_KEY]` as the primary schema drift signal.
     */
    const val CONTRACT_SCHEMA_VERSION_KEY: String = "contract_schema_version"

    // ── Field type classification ─────────────────────────────────────────────

    /**
     * Canonical type classification for a capability_report field as emitted by Android.
     */
    enum class FieldType {
        /** Boolean true/false value. */
        BOOLEAN,
        /** Non-null String value. */
        STRING,
        /** Non-negative integer value. */
        INT,
        /** Non-empty list of Strings. */
        STRING_LIST,
        /** Possibly-empty list of Strings (empty list is valid). */
        STRING_LIST_NULLABLE,
        /** Arbitrary string-keyed map. */
        STRING_MAP
    }

    /**
     * Descriptor for a single field in the capability_report wire payload.
     *
     * @property key              Wire key name as it appears in the JSON payload.
     * @property type             Expected [FieldType] of the value.
     * @property required         `true` when V2 must treat absence as a contract violation.
     * @property vocabularyValues Non-null for string-enum fields: only values in this set are
     *                            canonical.  A value outside this set is malformed.
     * @property description      Short human-readable description for contract documentation.
     */
    data class FieldDescriptor(
        val key: String,
        val type: FieldType,
        val required: Boolean,
        val vocabularyValues: Set<String>? = null,
        val description: String = ""
    )

    // ── Top-level payload field contract ──────────────────────────────────────

    /**
     * Descriptors for all top-level fields in the `capability_report` wire payload.
     *
     * These fields appear at the JSON root level (not inside `metadata`).
     *
     * | Key                 | Required | Type                | Canonical constraint              |
     * |---------------------|----------|---------------------|-----------------------------------|
     * | `type`              | yes      | STRING              | "capability_report"               |
     * | `version`           | yes      | STRING              | "3.0"                             |
     * | `platform`          | yes      | STRING              | "android"                         |
     * | `device_id`         | yes      | STRING              | non-blank                         |
     * | `device_type`       | yes      | STRING              | "Android_Agent"                   |
     * | `trace_id`          | yes      | STRING              | non-blank                         |
     * | `route_mode`        | yes      | STRING              | "cross_device" / "local_only"     |
     * | `idempotency_key`   | yes      | STRING              | non-blank                         |
     * | `supported_actions` | yes      | STRING_LIST         | non-empty                         |
     * | `capabilities`      | yes      | STRING_LIST_NULLABLE| may be empty                      |
     * | `metadata`          | yes      | STRING_MAP          | must satisfy metadata descriptors |
     */
    val TOP_LEVEL_FIELD_DESCRIPTORS: List<FieldDescriptor> = listOf(
        FieldDescriptor(
            key = "type",
            type = FieldType.STRING,
            required = true,
            vocabularyValues = setOf("capability_report"),
            description = "Wire message type; V2 gateway rejects any value other than 'capability_report'"
        ),
        FieldDescriptor(
            key = "version",
            type = FieldType.STRING,
            required = true,
            vocabularyValues = setOf("3.0"),
            description = "AIP protocol version; must be '3.0' for V2 gateway acceptance"
        ),
        FieldDescriptor(
            key = "platform",
            type = FieldType.STRING,
            required = true,
            vocabularyValues = setOf("android"),
            description = "Device platform; always 'android' for Android participant routing"
        ),
        FieldDescriptor(
            key = "device_id",
            type = FieldType.STRING,
            required = true,
            description = "Unique device identifier for V2 session routing and state store keying"
        ),
        FieldDescriptor(
            key = "device_type",
            type = FieldType.STRING,
            required = true,
            vocabularyValues = setOf("Android_Agent"),
            description = "Device type classifier for V2 node routing"
        ),
        FieldDescriptor(
            key = "trace_id",
            type = FieldType.STRING,
            required = true,
            description = "Per-session distributed trace ID for observability correlation"
        ),
        FieldDescriptor(
            key = "route_mode",
            type = FieldType.STRING,
            required = true,
            vocabularyValues = setOf("cross_device", "local_only"),
            description = "Execution routing mode at the time of the capability report"
        ),
        FieldDescriptor(
            key = "idempotency_key",
            type = FieldType.STRING,
            required = true,
            description = "Idempotent delivery key for duplicate report detection at V2"
        ),
        FieldDescriptor(
            key = "supported_actions",
            type = FieldType.STRING_LIST,
            required = true,
            description = "Non-empty list of low-level action capability strings"
        ),
        FieldDescriptor(
            key = "capabilities",
            type = FieldType.STRING_LIST_NULLABLE,
            required = true,
            description = "High-level autonomous capability names; may be empty"
        ),
        FieldDescriptor(
            key = "metadata",
            type = FieldType.STRING_MAP,
            required = true,
            description = "Full capability metadata map; must satisfy METADATA_FIELD_DESCRIPTORS"
        )
    )

    /**
     * The stable set of required top-level field key names.
     *
     * V2 must treat the absence of any key in this set as a contract violation.
     * Tests compare this set against [EXPECTED_REQUIRED_TOP_LEVEL_FIELD_COUNT] to detect
     * field addition or removal without a [CONTRACT_SCHEMA_VERSION] bump.
     */
    val REQUIRED_TOP_LEVEL_PAYLOAD_KEYS: Set<String> =
        TOP_LEVEL_FIELD_DESCRIPTORS
            .filter { it.required }
            .map { it.key }
            .toSet()

    // ── Metadata field contract ───────────────────────────────────────────────

    /**
     * Descriptors for all required metadata fields in the `capability_report` `metadata` map.
     *
     * This is the authoritative union of:
     *  - [CapabilityReport.REQUIRED_METADATA_KEYS] — eight core readiness signals.
     *  - [CapabilityReport.SCHEDULING_BASIS_METADATA_KEYS] — four scheduling basis signals.
     *  - The sixteen canonical gate signals from [CapabilityReport.CANONICAL_GATE_METADATA_KEYS].
     *  - [CONTRACT_SCHEMA_VERSION_KEY] — the export contract schema version (introduced PR-3).
     *
     * All fields listed here must be present and correctly typed in every `capability_report`
     * metadata map that Android sends to V2.
     */
    val METADATA_FIELD_DESCRIPTORS: List<FieldDescriptor> = listOf(

        // ── Core readiness signals (CapabilityReport.REQUIRED_METADATA_KEYS) ─────────────

        FieldDescriptor(
            key = "goal_execution_enabled",
            type = FieldType.BOOLEAN,
            required = true,
            description = "Device can execute autonomous goals end-to-end"
        ),
        FieldDescriptor(
            key = "local_model_enabled",
            type = FieldType.BOOLEAN,
            required = true,
            description = "On-device inference models are loaded and ready"
        ),
        FieldDescriptor(
            key = "cross_device_enabled",
            type = FieldType.BOOLEAN,
            required = true,
            description = "Cross-device collaboration is enabled in settings"
        ),
        FieldDescriptor(
            key = "parallel_execution_enabled",
            type = FieldType.BOOLEAN,
            required = true,
            description = "Parallel subtask execution is active"
        ),
        FieldDescriptor(
            key = "device_role",
            type = FieldType.STRING,
            required = true,
            vocabularyValues = setOf("phone", "tablet", "hub"),
            description = "Logical cluster role for V2 routing"
        ),
        FieldDescriptor(
            key = "model_ready",
            type = FieldType.BOOLEAN,
            required = true,
            description = "Model files are present and verified on disk"
        ),
        FieldDescriptor(
            key = "accessibility_ready",
            type = FieldType.BOOLEAN,
            required = true,
            description = "Accessibility service is enabled"
        ),
        FieldDescriptor(
            key = "overlay_ready",
            type = FieldType.BOOLEAN,
            required = true,
            description = "Overlay (SYSTEM_ALERT_WINDOW) permission is granted"
        ),

        // ── Scheduling basis signals (CapabilityReport.SCHEDULING_BASIS_METADATA_KEYS) ───

        FieldDescriptor(
            key = AndroidCapabilityVector.KEY_LOCAL_ELIGIBLE,
            type = FieldType.BOOLEAN,
            required = true,
            description = "Device is eligible for local autonomous execution"
        ),
        FieldDescriptor(
            key = AndroidCapabilityVector.KEY_CROSS_DEVICE_ELIGIBLE,
            type = FieldType.BOOLEAN,
            required = true,
            description = "Device is eligible for cross-device task participation"
        ),
        FieldDescriptor(
            key = AndroidCapabilityVector.KEY_PARALLEL_SUBTASK_ELIGIBLE,
            type = FieldType.BOOLEAN,
            required = true,
            description = "Device can accept parallel subtask assignments"
        ),
        FieldDescriptor(
            key = AndroidCapabilityVector.KEY_EXECUTION_DIMENSIONS,
            type = FieldType.STRING,
            required = true,
            description = "Comma-separated active ExecutionDimension wire values (empty string when none)"
        ),

        // ── Canonical gate signals (CapabilityReport.CANONICAL_GATE_METADATA_KEYS) ───────

        FieldDescriptor(
            key = "degraded_mode",
            type = FieldType.BOOLEAN,
            required = true,
            description = "Device is operating in a degraded capability state"
        ),
        FieldDescriptor(
            key = "mode_state",
            type = FieldType.STRING,
            required = true,
            vocabularyValues = setOf("local_only", "cross_device"),
            description = "Execution mode state wire label"
        ),
        FieldDescriptor(
            key = "mode_readiness_state",
            type = FieldType.STRING,
            required = true,
            vocabularyValues = setOf("ready", "degraded"),
            description = "Mode readiness classification"
        ),
        FieldDescriptor(
            key = "cross_device_eligibility",
            type = FieldType.BOOLEAN,
            required = true,
            description = "Whether cross-device tasks may be dispatched in the current mode"
        ),
        FieldDescriptor(
            key = "goal_execution_eligibility",
            type = FieldType.BOOLEAN,
            required = true,
            description = "Whether goal execution may be dispatched in the current mode"
        ),
        FieldDescriptor(
            key = "parallel_execution_eligibility",
            type = FieldType.BOOLEAN,
            required = true,
            description = "Whether parallel execution may be dispatched in the current mode"
        ),
        FieldDescriptor(
            key = "local_intelligence_status",
            type = FieldType.STRING,
            required = true,
            vocabularyValues = LocalIntelligenceCapabilityStatus.entries.map { it.wireValue }.toSet(),
            description = "Local intelligence capability status wire value"
        ),
        FieldDescriptor(
            key = "local_inference_ready",
            type = FieldType.BOOLEAN,
            required = true,
            description = "Whether the local inference subsystem is ready to accept tasks"
        ),
        FieldDescriptor(
            key = "local_inference_available",
            type = FieldType.BOOLEAN,
            required = true,
            description = "Whether local inference is available (including degraded state)"
        ),
        FieldDescriptor(
            key = LocalExecutionModeGate.KEY_EXECUTION_MODE_STATE,
            type = FieldType.STRING,
            required = true,
            vocabularyValues = LocalExecutionModeGate.ExecutionModeState.entries.map { it.wireValue }.toSet(),
            description = "Canonical execution mode state from LocalExecutionModeGate"
        ),
        FieldDescriptor(
            key = LocalExecutionModeGate.KEY_ACCEPTS_CROSS_DEVICE_TASKS,
            type = FieldType.BOOLEAN,
            required = true,
            description = "Whether Android is accepting cross-device delegated tasks in the current mode"
        ),
        FieldDescriptor(
            key = LocalExecutionModeGate.KEY_V2_GOVERNANCE_ACTIVE,
            type = FieldType.BOOLEAN,
            required = true,
            description = "Whether V2 is the active governing authority in the current mode"
        ),
        FieldDescriptor(
            key = LocalExecutionModeGate.KEY_IS_HOLD_STATE,
            type = FieldType.BOOLEAN,
            required = true,
            description = "Whether Android is in a hold state (task dispatch must be deferred)"
        ),
        FieldDescriptor(
            key = LocalExecutionModeGate.KEY_DEGRADATION_REASONS,
            type = FieldType.STRING,
            required = true,
            description = "Comma-separated degradation reason codes (empty string when none)"
        ),
        FieldDescriptor(
            key = LocalExecutionModeGate.KEY_SEMANTIC_TAG,
            type = FieldType.STRING,
            required = true,
            description = "Canonical semantic tag for the current execution mode state"
        ),
        FieldDescriptor(
            key = LocalExecutionModeGate.KEY_SCHEMA_VERSION,
            type = FieldType.STRING,
            required = true,
            vocabularyValues = setOf(LocalExecutionModeGate.SCHEMA_VERSION),
            description = "LocalExecutionModeGate wire-map schema version for gate-level drift detection"
        ),

        // ── Export contract schema version (introduced PR-3) ──────────────────────────────

        FieldDescriptor(
            key = CONTRACT_SCHEMA_VERSION_KEY,
            type = FieldType.STRING,
            required = true,
            vocabularyValues = setOf(CONTRACT_SCHEMA_VERSION),
            description = "AndroidCapabilityExportContract schema version; V2 reads this for cross-repo drift detection"
        )
    )

    /**
     * The stable set of required metadata field key names.
     *
     * V2 must treat the absence of any key in this set as a contract violation.
     * Tests compare the size of this set against [EXPECTED_REQUIRED_METADATA_FIELD_COUNT]
     * to detect field addition or removal without a [CONTRACT_SCHEMA_VERSION] bump.
     */
    val REQUIRED_METADATA_KEYS: Set<String> =
        METADATA_FIELD_DESCRIPTORS
            .filter { it.required }
            .map { it.key }
            .toSet()

    // ── Regression-anchor constants ───────────────────────────────────────────

    /**
     * Expected count of required top-level payload fields.
     *
     * If [REQUIRED_TOP_LEVEL_PAYLOAD_KEYS].size deviates from this value, a test failure
     * forces a deliberate [CONTRACT_SCHEMA_VERSION] bump and documentation update before
     * the change can land.
     */
    const val EXPECTED_REQUIRED_TOP_LEVEL_FIELD_COUNT: Int = 11

    /**
     * Expected count of required metadata fields.
     *
     * If [REQUIRED_METADATA_KEYS].size deviates from this value, a test failure forces a
     * deliberate [CONTRACT_SCHEMA_VERSION] bump and documentation update before the change
     * can land.
     */
    const val EXPECTED_REQUIRED_METADATA_FIELD_COUNT: Int = 29

    // ── Validation ────────────────────────────────────────────────────────────

    /**
     * Outcome of validating a capability_report payload or metadata map against this contract.
     *
     * @property valid   `true` when all contract checks pass.
     * @property issues  Human-readable list of all violations; empty when [valid].
     */
    data class ValidationResult(
        val valid: Boolean,
        val issues: List<String>
    )

    /**
     * Validates a complete `capability_report` payload map against this contract.
     *
     * Checks:
     *  - All [REQUIRED_TOP_LEVEL_PAYLOAD_KEYS] are present.
     *  - Fields with [FieldDescriptor.vocabularyValues] carry a value in the allowed set.
     *  - The `metadata` value, when present and a Map, satisfies [validateMetadata].
     *
     * @param topLevel Map of the top-level JSON fields (e.g., parsed from the wire JSON).
     * @return [ValidationResult] with all issues found; [ValidationResult.valid] is `false`
     *         on any violation.
     */
    fun validate(topLevel: Map<String, Any?>): ValidationResult {
        val issues = mutableListOf<String>()

        for (descriptor in TOP_LEVEL_FIELD_DESCRIPTORS) {
            if (!descriptor.required) continue
            if (!topLevel.containsKey(descriptor.key)) {
                issues += "missing required top-level field '${descriptor.key}'"
                continue
            }
            val value = topLevel[descriptor.key]
            val typeIssue = checkType("top-level", descriptor, value)
            if (typeIssue != null) {
                issues += typeIssue
                continue
            }
            vocabularyIssue("top-level", descriptor, value)?.let { issues += it }
        }

        @Suppress("UNCHECKED_CAST")
        val metadata = topLevel["metadata"] as? Map<String, Any?>
        if (metadata != null) {
            issues += validateMetadata(metadata).issues
        }

        return ValidationResult(valid = issues.isEmpty(), issues = issues)
    }

    /**
     * Validates the `metadata` sub-map of a `capability_report` payload.
     *
     * Checks:
     *  - All required metadata fields from [METADATA_FIELD_DESCRIPTORS] are present.
     *  - Boolean fields carry a Boolean (not a String or other type).
     *  - String fields carry a String.
     *  - Vocabulary-constrained fields carry a value in the allowed vocabulary.
     *
     * @param metadata The metadata map from the capability_report payload.
     * @return [ValidationResult] with all metadata issues found.
     */
    fun validateMetadata(metadata: Map<String, Any?>): ValidationResult {
        val issues = mutableListOf<String>()

        for (descriptor in METADATA_FIELD_DESCRIPTORS) {
            if (!descriptor.required) continue
            if (!metadata.containsKey(descriptor.key)) {
                issues += "missing required metadata field '${descriptor.key}'"
                continue
            }
            val value = metadata[descriptor.key]
            val typeIssue = checkType("metadata", descriptor, value)
            if (typeIssue != null) {
                issues += typeIssue
                continue
            }
            vocabularyIssue("metadata", descriptor, value)?.let { issues += it }
        }

        return ValidationResult(valid = issues.isEmpty(), issues = issues)
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun checkType(context: String, descriptor: FieldDescriptor, value: Any?): String? =
        when (descriptor.type) {
            FieldType.BOOLEAN ->
                if (value !is Boolean)
                    "$context field '${descriptor.key}' must be Boolean, got ${typeName(value)}"
                else null
            FieldType.STRING ->
                if (value !is String)
                    "$context field '${descriptor.key}' must be String, got ${typeName(value)}"
                else null
            FieldType.INT ->
                if (value !is Int && value !is Long)
                    "$context field '${descriptor.key}' must be Int/Long, got ${typeName(value)}"
                else null
            FieldType.STRING_LIST, FieldType.STRING_LIST_NULLABLE ->
                if (value !is List<*>)
                    "$context field '${descriptor.key}' must be List, got ${typeName(value)}"
                else null
            FieldType.STRING_MAP ->
                if (value !is Map<*, *>)
                    "$context field '${descriptor.key}' must be Map, got ${typeName(value)}"
                else null
        }

    private fun vocabularyIssue(context: String, descriptor: FieldDescriptor, value: Any?): String? {
        val vocab = descriptor.vocabularyValues ?: return null
        val strVal = value as? String ?: return null
        return if (strVal !in vocab) {
            "$context field '${descriptor.key}' has disallowed value '$strVal'; " +
                "allowed: ${vocab.sorted()}"
        } else null
    }

    private fun typeName(value: Any?): String = value?.javaClass?.simpleName ?: "null"
}
