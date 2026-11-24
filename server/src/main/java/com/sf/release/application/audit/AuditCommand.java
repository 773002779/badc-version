package com.sf.release.application.audit;

import com.sf.release.domain.audit.AuditAction;
import com.sf.release.domain.audit.AuditObjectType;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What a trigger point knows about one audit event (audit design section 3): the action, the object,
 * the one-line summary, the caller's reason and the <em>changed fields only</em>.
 *
 * <p><strong>No SQL, no actor, no timestamp.</strong> A trigger only describes the fact; the actor,
 * the trace id and {@code created_at} are the write service's job, because a caller must not be able
 * to attribute an action to somebody else and the time is the moment the row is written.
 *
 * <p><strong>before/after are field subsets.</strong> {@code before} is {@code null} for a creation
 * and {@code after} is {@code null} for a deletion; when both are null the action carries no state
 * change (a refresh, a logout-like event). Values are serialized by {@link AuditService} with the
 * application {@code ObjectMapper}.
 *
 * <p>The builder keeps the trigger points readable (a record with twelve positional arguments is
 * unreadable at the call site and easy to get wrong): the object type defaults to the action's
 * default, and the scope helpers set the two columns the read model filters on.
 */
public record AuditCommand(
    AuditAction action,
    AuditObjectType objectType,
    String objectId,
    String objectLabel,
    String summary,
    String reason,
    Map<String, Object> before,
    Map<String, Object> after,
    Long scopeUnitId,
    Long scopeSubsystemId) {

    public AuditCommand {
        before = immutableCopy(before);
        after = immutableCopy(after);
    }

    /** A builder for one command; the object type starts as {@link AuditAction#objectType()}. */
    public static Builder of(AuditAction action, String objectId, String objectLabel,
                             String summary) {
        return new Builder(action, objectId, objectLabel, summary);
    }

    private static Map<String, Object> immutableCopy(Map<String, Object> values) {
        if (values == null) {
            return null;
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }

    /** Mutable builder of one immutable {@link AuditCommand}. */
    public static final class Builder {

        private final AuditAction action;
        private final String objectId;
        private final String objectLabel;
        private final String summary;
        private AuditObjectType objectType;
        private String reason;
        private Map<String, Object> before;
        private Map<String, Object> after;
        private Long scopeUnitId;
        private Long scopeSubsystemId;

        private Builder(AuditAction action, String objectId, String objectLabel, String summary) {
            this.action = action;
            this.objectType = action == null ? null : action.objectType();
            this.objectId = objectId;
            this.objectLabel = objectLabel;
            this.summary = summary;
        }

        /** Overrides the action's default object type (resource/setting actions need this). */
        public Builder objectType(AuditObjectType type) {
            this.objectType = type;
            return this;
        }

        public Builder reason(String value) {
            this.reason = value;
            return this;
        }

        public Builder before(Map<String, Object> values) {
            this.before = values;
            return this;
        }

        public Builder after(Map<String, Object> values) {
            this.after = values;
            return this;
        }

        /** Unit-level scope: the row is readable by a caller whose data scope contains the unit. */
        public Builder scopeUnitId(Long unitId) {
            this.scopeUnitId = unitId;
            return this;
        }

        /** System-level scope: readable by a caller with a unit of that subsystem (or ALL). */
        public Builder scopeSubsystemId(Long subsystemId) {
            this.scopeSubsystemId = subsystemId;
            return this;
        }

        public AuditCommand build() {
            return new AuditCommand(action, objectType, objectId, objectLabel, summary, reason,
                before, after, scopeUnitId, scopeSubsystemId);
        }
    }
}