package com.dag.demo;

import com.dag.core.DagEngine;
import com.dag.core.enums.RetryMode;
import com.dag.core.model.NodeInfo;
import com.dag.core.model.TaskInfo;
import com.dag.core.model.TraceEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 接入演示：启动后提交固定流程 + 自定义流程，等待终态并打印任务状态与完整轨迹。
 * <p>测试环境通过 dag.demo.enabled=false 关闭（避免依赖 MySQL 示例数据）。</p>
 */
@Component
@ConditionalOnProperty(name = "dag.demo.enabled", havingValue = "true", matchIfMissing = true)
public class DemoRunner implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoRunner.class);

    private final DagEngine dagEngine;

    public DemoRunner(DagEngine dagEngine) {
        this.dagEngine = dagEngine;
    }

    @Override
    public void run(String... args) throws Exception {
        log.info("========== dag-engine demo start ==========");

        // 1. 提交固定流程（graph 取自 flow_definition，见 data.sql）
        Map<String, Object> bizArgs = new HashMap<>();
        bizArgs.put("orderId", "DEMO-001");
        TaskInfo fixed = dagEngine.submit("order", "DEMO-001", bizArgs);
        log.info("[固定流程] taskId={}, state={}", fixed.getTaskId(), fixed.getState());

        // 2. 提交自定义流程（串行 A→B，B 异步等待确认）
        String graph = "{\"nodes\":["
                + "{\"biz_node_id\":\"A\",\"handler\":\"echoHandler\",\"async_type\":0,\"biz_args\":{\"step\":\"A\"}},"
                + "{\"biz_node_id\":\"B\",\"handler\":\"asyncHandler\",\"async_type\":1}"
                + "],\"edges\":[{\"from\":\"A\",\"to\":\"B\"}]}";
        TaskInfo custom = dagEngine.submit("demo", "CUSTOM-001", graph, bizArgs);
        log.info("[自定义流程] taskId={}, state={}", custom.getTaskId(), custom.getState());

        // 3. 等待固定流程终态，打印轨迹
        TaskInfo finalTask = waitFinal(fixed.getTaskId(), 30_000L);
        log.info("[固定流程终态] state={}", finalTask.getState());
        List<TraceEntry> traces = dagEngine.getTrace(fixed.getTaskId(), null);
        for (TraceEntry t : traces) {
            log.info("  trace: {} node={} taskState={} nodeState={} detail={}",
                    t.getEventType(), t.getBizNodeId(), t.getTaskState(), t.getNodeState(), t.getDetail());
        }

        // 4. 演示异步回调：找到自定义流程 B 节点（WAIT_CONFIRM）后 confirm
        List<NodeInfo> nodes = dagEngine.getNodes(custom.getTaskId());
        for (NodeInfo n : nodes) {
            if ("B".equals(n.getBizNodeId()) && "WAIT_CONFIRM".equals(n.getState())) {
                dagEngine.confirm(n.getId(), "{\"payResult\":\"ok\"}");
                log.info("[回调确认] node={} → confirm success", n.getBizNodeId());
            }
        }
        TaskInfo customFinal = waitFinal(custom.getTaskId(), 10_000L);
        log.info("[自定义流程终态] state={}", customFinal.getState());

        log.info("========== dag-engine demo done ==========");
    }

    private TaskInfo waitFinal(String taskId, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            TaskInfo t = dagEngine.getTask(taskId);
            if (t != null && !"RUNNING".equals(t.getState())) {
                return t;
            }
            Thread.sleep(200L);
        }
        return dagEngine.getTask(taskId);
    }
}
