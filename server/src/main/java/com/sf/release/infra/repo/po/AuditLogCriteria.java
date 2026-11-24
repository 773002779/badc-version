package com.sf.release.infra.repo.po;

import java.time.Instant;
import java.util.List;

/**
 * Bound filter of the audit queries (audit design section 4): the caller's filters plus the resolved
 * data scope, shared by the count, the page and the export statement so their predicates cannot
 * drift apart.
 *
 * <p><strong>Scope is a predicate, not a widening.</strong> {@code allUnits} is the explicit
 * unrestricted marker. Otherwise the rows the caller may read are the unit-scoped rows of the
 * caller's units plus the subsystem-scoped rows of those units' subsystems:
 * {@code scope_unit_id IN (scopeUnitIds) OR (scope_unit_id IS NULL AND scope_subsystem_id IN
 * (subsystems of scopeUnitIds))}. Platform rows (both columns NULL) therefore stay invisible to a
 * scoped caller, which is the design's fail-closed rule. The service never builds this for a caller
 * with an empty scope: it answers with an empty page/CSV before any statement runs.
 *
 * <p>Unlike the release-records page, an explicit {@code unitId} outside the scope is answered as an
 * empty result (design section 6): the audit page must not tell a caller whether that unit exists,
 * it simply shows nothing.
 */
public class AuditLogCriteria {

    private final String objectType;
    private final String objectId;
    private final String action;
    private final String actor;
    private final Instant from;
    private final Instant to;
    private final Long unitId;
    private final boolean allUnits;
    private final List<Long> scopeUnitIds;

    public AuditLogCriteria(String objectType, String objectId, String action, String actor,
                            Instant from, Instant to, Long unitId, boolean allUnits,
                            List<Long> scopeUnitIds) {
        this.objectType = objectType;
        this.objectId = objectId;
        this.action = action;
        this.actor = actor;
        this.from = from;
        this.to = to;
        this.unitId = unitId;
        this.allUnits = allUnits;
        this.scopeUnitIds = scopeUnitIds == null ? List.of() : List.copyOf(scopeUnitIds);
    }

    public String getObjectType() {
        return objectType;
    }

    public String getObjectId() {
        return objectId;
    }

    public String getAction() {
        return action;
    }

    public String getActor() {
        return actor;
    }

    public Instant getFrom() {
        return from;
    }

    public Instant getTo() {
        return to;
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