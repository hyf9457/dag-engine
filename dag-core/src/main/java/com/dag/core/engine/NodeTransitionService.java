package com.dag.core.engine;

import com.dag.core.enums.EventType;
import com.dag.core.enums.NodeState;
import com.dag.core.repository.NodeDO;
import com.dag.core.repository.NodeMapper;
import com.dag.core.repository.TaskDO;
import com.dag.core.repository.TaskMapper;
import com.dag.core.trace.TraceRecorder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;

/**
 * 节点状态流转事务服务。
 * <p>核心约束（05 §3.6）：节点终态 + 任务收敛 + 流水写入 = 短事务原子提交，
 * 正常路径不丢收敛；提交前崩溃则节点终态未生效，由巡检/兜底重新处理。</p>
 * <p>所有 CAS 更新以影响行数判定：行数=1 生效；行数=0 表示状态已被其他路径抢占（竞态），本次作废。</p>
 */
public class NodeTransitionService {

    private static final Logger log = LoggerFactory.getLogger(NodeTransitionService.class);

    private final NodeMapper nodeMapper;
    private final TaskMapper taskMapper;
    private final TaskStateMachine stateMachine;
    private final TraceRecorder traceRecorder;

    public NodeTransitionService(NodeMapper nodeMapper, TaskMapper taskMapper,
                                 TaskStateMachine stateMachine, TraceRecorder traceRecorder) {
        this.nodeMapper = nodeMapper;
        this.taskMapper = taskMapper;
        this.stateMachine = stateMachine;
        this.traceRecorder = traceRecorder;
    }

    /** 同步成功：RUNNING → SUCCESS + 收敛 + 流水；返回是否生效（生效时调用方需 push 下游） */
    @Transactional
    public boolean persistSyncSuccess(NodeDO node, TaskDO task, String resultJson) {
        int rows = nodeMapper.finishSuccess(node.getId(), resultJson);
        if (rows == 0) {
            log.warn("finishSuccess CAS failed, node={}, state={}", node.getId(), node.getState());
            return false;
        }
        String convergeState = stateMachine.convergeIfNeeded(task.getTaskId());
        String taskState = convergeState != null ? convergeState : task.getState();
        traceRecorder.nodeEvent(task.getTaskId(), node.getId(), node.getBizNodeId(),
                EventType.NODE_SUCCESS, taskState, NodeState.SUCCESS.name(), resultJson);
        return true;
    }

    /** 同步失败：RUNNING → FAILED + 收敛 FAILED + 流水（返回 false 表示 CAS 未生效） */
    @Transactional
    public boolean persistSyncFailed(NodeDO node, TaskDO task, String error) {
        int rows = nodeMapper.finishFailed(node.getId(), error);
        if (rows == 0) {
            log.warn("finishFailed CAS failed, node={}", node.getId());
            return false;
        }
        String convergeState = stateMachine.convergeIfNeeded(task.getTaskId());
        String taskState = convergeState != null ? convergeState : task.getState();
        traceRecorder.nodeEvent(task.getTaskId(), node.getId(), node.getBizNodeId(),
                EventType.NODE_FAILED, taskState, NodeState.FAILED.name(), error);
        return true;
    }

    /** 异步挂起：RUNNING → WAIT_CONFIRM + 流水（任务保持 RUNNING，无超时） */
    @Transactional
    public boolean persistWaitConfirm(NodeDO node, TaskDO task) {
        int rows = nodeMapper.markWaitConfirm(node.getId());
        if (rows == 0) {
            log.warn("markWaitConfirm CAS failed, node={}", node.getId());
            return false;
        }
        traceRecorder.nodeEvent(task.getTaskId(), node.getId(), node.getBizNodeId(),
                EventType.NODE_WAIT_CONFIRM, task.getState(), NodeState.WAIT_CONFIRM.name(), null);
        return true;
    }

    /** 回调确认成功：WAIT_CONFIRM → SUCCESS + 收敛 + 流水；行数=0 幂等忽略（重复/晚到） */
    @Transactional
    public boolean persistConfirmSuccess(NodeDO node, TaskDO task, String resultJson) {
        int rows = nodeMapper.confirmSuccess(node.getId(), resultJson);
        if (rows == 0) {
            return false; // 重复/晚到回调，幂等忽略（4001 语义）
        }
        String convergeState = stateMachine.convergeIfNeeded(task.getTaskId());
        String taskState = convergeState != null ? convergeState : task.getState();
        traceRecorder.nodeEvent(task.getTaskId(), node.getId(), node.getBizNodeId(),
                EventType.NODE_CONFIRM, taskState, NodeState.SUCCESS.name(), resultJson);
        return true;
    }

    /** 回调失败：WAIT_CONFIRM → FAILED + 收敛 FAILED + 流水 */
    @Transactional
    public boolean persistConfirmFailed(NodeDO node, TaskDO task, String error) {
        int rows = nodeMapper.confirmFailed(node.getId(), error);
        if (rows == 0) {
            return false;
        }
        String convergeState = stateMachine.convergeIfNeeded(task.getTaskId());
        String taskState = convergeState != null ? convergeState : task.getState();
        traceRecorder.nodeEvent(task.getTaskId(), node.getId(), node.getBizNodeId(),
                EventType.NODE_CONFIRM, taskState, NodeState.FAILED.name(), error);
        return true;
    }

    /** 超时：RUNNING 且超阈值 → FAILED(time_out=1) + 收敛 + 流水 NODE_TIMEOUT */
    @Transactional
    public boolean persistTimeout(NodeDO node, TaskDO task, Date deadline) {
        int rows = nodeMapper.timeoutFail(node.getId(), deadline);
        if (rows == 0) {
            return false; // 状态已变（完成/被重置/被取消），本次超时判定作废
        }
        String convergeState = stateMachine.convergeIfNeeded(task.getTaskId());
        String taskState = convergeState != null ? convergeState : task.getState();
        traceRecorder.nodeEvent(task.getTaskId(), node.getId(), node.getBizNodeId(),
                EventType.NODE_TIMEOUT, taskState, NodeState.FAILED.name(),
                "timeoutSeconds=" + deadline.getTime() + ", now=" + System.currentTimeMillis());
        return true;
    }

    /** 巡检重置：RUNNING → PENDING（执行器已死）+ 流水 NODE_RESET(source=inspect) */
    @Transactional
    public boolean persistResetInspect(NodeDO node, TaskDO task) {
        int rows = nodeMapper.resetRunning(node.getId());
        if (rows == 0) {
            return false;
        }
        traceRecorder.nodeEvent(task.getTaskId(), node.getId(), node.getBizNodeId(),
                EventType.NODE_RESET, task.getState(), NodeState.PENDING.name(), "source=inspect");
        return true;
    }

    /** 续跑：WAIT_CONFIRM → PENDING + 流水 RESUME */
    @Transactional
    public boolean persistResume(NodeDO node, TaskDO task) {
        int rows = nodeMapper.resumePending(node.getId());
        if (rows == 0) {
            return false;
        }
        traceRecorder.nodeEvent(task.getTaskId(), node.getId(), node.getBizNodeId(),
                EventType.RESUME, task.getState(), NodeState.PENDING.name(), null);
        return true;
    }
}
