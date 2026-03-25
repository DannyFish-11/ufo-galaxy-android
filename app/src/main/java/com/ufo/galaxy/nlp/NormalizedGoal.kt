package com.ufo.galaxy.nlp

/**
 * The result of normalizing a natural-language goal string.
 *
 * Preserves the [original] user input unchanged for display and logging, while
 * [normalized] provides a stable, cleaned form suitable for planner consumption.
 * Any constraint phrases extracted from the input are collected in [constraints].
 *
 * @property original     The unmodified raw user input.
 * @property normalized   Cleaned, lowercased, alias-resolved goal text.
 * @property constraints  Constraint strings extracted from the input
 *                        (e.g. "within 5 minutes", "silently").
 */
data class NormalizedGoal(
    val original: String,
    val normalized: String,
    val constraints: List<String> = emptyList()
)
