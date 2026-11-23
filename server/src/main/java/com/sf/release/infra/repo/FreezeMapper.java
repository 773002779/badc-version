package com.sf.release.infra.repo;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sf.release.infra.repo.po.FreezeEventPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.Instant;
import java.util.List;

/**
 * MyBatis-Plus mapper for {@code freeze_event} (V21).
 *
 * <p>Two predicates, deliberately kept apart:
 *
 * <ul>
 *   <li><strong>in force</strong> ({@code selectActive*}): {@code status = ACTIVE AND start_at <= at
 *       AND end_at > at} — the display question "is production frozen right now", also used by the
 *       pre-execution review and the batch-boundary probe;</li>
 *   <li><strong>governing</strong> ({@code selectGoverning*}): {@code status = ACTIVE AND end_at >
 *       now AND start_at <= threshold} with {@code threshold = max(now, plannedAt)} — the decision
 *       question of admission, in-flight handling and the unfreeze recompute. A freeze declared for
 *       next week governs a release planned for next week today (fail-closed). With no planned time
 *       the threshold is {@code now}, which is exactly the in-force range.</li>
 * </ul>
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

    /** Every freeze that governs a release at {@code now} planned for {@code threshold}. */
    @Select("SELECT * FROM freeze_event WHERE status = 'ACTIVE'"
        + " AND end_at > #{now} AND start_at <= #{threshold} ORDER BY id")
    List<FreezeEventPO> selectGoverning(@Param("now") Instant now,
                                        @Param("threshold") Instant threshold);

    /**
     * The freezes that govern a release of one system at {@code now} planned for
     * {@code threshold}: a GLOBAL freeze always applies, a SUBSYSTEM freeze only to its own.
     */
    @Select("SELECT * FROM freeze_event WHERE status = 'ACTIVE'"
        + " AND end_at > #{now} AND start_at <= #{threshold}"
        + " AND (scope = 'GLOBAL' OR (scope = 'SUBSYSTEM' AND subsystem_id = #{subsystemId}))"
        + " ORDER BY id")
    List<FreezeEventPO> selectGoverningForSubsystem(@Param("subsystemId") long subsystemId,
                                                    @Param("now") Instant now,
                                                    @Param("threshold") Instant threshold);

    /**
     * The freezes the daily scan must close: still {@code ACTIVE} although their {@code end_at}
     * already passed. A released freeze is never touched again (a person's lift stays the record).
     */
    @Select("SELECT * FROM freeze_event WHERE status = 'ACTIVE' AND end_at <= #{now}"
        + " ORDER BY id LIMIT #{limit}")
    List<FreezeEventPO> selectDueForExpiry(@Param("now") Instant now, @Param("limit") int limit);
}