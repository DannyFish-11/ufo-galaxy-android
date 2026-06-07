package com.ufo.galaxy.agent

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for [HandoffContractValidator] — the canonical Android-side handoff contract
 * validator (PR-9, post-#533 dual-repo runtime unification master plan —
 * Canonical Cross-Repo Delegated-Runtime Handoff Contract Foundations, Android side).
 *
 * ## Test matrix
 *
 * ### Required identifier fields — happy path
 *  - A fully populated envelope produces an isValid=true result.
 *  - A fully populated envelope has no missingFields.
 *  - A fully populated envelope has no ERROR-level fieldProblems.
 *
 * ### Required identifier fields — missing/blank
 *  - Blank takeover_id → missingFields contains "takeover_id".
 *  - Blank task_id → missingFields contains "task_id".
 *  - Blank trace_id → missingFields contains "trace_id".
 *  - Blank goal → missingFields contains "goal".
 *  - All four blank → isInvalid=true and all four names in missingFields.
 *  - Missing any single required field → isInvalid=true.
 *
 * ### handoff_reason coherence
 *  - Known reason wire value → no ERROR fieldProblems.
 *  - Unknown reason wire value → ERROR fieldProblem for "handoff_reason".
 *  - Absent (null) handoff_reason → no fieldProblems for handoff_reason.
 *  - Unknown reason still invalidates the result (isInvalid=true).
 *
 * ### originating_formation_role coherence
 *  - Known role wire value → no fieldProblems.
 *  - Unknown role wire value → WARNING fieldProblem for "originating_formation_role".
 *  - Unknown role does NOT cause isInvalid (WARNING only).
 *  - Absent (null) originating_formation_role → no fieldProblems.
 *
 * ### required_capability_dimensions coherence
 *  - All known dimension wire values → no fieldProblems.
 *  - Unknown dimension wire value → WARNING fieldProblem for "required_capability_dimensions".
 *  - Unknown dimension does NOT cause isInvalid (WARNING only).
 *  - Empty list → no fieldProblems.
 *  - Multiple unknown dimensions → one WARNING per unknown value.
 *
 * ### ProblemLevel distinction
 *  - ERROR problems cause isInvalid.
 *  - WARNING-only problems keep isValid=true.
 *
 * ### ValidationResult.summary()
 *  - valid result returns "valid".
 *  - missing-field result contains "missing=" and the field name.
 *  - ERROR fieldProblem result contains "problems=".
 *
 * ### REQUIRED_IDENTIFIER_FIELDS constant
 *  - Contains exactly "takeover_id", "task_id", "trace_id", "goal".
 *  - Has exactly 4 entries.
 */
class HandoffContractValidatorTest {

    // ── Helpers ───────────────────────────────────────────────────────────────

    private val validator = HandoffContractValidator()

    private fun validEnvelope(
        takeoverId: String = "to-1",
        taskId: String = "t-1",
        traceId: String = "tr-1",
        goal: String = "open settings",
        handoffReason: String? = null,
        originatingHostId: String? = null,
        originatingFormationRole: String? = null,
        requiredCapabilityDimensions: List<String> = emptyList()
    ) = TakeoverRequestEnvelope(
        takeover_id = takeoverId,
        task_id = taskId,
        trace_id = traceId,
        goal = goal,
        handoff_reason = handoffReason,
        originating_host_id = originatingHostId,
        originating_formation_role = originatingFormationRole,
        required_capability_dimensions = requiredCapabilityDimensions
    )

    // ── Required identifier fields — happy path ───────────────────────────────

    @Test
    fun `fully populated envelope produces isValid=true`() {
        val result = validator.validate(validEnvelope())
        assertTrue("Fully populated envelope must be valid", result.isValid)
    }

    @Test
    fun `fully populated envelope has no missingFields`() {
        val result = validator.validate(validEnvelope())
        assertTrue("Fully populated envelope must have no missing fields", result.missingFields.isEmpty())
    }

    @Test
    fun `fully populated envelope has no ERROR fieldProblems`() {
        val result = validator.validate(validEnvelope())
        assertTrue(
            "Fully populated envelope must have no ERROR-level field problems",
            result.fieldProblems.none { it.level == HandoffContractValidator.ProblemLevel.ERROR }
        )
    }

    // ── Required identifier fields — missing/blank ────────────────────────────

    @Test
    fun `blank takeover_id produces missingFields entry`() {
        val result = validator.validate(validEnvelope(takeoverId = ""))
        assertTrue("Blank takeover_id must appear in missingFields", result.missingFields.contains("takeover_id"))
    }

    @Test
    fun `blank task_id produces missingFields entry`() {
        val result = validator.validate(validEnvelope(taskId = ""))
        assertTrue("Blank task_id must appear in missingFields", result.missingFields.contains("task_id"))
    }

