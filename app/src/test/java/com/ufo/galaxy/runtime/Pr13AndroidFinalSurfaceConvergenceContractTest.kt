package com.ufo.galaxy.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Pr13AndroidFinalSurfaceConvergenceContractTest {

    @Test
    fun `required canonical main-chain modules include runtime and execution anchors`() {
        val modules = AndroidFinalSurfaceConvergenceContract.requiredCanonicalMainChainModules
        assertTrue(modules.contains("RuntimeController"))
        assertTrue(modules.contains("GalaxyConnectionService"))
        assertTrue(modules.contains("GalaxyWebSocketClient"))
        assertTrue(modules.contains("LocalExecutionModeGate"))
        assertTrue(modules.contains("LocalLoopExecutor"))
        assertTrue(modules.contains("AutonomousExecutionPipeline"))
    }

    @Test
    fun `ui diagnostics and product-facing layers are consumption-only`() {
        val consumptionEntries = AndroidFinalSurfaceConvergenceContract.entries
            .filter {
                it.layerClass != AndroidFinalSurfaceConvergenceContract.LayerClass.RUNTIME_AUTHORITY_CANONICAL
            }
        assertTrue(consumptionEntries.isNotEmpty())
        assertTrue(consumptionEntries.none { it.reassemblesAuthorityTruth })
        assertTrue(consumptionEntries.none { it.reassemblesAuthorityState })
        assertTrue(consumptionEntries.none { it.reassemblesAuthorityDispatch })
        assertTrue(AndroidFinalSurfaceConvergenceContract.validateNoAuthorityReassemblyInConsumptionSurfaces())
    }

    @Test
    fun `contract has explicit V2 alignment for every final surface boundary`() {
        AndroidFinalSurfaceConvergenceContract.entries.forEach {
            assertNotNull("v2AlignmentSurface must be present: ${it.surfaceId}", it.v2AlignmentSurface)
            assertTrue(it.v2AlignmentSurface!!.isNotBlank())
        }
    }

    @Test
    fun `query helpers return stable boundary entries`() {
        val productEntry = AndroidFinalSurfaceConvergenceContract.forId(
            "android-ui-visible-product-facing-composition"
        )
        assertNotNull(productEntry)
        assertEquals(
            AndroidFinalSurfaceConvergenceContract.LayerClass.PRODUCT_FACING_COMPOSITION_ONLY,
            productEntry!!.layerClass
        )

        val runtimeVisible = AndroidFinalSurfaceConvergenceContract.byLayerClass(
            AndroidFinalSurfaceConvergenceContract.LayerClass.RUNTIME_VISIBLE_CONSUMPTION_ONLY
        )
        assertEquals(1, runtimeVisible.size)

        val uiVisible = AndroidFinalSurfaceConvergenceContract.byLayerClass(
            AndroidFinalSurfaceConvergenceContract.LayerClass.UI_VISIBLE_CONSUMPTION_ONLY
        )
        assertEquals(1, uiVisible.size)
    }

    @Test
    fun `convergence invariants are present and introduced PR is fixed`() {
        assertEquals(13, AndroidFinalSurfaceConvergenceContract.INTRODUCED_PR)
        assertTrue(AndroidFinalSurfaceConvergenceContract.DESCRIPTION.isNotBlank())
        assertFalse(AndroidFinalSurfaceConvergenceContract.convergenceInvariants.isEmpty())
        assertTrue(
            AndroidFinalSurfaceConvergenceContract.convergenceInvariants.any {
                it.contains("consume canonical runtime", ignoreCase = true)
            }
        )
    }
}
