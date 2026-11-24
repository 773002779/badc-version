package com.sf.release.application.notification;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sf.release.common.security.Permissions;
import com.sf.release.domain.approval.ApprovalRole;
import com.sf.release.domain.task.MemberRole;
import com.sf.release.infra.repo.DeployUnitMapper;
import com.sf.release.infra.repo.NotificationMapper;
import com.sf.release.infra.repo.ReleaseItemMapper;
import com.sf.release.infra.repo.ReleaseOrderMapper;
import com.sf.release.infra.repo.ReleaseTaskMapper;
import com.sf.release.infra.repo.ReleaseTaskMemberMapper;
import com.sf.release.infra.repo.SubsystemMapper;
import com.sf.release.infra.repo.po.DeployUnitPO;
import com.sf.release.infra.repo.po.NotificationOrderRow;
import com.sf.release.infra.repo.po.ReleaseItemPO;
import com.sf.release.infra.repo.po.ReleaseOrderPO;
import com.sf.release.infra.repo.po.ReleaseTaskMemberPO;
import com.sf.release.infra.repo.po.ReleaseTaskPO;
import com.sf.release.infra.repo.po.SubsystemPO;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Resolves the recipients of each notification event from the persisted membership/role data
 * (notification design section 4).
 *
 * <p><strong>Task members (4.1).</strong> A release task's members by role, always joined with the
 * task creator; {@code OWNER} counts as {@code 研发} ({@code DEV}). The result is user-id ascending
 * so the tests (and the page) are stable.
 *
 * <p><strong>Approval (4.2).</strong> {@code APPROVAL_PENDING} maps the pending step's role to the
 * users holding that platform role. When nobody holds it, the system owner receives the message with
 * the design's note that the role was empty; a step never notifies both levels at once — the caller
 * only asks for the first unsigned step. {@code WAIVER_PENDING} goes to the users whose role mapping
 * grants {@code control:approve}, plus the system owner.
 *
 * <p><strong>Freeze impact (4.3, C22).</strong> The freeze audience is computed from the releases in
 * the production flow inside the freeze's scope: their {@code PRODUCT}/{@code DEV}/{@code TEST}/
 * {@code OPS} members, one row per person, with the release numbers and current stage in the body.
 * A role class missing on an order adds the system owner as the fallback, once per system, with the
 * system name in the body. The result is per-recipient, which is why the audience returns
 * {@link NotificationTarget}s with their own body.
 *
 * <p><strong>Lock timeout (4.4).</strong> The unit owner plus the creators of the orders with a live
 * instance on the unit; when that set is empty, the system owner.
 *
 * <p>The resolver only reads; it never writes and never sends. It runs inside the notification
 * transaction, which is why it takes ids and re-reads the authoritative rows there.
 */
@Component
public class NotificationRecipientResolver {

    private final ReleaseOrderMapper orderMapper;
    private final ReleaseItemMapper itemMapper;
    private final ReleaseTaskMapper taskMapper;
    private final ReleaseTaskMemberMapper memberMapper;
    private final DeployUnitMapper unitMapper;
    private final SubsystemMapper subsystemMapper;
    private final NotificationMapper notificationMapper;

    public NotificationRecipientResolver(ReleaseOrderMapper orderMapper,
                                         ReleaseItemMapper itemMapper,
                                         ReleaseTaskMapper taskMapper,
                                         ReleaseTaskMemberMapper memberMapper,
                                         DeployUnitMapper unitMapper,
                                         SubsystemMapper subsystemMapper,
                                         NotificationMapper notificationMapper) {
        this.orderMapper = orderMapper;
        this.itemMapper = itemMapper;
        this.taskMapper = taskMapper;
        this.memberMapper = memberMapper;
        this.unitMapper = unitMapper;
        this.subsystemMapper = subsystemMapper;
        this.notificationMapper = notificationMapper;
    }

    // ------------------------------------------------------------------ task members (4.1)

