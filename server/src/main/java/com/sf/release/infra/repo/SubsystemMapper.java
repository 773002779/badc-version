package com.sf.release.infra.repo;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sf.release.infra.repo.po.SubsystemPO;
import org.apache.ibatis.annotations.Mapper;

/** MyBatis-Plus mapper for {@code subsystem}; CRUD plus paging via {@code selectPage}. */
@Mapper
public interface SubsystemMapper extends BaseMapper<SubsystemPO> {
}