    @Test
    fun `blank trace_id produces missingFields entry`() {
        val result = validator.validate(validEnvelope(traceId = ""))
        assertTrue("Blank trace_id must appear in missingFields", result.missingFields.contains("trace_id"))
    }

    @Test
    fun `blank goal produces missingFields entry`() {
        val result = validator.validate(validEnvelope(goal = ""))
        assertTrue("Blank goal must appear in missingFields", result.missingFields.contains("goal"))
    }

    @Test
    fun `all four blank required fields are all reported in missingFields`() {
        val result = validator.validate(validEnvelope(takeoverId = "", taskId = "", traceId = "", goal = ""))
        assertTrue(result.missingFields.contains("takeover_id"))
        assertTrue(result.missingFields.contains("task_id"))
        assertTrue(result.missingFields.contains("trace_id"))
        assertTrue(result.missingFields.contains("goal"))
    }

    @Test
    fun `all four blank required fields makes result isInvalid`() {
        val result = validator.validate(validEnvelope(takeoverId = "", taskId = "", traceId = "", goal = ""))
        assertTrue(result.isInvalid)
    }

    @Test
    fun `missing single required field makes result isInvalid`() {
        val result = validator.validate(validEnvelope(taskId = ""))
        assertTrue("Missing a single required field must make the result invalid", result.isInvalid)
    }

    @Test
    fun `blank takeover_id isInvalid`() {
        val result = validator.validate(validEnvelope(takeoverId = "  "))
        assertTrue(result.isInvalid)
    }

    // ── handoff_reason coherence ──────────────────────────────────────────────

    @Test
    fun `known handoff_reason wire value produces no ERROR fieldProblems`() {
        val result = validator.validate(validEnvelope(handoffReason = "continuation"))
        assertTrue(
            "Known handoff_reason must not produce ERROR field problems",
            result.fieldProblems.none {
                it.field == "handoff_reason" && it.level == HandoffContractValidator.ProblemLevel.ERROR
            }
        )
    }

    @Test
    fun `unknown handoff_reason wire value produces ERROR fieldProblem`() {
        val result = validator.validate(validEnvelope(handoffReason = "totally_unknown_reason"))
        assertTrue(
            "Unknown handoff_reason must produce an ERROR-level field problem",
            result.fieldProblems.any {
                it.field == "handoff_reason" && it.level == HandoffContractValidator.ProblemLevel.ERROR
            }
        )
    }

    @Test
    fun `absent handoff_reason produces no fieldProblems`() {
        val result = validator.validate(validEnvelope(handoffReason = null))
        assertTrue(
            "Null handoff_reason must produce no field problems",
            result.fieldProblems.none { it.field == "handoff_reason" }
        )
    }

    @Test
    fun `unknown handoff_reason makes result isInvalid`() {
        val result = validator.validate(validEnvelope(handoffReason = "not_a_reason"))
        assertTrue("Unknown handoff_reason must make result isInvalid", result.isInvalid)
    }

    @Test
    fun `each known HandoffReason wire value passes validation`() {
        DelegatedHandoffContract.HandoffReason.entries.forEach { reason ->
            val result = validator.validate(validEnvelope(handoffReason = reason.wireValue))
            assertTrue(
                "Known HandoffReason '${reason.wireValue}' must pass validation",
                result.fieldProblems.none {
                    it.field == "handoff_reason" && it.level == HandoffContractValidator.ProblemLevel.ERROR
                }
            )
        }
    }

    // ── originating_formation_role coherence ──────────────────────────────────

    @Test
    fun `known originating_formation_role wire value produces no fieldProblems`() {
        val result = validator.validate(validEnvelope(originatingFormationRole = "primary"))
        assertTrue(
            "Known formation role must produce no field problems",
            result.fieldProblems.none { it.field == "originating_formation_role" }
        )
    }

    @Test
    fun `unknown originating_formation_role wire value produces WARNING fieldProblem`() {
        val result = validator.validate(validEnvelope(originatingFormationRole = "unknown_role"))
        assertTrue(
            "Unknown formation role must produce a WARNING-level field problem",
            result.fieldProblems.any {
                it.field == "originating_formation_role" &&
                    it.level == HandoffContractValidator.ProblemLevel.WARNING
            }
        )
    }

    @Test
    fun `unknown originating_formation_role does NOT make result isInvalid`() {
        val result = validator.validate(validEnvelope(originatingFormationRole = "unknown_role"))
        assertTrue(
            "Unknown formation role (WARNING) must not make result isInvalid",
            result.isValid
        )
    }

    @Test
    fun `absent originating_formation_role produces no fieldProblems`() {
        val result = validator.validate(validEnvelope(originatingFormationRole = null))
        assertTrue(
            "Null originating_formation_role must produce no field problems",
            result.fieldProblems.none { it.field == "originating_formation_role" }
        )
    }

    // ── required_capability_dimensions coherence ──────────────────────────────

