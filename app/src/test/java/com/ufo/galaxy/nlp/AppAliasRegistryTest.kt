package com.ufo.galaxy.nlp

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for [AppAliasRegistry].
 *
 * Covers:
 * - Known app aliases resolve to the canonical name.
 * - Unknown app names are returned unchanged.
 * - Action alias resolution.
 * - UI target alias resolution.
 * - Case-insensitive lookup for all three registries.
 * - No-op for already-canonical values.
 */
class AppAliasRegistryTest {

    // ── App alias resolution ──────────────────────────────────────────────────

    @Test
    fun `yt resolves to YouTube`() {
        assertEquals("YouTube", AppAliasRegistry.resolveApp("yt"))
    }

    @Test
    fun `fb resolves to Facebook`() {
        assertEquals("Facebook", AppAliasRegistry.resolveApp("fb"))
    }

    @Test
    fun `ig resolves to Instagram`() {
        assertEquals("Instagram", AppAliasRegistry.resolveApp("ig"))
    }

    @Test
    fun `wa resolves to WhatsApp`() {
        assertEquals("WhatsApp", AppAliasRegistry.resolveApp("wa"))
    }

    @Test
    fun `tg resolves to Telegram`() {
        assertEquals("Telegram", AppAliasRegistry.resolveApp("tg"))
    }

    @Test
    fun `calc resolves to Calculator`() {
        assertEquals("Calculator", AppAliasRegistry.resolveApp("calc"))
    }

    @Test
    fun `gmaps resolves to Google Maps`() {
        assertEquals("Google Maps", AppAliasRegistry.resolveApp("gmaps"))
    }

    @Test
    fun `playstore resolves to Play Store`() {
        assertEquals("Play Store", AppAliasRegistry.resolveApp("playstore"))
    }

    @Test
    fun `unknown app name is returned unchanged`() {
        assertEquals("MyCustomApp", AppAliasRegistry.resolveApp("MyCustomApp"))
    }

    @Test
    fun `app resolution is case-insensitive`() {
        assertEquals("YouTube", AppAliasRegistry.resolveApp("YT"))
        assertEquals("YouTube", AppAliasRegistry.resolveApp("Yt"))
        assertEquals("YouTube", AppAliasRegistry.resolveApp("YT"))
    }

    @Test
    fun `resolveApp with extra whitespace trims before lookup`() {
        assertEquals("YouTube", AppAliasRegistry.resolveApp("  yt  "))
    }

    // ── Action alias resolution ───────────────────────────────────────────────

    @Test
    fun `launch resolves to open`() {
        assertEquals("open", AppAliasRegistry.resolveAction("launch"))
    }

    @Test
    fun `start resolves to open`() {
        assertEquals("open", AppAliasRegistry.resolveAction("start"))
    }

    @Test
    fun `go back resolves to back`() {
        assertEquals("back", AppAliasRegistry.resolveAction("go back"))
    }

    @Test
    fun `press back resolves to back`() {
        assertEquals("back", AppAliasRegistry.resolveAction("press back"))
    }

    @Test
    fun `go home resolves to home`() {
        assertEquals("home", AppAliasRegistry.resolveAction("go home"))
    }

    @Test
    fun `click resolves to tap`() {
        assertEquals("tap", AppAliasRegistry.resolveAction("click"))
    }

    @Test
    fun `swipe down resolves to scroll down`() {
        assertEquals("scroll down", AppAliasRegistry.resolveAction("swipe down"))
    }

    @Test
    fun `action resolution is case-insensitive`() {
        assertEquals("open", AppAliasRegistry.resolveAction("LAUNCH"))
        assertEquals("back", AppAliasRegistry.resolveAction("GO BACK"))
    }

    @Test
    fun `unknown action is returned unchanged`() {
        assertEquals("flip", AppAliasRegistry.resolveAction("flip"))
    }

    // ── UI target alias resolution ────────────────────────────────────────────

    @Test
    fun `btn resolves to button`() {
        assertEquals("button", AppAliasRegistry.resolveUiTarget("btn"))
    }

    @Test
    fun `img resolves to image`() {
        assertEquals("image", AppAliasRegistry.resolveUiTarget("img"))
    }

    @Test
    fun `navbar resolves to navigation bar`() {
        assertEquals("navigation bar", AppAliasRegistry.resolveUiTarget("navbar"))
    }

    @Test
    fun `fab resolves to floating action button`() {
        assertEquals("floating action button", AppAliasRegistry.resolveUiTarget("fab"))
    }

    @Test
    fun `hamburger resolves to menu button`() {
        assertEquals("menu button", AppAliasRegistry.resolveUiTarget("hamburger"))
    }

    @Test
    fun `ui target resolution is case-insensitive`() {
        assertEquals("button", AppAliasRegistry.resolveUiTarget("BTN"))
        assertEquals("navigation bar", AppAliasRegistry.resolveUiTarget("NAVBAR"))
    }

    @Test
    fun `unknown ui target is returned unchanged`() {
        assertEquals("weird element", AppAliasRegistry.resolveUiTarget("weird element"))
    }

    // ── Map completeness / no-op for canonical values ─────────────────────────

    @Test
    fun `APP_ALIASES map is non-empty`() {
        assertTrue(AppAliasRegistry.APP_ALIASES.isNotEmpty())
    }

    @Test
    fun `ACTION_ALIASES map is non-empty`() {
        assertTrue(AppAliasRegistry.ACTION_ALIASES.isNotEmpty())
    }

    @Test
    fun `UI_TARGET_ALIASES map is non-empty`() {
        assertTrue(AppAliasRegistry.UI_TARGET_ALIASES.isNotEmpty())
    }

    @Test
    fun `all APP_ALIASES keys are lowercase`() {
        for (key in AppAliasRegistry.APP_ALIASES.keys) {
            assertEquals("Key '$key' should be lowercase", key, key.lowercase())
        }
    }

    @Test
    fun `all ACTION_ALIASES keys are lowercase`() {
        for (key in AppAliasRegistry.ACTION_ALIASES.keys) {
            assertEquals("Key '$key' should be lowercase", key, key.lowercase())
        }
    }

    @Test
    fun `all UI_TARGET_ALIASES keys are lowercase`() {
        for (key in AppAliasRegistry.UI_TARGET_ALIASES.keys) {
            assertEquals("Key '$key' should be lowercase", key, key.lowercase())
        }
    }
}
