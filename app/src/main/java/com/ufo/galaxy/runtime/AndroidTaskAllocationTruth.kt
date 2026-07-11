package com.ufo.galaxy.runtime

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

enum class TaskAllocationPathClass(val wireValue: String) {
    CANONICAL_DELEGATED_DISPATCH("canonical_delegated_dispatch"),
    CANONICAL_FALLBACK_LOCAL("canonical_fallback_local"),
    COMPAT_LEGACY_BYPASS("compat_legacy_bypass");

    companion object {
        fun fromWireValue(value: String?): TaskAllocationPathClass? =
            entries.firstOrNull { it.wireValue == value }
    }
}

enum class TaskAllocationPhase(val wireValue: String) {
    ALLOCATION_REQUESTED("allocation_requested"),
    EXECUTOR_SELECTED("executor_selected"),
    EXECUTION_IN_FLIGHT("execution_in_flight"),
    EXECUTION_TERMINALIZING("execution_terminalizing"),
    RECONCILIATION_PENDING("reconciliation_pending"),
    CLOSED("closed");

    companion object {
        fun fromWireValue(value: String?): TaskAllocationPhase? =
            entries.firstOrNull { it.wireValue == value }
    }
}

enum class TaskAllocationClosureClass(val wireValue: String) {
    RESULT("result"),
    CANCELLED("cancelled"),
    FAILED("failed"),
    INTERRUPTED("interrupted");

    companion object {
        fun fromWireValue(value: String?): TaskAllocationClosureClass? =
            entries.firstOrNull { it.wireValue == value }
    }
}

enum class TaskAllocationTransitionEvent(val wireValue: String) {
    ALLOCATION_REQUESTED("allocation_requested"),
    EXECUTOR_SELECTED("executor_selected"),
    EXECUTION_STARTED("execution_started"),
    STATUS_UPDATED("status_updated"),
    RECONCILIATION_MARKED("reconciliation_marked"),
    CLOSURE_RECORDED("closure_recorded");

    companion object {
        fun fromWireValue(value: String?): TaskAllocationTransitionEvent? =
            entries.firstOrNull { it.wireValue == value }
    }
}

data class TaskAllocationTransition(
    val event: TaskAllocationTransitionEvent,
    val fromPhase: TaskAllocationPhase?,
    val toPhase: TaskAllocationPhase,
    val occurredAtMs: Long,
    val note: String? = null
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put(KEY_EVENT, event.wireValue)
        fromPhase?.let { put(KEY_FROM_PHASE, it.wireValue) }
        put(KEY_TO_PHASE, toPhase.wireValue)
        put(KEY_OCCURRED_AT_MS, occurredAtMs)
        note?.let { put(KEY_NOTE, it) }
    }

    fun toMap(): Map<String, Any?> = buildMap {
        put(KEY_EVENT, event.wireValue)
        put(KEY_FROM_PHASE, fromPhase?.wireValue)
        put(KEY_TO_PHASE, toPhase.wireValue)
        put(KEY_OCCURRED_AT_MS, occurredAtMs)
        put(KEY_NOTE, note)
    }

    companion object {
        private const val KEY_EVENT = "event"
        private const val KEY_FROM_PHASE = "from_phase"
        private const val KEY_TO_PHASE = "to_phase"
        private const val KEY_OCCURRED_AT_MS = "occurred_at_ms"
        private const val KEY_NOTE = "note"

        fun fromJson(json: JSONObject): TaskAllocationTransition? {
            val event = TaskAllocationTransitionEvent.fromWireValue(json.optString(KEY_EVENT))
                ?: return null
            val toPhase = TaskAllocationPhase.fromWireValue(json.optString(KEY_TO_PHASE))
                ?: return null
            val fromPhase = TaskAllocationPhase.fromWireValue(json.optString(KEY_FROM_PHASE))
            val occurredAtMs = json.optLong(KEY_OCCURRED_AT_MS, 0L)
            val note = json.optString(KEY_NOTE).takeIf { it.isNotBlank() }
            return TaskAllocationTransition(
                event = event,
                fromPhase = fromPhase,
                toPhase = toPhase,
                occurredAtMs = occurredAtMs,
                note = note
            )
        }
    }
}

