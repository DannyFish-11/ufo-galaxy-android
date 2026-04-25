package com.ufo.galaxy.runtime

import com.ufo.galaxy.network.OfflineTaskQueue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PR-66 / PR-5Android — Continuity recovery and offline durability hardening: acceptance tests.
 *
 * Validates the four acceptance criteria for Android-side continuity recovery hardening:
 *
 *  1. **How Android restores or bounds in-flight participant/runtime work** after
 *     restart/reconnect — via [AndroidRecoveryParticipationOwner.evaluateRecovery].
 *
 *  2. **How stale/duplicate/offline-delayed emissions are suppressed or controlled** —
 *     via [EmittedSignalLedger.replayBounded], [OfflineTaskQueue.discardForDifferentSession],
 *     and [AndroidContinuityIntegration.suppressDuplicateLocalEmit].
 *
 *  3. **That Android continuity/recovery behavior is testable and reviewable** — the
 *     [ContinuityRecoveryDurabilityContract] provides the machine-readable validation
 *     matrix; this test suite provides the concrete test evidence.
 *
 *  4. **How Android remains aligned with V2 canonical authority** during recovery —
 *     demonstrated by [LocalRecoveryDecision.WaitForV2ReplayDecision] and
 *     [LocalRecoveryDecision.RehydrateThenContinue] semantics.
 *
 * ## Test matrix
 *
 * ### ContinuityRecoveryDurabilityContract — structural invariants
 *  - INTRODUCED_PR is 66
 *  - coveredBehaviors has exactly COVERED_BEHAVIOR_COUNT entries
 *  - all coveredBehavior behaviorIds are non-blank
 *  - all coveredBehavior behaviorIds are unique
 *  - all coveredBehavior descriptions are non-blank
 *  - all coveredBehavior coveredBy values are non-blank
 *  - all coveredBehavior testEvidence values are non-blank
 *  - boundedEmissions has exactly BOUNDED_EMISSION_COUNT entries
 *  - all boundedEmission emissionIds are non-blank
 *  - all boundedEmission emissionIds are unique
 *  - all boundedEmission suppressionConditions are non-blank
 *  - deferredItems has exactly DEFERRED_ITEM_COUNT entries
 *  - all deferredItem itemIds are non-blank
 *  - all deferredItem itemIds are unique
 *  - all deferredItem deferralReasons are non-blank
 *
 * ### ContinuityRecoveryDurabilityContract — covered behavior entries
 *  - process_recreation_with_prior_context is covered
 *  - process_recreation_without_prior_context is covered
 *  - transport_reconnect_defers_to_v2 is covered
 *  - receiver_pipeline_rebind_resumes_locally is covered
 *  - duplicate_recovery_suppression is covered
 *  - emit_ledger_terminal_bounding_ack is covered
 *  - emit_ledger_terminal_bounding_progress is covered
 *  - emit_ledger_result_replay_idempotent is covered
 *  - offline_queue_stale_session_discard is covered
 *  - offline_queue_null_tag_preserved is covered
 *  - offline_queue_matching_tag_preserved is covered
 *  - duplicate_signal_emit_suppression is covered
 *  - fresh_attach_no_recovery_context is covered
 *
 * ### ContinuityRecoveryDurabilityContract — bounded emission entries
 *  - ack_after_terminal_result is bounded
 *  - progress_after_terminal_result is bounded
 *  - offline_queue_stale_authority is bounded
 *  - duplicate_signal_cross_execution is bounded
 *  - duplicate_recovery_attempt is bounded
 *  - stale_identity_reception is bounded
 *
 * ### AndroidRecoveryParticipationOwner — restart/reconnect restoration (AC1)
 *  - process recreation with prior context yields RehydrateThenContinue
 *  - RehydrateThenContinue semanticTag is "rehydrate_then_continue"
 *  - RehydrateThenContinue localContext matches the decision snapshot
 *  - process recreation without prior context yields WaitForV2ReplayDecision
 *  - WaitForV2ReplayDecision for process recreation has null durableSessionId
 *  - WaitForV2ReplayDecision semanticTag is "wait_for_v2_replay_decision"
 *  - transport reconnect yields WaitForV2ReplayDecision with durableSessionId
 *  - WaitForV2ReplayDecision for transport reconnect carries durableSessionId from session
 *  - receiver pipeline rebind yields ResumeLocalExecution
 *  - ResumeLocalExecution activeSessionId matches rebind session
 *  - fresh attach yields NoRecoveryContext
 *  - duplicate recovery attempt is suppressed with SuppressDuplicateLocalRecovery
 *
 * ### EmittedSignalLedger — terminal-state bounding (AC2)
 *  - hasTerminalResult is false before any signal is recorded
 *  - hasTerminalResult is false after ACK only
 *  - hasTerminalResult is false after ACK and PROGRESS
 *  - hasTerminalResult is true after RESULT is recorded
 *  - replayBounded for ACK returns signal when no terminal result
 *  - replayBounded for PROGRESS returns signal when no terminal result
 *  - replayBounded for RESULT returns null when no prior recording
 *  - replayBounded for ACK returns null after terminal RESULT
 *  - replayBounded for PROGRESS returns null after terminal RESULT
 *  - replayBounded for RESULT returns non-null after terminal RESULT
 *  - replayBounded RESULT replay preserves original signalId
 *  - replaySignal (unbounded) still returns ACK after terminal RESULT
 *  - replaySignal (unbounded) still returns PROGRESS after terminal RESULT
 *
 * ### OfflineTaskQueue — session-authority bounding (AC2)
 *  - discardForDifferentSession returns 0 when queue is empty
 *  - discardForDifferentSession removes messages with different non-null tag
 *  - discardForDifferentSession preserves messages with matching tag
 *  - discardForDifferentSession preserves messages with null tag
 *  - discardForDifferentSession returns correct discard count
 *  - discardForDifferentSession reduces queue size by discard count
 *  - sizeFlow updates after discardForDifferentSession
 *  - mixed tags: only stale non-null tags are removed
 *  - enqueue with sessionTag stores sessionTag on QueuedMessage
 *  - enqueue without sessionTag stores null sessionTag on QueuedMessage
 *
 * ### AndroidContinuityIntegration — duplicate signal suppression (AC2)
 *  - suppressDuplicateLocalEmit returns false for unseen signalId
 *  - suppressDuplicateLocalEmit returns true after markLocalEmitSeen
 *  - second markLocalEmitSeen for same id is idempotent
 *  - clearEmittedSignalsSeen resets suppression set
 *
 * ### V2 authority alignment (AC4)
 *  - WaitForV2ReplayDecision reason for transport reconnect is non-blank
 *  - WaitForV2ReplayDecision reason for process recreation no-context is non-blank
 *  - RehydrateThenContinue does not self-authorize (decision carries context to present to V2)
 *  - deferredItems mentions emit_ledger_cross_process_persistence
 *  - deferredItems mentions takeover_recovery_path_explicit_bounding
 */
