package com.dag.core.repository;

import java.util.Date;

/**
 * node_instance 实体（节点表，提交时由 graph_json 解析生成）。
 */
public class NodeDO {

    private String id;
    private String taskId;
    private String bizNodeId;
    private String handler;
    private String state;   // PENDING / RUNNING / WAIT_CONFIRM / SUCCESS / FAILED / CANCELLED
    private String result;
    private String bizArgs;
    private Integer timeOut;
    private Integer asyncType;
    private Date startedAt;
    private Date finishedAt;
    private Date createdAt;
    private Date updatedAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getTaskId() { return taskId; }
    public void setTaskId(String taskId) { this.taskId = taskId; }
    public String getBizNodeId() { return bizNodeId; }
    public void setBizNodeId(String bizNodeId) { this.bizNodeId = bizNodeId; }
    public String getHandler() { return handler; }
    public void setHandler(String handler) { this.handler = handler; }
    public String getState() { return state; }
    public void setState(String state) { this.state = state; }
    public String getResult() { return result; }
    public void setResult(String result) { this.result = result; }
    public String getBizArgs() { return bizArgs; }
    public void setBizArgs(String bizArgs) { this.bizArgs = bizArgs; }
    public Integer getTimeOut() { return timeOut; }
    public void setTimeOut(Integer timeOut) { this.timeOut = timeOut; }
    public Integer getAsyncType() { return asyncType; }
    public void setAsyncType(Integer asyncType) { this.asyncType = asyncType; }
    public Date getStartedAt() { return startedAt; }
    public void setStartedAt(Date startedAt) { this.startedAt = startedAt; }
    public Date getFinishedAt() { return finishedAt; }
    public void setFinishedAt(Date finishedAt) { this.finishedAt = finishedAt; }
    public Date getCreatedAt() { return createdAt; }
    public void setCreatedAt(Date createdAt) { this.createdAt = createdAt; }
    public Date getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Date updatedAt) { this.updatedAt = updatedAt; }
}
