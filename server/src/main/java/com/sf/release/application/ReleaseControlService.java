package com.sf.release.application;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sf.release.application.audit.AuditCommand;
import com.sf.release.application.audit.AuditService;
import com.sf.release.application.notification.NotificationAudiences;
import com.sf.release.application.notification.NotificationCommand;
import com.sf.release.application.notification.NotificationService;
import com.sf.release.common.BizError;
import com.sf.release.common.BizException;
import com.sf.release.common.security.Permissions;
import com.sf.release.common.security.Roles;
import com.sf.release.domain.audit.AuditAction;
import com.sf.release.domain.audit.AuditObjectType;
import com.sf.release.domain.notification.NotificationEventKey;
import com.sf.release.domain.pipeline.InstanceStatus;
import com.sf.release.domain.task.ItemEvent;
import com.sf.release.domain.task.ItemStateMachine;
import com.sf.release.domain.task.ItemStatus;
import com.sf.release.domain.task.MemberRole;
import com.sf.release.domain.task.TaskStatus;
import com.sf.release.infra.repo.DeployUnitMapper;
import com.sf.release.infra.repo.ReleaseItemMapper;
import com.sf.release.infra.repo.ReleaseOrderMapper;
import com.sf.release.infra.repo.ReleaseTaskMapper;
import com.sf.release.infra.repo.po.DeployUnitPO;
import com.sf.release.infra.repo.po.ReleaseItemPO;
import com.sf.release.infra.repo.po.ReleaseOrderPO;
import com.sf.release.infra.repo.po.ReleaseTaskPO;
import com.sf.release.infra.security.CurrentUser;
import com.sf.release.infra.security.ScopeGuard;
import com.sf.release.infra.security.UserContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The 停止 / 退出 / 关闭 / 归档 use cases (P5-B design §1, §4), the four stop-loss actions of a
 * release.
 *
 * <p><strong>One termination path.</strong> Every action ends the affected pipelines through
 * {@link PipelineTerminationService}, which CAS-terminates the instance (including {@code PAUSED}),
 * skips the unfinished work and publishes {@code InstanceFinishedEvent(TERMINATED)} inside the
 * caller's transaction; the engine cancellations and the 部署单元 lock releases run after the commit
 * as {@code pipelineTermination.afterCommit(...)}. The actions differ only in their object, their
 * permission, the states they accept and the cascade they add:
 *
 * <table>
 *   <caption>action → cascade</caption>
 *   <tr><th>action</th><th>object</th><th>item</th><th>extra cascade</th></tr>
 *   <tr><td>{@link #stop}</td><td>发布单 (production flow)</td><td>{@code TERMINATED}</td>
 *       <td>cancel the order's in-flight work; keep the batches already online</td></tr>
 *   <tr><td>{@link #exit}</td><td>发布单 (any non-terminal)</td><td>{@code TERMINATED}</td>
 *       <td>stop + void the pending approvals + invalidate the unfinished checks</td></tr>
 *   <tr><td>{@link #close}</td><td>变更项 (any non-terminal)</td><td>{@code CLOSED}</td>
 *       <td>terminate every active instance of the item (online and daily) + void approvals/checks</td></tr>
 *   <tr><td>{@link #archive}</td><td>发布任务</td><td>every unfinished item {@code CLOSED}</td>
 *       <td>the close cascade per item, terminate every in-flight instance of the task, release
 *       every lock; the task becomes read-only</td></tr>
 * </table>
 *
 * <p><strong>One business transaction, then best effort.</strong> The item/task status, the order
 * mirror, the instance terminations, the approval cancellation and the check invalidation commit
 * together, so a caller never observes a half cascade. Engine cancellation and lock release cannot
 * be rolled back, so they run after the commit and only WARN on failure (design §2). The audit row
 * and the notification are written through {@link AuditService}/{@link NotificationService}, both
 * REQUIRES_NEW and failure-swallowing, so they can never revert the business state (design §5).
 *
 * <p><strong>Idempotency.</strong> The four actions are not idempotent in the "repeat silently"
 * sense: a second call on an object that is already terminal answers {@code 40001} with
 * {@code currentStatus} (design §1). An unknown object is {@code 40000}, a caller without the
 * permission or the unit data scope is {@code 40009}.
 *
 * <p><strong>What is kept.</strong> Nothing is deleted: the instance/task history and every
 * {@code SUCCESS} batch stay as evidence (J8/J21). An archived task keeps its release records and
 * its rollback entry: this service never adds an archived-task guard to
 * {@link RollbackService}, because production rollback is how operations recover from a release
 * that is no longer editable (design §8).
 */
@Service
public class ReleaseControlService {

    private static final Logger log = LoggerFactory.getLogger(ReleaseControlService.class);

    /** {@code audit_log.reason} is 512 wide; the design caps the operator reason at 200. */
    static final int MAX_REASON_LENGTH = 200;

    /**
     * The states a production 停止 applies to: the release is somewhere in the production flow.
     * Every other non-terminal state is a 退出, never a 停止 (design §1).
     */
    private static final EnumSet<ItemStatus> STOPPABLE = EnumSet.of(
        ItemStatus.SUBMITTED, ItemStatus.PENDING_APPROVAL, ItemStatus.READY,
        ItemStatus.PROD_DEPLOYING);

    private final ReleaseOrderMapper orderMapper;
    private final ReleaseItemMapper itemMapper;
    private final ReleaseTaskMapper taskMapper;
    private final DeployUnitMapper unitMapper;
    private final ItemTransitions transitions;
    private final PipelineTerminationService termination;
    private final ApprovalService approvalService;
    private final GateService gateService;
    private final AuditService audit;
    private final NotificationService notifications;
    private final TransactionTemplate transactionTemplate;

    public ReleaseControlService(ReleaseOrderMapper orderMapper,
                                 ReleaseItemMapper itemMapper,
                                 ReleaseTaskMapper taskMapper,
                                 DeployUnitMapper unitMapper,
                                 ItemTransitions transitions,
                                 PipelineTerminationService termination,
                                 ApprovalService approvalService,
                                 GateService gateService,
                                 AuditService audit,
                                 NotificationService notifications,
                                 PlatformTransactionManager transactionManager) {
        this.orderMapper = Objects.requireNonNull(orderMapper, "orderMapper must not be null");
        this.itemMapper = Objects.requireNonNull(itemMapper, "itemMapper must not be null");
        this.taskMapper = Objects.requireNonNull(taskMapper, "taskMapper must not be null");
        this.unitMapper = Objects.requireNonNull(unitMapper, "unitMapper must not be null");
        this.transitions = Objects.requireNonNull(transitions, "transitions must not be null");
        this.termination = Objects.requireNonNull(termination, "termination must not be null");
        this.approvalService = Objects.requireNonNull(approvalService,
            "approvalService must not be null");
        this.gateService = Objects.requireNonNull(gateService, "gateService must not be null");
        this.audit = Objects.requireNonNull(audit, "audit must not be null");
        this.notifications = Objects.requireNonNull(notifications,
            "notifications must not be null");
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    // ------------------------------------------------------------------ stop

    /**
     * 停止发布: ends the production pipeline of {@code publishNo}, keeps the batches that already
     * went online, and moves the 变更项 to {@code TERMINATED}.
     *
     * @throws BizException 40000 unknown publish number or a blank/oversized reason; 40001 the item
     *     is not in the production flow (or a concurrent writer changed it); 40009 no
     *     {@code control:execute-prod} or no data scope on the unit
     */
    public ControlResult stop(String publishNo, String reason) {
        CurrentUser user = UserContext.current();
        requirePerm(user, Permissions.CONTROL_EXECUTE_PROD);
        ReleaseOrderPO order = requireOrder(requirePublishNo(publishNo));
        ReleaseItemPO item = requireItem(order);
        ScopeGuard.assertUnitInScope(unitIdOf(item));
        String why = requireReason(reason);
        requireStoppable(order, item);
        return endRelease(order, item, why, AuditAction.STOP,
            NotificationEventKey.RELEASE_STOPPED, false);
    }

    // ------------------------------------------------------------------ exit

    /**
     * 退出流水线: ends any non-terminal pipeline of {@code publishNo}, voids the signatures nobody
     * has given yet and invalidates the checks that have not produced a result.
     *
     * <p>The permission is {@code release:publish} <em>or</em> {@code control:execute-prod}
     * (design §4): a developer leaves their own release pipeline, and operations may end one that
     * is stuck. The controller therefore does not pin one permission; this method owns the OR.
     *
     * @throws BizException 40000 unknown publish number or a blank/oversized reason; 40001 the item
     *     is already terminal or {@code DONE}; 40009 neither permission nor the unit scope
     */
    public ControlResult exit(String publishNo, String reason) {
        CurrentUser user = UserContext.current();
        if (!user.hasPerm(Permissions.RELEASE_PUBLISH)
                && !user.hasPerm(Permissions.CONTROL_EXECUTE_PROD)) {
            throw new BizException(BizError.FORBIDDEN,
                "无权执行该操作：" + Permissions.RELEASE_PUBLISH + " 或 "
                    + Permissions.CONTROL_EXECUTE_PROD,
                Map.of("perm", Permissions.RELEASE_PUBLISH + "|"
                    + Permissions.CONTROL_EXECUTE_PROD));
        }
        ReleaseOrderPO order = requireOrder(requirePublishNo(publishNo));
        ReleaseItemPO item = requireItem(order);
        ScopeGuard.assertUnitInScope(unitIdOf(item));
        String why = requireReason(reason);
        requireExitable(order, item);
        return endRelease(order, item, why, AuditAction.EXIT,
            NotificationEventKey.RELEASE_EXITED, true);
    }

    /**
     * Shared stop/exit body: one transaction that moves the item to {@code TERMINATED}, mirrors it on
     * the order, terminates the order's in-flight instances and — for an exit — voids the pending
     * approvals and the unfinished checks; then the best-effort engine/lock work, the audit row and
     * the notification.
     */
    private ControlResult endRelease(ReleaseOrderPO order, ReleaseItemPO item, String reason,
                                     AuditAction action, NotificationEventKey event,
                                     boolean voidApprovalsAndChecks) {
        long orderId = order.getId();
        long itemId = item.getId();
        String publishNo = order.getPublishNo();
        String crid = item.getCrid();
        long unitId = unitIdOf(item);
        List<PipelineTerminationService.Terminated> terminated = transactionTemplate.execute(tx -> {
            applyTransition(itemId, ItemEvent.EXIT, Map.of("publishNo", publishNo,
                "itemId", itemId));
            syncOrderStatus(orderId, ItemStatus.TERMINATED.name());
            List<PipelineTerminationService.Terminated> ended =
                termination.terminateOrder(orderId, reason);
            if (voidApprovalsAndChecks) {
                cancelApprovals(orderId, reason);
                gateService.invalidateUnfinished(itemId, reason);
            }
            return ended;
        });
        List<PipelineTerminationService.Terminated> ended =
            terminated == null ? List.of() : terminated;
        termination.afterCommit(ended);

        String terminatedNo = terminatedPipelineNo(ended);
        log.info("release {}: publishNo={}, itemId={}, unitId={}, terminatedPipelineNo={},"
                + " terminatedInstances={}, by={}, reason={}", action, publishNo, itemId, unitId,
            terminatedNo, ended.size(), UserContext.current().userId(), reason);
        auditControl(action, null, publishNo, publishNo,
            action.label() + "：发布单 " + publishNo + "（变更项 " + (crid == null ? "" : crid) + "）",
            Map.of("itemStatus", item.getStatus()), afterOf(ItemStatus.TERMINATED, terminatedNo),
            reason, unitId, subsystemOfUnit(unitId));
        notifyReleased(event, publishNo, crid, reason, orderId, ended.size());
        return new ControlResult(ItemStatus.TERMINATED.name(), ItemStatus.TERMINATED.name(),
            terminatedNo);
    }

    // ------------------------------------------------------------------ close

    /**
     * 关闭变更项: moves any non-terminal 变更项 to {@code CLOSED}, ends its active instances (online
     * and daily), voids the pending signatures and invalidates the unfinished checks. A closed item
     * can never be published again: the state machine has no outgoing edge from {@code CLOSED}.
     *
     * @throws BizException 40000 unknown item or a blank/oversized reason; 40001 the item is
     *     {@code DONE} or already terminal; 40009 no {@code release:publish} or no unit scope
     */
    public ControlResult close(long itemId, String reason) {
        CurrentUser user = UserContext.current();
        requirePerm(user, Permissions.RELEASE_PUBLISH);
        ReleaseItemPO item = requireItemById(itemId);
        long unitId = unitIdOf(item);
        ScopeGuard.assertUnitInScope(unitId);
        String why = requireReason(reason);
        requireClosable(item);
        Long orderId = latestOrderId(itemId);
        String crid = item.getCrid();
        List<PipelineTerminationService.Terminated> terminated = transactionTemplate.execute(tx -> {
            applyTransition(itemId, ItemEvent.CLOSE, Map.of("itemId", itemId,
                "crid", crid == null ? "" : crid));
            if (orderId != null) {
                syncOrderStatus(orderId, ItemStatus.CLOSED.name());
            }
            List<PipelineTerminationService.Terminated> ended = termination.terminateItem(itemId,
                reason);
            if (orderId != null) {
                cancelApprovals(orderId, reason);
            }
            gateService.invalidateUnfinished(itemId, reason);
            return ended;
        });
        List<PipelineTerminationService.Terminated> ended =
            terminated == null ? List.of() : terminated;
        termination.afterCommit(ended);

        String terminatedNo = terminatedPipelineNo(ended);
        log.info("release CLOSE: itemId={}, unitId={}, terminatedPipelineNo={},"
                + " terminatedInstances={}, by={}, reason={}", itemId, unitId, terminatedNo,
            ended.size(), UserContext.current().userId(), reason);
        auditControl(AuditAction.CLOSE, AuditObjectType.ITEM, String.valueOf(itemId), crid,
            "关闭变更项：" + (crid == null ? itemId : crid),
            Map.of("itemStatus", item.getStatus()), afterOf(ItemStatus.CLOSED, terminatedNo),
            reason, unitId, subsystemOfUnit(unitId));
        notifyItemClosed(item.getTaskId(), itemId, crid, reason, ended.size());
        return new ControlResult(orderId == null ? null : ItemStatus.CLOSED.name(),
            ItemStatus.CLOSED.name(), terminatedNo);
    }

    // ------------------------------------------------------------------ archive

    /**
     * 退出任务/归档: turns the task read-only and cascades the close over every unfinished 变更项,
     * then terminates every in-flight instance of the task and releases every unit lock it holds.
     * The history — instances, tasks, release records and the rollback entry — is kept.
     *
     * <p>The authorization is the design's: {@code task:create} plus "the task creator or an
     * administrator" (§4), and the unit data scope of the items the cascade will close. The task
     * has no unit of its own, so the scope is checked on the distinct units of its items.
     *
     * @throws BizException 40000 unknown task or a blank/oversized reason; 40001 the task is
     *     already {@code ARCHIVED} (with {@code currentStatus}); 40009 not the creator/administrator
     *     or no data scope on an item's unit
     */
    public ArchiveResult archive(long taskId, String reason) {
        CurrentUser user = UserContext.current();
        requirePerm(user, Permissions.TASK_CREATE);
        ReleaseTaskPO task = requireTask(taskId);
        String why = requireReason(reason);
        List<ReleaseItemPO> items = itemsOf(taskId);
        requireArchivable(task, items, user);
        List<ReleaseItemPO> unfinished = items.stream()
            .filter(item -> statusOf(item).isBlocking())
            .toList();

        Archived archived = transactionTemplate.execute(tx -> archiveInTransaction(task, unfinished,
            reason));
        Archived result = Objects.requireNonNull(archived, "archive result");
        termination.afterCommit(result.terminated());
        termination.releaseTaskLocks(taskId);

        log.info("release ARCHIVE: taskId={}, subsystemId={}, closedItems={},"
                + " terminatedInstances={}, by={}, reason={}", taskId, task.getSubsystemId(),
            result.closedItems(), result.terminated().size(), user.userId(), reason);
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("status", TaskStatus.ARCHIVED.name());
        after.put("closedItems", result.closedItems());
        after.put("terminatedInstances", result.terminated().size());
        auditControl(AuditAction.ARCHIVE, AuditObjectType.TASK, String.valueOf(taskId),
            task.getName(),
            "归档任务：" + task.getName() + "（关闭 " + result.closedItems() + " 个变更项，终止 "
                + result.terminated().size() + " 个在途实例）",
            Map.of("status", TaskStatus.ACTIVE.name()), after, reason, null,
            task.getSubsystemId());
        notifyTaskArchived(task, result.closedItems(), result.terminated().size(), reason);
        return new ArchiveResult(TaskStatus.ARCHIVED.name(), result.closedItems(),
            result.terminated().size());
    }

    /**
     * The archive body: flips the task {@code ACTIVE → ARCHIVED} with a status CAS, applies the close
     * cascade to every unfinished item (the {@code DONE} and the already closed-out items are left
     * alone) and terminates every in-flight instance of the task.
     */
    private Archived archiveInTransaction(ReleaseTaskPO task, List<ReleaseItemPO> unfinished,
                                          String reason) {
        long taskId = task.getId();
        Instant now = Instant.now();
        int rows = taskMapper.update(null, Wrappers.<ReleaseTaskPO>lambdaUpdate()
            .eq(ReleaseTaskPO::getId, taskId)
            .eq(ReleaseTaskPO::getStatus, TaskStatus.ACTIVE.name())
            .set(ReleaseTaskPO::getStatus, TaskStatus.ARCHIVED.name())
            .set(ReleaseTaskPO::getUpdatedAt, now));
        if (rows != 1) {
            ReleaseTaskPO current = taskMapper.selectById(taskId);
            throw new BizException(BizError.STATE_NOT_ALLOWED,
                "任务状态已变化，归档未生效，请刷新后重试",
                Map.of("taskId", taskId, "currentStatus", current == null
                    ? "UNKNOWN" : String.valueOf(current.getStatus())));
        }
        int closed = 0;
        for (ReleaseItemPO item : unfinished) {
            ItemTransitions.Outcome outcome = transitions.apply(item.getId(), ItemEvent.CLOSE);
            if (outcome != ItemTransitions.Outcome.APPLIED) {
                // A concurrent writer moved the item in the meantime (a publish that finished, a
                // manual close). The archive is idempotent about it: the item is not blocking any
                // more, which is all the cascade promised.
                log.info("archived task {} left item {} as it is: {}", taskId, item.getId(),
                    outcome);
                continue;
            }
            closed++;
            Long orderId = latestOrderId(item.getId());
            if (orderId != null) {
                syncOrderStatus(orderId, ItemStatus.CLOSED.name());
                cancelApprovals(orderId, reason);
            }
            gateService.invalidateUnfinished(item.getId(), reason);
        }
        List<PipelineTerminationService.Terminated> terminated = termination.terminateTask(taskId,
            reason);
        return new Archived(closed, terminated);
    }

    // ------------------------------------------------------------------ validation

    private static void requirePerm(CurrentUser user, String perm) {
        if (!user.hasPerm(perm)) {
            throw new BizException(BizError.FORBIDDEN, "无权执行该操作：" + perm,
                Map.of("perm", perm));
        }
    }

    private static String requirePublishNo(String raw) {
        String no = trimToNull(raw);
        if (no == null) {
            throw new BizException(BizError.PARAM_INVALID, "发布单号不能为空",
                Map.of("publishNo", "不能为空"));
        }
        return no;
    }

    private static String requireReason(String raw) {
        String reason = trimToNull(raw);
        if (reason == null) {
            throw new BizException(BizError.PARAM_INVALID, "原因不能为空",
                Map.of("reason", "不能为空"));
        }
        if (reason.length() > MAX_REASON_LENGTH) {
            throw new BizException(BizError.PARAM_INVALID,
                "原因长度不能超过 " + MAX_REASON_LENGTH, Map.of("reason", "长度超限"));
        }
        return reason;
    }

    private ReleaseOrderPO requireOrder(String publishNo) {
        ReleaseOrderPO order = orderMapper.selectOne(Wrappers.<ReleaseOrderPO>lambdaQuery()
            .eq(ReleaseOrderPO::getPublishNo, publishNo));
        if (order == null) {
            throw new BizException(BizError.PARAM_INVALID, "发布单不存在：" + publishNo,
                Map.of("publishNo", publishNo));
        }
        return order;
    }

    private ReleaseItemPO requireItem(ReleaseOrderPO order) {
        ReleaseItemPO item = order.getItemId() == null ? null
            : itemMapper.selectById(order.getItemId());
        if (item == null) {
            throw new BizException(BizError.PARAM_INVALID, "变更项不存在",
                Map.of("publishNo", order.getPublishNo(), "itemId", order.getItemId()));
        }
        return item;
    }

    private ReleaseItemPO requireItemById(long itemId) {
        ReleaseItemPO item = itemMapper.selectById(itemId);
        if (item == null) {
            throw new BizException(BizError.PARAM_INVALID, "变更项不存在", Map.of("itemId", itemId));
        }
        return item;
    }

    private ReleaseTaskPO requireTask(long taskId) {
        ReleaseTaskPO task = taskMapper.selectById(taskId);
        if (task == null) {
            throw new BizException(BizError.PARAM_INVALID, "发布任务不存在",
                Map.of("taskId", taskId));
        }
        return task;
    }

    private void requireStoppable(ReleaseOrderPO order, ReleaseItemPO item) {
        ItemStatus status = statusOf(item);
        if (!STOPPABLE.contains(status)) {
            throw new BizException(BizError.STATE_NOT_ALLOWED,
                "只有生产流程（SUBMITTED/PENDING_APPROVAL/READY/PROD_DEPLOYING）中的发布单可以停止，"
                    + "当前是 " + status.name() + "；其他状态请使用「退出流水线」",
                Map.of("publishNo", order.getPublishNo(), "itemId", item.getId(),
                    "currentStatus", status.name()));
        }
    }

    private void requireExitable(ReleaseOrderPO order, ReleaseItemPO item) {
        ItemStatus status = statusOf(item);
        if (!ItemStateMachine.allowedEvents(status).contains(ItemEvent.EXIT)) {
            throw new BizException(BizError.STATE_NOT_ALLOWED,
                exitRefusal(status),
                Map.of("publishNo", order.getPublishNo(), "itemId", item.getId(),
                    "currentStatus", status.name()));
        }
    }

    private void requireClosable(ReleaseItemPO item) {
        ItemStatus status = statusOf(item);
        if (!ItemStateMachine.allowedEvents(status).contains(ItemEvent.CLOSE)) {
            throw new BizException(BizError.STATE_NOT_ALLOWED,
                closeRefusal(status),
                Map.of("itemId", item.getId(), "currentStatus", status.name()));
        }
    }

    /**
     * The archive authorization (design §4): a task is archived by its creator or an administrator,
     * and the cascade must not touch a unit outside the caller's data scope. The task has no unit of
     * its own, so the scope is checked on the distinct units of the items the cascade may close. An
     * item with a missing unit (a corrupt row) is ignored instead of blocking the archive; the
     * cascade skips nothing else.
     */
    private void requireArchivable(ReleaseTaskPO task, List<ReleaseItemPO> items,
                                   CurrentUser user) {
        if (TaskStatus.ARCHIVED.name().equals(task.getStatus())) {
            throw new BizException(BizError.STATE_NOT_ALLOWED, "任务已归档，不能重复归档",
                Map.of("taskId", task.getId(), "currentStatus", TaskStatus.ARCHIVED.name()));
        }
        boolean admin = user.hasRole(Roles.ADMIN);
        boolean creator = user.userId() != null && user.userId().equals(task.getCreator());
        if (!admin && !creator) {
            throw new BizException(BizError.FORBIDDEN, "只有任务创建人或管理员可以归档任务",
                Map.of("taskId", task.getId(), "creator", String.valueOf(task.getCreator())));
        }
        for (Long unitId : distinctUnits(items)) {
            if (unitId != null && unitId > 0) {
                ScopeGuard.assertUnitInScope(unitId);
            }
        }
    }

    private static List<Long> distinctUnits(List<ReleaseItemPO> items) {
        List<Long> units = new ArrayList<>(2);
        for (ReleaseItemPO item : items) {
            Long unitId = item.getUnitId();
            if (unitId != null && unitId > 0 && !units.contains(unitId)) {
                units.add(unitId);
            }
        }
        return units;
    }

    private List<ReleaseItemPO> itemsOf(long taskId) {
        return itemMapper.selectList(Wrappers.<ReleaseItemPO>lambdaQuery()
            .eq(ReleaseItemPO::getTaskId, taskId)
            .orderByAsc(ReleaseItemPO::getId));
    }

    // ------------------------------------------------------------------ cascade helpers

    /**
     * Applies one main-state event with the {@code row_version} CAS every item transition uses. A
     * concurrent writer turns the action into a 40001 naming the status the caller should refresh
     * from, so nothing is half applied.
     */
    private void applyTransition(long itemId, ItemEvent event, Map<String, Object> payload) {
        ItemTransitions.Outcome outcome = transitions.apply(itemId, event);
        if (outcome == ItemTransitions.Outcome.APPLIED) {
            return;
        }
        ReleaseItemPO current = itemMapper.selectById(itemId);
        Map<String, Object> values = new LinkedHashMap<>(payload);
        values.put("event", event.name());
        values.put("outcome", outcome.name());
        values.put("currentStatus", current == null ? "UNKNOWN" : statusOf(current).name());
        throw new BizException(BizError.STATE_NOT_ALLOWED,
            "变更项状态已变化，操作未生效，请刷新后重试", values);
    }

    /**
     * Mirrors the item status onto the 发布单 with the {@code row_version} CAS every order status
     * change uses (submit/approve/execute/rollback all mirror it, and the release-records page reads
     * that column). A lost CAS aborts the action: a release that ended on the item but still reads
     * {@code EXECUTING} on its order is a lie the page would show.
     */
    private void syncOrderStatus(long orderId, String status) {
        ReleaseOrderPO current = orderMapper.selectByIdForUpdate(orderId);
        if (current == null) {
            throw new IllegalStateException(
                "release_order disappeared during a control action: " + orderId);
        }
        if (status.equals(current.getStatus())) {
            return;
        }
        int version = current.getRowVersion() == null ? 0 : current.getRowVersion();
        int rows = orderMapper.update(null, Wrappers.<ReleaseOrderPO>lambdaUpdate()
            .eq(ReleaseOrderPO::getId, orderId)
            .eq(ReleaseOrderPO::getRowVersion, version)
            .set(ReleaseOrderPO::getStatus, status)
            .set(ReleaseOrderPO::getRowVersion, version + 1));
        if (rows != 1) {
            throw new BizException(BizError.STATE_NOT_ALLOWED,
                "发布单状态已变化，操作未生效，请刷新后重试",
                Map.of("publishNo", String.valueOf(current.getPublishNo()),
                    "orderId", orderId));
        }
    }

    /**
     * Voids the pending steps of the order's newest admission snapshot (design §3). A logical
     * failure (an order that was never submitted) is a no-op; a genuine storage failure aborts the
     * action, because a release that is terminated while its chain still waits for signatures would
     * keep notifying approvers about a release that no longer exists.
     */
    private void cancelApprovals(long orderId, String reason) {
        int cancelled = approvalService.cancelPendingOfLatestSnapshot(orderId, reason);
        if (cancelled > 0) {
            log.info("pending approvals voided by a control action: orderId={}, steps={},"
                + " reason={}", orderId, cancelled, reason);
        }
    }

    private Long latestOrderId(long itemId) {
        ReleaseOrderPO order = orderMapper.selectOne(Wrappers.<ReleaseOrderPO>lambdaQuery()
            .eq(ReleaseOrderPO::getItemId, itemId)
            .orderByDesc(ReleaseOrderPO::getId)
            .last("LIMIT 1"));
        return order == null ? null : order.getId();
    }

    private Long subsystemOfUnit(long unitId) {
        if (unitId <= 0) {
            return null;
        }
        DeployUnitPO unit = unitMapper.selectById(unitId);
        return unit == null ? null : unit.getSubsystemId();
    }

    private static long unitIdOf(ReleaseItemPO item) {
        return item.getUnitId() == null ? 0L : item.getUnitId();
    }

    private static ItemStatus statusOf(ReleaseItemPO item) {
        return item.toDomain().status();
    }

    private static String terminatedPipelineNo(
            List<PipelineTerminationService.Terminated> terminated) {
        return terminated.isEmpty() ? null : terminated.get(0).pipelineNo();
    }

    private static Map<String, Object> afterOf(ItemStatus status, String terminatedPipelineNo) {
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("itemStatus", status.name());
        after.put("instanceStatus", terminatedPipelineNo == null
            ? null : InstanceStatus.TERMINATED.name());
        after.put("terminatedPipelineNo", terminatedPipelineNo);
        return after;
    }

    private static String exitRefusal(ItemStatus status) {
        if (status == ItemStatus.DONE) {
            return "变更项已生产完成（DONE），不能退出；如需回退请使用生产回滚";
        }
        if (status.isTerminal()) {
            return "变更项已是终态（" + status.name() + "），不能重复退出";
        }
        return "变更项当前状态不允许退出：" + status.name();
    }

    private static String closeRefusal(ItemStatus status) {
        if (status == ItemStatus.DONE) {
            return "变更项已生产完成（DONE），不能关闭；如需回退请使用生产回滚";
        }
        if (status.isTerminal()) {
            return "变更项已是终态（" + status.name() + "），不能重复关闭";
        }
        return "变更项当前状态不允许关闭：" + status.name();
    }

    // ------------------------------------------------------------------ audit and notification

    private void auditControl(AuditAction action, AuditObjectType objectType, String objectId,
                              String objectLabel, String summary, Map<String, Object> before,
                              Map<String, Object> after, String reason, Long unitId,
                              Long subsystemId) {
        AuditCommand.Builder builder = AuditCommand.of(action, objectId, objectLabel, summary)
            .reason(reason);
        if (objectType != null) {
            builder.objectType(objectType);
        }
        if (before != null) {
            builder.before(before);
        }
        if (after != null) {
            builder.after(after);
        }
        if (unitId != null && unitId > 0) {
            builder.scopeUnitId(unitId);
        }
        if (subsystemId != null && subsystemId > 0) {
            builder.scopeSubsystemId(subsystemId);
        }
        audit.record(builder.build());
    }

    /**
     * {@code RELEASE_STOPPED} / {@code RELEASE_EXITED}: the task's OPS/DEV members plus its creator
     * (design §5). The body names the object, the reason and — for a stop — that the batches already
     * online are kept.
     */
    private void notifyReleased(NotificationEventKey event, String publishNo, String crid,
                                String reason, long orderId, int terminatedInstances) {
        String title = event == NotificationEventKey.RELEASE_STOPPED
            ? "发布单已停止：" + publishNo : "发布单已退出：" + publishNo;
        StringBuilder body = new StringBuilder("发布单 ").append(publishNo);
        if (crid != null) {
            body.append("（变更项 ").append(crid).append("）");
        }
        body.append(event == NotificationEventKey.RELEASE_STOPPED ? " 已停止发布。" : " 已退出流水线。")
            .append("原因：").append(reason).append('。');
        if (event == NotificationEventKey.RELEASE_STOPPED) {
            body.append("已上线批次保留，未执行批次已取消；如需回退请使用生产回滚。");
        } else {
            body.append("在途任务已取消，未完成检查已失效，待签审批已作废。");
        }
        if (terminatedInstances > 0) {
            body.append("共终止 ").append(terminatedInstances).append(" 个流水线实例。");
        }
        notifications.notify(new NotificationCommand(event, publishNo, publishNo, title,
            body.toString(), publishNo,
            NotificationAudiences.taskMembers(orderId, EnumSet.of(MemberRole.OPS,
                MemberRole.DEV))));
    }

    /** {@code ITEM_CLOSED}: the task's DEV/TEST/OPS members plus its creator (design §5). */
    private void notifyItemClosed(Long taskId, long itemId, String crid, String reason,
                                   int terminatedInstances) {
        if (taskId == null) {
            return;
        }
        StringBuilder body = new StringBuilder("变更项 ")
            .append(crid == null ? String.valueOf(itemId) : crid).append(" 已关闭。原因：")
            .append(reason).append("。该变更项后续不可再发布，活动实例已终止，待签审批与未完成检查已作废，")
            .append("发布记录保留。");
        if (terminatedInstances > 0) {
            body.append("共终止 ").append(terminatedInstances).append(" 个流水线实例。");
        }
        notifications.notify(new NotificationCommand(NotificationEventKey.ITEM_CLOSED,
            String.valueOf(itemId), crid,
            "变更项已关闭：" + (crid == null ? itemId : crid), body.toString(),
            String.valueOf(itemId),
            NotificationAudiences.taskMembersOfTask(taskId,
                EnumSet.of(MemberRole.DEV, MemberRole.TEST, MemberRole.OPS))));
    }

    /** {@code TASK_ARCHIVED}: every task member plus the creator, with the system-owner fallback. */
    private void notifyTaskArchived(ReleaseTaskPO task, int closedItems,
                                    int terminatedInstances, String reason) {
        StringBuilder body = new StringBuilder("任务「").append(task.getName())
            .append("」已归档为只读。原因：").append(reason).append('。')
            .append("已关闭 ").append(closedItems).append(" 个未完成变更项，终止 ")
            .append(terminatedInstances).append(" 个在途实例；发布记录与回滚入口保留。");
        notifications.notify(new NotificationCommand(NotificationEventKey.TASK_ARCHIVED,
            String.valueOf(task.getId()), task.getName(),
            "任务已归档：" + task.getName(), body.toString(), String.valueOf(task.getId()),
            NotificationAudiences.taskArchived(task.getId())));
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    // ------------------------------------------------------------------ response records

    /**
     * What stop/exit/close answer (design §4): the object's latest status, the item's status and the
     * pipeline number of the instance this call terminated ({@code null} when none was active).
     *
     * <p>{@code status} is the mirror of the item status on the 发布单 when the item has one, i.e.
     * {@code TERMINATED}/{@code CLOSED}; it is {@code null} for a 变更项 that was never published
     * (its close has no order to mirror on).
     */
    public record ControlResult(String status, String itemStatus, String terminatedPipelineNo) {
    }

    /** What archive answers (design §4): the task status and the cascade counts. */
    public record ArchiveResult(String taskStatus, int closedItems, int terminatedInstances) {
    }

    /** Internal carrier of the committed archive cascade. */
    private record Archived(int closedItems,
                            List<PipelineTerminationService.Terminated> terminated) {
    }
}