    /** The members of {@code roles} on the order's release task, plus the task creator. */
    public List<String> taskRecipients(long orderId, Set<MemberRole> roles) {
        TaskRef task = taskOfOrder(orderId);
        if (task == null) {
            return List.of();
        }
        Set<String> recipients = new TreeSet<>(memberIds(task.taskId(), roles));
        addIfPresent(recipients, task.creator());
        return List.copyOf(recipients);
    }

    // ------------------------------------------------------------------ approval (4.2)

    /**
     * The recipients of the first pending approval step of {@code role}.
     *
     * <p>When no user holds the role the system owner receives the same body plus the design's note;
     * an empty result means there is neither a role holder nor a system owner.
     */
    public List<NotificationTarget> approvalPendingTargets(long orderId, ApprovalRole role,
                                                           String baseBody) {
        List<String> users = notificationMapper.selectUserIdsByRole(role.name());
        Set<String> distinct = new TreeSet<>(users);
        if (!distinct.isEmpty()) {
            return distinct.stream().map(user -> new NotificationTarget(user, null)).toList();
        }
        String owner = systemOwnerOfOrder(orderId);
        if (owner == null) {
            return List.of();
        }
        return List.of(new NotificationTarget(owner, fallbackNote(baseBody)));
    }

    /** The users holding {@code control:approve} plus the system owner (4.2, waiver). */
    public List<String> waiverRecipients(long itemId) {
        Set<String> recipients = new TreeSet<>(
            notificationMapper.selectUserIdsByPermission(Permissions.CONTROL_APPROVE));
        Long subsystemId = subsystemIdOfItem(itemId);
        addIfPresent(recipients, systemOwner(subsystemId));
        return List.copyOf(recipients);
    }

    // ------------------------------------------------------------------ freeze impact (4.3)

    /**
     * One target per person affected by a freeze in {@code scopeSubsystemId} ({@code 0} = global),
     * with the release numbers and stages appended to the base body.
     */
    public List<NotificationTarget> freezeTargets(long scopeSubsystemId, String baseBody) {
        List<NotificationOrderRow> rows = notificationMapper.selectFreezeAudience(scopeSubsystemId);
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<Long, Map<MemberRole, List<String>>> membersByTask = membersOfTasks(rows);
        Map<String, LinkedHashMap<Long, NotificationOrderRow>> byRecipient = new TreeMap<>();
        Map<String, Set<String>> fallbackNotes = new TreeMap<>();
        for (NotificationOrderRow row : rows) {
            for (MemberRole role : EnumSet.of(MemberRole.PRODUCT, MemberRole.DEV,
                MemberRole.TEST, MemberRole.OPS)) {
                List<String> members = membersOf(membersByTask, row.getTaskId(), role);
                if (members.isEmpty()) {
                    String owner = systemOwner(row.getSubsystemId());
                    if (owner == null) {
                        continue;
                    }
                    addOrder(byRecipient, owner, row);
                    fallbackNotes.computeIfAbsent(owner, key -> new TreeSet<>())
                        .add("系统「" + blankTo(row.getSubsystemName(), "未知系统") + "」缺少"
                            + NotificationLabels.memberRole(role) + "角色成员，已转系统负责人。");
                } else {
                    for (String member : members) {
                        addOrder(byRecipient, member, row);
                    }
                }
            }
        }
        List<NotificationTarget> targets = new ArrayList<>(byRecipient.size());
        for (Map.Entry<String, LinkedHashMap<Long, NotificationOrderRow>> entry
                : byRecipient.entrySet()) {
            targets.add(new NotificationTarget(entry.getKey(),
                freezeBody(baseBody, fallbackNotes.get(entry.getKey()), entry.getValue().values())));
        }
        return targets;
    }

    // ------------------------------------------------------------------ rollback (3, ROLLBACK_DONE)