class Pr66ContinuityRecoveryDurabilityTest {

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun buildSignal(
        kind: DelegatedExecutionSignal.Kind,
        signalId: String = java.util.UUID.randomUUID().toString(),
        resultKind: DelegatedExecutionSignal.ResultKind? = null
    ) = DelegatedExecutionSignal(
        kind = kind,
        unitId = "unit-1",
        taskId = "task-1",
        traceId = "trace-1",
        attachedSessionId = "session-1",
        handoffContractVersion = 1,
        stepCount = 0,
        activationStatusHint = "pending",
        resultKind = resultKind,
        timestampMs = 1_000L,
        signalId = signalId,
        emissionSeq = when (kind) {
            DelegatedExecutionSignal.Kind.ACK -> DelegatedExecutionSignal.EMISSION_SEQ_ACK
            DelegatedExecutionSignal.Kind.PROGRESS -> DelegatedExecutionSignal.EMISSION_SEQ_PROGRESS
            DelegatedExecutionSignal.Kind.RESULT -> DelegatedExecutionSignal.EMISSION_SEQ_RESULT
        }
    )

    private fun buildLedgerWithAllSignals(): EmittedSignalLedger {
        val ledger = EmittedSignalLedger()
        ledger.recordEmitted(buildSignal(DelegatedExecutionSignal.Kind.ACK, signalId = "sig-ack"))
        ledger.recordEmitted(buildSignal(DelegatedExecutionSignal.Kind.PROGRESS, signalId = "sig-progress"))
        ledger.recordEmitted(
            buildSignal(
                DelegatedExecutionSignal.Kind.RESULT,
                signalId = "sig-result",
                resultKind = DelegatedExecutionSignal.ResultKind.COMPLETED
            )
        )
        return ledger
    }

    private fun buildReattachHint() = ProcessRecreatedReattachHint(
        priorDurableSessionId = "prior-session-id",
        deviceId = "device-1"
    )

    private fun buildLocalContext() = LocalExecutionContextSnapshot(
        flowId = "flow-1",
        flowLineageId = "lineage-1",
        unitId = "unit-1",
        taskId = "task-1",
        traceId = "trace-1",
        durableSessionId = "durable-1",
        attachedSessionId = "session-1",
        lastKnownPhase = AndroidFlowExecutionPhase.ACTIVE_LOOP,
        continuityToken = "ct-1",
        continuationToken = null,
        stepCount = 2,
        lastStepAtMs = 3_000L,
        rehydratedAtMs = 4_000L
    )

    private fun buildDurableSession(id: String = "durable-session-42", epoch: Int = 1) =
        DurableSessionContinuityRecord(
            durableSessionId = id,
            sessionContinuityEpoch = epoch,
            activationEpochMs = 1_000L,
            activationSource = "user_activation"
        )

    // ── ContinuityRecoveryDurabilityContract — structural invariants ──────────

    @Test
    fun `INTRODUCED_PR is 66`() {
        assertEquals(66, ContinuityRecoveryDurabilityContract.INTRODUCED_PR)
    }

