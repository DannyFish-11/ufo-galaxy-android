package com.ufo.galaxy.inference

import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * `llama-server` 是本 App 亲自 exec、跑在自己 UID 下的**可执行文件** —— 它至少要有
 * 模型权重那样的供给纪律。
 *
 * 修复前的状态是反的：两个 `.gguf` 权重的 SHA-256 早已构建期钉死
 * （[com.ufo.galaxy.model.ModelAssetManager]），而这个二进制**没有任何校验**就直接
 * `ProcessBuilder(...).start()`。数据管得比可执行文件严。
 *
 * 二进制不在仓内、也无法在 CI 里构建（见 `app/src/main/jniLibs/README.md`），只能人工
 * 供给到 `files/bin/llama-server` —— 正因为如此，"这个文件是不是我以为的那个"才更需要
 * 可校验。
 */
class LlamaServerIntegrityTest {

    @get:Rule
    val tmpFolder = TemporaryFolder()

    private class FakeProcess : LlamaServerController.ManagedProcess {
        override fun isAlive() = true
        override fun destroy() {}
    }

    private class CountingLauncher : LlamaServerController.ProcessLauncher {
        var launches = 0
        override fun launch(command: List<String>): LlamaServerController.ManagedProcess {
            launches++
            return FakeProcess()
        }
    }

    private fun sha256(text: String): String {
        val d = MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
        return d.joinToString("") { "%02x".format(it) }
    }

    private fun controller(
        binaryContent: String,
        expected: String?,
        launcher: LlamaServerController.ProcessLauncher
    ): LlamaServerController {
        val dir = tmpFolder.newFolder()
        val binary = File(dir, "llama-server").apply { writeText(binaryContent); setExecutable(true) }
        val model = File(dir, "model.gguf").apply { writeText("weights") }
        val mmproj = File(dir, "mmproj.gguf").apply { writeText("proj") }
        return LlamaServerController(
            binaryPath = binary.absolutePath,
            modelPath = model.absolutePath,
            mmprojPath = mmproj.absolutePath,
            launcher = launcher,
            expectedSha256 = expected
        )
    }

    @Test
    fun `a matching digest launches`() {
        val launcher = CountingLauncher()
        val outcome = controller("elf-bytes", sha256("elf-bytes"), launcher).ensureRunning()
        assertTrue("摘要相符时应正常启动，实际 $outcome",
            outcome is LlamaServerController.StartOutcome.Running)
        assertEquals(1, launcher.launches)
    }

    @Test
    fun `a mismatching digest refuses to execute`() {
        val launcher = CountingLauncher()
        val outcome = controller("tampered", sha256("elf-bytes"), launcher).ensureRunning()
        assertTrue(
            "摘要不符必须**拒绝执行**，而不是降级或将就，实际 $outcome",
            outcome is LlamaServerController.StartOutcome.IntegrityFailed,
        )
        assertEquals(
            "拒绝就是拒绝 —— 不许有任何一次 exec 溜过去",
            0, launcher.launches,
        )
    }

    @Test
    fun `digest comparison is case insensitive on the pinned value`() {
        val launcher = CountingLauncher()
        val outcome = controller("elf-bytes", sha256("elf-bytes").uppercase(), launcher).ensureRunning()
        assertTrue("大写十六进制也应识别，实际 $outcome",
            outcome is LlamaServerController.StartOutcome.Running)
    }

    @Test
    fun `an unpinned build still launches - it just does not pretend to have verified`() {
        // 与权重那边"留空 = 显式承认走 TOFU"的口径一致：不校验，但也不假装校验过
        // （日志里记 integrity=unpinned）。这样加校验不会让任何现有部署突然起不来。
        listOf(null, "", "   ").forEach { unpinned ->
            val launcher = CountingLauncher()
            val outcome = controller("elf-bytes", unpinned, launcher).ensureRunning()
            assertTrue("未钉死('$unpinned')时应照常启动，实际 $outcome",
                outcome is LlamaServerController.StartOutcome.Running)
            assertEquals(1, launcher.launches)
        }
    }

    @Test
    fun `sha256Of matches the JDK digest`() {
        val f = tmpFolder.newFile().apply { writeText("hello llama") }
        assertEquals(sha256("hello llama"), LlamaServerController.sha256Of(f))
    }

    @Test
    fun `sha256Of survives a file larger than the read buffer`() {
        // 摘要是分块读的；一次读不完的文件必须和一次性读的结果一致，
        // 否则大二进制（llama-server 通常几十 MB）会算出一个和谁都对不上的摘要。
        val big = buildString { repeat(200_000) { append("abcdefghij") } }
        val f = tmpFolder.newFile().apply { writeText(big) }
        assertEquals(sha256(big), LlamaServerController.sha256Of(f))
    }

    @Test
    fun `missing binary is reported as not provisioned, not as an integrity failure`() {
        // 两者要分得开：一个是"还没供给"（正常，走降级），一个是"供给的东西不对"（拒绝）。
        val dir = tmpFolder.newFolder()
        val model = File(dir, "model.gguf").apply { writeText("weights") }
        val mmproj = File(dir, "mmproj.gguf").apply { writeText("proj") }
        val outcome = LlamaServerController(
            binaryPath = File(dir, "absent").absolutePath,
            modelPath = model.absolutePath,
            mmprojPath = mmproj.absolutePath,
            launcher = CountingLauncher(),
            expectedSha256 = sha256("elf-bytes")
        ).ensureRunning()
        assertTrue("二进制没供给应报 NotProvisioned，实际 $outcome",
            outcome is LlamaServerController.StartOutcome.NotProvisioned)
    }
}
