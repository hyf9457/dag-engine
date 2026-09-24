package com.dag.core.model;

/**
 * 图边定义（graph_json edges[]）。
 */
public class GraphEdge {

    private String from;
    private String to;

    public GraphEdge() {
    }

    public GraphEdge(String from, String to) {
        this.from = from;
        this.to = to;
    }

    public String getFrom() { return from; }
    public void setFrom(String from) { this.from = from; }
    public String getTo() { return to; }
    public void setTo(String to) { this.to = to; }
}
