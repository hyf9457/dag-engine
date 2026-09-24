package com.dag.core.enums;

/**
 * 重试模式。
 * <p>CONTINUE：仅重置非 SUCCESS 节点（FAILED / WAIT_CONFIRM → PENDING），成功节点保留（已拍板口径）；
 * RESTART：全部节点重置为 PENDING，全量重跑。</p>
 */
public enum RetryMode {
    CONTINUE,
    RESTART
}