    /**
     * The rollback audience of design section 3: the task's {@code OPS}/{@code DEV} members, the
     * rollback requester and the unit owner.
     */
    public List<String> rollbackRecipients(long unitId, Long sourceOrderId, String requestedBy) {
        Set<String> recipients = new TreeSet<>();
        if (sourceOrderId != null && sourceOrderId > 0) {
            TaskRef task = taskOfOrder(sourceOrderId);
            if (task != null) {
                recipients.addAll(memberIds(task.taskId(),
                    EnumSet.of(MemberRole.DEV, MemberRole.OPS)));
            }
        }
        addIfPresent(recipients, requestedBy);
        DeployUnitPO unit = unitMapper.selectById(unitId);
        if (unit != null) {
            addIfPresent(recipients, unit.getOwnerId());
        }
        return List.copyOf(recipients);
    }

    // ------------------------------------------------------------------ lock timeout (4.4)

    /** The unit owner plus the creators of the unit's in-flight orders; system owner when empty. */
    public List<String> lockTimeoutRecipients(long unitId) {
        Set<String> recipients = new TreeSet<>();
        DeployUnitPO unit = unitMapper.selectById(unitId);
        if (unit != null) {
            addIfPresent(recipients, unit.getOwnerId());
        }
        recipients.addAll(notificationMapper.selectLiveCreatorIds(unitId));
        if (recipients.isEmpty() && unit != null) {
            addIfPresent(recipients, systemOwner(unit.getSubsystemId()));
        }
        return List.copyOf(recipients);
    }

    // ------------------------------------------------------------------ reads

    /** One system's owner id, or {@code null}. */
    public String systemOwner(Long subsystemId) {
        if (subsystemId == null || subsystemId <= 0) {
            return null;
        }
        SubsystemPO po = subsystemMapper.selectById(subsystemId);
        return po == null ? null : trimToNull(po.getOwnerId());
    }

    // ------------------------------------------------------------------ internals

    private TaskRef taskOfOrder(long orderId) {
        ReleaseOrderPO order = orderMapper.selectById(orderId);
        if (order == null || order.getItemId() == null) {
            return null;
        }
        ReleaseItemPO item = itemMapper.selectById(order.getItemId());
        if (item == null || item.getTaskId() == null) {
            return null;
        }
        ReleaseTaskPO task = taskMapper.selectById(item.getTaskId());
        if (task == null) {
            return null;
        }
        return new TaskRef(task.getId(), task.getCreator());
    }

    private Long subsystemIdOfItem(long itemId) {
        ReleaseItemPO item = itemMapper.selectById(itemId);
        if (item == null || item.getUnitId() == null) {
            return null;
        }
        DeployUnitPO unit = unitMapper.selectById(item.getUnitId());
        return unit == null ? null : unit.getSubsystemId();
    }

    private String systemOwnerOfOrder(long orderId) {
        return systemOwner(notificationMapper.selectSubsystemIdOfOrder(orderId));
    }

    private List<String> memberIds(long taskId, Set<MemberRole> roles) {
        if (roles == null || roles.isEmpty()) {
            return List.of();
        }
        Set<String> names = new LinkedHashSet<>();
        for (MemberRole role : roles) {
            names.add(role.name());
            if (role == MemberRole.DEV) {
                names.add(MemberRole.OWNER.name());
            }
        }
        List<ReleaseTaskMemberPO> members = memberMapper.selectList(
            Wrappers.<ReleaseTaskMemberPO>lambdaQuery()
                .eq(ReleaseTaskMemberPO::getTaskId, taskId)
                .in(ReleaseTaskMemberPO::getRole, names)
                .orderByAsc(ReleaseTaskMemberPO::getUserId));
        Set<String> ids = new TreeSet<>();
        for (ReleaseTaskMemberPO member : members) {
            addIfPresent(ids, member.getUserId());
        }
        return List.copyOf(ids);
    }

