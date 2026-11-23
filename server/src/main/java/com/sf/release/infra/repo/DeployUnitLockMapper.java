package com.sf.release.infra.repo;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sf.release.infra.repo.po.DeployUnitLockPO;
import com.sf.release.infra.repo.po.LockedInstanceRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * MyBatis-Plus mapper for {@code deploy_unit_lock}.
 *
 * <p>{@link #forceExpire} is the test/administration seam for the expiry path: it moves the
 * row's {@code expire_at} a day into the past so the next {@code UnitLockService.acquire} may
 * reclaim it, without waiting for the four-hour TTL.
 *
 * <p>It subtracts from the row's own {@code expire_at} instead of using {@code NOW(3)} on
 * purpose: {@code expire_at} is written from a Java {@code Instant} (UTC) while the MySQL
 * session clock is the host's local time zone, so a database-generated timestamp could still
 * look "in the future" when read back as UTC. A relative change is time-zone independent.
 */
@Mapper
public interface DeployUnitLockMapper extends BaseMapper<DeployUnitLockPO> {

    /**
     * Marks the lock row of {@code unitId} as expired.
     *
     * @return the number of rows changed (0 when the unit is not locked)
     */
    @Update("UPDATE deploy_unit_lock"
        + " SET expire_at = DATE_SUB(expire_at, INTERVAL 1 DAY)"
        + " WHERE unit_id = #{unitId}")
    int forceExpire(@Param("unitId") long unitId);

    /**
     * The lock rows whose owning pipeline is still live ({@code RUNNING} or {@code PAUSED}), bounded
     * by {@code limit}. The scheduler renews exactly these leases: a pause can last hours (approval,
     * a freeze), and without renewal the four-hour lease would expire and let another publisher
     * reclaim the unit while the release still owns it.
     *
     * <p>A terminal instance is not returned: its lease is supposed to end (the orchestrator releases
     * it), and renewing it would block the unit forever. The caller stops renewing a {@code PAUSED}
     * instance once its {@code paused_at} is older than the configured bound — an abandoned pause
     * (a rejected release nobody resubmits) must not hold a unit for ever either.
     */
    @Select("SELECT l.unit_id AS unitId, l.instance_id AS instanceId, pi.status AS status,"
        + " pi.paused_at AS pausedAt"
        + " FROM deploy_unit_lock l"
        + " JOIN pipeline_instance pi ON pi.id = l.instance_id"
        + " WHERE pi.status IN ('RUNNING', 'PAUSED')"
        + " ORDER BY l.unit_id LIMIT #{limit}")
    List<LockedInstanceRow> selectLiveOwners(@Param("limit") int limit);
}