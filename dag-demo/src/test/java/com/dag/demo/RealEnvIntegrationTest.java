package com.dag.demo;

import com.dag.core.DagEngine;
import com.dag.core.engine.ScanRunner;
import com.dag.core.exception.DagException;
import com.dag.core.lock.LockManager;
import com.dag.core.model.NodeInfo;
import com.dag.core.model.TaskInfo;
import com.dag.core.model.TraceEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 真实环境验收测试（MySQL dag_test + Redis 单点，@ActiveProfiles("real")）。
 * <p>覆盖 08 验收中需真实 Redis 环境的用例：TC-E 超时巡检、TC-K 崩溃恢复/不误判、
 * 以及 Redis 锁下主链路（串行/异步回调/并发 confirm）。</p>
 * <p>前置：dag_test 库可连接（schema.sql/data.sql 自动初始化，幂等）。</p>
 */
@SpringBootTest
@ActiveProfiles("real")
@DisplayName("dag-engine 真实环境验收（MySQL+Redis）")
class RealEnvIntegrationTest {

    @Autowired
    private DagEngine dagEngine;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ScanRunner scanRunner;

    @Autowired
    private LockManager lockManager;

    /**
     * 探测 Redis 是否可写：引擎节点执行依赖分布式锁（tryLock），
     * 当前环境 YOUR_TEST_HOST:6379 为只读从节点（master YOUR_MASTER_HOST:60107 不可达，且非 cluster），
     * 提供可写主节点/读写地址后本套用例自动生效。
     */
    @BeforeEach
    void requireWritableRedis() {
        boolean writable;
        try {
            writable = lockManager.tryLock("__dag_probe__", 0);
            if (writable) {
                lockManager.unlock("__dag_probe__");
            }
        } catch (Exception e) {
            writable = false;
        }
        assumeTrue(writable, "需可写 Redis（当前 YOUR_TEST_HOST:6379 为只读从节点，master 不可达）；提供可写主节点后自动生效");
    }

    private static final AtomicInteger SEQ = new AtomicInteger(1);

    /** bizId 全局唯一（时间戳+序号）：dag_test 库跨轮次共享，固定序列会撞上前一轮残留任务（submit 幂等） */
    private static String bizId() {
        return "real-biz-" + System.currentTimeMillis() + "-" + SEQ.getAndIncrement();
    }

    private String graph(String nodes, String edges) {
        return "{\"nodes\":" + nodes + ",\"edges\":" + edges + "}";
    }

    private String node(String id, String handler) {
        return node(id, handler, 0, null);
    }

    private String node(String id, String handler, int asyncType, String bizArgs) {
        StringBuilder sb = new StringBuilder("{\"biz_node_id\":\"").append(id)
                .append("\",\"handler\":\"").append(handler)
                .append("\",\"async_type\":").append(asyncType);
        if (bizArgs != null) {
            sb.append(",\"biz_args\":").append(bizArgs);
        }
        sb.append("}");
        return sb.toString();
    }

    private String edge(String from, String to) {
        return "{\"from\":\"" + from + "\",\"to\":\"" + to + "\"}";
    }

