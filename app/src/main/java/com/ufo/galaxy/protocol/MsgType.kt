package com.ufo.galaxy.protocol

/**
 * AIP v3 消息类型常量（MsgType）
 *
 * 与 ufo-galaxy-realization-v2 中定义的消息类型保持一致。
 * 这是最小实现，保证编译通过，同时与现有 AIP v3 协议规范对齐。
 *
 * @author UFO³ Galaxy
 * @version 1.0
 */
object MsgType {
    // ── 下行（Gateway → Android） ────────────────────────────────────────────
    /** 任务下发 */
    const val TASK_ASSIGN = "task_assign"
    /** 目标执行 */
    const val GOAL_EXECUTION = "goal_execution"
    /** 并行子任务 */
    const val PARALLEL_SUBTASK = "parallel_subtask"
    /** 任务取消 */
    const val TASK_CANCEL = "task_cancel"

    // ── 上行（Android → Gateway） ────────────────────────────────────────────
    /** 任务提交（跨设备模式：本机→Gateway） */
    const val TASK_SUBMIT = "task_submit"
    /** 任务执行结果 */
    const val TASK_RESULT = "task_result"
    /** 目标执行结果 */
    const val GOAL_RESULT = "goal_result"
    /** 取消结果 */
    const val CANCEL_RESULT = "cancel_result"

    // ── 双向 ─────────────────────────────────────────────────────────────────
    /** 心跳 */
    const val HEARTBEAT = "heartbeat"
    /** 设备注册 */
    const val DEVICE_REGISTER = "device_register"
    /** 能力上报 */
    const val CAPABILITY_REPORT = "capability_report"
}
