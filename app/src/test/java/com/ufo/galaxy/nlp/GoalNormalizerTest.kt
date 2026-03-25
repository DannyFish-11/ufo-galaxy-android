package com.ufo.galaxy.nlp

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for [GoalNormalizer].
 *
 * Covers:
 * - Empty and whitespace-only inputs.
 * - Polite filler phrase stripping.
 * - Constraint extraction.
 * - Action verb normalization.
 * - App name normalization.
 * - UI target shorthand normalization.
 * - Original text preservation.
 * - No-op inputs (already normalized text remains unchanged).
 */
class GoalNormalizerTest {

    // ── Empty / blank ─────────────────────────────────────────────────────────

    @Test
    fun `empty string returns empty normalized text`() {
        val result = GoalNormalizer.normalize("")
        assertEquals("", result.normalizedText)
        assertEquals("", result.originalText)
        assertTrue(result.extractedConstraints.isEmpty())
    }

    @Test
    fun `whitespace-only string returns empty normalized text`() {
        val result = GoalNormalizer.normalize("   ")
        assertEquals("", result.normalizedText)
        assertEquals("", result.originalText)
    }

    // ── Original text preserved ───────────────────────────────────────────────

    @Test
    fun `original text is always the untrimmed-then-trimmed input`() {
        val raw = "  Please open WhatsApp  "
        val result = GoalNormalizer.normalize(raw)
        // originalText is the trimmed version; no mutation otherwise
        assertEquals("Please open WhatsApp", result.originalText)
    }

    @Test
    fun `original text preserved even when normalization changes text`() {
        val raw = "please launch fb"
        val result = GoalNormalizer.normalize(raw)
        assertEquals("please launch fb", result.originalText)
        assertNotEquals(result.originalText, result.normalizedText)
    }

    // ── Filler phrase stripping ───────────────────────────────────────────────

    @Test
    fun `strips leading 'please'`() {
        val result = GoalNormalizer.normalize("please open Settings")
        assertFalse(result.normalizedText.lowercase().startsWith("please"))
    }

    @Test
    fun `strips leading 'can you'`() {
        val result = GoalNormalizer.normalize("can you open Settings")
        assertFalse(result.normalizedText.lowercase().startsWith("can you"))
    }

    @Test
    fun `strips leading 'could you please'`() {
        val result = GoalNormalizer.normalize("could you please scroll down")
        assertFalse(result.normalizedText.lowercase().startsWith("could"))
    }

    @Test
    fun `strips 'i want to'`() {
        val result = GoalNormalizer.normalize("I want to open Camera")
        assertFalse(result.normalizedText.lowercase().startsWith("i want"))
    }

    @Test
    fun `strips 'help me'`() {
        val result = GoalNormalizer.normalize("help me tap the login button")
        assertFalse(result.normalizedText.lowercase().startsWith("help me"))
    }

    @Test
    fun `filler-only input does not produce empty normalized text`() {
        // Stripping "please" from "please" should gracefully keep original as fallback
        val result = GoalNormalizer.normalize("please")
        assertTrue(result.normalizedText.isNotEmpty())
    }

    // ── Action verb normalization ─────────────────────────────────────────────

    @Test
    fun `launch is normalized to open`() {
        val result = GoalNormalizer.normalize("launch YouTube")
        assertTrue(result.normalizedText.contains("open", ignoreCase = true))
        assertFalse(result.normalizedText.contains("launch", ignoreCase = true))
    }

    @Test
    fun `go back is normalized to back`() {
        val result = GoalNormalizer.normalize("go back to the previous screen")
        assertTrue(
            result.normalizedText.lowercase().contains("back"),
            "Expected 'back' in '${result.normalizedText}'"
        )
        assertFalse(result.normalizedText.lowercase().contains("go back"))
    }

    @Test
    fun `press back is normalized to back`() {
        val result = GoalNormalizer.normalize("press back button")
        // "press back" → "back", so result should contain "back"
        assertTrue(result.normalizedText.lowercase().contains("back"))
        assertFalse(result.normalizedText.lowercase().startsWith("press back"))
    }

    @Test
    fun `go home is normalized to home`() {
        val result = GoalNormalizer.normalize("go home")
        assertTrue(result.normalizedText.lowercase().contains("home"))
        assertFalse(result.normalizedText.lowercase().startsWith("go home"))
    }

    @Test
    fun `click is normalized to tap`() {
        val result = GoalNormalizer.normalize("click the submit button")
        assertTrue(result.normalizedText.lowercase().contains("tap"))
        assertFalse(result.normalizedText.lowercase().contains("click"))
    }

