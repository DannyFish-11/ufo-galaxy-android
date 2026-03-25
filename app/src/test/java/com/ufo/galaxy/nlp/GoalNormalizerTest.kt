package com.ufo.galaxy.nlp

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [GoalNormalizer].
 *
 * Verifies whitespace cleanup, filler removal, constraint extraction, action verb
 * normalization, and app name alias resolution individually and in combination.
 */
class GoalNormalizerTest {

    private lateinit var normalizer: GoalNormalizer

    @Before
    fun setUp() {
        normalizer = GoalNormalizer()
    }

    // ── Original text preservation ────────────────────────────────────────────

    @Test
    fun `original field is always unmodified`() {
        val raw = "  Please open WeChat  "
        val result = normalizer.normalize(raw)
        assertEquals(raw, result.original)
    }

    // ── Whitespace cleanup ────────────────────────────────────────────────────

    @Test
    fun `leading and trailing whitespace is trimmed`() {
        val result = normalizer.normalize("  open settings  ")
        assertFalse(result.normalized.startsWith(" "))
        assertFalse(result.normalized.endsWith(" "))
    }

    @Test
    fun `internal whitespace runs are collapsed`() {
        val result = normalizer.normalize("open   settings")
        assertFalse(result.normalized.contains("  "))
    }

    // ── Lowercase ─────────────────────────────────────────────────────────────

    @Test
    fun `output is lowercased`() {
        val result = normalizer.normalize("Open Settings")
        assertEquals(result.normalized, result.normalized.lowercase())
    }

    // ── Trailing punctuation ──────────────────────────────────────────────────

    @Test
    fun `trailing period is stripped`() {
        val result = normalizer.normalize("open settings.")
        assertFalse(result.normalized.endsWith("."))
    }

    @Test
    fun `trailing comma is stripped`() {
        val result = normalizer.normalize("open settings,")
        assertFalse(result.normalized.endsWith(","))
    }

    @Test
    fun `trailing exclamation mark is stripped`() {
        val result = normalizer.normalize("open settings!")
        assertFalse(result.normalized.endsWith("!"))
    }

    // ── Polite filler removal ─────────────────────────────────────────────────

    @Test
    fun `please prefix is removed`() {
        val result = normalizer.normalize("please open settings")
        assertFalse(result.normalized.startsWith("please"))
        assertTrue(result.normalized.contains("open"))
    }

    @Test
    fun `could you please prefix is removed`() {
        val result = normalizer.normalize("could you please open settings")
        assertFalse(result.normalized.startsWith("could"))
        assertTrue(result.normalized.contains("open"))
    }

    @Test
    fun `can you prefix is removed`() {
        val result = normalizer.normalize("can you open the camera")
        assertFalse(result.normalized.startsWith("can"))
        assertTrue(result.normalized.contains("open"))
    }

    @Test
    fun `i want to prefix is removed`() {
        val result = normalizer.normalize("i want to open settings")
        assertFalse(result.normalized.startsWith("i want"))
        assertTrue(result.normalized.contains("open"))
    }

    @Test
    fun `i need to prefix is removed`() {
        val result = normalizer.normalize("i need to check the alarm")
        assertFalse(result.normalized.startsWith("i need"))
    }

    @Test
    fun `i would like to prefix is removed`() {
        val result = normalizer.normalize("i would like to open wechat")
        assertFalse(result.normalized.startsWith("i would"))
        assertTrue(result.normalized.contains("open"))
    }

    // ── Constraint extraction ─────────────────────────────────────────────────

    @Test
    fun `within N minutes is extracted as constraint`() {
        val result = normalizer.normalize("send a message within 5 minutes")
        assertTrue(result.constraints.any { it.contains("within") && it.contains("5") && it.contains("minute") })
        assertFalse(result.normalized.contains("within 5 minutes", ignoreCase = true))
    }

    @Test
    fun `at most N times is extracted as constraint`() {
        val result = normalizer.normalize("tap the button at most 3 times")
        assertTrue(result.constraints.any { it.contains("at most") && it.contains("3") })
        assertFalse(result.normalized.contains("at most 3 times", ignoreCase = true))
    }

