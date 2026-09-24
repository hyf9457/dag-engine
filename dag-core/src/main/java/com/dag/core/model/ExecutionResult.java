package com.dag.core.model;

/**
 * 节点执行结果（handler 返回值）。
 * <p>运行期以 kind 为准：PENDING 进入 WAIT_CONFIRM（async_type 仅声明标记）；SUCCESS 写入 result 供下游 upstreamResults 读取。</p>
 */
public class ExecutionResult {

    public enum Kind { SUCCESS, FAILURE, PENDING }

    private final Kind kind;
    private final Object data;
    private final String error;

    private ExecutionResult(Kind kind, Object data, String error) {
        this.kind = kind;
        this.data = data;
        this.error = error;
    }

    /** 同步完成 */
    public static ExecutionResult success(Object data) {
        return new ExecutionResult(Kind.SUCCESS, data, null);
    }

    /** 同步显式失败 */
    public static ExecutionResult failure(String error) {
        return new ExecutionResult(Kind.FAILURE, null, error);
    }

    /** 异步挂起：节点进入 WAIT_CONFIRM，等待 confirm/fail */
    public static ExecutionResult pending() {
        return new ExecutionResult(Kind.PENDING, null, null);
    }

    public Kind getKind() { return kind; }
    public Object getData() { return data; }
    public String getError() { return error; }
}
