package com.sf.release.application;

import com.sf.release.common.BizError;
import com.sf.release.common.BizException;
import com.sf.release.infra.repo.DeployUnitLockMapper;
import com.sf.release.infra.repo.DeployUnitMapper;
import com.sf.release.infra.repo.RollbackRecordMapper;
import com.sf.release.infra.repo.po.DeployUnitLockPO;
import com.sf.release.infra.repo.po.DeployUnitPO;
import com.sf.release.infra.repo.po.RollbackVersionRow;
import com.sf.release.infra.security.ScopeGuard;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Objects;

/**
 * The unit's 发布概要 read model of the unit list's three new columns (PART-01 design §1):
 * the newest successful production deploy (the "最近发布版本 / 时间" columns) and the current
 * unit lock row (the "占用锁" column).
 *
 * <p><strong>Reused queries, no new tables.</strong> The newest production deploy is exactly the
 * rollback drawer's "current online version" ({@link RollbackRecordMapper#selectCurrent}), so the
 * list column and the rollback target share one definition; the lock row is the plain
 * {@code deploy_unit_lock} read (a lock row past its lease still shows until the reclaim job runs,
 * which is what the operator sees anyway).
 *
 * <p>Read-only and unit-scoped: the endpoint answers 40000 for an unknown unit, 40009 outside the
 * caller's data scope, and {@code null} sections instead of errors for a unit that never deployed
 * to production or is not locked.
 */
@Service
public class UnitReleaseSummaryService {

    private final DeployUnitMapper unitMapper;
    private final RollbackRecordMapper rollbackRecordMapper;
    private final DeployUnitLockMapper lockMapper;

    public UnitReleaseSummaryService(DeployUnitMapper unitMapper,
                                     RollbackRecordMapper rollbackRecordMapper,
                                     DeployUnitLockMapper lockMapper) {
        this.unitMapper = Objects.requireNonNull(unitMapper, "unitMapper must not be null");
        this.rollbackRecordMapper = Objects.requireNonNull(rollbackRecordMapper,
            "rollbackRecordMapper must not be null");
        this.lockMapper = Objects.requireNonNull(lockMapper, "lockMapper must not be null");
    }

    /**
     * The release summary of one unit.
     *
     * @throws BizException 40000 unknown unit; 40009 the unit is outside the caller's data scope
     */
    public Summary summary(long unitId) {
        DeployUnitPO unit = unitMapper.selectById(unitId);
        if (unit == null) {
            throw new BizException(BizError.PARAM_INVALID, "部署单元不存在：" + unitId,
                Map.of("unitId", unitId));
        }
        ScopeGuard.assertUnitInScope(unitId);
        RollbackVersionRow latest = rollbackRecordMapper.selectCurrent(unitId);
        DeployUnitLockPO lock = lockMapper.selectById(unitId);
        return new Summary(
            unitId,
            latest == null ? null : new LatestRelease(
                latest.getDigest(), latest.getPublishNo(), latest.getFinishedAt(),
                latest.getExecutedBy()),
            lock == null ? null : new UnitLock(
                lock.getInstanceId() == null ? 0L : lock.getInstanceId(),
                lock.getAcquiredAt(), lock.getExpireAt()));
    }

    /** The newest successful production deploy of the unit, or null when there was none. */
    public record LatestRelease(String digest, String publishNo,
                                java.time.Instant finishedAt, String executedBy) {
    }

    /** The current lock row, or null when the unit is free. */
    public record UnitLock(long instanceId, java.time.Instant acquiredAt,
                           java.time.Instant expireAt) {
    }

    /** The unit list's three columns in one read. */
    public record Summary(long unitId, LatestRelease latest, UnitLock lock) {
    }
}
