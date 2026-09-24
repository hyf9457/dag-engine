package com.dag.core.engine;

import com.dag.core.enums.NodeState;
import com.dag.core.model.DagGraph;
import com.dag.core.repository.NodeDO;
import com.dag.core.repository.NodeMapper;
import com.dag.core.repository.TaskDO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 节点调度与推进（push 主路径 + 兜底扫描共用入口）。
 * <p>提交执行线程池；执行线程内完成 加锁 → CAS 领取 → handler → 终态 → 释放锁（锁持有者=执行线程，Redisson 语义正确）。</p>
 * <p>EX-02：执行池满（RejectedExecutionException）→ 节点保持 PENDING（锁尚未获取），下轮调度/兜底再试，不会"锁在无人执行"。</p>
 */
public class ClaimService {

    private static final Logger log = LoggerFactory.getLogger(ClaimService.class);

    private final ThreadPoolExecutor executor;
    private final NodeExecutor nodeExecutor;
    private final NodeMapper nodeMapper;

    public ClaimService(ThreadPoolExecutor executor, NodeExecutor nodeExecutor, NodeMapper nodeMapper) {
        this.executor = executor;
        this.nodeExecutor = nodeExecutor;
        this.nodeMapper = nodeMapper;
    }

    /** 提交单个节点执行（异步，执行线程内领取） */
    public void submitNode(NodeDO node, TaskDO task, DagGraph graph) {
        try {
            executor.execute(() -> nodeExecutor.execute(node, task, graph));
        } catch (RejectedExecutionException e) {
            log.warn("executor pool full, node={} keep PENDING, will retry next scan", node.getId());
        }
    }

    /** push 推进：节点终态后评估直接后继，前驱全 SUCCESS 则提交执行 */
    public void pushDownstream(TaskDO task, DagGraph graph, String fromBizNodeId) {
        Set<String> successors = graph.getSuccessors(fromBizNodeId);
        if (successors.isEmpty()) {
            return;
        }
        List<NodeDO> nodes = nodeMapper.selectByTaskId(task.getTaskId());
        Map<String, NodeDO> byBizId = new HashMap<>();
        Map<String, String> stateByBizId = new HashMap<>();
        for (NodeDO n : nodes) {
            byBizId.put(n.getBizNodeId(), n);
            stateByBizId.put(n.getBizNodeId(), n.getState());
        }
        for (String succ : successors) {
            boolean allPredecessorsSuccess = true;
            for (String pre : graph.getPredecessors(succ)) {
                if (!NodeState.SUCCESS.name().equals(stateByBizId.get(pre))) {
                    allPredecessorsSuccess = false;
                    break;
                }
            }
            if (!allPredecessorsSuccess) {
                continue;
            }
            NodeDO next = byBizId.get(succ);
            if (next != null && NodeState.PENDING.name().equals(next.getState())) {
                submitNode(next, task, graph);
            }
        }
    }
}
