package com.sf.release.domain.task;

import com.sf.release.domain.resource.UnitType;

import java.time.Instant;
import java.time.LocalDate;

/**
 * A 变更项 (release item / CRID): the smallest publishable unit — one deployment unit or
 * second-party library plus one CODING build task (requirements §7, tech design §4.2).
 *
 * <p>Aggregate root of the item boundary: the main {@link ItemStatus} and the orthogonal
 * {@link GateStatus} are written in the same transaction, and every transition is CAS-ed on
 * {@code rowVersion} (tech design §4.3) so a lost update is retried instead of overwriting
 * the other writer. {@code buildPlanId} is the CODING build task snapshot taken at creation,
 * and {@code sourceBranch} is a read-only snapshot of the branch the CODING task builds.
 *
 * <p>Pure domain value: text fields are trimmed on construction and blank values become
 * {@code null}. Defaults mirror the DDL: {@code type = UNIT}, {@code status = PENDING},
 * {@code gateStatus = TEST_PENDING}, {@code rowVersion = 0}.
 *
 * <p>{@code baseCommit} and {@code lastTestPassBy/At} are the fields the rerun planner and
 * the gate evaluator will read in later tasks; Task 8 only persists them.
 */
public record ReleaseItem(
    Long id,
    Long taskId,
    String crid,
    UnitType type,
    Long unitId,
    String buildPlanId,
    String sourceBranch,
    String modulePath,
    String version,
    LocalDate planReleaseDate,
    ItemStatus status,
    GateStatus gateStatus,
    String baseCommit,
    String lastTestPassBy,
    Instant lastTestPassAt,
    int rowVersion,
    Instant createdAt,
    Instant updatedAt) {

    public ReleaseItem {
        crid = trimToNull(crid);
        buildPlanId = trimToNull(buildPlanId);
        sourceBranch = trimToNull(sourceBranch);
        modulePath = trimToNull(modulePath);
        version = trimToNull(version);
        baseCommit = trimToNull(baseCommit);
        lastTestPassBy = trimToNull(lastTestPassBy);
        type = type == null ? UnitType.UNIT : type;
        status = status == null ? ItemStatus.PENDING : status;
        gateStatus = gateStatus == null ? GateStatus.TEST_PENDING : gateStatus;
        rowVersion = Math.max(rowVersion, 0);
    }

    /** A copy carrying a new main status; the caller persists it with a row_version CAS. */
    public ReleaseItem withStatus(ItemStatus next) {
        return new ReleaseItem(id, taskId, crid, type, unitId, buildPlanId, sourceBranch,
            modulePath, version, planReleaseDate, next, gateStatus, baseCommit,
            lastTestPassBy, lastTestPassAt, rowVersion, createdAt, updatedAt);
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}