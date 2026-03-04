package com.ufo.galaxy.network

import com.ufo.galaxy.model.AIPMessage

class GalaxyWebSocketClient {
    // Existing code

    fun capability_report(version: String) {
        // Changed to send 'version' instead of 'protocol_version'
        val payload = mapOf(
            "version" to version
        )
        // Existing logic
    }

    fun sendDiagnostics(
        errorType: String,
        errorContext: String,
        taskId: String,
        nodeName: String,
        deviceId: String
    ) {
        // Sends a top-level message with type 'diagnostics_payload'
        val diagnosticsPayload = mapOf(
            "type" to "diagnostics_payload",
            "error_type" to errorType,
            "error_context" to errorContext,
            "task_id" to taskId,
            "node_name" to nodeName,
            "device_id" to deviceId,
            "version" to version,
            "timestamp" to System.currentTimeMillis()
        )
        // Existing logic to send diagnosticsPayload
    }
    // Other existing methods
}