package com.dag.core.model;

import java.util.Date;

/**
 * 流水条目（getTrace 返回模型，单表双状态）。
 */
public class TraceEntry {

    private String id;
    private String taskId;
    private String nodeId;       // 节点级事件；任务级为空
    private String bizNodeId;
    private String eventType;    // TASK_SUBMIT / NODE_CLAIM / ...
    private String taskState;    // 事件后任务状态（快照，整体维度）
    private String nodeState;    // 事件后节点状态（节点级事件）
    private String detail;
    private Date createdAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getTaskId() { return taskId; }
    public void setTaskId(String taskId) { this.taskId = taskId; }
    public String getNodeId() { return nodeId; }
    public void setNodeId(String nodeId) { this.nodeId = nodeId; }
    public String getBizNodeId() { return bizNodeId; }
    public void setBizNodeId(String bizNodeId) { this.bizNodeId = bizNodeId; }
    public String getEventType() { return eventType; }
    public void setEventType(String eventType) { this.eventType = eventType; }
    public String getTaskState() { return taskState; }
    public void setTaskState(String taskState) { this.taskState = taskState; }
    public String getNodeState() { return nodeState; }
    public void setNodeState(String nodeState) { this.nodeState = nodeState; }
    public String getDetail() { return detail; }
    public void setDetail(String detail) { this.detail = detail; }
    public Date getCreatedAt() { return createdAt; }
    public void setCreatedAt(Date createdAt) { this.createdAt = createdAt; }
}
