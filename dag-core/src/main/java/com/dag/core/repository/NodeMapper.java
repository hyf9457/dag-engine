package com.dag.core.repository;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.Date;
import java.util.List;

/**
 * node_instance Mapper（全部状态流转为单条 CAS 条件更新）。
 */
@Mapper
public interface NodeMapper {

    int insertBatch(@Param("list") List<NodeDO> nodes);

    List<NodeDO> selectByTaskId(@Param("taskId") String taskId);

    NodeDO selectById(@Param("id") String id);

    /** 领取：PENDING → RUNNING（CAS，影响行数=1 才算成功） */
    int claim(@Param("id") String id);

    /** 同步成功：RUNNING → SUCCESS */
    int finishSuccess(@Param("id") String id, @Param("result") String result);

    /** 同步失败：RUNNING → FAILED */
    int finishFailed(@Param("id") String id, @Param("result") String result);

    /** 异步挂起：RUNNING → WAIT_CONFIRM */
    int markWaitConfirm(@Param("id") String id);

    /** 回调确认：WAIT_CONFIRM → SUCCESS */
    int confirmSuccess(@Param("id") String id, @Param("result") String result);

    /** 回调失败：WAIT_CONFIRM → FAILED */
    int confirmFailed(@Param("id") String id, @Param("result") String result);

    /** 超时：RUNNING 且 started_at < deadline → FAILED（time_out=1，result=timeout） */
    int timeoutFail(@Param("id") String id, @Param("deadline") Date deadline);

    /** 巡检重置：RUNNING → PENDING（执行器已死，重新调度） */
    int resetRunning(@Param("id") String id);

    /** 续跑：WAIT_CONFIRM → PENDING */
    int resumePending(@Param("id") String id);

    /** 重试 CONTINUE：FAILED / WAIT_CONFIRM → PENDING */
    int resetForContinue(@Param("taskId") String taskId);

    /** 重试 RESTART：任务下全部节点 → PENDING */
    int resetAll(@Param("taskId") String taskId);

    /** 取消节点：非终态 → CANCELLED */
    int cancelNodes(@Param("taskId") String taskId);

    /** 强制终态（forceFinalize）：任务下全部非终态节点 → 指定终态 */
    int finalizeNodes(@Param("taskId") String taskId, @Param("state") String state);

    /** 巡检扫描：RUNNING 节点（LIMIT 防全表） */
    List<NodeDO> selectRunningNodes(@Param("limit") int limit);
}
