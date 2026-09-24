package com.dag.core.enums;

/**
 * 节点状态机。
 * <p>流转：PENDING → RUNNING → SUCCESS / FAILED / WAIT_CONFIRM（回调 → SUCCESS / FAILED）；
 * 任意非终态可 cancel → CANCELLED；WAIT_CONFIRM 可 resume → PENDING；FAILED / WAIT_CONFIRM 可被 retry / 巡检重置 → PENDING。</p>
 */
public enum NodeState {

    PENDING,
    RUNNING,
    WAIT_CONFIRM,
    SUCCESS,
    FAILED,
    CANCELLED;

    public boolean isFinal() {
        return this == SUCCESS || this == FAILED || this == CANCELLED;
    }
}
