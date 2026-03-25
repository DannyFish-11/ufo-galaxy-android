package com.ufo.galaxy.nlp

/**
 * Lightweight, rule-based natural-language goal normalizer.
 *
 * Produces a stable [NormalizedGoal] from a raw user-input string without any external
 * NLP dependencies. All transformations are deterministic and transparent.
 *
 * ### Normalization pipeline (applied in order)
 * 1. Trim leading/trailing whitespace.
 * 2. Collapse internal runs of whitespace to a single space.
 * 3. Remove common polite filler phrases ("please", "could you", etc.).
 * 4. Extract and strip simple constraint clauses (e.g. "within 5 minutes").
 * 5. Resolve action verb synonyms using [AppAliasRegistry] (longest-match first).
 * 6. Resolve app name aliases using [AppAliasRegistry].
 * 7. Lowercase the result.
 * 8. Strip a trailing period or comma if present.
 *
 * The [NormalizedGoal.original] field always contains the unmodified input so that
 * callers can use it for display and logging. [NormalizedGoal.normalized] is the
 * stable form passed to the planner.
 *
 * @param registry The [AppAliasRegistry] instance used for alias lookups.
 *                 Defaults to the global [AppAliasRegistry] singleton.
 */
class GoalNormalizer(
    private val registry: AppAliasRegistry = AppAliasRegistry
) {

    companion object {
        /** Polite filler phrases removed before further processing (case-insensitive). */
        private val FILLER_PHRASES: List<String> = listOf(
            "could you please",
            "can you please",
            "would you please",
            "please help me",
            "help me to",
            "help me",
            "could you",
            "can you",
            "would you",
            "i want you to",
            "i need you to",
            "i want to",
            "i need to",
            "i'd like to",
            "i would like to",
            "i'd like you to",
            "i would like you to",
            "please"
        )

        /**
         * Simple constraint patterns.  Each entry is a [Regex] that, when matched,
         * extracts a constraint clause to move into [NormalizedGoal.constraints] and
         * removes that text from the normalized goal.
         *
         * Groups:
         * - group(0) – the full matched substring to strip from the goal text.
         * - group(1) – the human-readable constraint string to keep.
         */
        private val CONSTRAINT_PATTERNS: List<Regex> = listOf(
            // "within N minutes/hours/seconds"
            Regex("""(within \d+ (?:minute|minutes|hour|hours|second|seconds))""", RegexOption.IGNORE_CASE),
            // "in less than N minutes/hours/seconds"
            Regex("""(in less than \d+ (?:minute|minutes|hour|hours|second|seconds))""", RegexOption.IGNORE_CASE),
            // "at most N times / steps"
            Regex("""(at most \d+ (?:time|times|step|steps))""", RegexOption.IGNORE_CASE),
            // "quietly" / "silently"
            Regex("""(quietly|silently)""", RegexOption.IGNORE_CASE),
            // "without notifications"
            Regex("""(without (?:sound|notification|notifications|disturbing anyone))""", RegexOption.IGNORE_CASE)
        )

        // Action verb phrases sorted longest-first so "go to home screen" matches before "go to"
        private val ACTION_SYNONYM_PHRASES: List<Pair<Regex, String>> by lazy {
            listOf(
                // home synonyms (check before generic "go to")
                Regex("""(?:go to home screen|back to homescreen|back to home|return home|go home|返回桌面|回首页)""", RegexOption.IGNORE_CASE) to "home",
                // back synonyms
                Regex("""(?:press back|go back|返回上级|返回上一页)""", RegexOption.IGNORE_CASE) to "back",
                // open synonyms (multi-word first)
                Regex("""(?:navigate to|bring up|go to)""", RegexOption.IGNORE_CASE) to "open",
                Regex("""(?:launch|start|run)\b""", RegexOption.IGNORE_CASE) to "open",
                // search synonyms
                Regex("""(?:look up|look for|find)\b""", RegexOption.IGNORE_CASE) to "search",
                // tap synonyms
                Regex("""(?:click|press|select|choose|hit)\b""", RegexOption.IGNORE_CASE) to "tap",
                // type synonyms
                Regex("""(?:input|write|enter)\b""", RegexOption.IGNORE_CASE) to "type",
                // scroll synonyms
                Regex("""(?:swipe|slide)\b""", RegexOption.IGNORE_CASE) to "scroll"
            )
        }
    }

    /**
     * Normalizes [rawGoal] and returns a [NormalizedGoal].
     *
     * The [NormalizedGoal.original] field is always equal to [rawGoal].
     * The [NormalizedGoal.normalized] field contains the result of the full pipeline.
     * The [NormalizedGoal.constraints] list contains any extracted constraint phrases.
     *
     * @param rawGoal Raw natural-language user input.
     * @return [NormalizedGoal] with stable normalized form.
     */
    fun normalize(rawGoal: String): NormalizedGoal {
        val original = rawGoal

        // Step 1 & 2: trim and collapse whitespace
        var text = rawGoal.trim().replace(Regex("""\s+"""), " ")

        // Step 3: remove polite filler phrases (case-insensitive, longest match first)
        for (filler in FILLER_PHRASES) {
            // Only remove at start of string to avoid false positives mid-sentence
            val pattern = Regex("""^${Regex.escape(filler)}[, ]+""", RegexOption.IGNORE_CASE)
            text = pattern.replace(text, "")
        }
        text = text.trim()

        // Step 4: extract constraint clauses
        val constraints = mutableListOf<String>()
        for (pattern in CONSTRAINT_PATTERNS) {
            val match = pattern.find(text)
            if (match != null) {
                constraints.add(match.groupValues[1].lowercase())
                text = text.removeRange(match.range).trim()
                // Clean up any dangling comma+space left behind
                text = text.replace(Regex(""",\s*$"""), "").trim()
                text = text.replace(Regex("""^,\s*"""), "").trim()
            }
        }

        // Step 5: resolve action verb synonyms (applied before lowercasing to preserve
        // the regex word-boundary anchors which are case-insensitive)
        for ((pattern, canonical) in ACTION_SYNONYM_PHRASES) {
            text = pattern.replace(text, canonical)
        }

        // Step 6: resolve app name aliases (word-boundary match, case-insensitive)
        text = resolveAppAliasesInText(text)

        // Step 7: lowercase
        text = text.lowercase()

        // Step 8: strip trailing punctuation
        text = text.trimEnd('.', ',', '!', '?').trim()

        // Collapse any whitespace introduced by replacements
        text = text.replace(Regex("""\s+"""), " ").trim()

        return NormalizedGoal(
            original = original,
            normalized = text,
            constraints = constraints
        )
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Scans [text] for known app name aliases using word-boundary matching and replaces
     * each occurrence with the canonical name.  Longer aliases are checked first to
     * prevent partial matches (e.g. "file manager" before "file").
     *
     * ASCII aliases use `\b` word boundaries; non-ASCII (e.g. Chinese) aliases use plain
     * literal matching since `\b` does not apply to non-ASCII characters.
     */
    private fun resolveAppAliasesInText(text: String): String {
        // Build sorted list from registry keys: longest first
        val keys = registry.appAliasKeys()
            .filter { it.isNotBlank() }
            .sortedByDescending { it.length }

        var result = text
        for (alias in keys) {
            val canonical = registry.resolveAppAlias(alias)
            val hasNonAscii = alias.any { it.code > 127 }
            val pattern = if (hasNonAscii) {
                // No word-boundary anchors for non-ASCII (e.g. Chinese) text
                Regex(Regex.escape(alias), RegexOption.IGNORE_CASE)
            } else {
                Regex("""\b${Regex.escape(alias)}\b""", RegexOption.IGNORE_CASE)
            }
            result = pattern.replace(result, canonical)
        }
        return result
    }
}
