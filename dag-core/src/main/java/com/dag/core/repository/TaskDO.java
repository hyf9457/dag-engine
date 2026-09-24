package com.dag.core.repository;

import java.util.Date;

/**
 * task_instance 实体（任务表）。
 */
public class TaskDO {

    private String taskId;
    private String bizType;
    private String bizId;
    private String graphJson;
    private String state;   // RUNNING / SUCCESS / FAILED / CANCELLED
    private String bizArgs;
    private Date startedAt;
    private Date finishedAt;
    private Date createdAt;
    private Date updatedAt;

    public String getTaskId() { return taskId; }
    public void setTaskId(String taskId) { this.taskId = taskId; }
    public String getBizType() { return bizType; }
    public void setBizType(String bizType) { this.bizType = bizType; }
    public String getBizId() { return bizId; }
    public void setBizId(String bizId) { this.bizId = bizId; }
    public String getGraphJson() { return graphJson; }
    public void setGraphJson(String graphJson) { this.graphJson = graphJson; }
    public String getState() { return state; }
    public void setState(String state) { this.state = state; }
    public String getBizArgs() { return bizArgs; }
    public void setBizArgs(String bizArgs) { this.bizArgs = bizArgs; }
    public Date getStartedAt() { return startedAt; }
    public void setStartedAt(Date startedAt) { this.startedAt = startedAt; }
    public Date getFinishedAt() { return finishedAt; }
    public void setFinishedAt(Date finishedAt) { this.finishedAt = finishedAt; }
    public Date getCreatedAt() { return createdAt; }
    public void setCreatedAt(Date createdAt) { this.createdAt = createdAt; }
    public Date getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Date updatedAt) { this.updatedAt = updatedAt; }
}
