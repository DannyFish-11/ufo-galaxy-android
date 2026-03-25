package com.ufo.galaxy.nlp

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for [AppAliasRegistry].
 *
 * Verifies that app name aliases, action verb synonyms, and UI target synonyms
 * resolve consistently to their canonical forms.
 */
class AppAliasRegistryTest {

    // ── App name aliases ──────────────────────────────────────────────────────

    @Test
    fun `resolveAppAlias returns canonical for Chinese WeChat name`() {
        assertEquals("wechat", AppAliasRegistry.resolveAppAlias("微信"))
    }

    @Test
    fun `resolveAppAlias returns canonical for weixin`() {
        assertEquals("wechat", AppAliasRegistry.resolveAppAlias("weixin"))
    }

    @Test
    fun `resolveAppAlias is case-insensitive for wechat`() {
        assertEquals("wechat", AppAliasRegistry.resolveAppAlias("WeChat"))
        assertEquals("wechat", AppAliasRegistry.resolveAppAlias("WECHAT"))
    }

    @Test
    fun `resolveAppAlias returns canonical for Alipay Chinese name`() {
        assertEquals("alipay", AppAliasRegistry.resolveAppAlias("支付宝"))
    }

    @Test
    fun `resolveAppAlias returns canonical for douyin`() {
        assertEquals("tiktok", AppAliasRegistry.resolveAppAlias("douyin"))
    }

    @Test
    fun `resolveAppAlias returns canonical for Chinese Douyin name`() {
        assertEquals("tiktok", AppAliasRegistry.resolveAppAlias("抖音"))
    }

    @Test
    fun `resolveAppAlias returns canonical for clock aliases`() {
        assertEquals("clock", AppAliasRegistry.resolveAppAlias("时钟"))
        assertEquals("clock", AppAliasRegistry.resolveAppAlias("闹钟"))
        assertEquals("clock", AppAliasRegistry.resolveAppAlias("alarm"))
    }

    @Test
    fun `resolveAppAlias returns canonical for settings variants`() {
        assertEquals("settings", AppAliasRegistry.resolveAppAlias("setting"))
        assertEquals("settings", AppAliasRegistry.resolveAppAlias("Settings"))
        assertEquals("settings", AppAliasRegistry.resolveAppAlias("设置"))
    }

    @Test
    fun `resolveAppAlias returns canonical for gallery aliases`() {
        assertEquals("gallery", AppAliasRegistry.resolveAppAlias("相册"))
        assertEquals("gallery", AppAliasRegistry.resolveAppAlias("图库"))
        assertEquals("gallery", AppAliasRegistry.resolveAppAlias("photos"))
    }

    @Test
    fun `resolveAppAlias returns canonical for phone aliases`() {
        assertEquals("phone", AppAliasRegistry.resolveAppAlias("电话"))
        assertEquals("phone", AppAliasRegistry.resolveAppAlias("dialer"))
    }

    @Test
    fun `resolveAppAlias returns original for unknown app name`() {
        assertEquals("spotify", AppAliasRegistry.resolveAppAlias("spotify"))
        assertEquals("unknownapp", AppAliasRegistry.resolveAppAlias("unknownapp"))
    }

    // ── Action verb synonyms ──────────────────────────────────────────────────

    @Test
    fun `resolveActionSynonym maps launch to open`() {
        assertEquals("open", AppAliasRegistry.resolveActionSynonym("launch"))
    }

    @Test
    fun `resolveActionSynonym maps start to open`() {
        assertEquals("open", AppAliasRegistry.resolveActionSynonym("start"))
    }

    @Test
    fun `resolveActionSynonym maps go back to back`() {
        assertEquals("back", AppAliasRegistry.resolveActionSynonym("go back"))
    }

    @Test
    fun `resolveActionSynonym maps go home to home`() {
        assertEquals("home", AppAliasRegistry.resolveActionSynonym("go home"))
    }

    @Test
    fun `resolveActionSynonym maps click to tap`() {
        assertEquals("tap", AppAliasRegistry.resolveActionSynonym("click"))
    }

    @Test
    fun `resolveActionSynonym maps input to type`() {
        assertEquals("type", AppAliasRegistry.resolveActionSynonym("input"))
    }

    @Test
    fun `resolveActionSynonym maps swipe to scroll`() {
        assertEquals("scroll", AppAliasRegistry.resolveActionSynonym("swipe"))
    }

    @Test
    fun `resolveActionSynonym is case-insensitive`() {
        assertEquals("open", AppAliasRegistry.resolveActionSynonym("LAUNCH"))
        assertEquals("tap", AppAliasRegistry.resolveActionSynonym("Click"))
    }

    @Test
    fun `resolveActionSynonym returns original for unknown verb`() {
        assertEquals("unknownverb", AppAliasRegistry.resolveActionSynonym("unknownverb"))
    }

    // ── UI target synonyms ────────────────────────────────────────────────────

    @Test
    fun `resolveUiTargetSynonym maps search bar to search field`() {
        assertEquals("search field", AppAliasRegistry.resolveUiTargetSynonym("search bar"))
    }

    @Test
    fun `resolveUiTargetSynonym maps search box to search field`() {
        assertEquals("search field", AppAliasRegistry.resolveUiTargetSynonym("search box"))
    }

    @Test
    fun `resolveUiTargetSynonym maps ok button to confirm button`() {
        assertEquals("confirm button", AppAliasRegistry.resolveUiTargetSynonym("ok button"))
    }

    @Test
    fun `resolveUiTargetSynonym maps hamburger menu to menu`() {
        assertEquals("menu", AppAliasRegistry.resolveUiTargetSynonym("hamburger menu"))
    }

    @Test
    fun `resolveUiTargetSynonym is case-insensitive`() {
        assertEquals("search field", AppAliasRegistry.resolveUiTargetSynonym("Search Bar"))
    }

    @Test
    fun `resolveUiTargetSynonym returns original for unknown target`() {
        assertEquals("floating button", AppAliasRegistry.resolveUiTargetSynonym("floating button"))
    }
}
