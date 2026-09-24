package com.dag.core.engine;

import com.dag.core.enums.EventType;
import com.dag.core.enums.NodeState;
import com.dag.core.exception.DagException;
import com.dag.core.handler.HandlerRegistry;
import com.dag.core.handler.NodeHandler;
import com.dag.core.lock.LockManager;
import com.dag.core.model.DagGraph;
import com.dag.core.model.ExecutionResult;
import com.dag.core.model.GraphNode;
import com.dag.core.model.NodeContext;
import com.dag.core.repository.NodeDO;
import com.dag.core.repository.NodeMapper;
import com.dag.core.repository.TaskDO;
import com.dag.core.trace.TraceRecorder;
import com.dag.core.config.DagProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 节点执行管线（执行线程内完整生命周期）。
 * <p>流程：tryLock（watchdog 续期=存活标记）→ CAS PENDING→RUNNING（领取）→ 流水 NODE_CLAIM →
 * 构建 NodeContext（bizArgs 全局 / nodeArgs 节点 / upstreamResults 上游结果）→ handler.execute →
 * 按 ExecutionResult.kind 分发（SUCCESS→persistSyncSuccess+push；FAILURE→persistSyncFailed；
 * PENDING→persistWaitConfirm）→ 释放锁。</p>
 * <p>handler 抛异常 → 捕获转 FAILED；结果 JSON 序列化失败 → FAILED（EX-05：不抛未处理异常）。</p>
 */
public class NodeExecutor {

    private static final Logger log = LoggerFactory.getLogger(NodeExecutor.class);

    private final LockManager lockManager;
    private final HandlerRegistry handlerRegistry;
    private final NodeMapper nodeMapper;
    private final TraceRecorder traceRecorder;
    private final NodeTransitionService transitionService;
    private final ClaimService claimService;
    private final ObjectMapper objectMapper;
    private final DagProperties properties;

    public NodeExecutor(LockManager lockManager, HandlerRegistry handlerRegistry, NodeMapper nodeMapper,
                        TraceRecorder traceRecorder, NodeTransitionService transitionService,
                        ClaimService claimService, ObjectMapper objectMapper, DagProperties properties) {
        this.lockManager = lockManager;
        this.handlerRegistry = handlerRegistry;
        this.nodeMapper = nodeMapper;
        this.traceRecorder = traceRecorder;
        this.transitionService = transitionService;
        this.claimService = claimService;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    public void execute(NodeDO node, TaskDO task, DagGraph graph) {
        boolean locked;
        try {
            locked = lockManager.tryLock(node.getId(), properties.getLock().getWaitMs());
        } catch (Exception e) {
            // 锁服务异常（如 Redis 不可写/故障）：不误 claim、不误执行，节点保持 PENDING，下轮调度重试
            log.error("tryLock error, node={} (node stays PENDING, will be retried by scheduler)",
                    node.getId(), e);
            return;
        }
        if (!locked) {
            return; // 其他实例正在执行/巡检判定中
        }
        boolean claimed = false;
        try {
            // 领取：CAS PENDING → RUNNING（多实例/双路径防重核心，行数=1 才算成功）
            if (nodeMapper.claim(node.getId()) == 0) {
                return;
            }
            claimed = true;
            traceRecorder.nodeEvent(task.getTaskId(), node.getId(), node.getBizNodeId(),
                    EventType.NODE_CLAIM, task.getState(), NodeState.RUNNING.name(), node.getHandler());

            NodeContext context = buildContext(node, task, graph);
            NodeHandler handler = handlerRegistry.get(node.getHandler());
            if (handler == null) {
                // 理论上提交时已校验（1005），防御性兜底
                transitionService.persistSyncFailed(node, task, "handler not found: " + node.getHandler());
                return;
            }

            ExecutionResult result;
            try {
                result = handler.execute(context);
            } catch (Throwable t) {
                log.error("handler execute error, node={}", node.getId(), t);
                result = ExecutionResult.failure("handler exception: " + t);
            }

            if (result == null) {
                result = ExecutionResult.failure("handler returned null");
            }

            switch (result.getKind()) {
                case SUCCESS:
                    String dataJson = toJson(result.getData());
                    if (transitionService.persistSyncSuccess(node, task, dataJson)) {
                        claimService.pushDownstream(task, graph, node.getBizNodeId());
                    }
                    break;
                case FAILURE:
                    transitionService.persistSyncFailed(node, task, result.getError());
                    break;
                case PENDING:
                    // 运行期以返回值为准（async_type 仅声明标记，已拍板口径 EX-03）
                    transitionService.persistWaitConfirm(node, task);
                    break;
            }
        } finally {
            if (claimed) {
                lockManager.unlock(node.getId());
            }
        }
    }

    private NodeContext buildContext(NodeDO node, TaskDO task, DagGraph graph) {
        NodeContext context = new NodeContext();
        context.setTaskId(task.getTaskId());
        context.setNodeInstanceId(node.getId());
        context.setBizNodeId(node.getBizNodeId());
        context.setBizType(task.getBizType());
        context.setBizId(task.getBizId());
        context.setBizArgs(parseJson(task.getBizArgs()));
        context.setNodeArgs(parseJson(node.getBizArgs()));
        context.setUpstreamResults(buildUpstreamResults(task, node, graph));
        return context;
    }

    /** 上游结果：SUCCESS 前驱的 result（key = biz_node_id） */
    private Map<String, Object> buildUpstreamResults(TaskDO task, NodeDO node, DagGraph graph) {
        Map<String, Object> results = new HashMap<>();
        List<NodeDO> nodes = nodeMapper.selectByTaskId(task.getTaskId());
        for (NodeDO n : nodes) {
            if (NodeState.SUCCESS.name().equals(n.getState()) && graph.getPredecessors(node.getBizNodeId()).contains(n.getBizNodeId())) {
                results.put(n.getBizNodeId(), parseJson(n.getResult()));
            }
        }
        return results;
    }

    private Map<String, Object> parseJson(String json) {
        if (json == null || json.isEmpty()) {
            return new HashMap<>();
        }
        try {
            return objectMapper.readValue(json, objectMapper.getTypeFactory()
                    .constructMapType(Map.class, String.class, Object.class));
        } catch (JsonProcessingException e) {
            log.warn("parse json failed: {}", json, e);
            return new HashMap<>();
        }
    }

    private String toJson(Object data) {
        if (data == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(data);
        } catch (JsonProcessingException e) {
            log.warn("serialize result failed, node data={}", data, e);
            // EX-05：序列化失败不抛未处理异常，回退字符串
            return String.valueOf(data);
        }
    }
}
