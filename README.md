# dag-engine — 开箱即用的 DAG 流程引擎 Starter

基于 **Java 8 + Spring Boot 2.5.x** 的单机内嵌流程引擎，业务方引入 `dag-core` 依赖即可获得：
流程定义（静态/动态）、DAG 串行/并行/汇聚推进、节点异步回调确认、超时判定、失败重试、取消、断点续跑、全链路流水追踪。

> 设计文档见 `docs/`（01 需求规格 → 09 验收）；本文件为接入指南。

---

## 1. 特性一览

| 能力 | 说明 |
|---|---|
| DAG 拓扑 | 串行、并行、汇聚；多重边拒绝；孤立节点视为单节点任务 |
| 流程来源 | 静态流程（`flow_definition` 表）或调用方提交自定义 `graph_json`（提交时校验可执行性） |
| 节点执行 | 同步 Handler 驱动；异步节点由业务方通过 API 回调确认（无需 HTTP 回调通道） |
| 超时 | 平台统一超时（`dag.node.timeout-seconds`，节点不自定义）；**异步等待确认的节点无超时** |
| 重试 | FAILED 任务可重试：`CONTINUE`（仅重置失败/挂起节点）或 `RESTART`（全量重跑） |
| 取消 / 续跑 | 仅 RUNNING 任务可取消；仅 WAIT_CONFIRM 节点可续跑（状态校验） |
| 多实例安全 | 单机内嵌、多副本共享 MySQL：节点领取 CAS + Redisson 分布式锁（watchdog 续期）防重复执行 |
| 异常恢复 | 统一巡检：执行器崩溃（锁无人持有）→ 重置重跑；存活超时 → FAILED(time_out=1) |
| 可观测 | `trace_flow` 流水表记录任务/节点全轨迹（提交、领取、成功、失败、超时、重置、重试、取消、回调…） |

## 2. 模块结构

```
dag-engine
├── dag-core    # 引擎核心（Spring Boot Starter，业务方引入）
├── dag-demo    # 接入指引 + 验收测试（H2 本地 + 真实 MySQL/Redis）
└── docs        # 需求/架构/类图/数据库/接口/异常/测试文档
```

## 3. 快速开始

### 3.1 引入依赖

```xml
<dependency>
    <groupId>com.dag</groupId>
    <artifactId>dag-core</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

### 3.2 最小配置（application.yml）

```yaml
spring:
  datasource:            # 业务方自己的数据源（引擎复用，自动建表见 3.6）
    url: jdbc:mysql://127.0.0.1:3306/your_db?useUnicode=true&characterEncoding=utf8&useSSL=false
    username: root
    password: your_pwd

dag:
  enabled: true                      # 引擎总开关
  scheduler:
    enabled: true                    # 定时调度 + 异常巡检
    interval-ms: 1000
  executor:
    threads: 8
  node:
    timeout-seconds: 60              # 节点超时（平台统一）
  lock:
    redis-required: true             # 多实例部署必须 true；false 时降级本地锁并关闭异常巡检
    redis-address: redis://127.0.0.1:6379
    redis-password:                  # 可为空
    # 生产 Redis Cluster（二选一，配置后自动走集群拓扑发现）：
    # redis-cluster-addresses: redis://node1:6379,redis://node2:6379,redis://node3:6379
```

### 3.3 实现执行器（Handler）

实现 `NodeHandler`，用 `@Component("名称")` 注册——**节点定义里的 `handler` 字段必须与 Bean 名大小写严格一致**：

```java
@Component("payHandler")
public class PayHandler implements NodeHandler {
    @Override
    public ExecutionResult execute(NodeContext context) {
        // context.getBizArgs()   任务全局业务参数（submit 传入）
        // context.getNodeArgs()  节点业务参数（graph_json nodes[].biz_args）
        // context.getUpstreamResults()  上游节点结果（key = biz_node_id）
        boolean ok = doPay(context.getNodeArgs().get("orderId"));
        if (ok) {
            return ExecutionResult.success("{\"payNo\":\"P20240924001\"}"); // 结果回写 node.result
        }
        return ExecutionResult.failure("支付失败");                          // 节点 FAILED，任务收敛 FAILED
    }
}

@Component("auditHandler")
public class AuditHandler implements NodeHandler {
    @Override
    public ExecutionResult execute(NodeContext context) {
        return ExecutionResult.pending(); // 异步挂起：WAIT_CONFIRM，等待业务方 confirm/fail
    }
}
```

### 3.4 定义流程（graph_json）

```json
{
  "nodes": [
    { "biz_node_id": "A", "handler": "payHandler", "async_type": 0, "biz_args": { "orderId": "20240924001" } },
    { "biz_node_id": "B", "handler": "auditHandler", "async_type": 1 },
    { "biz_node_id": "C", "handler": "shipHandler", "async_type": 0 }
  ],
  "edges": [
    { "from": "A", "to": "B" },
    { "from": "B", "to": "C" }
  ]
}
```

- `biz_node_id`：调用方自定义的节点唯一标识（同一任务内唯一）；
- `handler`：对应 `@Component` Bean 名（大小写敏感）；
- `async_type`：声明标记，**运行期以 Handler 返回值为准**（返回 `pending()` 即进入 WAIT_CONFIRM）；
- `biz_args`：节点业务参数，执行时经 `context.getNodeArgs()` 提供给 Handler；
- 校验：多重边拒绝（1004）、孤立节点视为单节点任务、handler 必须已注册（1005）、有向无环（1002）。

### 3.5 提交任务并驱动

```java
@Autowired
private DagEngine dagEngine;

