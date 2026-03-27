package com.ufo.galaxy.data

/**
 * AIP v3 类型重导出层（向后兼容）
 * ============================================================
 * 此文件保留用于旧代码兼容。所有活跃代码应直接从
 * com.ufo.galaxy.protocol 导入类型。
 *
 * 已删除的重复类型（现统一从 protocol/ 导入）：
 *   - AipV3MessageType       → 使用 protocol.MsgType
 *   - TaskSubmitPayload      → 使用 protocol.TaskSubmitPayload
 *   - TaskAssignPayload      → 使用 protocol.TaskAssignPayload
 *   - TaskResultPayload      → 使用 protocol.TaskResultPayload
 *   - CommandResultPayload   → 使用 protocol.CommandResultPayload
 *   - AipV3Envelope         → 使用 protocol.AipMessage
 *
 * 唯一保留的独立类型：
 *   - TaskExecutionStatus（见下方 re-export）
 *
 * 删除日期：2026-03-27
 * 变更原因：P0 — Android 端 AIP 模型重复定义，protocol/ 为单一事实来源
 */

// ── Re-export TaskExecutionStatus from protocol package ─────────────────────
// 旧代码（如 EdgeOrchestrator）从 com.ufo.galaxy.data.TaskExecutionStatus 导入，
// 新代码应直接从 com.ufo.galaxy.protocol.TaskExecutionStatus 导入。
// typealias 无法跨包使用，故此处保留独立定义（与 protocol/ 版本完全一致）。
import com.ufo.galaxy.protocol.TaskExecutionStatus

// ── 导入所有 protocol 包类型（替代本地重复定义）──────────────────────────────
import com.ufo.galaxy.protocol.AipMessage
import com.ufo.galaxy.protocol.MsgType
import com.ufo.galaxy.protocol.TaskSubmitPayload
import com.ufo.galaxy.protocol.TaskAssignPayload
import com.ufo.galaxy.protocol.TaskResultPayload
import com.ufo.galaxy.protocol.CommandResultPayload
import com.ufo.galaxy.protocol.TaskSubmitContext

/**
 * 向后兼容类型别名（deprecated）
 * 旧代码从 com.ufo.galaxy.data 包导入以下类型时可用。
 * 新代码禁止使用，应直接从 com.ufo.galaxy.protocol 导入。
 */
@Deprecated(
    message = "Use com.ufo.galaxy.protocol.MsgType directly.",
    replaceWith = ReplaceWith("com.ufo.galaxy.protocol.MsgType")
)
typealias AipV3MessageType = MsgType

@Deprecated(
    message = "Use com.ufo.galaxy.protocol.TaskSubmitPayload directly.",
    replaceWith = ReplaceWith("com.ufo.galaxy.protocol.TaskSubmitPayload")
)
typealias TaskSubmitPayload = com.ufo.galaxy.protocol.TaskSubmitPayload

@Deprecated(
    message = "Use com.ufo.galaxy.protocol.TaskAssignPayload directly.",
    replaceWith = ReplaceWith("com.ufo.galaxy.protocol.TaskAssignPayload")
)
typealias TaskAssignPayload = com.ufo.galaxy.protocol.TaskAssignPayload

@Deprecated(
    message = "Use com.ufo.galaxy.protocol.TaskResultPayload directly.",
    replaceWith = ReplaceWith("com.ufo.galaxy.protocol.TaskResultPayload")
)
typealias TaskResultPayload = com.ufo.galaxy.protocol.TaskResultPayload

@Deprecated(
    message = "Use com.ufo.galaxy.protocol.CommandResultPayload directly.",
    replaceWith = ReplaceWith("com.ufo.galaxy.protocol.CommandResultPayload")
)
typealias CommandResultPayload = com.ufo.galaxy.protocol.CommandResultPayload

@Deprecated(
    message = "Use com.ufo.galaxy.protocol.AipMessage directly.",
    replaceWith = ReplaceWith("com.ufo.galaxy.protocol.AipMessage")
)
typealias AipV3Envelope = com.ufo.galaxy.protocol.AipMessage
