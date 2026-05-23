package com.ufo.galaxy.runtime

import com.ufo.galaxy.protocol.MsgType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PR-120 — AndroidNonClosureSignalBoundaryContract unit tests.
 *
 * Provides strong negative-proof coverage demonstrating that:
 *
 *  1. [AndroidNonClosureSignalBoundaryContract.NonClosureSignalClass] has exactly 5 values
 *     with stable wire values.
 *  2. ALL [NonClosureSignalClass] values have [isClosureEligible] = `false` (defining invariant).
 *  3. ALL [NonClosureSignalClass] values have [isCanonicalResultCapable] = `false`.
 *  4. [classify] maps each non-closure [MsgType] to the correct [NonClosureSignalClass].
 *  5. [classify] returns `null` for closure-bearing types (e.g. [MsgType.GOAL_EXECUTION_RESULT]).
 *  6. [isNonClosureType] correctly gates non-closure types and passes closure-bearing types.
 *  7. [toWireMap] produces the correct wire keys and values.
 *  8. [NON_CLOSURE_MSG_TYPES] contains all and only the expected non-closure types.
 *  9. [V2_NON_CLOSURE_ALIGNMENT_MAP] has an entry for every [NonClosureSignalClass] value.
 * 10. [NON_CLOSURE_INVARIANTS] has exactly 10 entries with no duplicates.
 * 11. [fromWireValue] returns the correct class or falls back to DIAGNOSTICS_ONLY for unknown.
 * 12. Negative proof: diagnostics-only payloads cannot be closure-eligible under any path.
 * 13. Negative proof: advisory-recovery-only payloads cannot be closure-eligible.
 * 14. Negative proof: readiness-only payloads cannot be closure-eligible.
 * 15. Negative proof: evaluator-advisory-only payloads cannot be closure-eligible.
 * 16. Negative proof: continuity-diagnostic-only payloads cannot be closure-eligible.
 * 17. Negative proof: unknown wire values fall back to DIAGNOSTICS_ONLY (most restrictive default).
 * 18. [StabilizationBaseline] registers the new surface with correct counts.
 */
class Pr120AndroidNonClosureSignalBoundaryContractTest {

    // ══════════════════════════════════════════════════════════════════════════
    // 1. NonClosureSignalClass enum coverage
    // ══════════════════════════════════════════════════════════════════════════

    @Test fun `NonClosureSignalClass has exactly 5 values`() {
        assertEquals(5, AndroidNonClosureSignalBoundaryContract.NonClosureSignalClass.values().size)
    }

    @Test fun `DIAGNOSTICS_ONLY wireValue is diagnostics_only`() {
        assertEquals(
            "diagnostics_only",
            AndroidNonClosureSignalBoundaryContract.NonClosureSignalClass.DIAGNOSTICS_ONLY.wireValue
        )
    }

    @Test fun `ADVISORY_RECOVERY_ONLY wireValue is advisory_recovery_only`() {
        assertEquals(
            "advisory_recovery_only",
            AndroidNonClosureSignalBoundaryContract.NonClosureSignalClass.ADVISORY_RECOVERY_ONLY.wireValue
        )
    }

    @Test fun `READINESS_ONLY wireValue is readiness_only`() {
        assertEquals(
            "readiness_only",
            AndroidNonClosureSignalBoundaryContract.NonClosureSignalClass.READINESS_ONLY.wireValue
        )
    }

    @Test fun `EVALUATOR_ADVISORY_ONLY wireValue is evaluator_advisory_only`() {
        assertEquals(
            "evaluator_advisory_only",
            AndroidNonClosureSignalBoundaryContract.NonClosureSignalClass.EVALUATOR_ADVISORY_ONLY.wireValue
        )
    }

    @Test fun `CONTINUITY_DIAGNOSTIC_ONLY wireValue is continuity_diagnostic_only`() {
        assertEquals(
            "continuity_diagnostic_only",
            AndroidNonClosureSignalBoundaryContract.NonClosureSignalClass.CONTINUITY_DIAGNOSTIC_ONLY.wireValue
        )
    }

