package com.ufo.galaxy.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PR-121 — AndroidCanonicalTransportPathBoundaryContract unit tests.
 *
 * Validates that:
 *
 *  1. [AndroidCanonicalTransportPathBoundaryContract.TransportPathClass] has exactly 4 values
 *     with stable wire values.
 *  2. Only [CANONICAL_PRODUCTION] has [isCanonicalProduction] = `true`; all others are `false`.
 *  3. [classifySendPath] correctly classifies canonical vs. compat send paths.
 *  4. [isCanonicalProduction] correctly gates on the transport path class.
 *  5. [V2_TRANSPORT_PATH_ALIGNMENT_MAP] has an entry for every [TransportPathClass] value.
 *  6. [TRANSPORT_PATH_INVARIANTS] has exactly 10 entries with no duplicates.
 *  7. [fromWireValue] returns the correct class or falls back to NOT_APPLICABLE for unknown.
 *  8. Negative proof: COMPAT_LEGACY is not production-equivalent.
 *  9. Negative proof: DIAGNOSTICS_ONLY_PATH is not production-equivalent.
 * 10. Negative proof: NOT_APPLICABLE is not production-equivalent.
 * 11. Closure-bearing payloads may only travel via CANONICAL_PRODUCTION transport (INV-TRP-04).
 * 12. Reconnect and compat path behavior does not alter canonical path classification.
 * 13. [StabilizationBaseline] registers the new surface with correct counts.
 */
class Pr121AndroidCanonicalTransportPathBoundaryContractTest {

    // ══════════════════════════════════════════════════════════════════════════
    // 1. TransportPathClass enum coverage
    // ══════════════════════════════════════════════════════════════════════════

    @Test fun `TransportPathClass has exactly 4 values`() {
        assertEquals(4, AndroidCanonicalTransportPathBoundaryContract.TransportPathClass.values().size)
    }

    @Test fun `CANONICAL_PRODUCTION wireValue is canonical_production`() {
        assertEquals(
            "canonical_production",
            AndroidCanonicalTransportPathBoundaryContract.TransportPathClass.CANONICAL_PRODUCTION.wireValue
        )
    }

    @Test fun `COMPAT_LEGACY wireValue is compat_legacy`() {
        assertEquals(
            "compat_legacy",
            AndroidCanonicalTransportPathBoundaryContract.TransportPathClass.COMPAT_LEGACY.wireValue
        )
    }

    @Test fun `DIAGNOSTICS_ONLY_PATH wireValue is diagnostics_only_path`() {
        assertEquals(
            "diagnostics_only_path",
            AndroidCanonicalTransportPathBoundaryContract.TransportPathClass.DIAGNOSTICS_ONLY_PATH.wireValue
        )
    }

    @Test fun `NOT_APPLICABLE wireValue is not_applicable`() {
        assertEquals(
            "not_applicable",
            AndroidCanonicalTransportPathBoundaryContract.TransportPathClass.NOT_APPLICABLE.wireValue
        )
    }

