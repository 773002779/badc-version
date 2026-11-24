package com.sf.release.application.notification;

import com.sf.release.domain.approval.ApprovalRole;
import com.sf.release.domain.task.MemberRole;

import java.util.EnumSet;
import java.util.Set;

/**
 * The audience factories the trigger points use (design section 3/4). Each one captures only the
 * ids the event supplies; {@link NotificationRecipientResolver} reads the authoritative rows inside
 * the notification transaction.
 */
public final class NotificationAudiences {

    private NotificationAudiences() {
    }

    /** Task members of {@code roles} plus the task creator (4.1); needs the order to reach its task. */
    public static NotificationAudience taskMembers(long orderId, Set<MemberRole> roles) {
        Set<MemberRole> wanted = roles == null || roles.isEmpty()
            ? Set.of() : EnumSet.copyOf(roles);
        return (resolver, baseBody) -> resolver.taskRecipients(orderId, wanted)
            .stream().map(user -> new NotificationTarget(user, null)).toList();
    }

    /** The first pending step's role holders, with the system-owner fallback (4.2). */
    public static NotificationAudience approvalPending(long orderId, ApprovalRole role) {
        return (resolver, baseBody) -> resolver.approvalPendingTargets(orderId, role, baseBody);
    }

    /** The users granting {@code control:approve} plus the system owner (4.2, waiver). */
    public static NotificationAudience waiverApprovers(long itemId) {
        return (resolver, baseBody) -> resolver.waiverRecipients(itemId)
            .stream().map(user -> new NotificationTarget(user, null)).toList();
    }

    /** The frozen scope's in-flight releases, aggregated per person (4.3); {@code 0} = global. */
    public static NotificationAudience freeze(long scopeSubsystemId) {
        long scope = Math.max(scopeSubsystemId, 0L);
        return (resolver, baseBody) -> resolver.freezeTargets(scope, baseBody);
    }

    /** The rollback audience of ROLLBACK_DONE (section 3). */
    public static NotificationAudience rollback(long unitId, Long sourceOrderId, String requestedBy) {
        return (resolver, baseBody) -> resolver
            .rollbackRecipients(unitId, sourceOrderId, requestedBy).stream()
            .map(user -> new NotificationTarget(user, null)).toList();
    }

    /** The unit owner plus the in-flight creators, with the system-owner fallback (4.4). */
    public static NotificationAudience lockTimeout(long unitId) {
        return (resolver, baseBody) -> resolver.lockTimeoutRecipients(unitId).stream()
            .map(user -> new NotificationTarget(user, null)).toList();
    }
}