package com.ufo.galaxy.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 模型摘要**来源**的契约测试（三仓-8）。
 *
 * 背景：`VLM_SHA256` / `VLM_MMPROJ_SHA256` 长期硬编码为 `null`，运行时走
 * trust-on-first-use —— 首次下载什么就信什么，算出摘要落盘，此后强制校验。
 *
 * TOFU 能防"下载之后被篡改或损坏"，**防不住"首次下载时就已经被投毒"**：
 * 毒化的摘要一旦被持久化，之后每一次校验都会"通过"。
 *
 * 问题在于这个区别原本是**不可见**的 —— `effectiveChecksum()` 无论钉死还是
 * TOFU 都返回一个非空字符串，看不出差别。本测试锁住新增的
 * [ModelAssetManager.ChecksumProvenance]：让"这个模型凭什么被信任"成为一个
 * 可查询、可上报的事实。
 *
 * 注：`ModelAssetManager` 接受普通 `File` 作为存储根，因此可在纯 JVM 单测里
 * 构造，无需 Robolectric / instrumentation。
 */
class ModelChecksumProvenanceTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun manager(overrides: Map<String, String?> = emptyMap()) =
        ModelAssetManager(tmp.newFolder("models-${System.nanoTime()}"), overrides)

    // ── 无摘要 ──────────────────────────────────────────────────────────────

    @Test
    fun `fresh install with no download reports NONE`() {
        val m = manager()
        assertEquals(
            ModelAssetManager.ChecksumProvenance.NONE,
            m.checksumProvenance(ModelAssetManager.MODEL_ID_VLM)
        )
        assertEquals(null, m.effectiveChecksum(ModelAssetManager.MODEL_ID_VLM))
    }

    // ── override ───────────────────────────────────────────────────────────

    @Test
    fun `constructor override reports OVERRIDE`() {
        val digest = "a".repeat(64)
        val m = manager(mapOf(ModelAssetManager.MODEL_ID_VLM to digest))
        assertEquals(
            ModelAssetManager.ChecksumProvenance.OVERRIDE,
            m.checksumProvenance(ModelAssetManager.MODEL_ID_VLM)
        )
        assertEquals(digest, m.effectiveChecksum(ModelAssetManager.MODEL_ID_VLM))
    }

    // ── TOFU ───────────────────────────────────────────────────────────────

    @Test
    fun `checksum learned from first download reports TRUST_ON_FIRST_USE not PINNED`() {
        val m = manager()
        val dir = m.modelsDir
        java.io.File(dir, ModelAssetManager.VLM_FILE).writeText("pretend weights")

        val computed = m.persistComputedChecksum(ModelAssetManager.MODEL_ID_VLM)
        assertTrue("首次下载后应当算出并落盘摘要", computed != null)

        // 关键断言：摘要非空 ≠ 已钉死。这正是修复前不可见的区别。
        assertEquals(
            ModelAssetManager.ChecksumProvenance.TRUST_ON_FIRST_USE,
            m.checksumProvenance(ModelAssetManager.MODEL_ID_VLM)
        )
    }

    @Test
    fun `effectiveChecksum alone cannot distinguish TOFU from pinned`() {
        // 用文档形式钉住"为什么需要 provenance"：两种情况下 effectiveChecksum
        // 都非空，只有 provenance 能区分。将来若有人想删掉 provenance，
        // 这条会提醒他删掉的是什么。
        val m = manager()
        java.io.File(m.modelsDir, ModelAssetManager.VLM_FILE).writeText("x")
        m.persistComputedChecksum(ModelAssetManager.MODEL_ID_VLM)

        val tofuChecksum = m.effectiveChecksum(ModelAssetManager.MODEL_ID_VLM)
        val pinned = manager(mapOf(ModelAssetManager.MODEL_ID_VLM to "b".repeat(64)))
        val pinnedChecksum = pinned.effectiveChecksum(ModelAssetManager.MODEL_ID_VLM)

        assertTrue("两者都非空 —— 单看摘要区分不出信任等级", tofuChecksum != null && pinnedChecksum != null)
        assertTrue(
            "但 provenance 能区分",
            m.checksumProvenance(ModelAssetManager.MODEL_ID_VLM) !=
                pinned.checksumProvenance(ModelAssetManager.MODEL_ID_VLM)
        )
    }

    // ── 汇总信号（供 DEVICE_STATE_SNAPSHOT 上报）─────────────────────────────

    @Test
    fun `hasUnpinnedModelChecksum is true when nothing is pinned`() {
        assertTrue(manager().hasUnpinnedModelChecksum())
    }

    @Test
    fun `hasUnpinnedModelChecksum is false only when every model is pinned or overridden`() {
        val m = manager(
            mapOf(
                ModelAssetManager.MODEL_ID_VLM to "c".repeat(64),
                ModelAssetManager.MODEL_ID_VLM_MMPROJ to "d".repeat(64),
            )
        )
        assertFalse("两个模型都 override 后不应再报 unpinned", m.hasUnpinnedModelChecksum())
    }

    @Test
    fun `partial pinning still reports unpinned`() {
        val m = manager(mapOf(ModelAssetManager.MODEL_ID_VLM to "e".repeat(64)))
        assertTrue("只钉死一个不算 —— 另一个仍是弱环", m.hasUnpinnedModelChecksum())
    }

    @Test
    fun `provenance snapshot covers every registered model`() {
        val snap = manager().checksumProvenanceSnapshot()
        assertTrue(snap.containsKey(ModelAssetManager.MODEL_ID_VLM))
        assertTrue(snap.containsKey(ModelAssetManager.MODEL_ID_VLM_MMPROJ))
        assertEquals(ModelAssetManager.ChecksumProvenance.NONE.name, snap[ModelAssetManager.MODEL_ID_VLM])
    }
}