    @Test
    fun `silently is extracted as constraint`() {
        val result = normalizer.normalize("open music silently")
        assertTrue(result.constraints.contains("silently"))
        assertFalse(result.normalized.contains("silently", ignoreCase = true))
    }

    @Test
    fun `no constraints returns empty list`() {
        val result = normalizer.normalize("open settings")
        assertTrue(result.constraints.isEmpty())
    }

    // ── Action verb normalization ─────────────────────────────────────────────

    @Test
    fun `launch is normalized to open`() {
        val result = normalizer.normalize("launch wechat")
        assertTrue(result.normalized.startsWith("open"))
    }

    @Test
    fun `start is normalized to open`() {
        val result = normalizer.normalize("start settings")
        assertTrue(result.normalized.startsWith("open"))
    }

    @Test
    fun `go home is normalized to home`() {
        val result = normalizer.normalize("go home")
        assertEquals("home", result.normalized)
    }

    @Test
    fun `go back is normalized to back`() {
        val result = normalizer.normalize("go back")
        assertEquals("back", result.normalized)
    }

    @Test
    fun `click is normalized to tap`() {
        val result = normalizer.normalize("click the login button")
        assertTrue(result.normalized.startsWith("tap"))
    }

    @Test
    fun `swipe is normalized to scroll`() {
        val result = normalizer.normalize("swipe down the feed")
        assertTrue(result.normalized.startsWith("scroll"))
    }

    @Test
    fun `return home is normalized to home action`() {
        val result = normalizer.normalize("return home")
        assertEquals("home", result.normalized)
    }

    // ── App name alias resolution ─────────────────────────────────────────────

    @Test
    fun `WeChat is resolved to wechat`() {
        val result = normalizer.normalize("open WeChat")
        assertTrue(result.normalized.contains("wechat"))
    }

    @Test
    fun `alarm is resolved to clock`() {
        val result = normalizer.normalize("open alarm")
        assertTrue(result.normalized.contains("clock"))
    }

    @Test
    fun `settings alias is resolved`() {
        val result = normalizer.normalize("open setting")
        assertTrue(result.normalized.contains("settings"))
    }

    @Test
    fun `camera alias is preserved`() {
        val result = normalizer.normalize("open the camera")
        assertTrue(result.normalized.contains("camera"))
    }

    // ── Combined scenarios ────────────────────────────────────────────────────

    @Test
    fun `please launch WeChat normalizes to open wechat`() {
        val result = normalizer.normalize("please launch WeChat")
        assertEquals("open wechat", result.normalized)
        assertEquals("please launch WeChat", result.original)
    }

    @Test
    fun `could you please open alarm normalizes to open clock`() {
        val result = normalizer.normalize("could you please open the alarm")
        assertTrue(result.normalized.contains("clock"))
        assertFalse(result.normalized.startsWith("could"))
    }

    @Test
    fun `Chinese home command resolves to home`() {
        val result = normalizer.normalize("返回桌面")
        assertEquals("home", result.normalized)
    }

    @Test
    fun `Chinese WeChat name resolves to wechat`() {
        val result = normalizer.normalize("open 微信")
        assertTrue(result.normalized.contains("wechat"))
    }

    @Test
    fun `filler plus constraint plus app alias all handled together`() {
        val result = normalizer.normalize("please launch WeChat within 2 minutes")
        assertEquals("open wechat", result.normalized)
        assertTrue(result.constraints.any { it.contains("within") && it.contains("2") })
        assertEquals("please launch WeChat within 2 minutes", result.original)
    }

    @Test
    fun `empty string produces empty normalized`() {
        val result = normalizer.normalize("")
        assertEquals("", result.normalized)
        assertTrue(result.constraints.isEmpty())
    }

    @Test
    fun `whitespace only string produces empty normalized`() {
        val result = normalizer.normalize("   ")
        assertEquals("", result.normalized)
    }

    // ── Constraint deduplication when merged ─────────────────────────────────

    @Test
    fun `constraints list is not empty when constraint phrase present`() {
        val result = normalizer.normalize("do something quietly")
        assertFalse(result.constraints.isEmpty())
    }
}
