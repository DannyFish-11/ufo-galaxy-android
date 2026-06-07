package com.ufo.galaxy.runtime

/**
 * PR-70 (Android) — Enum classifying the provenance of a participant verification run.
 *
 * [RealDeviceVerificationKind] is attached to every
 * [RealDeviceParticipantVerificationReport] to make the verification origin machine-readable
 * and auditable.  It ensures that V2 (and any CI/CD gate consuming the report) can
 * distinguish genuine real-device evidence from emulator, simulated, stale, or partial
 * evidence without relying on free-text log inspection.
 *
 * ## Prohibition on optimistic promotion
 *
 * A [RealDeviceParticipantVerificationBridge] implementation **must not** upgrade evidence to
 * [REAL_DEVICE] unless:
 *  - A physical Android device was detected and connected at verification time.
 *  - All required [RealDeviceVerificationScenario] values were observed on that device.
 *
 * An absent or unavailable device **must** produce [INCOMPLETE] or [NO_DEVICE], not
 * [REAL_DEVICE] or [EMULATOR].
 *
 * ## Wire-value stability
 *
 * [wireValue] is stable across releases.  V2 Python/CI code must compare against the wire
 * values listed in the table below, not against ordinal or name strings.
 *
 * | [RealDeviceVerificationKind] | [wireValue]           | Description                                                                         |
 * |------------------------------|-----------------------|-------------------------------------------------------------------------------------|
 * | [REAL_DEVICE]                | `real_device`         | All required scenarios verified on a physical Android device.                       |
 * | [EMULATOR]                   | `emulator`            | Scenarios verified on an Android emulator; no physical device involved.             |
 * | [SIMULATED]                  | `simulated`           | Software-only simulation; no Android runtime (emulator or device) was used.         |
 * | [STALE]                      | `stale`               | Previously verified (kind unknown or real-device) but evidence is too old to trust. |
 * | [INCOMPLETE]                 | `incomplete`          | Verification attempted but not all required scenarios were completed.                |
 * | [NO_DEVICE]                  | `no_device`           | No physical device was present; verification could not be started.                  |
 *
 * @property wireValue  Stable lowercase string for wire transport and cross-repo consumption.
 * @property description Human-readable description of this verification kind.
 *
 * @see RealDeviceParticipantVerificationBridge
 * @see RealDeviceParticipantVerificationReport
 * @see RealDeviceVerificationScenario
 */
enum class RealDeviceVerificationKind(
    val wireValue: String,
    val description: String
) {

    /**
     * All required [RealDeviceVerificationScenario] values were successfully verified on
     * a physical Android device.
     *
     * This is the only kind accepted by V2 as authoritative real-device participant
     * evidence.  All other kinds must be treated as lower-confidence signals.
     */
    REAL_DEVICE(
        wireValue = "real_device",
        description = "All required scenarios verified on a physical Android device"
    ),

    /**
     * Scenarios were verified using an Android emulator.
     *
     * Emulator evidence is structurally equivalent to real-device evidence but does not
     * provide hardware-level runtime confirmation.  V2 must not treat emulator evidence
     * as equivalent to [REAL_DEVICE] for hardware-gated acceptance decisions.
     */
    EMULATOR(
        wireValue = "emulator",
        description = "Scenarios verified on an Android emulator; not a physical device"
    ),

    /**
     * Scenarios were verified in a software-only simulation without any Android runtime.
     *
     * Simulated evidence covers logic paths and state-machine behaviour but cannot confirm
     * real Android OS lifecycle, WS connectivity, or device-level registration.  V2 must
     * treat simulated evidence as advisory only.
     */
    SIMULATED(
        wireValue = "simulated",
        description = "Software-only simulation; no Android runtime used"
    ),

    /**
     * Evidence was produced by a previous real-device (or higher-confidence) verification
     * run but is now older than the staleness threshold.
     *
     * V2 must request a fresh verification run before consuming stale evidence for
     * acceptance decisions.  Stale evidence must not be promoted to [REAL_DEVICE].
     */
    STALE(
        wireValue = "stale",
        description = "Previous verification evidence is too old; a refresh is required"
    ),

    /**
     * A verification run was attempted but not all required [RealDeviceVerificationScenario]
     * values were completed.
     *
     * Incomplete evidence indicates a partial run (e.g. device disconnected mid-run, or a
     * scenario timed out).  V2 must not treat partial scenario coverage as fully verified.
     */
    INCOMPLETE(
        wireValue = "incomplete",
        description = "Verification attempted but not all required scenarios were completed"
    ),

    /**
     * No physical Android device was present when verification was attempted.
     *
     * This state is recorded when [RealDeviceParticipantVerificationBridge] is invoked but
     * the device availability probe returns false.  It prevents the bridge from falsely
     * reporting [REAL_DEVICE] when no device is connected.
     *
     * V2 must treat [NO_DEVICE] as equivalent to [INCOMPLETE] for acceptance gating.
     */
    NO_DEVICE(
        wireValue = "no_device",
        description = "No physical device present; verification could not proceed"
    );

    companion object {

        /**
         * Returns the [RealDeviceVerificationKind] matching [value], or `null` if no match.
         *
         * @param value  The wire-value string to look up.
         */
        fun fromWireValue(value: String?): RealDeviceVerificationKind? =
            entries.firstOrNull { it.wireValue == value }

        /**
         * Returns `true` when [kind] represents evidence that V2 may treat as authoritative
         * real-device verification (i.e. [REAL_DEVICE] only).
         *
         * All other kinds must be treated as lower-confidence or blocking by V2.
         */
        fun isRealDeviceAuthoritative(kind: RealDeviceVerificationKind): Boolean =
            kind == REAL_DEVICE

        /**
         * Returns `true` when [kind] represents evidence that is usable by V2 with reduced
         * confidence (i.e. [REAL_DEVICE] or [EMULATOR]).
         *
         * [SIMULATED], [STALE], [INCOMPLETE], and [NO_DEVICE] are not usable for
         * acceptance-gate decisions.
         */
        fun isUsableEvidence(kind: RealDeviceVerificationKind): Boolean =
            kind == REAL_DEVICE || kind == EMULATOR

        /** All stable wire values for this enum, for validation purposes. */
        val ALL_WIRE_VALUES: Set<String> = entries.map { it.wireValue }.toSet()
    }
}
