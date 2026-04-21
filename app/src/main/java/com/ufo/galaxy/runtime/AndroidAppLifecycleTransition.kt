package com.ufo.galaxy.runtime

/**
 * PR-60 — Explicit Android app-level lifecycle transition model for the participant runtime.
 *
 * Models the Android application lifecycle events that are **relevant to the cross-device
 * participant runtime** and must be handled by [RuntimeController] in a consistent,
 * reviewable way.  This enum makes lifecycle authority explicit: every app-level event
 * that can affect the runtime has a named transition here, so the behaviour is observable
 * and testable rather than implicit and scattered.
 *
 * ## Design intent
 *
 * Before PR-60, app-level lifecycle handling was split across:
 * - [com.ufo.galaxy.ui.viewmodel.MainViewModel.onResume] → `runtimeController.connectIfEnabled()`
 * - [com.ufo.galaxy.ui.viewmodel.MainViewModel.onPause] → no-op (connection preserved)
 * - [com.ufo.galaxy.service.GalaxyConnectionService.onStartCommand] → `runtimeController.connectIfEnabled()`
 *
 * These call-sites are correct but the **why** was implicit.  [AndroidAppLifecycleTransition]
 * names each event and documents its expected runtime response, making it reviewable and
 * ensuring that new call-sites handle the right cases.
 *
 * ## Android vs V2 lifecycle authority boundary
 *
 * Android is the **participant-side** lifecycle authority.  It decides how to respond to
 * its own app-level events (foreground/background, process recreation, etc.).  V2 is the
 * **orchestration-side** authority: it decides whether to resume, suspend, or terminate
 * a session after observing [V2MultiDeviceLifecycleEvent] signals from Android.
 *
 * Android MUST NOT make session-continuation or task-reassignment decisions unilaterally.
 * It MUST report its lifecycle state to V2 and wait for V2's decision.
 *
 * ## Process recreation and durability
 *
 * [PROCESS_RECREATED] is the critical case: the process was killed by Android's low-memory
 * killer (or an equivalent mechanism) and recreated by the system.  In this case:
 *
 * - All in-memory runtime state ([DurableSessionContinuityRecord], `_runtimeAttachmentSessionId`,
 *   [ReconnectRecoveryState]) is **lost** — these are process-scoped, not persisted.
 * - [com.ufo.galaxy.data.AppSettings] state (crossDeviceEnabled, gatewayHost, etc.) **survives**
 *   — it is persisted in [android.content.SharedPreferences].
 * - The runtime restarts from [RuntimeController.RuntimeState.Idle] and calls
 *   [RuntimeController.connectIfEnabled] via the background-restore path.
 * - V2 will see a [V2MultiDeviceLifecycleEvent.DeviceConnected] (not Reconnected) because
 *   the attachment identity is fresh — V2 must treat process recreation as a new attachment,
 *   not as a resume.  See [AndroidLifecycleRecoveryContract] for the full boundary rules.
 *
 * ## Wire values
 *
 * [wireValue] is the stable, serialisable representation used in [GalaxyLogger] structured
 * log entries.  Do **not** rename [wireValue] after this PR ships without updating all
 * log-analysis tooling.
 *
 * @property wireValue Stable lowercase string identifying the transition in structured logs.
 * @property runtimeImplication Human-readable description of what [RuntimeController] must do.
 */
enum class AndroidAppLifecycleTransition(
    val wireValue: String,
    val runtimeImplication: String
) {

    /**
     * The app entered the foreground (Activity resumed, service binding established, or
     * service start-command received with cross-device enabled).
     *
     * **Runtime action**: [RuntimeController.connectIfEnabled] — restores the WS connection
     * if cross-device is enabled, no-op otherwise.  Does NOT re-create a new session era if
     * the runtime is already [RuntimeController.RuntimeState.Active].
     */
    FOREGROUND(
        wireValue = "foreground",
        runtimeImplication = "connectIfEnabled: restore WS if cross-device on; no-op otherwise"
    ),

    /**
     * The app entered the background (Activity paused/stopped, all UI components hidden).
     *
     * **Runtime action**: preserve the WS connection and attached session — Android does NOT
     * disconnect when backgrounded.  The runtime continues accepting delegated tasks while
     * backgrounded so that background execution (e.g. via a foreground service) is unaffected.
     *
     * The connection is only terminated by an explicit user action ([RUNTIME_STOPPED]) or by
     * the system killing the process ([PROCESS_RECREATED]).
     */
    BACKGROUND(
        wireValue = "background",
        runtimeImplication = "preserve WS; no runtime state change; background execution continues"
    ),

    /**
     * The application process was recreated by the Android system after being killed
     * (low-memory killer, ANR, explicit kill, or dev tool).
     *
     * **Runtime action**: [RuntimeController.connectIfEnabled] via the background-restore
     * path.  All in-memory session state is lost; a new attachment era begins.  The runtime
     * reports [V2MultiDeviceLifecycleEvent.DeviceConnected] (not Reconnected) because the
     * durable session identity does not survive process death.
     *
     * See [AndroidLifecycleRecoveryContract.PROCESS_RECREATION_BOUNDARY] for the full list
     * of what survives and what must be re-synchronised from V2.
     */
    PROCESS_RECREATED(
        wireValue = "process_recreated",
        runtimeImplication = "connectIfEnabled via background_restore; new attachment era; session identity lost"
    ),

    /**
     * The user explicitly disabled cross-device or triggered a full stop
     * (toggle off, settings reconnect, kill-switch).
     *
     * **Runtime action**: [RuntimeController.stop] — disconnects the WS, detaches the
     * session, and transitions to [RuntimeController.RuntimeState.LocalOnly].
     */
    RUNTIME_STOPPED(
        wireValue = "runtime_stopped",
        runtimeImplication = "RuntimeController.stop: disconnect WS, detach session, LocalOnly"
    ),

    /**
     * The runtime encountered a configuration change (screen rotation, language change) that
     * recreates the Activity but does NOT kill the process.
     *
     * **Runtime action**: no-op.  The [RuntimeController] is process-scoped (held by
     * [com.ufo.galaxy.UFOGalaxyApplication]) and survives configuration changes.  The
     * Activity's ViewModel also survives via [com.ufo.galaxy.ui.viewmodel.MainViewModel].
     * After the Activity is recreated it calls [FOREGROUND], which calls [connectIfEnabled]
     * (no-op if already Active).
     */
    CONFIGURATION_CHANGE(
        wireValue = "configuration_change",
        runtimeImplication = "no-op; RuntimeController is process-scoped and survives config changes"
    )
}