    @Test fun `all NonClosureSignalClass values have non-blank descriptions`() {
        AndroidNonClosureSignalBoundaryContract.NonClosureSignalClass.values().forEach { cls ->
            assertTrue(
                "description must be non-blank for $cls",
                cls.description.isNotBlank()
            )
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 2 & 3. Core non-closure invariants: isClosureEligible and isCanonicalResultCapable
    // ══════════════════════════════════════════════════════════════════════════

    @Test fun `all NonClosureSignalClass values have isClosureEligible = false (INV-NCL-01)`() {
        AndroidNonClosureSignalBoundaryContract.NonClosureSignalClass.values().forEach { cls ->
            assertFalse(
                "INV-NCL-01 violated: $cls must have isClosureEligible=false",
                cls.isClosureEligible
            )
        }
    }

    @Test fun `all NonClosureSignalClass values have isCanonicalResultCapable = false (INV-NCL-02)`() {
        AndroidNonClosureSignalBoundaryContract.NonClosureSignalClass.values().forEach { cls ->
            assertFalse(
                "INV-NCL-02 violated: $cls must have isCanonicalResultCapable=false",
                cls.isCanonicalResultCapable
            )
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 4. classify(): correct mapping for each non-closure MsgType
    // ══════════════════════════════════════════════════════════════════════════

    @Test fun `classify DIAGNOSTICS_PAYLOAD returns DIAGNOSTICS_ONLY`() {
        assertEquals(
            AndroidNonClosureSignalBoundaryContract.NonClosureSignalClass.DIAGNOSTICS_ONLY,
            AndroidNonClosureSignalBoundaryContract.classify(MsgType.DIAGNOSTICS_PAYLOAD)
        )
    }

    @Test fun `classify DEVICE_STATE_SNAPSHOT returns DIAGNOSTICS_ONLY`() {
        assertEquals(
            AndroidNonClosureSignalBoundaryContract.NonClosureSignalClass.DIAGNOSTICS_ONLY,
            AndroidNonClosureSignalBoundaryContract.classify(MsgType.DEVICE_STATE_SNAPSHOT)
        )
    }

    @Test fun `classify DEVICE_READINESS_REPORT returns READINESS_ONLY`() {
        assertEquals(
            AndroidNonClosureSignalBoundaryContract.NonClosureSignalClass.READINESS_ONLY,
            AndroidNonClosureSignalBoundaryContract.classify(MsgType.DEVICE_READINESS_REPORT)
        )
    }

    @Test fun `classify DEVICE_GOVERNANCE_REPORT returns EVALUATOR_ADVISORY_ONLY`() {
        assertEquals(
            AndroidNonClosureSignalBoundaryContract.NonClosureSignalClass.EVALUATOR_ADVISORY_ONLY,
            AndroidNonClosureSignalBoundaryContract.classify(MsgType.DEVICE_GOVERNANCE_REPORT)
        )
    }

    @Test fun `classify DEVICE_ACCEPTANCE_REPORT returns EVALUATOR_ADVISORY_ONLY`() {
        assertEquals(
            AndroidNonClosureSignalBoundaryContract.NonClosureSignalClass.EVALUATOR_ADVISORY_ONLY,
            AndroidNonClosureSignalBoundaryContract.classify(MsgType.DEVICE_ACCEPTANCE_REPORT)
        )
    }

    @Test fun `classify DEVICE_STRATEGY_REPORT returns EVALUATOR_ADVISORY_ONLY`() {
        assertEquals(
            AndroidNonClosureSignalBoundaryContract.NonClosureSignalClass.EVALUATOR_ADVISORY_ONLY,
            AndroidNonClosureSignalBoundaryContract.classify(MsgType.DEVICE_STRATEGY_REPORT)
        )
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 5. Negative proof: closure-bearing types return null from classify()
    // ══════════════════════════════════════════════════════════════════════════

    @Test fun `classify GOAL_EXECUTION_RESULT returns null (closure-bearing type)`() {
        assertNull(
            "GOAL_EXECUTION_RESULT is closure-bearing and MUST NOT be classified as non-closure",
            AndroidNonClosureSignalBoundaryContract.classify(MsgType.GOAL_EXECUTION_RESULT)
        )
    }

    @Test fun `classify GOAL_RESULT returns null (closure-bearing type)`() {
        assertNull(
            "GOAL_RESULT is closure-bearing and MUST NOT be classified as non-closure",
            AndroidNonClosureSignalBoundaryContract.classify(MsgType.GOAL_RESULT)
        )
    }

    @Test fun `classify TASK_RESULT returns null (closure-bearing type)`() {
        assertNull(
            "TASK_RESULT is closure-bearing and MUST NOT be classified as non-closure",
            AndroidNonClosureSignalBoundaryContract.classify(MsgType.TASK_RESULT)
        )
    }

    @Test fun `classify RECONCILIATION_SIGNAL returns null (canonical truth signal)`() {
        assertNull(
            "RECONCILIATION_SIGNAL carries canonical truth and MUST NOT be classified as non-closure",
            AndroidNonClosureSignalBoundaryContract.classify(MsgType.RECONCILIATION_SIGNAL)
        )
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 6. isNonClosureType() correctly gates non-closure and passes closure-bearing types
    // ══════════════════════════════════════════════════════════════════════════

    @Test fun `isNonClosureType returns true for DIAGNOSTICS_PAYLOAD`() {
        assertTrue(AndroidNonClosureSignalBoundaryContract.isNonClosureType(MsgType.DIAGNOSTICS_PAYLOAD))
    }

    @Test fun `isNonClosureType returns true for DEVICE_STATE_SNAPSHOT`() {
        assertTrue(AndroidNonClosureSignalBoundaryContract.isNonClosureType(MsgType.DEVICE_STATE_SNAPSHOT))
    }

    @Test fun `isNonClosureType returns true for DEVICE_READINESS_REPORT`() {
        assertTrue(AndroidNonClosureSignalBoundaryContract.isNonClosureType(MsgType.DEVICE_READINESS_REPORT))
    }

    @Test fun `isNonClosureType returns true for DEVICE_GOVERNANCE_REPORT`() {
        assertTrue(AndroidNonClosureSignalBoundaryContract.isNonClosureType(MsgType.DEVICE_GOVERNANCE_REPORT))
    }

    @Test fun `isNonClosureType returns true for DEVICE_ACCEPTANCE_REPORT`() {
        assertTrue(AndroidNonClosureSignalBoundaryContract.isNonClosureType(MsgType.DEVICE_ACCEPTANCE_REPORT))
    }

    @Test fun `isNonClosureType returns true for DEVICE_STRATEGY_REPORT`() {
        assertTrue(AndroidNonClosureSignalBoundaryContract.isNonClosureType(MsgType.DEVICE_STRATEGY_REPORT))
    }

    @Test fun `isNonClosureType returns false for GOAL_EXECUTION_RESULT`() {
        assertFalse(
            "GOAL_EXECUTION_RESULT is closure-bearing and isNonClosureType MUST return false",
            AndroidNonClosureSignalBoundaryContract.isNonClosureType(MsgType.GOAL_EXECUTION_RESULT)
        )
    }

    @Test fun `isNonClosureType returns false for GOAL_RESULT`() {
        assertFalse(AndroidNonClosureSignalBoundaryContract.isNonClosureType(MsgType.GOAL_RESULT))
    }

    @Test fun `isNonClosureType returns false for TASK_RESULT`() {
        assertFalse(AndroidNonClosureSignalBoundaryContract.isNonClosureType(MsgType.TASK_RESULT))
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 7. toWireMap() produces correct wire keys and values
    // ══════════════════════════════════════════════════════════════════════════

    @Test fun `toWireMap contains non_closure_signal_class key`() {
        val wireMap = AndroidNonClosureSignalBoundaryContract.toWireMap(
            AndroidNonClosureSignalBoundaryContract.NonClosureSignalClass.DIAGNOSTICS_ONLY
        )
        assertTrue(
            wireMap.containsKey(AndroidNonClosureSignalBoundaryContract.KEY_NON_CLOSURE_SIGNAL_CLASS)
        )
    }

    @Test fun `toWireMap contains non_closure_schema_version key`() {
        val wireMap = AndroidNonClosureSignalBoundaryContract.toWireMap(
            AndroidNonClosureSignalBoundaryContract.NonClosureSignalClass.DIAGNOSTICS_ONLY
        )
        assertTrue(
            wireMap.containsKey(AndroidNonClosureSignalBoundaryContract.KEY_NON_CLOSURE_SCHEMA_VERSION)
        )
    }

    @Test fun `toWireMap non_closure_signal_class value matches wireValue for DIAGNOSTICS_ONLY`() {
        val wireMap = AndroidNonClosureSignalBoundaryContract.toWireMap(
            AndroidNonClosureSignalBoundaryContract.NonClosureSignalClass.DIAGNOSTICS_ONLY
        )
        assertEquals(
            "diagnostics_only",
            wireMap[AndroidNonClosureSignalBoundaryContract.KEY_NON_CLOSURE_SIGNAL_CLASS]
        )
    }

    @Test fun `toWireMap non_closure_signal_class value matches wireValue for READINESS_ONLY`() {
        val wireMap = AndroidNonClosureSignalBoundaryContract.toWireMap(
            AndroidNonClosureSignalBoundaryContract.NonClosureSignalClass.READINESS_ONLY
        )
        assertEquals(
            "readiness_only",
            wireMap[AndroidNonClosureSignalBoundaryContract.KEY_NON_CLOSURE_SIGNAL_CLASS]
        )
    }

    @Test fun `toWireMap schema_version value is SCHEMA_VERSION`() {
        val wireMap = AndroidNonClosureSignalBoundaryContract.toWireMap(
            AndroidNonClosureSignalBoundaryContract.NonClosureSignalClass.EVALUATOR_ADVISORY_ONLY
        )
        assertEquals(
            AndroidNonClosureSignalBoundaryContract.SCHEMA_VERSION,
            wireMap[AndroidNonClosureSignalBoundaryContract.KEY_NON_CLOSURE_SCHEMA_VERSION]
        )
    }

    @Test fun `toWireMap produces non-null values for all NonClosureSignalClass values`() {
        AndroidNonClosureSignalBoundaryContract.NonClosureSignalClass.values().forEach { cls ->
            val wireMap = AndroidNonClosureSignalBoundaryContract.toWireMap(cls)
            assertNotNull(wireMap[AndroidNonClosureSignalBoundaryContract.KEY_NON_CLOSURE_SIGNAL_CLASS])
            assertNotNull(wireMap[AndroidNonClosureSignalBoundaryContract.KEY_NON_CLOSURE_SCHEMA_VERSION])
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 8. NON_CLOSURE_MSG_TYPES contains all and only expected types
    // ══════════════════════════════════════════════════════════════════════════

    @Test fun `NON_CLOSURE_MSG_TYPES has exactly 6 entries`() {
        assertEquals(6, AndroidNonClosureSignalBoundaryContract.NON_CLOSURE_MSG_TYPES.size)
    }

    @Test fun `NON_CLOSURE_MSG_TYPES contains DIAGNOSTICS_PAYLOAD`() {
        assertTrue(MsgType.DIAGNOSTICS_PAYLOAD in AndroidNonClosureSignalBoundaryContract.NON_CLOSURE_MSG_TYPES)
    }

    @Test fun `NON_CLOSURE_MSG_TYPES contains DEVICE_STATE_SNAPSHOT`() {
        assertTrue(MsgType.DEVICE_STATE_SNAPSHOT in AndroidNonClosureSignalBoundaryContract.NON_CLOSURE_MSG_TYPES)
    }

    @Test fun `NON_CLOSURE_MSG_TYPES contains DEVICE_READINESS_REPORT`() {
        assertTrue(MsgType.DEVICE_READINESS_REPORT in AndroidNonClosureSignalBoundaryContract.NON_CLOSURE_MSG_TYPES)
    }

    @Test fun `NON_CLOSURE_MSG_TYPES contains DEVICE_GOVERNANCE_REPORT`() {
        assertTrue(MsgType.DEVICE_GOVERNANCE_REPORT in AndroidNonClosureSignalBoundaryContract.NON_CLOSURE_MSG_TYPES)
    }

    @Test fun `NON_CLOSURE_MSG_TYPES contains DEVICE_ACCEPTANCE_REPORT`() {
        assertTrue(MsgType.DEVICE_ACCEPTANCE_REPORT in AndroidNonClosureSignalBoundaryContract.NON_CLOSURE_MSG_TYPES)
    }

    @Test fun `NON_CLOSURE_MSG_TYPES contains DEVICE_STRATEGY_REPORT`() {
        assertTrue(MsgType.DEVICE_STRATEGY_REPORT in AndroidNonClosureSignalBoundaryContract.NON_CLOSURE_MSG_TYPES)
    }

    @Test fun `NON_CLOSURE_MSG_TYPES does NOT contain GOAL_EXECUTION_RESULT`() {
        assertFalse(
            "GOAL_EXECUTION_RESULT is closure-bearing and MUST NOT be in NON_CLOSURE_MSG_TYPES",
            MsgType.GOAL_EXECUTION_RESULT in AndroidNonClosureSignalBoundaryContract.NON_CLOSURE_MSG_TYPES
        )
    }

    @Test fun `NON_CLOSURE_MSG_TYPES does NOT contain RECONCILIATION_SIGNAL`() {
        assertFalse(
            "RECONCILIATION_SIGNAL carries canonical truth and MUST NOT be in NON_CLOSURE_MSG_TYPES",
            MsgType.RECONCILIATION_SIGNAL in AndroidNonClosureSignalBoundaryContract.NON_CLOSURE_MSG_TYPES
        )
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 9. V2_NON_CLOSURE_ALIGNMENT_MAP completeness
    // ══════════════════════════════════════════════════════════════════════════

    @Test fun `V2_NON_CLOSURE_ALIGNMENT_MAP has an entry for every NonClosureSignalClass`() {
        AndroidNonClosureSignalBoundaryContract.NonClosureSignalClass.values().forEach { cls ->
            assertTrue(
                "V2_NON_CLOSURE_ALIGNMENT_MAP must have entry for $cls",
                AndroidNonClosureSignalBoundaryContract.V2_NON_CLOSURE_ALIGNMENT_MAP.containsKey(cls)
            )
        }
    }

    @Test fun `V2_NON_CLOSURE_ALIGNMENT_MAP paths are non-blank`() {
        AndroidNonClosureSignalBoundaryContract.V2_NON_CLOSURE_ALIGNMENT_MAP.forEach { (cls, path) ->
            assertTrue("V2 alignment path must be non-blank for $cls", path.isNotBlank())
        }
    }

    @Test fun `V2_NON_CLOSURE_ALIGNMENT_MAP DIAGNOSTICS_ONLY does not mention closure path`() {
        val path = AndroidNonClosureSignalBoundaryContract.V2_NON_CLOSURE_ALIGNMENT_MAP[
            AndroidNonClosureSignalBoundaryContract.NonClosureSignalClass.DIAGNOSTICS_ONLY
        ] ?: ""
        assertFalse(
            "DIAGNOSTICS_ONLY V2 path must not mention task_result_canonical_truth_chain",
            path.contains("task_result_canonical_truth_chain")
        )
    }

    @Test fun `V2_NON_CLOSURE_ALIGNMENT_MAP all paths explicitly deny closure`() {
        AndroidNonClosureSignalBoundaryContract.V2_NON_CLOSURE_ALIGNMENT_MAP.forEach { (cls, path) ->
            assertTrue(
                "V2 path for $cls must contain 'no' to indicate non-closure handling",
                path.contains("no", ignoreCase = true) || path.contains("only", ignoreCase = true)
            )
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 10. NON_CLOSURE_INVARIANTS completeness
    // ══════════════════════════════════════════════════════════════════════════

    @Test fun `NON_CLOSURE_INVARIANTS has exactly 10 entries`() {
        assertEquals(10, AndroidNonClosureSignalBoundaryContract.NON_CLOSURE_INVARIANTS.size)
    }

    @Test fun `all NON_CLOSURE_INVARIANTS are non-blank`() {
        AndroidNonClosureSignalBoundaryContract.NON_CLOSURE_INVARIANTS.forEach { inv ->
            assertTrue("Invariant must be non-blank", inv.isNotBlank())
        }
    }

    @Test fun `NON_CLOSURE_INVARIANTS has no duplicate entries`() {
        val set = AndroidNonClosureSignalBoundaryContract.NON_CLOSURE_INVARIANTS.toSet()
        assertEquals(
            "NON_CLOSURE_INVARIANTS must not contain duplicates",
            AndroidNonClosureSignalBoundaryContract.NON_CLOSURE_INVARIANTS.size,
            set.size
        )
    }

    @Test fun `NON_CLOSURE_INVARIANTS contains INV-NCL-01 (isClosureEligible=false)`() {
        assertTrue(
            AndroidNonClosureSignalBoundaryContract.NON_CLOSURE_INVARIANTS.any { it.contains("INV-NCL-01") }
        )
    }

    @Test fun `NON_CLOSURE_INVARIANTS contains INV-NCL-03 (V2 routing gate)`() {
        assertTrue(
            AndroidNonClosureSignalBoundaryContract.NON_CLOSURE_INVARIANTS.any { it.contains("INV-NCL-03") }
        )
    }

    @Test fun `NON_CLOSURE_INVARIANTS contains INV-NCL-10 (fallback scenario non-closure preservation)`() {
        assertTrue(
            AndroidNonClosureSignalBoundaryContract.NON_CLOSURE_INVARIANTS.any { it.contains("INV-NCL-10") }
        )
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 11. fromWireValue(): correct lookup and defensive fallback
    // ══════════════════════════════════════════════════════════════════════════

    @Test fun `fromWireValue returns DIAGNOSTICS_ONLY for diagnostics_only`() {
        assertEquals(
            AndroidNonClosureSignalBoundaryContract.NonClosureSignalClass.DIAGNOSTICS_ONLY,
            AndroidNonClosureSignalBoundaryContract.NonClosureSignalClass.fromWireValue("diagnostics_only")
        )
    }

    @Test fun `fromWireValue returns READINESS_ONLY for readiness_only`() {
        assertEquals(
            AndroidNonClosureSignalBoundaryContract.NonClosureSignalClass.READINESS_ONLY,
            AndroidNonClosureSignalBoundaryContract.NonClosureSignalClass.fromWireValue("readiness_only")
        )
    }

    @Test fun `fromWireValue returns EVALUATOR_ADVISORY_ONLY for evaluator_advisory_only`() {
        assertEquals(
            AndroidNonClosureSignalBoundaryContract.NonClosureSignalClass.EVALUATOR_ADVISORY_ONLY,
            AndroidNonClosureSignalBoundaryContract.NonClosureSignalClass.fromWireValue("evaluator_advisory_only")
        )
    }

    @Test fun `fromWireValue returns ADVISORY_RECOVERY_ONLY for advisory_recovery_only`() {
        assertEquals(
            AndroidNonClosureSignalBoundaryContract.NonClosureSignalClass.ADVISORY_RECOVERY_ONLY,
            AndroidNonClosureSignalBoundaryContract.NonClosureSignalClass.fromWireValue("advisory_recovery_only")
        )
    }

    @Test fun `fromWireValue returns CONTINUITY_DIAGNOSTIC_ONLY for continuity_diagnostic_only`() {
        assertEquals(
            AndroidNonClosureSignalBoundaryContract.NonClosureSignalClass.CONTINUITY_DIAGNOSTIC_ONLY,
            AndroidNonClosureSignalBoundaryContract.NonClosureSignalClass.fromWireValue("continuity_diagnostic_only")
        )
    }

    @Test fun `fromWireValue unknown value falls back to DIAGNOSTICS_ONLY (INV-NCL-17)`() {
        // Unknown/future wire values MUST fall back to DIAGNOSTICS_ONLY (most restrictive default),
        // not to a closure-eligible class. This prevents future wires from accidentally being
        // treated as closure-relevant under unknown-value handling.
        val fallback = AndroidNonClosureSignalBoundaryContract.NonClosureSignalClass.fromWireValue(
            "future_unknown_class_xyz"
        )
        assertEquals(
            "Unknown wire value must fall back to DIAGNOSTICS_ONLY, not a more permissive class",
            AndroidNonClosureSignalBoundaryContract.NonClosureSignalClass.DIAGNOSTICS_ONLY,
            fallback
        )
        assertFalse(
            "Fallback from unknown wire value must still have isClosureEligible=false",
            fallback.isClosureEligible
        )
    }

    @Test fun `fromWireValue closure-bearing wire value (authority_result) falls back to DIAGNOSTICS_ONLY`() {
        // "authority_result" is a wire value from AndroidResultUplinkBoundaryContract,
        // not a NonClosureSignalClass value. fromWireValue MUST NOT map it to anything
        // closure-eligible — it falls back to DIAGNOSTICS_ONLY.
        val fallback = AndroidNonClosureSignalBoundaryContract.NonClosureSignalClass.fromWireValue(
            "authority_result"
        )
        assertFalse(
            "fromWireValue for 'authority_result' must still return a non-closure class",
            fallback.isClosureEligible
        )
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 12–16. Negative proof: each NonClosureSignalClass cannot be closure-eligible
    // ══════════════════════════════════════════════════════════════════════════

    @Test fun `DIAGNOSTICS_ONLY cannot close tasks — isClosureEligible is always false`() {
        val cls = AndroidNonClosureSignalBoundaryContract.NonClosureSignalClass.DIAGNOSTICS_ONLY
        assertFalse(
            "Negative proof: DIAGNOSTICS_ONLY payloads (DiagnosticsPayload, DeviceStateSnapshotPayload) " +
                "MUST NOT be used for task closure",
            cls.isClosureEligible
        )
        assertFalse(
            "Negative proof: DIAGNOSTICS_ONLY payloads MUST NOT carry canonical results",
            cls.isCanonicalResultCapable
        )
    }

    @Test fun `ADVISORY_RECOVERY_ONLY cannot close tasks — isClosureEligible is always false`() {
        val cls = AndroidNonClosureSignalBoundaryContract.NonClosureSignalClass.ADVISORY_RECOVERY_ONLY
        assertFalse(
            "Negative proof: ADVISORY_RECOVERY_ONLY signals MUST NOT close tasks; " +
                "V2 must adjudicate canonical continuity independently",
            cls.isClosureEligible
        )
        assertFalse(cls.isCanonicalResultCapable)
    }

    @Test fun `READINESS_ONLY cannot close tasks — isClosureEligible is always false`() {
        val cls = AndroidNonClosureSignalBoundaryContract.NonClosureSignalClass.READINESS_ONLY
        assertFalse(
            "Negative proof: READINESS_ONLY payloads (DeviceReadinessReportPayload) " +
                "MUST NOT produce task closure; readiness is for release-gate assessment only",
            cls.isClosureEligible
        )
        assertFalse(cls.isCanonicalResultCapable)
    }

    @Test fun `EVALUATOR_ADVISORY_ONLY cannot close tasks — isClosureEligible is always false`() {
        val cls = AndroidNonClosureSignalBoundaryContract.NonClosureSignalClass.EVALUATOR_ADVISORY_ONLY
        assertFalse(
            "Negative proof: EVALUATOR_ADVISORY_ONLY payloads (governance/acceptance/strategy reports) " +
                "MUST NOT produce canonical task closure; they feed governance/graduation gates only",
            cls.isClosureEligible
        )
        assertFalse(cls.isCanonicalResultCapable)
    }

    @Test fun `CONTINUITY_DIAGNOSTIC_ONLY cannot close tasks — isClosureEligible is always false`() {
        val cls = AndroidNonClosureSignalBoundaryContract.NonClosureSignalClass.CONTINUITY_DIAGNOSTIC_ONLY
        assertFalse(
            "Negative proof: CONTINUITY_DIAGNOSTIC_ONLY events MUST NOT produce task closure; " +
                "they are service-layer diagnostics visibility only",
            cls.isClosureEligible
        )
        assertFalse(cls.isCanonicalResultCapable)
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Additional negative proof: non-closure types cannot impersonate closure types
    // ══════════════════════════════════════════════════════════════════════════

    @Test fun `no NonClosureSignalClass wireValue collides with AndroidResultUplinkBoundaryContract ResultSignalClass wireValues`() {
        // This test prevents a wire-level collision where a non-closure wire value could be
        // misread as a closure-bearing ResultSignalClass value by V2.
        val closureWireValues = setOf("authority_result", "acceptance_closure_signal", "diagnostics_informational")
        AndroidNonClosureSignalBoundaryContract.NonClosureSignalClass.values().forEach { cls ->
            assertFalse(
                "NonClosureSignalClass wire value '${cls.wireValue}' must not collide with " +
                    "AndroidResultUplinkBoundaryContract.ResultSignalClass wire values",
                cls.wireValue in closureWireValues
            )
        }
    }

    @Test fun `all non-closure classified MsgTypes also have classify() returning non-null`() {
        AndroidNonClosureSignalBoundaryContract.NON_CLOSURE_MSG_TYPES.forEach { msgType ->
            assertNotNull(
                "classify($msgType) must return non-null for all NON_CLOSURE_MSG_TYPES",
                AndroidNonClosureSignalBoundaryContract.classify(msgType)
            )
        }
    }

    @Test fun `all non-closure classified MsgTypes have isClosureEligible = false transitively`() {
        // End-to-end proof: each type in NON_CLOSURE_MSG_TYPES, when classified, yields a
        // NonClosureSignalClass with isClosureEligible=false.
        AndroidNonClosureSignalBoundaryContract.NON_CLOSURE_MSG_TYPES.forEach { msgType ->
            val cls = AndroidNonClosureSignalBoundaryContract.classify(msgType)
            assertNotNull("classify($msgType) must return non-null", cls)
            assertFalse(
                "classify($msgType) must return a class with isClosureEligible=false",
                cls!!.isClosureEligible
            )
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 18. StabilizationBaseline registration
    // ══════════════════════════════════════════════════════════════════════════

    @Test fun `StabilizationBaseline registers android-non-closure-signal-boundary-contract`() {
        assertNotNull(
            StabilizationBaseline.forId("android-non-closure-signal-boundary-contract")
        )
    }

    @Test fun `android-non-closure-signal-boundary-contract is CANONICAL_STABLE`() {
        val entry = StabilizationBaseline.forId("android-non-closure-signal-boundary-contract")
        assertEquals(
            StabilizationBaseline.SurfaceStability.CANONICAL_STABLE,
            entry!!.stability
        )
    }

    @Test fun `android-non-closure-signal-boundary-contract has EXTEND guidance`() {
        val entry = StabilizationBaseline.forId("android-non-closure-signal-boundary-contract")
        assertEquals(
            StabilizationBaseline.ExtensionGuidance.EXTEND,
            entry!!.extensionGuidance
        )
    }

    @Test fun `android-non-closure-signal-boundary-contract introducedPr is 120`() {
        val entry = StabilizationBaseline.forId("android-non-closure-signal-boundary-contract")
        assertEquals(120, entry!!.introducedPr)
    }

    @Test fun `INTRODUCED_PR constant is 120`() {
        assertEquals(120, AndroidNonClosureSignalBoundaryContract.INTRODUCED_PR)
    }

    @Test fun `SCHEMA_VERSION constant is 1`() {
        assertEquals("1", AndroidNonClosureSignalBoundaryContract.SCHEMA_VERSION)
    }
}
