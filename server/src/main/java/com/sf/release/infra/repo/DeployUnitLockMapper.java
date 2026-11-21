package com.sf.release.infra.repo;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sf.release.infra.repo.po.DeployUnitLockPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

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
}