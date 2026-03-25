package com.ufo.galaxy.local

/**
 * Unified failure taxonomy for terminal and step-level failures in the local loop.
 *
 * Every failure path in [com.ufo.galaxy.loop.LoopController], [com.ufo.galaxy.loop.LocalPlanner],
 * and [com.ufo.galaxy.loop.ExecutorBridge] maps to one of these codes so that callers,
 * telemetry, and tests share a single vocabulary.
 *
 * Codes are grouped by subsystem:
 * - SCREENSHOT_* : screenshot capture failures
 * - PLAN_*       : planner failures (all stages exhausted)
 * - GROUND_*     : grounding failures (all stages exhausted)
 * - EXEC_*       : accessibility execution failures
 * - LOOP_*       : loop-level guards (stagnation, budget, timeout, cancel)
 */
enum class FailureCode(
    /** Short machine-readable token for logs and protocol fields. */
    val token: String,
    /** Human-readable description of this failure. */
    val description: String
) {

    // ── Screenshot ────────────────────────────────────────────────────────────

    SCREENSHOT_CAPTURE_FAILED(
        "screenshot_capture_failed",
        "Screenshot capture threw an exception or returned empty bytes"
    ),

    // ── Planner ───────────────────────────────────────────────────────────────

    PLAN_ALL_STAGES_EXHAUSTED(
        "plan_all_stages_exhausted",
        "All planner fallback stages were attempted and none produced a valid plan"
    ),
    PLAN_EMPTY_RESULT(
        "plan_empty_result",
        "Planner returned successfully but produced zero steps"
    ),
    PLAN_REPLAN_EXHAUSTED(
        "plan_replan_exhausted",
        "All replan fallback stages were attempted and none produced a recovery plan"
    ),

    // ── Grounding ─────────────────────────────────────────────────────────────

    GROUND_ALL_STAGES_EXHAUSTED(
        "ground_all_stages_exhausted",
        "All grounding fallback stages were attempted and none produced valid coordinates"
    ),
    GROUND_LOW_CONFIDENCE(
        "ground_low_confidence",
        "Grounding succeeded but confidence is below the minimum threshold"
    ),
    GROUND_SERVICE_UNAVAILABLE(
        "ground_service_unavailable",
        "Grounding service is not loaded and no fallback was available"
    ),

    // ── Execution ─────────────────────────────────────────────────────────────

    EXEC_ACCESSIBILITY_RETURNED_FALSE(
        "exec_accessibility_returned_false",
        "AccessibilityExecutor.execute() returned false; action was not performed"
    ),
    EXEC_EXCEPTION(
        "exec_exception",
        "An unhandled exception occurred during action dispatch"
    ),

    // ── Loop guards ───────────────────────────────────────────────────────────

    LOOP_MAX_STEPS_REACHED(
        "loop_max_steps_reached",
        "The step budget was exhausted before the task completed"
    ),
    LOOP_STEP_RETRIES_EXHAUSTED(
        "loop_step_retries_exhausted",
        "A step failed and all per-step retry attempts were consumed"
    ),
    LOOP_STAGNATION_REPEATED_ACTION(
        "loop_stagnation_repeated_action",
        "The same action was attempted too many times with no progress"
    ),
    LOOP_STAGNATION_NO_UI_CHANGE(
        "loop_stagnation_no_ui_change",
        "Multiple consecutive steps produced no detectable UI change"
    ),
    LOOP_STAGNATION_REPEATED_PLAN(
        "loop_stagnation_repeated_plan",
        "The planner produced the same plan multiple times in a row"
    ),
    LOOP_STEP_TIMEOUT(
        "loop_step_timeout",
        "A single step exceeded the per-step time limit"
    ),
    LOOP_GOAL_TIMEOUT(
        "loop_goal_timeout",
        "The entire goal execution exceeded the total time limit"
    ),
    LOOP_CANCELLED_BY_REMOTE(
        "loop_cancelled_by_remote",
        "Local execution was cancelled because a remote task was assigned"
    ),
    LOOP_BLOCKED_BY_REMOTE(
        "loop_blocked_by_remote",
        "Local execution was blocked because a remote task is already active"
    ),

    // ── Unknown ───────────────────────────────────────────────────────────────

    UNKNOWN(
        "unknown",
        "An unclassified failure occurred"
    );

    override fun toString(): String = token
}