    @Test
    fun `coveredBehaviors has exactly COVERED_BEHAVIOR_COUNT entries`() {
        assertEquals(
            ContinuityRecoveryDurabilityContract.COVERED_BEHAVIOR_COUNT,
            ContinuityRecoveryDurabilityContract.coveredBehaviors.size
        )
    }

    @Test
    fun `all coveredBehavior behaviorIds are non-blank`() {
        ContinuityRecoveryDurabilityContract.coveredBehaviors.forEach {
            assertTrue("behaviorId blank: ${it.behaviorId}", it.behaviorId.isNotBlank())
        }
    }

    @Test
    fun `all coveredBehavior behaviorIds are unique`() {
        val ids = ContinuityRecoveryDurabilityContract.coveredBehaviors.map { it.behaviorId }
        assertEquals("Duplicate behaviorIds found", ids.size, ids.toSet().size)
    }

    @Test
    fun `all coveredBehavior descriptions are non-blank`() {
        ContinuityRecoveryDurabilityContract.coveredBehaviors.forEach {
            assertTrue("description blank for ${it.behaviorId}", it.description.isNotBlank())
        }
    }

    @Test
    fun `all coveredBehavior coveredBy values are non-blank`() {
        ContinuityRecoveryDurabilityContract.coveredBehaviors.forEach {
            assertTrue("coveredBy blank for ${it.behaviorId}", it.coveredBy.isNotBlank())
        }
    }

    @Test
    fun `all coveredBehavior testEvidence values are non-blank`() {
        ContinuityRecoveryDurabilityContract.coveredBehaviors.forEach {
            assertTrue("testEvidence blank for ${it.behaviorId}", it.testEvidence.isNotBlank())
        }
    }

    @Test
    fun `boundedEmissions has exactly BOUNDED_EMISSION_COUNT entries`() {
        assertEquals(
            ContinuityRecoveryDurabilityContract.BOUNDED_EMISSION_COUNT,
            ContinuityRecoveryDurabilityContract.boundedEmissions.size
        )
    }

    @Test
    fun `all boundedEmission emissionIds are non-blank`() {
        ContinuityRecoveryDurabilityContract.boundedEmissions.forEach {
            assertTrue("emissionId blank: ${it.emissionId}", it.emissionId.isNotBlank())
        }
    }

    @Test
    fun `all boundedEmission emissionIds are unique`() {
        val ids = ContinuityRecoveryDurabilityContract.boundedEmissions.map { it.emissionId }
        assertEquals("Duplicate emissionIds found", ids.size, ids.toSet().size)
    }

    @Test
    fun `all boundedEmission suppressionConditions are non-blank`() {
        ContinuityRecoveryDurabilityContract.boundedEmissions.forEach {
            assertTrue(
                "suppressionCondition blank for ${it.emissionId}",
                it.suppressionCondition.isNotBlank()
            )
        }
    }

    @Test
    fun `deferredItems has exactly DEFERRED_ITEM_COUNT entries`() {
        assertEquals(
            ContinuityRecoveryDurabilityContract.DEFERRED_ITEM_COUNT,
            ContinuityRecoveryDurabilityContract.deferredItems.size
        )
    }

    @Test
    fun `all deferredItem itemIds are non-blank`() {
        ContinuityRecoveryDurabilityContract.deferredItems.forEach {
            assertTrue("itemId blank: ${it.itemId}", it.itemId.isNotBlank())
        }
    }

    @Test
    fun `all deferredItem itemIds are unique`() {
        val ids = ContinuityRecoveryDurabilityContract.deferredItems.map { it.itemId }
        assertEquals("Duplicate itemIds found", ids.size, ids.toSet().size)
    }

    @Test
    fun `all deferredItem deferralReasons are non-blank`() {
        ContinuityRecoveryDurabilityContract.deferredItems.forEach {
            assertTrue("deferralReason blank for ${it.itemId}", it.deferralReason.isNotBlank())
        }
    }

    // ── ContinuityRecoveryDurabilityContract — covered behavior spot-checks ──

    @Test
    fun `process_recreation_with_prior_context is covered`() {
        assertNotNull(
            ContinuityRecoveryDurabilityContract.coveredBehaviorFor(
                "process_recreation_with_prior_context"
            )
        )
    }

    @Test
    fun `process_recreation_without_prior_context is covered`() {
        assertNotNull(
            ContinuityRecoveryDurabilityContract.coveredBehaviorFor(
                "process_recreation_without_prior_context"
            )
        )
    }

    @Test
    fun `transport_reconnect_defers_to_v2 is covered`() {
        assertNotNull(
            ContinuityRecoveryDurabilityContract.coveredBehaviorFor(
                "transport_reconnect_defers_to_v2"
            )
        )
    }

    @Test
    fun `receiver_pipeline_rebind_resumes_locally is covered`() {
        assertNotNull(
            ContinuityRecoveryDurabilityContract.coveredBehaviorFor(
                "receiver_pipeline_rebind_resumes_locally"
            )
        )
    }

    @Test
    fun `emit_ledger_terminal_bounding_ack is covered`() {
        assertNotNull(
            ContinuityRecoveryDurabilityContract.coveredBehaviorFor(
                "emit_ledger_terminal_bounding_ack"
            )
        )
    }

