package com.sf.release.infra.repo;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sf.release.infra.repo.po.AppSettingPO;
import org.apache.ibatis.annotations.Mapper;

/**
 * MyBatis-Plus mapper for {@code app_setting} (V30). The table is a small key/value store; the
 * service updates one row per section with an explicit column set so {@code updated_by} can be
 * replaced and a missing seed row can be repaired.
 */
@Mapper
public interface AppSettingMapper extends BaseMapper<AppSettingPO> {
}