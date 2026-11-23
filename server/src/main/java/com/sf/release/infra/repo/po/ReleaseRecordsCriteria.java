package com.sf.release.infra.repo.po;

import java.util.List;

/**
 * Bound filter of the global release-records queries (GAP-10): the user filters plus the caller's
 * data scope, so the count and the page statements share exactly the same predicates.
 *
 * <p><strong>Scope is a predicate, not a widening.</strong> {@code allUnits} is the explicit
 * unrestricted marker; otherwise {@code unitId IN (scopeUnitIds)} is ANDed with every user filter.
 * The service never builds this for a caller with an empty scope: that case is answered with an
 * empty page before any statement runs (fail-closed).
 */
public class ReleaseRecordsCriteria {

    private final String keyword;
    private final String status;
    private final Long subsystemId;
    private final Long unitId;
    private final boolean allUnits;
    private final List<Long> scopeUnitIds;

    public ReleaseRecordsCriteria(String keyword, String status, Long subsystemId, Long unitId,
                                  boolean allUnits, List<Long> scopeUnitIds) {
        this.keyword = keyword;
        this.status = status;
        this.subsystemId = subsystemId;
        this.unitId = unitId;
        this.allUnits = allUnits;
        this.scopeUnitIds = scopeUnitIds == null ? List.of() : List.copyOf(scopeUnitIds);
    }

    public String getKeyword() {
        return keyword;
    }

    public String getStatus() {
        return status;
    }

    public Long getSubsystemId() {
        return subsystemId;
    }

    public Long getUnitId() {
        return unitId;
    }

    public boolean isAllUnits() {
        return allUnits;
    }

    public List<Long> getScopeUnitIds() {
        return scopeUnitIds;
    }
}