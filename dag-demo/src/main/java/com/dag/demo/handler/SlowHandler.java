package com.dag.demo.handler;

import com.dag.core.handler.NodeHandler;
import com.dag.core.model.ExecutionResult;
import com.dag.core.model.NodeContext;
import org.springframework.stereotype.Component;

/**
 * 挂起执行器（示例）：sleep 超过平台超时，演示超时判定（需 Redis 环境，巡检开启时生效）。
 * <p>sleep 时长支持节点参数 sleepMs 覆盖（默认 30s），便于验收用例控制执行时长。</p>
 */
@Component("slowHandler")
public class SlowHandler implements NodeHandler {

    @Override
    public ExecutionResult execute(NodeContext context) {
        long sleepMs = 30_000L;
        Object configured = context.getNodeArgs().get("sleepMs");
        if (configured instanceof Number) {
            sleepMs = ((Number) configured).longValue();
        } else if (configured instanceof String) {
            try {
                sleepMs = Long.parseLong((String) configured);
            } catch (NumberFormatException ignored) {
                // 非法值保持默认
            }
        }
        try {
            Thread.sleep(sleepMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return ExecutionResult.success("slow done");
    }
}
