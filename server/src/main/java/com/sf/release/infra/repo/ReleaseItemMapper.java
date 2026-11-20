package com.sf.release.infra.repo;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sf.release.infra.repo.po.ReleaseItemPO;
import org.apache.ibatis.annotations.Mapper;

/** MyBatis-Plus mapper for {@code release_item}. */
@Mapper
public interface ReleaseItemMapper extends BaseMapper<ReleaseItemPO> {
}