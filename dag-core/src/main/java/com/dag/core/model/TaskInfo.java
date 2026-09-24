package com.dag.core.model;

import java.util.Date;
import java.util.Map;

/**
 * 任务信息（getTask 返回模型）。
 */
public class TaskInfo {

    private String taskId;
    private String bizType;
    private String bizId;
    private String state;       // RUNNING / SUCCESS / FAILED / CANCELLED
    private String graphJson;
    private Map<String, Object> bizArgs;
    private Date startedAt;
    private Date finishedAt;

    public String getTaskId() { return taskId; }
    public void setTaskId(String taskId) { this.taskId = taskId; }
    public String getBizType() { return bizType; }
    public void setBizType(String bizType) { this.bizType = bizType; }
    public String getBizId() { return bizId; }
    public void setBizId(String bizId) { this.bizId = bizId; }
    public String getState() { return state; }
    public void setState(String state) { this.state = state; }
    public String getGraphJson() { return graphJson; }
    public void setGraphJson(String graphJson) { this.graphJson = graphJson; }
    public Map<String, Object> getBizArgs() { return bizArgs; }
    public void setBizArgs(Map<String, Object> bizArgs) { this.bizArgs = bizArgs; }
    public Date getStartedAt() { return startedAt; }
    public void setStartedAt(Date startedAt) { this.startedAt = startedAt; }
    public Date getFinishedAt() { return finishedAt; }
    public void setFinishedAt(Date finishedAt) { this.finishedAt = finishedAt; }
}
