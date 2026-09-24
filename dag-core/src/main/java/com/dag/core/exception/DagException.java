package com.dag.core.exception;

/**
 * 引擎业务异常。
 * <p>错误码见接口设计 06 §4：100x 图校验 / 200x 任务 / 300x 状态 / 4001 回调幂等 / 5001 系统 / 9001 参数。</p>
 * <p>幂等 / 状态不匹配场景不抛异常，API 返回 false 或已有数据。</p>
 */
public class DagException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final int code;

    public DagException(int code, String message) {
        super(message);
        this.code = code;
    }

    public DagException(int code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public int getCode() {
        return code;
    }
}
