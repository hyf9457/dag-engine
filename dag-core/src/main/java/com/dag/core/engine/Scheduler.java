package com.dag.core.engine;

import com.dag.core.enums.NodeState;
import com.dag.core.graph.GraphParser;
import com.dag.core.model.DagGraph;
import com.dag.core.repository.NodeDO;
import com.dag.core.repository.NodeMapper;
import com.dag.core.repository.TaskDO;
import com.dag.core.repository.TaskMapper;
import com.dag.core.config.DagProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 调度器（兜底扫描）：周期性加载 RUNNING 任务，重建 DAG，领取就绪 PENDING 节点。
 * <p>职责：崩溃丢失的推进（EX-B02/B05/B06）、提交后根节点触发、重试/续跑后的再触发。
 * 与 push 主路径汇合同一领取（执行线程内 CAS），天然去重。</p>
 */
public class Scheduler {

    private static final Logger log = LoggerFactory.getLogger(Scheduler.class);

    private final TaskMapper taskMapper;
    private final NodeMapper nodeMapper;
    private final GraphParser graphParser;
    private final ClaimService claimService;
    private final TaskStateMachine stateMachine;
    private final DagProperties properties;

    public Scheduler(TaskMapper taskMapper, NodeMapper nodeMapper, GraphParser graphParser,
                     ClaimService claimService, TaskStateMachine stateMachine, DagProperties properties) {
        this.taskMapper = taskMapper;
        this.nodeMapper = nodeMapper;
        this.graphParser = graphParser;
        this.claimService = claimService;
        this.stateMachine = stateMachine;
        this.properties = properties;
    }

    public void scanAndSchedule() {
        List<TaskDO> tasks = taskMapper.selectRunningTasks(properties.getScheduler().getBatchSize());
        for (TaskDO task : tasks) {
            try {
                scheduleTask(task);
            } catch (Exception e) {
                // 单任务异常不阻断整体扫描
                log.error("schedule task failed, taskId={}", task.getTaskId(), e);
            }
        }
    }

    /** 调度单个任务（提交后根节点即时触发 / 重试与续跑后重新推进 / 兜底扫描复用） */
    public void scheduleTask(TaskDO task) {
        // 收敛兜底（幂等单条 CAS）：并发节点终态事务基于各自快照判定，可能双方都"看不到"对方提交而漏收敛；
        // 任何推进入口先补一次收敛，已收敛为终态则不再推进（EX-B07 收敛漏触发自愈路径）。
        if (stateMachine.convergeIfNeeded(task.getTaskId()) != null) {
            return;
        }
        DagGraph graph = graphParser.parse(task.getGraphJson());
        List<NodeDO> nodes = nodeMapper.selectByTaskId(task.getTaskId());
        Map<String, String> stateByBizId = new HashMap<>();
        Map<String, NodeDO> nodeByBizId = new HashMap<>();
        for (NodeDO n : nodes) {
            stateByBizId.put(n.getBizNodeId(), n.getState());
            nodeByBizId.put(n.getBizNodeId(), n);
        }
        for (NodeDO node : nodes) {
            if (!NodeState.PENDING.name().equals(node.getState())) {
                continue;
            }
            // 前驱全 SUCCESS 才可执行（根节点无前驱天然满足）
            boolean ready = true;
            for (String pre : graph.getPredecessors(node.getBizNodeId())) {
                if (!NodeState.SUCCESS.name().equals(stateByBizId.get(pre))) {
                    ready = false;
                    break;
                }
            }
            if (ready) {
                claimService.submitNode(node, task, graph);
            }
        }
    }
}