data class TaskAllocationTruthRecord(
    val taskId: String,
    val requestedAllocationClass: String,
    val selectedExecutorRef: String,
    val inFlightOwnerRef: String,
    val executionLocation: String,
    val dispatchPlanId: String?,
    val temporalWorkflowRunId: String?,
    val allocationPathClass: TaskAllocationPathClass,
    val fallbackPathClass: TaskAllocationPathClass?,
    val participantLocalPhase: TaskAllocationPhase,
    val inFlightOwnership: Boolean,
    val requestedAtMs: Long,
    val selectedAtMs: Long,
    val inFlightAtMs: Long?,
    val closedAtMs: Long?,
    val closureClass: TaskAllocationClosureClass?,
    val lastUpdatedAtMs: Long,
    val transitions: List<TaskAllocationTransition>
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put(KEY_TASK_ID, taskId)
        put(KEY_REQUESTED_ALLOCATION_CLASS, requestedAllocationClass)
        put(KEY_SELECTED_EXECUTOR_REF, selectedExecutorRef)
        put(KEY_IN_FLIGHT_OWNER_REF, inFlightOwnerRef)
        put(KEY_EXECUTION_LOCATION, executionLocation)
        dispatchPlanId?.let { put(KEY_DISPATCH_PLAN_ID, it) }
        temporalWorkflowRunId?.let { put(KEY_TEMPORAL_WORKFLOW_RUN_ID, it) }
        put(KEY_ALLOCATION_PATH_CLASS, allocationPathClass.wireValue)
        fallbackPathClass?.let { put(KEY_FALLBACK_PATH_CLASS, it.wireValue) }
        put(KEY_PARTICIPANT_LOCAL_PHASE, participantLocalPhase.wireValue)
        put(KEY_IN_FLIGHT_OWNERSHIP, inFlightOwnership)
        put(KEY_REQUESTED_AT_MS, requestedAtMs)
        put(KEY_SELECTED_AT_MS, selectedAtMs)
        inFlightAtMs?.let { put(KEY_IN_FLIGHT_AT_MS, it) }
        closedAtMs?.let { put(KEY_CLOSED_AT_MS, it) }
        closureClass?.let { put(KEY_CLOSURE_CLASS, it.wireValue) }
        put(KEY_LAST_UPDATED_AT_MS, lastUpdatedAtMs)
        put(KEY_TRANSITIONS, JSONArray().apply {
            transitions.forEach { put(it.toJson()) }
        })
    }

    fun toMap(): Map<String, Any?> = buildMap {
        put(KEY_TASK_ID, taskId)
        put(KEY_REQUESTED_ALLOCATION_CLASS, requestedAllocationClass)
        put(KEY_SELECTED_EXECUTOR_REF, selectedExecutorRef)
        put(KEY_IN_FLIGHT_OWNER_REF, inFlightOwnerRef)
        put(KEY_EXECUTION_LOCATION, executionLocation)
        put(KEY_DISPATCH_PLAN_ID, dispatchPlanId)
        put(KEY_TEMPORAL_WORKFLOW_RUN_ID, temporalWorkflowRunId)
        put(KEY_ALLOCATION_PATH_CLASS, allocationPathClass.wireValue)
        put(KEY_FALLBACK_PATH_CLASS, fallbackPathClass?.wireValue)
        put(KEY_PARTICIPANT_LOCAL_PHASE, participantLocalPhase.wireValue)
        put(KEY_IN_FLIGHT_OWNERSHIP, inFlightOwnership)
        put(KEY_REQUESTED_AT_MS, requestedAtMs)
        put(KEY_SELECTED_AT_MS, selectedAtMs)
        put(KEY_IN_FLIGHT_AT_MS, inFlightAtMs)
        put(KEY_CLOSED_AT_MS, closedAtMs)
        put(KEY_CLOSURE_CLASS, closureClass?.wireValue)
        put(KEY_LAST_UPDATED_AT_MS, lastUpdatedAtMs)
        put(KEY_TRANSITIONS, transitions.map { it.toMap() })
    }

    companion object {
        const val KEY_TASK_ID = "task_id"
        private const val KEY_REQUESTED_ALLOCATION_CLASS = "requested_allocation_class"
        private const val KEY_SELECTED_EXECUTOR_REF = "selected_executor_ref"
        private const val KEY_IN_FLIGHT_OWNER_REF = "in_flight_owner_ref"
        private const val KEY_EXECUTION_LOCATION = "execution_location"
        private const val KEY_DISPATCH_PLAN_ID = "dispatch_plan_id"
        private const val KEY_TEMPORAL_WORKFLOW_RUN_ID = "temporal_workflow_run_id"
        private const val KEY_ALLOCATION_PATH_CLASS = "allocation_path_class"
        private const val KEY_FALLBACK_PATH_CLASS = "fallback_path_class"
        private const val KEY_PARTICIPANT_LOCAL_PHASE = "participant_local_phase"
        private const val KEY_IN_FLIGHT_OWNERSHIP = "in_flight_ownership"
        private const val KEY_REQUESTED_AT_MS = "requested_at_ms"
        private const val KEY_SELECTED_AT_MS = "selected_at_ms"
        private const val KEY_IN_FLIGHT_AT_MS = "in_flight_at_ms"
        private const val KEY_CLOSED_AT_MS = "closed_at_ms"
        private const val KEY_CLOSURE_CLASS = "closure_class"
        private const val KEY_LAST_UPDATED_AT_MS = "last_updated_at_ms"
        private const val KEY_TRANSITIONS = "transitions"

        fun fromJson(json: JSONObject): TaskAllocationTruthRecord? {
            val taskId = json.optString(KEY_TASK_ID).takeIf { it.isNotBlank() } ?: return null
            val requestedClass = json.optString(KEY_REQUESTED_ALLOCATION_CLASS)
                .takeIf { it.isNotBlank() } ?: return null
            val selectedExecutorRef = json.optString(KEY_SELECTED_EXECUTOR_REF)
                .takeIf { it.isNotBlank() } ?: return null
            val inFlightOwnerRef = json.optString(KEY_IN_FLIGHT_OWNER_REF)
                .takeIf { it.isNotBlank() } ?: return null
            val executionLocation = json.optString(KEY_EXECUTION_LOCATION)
                .takeIf { it.isNotBlank() } ?: return null
            val pathClass = TaskAllocationPathClass.fromWireValue(
                json.optString(KEY_ALLOCATION_PATH_CLASS)
            ) ?: return null
            val fallbackPathClass = TaskAllocationPathClass.fromWireValue(
                json.optString(KEY_FALLBACK_PATH_CLASS)
            )
            val phase = TaskAllocationPhase.fromWireValue(
                json.optString(KEY_PARTICIPANT_LOCAL_PHASE)
            ) ?: return null
            val transitions = buildList {
                val array = json.optJSONArray(KEY_TRANSITIONS) ?: JSONArray()
                for (i in 0 until array.length()) {
                    val entry = array.optJSONObject(i) ?: continue
                    TaskAllocationTransition.fromJson(entry)?.let { add(it) }
                }
            }
            return TaskAllocationTruthRecord(
                taskId = taskId,
                requestedAllocationClass = requestedClass,
                selectedExecutorRef = selectedExecutorRef,
                inFlightOwnerRef = inFlightOwnerRef,
                executionLocation = executionLocation,
                dispatchPlanId = json.optString(KEY_DISPATCH_PLAN_ID).takeIf { it.isNotBlank() },
                temporalWorkflowRunId = json.optString(KEY_TEMPORAL_WORKFLOW_RUN_ID)
                    .takeIf { it.isNotBlank() },
                allocationPathClass = pathClass,
                fallbackPathClass = fallbackPathClass,
                participantLocalPhase = phase,
                inFlightOwnership = json.optBoolean(KEY_IN_FLIGHT_OWNERSHIP, false),
                requestedAtMs = json.optLong(KEY_REQUESTED_AT_MS, 0L),
                selectedAtMs = json.optLong(KEY_SELECTED_AT_MS, 0L),
                inFlightAtMs = json.takeIf { it.has(KEY_IN_FLIGHT_AT_MS) }
                    ?.optLong(KEY_IN_FLIGHT_AT_MS),
                closedAtMs = json.takeIf { it.has(KEY_CLOSED_AT_MS) }
                    ?.optLong(KEY_CLOSED_AT_MS),
                closureClass = TaskAllocationClosureClass.fromWireValue(
                    json.optString(KEY_CLOSURE_CLASS)
                ),
                lastUpdatedAtMs = json.optLong(KEY_LAST_UPDATED_AT_MS, 0L),
                transitions = transitions
            )
        }
    }
}