    private TaskInfo waitFinal(String taskId, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            TaskInfo t = dagEngine.getTask(taskId);
            if (t != null && !"RUNNING".equals(t.getState())) {
                return t;
            }
            Thread.sleep(100L);
        }
        return dagEngine.getTask(taskId);
    }

    private NodeInfo nodeOf(String taskId, String bizNodeId) {
        for (NodeInfo n : dagEngine.getNodes(taskId)) {
            if (bizNodeId.equals(n.getBizNodeId())) {
                return n;
            }
        }
        return null;
    }

    private boolean hasTraceEvent(String taskId, String nodeId, String eventType, String detailContains) {
        List<TraceEntry> traces = dagEngine.getTrace(taskId, nodeId);
        for (TraceEntry t : traces) {
            if (eventType.equals(t.getEventType())
                    && (detailContains == null || (t.getDetail() != null && t.getDetail().contains(detailContains)))) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------ TC-E 超时巡检
    // 前置：需要可写 Redis（当前环境 YOUR_TEST_HOST:6379 为只读从节点，master 不可达 → assumeTrue 跳过；
    // 提供可写 Redis 后自动生效）

    @Test
    @DisplayName("TC-E01：执行超时 → 节点 FAILED(time_out=1, result=timeout) + 任务收敛 FAILED")
    void timeoutInspect() throws Exception {
        String g = graph(
                "[" + node("A", "slowHandler", 0, "{\"sleepMs\":30000}") + "]",
                "[]");
        TaskInfo task = dagEngine.submit("real_timeout", bizId(), g, null);
        // 手动触发巡检（诊断 @Scheduled 是否生效）：每 500ms 一轮，覆盖 5s 超时阈值
        for (int i = 0; i < 14; i++) {
            scanRunner.run();
            Thread.sleep(500L);
        }

        NodeInfo a = nodeOf(task.getTaskId(), "A");
        assertNotNull(a);
        assertEquals("FAILED", a.getState(), "节点应被超时判定为 FAILED");
        assertTrue(a.isTimeOut(), "time_out 应置 1");
        assertNotNull(a.getResult());
        assertTrue(a.getResult().contains("timeout"), "result 应由引擎填 timeout");

        assertEquals("FAILED", dagEngine.getTask(task.getTaskId()).getState());
        assertTrue(hasTraceEvent(task.getTaskId(), a.getId(), "NODE_TIMEOUT", null),
                "流水应含 NODE_TIMEOUT");
    }

    // ------------------------------------------------------ TC-K 崩溃恢复

    @Test
    @DisplayName("TC-K01：执行器崩溃状态残留（RUNNING 且锁无人持有）→ 巡检重置 PENDING → 重新执行 SUCCESS")
    void crashResetRecover() throws Exception {
        // 1. 正常跑完单节点任务
        String g = graph("[" + node("A", "echoHandler") + "]", "[]");
        TaskInfo task = dagEngine.submit("real_crash", bizId(), g, null);
        TaskInfo done = waitFinal(task.getTaskId(), 10_000L);
        assertEquals("SUCCESS", done.getState());

        // 2. 模拟"执行完成但终态未落库（提交前崩溃）"的状态残留：节点/任务手工置 RUNNING（锁已释放）
        jdbcTemplate.update("UPDATE node_instance SET state='RUNNING', started_at=NOW(), finished_at=NULL, result=NULL, time_out=0 WHERE task_id=?",
                task.getTaskId());
        jdbcTemplate.update("UPDATE task_instance SET state='RUNNING', finished_at=NULL WHERE task_id=?",
                task.getTaskId());

        // 3. 手动触发巡检（诊断 @Scheduled 是否生效）：重置 → 下一轮调度重新执行
        for (int i = 0; i < 12; i++) {
            scanRunner.run();
            Thread.sleep(500L);
        }
        NodeInfo a = nodeOf(task.getTaskId(), "A");
        assertEquals("SUCCESS", a.getState(), "崩溃残留节点应被巡检重置并重新执行成功");
        assertEquals("SUCCESS", dagEngine.getTask(task.getTaskId()).getState());
        assertTrue(hasTraceEvent(task.getTaskId(), a.getId(), "NODE_RESET", "source=inspect"),
                "流水应含 NODE_RESET(source=inspect)");
    }

    @Test
    @DisplayName("TC-K02：执行器存活持锁 → 不被巡检重置/超时，慢但未超时正常 SUCCESS")
    void aliveNotMisjudged() throws Exception {
        // slowHandler sleep 3s < timeout 5s：执行期间持锁，巡检不得重置（锁判定：存活）
        String g = graph(
                "[" + node("A", "slowHandler", 0, "{\"sleepMs\":3000}") + "]",
                "[]");
        TaskInfo task = dagEngine.submit("real_alive", bizId(), g, null);

        Thread.sleep(1_500L); // 执行中
        NodeInfo a = nodeOf(task.getTaskId(), "A");
        assertEquals("RUNNING", a.getState(), "执行中节点不应被巡检重置");

        TaskInfo finalTask = waitFinal(task.getTaskId(), 10_000L);
        assertEquals("SUCCESS", finalTask.getState(), "未超时的慢执行应正常 SUCCESS");
        assertFalse(hasTraceEvent(task.getTaskId(), a.getId(), "NODE_RESET", null),
                "不应出现巡检重置事件");
        assertFalse(hasTraceEvent(task.getTaskId(), a.getId(), "NODE_TIMEOUT", null),
                "不应出现超时事件");
    }

    // ------------------------------------------------------ 主链路冒烟（真实 MySQL+Redis）

    @Test
    @DisplayName("TC-A01/B01/C01：真实环境串行流程全 SUCCESS 收敛")
    void serialSmoke() throws Exception {
        String g = graph(
                "[" + node("A", "echoHandler") + "," + node("B", "echoHandler") + "," + node("C", "echoHandler") + "]",
                "[" + edge("A", "B") + "," + edge("B", "C") + "]");
        TaskInfo task = dagEngine.submit("real_serial", bizId(), g, null);
        TaskInfo finalTask = waitFinal(task.getTaskId(), 10_000L);
        assertEquals("SUCCESS", finalTask.getState());
        assertEquals("SUCCESS", nodeOf(task.getTaskId(), "C").getState());
    }

    @Test
    @DisplayName("TC-D01/D06：真实环境异步 confirm 推进 + Redis 锁下并发 confirm 仅一成功")
    void asyncConfirmSmoke() throws Exception {
        String g = graph(
                "[" + node("A", "asyncHandler", 1, null) + "," + node("B", "echoHandler") + "]",
                "[" + edge("A", "B") + "]");
        TaskInfo task = dagEngine.submit("real_async", bizId(), g, null);
        Thread.sleep(800L);
        NodeInfo a = nodeOf(task.getTaskId(), "A");
        assertEquals("WAIT_CONFIRM", a.getState());
        assertEquals("PENDING", nodeOf(task.getTaskId(), "B").getState());

        // 并发 confirm：Redis 锁下仅一个生效
        CountDownLatch latch = new CountDownLatch(2);
        AtomicInteger successCount = new AtomicInteger();
        Runnable r = () -> {
            try {
                if (dagEngine.confirm(a.getId(), "{\"pay\":\"ok\"}")) {
                    successCount.incrementAndGet();
                }
            } finally {
                latch.countDown();
            }
        };
        new Thread(r).start();
        new Thread(r).start();
        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertEquals(1, successCount.get(), "并发 confirm 应仅一个生效");

        TaskInfo finalTask = waitFinal(task.getTaskId(), 10_000L);
        assertEquals("SUCCESS", finalTask.getState());
        assertEquals("SUCCESS", nodeOf(task.getTaskId(), "B").getState());
    }

    @Test
    @DisplayName("TC-A02：真实环境提交幂等")
    void submitIdempotentSmoke() {
        String g = graph("[" + node("A", "echoHandler") + "]", "[]");
        String id = bizId();
        TaskInfo first = dagEngine.submit("real_idem", id, g, null);
        TaskInfo second = dagEngine.submit("real_idem", id, g, null);
        assertEquals(first.getTaskId(), second.getTaskId());
    }

    @Test
    @DisplayName("TC-F01：真实环境 FAILED 任务重试 CONTINUE 重跑")
    void retrySmoke() throws Exception {
        String g = graph(
                "[" + node("A", "echoHandler") + "," + node("B", "failHandler") + "]",
                "[" + edge("A", "B") + "]");
        TaskInfo task = dagEngine.submit("real_retry", bizId(), g, null);
        TaskInfo failed = waitFinal(task.getTaskId(), 10_000L);
        assertEquals("FAILED", failed.getState());

        TaskInfo retried = dagEngine.retry(task.getTaskId(), com.dag.core.enums.RetryMode.CONTINUE);
        assertEquals("RUNNING", retried.getState());
        assertEquals("SUCCESS", nodeOf(task.getTaskId(), "A").getState());
        TaskInfo failedAgain = waitFinal(task.getTaskId(), 10_000L);
        assertEquals("FAILED", failedAgain.getState());
    }
}