// 提交自定义流程（动态）
TaskInfo task = dagEngine.submit("order", "20240924001", graphJson, bizArgs);

// 提交静态流程（biz_type+biz_id 命中 flow_definition 表）
TaskInfo task = dagEngine.submit("order", "20240924001", bizArgs);

// 提交幂等：同 (biz_type, biz_id) 重复提交返回同一任务
```

节点由引擎**推式调度**：根节点立即执行 → 节点终态后经 DAG 边判断前驱是否全部 SUCCESS → 满足即推进下游。无需 MQ、无需拉取。

### 3.6 表结构初始化

引擎启动时自动执行 `dag-core` 内置 `schema.sql`（幂等建表）。需建 4 张表：

| 表 | 说明 |
|---|---|
| `flow_definition` | 静态流程定义（biz_type, biz_id, graph_json, status） |
| `task_instance` | 任务实例（biz_type, biz_id, graph_json, state, biz_args） |
| `node_instance` | 节点实例（task_id, biz_node_id, handler, state, result, time_out, async_type, biz_args） |
| `trace_flow` | 运行流水（task_state + node_state 双状态快照，按 task_id 时间序即完整轨迹） |

## 4. 核心 API（`com.dag.core.DagEngine`）

| 方法 | 说明 |
|---|---|
| `submit(bizType, bizId[, graphJson], bizArgs)` | 提交任务（静态/自定义），幂等 |
| `confirm(nodeInstanceId, resultJson)` | 异步节点确认成功（WAIT_CONFIRM→SUCCESS，重复回调幂等） |
| `fail(nodeInstanceId, error)` | 异步节点确认失败（WAIT_CONFIRM→FAILED） |
| `retry(taskId, RetryMode)` | 重试 FAILED 任务：`CONTINUE` / `RESTART` |
| `resume(nodeInstanceId)` | 断点续跑（WAIT_CONFIRM→PENDING 重新执行） |
| `cancel(taskId)` | 取消 RUNNING 任务（→CANCELLED） |
| `forceFinalize(taskId, FinalState)` | 运维强制终态（SUCCESS/FAILED/CANCELLED） |
| `getTask / getNodes / getTrace` | 查询任务、节点、流水 |

## 5. 状态机

**节点**：`PENDING → RUNNING → SUCCESS / FAILED / WAIT_CONFIRM`；`WAIT_CONFIRM → SUCCESS / FAILED / PENDING(续跑)`；非终态可被 `CANCELLED`。

**任务（收敛态）**：全部节点 SUCCESS → `SUCCESS`；任一节点 FAILED → `FAILED`；用户取消 → `CANCELLED`；其余为 `RUNNING`。

**超时**：同步节点 RUNNING 超过 `timeout-seconds` 且执行器存活 → `FAILED`（`time_out=1`、`result=timeout`）；异步等待节点无超时。

## 6. 关键机制（多实例安全与异常恢复）

1. **推式调度 + CAS 防重**：节点领取 `PENDING→RUNNING` 为条件更新（影响行数=1 才生效），多实例/调度+巡检双路径不会重复执行；
2. **Redisson 分布式锁**：执行前加锁（watchdog 30s 自动续期 = 执行器存活标记），节点间互斥；
3. **统一巡检**（每 `interval-ms`）：对 RUNNING 节点 tryLock——**抢到锁 = 执行器已死** → 重置 PENDING 重新调度；**抢不到 = 执行器存活** → 超时判定（锁判定先于超时判定，崩溃永不被误判为业务超时）；
4. **崩溃恢复**：执行中服务挂掉（节点 RUNNING 且锁失效）→ 巡检重置 → 重新执行；提交后崩溃（节点终态未落库）→ 兜底扫描收敛；
5. **流水追踪**：每次状态变化原子写入 `trace_flow`（任务状态+节点状态双快照），重试/重跑/取消均留痕。

## 7. 测试与验收

| 测试 | 运行方式 | 覆盖 |
|---|---|---|
| `DagEngineIntegrationTest`（H2 本地） | `mvn -pl dag-demo test` | 主链路 24 用例：串并行/汇聚/失败/重试/取消/续跑/幂等/回调/流水/非法图… |
| `RealEnvIntegrationTest`（真实 MySQL+Redis） | `mvn -pl dag-demo test -Dtest=RealEnvIntegrationTest`（`application-real.yml`，@ActiveProfiles("real")） | 7 用例：超时巡检、崩溃恢复、存活不误判、Redis 锁并发 confirm、主链路冒烟 |

> 真实环境用例统一前置「可写 Redis」（探针自动判定）：Redis 不可写时 Skipped 并附原因，可写后自动全量生效。测试基建细节与验收结论见 `docs/07-异常与恢复.md §7`。

## 8. 已知限制（设计已拍板）

- 无失败自动重试（超时/失败后由调用方调 `retry`）；无补偿回滚；无条件分支；无子流程嵌套；
- 超时时间平台统一，不支持节点自定义；
- 任务/节点 ID 由引擎生成（业务方不可指定）；
- 节点 Handler 为同步模型（异步语义通过返回 `pending()` + API 回调实现）。

## 9. 版本与环境

- 构建：JDK 8 / Maven 3.8；Spring Boot 2.5.x；MySQL 5.6+（8.0 兼容）；Redisson 3.17（随 `dag-core` 传递，无需单独引入）。
