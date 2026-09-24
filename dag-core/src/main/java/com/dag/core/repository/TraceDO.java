package com.dag.core.repository;

import java.util.Date;

/**
 * task_trace 实体（单表双状态流水）。
 */
public class TraceDO {

    private String id;
    private String taskId;
    private String nodeId;
    private String bizNodeId;
    private String eventType;
    private String taskState;
    private String nodeState;
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
