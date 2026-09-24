package com.dag.core.repository;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * flow_definition Mapper。
 */
@Mapper
public interface FlowDefinitionMapper {

    FlowDefinitionDO selectByBiz(@Param("bizType") String bizType, @Param("bizId") String bizId);

    int insert(FlowDefinitionDO flowDefinition);
}
