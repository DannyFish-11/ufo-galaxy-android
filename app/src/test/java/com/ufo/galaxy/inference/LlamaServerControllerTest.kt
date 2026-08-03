package com.ufo.galaxy.inference

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 本地 llama-server 进程控制器的生命周期语义测试:进程创建走 fake launcher,
 * 不真正拉起任何进程。
 */
class LlamaServerControllerTest {

    @get:Rule
    val tmpFolder = TemporaryFolder()

    private class FakeProcess : LlamaServerController.ManagedProcess {
        var alive = true
        var destroyed = false
        override fun isAlive(): Boolean = alive
        override fun destroy() { destroyed = true; alive = false }
    }

    private class RecordingLauncher(
        private val process: FakeProcess = FakeProcess(),
        private val throwOnLaunch: Boolean = false
    ) : LlamaServerController.ProcessLauncher {
        val commands = mutableListOf<List<String>>()
        val lastProcess: FakeProcess get() = process
        override fun launch(command: List<String>): LlamaServerController.ManagedProcess {
            commands.add(command)
            if (throwOnLaunch) throw IllegalStateException("spawn refused")
            return process
        }
    }

    private fun provisioned(launcher: LlamaServerController.ProcessLauncher): LlamaServerController {
        val dir = tmpFolder.newFolder()
        val binary = File(dir, "llama-server").apply { writeText("elf"); setExecutable(true) }
        val model = File(dir, "model.gguf").apply { writeText("weights") }
        val mmproj = File(dir, "mmproj.gguf").apply { writeText("proj") }
        return LlamaServerController(
            binaryPath = binary.absolutePath,
            modelPath = model.absolutePath,
            mmprojPath = mmproj.absolutePath,
            launcher = launcher
        )
    }

    @Test
    fun `ensureRunning launches with model mmproj port and context args`() {
        val launcher = RecordingLauncher()
        val controller = provisioned(launcher)

        val outcome = controller.ensureRunning()

        assertTrue(outcome is LlamaServerController.StartOutcome.Running)
        assertTrue(controller.isRunning())
        val cmd = launcher.commands.single()
        assertTrue("-m 必须携带 LLM 权重路径", cmd.containsAll(listOf("-m")))
        assertTrue("--mmproj 必须携带视觉投影路径(旧栈缺它导致模型从未看见屏幕)",
            cmd.contains("--mmproj"))
        assertEquals("mmproj 参数值必须紧跟旗标",
            true, cmd[cmd.indexOf("--mmproj") + 1].endsWith("mmproj.gguf"))
        assertTrue(cmd.contains(LlamaServerController.DEFAULT_PORT.toString()))
        assertTrue(cmd.contains(LlamaServerController.DEFAULT_CONTEXT_SIZE.toString()))
    }

    @Test
    fun `ensureRunning is idempotent while process alive`() {
        val launcher = RecordingLauncher()
        val controller = provisioned(launcher)

        controller.ensureRunning()
        controller.ensureRunning()

        assertEquals("存活期间不得重复拉起进程", 1, launcher.commands.size)
    }

    @Test
    fun `missing binary yields NotProvisioned without touching launcher`() {
        val launcher = RecordingLauncher()
        val dir = tmpFolder.newFolder()
        val model = File(dir, "model.gguf").apply { writeText("w") }
        val mmproj = File(dir, "mmproj.gguf").apply { writeText("p") }
        val controller = LlamaServerController(
            binaryPath = File(dir, "absent-binary").absolutePath,
            modelPath = model.absolutePath,
            mmprojPath = mmproj.absolutePath,
            launcher = launcher
        )

        val outcome = controller.ensureRunning()

        assertTrue(outcome is LlamaServerController.StartOutcome.NotProvisioned)
        assertTrue(launcher.commands.isEmpty())
        assertFalse(controller.isRunning())
    }

    @Test
    fun `missing mmproj yields ModelsMissing - refuses text-only server`() {
        val launcher = RecordingLauncher()
        val dir = tmpFolder.newFolder()
        val binary = File(dir, "llama-server").apply { writeText("elf"); setExecutable(true) }
        val model = File(dir, "model.gguf").apply { writeText("w") }
        val controller = LlamaServerController(
            binaryPath = binary.absolutePath,
            modelPath = model.absolutePath,
            mmprojPath = File(dir, "absent-mmproj.gguf").absolutePath,
            launcher = launcher
        )

        val outcome = controller.ensureRunning()

        assertTrue("缺 mmproj 必须拒起纯文本服务(旧栈静默故障形态)",
            outcome is LlamaServerController.StartOutcome.ModelsMissing)
        assertTrue(launcher.commands.isEmpty())
    }

    @Test
    fun `launch exception maps to Failed outcome`() {
        val launcher = RecordingLauncher(throwOnLaunch = true)
        val controller = provisioned(launcher)

        val outcome = controller.ensureRunning()

        assertTrue(outcome is LlamaServerController.StartOutcome.Failed)
        assertFalse(controller.isRunning())
    }

    @Test
    fun `stop destroys process and allows relaunch`() {
        val launcher = RecordingLauncher()
        val controller = provisioned(launcher)
        controller.ensureRunning()

        controller.stop()

        assertTrue(launcher.lastProcess.destroyed)
        assertFalse(controller.isRunning())
        controller.ensureRunning()
        assertEquals("stop 后应允许重新拉起", 2, launcher.commands.size)
    }
}