    @Test
    fun `offline_queue_stale_session_discard is covered`() {
        assertNotNull(
            ContinuityRecoveryDurabilityContract.coveredBehaviorFor(
                "offline_queue_stale_session_discard"
            )
        )
    }

    @Test
    fun `duplicate_signal_emit_suppression is covered`() {
        assertNotNull(
            ContinuityRecoveryDurabilityContract.coveredBehaviorFor(
                "duplicate_signal_emit_suppression"
            )
        )
    }

    // ── ContinuityRecoveryDurabilityContract — bounded emission spot-checks ──

    @Test
    fun `ack_after_terminal_result is bounded`() {
        assertNotNull(
            ContinuityRecoveryDurabilityContract.boundedEmissionFor("ack_after_terminal_result")
        )
    }

    @Test
    fun `progress_after_terminal_result is bounded`() {
        assertNotNull(
            ContinuityRecoveryDurabilityContract.boundedEmissionFor(
                "progress_after_terminal_result"
            )
        )
    }

    @Test
    fun `offline_queue_stale_authority is bounded`() {
        assertNotNull(
            ContinuityRecoveryDurabilityContract.boundedEmissionFor("offline_queue_stale_authority")
        )
    }

    @Test
    fun `duplicate_signal_cross_execution is bounded`() {
        assertNotNull(
            ContinuityRecoveryDurabilityContract.boundedEmissionFor(
                "duplicate_signal_cross_execution"
            )
        )
    }

    @Test
    fun `duplicate_recovery_attempt is bounded`() {
        assertNotNull(
            ContinuityRecoveryDurabilityContract.boundedEmissionFor("duplicate_recovery_attempt")
        )
    }

    @Test
    fun `stale_identity_reception is bounded`() {
        assertNotNull(
            ContinuityRecoveryDurabilityContract.boundedEmissionFor("stale_identity_reception")
        )
    }

    // ── AndroidRecoveryParticipationOwner — restart/reconnect restoration ─────

    @Test
    fun `process recreation with context yields RehydrateThenContinue`() {
        val owner = AndroidRecoveryParticipationOwner()
        val localContext = buildLocalContext()
        val decision = AndroidContinuityIntegration.ContinuityDecision.ProcessRecreationReattach(
            reattachHint = buildReattachHint(),
            localContext = localContext
        )
        val result = owner.evaluateRecovery(
            AndroidRecoveryParticipationOwner.RecoveryInput(
                continuityDecision = decision,
                recoveryAttemptKey = "flow-1",
                nowMs = 5_000L
            )
        )
        assertTrue(
            "Expected RehydrateThenContinue but got $result",
            result is LocalRecoveryDecision.RehydrateThenContinue
        )
    }

    @Test
    fun `RehydrateThenContinue semanticTag is rehydrate_then_continue`() {
        val owner = AndroidRecoveryParticipationOwner()
        val decision = AndroidContinuityIntegration.ContinuityDecision.ProcessRecreationReattach(
            reattachHint = buildReattachHint(),
            localContext = buildLocalContext()
        )
        val result = owner.evaluateRecovery(
            AndroidRecoveryParticipationOwner.RecoveryInput(
                continuityDecision = decision,
                recoveryAttemptKey = "flow-2",
                nowMs = 5_000L
            )
        )
        assertEquals(
            AndroidRecoveryParticipationOwner.SEMANTIC_REHYDRATE_THEN_CONTINUE,
            result.semanticTag
        )
    }

    @Test
    fun `RehydrateThenContinue localContext matches the decision snapshot`() {
        val owner = AndroidRecoveryParticipationOwner()
        val localContext = buildLocalContext()
        val decision = AndroidContinuityIntegration.ContinuityDecision.ProcessRecreationReattach(
            reattachHint = buildReattachHint(),
            localContext = localContext
        )
        val result = owner.evaluateRecovery(
            AndroidRecoveryParticipationOwner.RecoveryInput(
                continuityDecision = decision,
                recoveryAttemptKey = "flow-3",
                nowMs = 5_000L
            )
        ) as LocalRecoveryDecision.RehydrateThenContinue
        assertEquals(localContext, result.localContext)
    }

    @Test
    fun `process recreation without prior context yields WaitForV2ReplayDecision`() {
        val owner = AndroidRecoveryParticipationOwner()
        val decision = AndroidContinuityIntegration.ContinuityDecision.ProcessRecreationReattach(
            reattachHint = buildReattachHint(),
            localContext = null
        )
        val result = owner.evaluateRecovery(
            AndroidRecoveryParticipationOwner.RecoveryInput(
                continuityDecision = decision,
                recoveryAttemptKey = "flow-4"
            )
        )
        assertTrue(
            "Expected WaitForV2ReplayDecision but got $result",
            result is LocalRecoveryDecision.WaitForV2ReplayDecision
        )
    }

