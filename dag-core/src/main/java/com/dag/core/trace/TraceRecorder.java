package com.dag.core.trace;

import com.dag.core.enums.EventType;
import com.dag.core.repository.TraceDO;
import com.dag.core.repository.TraceMapper;
import com.dag.core.util.IdGenerator;

/**
 * 流水记录器（单表双状态 task_trace）。
 * <p>规则（05 §2.4）：任务级事件只填 task_state；节点级事件同时填 task_state（快照）+ node_state；
 * 节点终态触发任务收敛时同条流水 task_state 直接记收敛终态（最后一行=任务最终状态）。</p>
 */
public class TraceRecorder {

    private final TraceMapper traceMapper;

    public TraceRecorder(TraceMapper traceMapper) {
        this.traceMapper = traceMapper;
    }

    /** 任务级事件 */
    public void taskEvent(String taskId, EventType type, String taskState, String detail) {
        TraceDO trace = new TraceDO();
        trace.setId(IdGenerator.newId());
        trace.setTaskId(taskId);
        trace.setEventType(type.name());
        trace.setTaskState(taskState);
        trace.setDetail(detail);
        traceMapper.insert(trace);
    }

    /** 节点级事件 */
    public void nodeEvent(String taskId, String nodeId, String bizNodeId,
                          EventType type, String taskState, String nodeState, String detail) {
        TraceDO trace = new TraceDO();
        trace.setId(IdGenerator.newId());
        trace.setTaskId(taskId);
        trace.setNodeId(nodeId);
        trace.setBizNodeId(bizNodeId);
        trace.setEventType(type.name());
        trace.setTaskState(taskState);
        trace.setNodeState(nodeState);
        trace.setDetail(detail);
        traceMapper.insert(trace);
    }
}
