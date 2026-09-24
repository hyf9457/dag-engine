package com.dag.core.model;

import java.util.Map;

/**
 * 图节点定义（graph_json nodes[]）。
 */
public class GraphNode {

    /** 用户自定义节点唯一 id（任务内唯一） */
    private String bizNodeId;

    /** 执行器 Spring Bean 名（与 @Component 名大小写敏感精确一致，已拍板） */
    private String handler;

    /** 0 同步 / 1 异步（声明标记；运行期以 ExecutionResult.kind 为准） */
    private int asyncType;

    /** 节点业务参数（执行器经 NodeContext.nodeArgs 读取） */
    private Map<String, Object> bizArgs;

    public GraphNode() {
    }

    public GraphNode(String bizNodeId, String handler, int asyncType, Map<String, Object> bizArgs) {
        this.bizNodeId = bizNodeId;
        this.handler = handler;
        this.asyncType = asyncType;
        this.bizArgs = bizArgs;
    }

    public String getBizNodeId() { return bizNodeId; }
    public void setBizNodeId(String bizNodeId) { this.bizNodeId = bizNodeId; }
    public String getHandler() { return handler; }
    public void setHandler(String handler) { this.handler = handler; }
    public int getAsyncType() { return asyncType; }
    public void setAsyncType(int asyncType) { this.asyncType = asyncType; }
    public Map<String, Object> getBizArgs() { return bizArgs; }
    public void setBizArgs(Map<String, Object> bizArgs) { this.bizArgs = bizArgs; }
}