    @Test
    fun `WaitForV2ReplayDecision for process recreation has null durableSessionId`() {
        val owner = AndroidRecoveryParticipationOwner()
        val decision = AndroidContinuityIntegration.ContinuityDecision.ProcessRecreationReattach(
            reattachHint = buildReattachHint(),
            localContext = null
        )
        val result = owner.evaluateRecovery(
            AndroidRecoveryParticipationOwner.RecoveryInput(
                continuityDecision = decision,
                recoveryAttemptKey = "flow-5"
            )
        ) as LocalRecoveryDecision.WaitForV2ReplayDecision
        assertNull(result.durableSessionId)
    }

    @Test
    fun `WaitForV2ReplayDecision semanticTag is wait_for_v2_replay_decision`() {
        val owner = AndroidRecoveryParticipationOwner()
        val decision = AndroidContinuityIntegration.ContinuityDecision.TransportReconnect(
            durableSession = buildDurableSession()
        )
        val result = owner.evaluateRecovery(
            AndroidRecoveryParticipationOwner.RecoveryInput(
                continuityDecision = decision,
                recoveryAttemptKey = "flow-6"
            )
        )
        assertEquals(
            AndroidRecoveryParticipationOwner.SEMANTIC_WAIT_FOR_V2_REPLAY_DECISION,
            result.semanticTag
        )
    }

    @Test
    fun `transport reconnect yields WaitForV2ReplayDecision with durableSessionId`() {
        val owner = AndroidRecoveryParticipationOwner()
        val durableSession = buildDurableSession(id = "durable-session-99", epoch = 3)
        val decision = AndroidContinuityIntegration.ContinuityDecision.TransportReconnect(
            durableSession = durableSession
        )
        val result = owner.evaluateRecovery(
            AndroidRecoveryParticipationOwner.RecoveryInput(
                continuityDecision = decision,
                recoveryAttemptKey = "flow-7"
            )
        ) as LocalRecoveryDecision.WaitForV2ReplayDecision
        assertEquals("durable-session-99", result.durableSessionId)
    }

    @Test
    fun `WaitForV2ReplayDecision for transport reconnect carries durableSessionId from session`() {
        val owner = AndroidRecoveryParticipationOwner()
        val durableSession = buildDurableSession(id = "ds-transport-42")
        val decision = AndroidContinuityIntegration.ContinuityDecision.TransportReconnect(
            durableSession = durableSession
        )
        val result = owner.evaluateRecovery(
            AndroidRecoveryParticipationOwner.RecoveryInput(
                continuityDecision = decision,
                recoveryAttemptKey = "flow-8"
            )
        ) as LocalRecoveryDecision.WaitForV2ReplayDecision
        assertEquals(durableSession.durableSessionId, result.durableSessionId)
    }

    @Test
    fun `receiver pipeline rebind yields ResumeLocalExecution`() {
        val owner = AndroidRecoveryParticipationOwner()
        val decision = AndroidContinuityIntegration.ContinuityDecision.ReceiverPipelineRebind(
            activeSessionId = "active-session-1"
        )
        val result = owner.evaluateRecovery(
            AndroidRecoveryParticipationOwner.RecoveryInput(
                continuityDecision = decision,
                recoveryAttemptKey = "flow-9"
            )
        )
        assertTrue(
            "Expected ResumeLocalExecution but got $result",
            result is LocalRecoveryDecision.ResumeLocalExecution
        )
    }

    @Test
    fun `ResumeLocalExecution activeSessionId matches rebind session`() {
        val owner = AndroidRecoveryParticipationOwner()
        val decision = AndroidContinuityIntegration.ContinuityDecision.ReceiverPipelineRebind(
            activeSessionId = "active-session-2"
        )
        val result = owner.evaluateRecovery(
            AndroidRecoveryParticipationOwner.RecoveryInput(
                continuityDecision = decision,
                recoveryAttemptKey = "flow-10"
            )
        ) as LocalRecoveryDecision.ResumeLocalExecution
        assertEquals("active-session-2", result.activeSessionId)
    }

    @Test
    fun `fresh attach yields NoRecoveryContext`() {
        val owner = AndroidRecoveryParticipationOwner()
        val decision = AndroidContinuityIntegration.ContinuityDecision.FreshAttach
        val result = owner.evaluateRecovery(
            AndroidRecoveryParticipationOwner.RecoveryInput(
                continuityDecision = decision,
                recoveryAttemptKey = "flow-11"
            )
        )
        assertEquals(LocalRecoveryDecision.NoRecoveryContext, result)
    }

    @Test
    fun `duplicate recovery attempt is suppressed with SuppressDuplicateLocalRecovery`() {
        val owner = AndroidRecoveryParticipationOwner()
        val attemptKey = "flow-12"
        owner.markRecoveryAttemptStarted(attemptKey)

        val decision = AndroidContinuityIntegration.ContinuityDecision.FreshAttach
        val result = owner.evaluateRecovery(
            AndroidRecoveryParticipationOwner.RecoveryInput(
                continuityDecision = decision,
                recoveryAttemptKey = attemptKey
            )
        )
        assertTrue(
            "Expected SuppressDuplicateLocalRecovery but got $result",
            result is LocalRecoveryDecision.SuppressDuplicateLocalRecovery
        )
        val suppress = result as LocalRecoveryDecision.SuppressDuplicateLocalRecovery
        assertEquals(attemptKey, suppress.attemptKey)
    }

