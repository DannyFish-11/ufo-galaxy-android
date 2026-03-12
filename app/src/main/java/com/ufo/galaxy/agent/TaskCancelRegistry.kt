package com.ufo.galaxy.agent

import kotlinx.coroutines.Job
import java.util.concurrent.ConcurrentHashMap

/**
 * Thread-safe registry that tracks the active coroutine [Job] for each running task.
 *
 * Used by [com.ufo.galaxy.service.GalaxyConnectionService] to support on-demand
 * cancellation of in-flight [com.ufo.galaxy.protocol.MsgType.GOAL_EXECUTION] and
 * [com.ufo.galaxy.protocol.MsgType.PARALLEL_SUBTASK] tasks.
 *
 * ## Lifecycle
 * 1. Call [register] when a task coroutine is launched.
 * 2. Call [cancel] when a [com.ufo.galaxy.protocol.MsgType.TASK_CANCEL] is received.
 * 3. Call [deregister] in the task coroutine's `finally` block to release the slot.
 *
 * ## Idempotency
 * [cancel] is idempotent: calling it on an already-completed task returns `false`
 * (no-op) and does not throw.
 */
class TaskCancelRegistry {

    private val registry = ConcurrentHashMap<String, Job>()

    /**
     * Registers [job] as the running coroutine for [taskId].
     * If a previous job is already registered under the same [taskId] it is silently
     * replaced (the caller is responsible for correct lifecycle management).
     */
    fun register(taskId: String, job: Job) {
        registry[taskId] = job
    }

    /**
     * Removes the entry for [taskId], releasing the registry slot.
     * Should be called from the task coroutine's `finally` block.
     * No-op if [taskId] is not registered.
     */
    fun deregister(taskId: String) {
        registry.remove(taskId)
    }

    /**
     * Cancels the coroutine registered under [taskId], if any.
     *
     * @return `true` when an active job was found and cancelled;
     *         `false` when the task was not registered (already completed or never started).
     */
    fun cancel(taskId: String): Boolean {
        val job = registry.remove(taskId) ?: return false
        job.cancel()
        return true
    }

    /**
     * Returns `true` when a job is registered for [taskId] and is still active.
     */
    fun isActive(taskId: String): Boolean = registry[taskId]?.isActive == true

    /** Returns the number of currently registered (potentially active) tasks. */
    val size: Int get() = registry.size
}