    /**
     * All members of the freeze's tasks in one query, grouped by task and role ({@code OWNER} is
     * folded into {@code DEV}).
     */
    private Map<Long, Map<MemberRole, List<String>>> membersOfTasks(List<NotificationOrderRow> rows) {
        List<Long> taskIds = rows.stream()
            .map(NotificationOrderRow::getTaskId)
            .filter(id -> id != null && id > 0)
            .distinct()
            .toList();
        Map<Long, Map<MemberRole, List<String>>> byTask = new LinkedHashMap<>();
        if (taskIds.isEmpty()) {
            return byTask;
        }
        for (ReleaseTaskMemberPO member : memberMapper.selectList(
            Wrappers.<ReleaseTaskMemberPO>lambdaQuery()
                .in(ReleaseTaskMemberPO::getTaskId, taskIds)
                .orderByAsc(ReleaseTaskMemberPO::getUserId))) {
            MemberRole role = roleOf(member.getRole());
            if (role == null) {
                continue;
            }
            byTask.computeIfAbsent(member.getTaskId(), key -> new LinkedHashMap<>())
                .computeIfAbsent(role, key -> new ArrayList<>())
                .add(member.getUserId());
        }
        return byTask;
    }

    private static List<String> membersOf(
            Map<Long, Map<MemberRole, List<String>>> byTask, Long taskId, MemberRole role) {
        if (taskId == null) {
            return List.of();
        }
        Map<MemberRole, List<String>> roles = byTask.get(taskId);
        if (roles == null) {
            return List.of();
        }
        List<String> members = new ArrayList<>(roles.getOrDefault(role, List.of()));
        if (role == MemberRole.DEV) {
            members.addAll(roles.getOrDefault(MemberRole.OWNER, List.of()));
        }
        return members.isEmpty() ? List.of() : List.copyOf(new TreeSet<>(members));
    }

    private static MemberRole roleOf(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return MemberRole.valueOf(raw);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static void addOrder(Map<String, LinkedHashMap<Long, NotificationOrderRow>> byRecipient,
                                 String userId, NotificationOrderRow row) {
        if (userId == null || userId.isBlank() || row.getOrderId() == null) {
            return;
        }
        byRecipient.computeIfAbsent(userId, key -> new LinkedHashMap<>())
            .putIfAbsent(row.getOrderId(), row);
    }

    /**
     * The per-recipient freeze body: the base sentence, the system-owner notes and the person's own
     * release list (at most ten rows plus a "等 N 单" tail, design 4.3).
     */
    private static String freezeBody(String baseBody, Set<String> notes,
                                     Iterable<NotificationOrderRow> orders) {
        StringBuilder body = new StringBuilder(baseBody == null ? "" : baseBody.trim());
        if (notes != null) {
            for (String note : notes) {
                body.append('\n').append(note);
            }
        }
        List<String> entries = new ArrayList<>();
        for (NotificationOrderRow order : orders) {
            entries.add(blankTo(order.getPublishNo(), "#" + order.getOrderId())
                + "（" + NotificationLabels.itemStatus(order.getItemStatus()) + "）");
        }
        if (!entries.isEmpty()) {
            body.append('\n').append("受影响发布单：");
            int shown = Math.min(entries.size(), 10);
            body.append(String.join("、", entries.subList(0, shown)));
            if (entries.size() > shown) {
                body.append(" 等 ").append(entries.size() - shown).append(" 单");
            }
        }
        return body.toString();
    }

    private static String fallbackNote(String baseBody) {
        String note = "（角色无人，已转系统负责人）";
        if (baseBody == null || baseBody.isBlank()) {
            return note;
        }
        return baseBody.trim() + note;
    }

    private static void addIfPresent(Set<String> target, String value) {
        if (value != null && !value.isBlank()) {
            target.add(value.trim());
        }
    }

    private static String blankTo(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** The order's release task facts the resolver needs. */
    private record TaskRef(long taskId, String creator) {
    }
}