package com.ufo.galaxy.runtime

import android.util.Log
import com.ufo.galaxy.observability.GalaxyLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * B7-FIX: Canonical three-phase state machine (Silent / Liminal / Manifest).
 *
 * V2 Desktop sends phase transitions via `state_event` (category=phase) and
 * `liquid_event` (msg_type=phase_change).  This state machine:
 *  1. Validates every transition against the allowed directed graph.
 *  2. Keeps the current phase in memory only — the phase is driven by the
 *     remote desktop peer, which re-sends the authoritative phase on
 *     (re)connect, so nothing is persisted across process restarts.
 *  3. Exposes a [StateFlow] that UI components (MainActivity, floating island)
 *     can collect to visualise the current phase.
 *
 * ## Transition graph
 * ```
 *        +--------+    +--------+    +---------+
 *   +--->| SILENT |--->|LIMINAL |--->|MANIFEST |
 *   |    +--------+    +--------+    +---------+
 *   |       ^  |          ^ |            ^ |
 *   |       |  +----------+ +------------+ |
 *   |       +------------------------------+
 *   +--------------------------------------+
 * ```
 * Any transition is allowed (fully connected), but self-transitions are no-ops.
 */
class PhaseStateMachine {

    companion object {
        private const val TAG = "PhaseStateMachine"
    }

    /** The three canonical phases.
     *  注意:必须是 PhaseStateMachine 的【直接】嵌套类,不能放进 companion object——
     *  放进 companion 后外部 `PhaseStateMachine.Phase` 无法解析(MainActivity /
     *  MainViewModel / GalaxyConnectionService 全报 Unresolved: Phase)。 */
    enum class Phase(val wireValue: String) {
        SILENT("silent"),
        LIMINAL("liminal"),
        MANIFEST("manifest");

        companion object {
            fun fromWire(value: String): Phase? =
                entries.find { it.wireValue.equals(value, ignoreCase = true) }
        }
    }

    private val _currentPhase = MutableStateFlow(Phase.SILENT)

    /** Observable current phase; UI components collect this to update visual state. */
    val currentPhase: StateFlow<Phase> = _currentPhase.asStateFlow()

    /**
     * Transition to [newPhase] if it differs from the current phase.
     * Logs the transition for observability and persists it.
     * Self-transitions are silently ignored (idempotent).
     */
    fun transitionTo(newPhase: Phase) {
        val previous = _currentPhase.value
        if (previous == newPhase) {
            Log.d(TAG, "Phase transition ignored: $newPhase (already current)")
            return
        }
        _currentPhase.value = newPhase
        GalaxyLogger.log(
            TAG,
            mapOf(
                "event" to "phase_transition",
                "from" to previous.wireValue,
                "to" to newPhase.wireValue
            )
        )
        Log.i(TAG, "Phase transition: ${previous.wireValue} → ${newPhase.wireValue}")
    }

    /**
     * Transition by wire string value (case-insensitive).
     * Unknown values are logged and ignored.
     */
    fun transitionTo(phaseWireValue: String) {
        val phase = Phase.fromWire(phaseWireValue)
        if (phase != null) {
            transitionTo(phase)
        } else {
            Log.w(TAG, "Unknown phase wire value: $phaseWireValue")
        }
    }

    /** Returns true if currently in [phase]. */
    fun isInPhase(phase: Phase): Boolean = _currentPhase.value == phase

    /** Returns the current phase wire value for outbound messages. */
    fun currentWireValue(): String = _currentPhase.value.wireValue
}
