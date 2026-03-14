package com.ufo.galaxy.ui

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Process-wide notifier for registration and connection failure events that should
 * be surfaced as dialogs (or toasts) in **both** the main UI and the floating window.
 *
 * Both [com.ufo.galaxy.ui.MainActivity] and [com.ufo.galaxy.service.EnhancedFloatingService]
 * collect [failures] and display the message appropriately.
 *
 * This is a singleton object so that any component – [RuntimeController], services,
 * or background coroutines – can emit a failure without holding a direct reference to
 * an Activity or Service context.
 *
 * Usage:
 * ```kotlin
 * // From any thread/coroutine:
 * RegistrationFailureNotifier.emit("注册失败：设备 ID 无效")
 *
 * // In MainActivity or EnhancedFloatingService:
 * lifecycleScope.launch {
 *     RegistrationFailureNotifier.failures.collect { reason ->
 *         showErrorDialog(reason)
 *     }
 * }
 * ```
 */
object RegistrationFailureNotifier {

    private val _failures = MutableSharedFlow<String>(extraBufferCapacity = 8)

    /**
     * Stream of human-readable failure reasons.
     * New subscribers receive only failures emitted after they start collecting.
     */
    val failures: SharedFlow<String> = _failures.asSharedFlow()

    /**
     * Emits [reason] to all active collectors.
     *
     * Safe to call from any thread; uses [MutableSharedFlow.tryEmit] which never blocks.
     * Returns `true` when at least one subscriber received the event, `false` when the
     * buffer is full and no subscriber consumed it in time (unlikely with the 8-event buffer).
     */
    fun emit(reason: String): Boolean = _failures.tryEmit(reason)
}
