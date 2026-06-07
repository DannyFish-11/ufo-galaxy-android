package com.ufo.galaxy.protocol

import com.google.gson.Gson
import com.ufo.galaxy.runtime.AndroidUplinkLineageMetadataContract
import org.junit.Assert.assertTrue
import org.junit.Test

class UplinkLineageMetadataWirePresenceTest {
    private val gson = Gson()

    @Test
    fun `goal result payload carries lineage wire fields`() {
        val payload = GoalResultPayload(
            task_id = "task-wire-1",
            status = "success",
            uplink_lineage_schema_version = AndroidUplinkLineageMetadataContract.SCHEMA_VERSION,
            uplink_lineage_execution_id = "task-wire-1",
            uplink_lineage_emission_id = "result:task-wire-1:success",
            uplink_lineage_dedupe_key = "dedupe-wire-1",
            uplink_lineage_recovery_basis = "resumed-cleanly:none"
        )

        val json = gson.toJson(payload)
        assertTrue(json.contains("uplink_lineage_schema_version"))
        assertTrue(json.contains("uplink_lineage_execution_id"))
        assertTrue(json.contains("uplink_lineage_emission_id"))
        assertTrue(json.contains("uplink_lineage_dedupe_key"))
        assertTrue(json.contains("uplink_lineage_recovery_basis"))
    }

    @Test
    fun `snapshot payload carries lineage wire fields`() {
        val payload = DeviceStateSnapshotPayload(
            device_id = "device-wire-1",
            llama_cpp_available = false,
            ncnn_available = false,
            active_runtime_type = "CENTER",
            model_ready = false,
            accessibility_ready = false,
            overlay_ready = false,
            local_loop_ready = false,
            model_id = null,
            runtime_type = "CENTER",
            checksum_ok = false,
            mobilevlm_present = false,
            mobilevlm_checksum_ok = false,
            seeclick_present = false,
            pending_first_download = true,
            warmup_result = "unavailable",
            offline_queue_depth = 0,
            current_fallback_tier = "local_only",
            uplink_lineage_schema_version = AndroidUplinkLineageMetadataContract.SCHEMA_VERSION,
            uplink_lineage_execution_id = MsgType.DEVICE_STATE_SNAPSHOT.value,
            uplink_lineage_emission_id = "snapshot:1",
            uplink_lineage_dedupe_key = "dedupe-snapshot-1",
            uplink_lineage_recovery_basis = "recovering:reconnect"
        )

        val json = gson.toJson(payload)
        assertTrue(json.contains("uplink_lineage_schema_version"))
        assertTrue(json.contains("uplink_lineage_execution_id"))
        assertTrue(json.contains("uplink_lineage_emission_id"))
        assertTrue(json.contains("uplink_lineage_dedupe_key"))
        assertTrue(json.contains("uplink_lineage_recovery_basis"))
    }
}
