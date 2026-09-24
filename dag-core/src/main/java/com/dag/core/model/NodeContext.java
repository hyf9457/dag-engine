package com.dag.core.model;

import java.util.Map;

/**
 * 节点执行上下文（handler 入参）。
 * <p>参数分层：bizArgs = 任务全局业务参数（submit 接口传入）；
 * nodeArgs = 节点业务参数（graph_json nodes[].biz_args）；
 * upstreamResults = 上游节点结果（key = biz_node_id，value = result 反序列化）。</p>
 */
public class NodeContext {

    private String taskId;
    private String nodeInstanceId;
    private String bizNodeId;
    private String bizType;
    private String bizId;
    private Map<String, Object> bizArgs;
    private Map<String, Object> nodeArgs;
    private Map<String, Object> upstreamResults;

    public String getTaskId() { return taskId; }
    public void setTaskId(String taskId) { this.taskId = taskId; }
    public String getNodeInstanceId() { return nodeInstanceId; }
    public void setNodeInstanceId(String nodeInstanceId) { this.nodeInstanceId = nodeInstanceId; }
    public String getBizNodeId() { return bizNodeId; }
    public void setBizNodeId(String bizNodeId) { this.bizNodeId = bizNodeId; }
    public String getBizType() { return bizType; }
    public void setBizType(String bizType) { this.bizType = bizType; }
    public String getBizId() { return bizId; }
    public void setBizId(String bizId) { this.bizId = bizId; }
    public Map<String, Object> getBizArgs() { return bizArgs; }
    public void setBizArgs(Map<String, Object> bizArgs) { this.bizArgs = bizArgs; }
    public Map<String, Object> getNodeArgs() { return nodeArgs; }
    public void setNodeArgs(Map<String, Object> nodeArgs) { this.nodeArgs = nodeArgs; }
    public Map<String, Object> getUpstreamResults() { return upstreamResults; }
    public void setUpstreamResults(Map<String, Object> upstreamResults) { this.upstreamResults = upstreamResults; }
}