data class AndroidTaskAllocationTruthSnapshot(
    val activeTaskId: String?,
    val activeTask: TaskAllocationTruthRecord?,
    val recentTaskAllocations: List<TaskAllocationTruthRecord>,
    val generatedAtMs: Long = System.currentTimeMillis(),
    val restoredFromDurableArtifact: Boolean = false,
    val restoredAtMs: Long? = null,
    val requiresLiveRevalidation: Boolean = false
) {
    fun toMap(): Map<String, Any?> = mapOf(
        KEY_SCHEMA_VERSION to SCHEMA_VERSION,
        KEY_GENERATED_AT_MS to generatedAtMs,
        KEY_ACTIVE_TASK_ID to activeTaskId,
        KEY_ACTIVE_TASK to activeTask?.toMap(),
        KEY_RECENT_TASK_ALLOCATIONS to recentTaskAllocations.map { it.toMap() },
        KEY_RESTORED_FROM_DURABLE_ARTIFACT to restoredFromDurableArtifact,
        KEY_RESTORED_AT_MS to restoredAtMs,
        KEY_REQUIRES_LIVE_REVALIDATION to requiresLiveRevalidation
    )

    fun toJson(): String = JSONObject().apply {
        put(KEY_SCHEMA_VERSION, SCHEMA_VERSION)
        put(KEY_GENERATED_AT_MS, generatedAtMs)
        activeTaskId?.let { put(KEY_ACTIVE_TASK_ID, it) }
        activeTask?.let { put(KEY_ACTIVE_TASK, it.toJson()) }
        put(KEY_RESTORED_FROM_DURABLE_ARTIFACT, restoredFromDurableArtifact)
        restoredAtMs?.let { put(KEY_RESTORED_AT_MS, it) }
        put(KEY_REQUIRES_LIVE_REVALIDATION, requiresLiveRevalidation)
        put(KEY_RECENT_TASK_ALLOCATIONS, JSONArray().apply {
            recentTaskAllocations.forEach { put(it.toJson()) }
        })
    }.toString()

    companion object {
        private const val TAG = "GALAXY:TASK_ALLOC_TRUTH"
        const val SCHEMA_VERSION = "1"
        const val KEY_SCHEMA_VERSION = "schema_version"
        const val KEY_GENERATED_AT_MS = "generated_at_ms"
        const val KEY_ACTIVE_TASK_ID = "active_task_id"
        const val KEY_ACTIVE_TASK = "active_task"
        const val KEY_RECENT_TASK_ALLOCATIONS = "recent_task_allocations"
        const val KEY_RESTORED_FROM_DURABLE_ARTIFACT = "restored_from_durable_artifact"
        const val KEY_RESTORED_AT_MS = "restored_at_ms"
        const val KEY_REQUIRES_LIVE_REVALIDATION = "requires_live_revalidation"

        fun fromJson(raw: String?): AndroidTaskAllocationTruthSnapshot? {
            if (raw.isNullOrBlank()) return null
            return try {
                val json = JSONObject(raw)
                val activeTaskId = json.optString(KEY_ACTIVE_TASK_ID).takeIf { it.isNotBlank() }
                val activeTask = json.optJSONObject(KEY_ACTIVE_TASK)
                    ?.let(TaskAllocationTruthRecord::fromJson)
                val recentAllocations = buildList {
                    val array = json.optJSONArray(KEY_RECENT_TASK_ALLOCATIONS) ?: JSONArray()
                    for (i in 0 until array.length()) {
                        val entry = array.optJSONObject(i) ?: continue
                        TaskAllocationTruthRecord.fromJson(entry)?.let { add(it) }
                    }
                }
                AndroidTaskAllocationTruthSnapshot(
                    activeTaskId = activeTaskId,
                    activeTask = activeTask,
                    recentTaskAllocations = recentAllocations,
                    generatedAtMs = json.optLong(KEY_GENERATED_AT_MS, 0L),
                    restoredFromDurableArtifact = json.optBoolean(
                        KEY_RESTORED_FROM_DURABLE_ARTIFACT,
                        false
                    ),
                    restoredAtMs = json.takeIf { it.has(KEY_RESTORED_AT_MS) }
                        ?.optLong(KEY_RESTORED_AT_MS),
                    requiresLiveRevalidation = json.optBoolean(
                        KEY_REQUIRES_LIVE_REVALIDATION,
                        false
                    )
                )
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse task allocation truth artifact: ${e.message}")
                null
            }
        }
    }
}

