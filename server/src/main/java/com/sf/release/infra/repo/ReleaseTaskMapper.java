package com.sf.release.infra.repo;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sf.release.infra.repo.po.ReleaseTaskPO;
import org.apache.ibatis.annotations.Mapper;

/** MyBatis-Plus mapper for {@code release_task}. */
@Mapper
public interface ReleaseTaskMapper extends BaseMapper<ReleaseTaskPO> {
}