    @Test fun `all TransportPathClass values have non-blank descriptions`() {
        AndroidCanonicalTransportPathBoundaryContract.TransportPathClass.values().forEach { cls ->
            assertTrue(
                "description must be non-blank for $cls",
                cls.description.isNotBlank()
            )
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 2. isCanonicalProduction: only CANONICAL_PRODUCTION is true
    // ══════════════════════════════════════════════════════════════════════════

    @Test fun `only CANONICAL_PRODUCTION has isCanonicalProduction = true (INV-TRP-01)`() {
        assertTrue(
            AndroidCanonicalTransportPathBoundaryContract.TransportPathClass.CANONICAL_PRODUCTION.isCanonicalProduction
        )
    }

    @Test fun `COMPAT_LEGACY has isCanonicalProduction = false (INV-TRP-02)`() {
        assertFalse(
            "COMPAT_LEGACY MUST NOT be treated as production-equivalent (INV-TRP-02)",
            AndroidCanonicalTransportPathBoundaryContract.TransportPathClass.COMPAT_LEGACY.isCanonicalProduction
        )
    }

    @Test fun `DIAGNOSTICS_ONLY_PATH has isCanonicalProduction = false`() {
        assertFalse(
            AndroidCanonicalTransportPathBoundaryContract.TransportPathClass.DIAGNOSTICS_ONLY_PATH.isCanonicalProduction
        )
    }

    @Test fun `NOT_APPLICABLE has isCanonicalProduction = false`() {
        assertFalse(
            AndroidCanonicalTransportPathBoundaryContract.TransportPathClass.NOT_APPLICABLE.isCanonicalProduction
        )
    }

    @Test fun `exactly 1 TransportPathClass value is canonical production`() {
        val canonicalCount = AndroidCanonicalTransportPathBoundaryContract.TransportPathClass.values()
            .count { it.isCanonicalProduction }
        assertEquals(
            "Exactly one TransportPathClass must be isCanonicalProduction=true (INV-TRP-01)",
            1,
            canonicalCount
        )
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 3. classifySendPath(): correct classification of send path scenarios
    // ══════════════════════════════════════════════════════════════════════════

    @Test fun `classifySendPath canonical sendJson returns CANONICAL_PRODUCTION`() {
        assertEquals(
            AndroidCanonicalTransportPathBoundaryContract.TransportPathClass.CANONICAL_PRODUCTION,
            AndroidCanonicalTransportPathBoundaryContract.classifySendPath(
                isCanonicalSendJson = true,
                isCompatSendAIPMessage = false
            )
        )
    }

    @Test fun `classifySendPath compat sendAIPMessage returns COMPAT_LEGACY`() {
        assertEquals(
            AndroidCanonicalTransportPathBoundaryContract.TransportPathClass.COMPAT_LEGACY,
            AndroidCanonicalTransportPathBoundaryContract.classifySendPath(
                isCanonicalSendJson = false,
                isCompatSendAIPMessage = true
            )
        )
    }

    @Test fun `classifySendPath compat sendAIPMessage that internally calls sendJson returns COMPAT_LEGACY`() {
        // When sendAIPMessage() is the entry point, the path class is COMPAT_LEGACY even though
        // internally it redirects to sendJson(). This is the existing behavior: deprecated callers
        // are COMPAT_LEGACY regardless of internal redirection.
        assertEquals(
            AndroidCanonicalTransportPathBoundaryContract.TransportPathClass.COMPAT_LEGACY,
            AndroidCanonicalTransportPathBoundaryContract.classifySendPath(
                isCanonicalSendJson = true,
                isCompatSendAIPMessage = true
            )
        )
    }

    @Test fun `classifySendPath neither sendJson nor compat returns NOT_APPLICABLE`() {
        assertEquals(
            AndroidCanonicalTransportPathBoundaryContract.TransportPathClass.NOT_APPLICABLE,
            AndroidCanonicalTransportPathBoundaryContract.classifySendPath(
                isCanonicalSendJson = false,
                isCompatSendAIPMessage = false
            )
        )
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 4. isCanonicalProduction() helper
    // ══════════════════════════════════════════════════════════════════════════

    @Test fun `isCanonicalProduction returns true for CANONICAL_PRODUCTION`() {
        assertTrue(
            AndroidCanonicalTransportPathBoundaryContract.isCanonicalProduction(
                AndroidCanonicalTransportPathBoundaryContract.TransportPathClass.CANONICAL_PRODUCTION
            )
        )
    }

    @Test fun `isCanonicalProduction returns false for COMPAT_LEGACY`() {
        assertFalse(
            "COMPAT_LEGACY must not be considered canonical production",
            AndroidCanonicalTransportPathBoundaryContract.isCanonicalProduction(
                AndroidCanonicalTransportPathBoundaryContract.TransportPathClass.COMPAT_LEGACY
            )
        )
    }

    @Test fun `isCanonicalProduction returns false for DIAGNOSTICS_ONLY_PATH`() {
        assertFalse(
            AndroidCanonicalTransportPathBoundaryContract.isCanonicalProduction(
                AndroidCanonicalTransportPathBoundaryContract.TransportPathClass.DIAGNOSTICS_ONLY_PATH
            )
        )
    }

    @Test fun `isCanonicalProduction returns false for NOT_APPLICABLE`() {
        assertFalse(
            AndroidCanonicalTransportPathBoundaryContract.isCanonicalProduction(
                AndroidCanonicalTransportPathBoundaryContract.TransportPathClass.NOT_APPLICABLE
            )
        )
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 5. V2_TRANSPORT_PATH_ALIGNMENT_MAP completeness
    // ══════════════════════════════════════════════════════════════════════════

    @Test fun `V2_TRANSPORT_PATH_ALIGNMENT_MAP has an entry for every TransportPathClass`() {
        AndroidCanonicalTransportPathBoundaryContract.TransportPathClass.values().forEach { cls ->
            assertTrue(
                "V2_TRANSPORT_PATH_ALIGNMENT_MAP must have entry for $cls",
                AndroidCanonicalTransportPathBoundaryContract.V2_TRANSPORT_PATH_ALIGNMENT_MAP.containsKey(cls)
            )
        }
    }

    @Test fun `V2_TRANSPORT_PATH_ALIGNMENT_MAP paths are non-blank`() {
        AndroidCanonicalTransportPathBoundaryContract.V2_TRANSPORT_PATH_ALIGNMENT_MAP.forEach { (cls, path) ->
            assertTrue("V2 alignment path must be non-blank for $cls", path.isNotBlank())
        }
    }

    @Test fun `V2_TRANSPORT_PATH_ALIGNMENT_MAP CANONICAL_PRODUCTION mentions canonical ingress`() {
        val path = AndroidCanonicalTransportPathBoundaryContract.V2_TRANSPORT_PATH_ALIGNMENT_MAP[
            AndroidCanonicalTransportPathBoundaryContract.TransportPathClass.CANONICAL_PRODUCTION
        ] ?: ""
        assertTrue(
            "CANONICAL_PRODUCTION V2 alignment must mention websocket.py or canonical ingress",
            path.contains("websocket") || path.contains("canonical")
        )
    }

    @Test fun `V2_TRANSPORT_PATH_ALIGNMENT_MAP COMPAT_LEGACY is not canonical`() {
        val path = AndroidCanonicalTransportPathBoundaryContract.V2_TRANSPORT_PATH_ALIGNMENT_MAP[
            AndroidCanonicalTransportPathBoundaryContract.TransportPathClass.COMPAT_LEGACY
        ] ?: ""
        assertTrue(
            "COMPAT_LEGACY V2 alignment must indicate non-canonical status",
            path.contains("non-canonical") || path.contains("compat") || path.contains("migration")
        )
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 6. TRANSPORT_PATH_INVARIANTS completeness
    // ══════════════════════════════════════════════════════════════════════════

    @Test fun `TRANSPORT_PATH_INVARIANTS has exactly 10 entries`() {
        assertEquals(10, AndroidCanonicalTransportPathBoundaryContract.TRANSPORT_PATH_INVARIANTS.size)
    }

    @Test fun `all TRANSPORT_PATH_INVARIANTS are non-blank`() {
        AndroidCanonicalTransportPathBoundaryContract.TRANSPORT_PATH_INVARIANTS.forEach { inv ->
            assertTrue("Invariant must be non-blank", inv.isNotBlank())
        }
    }

    @Test fun `TRANSPORT_PATH_INVARIANTS has no duplicates`() {
        val set = AndroidCanonicalTransportPathBoundaryContract.TRANSPORT_PATH_INVARIANTS.toSet()
        assertEquals(
            "TRANSPORT_PATH_INVARIANTS must not contain duplicates",
            AndroidCanonicalTransportPathBoundaryContract.TRANSPORT_PATH_INVARIANTS.size,
            set.size
        )
    }

    @Test fun `TRANSPORT_PATH_INVARIANTS contains INV-TRP-01 (sendJson is canonical)`() {
        assertTrue(
            AndroidCanonicalTransportPathBoundaryContract.TRANSPORT_PATH_INVARIANTS.any { it.contains("INV-TRP-01") }
        )
    }

    @Test fun `TRANSPORT_PATH_INVARIANTS contains INV-TRP-04 (closure via canonical only)`() {
        assertTrue(
            AndroidCanonicalTransportPathBoundaryContract.TRANSPORT_PATH_INVARIANTS.any { it.contains("INV-TRP-04") }
        )
    }

    @Test fun `TRANSPORT_PATH_INVARIANTS contains INV-TRP-08 (cross-device gate is canonical guarantee)`() {
        assertTrue(
            AndroidCanonicalTransportPathBoundaryContract.TRANSPORT_PATH_INVARIANTS.any { it.contains("INV-TRP-08") }
        )
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 7. fromWireValue(): correct lookup and defensive fallback
    // ══════════════════════════════════════════════════════════════════════════

    @Test fun `fromWireValue returns CANONICAL_PRODUCTION for canonical_production`() {
        assertEquals(
            AndroidCanonicalTransportPathBoundaryContract.TransportPathClass.CANONICAL_PRODUCTION,
            AndroidCanonicalTransportPathBoundaryContract.TransportPathClass.fromWireValue("canonical_production")
        )
    }

    @Test fun `fromWireValue returns COMPAT_LEGACY for compat_legacy`() {
        assertEquals(
            AndroidCanonicalTransportPathBoundaryContract.TransportPathClass.COMPAT_LEGACY,
            AndroidCanonicalTransportPathBoundaryContract.TransportPathClass.fromWireValue("compat_legacy")
        )
    }

    @Test fun `fromWireValue returns DIAGNOSTICS_ONLY_PATH for diagnostics_only_path`() {
        assertEquals(
            AndroidCanonicalTransportPathBoundaryContract.TransportPathClass.DIAGNOSTICS_ONLY_PATH,
            AndroidCanonicalTransportPathBoundaryContract.TransportPathClass.fromWireValue("diagnostics_only_path")
        )
    }

    @Test fun `fromWireValue returns NOT_APPLICABLE for not_applicable`() {
        assertEquals(
            AndroidCanonicalTransportPathBoundaryContract.TransportPathClass.NOT_APPLICABLE,
            AndroidCanonicalTransportPathBoundaryContract.TransportPathClass.fromWireValue("not_applicable")
        )
    }

    @Test fun `fromWireValue unknown value falls back to NOT_APPLICABLE (non-canonical default)`() {
        val fallback = AndroidCanonicalTransportPathBoundaryContract.TransportPathClass.fromWireValue(
            "future_unknown_transport_xyz"
        )
        assertEquals(
            "Unknown wire value must fall back to NOT_APPLICABLE (non-canonical default)",
            AndroidCanonicalTransportPathBoundaryContract.TransportPathClass.NOT_APPLICABLE,
            fallback
        )
        assertFalse(
            "Fallback from unknown wire value must not be canonical production",
            fallback.isCanonicalProduction
        )
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 8–10. Negative proof: non-canonical path classes cannot carry closure
    // ══════════════════════════════════════════════════════════════════════════

    @Test fun `COMPAT_LEGACY cannot carry closure-bearing payloads (INV-TRP-03 and INV-TRP-04)`() {
        val cls = AndroidCanonicalTransportPathBoundaryContract.TransportPathClass.COMPAT_LEGACY
        assertFalse(
            "Negative proof: COMPAT_LEGACY transport path MUST NOT be treated as production-equivalent; " +
                "closure-bearing AUTHORITY_RESULT payloads must NOT travel via COMPAT_LEGACY",
            cls.isCanonicalProduction
        )
        assertFalse(
            AndroidCanonicalTransportPathBoundaryContract.isCanonicalProduction(cls)
        )
    }

    @Test fun `DIAGNOSTICS_ONLY_PATH cannot carry closure-bearing payloads`() {
        val cls = AndroidCanonicalTransportPathBoundaryContract.TransportPathClass.DIAGNOSTICS_ONLY_PATH
        assertFalse(
            "Negative proof: DIAGNOSTICS_ONLY_PATH transport semantic MUST NOT carry " +
                "closure-bearing payloads",
            cls.isCanonicalProduction
        )
    }

    @Test fun `NOT_APPLICABLE cannot carry closure-bearing payloads`() {
        val cls = AndroidCanonicalTransportPathBoundaryContract.TransportPathClass.NOT_APPLICABLE
        assertFalse(
            "Negative proof: NOT_APPLICABLE path MUST NOT carry closure-bearing payloads",
            cls.isCanonicalProduction
        )
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 11. Closure-bearing payloads require CANONICAL_PRODUCTION (INV-TRP-04)
    // ══════════════════════════════════════════════════════════════════════════

    @Test fun `gate check: AUTHORITY_RESULT may only travel via CANONICAL_PRODUCTION (INV-TRP-04)`() {
        // Simulate: canCarryAuthorityResult = transportPathClass.isCanonicalProduction
        val nonCanonicalPaths = AndroidCanonicalTransportPathBoundaryContract.TransportPathClass.values()
            .filter { !it.isCanonicalProduction }
        assertTrue("There must be non-canonical paths to test against", nonCanonicalPaths.isNotEmpty())
        nonCanonicalPaths.forEach { cls ->
            assertFalse(
                "INV-TRP-04: AUTHORITY_RESULT MUST NOT travel via $cls",
                AndroidCanonicalTransportPathBoundaryContract.isCanonicalProduction(cls)
            )
        }
        // The one canonical path:
        assertTrue(
            "INV-TRP-04: AUTHORITY_RESULT may travel via CANONICAL_PRODUCTION",
            AndroidCanonicalTransportPathBoundaryContract.isCanonicalProduction(
                AndroidCanonicalTransportPathBoundaryContract.TransportPathClass.CANONICAL_PRODUCTION
            )
        )
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 12. Reconnect and compat internal redirection do not alter canonical path class
    // ══════════════════════════════════════════════════════════════════════════

    @Test fun `classifySendPath canonical sendJson returns CANONICAL_PRODUCTION regardless of reconnect scenario`() {
        // Reconnect scenario: sendJson() is called after a reconnect.
        // The transport path class must remain CANONICAL_PRODUCTION (INV-TRP-10).
        val afterReconnect = AndroidCanonicalTransportPathBoundaryContract.classifySendPath(
            isCanonicalSendJson = true,
            isCompatSendAIPMessage = false
        )
        assertEquals(
            "INV-TRP-10: sendJson() after reconnect must still classify as CANONICAL_PRODUCTION",
            AndroidCanonicalTransportPathBoundaryContract.TransportPathClass.CANONICAL_PRODUCTION,
            afterReconnect
        )
    }

    @Test fun `compat path internal sendJson redirect does not upgrade path class to canonical`() {
        // sendAIPMessage() internally calls sendJson(), but the caller's path class
        // is still COMPAT_LEGACY. The internal redirect does NOT upgrade the path class.
        val compatWithInternalRedirect = AndroidCanonicalTransportPathBoundaryContract.classifySendPath(
            isCanonicalSendJson = true,
            isCompatSendAIPMessage = true
        )
        assertEquals(
            "Internal sendJson redirect from sendAIPMessage() MUST NOT upgrade path to CANONICAL_PRODUCTION; " +
                "the entry point determines the path class",
            AndroidCanonicalTransportPathBoundaryContract.TransportPathClass.COMPAT_LEGACY,
            compatWithInternalRedirect
        )
        assertFalse(
            "Compat path with internal redirect must not be treated as canonical production",
            AndroidCanonicalTransportPathBoundaryContract.isCanonicalProduction(compatWithInternalRedirect)
        )
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 13. StabilizationBaseline registration
    // ══════════════════════════════════════════════════════════════════════════

    @Test fun `StabilizationBaseline registers android-canonical-transport-path-boundary-contract`() {
        assertNotNull(
            StabilizationBaseline.forId("android-canonical-transport-path-boundary-contract")
        )
    }

    @Test fun `android-canonical-transport-path-boundary-contract is CANONICAL_STABLE`() {
        val entry = StabilizationBaseline.forId("android-canonical-transport-path-boundary-contract")
        assertEquals(
            StabilizationBaseline.SurfaceStability.CANONICAL_STABLE,
            entry!!.stability
        )
    }

    @Test fun `android-canonical-transport-path-boundary-contract has EXTEND guidance`() {
        val entry = StabilizationBaseline.forId("android-canonical-transport-path-boundary-contract")
        assertEquals(
            StabilizationBaseline.ExtensionGuidance.EXTEND,
            entry!!.extensionGuidance
        )
    }

    @Test fun `android-canonical-transport-path-boundary-contract introducedPr is 121`() {
        val entry = StabilizationBaseline.forId("android-canonical-transport-path-boundary-contract")
        assertEquals(121, entry!!.introducedPr)
    }

    @Test fun `INTRODUCED_PR constant is 121`() {
        assertEquals(121, AndroidCanonicalTransportPathBoundaryContract.INTRODUCED_PR)
    }

    @Test fun `SCHEMA_VERSION constant is 1`() {
        assertEquals("1", AndroidCanonicalTransportPathBoundaryContract.SCHEMA_VERSION)
    }
}
