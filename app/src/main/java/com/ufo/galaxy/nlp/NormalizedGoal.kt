package com.ufo.galaxy.nlp

/**
 * Holds both the original and normalized forms of a user's natural-language goal.
 *
 * [originalText] is always the unmodified input, preserved for display, logging, and audit.
 * [normalizedText] is the cleaned, canonical form passed to the local planner for execution.
 * [extractedConstraints] lists any constraint phrases detected during normalization.
 *
 * @param originalText          Raw goal string received from the user or gateway.
 * @param normalizedText        Cleaned, alias-resolved form used for planning.
 * @param extractedConstraints  Constraint phrases extracted from the raw goal
 *                              (e.g., "without ads", "using wifi only").
 */
data class NormalizedGoal(
    val originalText: String,
    val normalizedText: String,
    val extractedConstraints: List<String> = emptyList()
)