class AndroidTaskAllocationTruthLedger(
    private val historyLimit: Int = DEFAULT_HISTORY_LIMIT
) {
    private val recordsByTaskId = linkedMapOf<String, TaskAllocationTruthRecord>()
    private var restoredFromDurableArtifact: Boolean = false
    private var restoredAtMs: Long? = null
    private var requiresLiveRevalidation: Boolean = false

    fun recordAccepted(
        taskId: String,
        participantId: String?,
        hostDescriptor: RuntimeHostDescriptor?,
        fallbackAllowed: Boolean,
        dispatchPlanId: String? = null,
        temporalWorkflowRunId: String? = null,
        nowMs: Long = System.currentTimeMillis()
    ) {
        markLiveRevalidated()
        val executorRef = buildExecutorRef(participantId, hostDescriptor)
        val transitions = listOf(
            TaskAllocationTransition(
                event = TaskAllocationTransitionEvent.ALLOCATION_REQUESTED,
                fromPhase = null,
                toPhase = TaskAllocationPhase.ALLOCATION_REQUESTED,
                occurredAtMs = nowMs,
                note = "delegated_dispatch_received"
            ),
            TaskAllocationTransition(
                event = TaskAllocationTransitionEvent.EXECUTOR_SELECTED,
                fromPhase = TaskAllocationPhase.ALLOCATION_REQUESTED,
                toPhase = TaskAllocationPhase.EXECUTOR_SELECTED,
                occurredAtMs = nowMs,
                note = "android_participant_selected"
            ),
            TaskAllocationTransition(
                event = TaskAllocationTransitionEvent.EXECUTION_STARTED,
                fromPhase = TaskAllocationPhase.EXECUTOR_SELECTED,
                toPhase = TaskAllocationPhase.EXECUTION_IN_FLIGHT,
                occurredAtMs = nowMs,
                note = "execution_started"
            )
        )
        upsertRecord(
            TaskAllocationTruthRecord(
                taskId = taskId,
                requestedAllocationClass = "delegated_takeover_request",
                selectedExecutorRef = executorRef,
                inFlightOwnerRef = executorRef,
                executionLocation = "android_participant_runtime",
                dispatchPlanId = dispatchPlanId,
                temporalWorkflowRunId = temporalWorkflowRunId,
                allocationPathClass = TaskAllocationPathClass.CANONICAL_DELEGATED_DISPATCH,
                fallbackPathClass = if (fallbackAllowed) {
                    TaskAllocationPathClass.CANONICAL_FALLBACK_LOCAL
                } else {
                    null
                },
                participantLocalPhase = TaskAllocationPhase.EXECUTION_IN_FLIGHT,
                inFlightOwnership = true,
                requestedAtMs = nowMs,
                selectedAtMs = nowMs,
                inFlightAtMs = nowMs,
                closedAtMs = null,
                closureClass = null,
                lastUpdatedAtMs = nowMs,
                transitions = transitions
            )
        )
    }

    fun recordStatus(
        taskId: String,
        status: ActiveTaskStatus,
        nowMs: Long = System.currentTimeMillis()
    ) {
        markLiveRevalidated()
        val current = recordsByTaskId[taskId] ?: return
        if (current.participantLocalPhase == TaskAllocationPhase.CLOSED) return
        val targetPhase = when (status) {
            ActiveTaskStatus.CANCELLING,
            ActiveTaskStatus.FAILING -> TaskAllocationPhase.EXECUTION_TERMINALIZING
            else -> TaskAllocationPhase.EXECUTION_IN_FLIGHT
        }
        if (current.participantLocalPhase == targetPhase) return
        upsertRecord(
            current.copy(
                participantLocalPhase = targetPhase,
                lastUpdatedAtMs = nowMs,
                transitions = current.transitions + TaskAllocationTransition(
                    event = TaskAllocationTransitionEvent.STATUS_UPDATED,
                    fromPhase = current.participantLocalPhase,
                    toPhase = targetPhase,
                    occurredAtMs = nowMs,
                    note = status.wireValue
                )
            )
        )
    }

    fun recordClosed(
        taskId: String,
        closureClass: TaskAllocationClosureClass,
        requiresCanonicalReconciliation: Boolean,
        nowMs: Long = System.currentTimeMillis()
    ) {
        markLiveRevalidated()
        val current = recordsByTaskId[taskId] ?: return
        if (current.participantLocalPhase == TaskAllocationPhase.CLOSED &&
            current.closureClass == closureClass &&
            !current.inFlightOwnership
        ) {
            return
        }
        val transitions = current.transitions.toMutableList()
        var phase = current.participantLocalPhase
        if (requiresCanonicalReconciliation && phase != TaskAllocationPhase.RECONCILIATION_PENDING) {
            transitions += TaskAllocationTransition(
                event = TaskAllocationTransitionEvent.RECONCILIATION_MARKED,
                fromPhase = phase,
                toPhase = TaskAllocationPhase.RECONCILIATION_PENDING,
                occurredAtMs = nowMs,
                note = "canonical_reconciliation_required"
            )
            phase = TaskAllocationPhase.RECONCILIATION_PENDING
        }
        transitions += TaskAllocationTransition(
            event = TaskAllocationTransitionEvent.CLOSURE_RECORDED,
            fromPhase = phase,
            toPhase = TaskAllocationPhase.CLOSED,
            occurredAtMs = nowMs,
            note = closureClass.wireValue
        )
        upsertRecord(
            current.copy(
                participantLocalPhase = TaskAllocationPhase.CLOSED,
                inFlightOwnership = false,
                closedAtMs = nowMs,
                closureClass = closureClass,
                lastUpdatedAtMs = nowMs,
                transitions = transitions
            )
        )
    }

    fun snapshot(activeTaskId: String?): AndroidTaskAllocationTruthSnapshot {
        val ordered = recordsByTaskId.values.sortedByDescending { it.lastUpdatedAtMs }
        return AndroidTaskAllocationTruthSnapshot(
            activeTaskId = activeTaskId,
            activeTask = activeTaskId?.let { recordsByTaskId[it] },
            recentTaskAllocations = ordered,
            restoredFromDurableArtifact = restoredFromDurableArtifact,
            restoredAtMs = restoredAtMs,
            requiresLiveRevalidation = requiresLiveRevalidation
        )
    }

    fun restore(raw: String?, restoredNowMs: Long = System.currentTimeMillis()) {
        recordsByTaskId.clear()
        val restoredSnapshot = AndroidTaskAllocationTruthSnapshot.fromJson(raw)
        val restored = restoredSnapshot
            ?.recentTaskAllocations
            .orEmpty()
            .sortedBy { it.lastUpdatedAtMs }
        restored.forEach { upsertRecord(it) }
        restoredFromDurableArtifact = restored.isNotEmpty() || restoredSnapshot?.restoredFromDurableArtifact == true
        restoredAtMs = if (restoredFromDurableArtifact) {
            restoredNowMs
        } else {
            null
        }
        requiresLiveRevalidation = restoredFromDurableArtifact
    }

    fun toJson(activeTaskId: String?): String = snapshot(activeTaskId).toJson()

    private fun upsertRecord(record: TaskAllocationTruthRecord) {
        recordsByTaskId.remove(record.taskId)
        recordsByTaskId[record.taskId] = record
        prune()
    }

    private fun markLiveRevalidated() {
        requiresLiveRevalidation = false
    }

    private fun prune() {
        val keep = recordsByTaskId.values
            .sortedByDescending { it.lastUpdatedAtMs }
            .take(historyLimit)
            .associateBy { it.taskId }
        val iterator = recordsByTaskId.keys.iterator()
        while (iterator.hasNext()) {
            val taskId = iterator.next()
            if (!keep.containsKey(taskId)) {
                iterator.remove()
            }
        }
    }

    private fun buildExecutorRef(
        participantId: String?,
        hostDescriptor: RuntimeHostDescriptor?
    ): String {
        val pid = participantId?.takeIf { it.isNotBlank() } ?: "unknown_participant"
        val deviceId = hostDescriptor?.deviceId?.takeIf { it.isNotBlank() } ?: "unknown_device"
        val hostId = hostDescriptor?.hostId?.takeIf { it.isNotBlank() } ?: "unknown_host"
        return "android:$pid:$deviceId:$hostId"
    }

    companion object {
        const val DEFAULT_HISTORY_LIMIT = 24
    }
}
