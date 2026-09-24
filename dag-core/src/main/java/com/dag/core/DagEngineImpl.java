package com.dag.core;

import com.dag.core.engine.ClaimService;
import com.dag.core.engine.NodeTransitionService;
import com.dag.core.engine.Scheduler;
import com.dag.core.engine.TaskOperationService;
import com.dag.core.enums.FinalState;
import com.dag.core.enums.NodeState;
import com.dag.core.enums.RetryMode;
import com.dag.core.exception.DagException;
import com.dag.core.graph.GraphParser;
import com.dag.core.graph.GraphValidator;
import com.dag.core.model.DagGraph;
import com.dag.core.model.NodeInfo;
import com.dag.core.model.TaskInfo;
import com.dag.core.model.TraceEntry;
import com.dag.core.repository.FlowDefinitionDO;
import com.dag.core.repository.FlowDefinitionMapper;
import com.dag.core.repository.NodeDO;
import com.dag.core.repository.NodeMapper;
import com.dag.core.repository.TaskDO;
import com.dag.core.repository.TaskMapper;
import com.dag.core.repository.TraceDO;
import com.dag.core.repository.TraceMapper;
import com.dag.core.util.IdGenerator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * DagEngine 门面实现（进程内 API）。
 */
@Component
public class DagEngineImpl implements DagEngine {

    private final FlowDefinitionMapper flowDefinitionMapper;
    private final TaskMapper taskMapper;
    private final NodeMapper nodeMapper;
    private final TraceMapper traceMapper;
    private final GraphParser graphParser;
    private final GraphValidator graphValidator;
    private final TaskOperationService operationService;
    private final NodeTransitionService transitionService;
    private final ClaimService claimService;
    private final Scheduler scheduler;
    private final ObjectMapper objectMapper;

    public DagEngineImpl(FlowDefinitionMapper flowDefinitionMapper, TaskMapper taskMapper,
                         NodeMapper nodeMapper, TraceMapper traceMapper, GraphParser graphParser,
                         GraphValidator graphValidator, TaskOperationService operationService,
                         NodeTransitionService transitionService, ClaimService claimService,
                         Scheduler scheduler, ObjectMapper objectMapper) {
        this.flowDefinitionMapper = flowDefinitionMapper;
        this.taskMapper = taskMapper;
        this.nodeMapper = nodeMapper;
        this.traceMapper = traceMapper;
        this.graphParser = graphParser;
        this.graphValidator = graphValidator;
        this.operationService = operationService;
        this.transitionService = transitionService;
        this.claimService = claimService;
        this.scheduler = scheduler;
        this.objectMapper = objectMapper;
    }

    // ---------------------------------------------------------------- submit

    @Override
    public TaskInfo submit(String bizType, String bizId, Map<String, Object> bizArgs) {
        validateBiz(bizType, bizId);
        TaskDO existing = taskMapper.selectByBiz(bizType, bizId);
        if (existing != null) {
            return toTaskInfo(existing); // 2001 幂等：返回已有任务
        }
        FlowDefinitionDO def = flowDefinitionMapper.selectByBiz(bizType, bizId);
        if (def == null || !"ENABLE".equals(def.getStatus())) {
            throw new DagException(2002, "流程定义不存在或未启用: " + bizType + "/" + bizId);
        }
        return submitInternal(bizType, bizId, def.getGraphJson(), bizArgs, "fixed");
    }

    @Override
    public TaskInfo submit(String bizType, String bizId, String graphJson, Map<String, Object> bizArgs) {
        validateBiz(bizType, bizId);
        TaskDO existing = taskMapper.selectByBiz(bizType, bizId);
        if (existing != null) {
            return toTaskInfo(existing);
        }
        graphParser.parse(graphJson);      // 结构校验 1001/1006
        return submitInternal(bizType, bizId, graphJson, bizArgs, "custom");
    }

