package com.dag.core.model;

import java.util.Date;

/**
 * 节点信息（getNodes 返回模型）。
 */
public class NodeInfo {

    private String id;             // 节点实例 id（回调/续跑使用）
    private String taskId;
    private String bizNodeId;
    private String handler;
    private String state;          // PENDING / RUNNING / WAIT_CONFIRM / SUCCESS / FAILED / CANCELLED
    private String result;         // handler 回写；超时/异常由引擎填
    private boolean timeOut;
    private int asyncType;
    private Date startedAt;
    private Date finishedAt;

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
    public boolean isTimeOut() { return timeOut; }
    public void setTimeOut(boolean timeOut) { this.timeOut = timeOut; }
    public int getAsyncType() { return asyncType; }
    public void setAsyncType(int asyncType) { this.asyncType = asyncType; }
    public Date getStartedAt() { return startedAt; }
    public void setStartedAt(Date startedAt) { this.startedAt = startedAt; }
    public Date getFinishedAt() { return finishedAt; }
    public void setFinishedAt(Date finishedAt) { this.finishedAt = finishedAt; }
}
