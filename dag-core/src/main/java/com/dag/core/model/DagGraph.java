package com.dag.core.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 解析后的 DAG 图（内存模型）。
 * <p>edges 不落库，每次运行期从 graph_json 重建；提供前驱/后继/入度查询供推进判定。</p>
 */
public class DagGraph {

    /** 节点 id → 节点定义 */
    private final Map<String, GraphNode> nodeMap = new HashMap<>();
    /** 边集合（from → to） */
    private final List<GraphEdge> edges = new ArrayList<>();
    /** 后继映射 */
    private final Map<String, Set<String>> successors = new HashMap<>();
    /** 前驱映射 */
    private final Map<String, Set<String>> predecessors = new HashMap<>();

    public DagGraph(List<GraphNode> nodes, List<GraphEdge> edges) {
        for (GraphNode node : nodes) {
            nodeMap.put(node.getBizNodeId(), node);
            successors.computeIfAbsent(node.getBizNodeId(), k -> new HashSet<>());
            predecessors.computeIfAbsent(node.getBizNodeId(), k -> new HashSet<>());
        }
        for (GraphEdge edge : edges) {
            this.edges.add(edge);
            // 端点不存在时先初始化集合，避免构造期 NPE（引用校验由 GraphValidator 在构造后执行 → 1004）
            successors.computeIfAbsent(edge.getFrom(), k -> new HashSet<>()).add(edge.getTo());
            predecessors.computeIfAbsent(edge.getTo(), k -> new HashSet<>()).add(edge.getFrom());
        }
    }

    public List<GraphNode> getNodes() {
        return Collections.unmodifiableList(new ArrayList<>(nodeMap.values()));
    }

    public List<GraphEdge> getEdges() {
        return Collections.unmodifiableList(edges);
    }

    public GraphNode getNode(String bizNodeId) {
        return nodeMap.get(bizNodeId);
    }

    /** 直接后继节点 id */
    public Set<String> getSuccessors(String bizNodeId) {
        return successors.getOrDefault(bizNodeId, Collections.emptySet());
    }

    /** 直接前驱节点 id */
    public Set<String> getPredecessors(String bizNodeId) {
        return predecessors.getOrDefault(bizNodeId, Collections.emptySet());
    }

    /** 根节点（无前驱） */
    public List<String> getRootIds() {
        List<String> roots = new ArrayList<>();
        for (String id : nodeMap.keySet()) {
            if (predecessors.get(id).isEmpty()) {
                roots.add(id);
            }
        }
        return roots;
    }

    /** 是否是孤立节点（无入边无出边）——已拍板：视为单节点任务独立执行 */
    public boolean isIsolated(String bizNodeId) {
        return predecessors.get(bizNodeId).isEmpty() && successors.get(bizNodeId).isEmpty();
    }
}
