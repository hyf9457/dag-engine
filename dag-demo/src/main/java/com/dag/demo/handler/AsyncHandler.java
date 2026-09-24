package com.dag.demo.handler;

import com.dag.core.handler.NodeHandler;
import com.dag.core.model.ExecutionResult;
import com.dag.core.model.NodeContext;
import org.springframework.stereotype.Component;

/**
 * 异步执行器（示例）：返回 pending，节点挂起等待业务方 confirm/fail 回调。
 */
@Component("asyncHandler")
public class AsyncHandler implements NodeHandler {

    @Override
    public ExecutionResult execute(NodeContext context) {
        return ExecutionResult.pending();
    }
}
