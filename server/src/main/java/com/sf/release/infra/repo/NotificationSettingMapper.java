package com.sf.release.infra.repo;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sf.release.infra.repo.po.NotificationSettingPO;
import org.apache.ibatis.annotations.Mapper;

/**
 * MyBatis-Plus mapper for {@code notification_setting}. The table has one row ({@code id = 1}); the
 * service updates it with an explicit column set so the webhook can be cleared again.
 */
@Mapper
public interface NotificationSettingMapper extends BaseMapper<NotificationSettingPO> {
}