    // ── EmittedSignalLedger — terminal-state bounding ─────────────────────────

    @Test
    fun `hasTerminalResult is false before any signal is recorded`() {
        val ledger = EmittedSignalLedger()
        assertFalse(ledger.hasTerminalResult)
    }

    @Test
    fun `hasTerminalResult is false after ACK only`() {
        val ledger = EmittedSignalLedger()
        ledger.recordEmitted(buildSignal(DelegatedExecutionSignal.Kind.ACK))
        assertFalse(ledger.hasTerminalResult)
    }

    @Test
    fun `hasTerminalResult is false after ACK and PROGRESS`() {
        val ledger = EmittedSignalLedger()
        ledger.recordEmitted(buildSignal(DelegatedExecutionSignal.Kind.ACK))
        ledger.recordEmitted(buildSignal(DelegatedExecutionSignal.Kind.PROGRESS))
        assertFalse(ledger.hasTerminalResult)
    }

    @Test
    fun `hasTerminalResult is true after RESULT is recorded`() {
        val ledger = buildLedgerWithAllSignals()
        assertTrue(ledger.hasTerminalResult)
    }

    @Test
    fun `replayBounded for ACK returns signal when no terminal result`() {
        val ledger = EmittedSignalLedger()
        ledger.recordEmitted(buildSignal(DelegatedExecutionSignal.Kind.ACK, signalId = "ack-id"))
        val replay = ledger.replayBounded(DelegatedExecutionSignal.Kind.ACK, 2_000L)
        assertNotNull("Expected non-null ACK replay before terminal", replay)
    }

    @Test
    fun `replayBounded for PROGRESS returns signal when no terminal result`() {
        val ledger = EmittedSignalLedger()
        ledger.recordEmitted(buildSignal(DelegatedExecutionSignal.Kind.PROGRESS, signalId = "prog-id"))
        val replay = ledger.replayBounded(DelegatedExecutionSignal.Kind.PROGRESS, 2_000L)
        assertNotNull("Expected non-null PROGRESS replay before terminal", replay)
    }

    @Test
    fun `replayBounded for RESULT returns null when no prior recording`() {
        val ledger = EmittedSignalLedger()
        // No signals recorded — nothing to replay even for RESULT
        val replay = ledger.replayBounded(DelegatedExecutionSignal.Kind.RESULT, 2_000L)
        assertNull(replay)
    }

    @Test
    fun `replayBounded for ACK returns null after terminal RESULT`() {
        val ledger = buildLedgerWithAllSignals()
        val replay = ledger.replayBounded(DelegatedExecutionSignal.Kind.ACK, 5_000L)
        assertNull(
            "Expected null ACK replay after terminal RESULT (stale emission bounding)",
            replay
        )
    }

    @Test
    fun `replayBounded for PROGRESS returns null after terminal RESULT`() {
        val ledger = buildLedgerWithAllSignals()
        val replay = ledger.replayBounded(DelegatedExecutionSignal.Kind.PROGRESS, 5_000L)
        assertNull(
            "Expected null PROGRESS replay after terminal RESULT (stale emission bounding)",
            replay
        )
    }

    @Test
    fun `replayBounded for RESULT returns non-null after terminal RESULT`() {
        val ledger = buildLedgerWithAllSignals()
        val replay = ledger.replayBounded(DelegatedExecutionSignal.Kind.RESULT, 5_000L)
        assertNotNull(
            "Expected non-null RESULT replay after terminal RESULT (idempotent replay)",
            replay
        )
    }

    @Test
    fun `replayBounded RESULT replay preserves original signalId`() {
        val ledger = buildLedgerWithAllSignals()
        val replay = ledger.replayBounded(DelegatedExecutionSignal.Kind.RESULT, 5_000L)!!
        assertEquals("sig-result", replay.signalId)
    }

    @Test
    fun `replaySignal (unbounded) still returns ACK after terminal RESULT`() {
        val ledger = buildLedgerWithAllSignals()
        // replaySignal is unbounded — it should still return a value even after terminal
        val replay = ledger.replaySignal(DelegatedExecutionSignal.Kind.ACK, 5_000L)
        assertNotNull(
            "replaySignal (unbounded) should return ACK regardless of terminal state",
            replay
        )
    }

    @Test
    fun `replaySignal (unbounded) still returns PROGRESS after terminal RESULT`() {
        val ledger = buildLedgerWithAllSignals()
        val replay = ledger.replaySignal(DelegatedExecutionSignal.Kind.PROGRESS, 5_000L)
        assertNotNull(
            "replaySignal (unbounded) should return PROGRESS regardless of terminal state",
            replay
        )
    }

    // ── OfflineTaskQueue — session-authority bounding ─────────────────────────

