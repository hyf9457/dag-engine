package com.dag.core.graph;

import com.dag.core.exception.DagException;
import com.dag.core.model.DagGraph;
import com.dag.core.model.GraphEdge;
import com.dag.core.model.GraphNode;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * graph_json 解析器（结构非法抛 1001）。
 */
public class GraphParser {

    private final ObjectMapper objectMapper;

    public GraphParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 解析并构造内存 DAG。
     * @param graphJson 原始 JSON（nodes + edges）
     * @return DagGraph
     * @throws DagException 1001 结构非法 / 1006 空图
     */
    public DagGraph parse(String graphJson) {
        JsonNode root;
        try {
            root = objectMapper.readTree(graphJson);
        } catch (IOException e) {
            throw new DagException(1001, "graph_json 不是合法 JSON: " + e.getMessage());
        }
        if (root == null || !root.isObject()) {
            throw new DagException(1001, "graph_json 必须是 JSON 对象");
        }

        JsonNode nodesNode = root.get("nodes");
        JsonNode edgesNode = root.get("edges");
        if (nodesNode == null || !nodesNode.isArray()) {
            throw new DagException(1001, "graph_json.nodes 缺失或不是数组");
        }
        if (edgesNode == null || !edgesNode.isArray()) {
            throw new DagException(1001, "graph_json.edges 缺失或不是数组");
        }

        List<GraphNode> nodes = new ArrayList<>();
        List<GraphEdge> edges = new ArrayList<>();
        java.util.Set<String> seenIds = new java.util.HashSet<>();
        try {
            for (JsonNode n : nodesNode) {
                if (!n.isObject()) {
                    throw new DagException(1001, "节点必须是对象");
                }
                GraphNode node = new GraphNode();
                JsonNode id = n.get("biz_node_id");
                JsonNode handler = n.get("handler");
                if (id == null || id.asText().isEmpty()) {
                    throw new DagException(1001, "节点缺少 biz_node_id");
                }
                if (handler == null || handler.asText().isEmpty()) {
                    throw new DagException(1001, "节点缺少 handler");
                }
                // 规则 3：biz_node_id 重复 → 1003（DagGraph 内部 Map 会覆盖，重复必须在解析期检出）
                if (!seenIds.add(id.asText())) {
                    throw new DagException(1003, "节点 id 重复: " + id.asText());
                }
                node.setBizNodeId(id.asText());
                node.setHandler(handler.asText());
                JsonNode async = n.get("async_type");
                node.setAsyncType(async != null ? async.asInt() : 0);
                if (node.getAsyncType() != 0 && node.getAsyncType() != 1) {
                    throw new DagException(1001, "async_type 只允许 0/1");
                }
                JsonNode args = n.get("biz_args");
                if (args != null && args.isObject()) {
                    node.setBizArgs(objectMapper.convertValue(args, new TypeReference<java.util.Map<String, Object>>() {
                    }));
                }
                nodes.add(node);
            }
            for (JsonNode e : edgesNode) {
                if (!e.isObject()) {
                    throw new DagException(1001, "边必须是对象");
                }
                JsonNode from = e.get("from");
                JsonNode to = e.get("to");
                if (from == null || from.asText().isEmpty() || to == null || to.asText().isEmpty()) {
                    throw new DagException(1001, "边缺少 from/to");
                }
                edges.add(new GraphEdge(from.asText(), to.asText()));
            }
        } catch (DagException de) {
            throw de;
        } catch (Exception ex) {
            throw new DagException(1001, "graph_json 解析失败: " + ex.getMessage());
        }

        if (nodes.isEmpty()) {
            throw new DagException(1006, "graph_json 无节点");
        }
        return new DagGraph(nodes, edges);
    }
}
