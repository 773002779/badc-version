package com.sf.release.infra.repo;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sf.release.infra.repo.po.PipelineNodePO;
import org.apache.ibatis.annotations.Mapper;

/** MyBatis-Plus mapper for {@code pipeline_node}. */
@Mapper
public interface PipelineNodeMapper extends BaseMapper<PipelineNodePO> {
}