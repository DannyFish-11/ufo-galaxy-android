package com.ufo.galaxy.inference

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalPlannerServiceAuthorityTest {

    @Test
    fun `classifyAuthority defaults to local execution subdecision`() {
        val decision = LocalPlannerService.classifyAuthority(emptyList())

        assertEquals(
            LocalPlannerService.AuthorityBoundary.LOCAL_EXECUTION_SUBDECISION,
            decision.boundary
        )
        assertFalse(decision.requiresCenterRevalidation)
    }

    @Test
    fun `classifyAuthority returns center authority required when v2 constraint is present`() {
        val decision = LocalPlannerService.classifyAuthority(
            listOf(LocalPlannerService.CONSTRAINT_V2_AUTHORITY_ONLY)
        )

        assertEquals(
            LocalPlannerService.AuthorityBoundary.CENTER_AUTHORITY_REQUIRED,
            decision.boundary
        )
        assertTrue(decision.requiresCenterRevalidation)
    }

    @Test
    fun `classifyAuthority returns suggestion only when suggestion constraint is present`() {
        val decision = LocalPlannerService.classifyAuthority(
            listOf(LocalPlannerService.CONSTRAINT_LOCAL_PLANNER_SUGGESTION_ONLY)
        )

        assertEquals(
            LocalPlannerService.AuthorityBoundary.LOCAL_SUGGESTION_ONLY,
            decision.boundary
        )
        assertTrue(decision.requiresCenterRevalidation)
    }
}
