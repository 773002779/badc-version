package com.sf.release.infra.repo;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sf.release.infra.repo.po.UnitPipelineConfigPO;
import org.apache.ibatis.annotations.Mapper;

/**
 * MyBatis-Plus mapper for {@code unit_pipeline_config} (V31, P6-C design §1).
 *
 * <p>The table keyed by {@code unit_id} has no generated id, hence {@code IdType.INPUT} on the PO;
 * the service updates the row in place and only inserts when it does not exist yet.
 */
@Mapper
public interface UnitPipelineConfigMapper extends BaseMapper<UnitPipelineConfigPO> {
}