    @Test
    fun `discardForDifferentSession returns 0 when queue is empty`() {
        val queue = OfflineTaskQueue(prefs = null)
        val discarded = queue.discardForDifferentSession("session-A")
        assertEquals(0, discarded)
    }

    @Test
    fun `discardForDifferentSession removes messages with different non-null tag`() {
        val queue = OfflineTaskQueue(prefs = null)
        queue.enqueue("task_result", """{"id":1}""", sessionTag = "session-OLD")
        queue.discardForDifferentSession("session-NEW")
        assertEquals(0, queue.size)
    }

    @Test
    fun `discardForDifferentSession preserves messages with matching tag`() {
        val queue = OfflineTaskQueue(prefs = null)
        queue.enqueue("task_result", """{"id":1}""", sessionTag = "session-CURRENT")
        queue.discardForDifferentSession("session-CURRENT")
        assertEquals(1, queue.size)
    }

    @Test
    fun `discardForDifferentSession preserves messages with null tag`() {
        val queue = OfflineTaskQueue(prefs = null)
        queue.enqueue("task_result", """{"id":1}""")  // no sessionTag → null
        queue.discardForDifferentSession("session-NEW")
        assertEquals(1, queue.size)
    }

    @Test
    fun `discardForDifferentSession returns correct discard count`() {
        val queue = OfflineTaskQueue(prefs = null)
        queue.enqueue("task_result", """{"id":1}""", sessionTag = "session-OLD")
        queue.enqueue("task_result", """{"id":2}""", sessionTag = "session-OLD")
        queue.enqueue("task_result", """{"id":3}""", sessionTag = "session-CURRENT")
        val discarded = queue.discardForDifferentSession("session-CURRENT")
        assertEquals(2, discarded)
    }

    @Test
    fun `discardForDifferentSession reduces queue size by discard count`() {
        val queue = OfflineTaskQueue(prefs = null)
        queue.enqueue("task_result", """{"id":1}""", sessionTag = "session-OLD")
        queue.enqueue("task_result", """{"id":2}""", sessionTag = "session-CURRENT")
        queue.enqueue("goal_result", """{"id":3}""")  // null tag — preserved
        val sizeBefore = queue.size
        val discarded = queue.discardForDifferentSession("session-CURRENT")
        assertEquals(sizeBefore - discarded, queue.size)
    }

    @Test
    fun `sizeFlow updates after discardForDifferentSession`() {
        val queue = OfflineTaskQueue(prefs = null)
        queue.enqueue("task_result", """{"id":1}""", sessionTag = "session-OLD")
        queue.enqueue("task_result", """{"id":2}""", sessionTag = "session-OLD")
        queue.discardForDifferentSession("session-NEW")
        assertEquals(0, queue.sizeFlow.value)
    }

    @Test
    fun `mixed tags only stale non-null tags are removed`() {
        val queue = OfflineTaskQueue(prefs = null)
        queue.enqueue("task_result", """{"id":1}""", sessionTag = "session-OLD")
        queue.enqueue("task_result", """{"id":2}""", sessionTag = "session-CURRENT")
        queue.enqueue("goal_result",  """{"id":3}""")               // null tag
        queue.enqueue("task_result", """{"id":4}""", sessionTag = "session-OLD")

        val discarded = queue.discardForDifferentSession("session-CURRENT")
        assertEquals(2, discarded)
        assertEquals(2, queue.size)  // id=2 (matching) and id=3 (null tag) remain
    }

    @Test
    fun `enqueue with sessionTag stores sessionTag on QueuedMessage`() {
        val queue = OfflineTaskQueue(prefs = null)
        queue.enqueue("task_result", """{"id":1}""", sessionTag = "session-X")
        val msg = queue.drainAll().first()
        assertEquals("session-X", msg.sessionTag)
    }

    @Test
    fun `enqueue without sessionTag stores null sessionTag on QueuedMessage`() {
        val queue = OfflineTaskQueue(prefs = null)
        queue.enqueue("task_result", """{"id":1}""")
        val msg = queue.drainAll().first()
        assertNull(msg.sessionTag)
    }

    // ── AndroidContinuityIntegration — duplicate signal suppression ───────────

    @Test
    fun `suppressDuplicateLocalEmit returns false for unseen signalId`() {
        val integration = AndroidContinuityIntegration()
        assertFalse(integration.suppressDuplicateLocalEmit("signal-id-new"))
    }

    @Test
    fun `suppressDuplicateLocalEmit returns true after markLocalEmitSeen`() {
        val integration = AndroidContinuityIntegration()
        integration.markLocalEmitSeen("signal-id-seen")
        assertTrue(integration.suppressDuplicateLocalEmit("signal-id-seen"))
    }

    @Test
    fun `second markLocalEmitSeen for same id is idempotent`() {
        val integration = AndroidContinuityIntegration()
        integration.markLocalEmitSeen("signal-id-dup")
        integration.markLocalEmitSeen("signal-id-dup")
        assertTrue(integration.suppressDuplicateLocalEmit("signal-id-dup"))
    }