    @Test
    fun `swipe down is normalized to scroll down`() {
        val result = GoalNormalizer.normalize("swipe down the feed")
        assertTrue(result.normalizedText.lowercase().contains("scroll"))
        assertFalse(result.normalizedText.lowercase().contains("swipe"))
    }

    // ── App name normalization ────────────────────────────────────────────────

    @Test
    fun `fb is normalized to Facebook`() {
        val result = GoalNormalizer.normalize("open fb")
        assertTrue(result.normalizedText.contains("Facebook"))
        assertFalse(result.normalizedText.contains(" fb"))
    }

    @Test
    fun `yt is normalized to YouTube`() {
        val result = GoalNormalizer.normalize("open yt")
        assertTrue(result.normalizedText.contains("YouTube"))
    }

    @Test
    fun `wa is normalized to WhatsApp`() {
        val result = GoalNormalizer.normalize("send a message via wa")
        assertTrue(result.normalizedText.contains("WhatsApp"))
    }

    @Test
    fun `tg is normalized to Telegram`() {
        val result = GoalNormalizer.normalize("launch tg")
        assertTrue(result.normalizedText.contains("Telegram"))
    }

    @Test
    fun `calc is normalized to Calculator`() {
        val result = GoalNormalizer.normalize("open calc")
        assertTrue(result.normalizedText.contains("Calculator"))
    }

    @Test
    fun `unknown app name is left unchanged`() {
        val result = GoalNormalizer.normalize("open MyCustomApp")
        assertTrue(result.normalizedText.contains("MyCustomApp"))
    }

    // ── UI target normalization ───────────────────────────────────────────────

    @Test
    fun `btn is normalized to button`() {
        val result = GoalNormalizer.normalize("tap the submit btn")
        assertTrue(result.normalizedText.contains("button"))
        assertFalse(result.normalizedText.contains(" btn"))
    }

    @Test
    fun `navbar is normalized to navigation bar`() {
        val result = GoalNormalizer.normalize("tap the navbar")
        assertTrue(result.normalizedText.lowercase().contains("navigation bar"))
    }

    @Test
    fun `fab is normalized to floating action button`() {
        val result = GoalNormalizer.normalize("tap the fab")
        assertTrue(result.normalizedText.lowercase().contains("floating action button"))
    }

    // ── Constraint extraction ─────────────────────────────────────────────────

    @Test
    fun `extracts 'without ads' constraint`() {
        val result = GoalNormalizer.normalize("play the video without ads")
        assertTrue(
            result.extractedConstraints.any { it.contains("ads", ignoreCase = true) },
            "Expected 'ads' in constraints but got ${result.extractedConstraints}"
        )
    }

    @Test
    fun `extracts 'using wifi only' constraint`() {
        val result = GoalNormalizer.normalize("download the file using wifi only")
        assertTrue(
            result.extractedConstraints.any { it.contains("wifi", ignoreCase = true) },
            "Expected 'wifi' in constraints but got ${result.extractedConstraints}"
        )
    }

    @Test
    fun `no constraint in plain instruction leaves constraints empty`() {
        val result = GoalNormalizer.normalize("tap the login button")
        assertTrue(result.extractedConstraints.isEmpty())
    }

    // ── Whitespace / punctuation cleanup ─────────────────────────────────────

    @Test
    fun `collapses multiple spaces`() {
        val result = GoalNormalizer.normalize("open   Settings")
        assertFalse(result.normalizedText.contains("  "))
    }

    @Test
    fun `trailing period is removed`() {
        val result = GoalNormalizer.normalize("open Settings.")
        assertFalse(result.normalizedText.endsWith("."))
    }

    // ── No-op inputs ──────────────────────────────────────────────────────────

    @Test
    fun `already normalized text is returned unchanged`() {
        val text = "open Settings"
        val result = GoalNormalizer.normalize(text)
        assertEquals(text, result.normalizedText)
    }

    @Test
    fun `combined: please launch fb without ads`() {
        val result = GoalNormalizer.normalize("please launch fb without ads")
        assertFalse(result.normalizedText.lowercase().startsWith("please"))
        assertTrue(result.normalizedText.contains("open", ignoreCase = true))
        assertTrue(result.normalizedText.contains("Facebook"))
        assertTrue(
            result.extractedConstraints.any { it.contains("ads", ignoreCase = true) }
        )
    }
}
