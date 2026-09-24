package com.dag.core;

import com.dag.core.enums.FinalState;
import com.dag.core.enums.RetryMode;
import com.dag.core.model.NodeInfo;
import com.dag.core.model.TaskInfo;
import com.dag.core.model.TraceEntry;

import java.util.List;
import java.util.Map;

/**
 * DAG 流程引擎门面（Spring Bean，业务方注入）。
 * <p>全部为进程内 API（dag-core 以 Spring Bean 注入业务方，无 HTTP 端点）。
 * 幂等 / 状态不匹配场景不抛异常，返回 false 或已有数据。</p>
 */
public interface DagEngine {

    /** 提交固定流程（graph 取自 flow_definition，按 biz_type+biz_id 匹配；同 biz 幂等返回已有任务） */
    TaskInfo submit(String bizType, String bizId, Map<String, Object> bizArgs);

    /** 提交自定义流程（直接携带 graph_json，校验可执行性后执行） */
    TaskInfo submit(String bizType, String bizId, String graphJson, Map<String, Object> bizArgs);

    /** 回调确认：仅 WAIT_CONFIRM 节点生效；重复/晚到返回 false（幂等） */
    boolean confirm(String nodeInstanceId, String resultJson);

    /** 回调失败：仅 WAIT_CONFIRM 节点生效；返回 false 表示未生效（状态不匹配） */
    boolean fail(String nodeInstanceId, String error);

    /** 取消：仅 RUNNING 任务可取消；终态返回 false */
    boolean cancel(String taskId);

    /** 重试：仅 FAILED 任务可重试；非 FAILED 抛 3001；mode 见 RetryMode */
    TaskInfo retry(String taskId, RetryMode mode);

    /** 断点续跑：仅 WAIT_CONFIRM 节点可 resume；其他状态返回 false */
    boolean resume(String nodeInstanceId);

    /** 查询任务 */
    TaskInfo getTask(String taskId);

    /** 查询任务下全部节点 */
    List<NodeInfo> getNodes(String taskId);

    /** 查询任务运行轨迹（单表流水，按时间升序；nodeId 可空=全部） */
    List<TraceEntry> getTrace(String taskId, String nodeId);

    /** 运维强制终态（挂起兜底）：任务/节点 → 指定终态 */
    boolean forceFinalize(String taskId, FinalState state);
}
