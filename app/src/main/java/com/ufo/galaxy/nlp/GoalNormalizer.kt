package com.ufo.galaxy.nlp

/**
 * Lightweight, rule-based normalizer that converts a raw natural-language goal string
 * into a stable, structured [NormalizedGoal].
 *
 * Normalization pipeline (applied in order):
 * 1. Trim leading/trailing whitespace.
 * 2. Strip polite filler phrases from the beginning ("please", "can you", "hey", etc.).
 * 3. Extract simple constraint phrases ("without X", "using X only") and remove them from
 *    the main instruction text.
 * 4. Normalize action verbs via [AppAliasRegistry.ACTION_ALIASES].
 * 5. Normalize app names via [AppAliasRegistry.APP_ALIASES].
 * 6. Normalize UI target shorthand via [AppAliasRegistry.UI_TARGET_ALIASES].
 * 7. Collapse repeated whitespace and strip trailing punctuation.
 *
 * [NormalizedGoal.originalText] always preserves the input exactly as received so that
 * it can be used for display and logging without any information loss.
 * [NormalizedGoal.normalizedText] is the safe planner-ready form.
 *
 * This normalizer is stateless and thread-safe.
 */
object GoalNormalizer {

    // ── Filler phrases ────────────────────────────────────────────────────────

    /**
     * Polite preamble phrases stripped from the start of a goal sentence.
     * Longer phrases are checked before shorter ones to favour greedy matching.
     */
    private val FILLER_PHRASES: List<String> = listOf(
        "would you please",
        "could you please",
        "can you please",
        "would you mind",
        "i would like you to",
        "i'd like you to",
        "i need you to",
        "i want you to",
        "i would like to",
        "i'd like to",
        "i need to",
        "i want to",
        "i would like",
        "i'd like",
        "i need",
        "i want",
        "help me to",
        "help me",
        "would you",
        "could you",
        "can you",
        "hey there",
        "hi there",
        "kindly",
        "please",
        "hey",
        "hi"
    )

    // ── Constraint patterns ───────────────────────────────────────────────────

    /**
     * Patterns that match extractable constraint phrases.
     * Each pattern must capture the constraint value in group 1.
     */
    private val CONSTRAINT_PATTERNS: List<Regex> = listOf(
        Regex("""(?i)\bwithout\s+(?:using\s+)?([\w\s]{1,40}?)(?:\s*[,.]|$)"""),
        Regex("""(?i)\busing\s+([\w\s]{1,40}?)\s+only\b"""),
        Regex("""(?i)\bonly\s+(?:using\s+)?([\w\s]{1,40}?)(?:\s*[,.]|$)""")
    )

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Normalizes [rawGoal] and returns a [NormalizedGoal] containing both the original
     * and the planner-ready normalized form.
     *
     * @param rawGoal Raw natural-language goal from user input or the gateway.
     * @return [NormalizedGoal] with original text, normalized text, and any extracted constraints.
     */
    fun normalize(rawGoal: String): NormalizedGoal {
        val original = rawGoal.trim()
        if (original.isEmpty()) {
            return NormalizedGoal(originalText = original, normalizedText = original)
        }

        var text = original

        // Step 1: Strip polite filler phrases from the front of the sentence.
        text = stripFillerPhrases(text)

        // Step 2: Extract and remove inline constraint phrases.
        val (constraints, withoutConstraints) = extractConstraints(text)
        text = withoutConstraints

        // Step 3: Normalize action verb synonyms.
        text = normalizeAliases(text, AppAliasRegistry.ACTION_ALIASES)

        // Step 4: Normalize app name aliases.
        text = normalizeAliases(text, AppAliasRegistry.APP_ALIASES)

        // Step 5: Normalize UI target shorthand.
        text = normalizeAliases(text, AppAliasRegistry.UI_TARGET_ALIASES)

        // Step 6: Collapse whitespace and strip trailing punctuation.
        text = cleanText(text)

        // Guard: if normalization accidentally emptied the string, restore the original.
        if (text.isEmpty()) text = original

        return NormalizedGoal(
            originalText = original,
            normalizedText = text,
            extractedConstraints = constraints
        )
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Strips known filler phrases from the beginning of [text] in a loop until no
     * further phrases are removed. Longer phrases are tried before shorter ones.
     */
    private fun stripFillerPhrases(text: String): String {
        var result = text
        var changed = true
        while (changed) {
            changed = false
            for (filler in FILLER_PHRASES) {
                val pattern = Regex(
                    "^${Regex.escape(filler)}[,\\s]*",
                    RegexOption.IGNORE_CASE
                )
                val stripped = pattern.replace(result, "").trimStart()
                if (stripped.length < result.length) {
                    result = stripped
                    changed = true
                    break  // restart the loop after any match
                }
            }
        }
        return result.trimStart()
    }

    /**
     * Scans [text] for [CONSTRAINT_PATTERNS], extracts the captured constraint values
     * (up to 5 words each), removes the matched spans from [text], and returns both
     * the constraint list and the cleaned text.
     */
    private fun extractConstraints(text: String): Pair<List<String>, String> {
        val constraints = mutableListOf<String>()
        var remaining = text
        for (pattern in CONSTRAINT_PATTERNS) {
            val match = pattern.find(remaining) ?: continue
            val constraint = match.groupValues[1].trim()
            // Accept only short phrases to avoid consuming unrelated sentence content.
            if (constraint.isNotEmpty() && constraint.split(Regex("\\s+")).size <= 5) {
                constraints.add(constraint)
                remaining = (remaining.removeRange(match.range)).trim()
            }
        }
        return Pair(constraints, remaining)
    }

    /**
     * Replaces [aliasMap] keys found in [text] with their canonical values.
     * Longer keys are matched before shorter ones to prevent partial-word shadowing.
     * Matching is case-insensitive and honours word boundaries.
     */
    private fun normalizeAliases(text: String, aliasMap: Map<String, String>): String {
        var result = text
        // Sort descending by key length so longer (more specific) phrases match first.
        val sorted = aliasMap.entries.sortedByDescending { it.key.length }
        for ((alias, canonical) in sorted) {
            val pattern = Regex(
                "(?<![\\w])${Regex.escape(alias)}(?![\\w])",
                RegexOption.IGNORE_CASE
            )
            result = pattern.replace(result, canonical)
        }
        return result
    }

    /**
     * Collapses runs of whitespace to a single space and removes trailing punctuation.
     */
    private fun cleanText(text: String): String =
        text.replace(Regex("\\s{2,}"), " ")
            .trim()
            .trimEnd(',', '.', '!', '?', ';', ':')
            .trim()
}
