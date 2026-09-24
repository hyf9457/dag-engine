package com.dag.core.graph;

import com.dag.core.exception.DagException;
import com.dag.core.handler.HandlerRegistry;
import com.dag.core.model.DagGraph;
import com.dag.core.model.GraphEdge;
import com.dag.core.model.GraphNode;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

/**
 * 图可执行性校验器（全部通过才允许落库）。
 * <p>规则（06 §2.3，含已拍板口径）：结构 1001 / 空图 1006 / 节点重复 1003 / 边引用 1004 /
 * 无环（含自环）1002 / handler 大小写敏感精确匹配 1005 / 多重边拒绝 1001 / 孤立节点允许（单节点任务）。</p>
 */
public class GraphValidator {

    private final HandlerRegistry handlerRegistry;

    public GraphValidator(HandlerRegistry handlerRegistry) {
        this.handlerRegistry = handlerRegistry;
    }

    public void validate(DagGraph graph) {
        validateNodeUnique(graph);
        validateEdgeReferences(graph);
        validateNoCycle(graph);
        validateMultiEdge(graph);
        validateHandlers(graph);
        // 孤立节点：允许（已拍板：视为单节点任务独立执行），无需校验
    }

    /** 规则 3：nodes 中 biz_node_id 不重复 → 1003 */
    private void validateNodeUnique(DagGraph graph) {
        Set<String> ids = new HashSet<>();
        for (GraphNode node : graph.getNodes()) {
            if (!ids.add(node.getBizNodeId())) {
                throw new DagException(1003, "节点 id 重复: " + node.getBizNodeId());
            }
        }
    }

    /** 规则 4：edges 的 from/to 均存在于 nodes → 1004 */
    private void validateEdgeReferences(DagGraph graph) {
        for (GraphEdge edge : graph.getEdges()) {
            if (graph.getNode(edge.getFrom()) == null) {
                throw new DagException(1004, "边 from 引用不存在: " + edge.getFrom());
            }
            if (graph.getNode(edge.getTo()) == null) {
                throw new DagException(1004, "边 to 引用不存在: " + edge.getTo());
            }
        }
    }

    /** 规则 5：无环（拓扑排序 Kahn，自环一并判环）→ 1002 */
    private void validateNoCycle(DagGraph graph) {
        Map<String, Integer> inDegree = new HashMap<>();
        Map<String, Set<String>> adj = new HashMap<>();
        for (GraphNode node : graph.getNodes()) {
            inDegree.put(node.getBizNodeId(), 0);
            adj.put(node.getBizNodeId(), new HashSet<>());
        }
        for (GraphEdge edge : graph.getEdges()) {
            if (edge.getFrom().equals(edge.getTo())) {
                throw new DagException(1002, "自环: " + edge.getFrom());
            }
            if (adj.get(edge.getFrom()).add(edge.getTo())) {
                inDegree.put(edge.getTo(), inDegree.get(edge.getTo()) + 1);
            }
        }
        Queue<String> queue = new ArrayDeque<>();
        for (Map.Entry<String, Integer> e : inDegree.entrySet()) {
            if (e.getValue() == 0) {
                queue.offer(e.getKey());
            }
        }
        int visited = 0;
        while (!queue.isEmpty()) {
            String cur = queue.poll();
            visited++;
            for (String next : adj.get(cur)) {
                int d = inDegree.get(next) - 1;
                inDegree.put(next, d);
                if (d == 0) {
                    queue.offer(next);
                }
            }
        }
        if (visited != inDegree.size()) {
            throw new DagException(1002, "DAG 存在环");
        }
    }

    /** 规则 7：edges 不允许重复（同一 from→to 只允许一条边）→ 1001（已拍板：拒绝） */
    private void validateMultiEdge(DagGraph graph) {
        Set<String> seen = new HashSet<>();
        for (GraphEdge edge : graph.getEdges()) {
            String key = edge.getFrom() + "->" + edge.getTo();
            if (!seen.add(key)) {
                throw new DagException(1001, "多重边（重复依赖）: " + key);
            }
        }
    }

    /** 规则 6：handler 可解析且大小写敏感精确匹配 Bean 名 → 1005（已拍板） */
    private void validateHandlers(DagGraph graph) {
        for (GraphNode node : graph.getNodes()) {
            if (!handlerRegistry.contains(node.getHandler())) {
                throw new DagException(1005, "handler 未注册（大小写敏感精确匹配）: " + node.getHandler());
            }
        }
    }
}