    @Test
    fun `all known dimension wire values produce no fieldProblems`() {
        val result = validator.validate(
            validEnvelope(
                requiredCapabilityDimensions = listOf("local_inference", "accessibility_execution")
            )
        )
        assertTrue(
            "All known dimension values must produce no field problems",
            result.fieldProblems.none { it.field == "required_capability_dimensions" }
        )
    }

    @Test
    fun `unknown dimension wire value produces WARNING fieldProblem`() {
        val result = validator.validate(
            validEnvelope(requiredCapabilityDimensions = listOf("not_a_real_dimension"))
        )
        assertTrue(
            "Unknown dimension value must produce a WARNING-level field problem",
            result.fieldProblems.any {
                it.field == "required_capability_dimensions" &&
                    it.level == HandoffContractValidator.ProblemLevel.WARNING
            }
        )
    }

    @Test
    fun `unknown dimension does NOT make result isInvalid`() {
        val result = validator.validate(
            validEnvelope(requiredCapabilityDimensions = listOf("not_a_real_dimension"))
        )
        assertTrue(
            "Unknown dimension (WARNING) must not make result isInvalid",
            result.isValid
        )
    }

    @Test
    fun `empty required_capability_dimensions produces no fieldProblems`() {
        val result = validator.validate(validEnvelope(requiredCapabilityDimensions = emptyList()))
        assertTrue(
            "Empty dimensions list must produce no field problems",
            result.fieldProblems.none { it.field == "required_capability_dimensions" }
        )
    }

    @Test
    fun `two unknown dimensions produce two separate WARNING fieldProblems`() {
        val result = validator.validate(
            validEnvelope(
                requiredCapabilityDimensions = listOf("dim_unknown_1", "dim_unknown_2")
            )
        )
        val warnings = result.fieldProblems.filter {
            it.field == "required_capability_dimensions" &&
                it.level == HandoffContractValidator.ProblemLevel.WARNING
        }
        assertEquals("Two unknown dimensions must produce two WARNING problems", 2, warnings.size)
    }

    // ── ProblemLevel distinction ──────────────────────────────────────────────

    @Test
    fun `ERROR problems cause isInvalid`() {
        val result = validator.validate(validEnvelope(handoffReason = "bad_reason"))
        assertTrue("An ERROR-level problem must cause isInvalid", result.isInvalid)
    }

    @Test
    fun `WARNING-only problems keep isValid=true`() {
        val result = validator.validate(
            validEnvelope(originatingFormationRole = "unrecognised_role")
        )
        assertTrue("WARNING-only problems must not make result isInvalid", result.isValid)
    }

    // ── ValidationResult.summary() ────────────────────────────────────────────

    @Test
    fun `summary() returns valid for a passing result`() {
        val result = validator.validate(validEnvelope())
        assertEquals("valid", result.summary())
    }

    @Test
    fun `summary() contains missing= for a missing-field result`() {
        val result = validator.validate(validEnvelope(taskId = ""))
        assertTrue(
            "summary() must contain 'missing=' when fields are missing",
            result.summary().contains("missing=")
        )
    }

    @Test
    fun `summary() contains problems= for an ERROR field problem`() {
        val result = validator.validate(validEnvelope(handoffReason = "bad_reason"))
        assertTrue(
            "summary() must contain 'problems=' when ERROR field problems exist",
            result.summary().contains("problems=")
        )
    }

    @Test
    fun `summary() begins with invalid when isInvalid`() {
        val result = validator.validate(validEnvelope(taskId = ""))
        assertTrue("summary() must begin with 'invalid:' for an invalid result", result.summary().startsWith("invalid:"))
    }

    // ── REQUIRED_IDENTIFIER_FIELDS constant ───────────────────────────────────

    @Test
    fun `REQUIRED_IDENTIFIER_FIELDS has exactly 4 entries`() {
        assertEquals(4, HandoffContractValidator.REQUIRED_IDENTIFIER_FIELDS.size)
    }

    @Test
    fun `REQUIRED_IDENTIFIER_FIELDS contains takeover_id`() {
        assertTrue(HandoffContractValidator.REQUIRED_IDENTIFIER_FIELDS.contains("takeover_id"))
    }

    @Test
    fun `REQUIRED_IDENTIFIER_FIELDS contains task_id`() {
        assertTrue(HandoffContractValidator.REQUIRED_IDENTIFIER_FIELDS.contains("task_id"))
    }

    @Test
    fun `REQUIRED_IDENTIFIER_FIELDS contains trace_id`() {
        assertTrue(HandoffContractValidator.REQUIRED_IDENTIFIER_FIELDS.contains("trace_id"))
    }

    @Test
    fun `REQUIRED_IDENTIFIER_FIELDS contains goal`() {
        assertTrue(HandoffContractValidator.REQUIRED_IDENTIFIER_FIELDS.contains("goal"))
    }
}
