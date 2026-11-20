package com.sf.release.infra.repo;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sf.release.infra.repo.po.PipelineInstancePO;
import org.apache.ibatis.annotations.Mapper;

/** MyBatis-Plus mapper for {@code pipeline_instance}. */
@Mapper
public interface PipelineInstanceMapper extends BaseMapper<PipelineInstancePO> {
}