    @Test
    fun `clearEmittedSignalsSeen resets suppression set`() {
        val integration = AndroidContinuityIntegration()
        integration.markLocalEmitSeen("signal-id-to-clear")
        integration.clearEmitSeenSet()
        assertFalse(integration.suppressDuplicateLocalEmit("signal-id-to-clear"))
    }

    // ── V2 authority alignment (AC4) ──────────────────────────────────────────

    @Test
    fun `WaitForV2ReplayDecision reason for transport reconnect is non-blank`() {
        val owner = AndroidRecoveryParticipationOwner()
        val decision = AndroidContinuityIntegration.ContinuityDecision.TransportReconnect(
            durableSession = buildDurableSession()
        )
        val result = owner.evaluateRecovery(
            AndroidRecoveryParticipationOwner.RecoveryInput(
                continuityDecision = decision,
                recoveryAttemptKey = "flow-v2-1"
            )
        ) as LocalRecoveryDecision.WaitForV2ReplayDecision
        assertTrue(result.reason.isNotBlank())
    }

    @Test
    fun `WaitForV2ReplayDecision reason for process recreation no-context is non-blank`() {
        val owner = AndroidRecoveryParticipationOwner()
        val decision = AndroidContinuityIntegration.ContinuityDecision.ProcessRecreationReattach(
            reattachHint = buildReattachHint(),
            localContext = null
        )
        val result = owner.evaluateRecovery(
            AndroidRecoveryParticipationOwner.RecoveryInput(
                continuityDecision = decision,
                recoveryAttemptKey = "flow-v2-2"
            )
        ) as LocalRecoveryDecision.WaitForV2ReplayDecision
        assertTrue(result.reason.isNotBlank())
    }

    @Test
    fun `RehydrateThenContinue carries localContext for V2 presentation`() {
        // AC4: Android does NOT self-authorize; it presents context to V2 via RehydrateThenContinue.
        // Verify: the decision type is RehydrateThenContinue (not ResumeLocalExecution), confirming
        // that Android defers the continuation decision to V2 rather than resuming autonomously.
        val owner = AndroidRecoveryParticipationOwner()
        val localContext = buildLocalContext()
        val decision = AndroidContinuityIntegration.ContinuityDecision.ProcessRecreationReattach(
            reattachHint = buildReattachHint(),
            localContext = localContext
        )
        val result = owner.evaluateRecovery(
            AndroidRecoveryParticipationOwner.RecoveryInput(
                continuityDecision = decision,
                recoveryAttemptKey = "flow-v2-3",
                nowMs = 5_000L
            )
        )
        // Android must NOT produce ResumeLocalExecution (self-authorized continuation).
        // It must produce RehydrateThenContinue, which carries context to present to V2.
        assertFalse(
            "Android must not self-authorize — must not return ResumeLocalExecution",
            result is LocalRecoveryDecision.ResumeLocalExecution
        )
        assertTrue(
            "Android must defer to V2 by returning RehydrateThenContinue",
            result is LocalRecoveryDecision.RehydrateThenContinue
        )
        val rehydrate = result as LocalRecoveryDecision.RehydrateThenContinue
        // The localContext is present for V2 to inspect — Android does not decide continuation
        assertNotNull("localContext must be non-null for V2 to evaluate", rehydrate.localContext)
        assertEquals(localContext.flowId, rehydrate.localContext.flowId)
    }

    @Test
    fun `deferredItems mentions emit_ledger_cross_process_persistence`() {
        assertNotNull(
            ContinuityRecoveryDurabilityContract.deferredItemFor(
                "emit_ledger_cross_process_persistence"
            )
        )
    }

    @Test
    fun `deferredItems mentions takeover_recovery_path_explicit_bounding`() {
        assertNotNull(
            ContinuityRecoveryDurabilityContract.deferredItemFor(
                "takeover_recovery_path_explicit_bounding"
            )
        )
    }

    @Test
    fun `stale identity rejection suppresses unit with mismatched session`() {
        // Validates bounded emission: stale_identity_reception
        val integration = AndroidContinuityIntegration()
        val activeSession = AttachedRuntimeSession(
            sessionId = "active-session-X",
            hostId = "host-1",
            deviceId = "device-1",
            state = AttachedRuntimeSession.State.ATTACHED
        )
        val result = integration.validateRuntimeIdentity(
            unitAttachedSessionId = "stale-session-OLD",
            activeSession = activeSession
        )
        assertTrue(
            "Expected StaleIdentity for mismatched session",
            result is AndroidContinuityIntegration.IdentityValidationResult.StaleIdentity
        )
    }

    @Test
    fun `stale identity not triggered for matching session`() {
        val integration = AndroidContinuityIntegration()
        val activeSession = AttachedRuntimeSession(
            sessionId = "active-session-Y",
            hostId = "host-1",
            deviceId = "device-1",
            state = AttachedRuntimeSession.State.ATTACHED
        )
        val result = integration.validateRuntimeIdentity(
            unitAttachedSessionId = "active-session-Y",
            activeSession = activeSession
        )
        assertEquals(
            AndroidContinuityIntegration.IdentityValidationResult.Valid,
            result
        )
    }
}
