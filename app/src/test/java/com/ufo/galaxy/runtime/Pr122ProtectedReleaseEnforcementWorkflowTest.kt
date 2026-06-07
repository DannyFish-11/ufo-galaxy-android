package com.ufo.galaxy.runtime

import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Paths

/**
 * PR-122 Android — Guard rails for protected release enforcement wiring in android-ci workflow.
 */
class Pr122ProtectedReleaseEnforcementWorkflowTest {

    private fun workflowText(): String {
        val path = Paths.get(".github", "workflows", "android-ci.yml")
        return Files.readString(path)
    }

    @Test
    fun `workflow defines protected release dispatch trigger and release tags`() {
        val text = workflowText()
        assertTrue(text.contains("workflow_dispatch:"))
        assertTrue(text.contains("protected_release:"))
        assertTrue(text.contains("tags: [ \"v*\", \"release-*\" ]"))
    }

    @Test
    fun `cross repo evidence status artifact includes structured blocking fields`() {
        val text = workflowText()
        assertTrue(text.contains("\"is_protected_release_path\":"))
        assertTrue(text.contains("\"release_blocked\":"))
        assertTrue(text.contains("\"blocking_condition_count\":"))
        assertTrue(text.contains("\"release_blocking_conditions\":"))
    }

    @Test
    fun `workflow includes protected release enforcement job and ci gate dependency`() {
        val text = workflowText()
        assertTrue(text.contains("protected-release-enforcement-gate:"))
        assertTrue(text.contains("Enforce structured blocking conditions on protected release paths"))
        assertTrue(text.contains("needs: [all-unit-tests, lint, cross-repo-compatibility-guard, cross-repo-evidence-status, protected-release-enforcement-gate]"))
        assertTrue(text.contains("\"${'$'}{{ needs.protected-release-enforcement-gate.result }}\""))
    }
}
