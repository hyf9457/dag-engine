package com.dag.core.handler;

import com.dag.core.model.ExecutionResult;
import com.dag.core.model.NodeContext;

/**
 * 节点执行器接口（业务方实现并以 @Component 注册，handler 名 = Bean 名）。
 */
public interface NodeHandler {

    /**
     * 执行节点。
     * @param context 节点执行上下文（全局参数 / 节点参数 / 上游结果）
     * @return success(data) / failure(error) / pending()
     */
    ExecutionResult execute(NodeContext context);
}
