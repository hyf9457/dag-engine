package com.dag.demo;

import com.dag.core.DagEngine;
import com.dag.core.enums.RetryMode;
import com.dag.core.exception.DagException;
import com.dag.core.model.NodeInfo;
import com.dag.core.model.TaskInfo;
import com.dag.core.model.TraceEntry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * dag-engine 核心功能集成测试（H2 + 本地锁降级）。
 * <p>覆盖 08 验收 P0 主路径；超时/巡检/崩溃恢复类用例需真实 Redis 环境（本测试环境降级模式巡检关闭），
 * 见 TC-E / TC-K 组（将在真实 Redis 环境验收）。</p>
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("dag-engine 核心功能集成测试")
class DagEngineIntegrationTest {

    @Autowired
    private DagEngine dagEngine;

    private static final AtomicInteger SEQ = new AtomicInteger(1);

    private static String bizId() {
        return "biz-" + SEQ.getAndIncrement();
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
            Thread.sleep(50L);
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

    // ------------------------------------------------------ A 提交与串行

    @Test
    @DisplayName("TC-A01/A03+B01+C01：提交自定义串行流程 → 全部 SUCCESS 收敛 SUCCESS")
    void serialFlowSuccess() throws Exception {
        String g = graph(
                "[" + node("A", "echoHandler") + "," + node("B", "echoHandler") + "," + node("C", "echoHandler") + "]",
                "[" + edge("A", "B") + "," + edge("B", "C") + "]");
        TaskInfo task = dagEngine.submit("t_serial", bizId(), g, null);
        assertNotNull(task);
        assertEquals("RUNNING", task.getState());

        TaskInfo finalTask = waitFinal(task.getTaskId(), 10_000L);
        assertEquals("SUCCESS", finalTask.getState());
        assertNotNull(finalTask.getFinishedAt());

        List<NodeInfo> nodes = dagEngine.getNodes(task.getTaskId());
        assertEquals(3, nodes.size());
        for (NodeInfo n : nodes) {
            assertEquals("SUCCESS", n.getState());
        }
    }

    @Test
    @DisplayName("TC-B10：上游 result 传递到下游 NodeContext")
    void upstreamResultPassed() throws Exception {
        String g = graph(
                "[" + node("A", "echoHandler", 0, "{\"step\":\"first\"}") + "," + node("B", "echoHandler") + "]",
                "[" + edge("A", "B") + "]");
        TaskInfo task = dagEngine.submit("t_upstream", bizId(), g, null);
        TaskInfo finalTask = waitFinal(task.getTaskId(), 10_000L);
        assertEquals("SUCCESS", finalTask.getState());

        NodeInfo b = nodeOf(task.getTaskId(), "B");
        assertNotNull(b);
        assertNotNull(b.getResult());
        assertTrue(b.getResult().contains("\"upstream\""));
        assertTrue(b.getResult().contains("\"A\""));
        assertTrue(b.getResult().contains("\"first\""));
    }

    // ------------------------------------------------------ B 拓扑

    @Test
    @DisplayName("TC-B02：并行分支 A→B、A→C 均成功")
    void parallelBranches() throws Exception {
        String g = graph(
                "[" + node("A", "echoHandler") + "," + node("B", "echoHandler") + "," + node("C", "echoHandler") + "]",
                "[" + edge("A", "B") + "," + edge("A", "C") + "]");
        TaskInfo task = dagEngine.submit("t_parallel", bizId(), g, null);
        TaskInfo finalTask = waitFinal(task.getTaskId(), 10_000L);
        assertEquals("SUCCESS", finalTask.getState());
        assertEquals("SUCCESS", nodeOf(task.getTaskId(), "B").getState());
        assertEquals("SUCCESS", nodeOf(task.getTaskId(), "C").getState());
    }

    @Test
    @DisplayName("TC-B03/B04：汇聚节点等全部前驱成功且只执行一次")
    void joinNode() throws Exception {
        String g = graph(
                "[" + node("A", "echoHandler") + "," + node("B", "echoHandler") + "," + node("C", "echoHandler") + "]",
                "[" + edge("A", "C") + "," + edge("B", "C") + "]");
        TaskInfo task = dagEngine.submit("t_join", bizId(), g, null);
        TaskInfo finalTask = waitFinal(task.getTaskId(), 10_000L);
        assertEquals("SUCCESS", finalTask.getState());
        NodeInfo c = nodeOf(task.getTaskId(), "C");
        assertEquals("SUCCESS", c.getState());
        // 汇聚节点只执行一次：NODE_SUCCESS 事件仅一条（另有 NODE_CLAIM 一条）
        List<TraceEntry> cTraces = dagEngine.getTrace(task.getTaskId(), c.getId());
        long successEvents = cTraces.stream().filter(t -> "NODE_SUCCESS".equals(t.getEventType())).count();
        assertEquals(1L, successEvents);
        long claimEvents = cTraces.stream().filter(t -> "NODE_CLAIM".equals(t.getEventType())).count();
        assertEquals(1L, claimEvents);
    }

    @Test
    @DisplayName("TC-B06：单节点流程直接收敛")
    void singleNode() throws Exception {
        String g = graph("[" + node("A", "echoHandler") + "]", "[]");
        TaskInfo task = dagEngine.submit("t_single", bizId(), g, null);
        TaskInfo finalTask = waitFinal(task.getTaskId(), 10_000L);
        assertEquals("SUCCESS", finalTask.getState());
    }

    @Test
    @DisplayName("TC-B08：孤立节点视为单节点任务独立执行（已拍板）")
    void isolatedNode() throws Exception {
        String g = graph(
                "[" + node("A", "echoHandler") + "," + node("X", "echoHandler") + "]",
                "[]"); // A、X 均孤立 → 各自独立执行
        TaskInfo task = dagEngine.submit("t_isolated", bizId(), g, null);
        TaskInfo finalTask = waitFinal(task.getTaskId(), 10_000L);
        assertEquals("SUCCESS", finalTask.getState());
        assertEquals("SUCCESS", nodeOf(task.getTaskId(), "A").getState());
        assertEquals("SUCCESS", nodeOf(task.getTaskId(), "X").getState());
    }

    // ------------------------------------------------------ C 状态收敛

    @Test
    @DisplayName("TC-C02/C06：任一 FAILED 收敛 FAILED，已完成节点保留")
    void failedConverge() throws Exception {
        String g = graph(
                "[" + node("A", "echoHandler") + "," + node("B", "failHandler") + "]",
                "[" + edge("A", "B") + "]");
        TaskInfo task = dagEngine.submit("t_failed", bizId(), g, null);
        TaskInfo finalTask = waitFinal(task.getTaskId(), 10_000L);
        assertEquals("FAILED", finalTask.getState());
        assertEquals("FAILED", nodeOf(task.getTaskId(), "B").getState());
        // A 已完成保留
        NodeInfo a = nodeOf(task.getTaskId(), "A");
        assertEquals("SUCCESS", a.getState());
        assertNotNull(a.getResult());
    }

    @Test
    @DisplayName("TC-B09：分支内部失败不影响其他分支，任务最终 FAILED")
    void branchFailure() throws Exception {
        String g = graph(
                "[" + node("A", "echoHandler") + "," + node("B", "failHandler") + "," + node("C", "echoHandler") + "]",
                "[" + edge("A", "B") + "," + edge("A", "C") + "]");
        TaskInfo task = dagEngine.submit("t_branch_fail", bizId(), g, null);
        TaskInfo finalTask = waitFinal(task.getTaskId(), 10_000L);
        assertEquals("FAILED", finalTask.getState());
        assertEquals("SUCCESS", nodeOf(task.getTaskId(), "C").getState());
    }

    // ------------------------------------------------------ D 回调

    @Test
    @DisplayName("TC-D01/D07：异步节点 confirm 成功 → 推进并收敛 SUCCESS")
    void asyncConfirm() throws Exception {
        String g = graph(
                "[" + node("A", "echoHandler") + "," + node("B", "asyncHandler", 1, null) + "," + node("C", "echoHandler") + "]",
                "[" + edge("A", "B") + "," + edge("B", "C") + "]");
        TaskInfo task = dagEngine.submit("t_async", bizId(), g, null);
        Thread.sleep(500L);
        NodeInfo b = nodeOf(task.getTaskId(), "B");
        assertNotNull(b);
        assertEquals("WAIT_CONFIRM", b.getState());
        // 任务保持 RUNNING（C 未执行）
        assertEquals("RUNNING", dagEngine.getTask(task.getTaskId()).getState());
        assertEquals("PENDING", nodeOf(task.getTaskId(), "C").getState());

        assertTrue(dagEngine.confirm(b.getId(), "{\"pay\":\"ok\"}"));
        TaskInfo finalTask = waitFinal(task.getTaskId(), 10_000L);
        assertEquals("SUCCESS", finalTask.getState());
        assertEquals("SUCCESS", nodeOf(task.getTaskId(), "C").getState());
        assertTrue(nodeOf(task.getTaskId(), "B").getResult().contains("pay"));
    }

    @Test
    @DisplayName("TC-D02：异步 fail → 节点 FAILED + 任务收敛 FAILED")
    void asyncFail() throws Exception {
        String g = graph(
                "[" + node("A", "echoHandler") + "," + node("B", "asyncHandler", 1, null) + "]",
                "[" + edge("A", "B") + "]");
        TaskInfo task = dagEngine.submit("t_async_fail", bizId(), g, null);
        Thread.sleep(500L);
        NodeInfo b = nodeOf(task.getTaskId(), "B");
        assertTrue(dagEngine.fail(b.getId(), "payment rejected"));
        TaskInfo finalTask = waitFinal(task.getTaskId(), 10_000L);
        assertEquals("FAILED", finalTask.getState());
        assertEquals("FAILED", nodeOf(task.getTaskId(), "B").getState());
    }

    @Test
    @DisplayName("TC-D03/D04：confirm 幂等 / 状态校验")
    void confirmIdempotent() throws Exception {
        String g = graph(
                "[" + node("A", "echoHandler") + "," + node("B", "asyncHandler", 1, null) + "]",
                "[" + edge("A", "B") + "]");
        TaskInfo task = dagEngine.submit("t_confirm_idem", bizId(), g, null);
        Thread.sleep(500L);
        NodeInfo b = nodeOf(task.getTaskId(), "B");
        assertTrue(dagEngine.confirm(b.getId(), "{}"));
        assertFalse(dagEngine.confirm(b.getId(), "{}"));  // 重复回调幂等忽略
        // 对 SUCCESS 节点 confirm 拒绝
        NodeInfo a = nodeOf(task.getTaskId(), "A");
        assertFalse(dagEngine.confirm(a.getId(), "{}"));
        assertFalse(dagEngine.confirm("not-exist", "{}"));
    }

    // ------------------------------------------------------ F 重试

    @Test
    @DisplayName("TC-F01/F04：retry CONTINUE 仅重置失败节点 → 重跑成功收敛 SUCCESS")
    void retryContinue() throws Exception {
        String g = graph(
                "[" + node("A", "echoHandler") + "," + node("B", "failHandler") + "]",
                "[" + edge("A", "B") + "]");
        // 第一次：A 成功、B 失败（failHandler 固定失败）→ 先造 B 失败
        TaskInfo task = dagEngine.submit("t_retry_cont", bizId(), g, null);
        TaskInfo failed = waitFinal(task.getTaskId(), 10_000L);
        assertEquals("FAILED", failed.getState());

        // 重试 CONTINUE：A 保持 SUCCESS，B 重置 PENDING 重跑（仍失败）
        TaskInfo retried = dagEngine.retry(task.getTaskId(), RetryMode.CONTINUE);
        assertEquals("RUNNING", retried.getState());
        assertEquals("SUCCESS", nodeOf(task.getTaskId(), "A").getState()); // 成功节点保留
        TaskInfo failedAgain = waitFinal(task.getTaskId(), 10_000L);
        assertEquals("FAILED", failedAgain.getState());
    }

    @Test
    @DisplayName("TC-F06：终态拒绝重试（3001）")
    void retryFinalRejected() throws Exception {
        String g = graph("[" + node("A", "echoHandler") + "]", "[]");
        TaskInfo task = dagEngine.submit("t_retry_rej", bizId(), g, null);
        TaskInfo done = waitFinal(task.getTaskId(), 10_000L);
        assertEquals("SUCCESS", done.getState());
        DagException ex = assertThrows(DagException.class,
                () -> dagEngine.retry(task.getTaskId(), RetryMode.CONTINUE));
        assertEquals(3001, ex.getCode());
    }

    @Test
    @DisplayName("TC-F08：重试不新建任务（task_id 不变）")
    void retrySameTask() throws Exception {
        String g = graph(
                "[" + node("A", "echoHandler") + "," + node("B", "failHandler") + "]",
                "[" + edge("A", "B") + "]");
        TaskInfo task = dagEngine.submit("t_retry_same", bizId(), g, null);
        waitFinal(task.getTaskId(), 10_000L);
        TaskInfo retried = dagEngine.retry(task.getTaskId(), RetryMode.RESTART);
        assertEquals(task.getTaskId(), retried.getTaskId());
    }

    // ------------------------------------------------------ G 取消

    @Test
    @DisplayName("TC-G01/G03：RUNNING 任务可取消，取消后不再执行")
    void cancelRunning() throws Exception {
        String g = graph(
                "[" + node("A", "asyncHandler", 1, null) + "," + node("B", "echoHandler") + "]",
                "[" + edge("A", "B") + "]");
        TaskInfo task = dagEngine.submit("t_cancel", bizId(), g, null);
        Thread.sleep(500L);
        assertEquals("WAIT_CONFIRM", nodeOf(task.getTaskId(), "A").getState());
        assertTrue(dagEngine.cancel(task.getTaskId()));
        assertEquals("CANCELLED", dagEngine.getTask(task.getTaskId()).getState());
        assertEquals("CANCELLED", nodeOf(task.getTaskId(), "A").getState());
        assertEquals("CANCELLED", nodeOf(task.getTaskId(), "B").getState());
    }

    @Test
    @DisplayName("TC-G02：终态拒绝取消")
    void cancelFinalRejected() throws Exception {
        String g = graph("[" + node("A", "echoHandler") + "]", "[]");
        TaskInfo task = dagEngine.submit("t_cancel_rej", bizId(), g, null);
        waitFinal(task.getTaskId(), 10_000L);
        assertFalse(dagEngine.cancel(task.getTaskId()));
        assertFalse(dagEngine.cancel("not-exist"));
    }

    // ------------------------------------------------------ H 续跑

    @Test
    @DisplayName("TC-H01：WAIT_CONFIRM 节点 resume 后重新执行")
    void resumeNode() throws Exception {
        String g = graph(
                "[" + node("A", "asyncHandler", 1, null) + "]",
                "[]");
        TaskInfo task = dagEngine.submit("t_resume", bizId(), g, null);
        Thread.sleep(500L);
        NodeInfo a = nodeOf(task.getTaskId(), "A");
        assertEquals("WAIT_CONFIRM", a.getState());
        assertTrue(dagEngine.resume(a.getId()));
        // resume 后 asyncHandler 再次返回 pending → 重新进入 WAIT_CONFIRM
        Thread.sleep(800L);
        NodeInfo a2 = nodeOf(task.getTaskId(), "A");
        assertEquals("WAIT_CONFIRM", a2.getState());
    }

    @Test
    @DisplayName("TC-H02：非 WAIT_CONFIRM 拒绝 resume")
    void resumeRejected() throws Exception {
        String g = graph(
                "[" + node("A", "echoHandler") + "," + node("B", "asyncHandler", 1, null) + "]",
                "[" + edge("A", "B") + "]");
        TaskInfo task = dagEngine.submit("t_resume_rej", bizId(), g, null);
        Thread.sleep(500L);
        NodeInfo a = nodeOf(task.getTaskId(), "A"); // SUCCESS
        assertFalse(dagEngine.resume(a.getId()));
    }

    // ------------------------------------------------------ J 并发与幂等

    @Test
    @DisplayName("TC-A02/J01：同 biz 重复提交返回已有任务")
    void submitIdempotent() {
        String g = graph("[" + node("A", "echoHandler") + "]", "[]");
        String id = bizId();
        TaskInfo first = dagEngine.submit("t_idem", id, g, null);
        TaskInfo second = dagEngine.submit("t_idem", id, g, null);
        assertEquals(first.getTaskId(), second.getTaskId());
    }

    @Test
    @DisplayName("TC-D06：并发 confirm 仅一个生效")
    void concurrentConfirm() throws Exception {
        String g = graph(
                "[" + node("A", "asyncHandler", 1, null) + "]",
                "[]");
        TaskInfo task = dagEngine.submit("t_cc", bizId(), g, null);
        Thread.sleep(500L);
        NodeInfo a = nodeOf(task.getTaskId(), "A");
        CountDownLatch latch = new CountDownLatch(2);
        AtomicInteger successCount = new AtomicInteger();
        Runnable r = () -> {
            try {
                if (dagEngine.confirm(a.getId(), "{}")) {
                    successCount.incrementAndGet();
                }
            } finally {
                latch.countDown();
            }
        };
        new Thread(r).start();
        new Thread(r).start();
        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertEquals(1, successCount.get());
    }

    // ------------------------------------------------------ I 轨迹

    @Test
    @DisplayName("TC-I01/I03：完整轨迹按时间升序且与状态一致")
    void traceComplete() throws Exception {
        String g = graph(
                "[" + node("A", "echoHandler") + "," + node("B", "failHandler") + "]",
                "[" + edge("A", "B") + "]");
        TaskInfo task = dagEngine.submit("t_trace", bizId(), g, null);
        waitFinal(task.getTaskId(), 10_000L);

        List<TraceEntry> traces = dagEngine.getTrace(task.getTaskId(), null);
        assertFalse(traces.isEmpty());
        // 升序
        for (int i = 1; i < traces.size(); i++) {
            assertFalse(traces.get(i).getCreatedAt().before(traces.get(i - 1).getCreatedAt()));
        }
        // 关键事件齐备
        List<String> types = new java.util.ArrayList<>();
        for (TraceEntry t : traces) {
            types.add(t.getEventType());
        }
        assertTrue(types.contains("TASK_SUBMIT"));
        assertTrue(types.contains("NODE_CLAIM"));
        assertTrue(types.contains("NODE_SUCCESS"));
        assertTrue(types.contains("NODE_FAILED"));
        // 末行 task_state 为任务最终状态（FAILED 收敛合并进节点事件）
        TraceEntry last = traces.get(traces.size() - 1);
        assertEquals("FAILED", last.getTaskState());
        // 状态一致：任务最新状态 == 最后一条流水 task_state
        assertEquals("FAILED", dagEngine.getTask(task.getTaskId()).getState());
    }

    @Test
    @DisplayName("TC-I02：单节点轨迹过滤")
    void traceFilterByNode() throws Exception {
        String g = graph(
                "[" + node("A", "echoHandler") + "," + node("B", "echoHandler") + "]",
                "[" + edge("A", "B") + "]");
        TaskInfo task = dagEngine.submit("t_trace_node", bizId(), g, null);
        waitFinal(task.getTaskId(), 10_000L);
        NodeInfo a = nodeOf(task.getTaskId(), "A");
        List<TraceEntry> aTraces = dagEngine.getTrace(task.getTaskId(), a.getId());
        assertFalse(aTraces.isEmpty());
        for (TraceEntry t : aTraces) {
            assertEquals(a.getId(), t.getNodeId());
        }
    }

    // ------------------------------------------------------ A 校验拒绝

    @Test
    @DisplayName("TC-A04-A09/L03/L04：非法图全部拒绝且不落库")
    void invalidGraphRejected() {
        // 非 JSON
        assertThrows(DagException.class,
                () -> dagEngine.submit("t_inv", bizId(), "not-json", null));
        // 空图 1006
        assertThrows(DagException.class,
                () -> dagEngine.submit("t_inv", bizId(), graph("[]", "[]"), null));
        // 节点重复 1003
        String dup = graph(
                "[" + node("A", "echoHandler") + "," + node("A", "echoHandler") + "]",
                "[]");
        DagException e1 = assertThrows(DagException.class,
                () -> dagEngine.submit("t_inv", bizId(), dup, null));
        assertEquals(1003, e1.getCode());
        // 边引用不存在 1004
        String ref = graph("[" + node("A", "echoHandler") + "]", "[" + edge("A", "Z") + "]");
        DagException e2 = assertThrows(DagException.class,
                () -> dagEngine.submit("t_inv", bizId(), ref, null));
        assertEquals(1004, e2.getCode());
        // 环 1002
        String cycle = graph(
                "[" + node("A", "echoHandler") + "," + node("B", "echoHandler") + "]",
                "[" + edge("A", "B") + "," + edge("B", "A") + "]");
        DagException e3 = assertThrows(DagException.class,
                () -> dagEngine.submit("t_inv", bizId(), cycle, null));
        assertEquals(1002, e3.getCode());
        // 自环 1002
        String self = graph("[" + node("A", "echoHandler") + "]", "[" + edge("A", "A") + "]");
        DagException e4 = assertThrows(DagException.class,
                () -> dagEngine.submit("t_inv", bizId(), self, null));
        assertEquals(1002, e4.getCode());
        // 多重边 1001（已拍板：拒绝）
        String multi = graph(
                "[" + node("A", "echoHandler") + "," + node("B", "echoHandler") + "]",
                "[" + edge("A", "B") + "," + edge("A", "B") + "]");
        DagException e5 = assertThrows(DagException.class,
                () -> dagEngine.submit("t_inv", bizId(), multi, null));
        assertEquals(1001, e5.getCode());
        // handler 未注册（大小写敏感精确匹配 1005）
        String unknownHandler = graph(
                "[" + node("A", "echoHANDLER") + "]",
                "[]");
        DagException e6 = assertThrows(DagException.class,
                () -> dagEngine.submit("t_inv", bizId(), unknownHandler, null));
        assertEquals(1005, e6.getCode());
        // 参数校验 9001
        String valid = graph("[" + node("A", "echoHandler") + "]", "[]");
        DagException e7 = assertThrows(DagException.class,
                () -> dagEngine.submit(null, null, valid, null));
        assertEquals(9001, e7.getCode());
    }

    @Test
    @DisplayName("TC-A10：参数分层——节点参数与全局参数均达执行器")
    void bizArgsLayered() throws Exception {
        Map<String, Object> bizArgs = new HashMap<>();
        bizArgs.put("global", "G1");
        String g = graph(
                "[" + node("A", "echoHandler", 0, "{\"local\":\"L1\"}") + "]",
                "[]");
        TaskInfo task = dagEngine.submit("t_args", bizId(), g, bizArgs);
        waitFinal(task.getTaskId(), 10_000L);
        NodeInfo a = nodeOf(task.getTaskId(), "A");
        assertTrue(a.getResult().contains("\"global\":\"G1\""));
        assertTrue(a.getResult().contains("\"local\":\"L1\""));
    }
}
