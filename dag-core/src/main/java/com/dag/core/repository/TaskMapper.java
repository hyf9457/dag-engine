package com.dag.core.repository;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * task_instance Mapper（全部状态流转为单条 CAS 条件更新）。
 */
@Mapper
public interface TaskMapper {

    TaskDO selectByTaskId(@Param("taskId") String taskId);

    TaskDO selectByBiz(@Param("bizType") String bizType, @Param("bizId") String bizId);

    int insert(TaskDO task);

    /** 收敛：RUNNING → 指定终态（CAS，影响行数=1 才算成功） */
    int converge(@Param("taskId") String taskId, @Param("state") String state);

    /** 收敛 SUCCESS：全节点 SUCCESS 时 RUNNING → SUCCESS（单条原子 SQL，含子查询判定，避免事务内读-写交叉） */
    int convergeSuccess(@Param("taskId") String taskId);

    /** 收敛 FAILED：存在任一 FAILED 节点时 RUNNING → FAILED（单条原子 SQL） */
    int convergeFailed(@Param("taskId") String taskId);

    /** 取消：RUNNING → CANCELLED（CAS） */
    int cancel(@Param("taskId") String taskId);

    /** 重试：FAILED → RUNNING（CAS） */
    int resetToRunning(@Param("taskId") String taskId);

    /** 兜底扫描：取活跃 RUNNING 任务（LIMIT 防全表） */
    List<TaskDO> selectRunningTasks(@Param("limit") int limit);
}
