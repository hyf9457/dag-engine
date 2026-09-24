package com.dag.demo.handler;

import com.dag.core.handler.NodeHandler;
import com.dag.core.model.ExecutionResult;
import com.dag.core.model.NodeContext;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 同步成功执行器（示例）：回显节点参数 / 全局参数 / 上游结果。
 * <p>Bean 名 = echoHandler（graph 中 handler 字段须大小写精确匹配）。</p>
 */
@Component("echoHandler")
public class EchoHandler implements NodeHandler {

    @Override
    public ExecutionResult execute(NodeContext context) {
        Map<String, Object> data = new HashMap<>();
        data.put("bizNodeId", context.getBizNodeId());
        data.put("bizArgs", context.getBizArgs());
        data.put("nodeArgs", context.getNodeArgs());
        data.put("upstream", context.getUpstreamResults());
        return ExecutionResult.success(data);
    }
}
