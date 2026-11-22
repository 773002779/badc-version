package com.sf.release.infra.repo;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sf.release.infra.repo.po.FreezeEventPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.Instant;
import java.util.List;

/**
 * MyBatis-Plus mapper for {@code freeze_event} (V21). "In force" is
 * {@code status = ACTIVE AND start_at <= at < end_at} — a half-open range, so a freeze that ends
 * exactly at {@code at} no longer blocks and a later freeze starting at {@code at} does.
 */
@Mapper
public interface FreezeMapper extends BaseMapper<FreezeEventPO> {

    /** Every freeze in force at {@code at}, regardless of scope (the 封板 banner list). */
    @Select("SELECT * FROM freeze_event WHERE status = 'ACTIVE'"
        + " AND start_at <= #{at} AND end_at > #{at} ORDER BY id")
    List<FreezeEventPO> selectActive(@Param("at") Instant at);

    /**
     * The freezes in force at {@code at} that affect one system: a GLOBAL freeze always applies, a
     * SUBSYSTEM freeze only to its own subsystem.
     */
    @Select("SELECT * FROM freeze_event WHERE status = 'ACTIVE'"
        + " AND start_at <= #{at} AND end_at > #{at}"
        + " AND (scope = 'GLOBAL' OR (scope = 'SUBSYSTEM' AND subsystem_id = #{subsystemId}))"
        + " ORDER BY id")
    List<FreezeEventPO> selectActiveForSubsystem(@Param("subsystemId") long subsystemId,
                                                 @Param("at") Instant at);

    /**
     * The freezes the daily scan must close: still {@code ACTIVE} although their {@code end_at}
     * already passed. A released freeze is never touched again (a person's lift stays the record).
     */
    @Select("SELECT * FROM freeze_event WHERE status = 'ACTIVE' AND end_at <= #{now}"
        + " ORDER BY id LIMIT #{limit}")
    List<FreezeEventPO> selectDueForExpiry(@Param("now") Instant now, @Param("limit") int limit);
}