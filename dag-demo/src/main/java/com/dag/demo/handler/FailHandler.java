package com.dag.demo.handler;

import com.dag.core.handler.NodeHandler;
import com.dag.core.model.ExecutionResult;
import com.dag.core.model.NodeContext;
import org.springframework.stereotype.Component;

/**
 * 失败执行器（示例）：返回 failure 演示失败收敛。
 */
@Component("failHandler")
public class FailHandler implements NodeHandler {

    @Override
    public ExecutionResult execute(NodeContext context) {
        return ExecutionResult.failure("demo fail: " + context.getBizNodeId());
    }
}
