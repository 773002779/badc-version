package com.sf.release.application;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sf.release.application.notification.NotificationAudiences;
import com.sf.release.application.notification.NotificationCommand;
import com.sf.release.application.notification.NotificationService;
import com.sf.release.common.BizError;
import com.sf.release.common.BizException;
import com.sf.release.common.security.Permissions;
import com.sf.release.common.security.Roles;
import com.sf.release.domain.approval.ApprovalChain;
import com.sf.release.domain.approval.ApprovalRole;
import com.sf.release.domain.approval.ApprovalStep;
import com.sf.release.domain.notification.NotificationEventKey;
import com.sf.release.domain.task.ItemEvent;
import com.sf.release.domain.task.ItemStatus;
import com.sf.release.domain.task.MemberRole;
import com.sf.release.infra.repo.AdmissionSnapshotMapper;
import com.sf.release.infra.repo.ReleaseItemMapper;
import com.sf.release.infra.repo.ReleaseOrderMapper;
import com.sf.release.infra.repo.po.AdmissionSnapshotPO;
import com.sf.release.infra.repo.po.ReleaseItemPO;
import com.sf.release.infra.repo.po.ReleaseOrderPO;
import com.sf.release.infra.security.CurrentUser;
import com.sf.release.infra.security.ScopeGuard;
import com.sf.release.infra.security.UserContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 审批通过 / 驳回 of one 发布单 (tech design 4.4, 4.8): the use cases behind
 * {@code POST /releases/{publishNo}/approve} and {@code .../reject}.
 *
 * <p><strong>Who may sign.</strong> Three checks, all in the service layer so a non-HTTP caller gets
 * them too:
 * <ol>
 *   <li>{@code control:approve} (40009). A role alone is not a permission and a permission alone is
 *       not a signature;</li>
 *   <li>the <em>current step's role</em> (40009 naming it). A platform {@code ADMIN} is not a
 *       部门经理/运维组长: without the step's role it cannot sign it (design 4.4: a step names a
 *       role, and the audit must not contain a signature nobody's role asked for);</li>
 *   <li>the caller is not the submitter (40008, design 4.4: 禁止自审，不自动转交). The submitter is
 *       the {@code admission_snapshot.submitted_by} of the generation being signed; a resubmission
 *       writes a new one, so the rule follows the current generation.</li>
 * </ol>
 *
 * <p><strong>The chain moves once.</strong> The lowest pending step is the only one decidable
 * ({@link ApprovalService#decide} re-checks that under the order lock). Approving the last step
 * applies {@code APPROVE_ALL} to the 变更项 ({@code PENDING_APPROVAL → READY}); rejecting applies
 * {@code REJECT} ({@code → REJECTED}) and leaves the later steps {@code CANCELED}. The order status
 * mirrors the item, because the item state machine is the authority.
 *
 * <p><strong>Wrong state is 40001, not a silent no-op.</strong> An item that is already
 * {@code READY}, {@code REJECTED} or back in the gate phase cannot be "approved"; the refusal names
 * the state so the page can refresh instead of retrying.
 */
@Service
public class ReleaseApprovalService {

    private static final Logger log = LoggerFactory.getLogger(ReleaseApprovalService.class);

    private final ReleaseOrderMapper orderMapper;
    private final ReleaseItemMapper itemMapper;
    private final AdmissionSnapshotMapper snapshotMapper;
    private final ApprovalService approvalService;
    private final ItemTransitions transitions;
    private final SubmitProdService submitProdService;
    private final NotificationService notifications;

    public ReleaseApprovalService(ReleaseOrderMapper orderMapper,
                                  ReleaseItemMapper itemMapper,
                                  AdmissionSnapshotMapper snapshotMapper,
                                  ApprovalService approvalService,
                                  ItemTransitions transitions,
                                  SubmitProdService submitProdService,
                                  NotificationService notifications) {
        this.orderMapper = orderMapper;
        this.itemMapper = itemMapper;
        this.snapshotMapper = snapshotMapper;
        this.approvalService = approvalService;
        this.transitions = transitions;
        this.submitProdService = submitProdService;
        this.notifications = notifications;
    }

    /**
     * Approves the lowest pending step of the latest snapshot's chain.
     *
     * @param comment optional approval note (stored on the step)
     * @throws BizException 40000 unknown publish number or an oversized note; 40001 the item is not
     *                      waiting for approval, or the chain has no pending step (already decided);
     *                      40008 the caller submitted this release; 40009 no {@code control:approve},
     *                      no scope on the unit, or the caller lacks the step's role
     */
    @Transactional
    public AdmissionView approve(String publishNo, String comment) {
        return decide(publishNo, true, comment);
    }

    /**
     * Rejects the lowest pending step of the latest snapshot's chain and cancels the rest.
     *
     * @param reason the mandatory rejection reason, stored as the step's comment
     * @throws BizException as {@link #approve}, plus 40000 when the reason is missing
     */
    @Transactional
    public AdmissionView reject(String publishNo, String reason) {
        return decide(publishNo, false, reason);
    }

    private AdmissionView decide(String publishNo, boolean approved, String note) {
        CurrentUser user = UserContext.current();
        requirePerm(user, Permissions.CONTROL_APPROVE);
        String no = requirePublishNo(publishNo);
        ReleaseOrderPO order = requireOrder(no);
        ReleaseItemPO item = requireItem(order);
        ScopeGuard.assertUnitInScope(item.getUnitId() == null ? 0L : item.getUnitId());
        ItemStatus status = AdmissionService.statusOf(item);
        if (status != ItemStatus.PENDING_APPROVAL) {
            throw wrongItemState(no, item, status);
        }
        AdmissionSnapshotPO snapshot = snapshotMapper.selectLatestByOrder(order.getId());
        if (snapshot == null) {
            throw new BizException(BizError.STATE_NOT_ALLOWED,
                "发布单尚未提交生产，没有审批链", Map.of("publishNo", no));
        }
        List<ApprovalStep> chain = approvalService.steps(snapshot.getId());
        ApprovalStep step = ApprovalChain.of(chain).nextPending().orElseThrow(() ->
            new BizException(BizError.STATE_NOT_ALLOWED,
                "审批链已无待签步骤（已全部通过或已驳回），无需再次操作",
                Map.of("publishNo", no, "snapshotId", snapshot.getId())));
        String submittedBy = snapshot.getSubmittedBy();
        if (submittedBy != null && !submittedBy.isBlank()
                && submittedBy.equals(user.userId())) {
            throw new BizException(BizError.SELF_APPROVAL_FORBIDDEN,
                "提交人不能审批自己提交的发布单，请由其他审批人操作",
                payload(no, step, roleNameOf(step.role()), Map.of("submittedBy", submittedBy,
                    "actor", user.userId())));
        }
        String requiredRole = roleNameOf(step.role());
        if (!user.hasRole(requiredRole)) {
            throw new BizException(BizError.FORBIDDEN,
                "第 " + step.stepNo() + " 步需要「" + step.role().label() + "」角色审批，当前账号没有该角色",
                payload(no, step, requiredRole, Map.of("requiredRole", requiredRole,
                    "actor", user.userId())));
        }
        ApprovalService.DecideResult result = approvalService.decide(snapshot.getId(),
            step.stepNo(), approved, user.userId(), note);
        if (result.approved()) {
            if (result.complete()) {
                applyTransition(item.getId(), ItemEvent.APPROVE_ALL, no);
                syncOrderStatus(order.getId(), ItemStatus.READY.name());
            }
        } else {
            applyTransition(item.getId(), ItemEvent.REJECT, no);
            syncOrderStatus(order.getId(), ItemStatus.REJECTED.name());
        }
        log.info("release approval decided: publishNo={}, snapshotId={}, stepNo={}, role={},"
                + " approved={}, by={}, complete={}, rejected={}", no, snapshot.getId(),
            step.stepNo(), requiredRole, approved, user.userId(), result.complete(),
            result.rejected());
        notifyDecided(no, order, snapshot.getId(), step, approved, user.userId(), note, result);
        // The view is read back inside the same transaction: it shows the chain and the statuses
        // this decision just produced, plus the pre-execution review of the current state.
        return submitProdService.admission(no);
    }

    /**
     * The approval notifications of design section 3: {@code APPROVAL_DECIDED} to the task members
     * and the creator, then — when an approval left a later step unsigned — {@code APPROVAL_PENDING}
     * for that step's role, so the next signer learns it is their turn (the first step was announced
     * when the chain was built).
     */
    private void notifyDecided(String publishNo, ReleaseOrderPO order, long snapshotId,
                               ApprovalStep step, boolean approved, String actor, String note,
                               ApprovalService.DecideResult result) {
        if (notifications == null) {
            return;
        }
        String decision = approved ? "通过" : "驳回";
        StringBuilder body = new StringBuilder("发布单 ").append(publishNo)
            .append(" 第 ").append(step.stepNo()).append(" 步（").append(step.role().label())
            .append("）已").append(decision).append("，审批人：").append(actor);
        String comment = note == null ? null : note.trim();
        if (comment != null && !comment.isEmpty()) {
            body.append("；意见：").append(comment);
        }
        body.append('。');
        notifications.notify(new NotificationCommand(
            NotificationEventKey.APPROVAL_DECIDED, publishNo, publishNo,
            NotificationEventKey.APPROVAL_DECIDED.label() + "：" + decision, body.toString(),
            snapshotId + ":" + step.stepNo() + ":DECIDED",
            NotificationAudiences.taskMembers(order.getId(),
                EnumSet.allOf(MemberRole.class))));
        if (!approved || result.complete()) {
            return;
        }
        ApprovalStepView next = result.steps().stream()
            .filter(candidate -> "PENDING".equals(candidate.status()))
            .findFirst().orElse(null);
        if (next == null) {
            return;
        }
        ApprovalRole role = ApprovalRole.valueOf(next.role());
        notifications.notify(new NotificationCommand(
            NotificationEventKey.APPROVAL_PENDING, publishNo, publishNo,
            NotificationEventKey.APPROVAL_PENDING.label(),
            "发布单 " + publishNo + " 第 " + next.stepNo() + " 步需要「" + role.label() + "」审批。",
            snapshotId + ":" + next.stepNo(),
            NotificationAudiences.approvalPending(order.getId(), role)));
    }

    private static BizException wrongItemState(String publishNo, ReleaseItemPO item,
                                               ItemStatus status) {
        Map<String, Object> payload = Map.of("publishNo", publishNo, "itemId", item.getId(),
            "status", status.name());
        if (status == ItemStatus.READY) {
            return new BizException(BizError.STATE_NOT_ALLOWED,
                "审批链已全部通过（变更项 READY），可以直接执行生产，无需再次审批", payload);
        }
        return new BizException(BizError.STATE_NOT_ALLOWED,
            "只有待审批（PENDING_APPROVAL）的变更项可以审批，当前是 " + status, payload);
    }

    /** Applies one main-state event, turning "the state changed under us" into a 40001. */
    private void applyTransition(long itemId, ItemEvent event, String publishNo) {
        ItemTransitions.Outcome outcome = transitions.apply(itemId, event);
        if (outcome != ItemTransitions.Outcome.APPLIED) {
            throw new BizException(BizError.STATE_NOT_ALLOWED,
                "变更项状态已变化，审批未生效（" + event + "）",
                Map.of("publishNo", publishNo, "itemId", itemId, "event", event.name(),
                    "outcome", outcome.name()));
        }
    }

    /**
     * Mirrors the item's new state on the order row. The order row was locked by
     * {@link ApprovalService#decide} in this transaction, so a current read is fresh and the
     * {@code row_version} CAS can only fail if this transaction itself is inconsistent (it then
     * rolls everything back instead of leaving the order saying something the item does not).
     */
    private void syncOrderStatus(long orderId, String status) {
        ReleaseOrderPO current = orderMapper.selectByIdForUpdate(orderId);
        if (current == null) {
            throw new IllegalStateException("release_order disappeared during an approval: "
                + orderId);
        }
        int version = current.getRowVersion() == null ? 0 : current.getRowVersion();
        int rows = orderMapper.update(null, Wrappers.<ReleaseOrderPO>lambdaUpdate()
            .eq(ReleaseOrderPO::getId, orderId)
            .eq(ReleaseOrderPO::getRowVersion, version)
            .set(ReleaseOrderPO::getStatus, status)
            .set(ReleaseOrderPO::getRowVersion, version + 1));
        if (rows != 1) {
            throw new BizException(BizError.STATE_NOT_ALLOWED,
                "发布单状态已变化，审批未生效，请刷新后重试", Map.of("orderId", orderId));
        }
    }

    // ------------------------------------------------------------------ helpers

    private static Map<String, Object> payload(String publishNo, ApprovalStep step, String role,
                                               Map<String, Object> extra) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("publishNo", publishNo);
        payload.put("stepNo", step.stepNo());
        payload.put("role", role);
        payload.putAll(extra);
        return payload;
    }

    /**
     * The platform role that may sign a step. The mapping is explicit (not {@code name()}): the
     * domain enum and {@link Roles} are two contracts that happen to share their identifiers today,
     * and a rename on either side must be a compile error here instead of silently opening or
     * closing a step.
     */
    private static String roleNameOf(ApprovalRole role) {
        return switch (role) {
            case OPS_LEAD -> Roles.OPS_LEAD;
            case DEPT_MANAGER -> Roles.DEPT_MANAGER;
        };
    }

    private static void requirePerm(CurrentUser user, String perm) {
        if (!user.hasPerm(perm)) {
            throw new BizException(BizError.FORBIDDEN, "无权执行该操作：" + perm,
                Map.of("perm", perm));
        }
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
        ReleaseItemPO item = itemMapper.selectById(order.getItemId());
        if (item == null) {
            throw new BizException(BizError.PARAM_INVALID, "变更项不存在",
                Map.of("publishNo", order.getPublishNo(), "itemId", order.getItemId()));
        }
        return item;
    }

    private static String requirePublishNo(String raw) {
        String no = raw == null ? "" : raw.trim();
        if (no.isEmpty()) {
            throw new BizException(BizError.PARAM_INVALID, "发布单号不能为空",
                Map.of("publishNo", "不能为空"));
        }
        return no;
    }
}