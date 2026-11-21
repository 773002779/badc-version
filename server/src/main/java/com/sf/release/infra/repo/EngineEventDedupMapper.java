package com.sf.release.infra.repo;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sf.release.infra.repo.po.EngineEventDedupPO;
import org.apache.ibatis.annotations.Mapper;

/**
 * MyBatis-Plus mapper for {@code engine_event_dedup}. The insert is intentionally a plain
 * {@link BaseMapper#insert}: a duplicate primary key surfaces as
 * {@code DuplicateKeyException}, which the webhook treats as "already processed".
 */
@Mapper
public interface EngineEventDedupMapper extends BaseMapper<EngineEventDedupPO> {
}