    private TaskInfo submitInternal(String bizType, String bizId, String graphJson,
                                    Map<String, Object> bizArgs, String source) {
        DagGraph graph = graphParser.parse(graphJson);
        graphValidator.validate(graph);    // 可执行性校验 1002-1005（含多重边拒绝/孤立节点允许）

        TaskDO task = new TaskDO();
        task.setTaskId(IdGenerator.newId());
        task.setBizType(bizType);
        task.setBizId(bizId);
        task.setGraphJson(graphJson);
        task.setState("RUNNING");
        task.setBizArgs(toJson(bizArgs));

        List<NodeDO> nodes = buildNodes(task, graph);
        try {
            operationService.submitNew(task, nodes, source);
        } catch (DuplicateKeyException e) {
            // 并发提交同 biz：UNIQUE(biz_type,biz_id) 兜底，返回先提交者的任务（TC-J01）
            TaskDO again = taskMapper.selectByBiz(bizType, bizId);
            if (again != null) {
                return toTaskInfo(again);
            }
            throw e;
        }
        // 事务提交后，同进程立即推进根节点（TC-A11 根节点立即可执行）
        scheduler.scheduleTask(task);
        return toTaskInfo(task);
    }

    private List<NodeDO> buildNodes(TaskDO task, DagGraph graph) {
        List<NodeDO> nodes = new ArrayList<>();
        for (com.dag.core.model.GraphNode gn : graph.getNodes()) {
            NodeDO node = new NodeDO();
            node.setId(IdGenerator.newId());
            node.setTaskId(task.getTaskId());
            node.setBizNodeId(gn.getBizNodeId());
            node.setHandler(gn.getHandler());
            node.setState(NodeState.PENDING.name());
            node.setBizArgs(toJson(gn.getBizArgs()));
            node.setTimeOut(0);
            node.setAsyncType(gn.getAsyncType());
            nodes.add(node);
        }
        return nodes;
    }

    // ---------------------------------------------------------------- confirm / fail

    @Override
    public boolean confirm(String nodeInstanceId, String resultJson) {
        NodeDO node = nodeMapper.selectById(nodeInstanceId);
        if (node == null) {
            return false;
        }
        if (!NodeState.WAIT_CONFIRM.name().equals(node.getState())) {
            return false; // 状态不匹配（3002 语义：返回 false）
        }
        TaskDO task = taskMapper.selectByTaskId(node.getTaskId());
        if (task == null) {
            return false;
        }
        if (transitionService.persistConfirmSuccess(node, task, resultJson)) {
            DagGraph graph = graphParser.parse(task.getGraphJson());
            claimService.pushDownstream(task, graph, node.getBizNodeId());
            return true;
        }
        return false; // 重复/晚到回调，幂等忽略（4001）
    }

    @Override
    public boolean fail(String nodeInstanceId, String error) {
        NodeDO node = nodeMapper.selectById(nodeInstanceId);
        if (node == null) {
            return false;
        }
        if (!NodeState.WAIT_CONFIRM.name().equals(node.getState())) {
            return false;
        }
        TaskDO task = taskMapper.selectByTaskId(node.getTaskId());
        if (task == null) {
            return false;
        }
        return transitionService.persistConfirmFailed(node, task, error);
    }

    // ---------------------------------------------------------------- cancel / retry / resume

    @Override
    public boolean cancel(String taskId) {
        TaskDO task = taskMapper.selectByTaskId(taskId);
        if (task == null || !"RUNNING".equals(task.getState())) {
            return false; // 终态不可取消（3001 语义：返回 false）
        }
        return operationService.cancelTask(task);
    }

    @Override
    public TaskInfo retry(String taskId, RetryMode mode) {
        TaskDO task = taskMapper.selectByTaskId(taskId);
        if (task == null) {
            throw new DagException(2002, "任务不存在: " + taskId);
        }
        if (!"FAILED".equals(task.getState())) {
            throw new DagException(3001, "仅 FAILED 任务可重试，当前: " + task.getState());
        }
        if (!operationService.retryTask(task, mode)) {
            throw new DagException(3001, "重试未生效（任务状态已变化）");
        }
        TaskDO refreshed = taskMapper.selectByTaskId(taskId);
        scheduler.scheduleTask(refreshed); // 重置后重新推进
        return toTaskInfo(refreshed);
    }

