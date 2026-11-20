package com.sf.release.infra.repo;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sf.release.infra.repo.po.ReleaseTaskMemberPO;
import org.apache.ibatis.annotations.Mapper;

/** MyBatis-Plus mapper for {@code release_task_member}. */
@Mapper
public interface ReleaseTaskMemberMapper extends BaseMapper<ReleaseTaskMemberPO> {
}