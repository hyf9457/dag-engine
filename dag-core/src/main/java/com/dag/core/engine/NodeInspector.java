package com.dag.core.engine;

import com.dag.core.lock.LockManager;
import com.dag.core.repository.NodeDO;
import com.dag.core.repository.NodeMapper;
import com.dag.core.repository.TaskDO;
import com.dag.core.repository.TaskMapper;
import com.dag.core.config.DagProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Date;
import java.util.List;

/**
 * 统一巡检器（合并原超时扫描 + 异常扫描，一次 tryLock 判定两件事）。
 * <p>对 RUNNING 节点 tryLock（不等待）：
 * ① 抢到锁 = 原执行器已死（watchdog 停/进程崩溃）→ CAS RUNNING→PENDING 重新调度；
 * ② 抢不到 = 执行器存活 → 超时判定（CAS RUNNING→FAILED，time_out=1，result=timeout）。</p>
 * <p>关键：锁判定先于超时判定，"服务挂掉"永不被误判为"业务超时失败"（K01/K02）。
 * WAIT_CONFIRM 不在本巡检范围（异步等待无超时，K09 由三出口处理）。
 * Redis 缺失降级（LocalLockManager）时巡检自动关闭（超时不判，02 §4.9）。</p>
 */
public class NodeInspector {

    private static final Logger log = LoggerFactory.getLogger(NodeInspector.class);

    private final LockManager lockManager;
    private final NodeMapper nodeMapper;
    private final TaskMapper taskMapper;
    private final NodeTransitionService transitionService;
    private final DagProperties properties;

    public NodeInspector(LockManager lockManager, NodeMapper nodeMapper, TaskMapper taskMapper,
                         NodeTransitionService transitionService, DagProperties properties) {
        this.lockManager = lockManager;
        this.nodeMapper = nodeMapper;
        this.taskMapper = taskMapper;
        this.transitionService = transitionService;
        this.properties = properties;
    }

    public void inspect() {
        if (!lockManager.isDistributed()) {
            return; // 降级：异常巡检自动关闭（超时不判）
        }
        List<NodeDO> running = nodeMapper.selectRunningNodes(properties.getScheduler().getBatchSize());
        for (NodeDO node : running) {
            try {
                inspectNode(node);
            } catch (Exception e) {
                log.error("inspect node failed, nodeId={}", node.getId(), e);
            }
        }
    }

    private void inspectNode(NodeDO node) {
        TaskDO task = taskMapper.selectByTaskId(node.getTaskId());
        if (task == null) {
            return;
        }
        if (lockManager.tryLock(node.getId(), 0)) {
            // 抢到锁 = 执行器已死（锁无人持有）→ 重置重新调度
            try {
                transitionService.persistResetInspect(node, task);
            } finally {
                lockManager.unlock(node.getId());
            }
        } else {
            // 执行器存活 → 超时判定（平台统一超时）
            Date deadline = new Date(System.currentTimeMillis()
                    - properties.getNode().getTimeoutSeconds() * 1000L);
            transitionService.persistTimeout(node, task, deadline);
        }
    }
}
