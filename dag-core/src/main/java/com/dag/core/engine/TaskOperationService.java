package com.dag.core.engine;

import com.dag.core.enums.EventType;
import com.dag.core.enums.NodeState;
import com.dag.core.enums.RetryMode;
import com.dag.core.repository.NodeDO;
import com.dag.core.repository.NodeMapper;
import com.dag.core.repository.TaskDO;
import com.dag.core.repository.TaskMapper;
import com.dag.core.trace.TraceRecorder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 任务级控制操作事务服务（取消 / 重试 / 强制终态 / 提交落库）。
 * <p>所有操作 = 任务 CAS + 节点批量 + 流水，短事务原子提交。</p>
 */
public class TaskOperationService {

    private static final Logger log = LoggerFactory.getLogger(TaskOperationService.class);

    private final TaskMapper taskMapper;
    private final NodeMapper nodeMapper;
    private final TraceRecorder traceRecorder;

    public TaskOperationService(TaskMapper taskMapper, NodeMapper nodeMapper, TraceRecorder traceRecorder) {
        this.taskMapper = taskMapper;
        this.nodeMapper = nodeMapper;
        this.traceRecorder = traceRecorder;
    }

    /** 提交落库：task + nodes + 流水 TASK_SUBMIT（幂等由调用方先查 + UNIQUE 兜底） */
    @Transactional
    public void submitNew(TaskDO task, List<NodeDO> nodes, String source) {
        taskMapper.insert(task);
        if (!nodes.isEmpty()) {
            nodeMapper.insertBatch(nodes);
        }
        traceRecorder.taskEvent(task.getTaskId(), EventType.TASK_SUBMIT, task.getState(), source);
    }

    /** 取消：仅 RUNNING（CAS）+ 非终态节点 → CANCELLED + 流水 TASK_CANCEL + NODE_CANCEL×N；返回是否生效 */
    @Transactional
    public boolean cancelTask(TaskDO task) {
        int rows = taskMapper.cancel(task.getTaskId());
        if (rows == 0) {
            return false; // 已非 RUNNING（终态/他方已取消）
        }
        List<NodeDO> nodes = nodeMapper.selectByTaskId(task.getTaskId());
        nodeMapper.cancelNodes(task.getTaskId());
        traceRecorder.taskEvent(task.getTaskId(), EventType.TASK_CANCEL, "CANCELLED", null);
        for (NodeDO node : nodes) {
            if (!NodeState.valueOf(node.getState()).isFinal()) {
                traceRecorder.nodeEvent(task.getTaskId(), node.getId(), node.getBizNodeId(),
                        EventType.NODE_CANCEL, "CANCELLED", NodeState.CANCELLED.name(), null);
            }
        }
        return true;
    }

    /** 重试：仅 FAILED（CAS FAILED→RUNNING）+ 按 mode 重置节点 + 流水 RETRY + NODE_RESET×N；返回是否生效 */
    @Transactional
    public boolean retryTask(TaskDO task, RetryMode mode) {
        int rows = taskMapper.resetToRunning(task.getTaskId());
        if (rows == 0) {
            return false; // 已非 FAILED
        }
        List<NodeDO> nodes = nodeMapper.selectByTaskId(task.getTaskId());
        if (mode == RetryMode.CONTINUE) {
            nodeMapper.resetForContinue(task.getTaskId());
        } else {
            nodeMapper.resetAll(task.getTaskId());
        }
        traceRecorder.taskEvent(task.getTaskId(), EventType.RETRY, "RUNNING", "mode=" + mode.name());
        for (NodeDO node : nodes) {
            boolean shouldReset = mode == RetryMode.RESTART
                    || NodeState.FAILED.name().equals(node.getState())
                    || NodeState.WAIT_CONFIRM.name().equals(node.getState());
            if (shouldReset) {
                traceRecorder.nodeEvent(task.getTaskId(), node.getId(), node.getBizNodeId(),
                        EventType.NODE_RESET, "RUNNING", NodeState.PENDING.name(),
                        mode == RetryMode.RESTART ? "source=retry-restart" : "source=retry");
            }
        }
        return true;
    }

    /** 强制终态：任务 RUNNING → 指定终态（CAS）+ 节点批量置终态 + 流水 FORCE_FINALIZE；返回是否生效 */
    @Transactional
    public boolean forceFinalize(TaskDO task, String state) {
        int rows = taskMapper.converge(task.getTaskId(), state);
        if (rows == 0) {
            return false;
        }
        nodeMapper.finalizeNodes(task.getTaskId(), state);
        traceRecorder.taskEvent(task.getTaskId(), EventType.FORCE_FINALIZE, state, null);
        return true;
    }
}
