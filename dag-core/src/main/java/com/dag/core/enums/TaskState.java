package com.dag.core.enums;

/**
 * 任务状态（收敛态）。
 * <p>全节点 SUCCESS → SUCCESS；任一节点 FAILED → FAILED（CAS 一次）；用户 cancel → CANCELLED；
 * 其余状态（含存在 WAIT_CONFIRM 未确认）→ RUNNING。重试是唯一例外路径 FAILED → RUNNING。</p>
 */
public enum TaskState {

    RUNNING,
    SUCCESS,
    FAILED,
    CANCELLED;

    public boolean isFinal() {
        return this == SUCCESS || this == FAILED || this == CANCELLED;
    }
}
