package com.ufo.galaxy.runtime

import com.ufo.galaxy.protocol.ReconciliationSignalPayload

/**
 * Canonical ingestion guard for Android-originated reconciliation signals.
 *
 * The same rule is expressed for both the typed Android model and the wire payload so V2
 * consumers can reject stale signals by comparing `durable_session_id` and
 * `session_continuity_epoch` against the current participant session era.
 */
object ReconciliationSignalIngestionGuard {

    data class CurrentAuthority(
        val durableSessionId: String,
        val sessionContinuityEpoch: Int
    )

    sealed class Decision {
        object Accept : Decision()
        data class Reject(val reason: String) : Decision()
    }

    fun evaluate(
        signal: ReconciliationSignal,
        currentAuthority: CurrentAuthority
    ): Decision = evaluateFields(
        durableSessionId = signal.durableSessionId,
        sessionContinuityEpoch = signal.sessionContinuityEpoch,
        currentAuthority = currentAuthority
    )

    fun evaluate(
        payload: ReconciliationSignalPayload,
        currentAuthority: CurrentAuthority
    ): Decision = evaluateFields(
        durableSessionId = payload.durable_session_id,
        sessionContinuityEpoch = payload.session_continuity_epoch,
        currentAuthority = currentAuthority
    )

    private fun evaluateFields(
        durableSessionId: String?,
        sessionContinuityEpoch: Int?,
        currentAuthority: CurrentAuthority
    ): Decision {
        if (durableSessionId.isNullOrBlank()) {
            return Decision.Reject(REASON_MISSING_DURABLE_SESSION_ID)
        }
        if (sessionContinuityEpoch == null) {
            return Decision.Reject(REASON_MISSING_SESSION_CONTINUITY_EPOCH)
        }
        if (durableSessionId != currentAuthority.durableSessionId) {
            return Decision.Reject(REASON_STALE_DURABLE_SESSION_ID)
        }
        if (sessionContinuityEpoch != currentAuthority.sessionContinuityEpoch) {
            return Decision.Reject(REASON_STALE_SESSION_CONTINUITY_EPOCH)
        }
        return Decision.Accept
    }

    const val REASON_MISSING_DURABLE_SESSION_ID = "missing_durable_session_id"
    const val REASON_MISSING_SESSION_CONTINUITY_EPOCH = "missing_session_continuity_epoch"
    const val REASON_STALE_DURABLE_SESSION_ID = "stale_durable_session_id"
    const val REASON_STALE_SESSION_CONTINUITY_EPOCH = "stale_session_continuity_epoch"
}
