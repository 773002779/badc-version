package com.sf.release.infra.repo;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sf.release.infra.repo.po.ReleaseOrderPO;
import org.apache.ibatis.annotations.Mapper;

/** MyBatis-Plus mapper for {@code release_order}. */
@Mapper
public interface ReleaseOrderMapper extends BaseMapper<ReleaseOrderPO> {
}