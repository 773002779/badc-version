package com.sf.release.infra.repo;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sf.release.infra.repo.po.DeployUnitPO;
import org.apache.ibatis.annotations.Mapper;

/** MyBatis-Plus mapper for {@code deploy_unit}. */
@Mapper
public interface DeployUnitMapper extends BaseMapper<DeployUnitPO> {
}