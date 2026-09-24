package com.dag.core.repository;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * task_trace Mapper（流水只追加不改）。
 */
@Mapper
public interface TraceMapper {

    int insert(TraceDO trace);

    List<TraceDO> selectByTaskId(@Param("taskId") String taskId);

    List<TraceDO> selectByTaskAndNode(@Param("taskId") String taskId, @Param("nodeId") String nodeId);
}
