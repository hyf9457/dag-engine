package com.dag.core.enums;

/**
 * 流水事件类型（单表双状态 task_trace）。
 * <p>任务级事件：TASK_SUBMIT / TASK_CANCEL / RETRY / FORCE_FINALIZE；
 * 节点级事件：NODE_CLAIM / NODE_SUCCESS / NODE_FAILED / NODE_WAIT_CONFIRM / NODE_CONFIRM /
 * NODE_TIMEOUT / NODE_RESET / RESUME / NODE_CANCEL。</p>
 */
public enum EventType {

    /** 任务提交（任务级） */
    TASK_SUBMIT,
    /** 节点被领取执行（节点级） */
    NODE_CLAIM,
    /** 节点同步成功（节点级） */
    NODE_SUCCESS,
    /** 节点失败（节点级：handler failure / 抛异常 / 回调 fail） */
    NODE_FAILED,
    /** 节点异步挂起（节点级） */
    NODE_WAIT_CONFIRM,
    /** 异步回调确认成功/失败（节点级，经 confirm/fail） */
    NODE_CONFIRM,
    /** 节点超时置 FAILED（节点级） */
    NODE_TIMEOUT,
    /** 节点重置回 PENDING（节点级，source=inspect/retry/retry-restart） */
    NODE_RESET,
    /** 断点续跑（节点级） */
    RESUME,
    /** 节点被取消（节点级） */
    NODE_CANCEL,
    /** 任务被取消（任务级） */
    TASK_CANCEL,
    /** 任务重试（任务级，mode=CONTINUE/RESTART） */
    RETRY,
    /** 运维强制终态（任务级，state=SUCCESS/FAILED/CANCELLED） */
    FORCE_FINALIZE
}