    @Override
    public boolean resume(String nodeInstanceId) {
        NodeDO node = nodeMapper.selectById(nodeInstanceId);
        if (node == null) {
            return false;
        }
        if (!NodeState.WAIT_CONFIRM.name().equals(node.getState())) {
            return false; // 仅 WAIT_CONFIRM 可续跑（3002）
        }
        TaskDO task = taskMapper.selectByTaskId(node.getTaskId());
        if (task == null) {
            return false;
        }
        if (transitionService.persistResume(node, task)) {
            scheduler.scheduleTask(task);
            return true;
        }
        return false;
    }

    // ---------------------------------------------------------------- 查询 / 运维

    @Override
    public TaskInfo getTask(String taskId) {
        TaskDO task = taskMapper.selectByTaskId(taskId);
        return task == null ? null : toTaskInfo(task);
    }

    @Override
    public List<NodeInfo> getNodes(String taskId) {
        List<NodeDO> nodes = nodeMapper.selectByTaskId(taskId);
        List<NodeInfo> result = new ArrayList<>();
        for (NodeDO node : nodes) {
            result.add(toNodeInfo(node));
        }
        return result;
    }

    @Override
    public List<TraceEntry> getTrace(String taskId, String nodeId) {
        List<TraceDO> traces = (nodeId == null || nodeId.isEmpty())
                ? traceMapper.selectByTaskId(taskId)
                : traceMapper.selectByTaskAndNode(taskId, nodeId);
        List<TraceEntry> result = new ArrayList<>();
        for (TraceDO t : traces) {
            TraceEntry entry = new TraceEntry();
            entry.setId(t.getId());
            entry.setTaskId(t.getTaskId());
            entry.setNodeId(t.getNodeId());
            entry.setBizNodeId(t.getBizNodeId());
            entry.setEventType(t.getEventType());
            entry.setTaskState(t.getTaskState());
            entry.setNodeState(t.getNodeState());
            entry.setDetail(t.getDetail());
            entry.setCreatedAt(t.getCreatedAt());
            result.add(entry);
        }
        return result;
    }

    @Override
    public boolean forceFinalize(String taskId, FinalState state) {
        TaskDO task = taskMapper.selectByTaskId(taskId);
        if (task == null || !"RUNNING".equals(task.getState())) {
            return false;
        }
        return operationService.forceFinalize(task, state.name());
    }

    // ---------------------------------------------------------------- 转换

    private void validateBiz(String bizType, String bizId) {
        if (bizType == null || bizType.isEmpty()) {
            throw new DagException(9001, "biz_type 不能为空");
        }
        if (bizId == null || bizId.isEmpty()) {
            throw new DagException(9001, "biz_id 不能为空");
        }
    }

    private TaskInfo toTaskInfo(TaskDO task) {
        TaskInfo info = new TaskInfo();
        info.setTaskId(task.getTaskId());
        info.setBizType(task.getBizType());
        info.setBizId(task.getBizId());
        info.setState(task.getState());
        info.setGraphJson(task.getGraphJson());
        info.setBizArgs(parseJson(task.getBizArgs()));
        info.setStartedAt(task.getStartedAt());
        info.setFinishedAt(task.getFinishedAt());
        return info;
    }

    private NodeInfo toNodeInfo(NodeDO node) {
        NodeInfo info = new NodeInfo();
        info.setId(node.getId());
        info.setTaskId(node.getTaskId());
        info.setBizNodeId(node.getBizNodeId());
        info.setHandler(node.getHandler());
        info.setState(node.getState());
        info.setResult(node.getResult());
        info.setTimeOut(node.getTimeOut() != null && node.getTimeOut() == 1);
        info.setAsyncType(node.getAsyncType() == null ? 0 : node.getAsyncType());
        info.setStartedAt(node.getStartedAt());
        info.setFinishedAt(node.getFinishedAt());
        return info;
    }

    private String toJson(Object obj) {
        if (obj == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            throw new DagException(5001, "参数序列化失败", e);
        }
    }

    private Map<String, Object> parseJson(String json) {
        if (json == null || json.isEmpty()) {
            return new HashMap<>();
        }
        try {
            return objectMapper.readValue(json, objectMapper.getTypeFactory()
                    .constructMapType(Map.class, String.class, Object.class));
        } catch (JsonProcessingException e) {
            return new HashMap<>();
        }
    }
}
