package com.ufo.galaxy.local

import com.ufo.galaxy.agent.NoOpImageScaler
import com.ufo.galaxy.loop.ExecutorBridge
import com.ufo.galaxy.loop.LocalPlanner
import com.ufo.galaxy.loop.LoopController
import com.ufo.galaxy.model.ModelAssetManager
import com.ufo.galaxy.model.ModelDownloader
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * Executes [LocalLoopScenario] instances against the production local-loop pipeline
 * in a deterministic, self-contained fashion.
 *
 * The runner wires together the scenario's fake components into a full
 * [DefaultLocalLoopExecutor] → [LoopController] stack and invokes
 * [DefaultLocalLoopExecutor.execute] via [runBlocking].  No Android framework,
 * network, or disk I/O (beyond the empty model directory) is required.
 *
 * ## Typical test usage
 *
 * ```kotlin
 * @get:Rule val tmpFolder = TemporaryFolder()
 *
 * private val runner by lazy { LocalLoopScenarioRunner(tmpFolder.newFolder("models")) }
 *
 * @Test
 * fun `happy path returns success`() {
 *     val result = runner.run(LocalLoopScenario("happy-path"))
 *     assertEquals(LocalLoopResult.STATUS_SUCCESS, result.status)
 * }
 * ```
 *
 * @param modelsDir Directory used for [ModelAssetManager] and [ModelDownloader].
 *                  An empty temporary directory is sufficient for JVM tests because
 *                  model files are not actually loaded by the fake planner/grounder.
 */
class LocalLoopScenarioRunner(private val modelsDir: File) {

    /**
     * Executes [scenario] end-to-end through the production pipeline and returns
     * the resulting [LocalLoopResult].
     *
     * The runner is stateless between calls; each invocation creates a fresh
     * [LoopController] and [DefaultLocalLoopExecutor] from the scenario's components.
     *
     * @param scenario Fully-configured scenario descriptor.
     * @return The [LocalLoopResult] produced by [DefaultLocalLoopExecutor.execute].
     */
    fun run(scenario: LocalLoopScenario): LocalLoopResult {
        val manager = ModelAssetManager(modelsDir)
        val downloader = ModelDownloader(modelsDir)

        val planner = LocalPlanner(scenario.planner)
        val bridge = ExecutorBridge(
            groundingService = scenario.grounder,
            accessibilityExecutor = scenario.accessibilityExecutor,
            imageScaler = NoOpImageScaler()
        )
        val loopController = LoopController(
            localPlanner = planner,
            executorBridge = bridge,
            screenshotProvider = scenario.screenshotProvider,
            modelAssetManager = manager,
            modelDownloader = downloader,
            maxSteps = scenario.maxSteps,
            maxRetriesPerStep = scenario.maxRetriesPerStep,
            stagnationDetector = scenario.stagnationDetector,
            stepTimeoutMs = scenario.stepTimeoutMs,
            goalTimeoutMs = scenario.goalTimeoutMs
        )
        val executor = DefaultLocalLoopExecutor(
            loopController = loopController,
            readinessProvider = scenario.readinessProvider
        )

        return runBlocking {
            executor.execute(
                LocalLoopOptions(
                    instruction = scenario.instruction,
                    sourceRuntimePosture = scenario.sourceRuntimePosture
                )
            )
        }
    }
}
