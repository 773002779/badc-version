package com.sf.release.application;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sf.release.application.audit.AuditCommand;
import com.sf.release.application.audit.AuditService;
import com.sf.release.common.BizError;
import com.sf.release.common.BizException;
import com.sf.release.common.security.Permissions;
import com.sf.release.domain.admission.AdmissionDecision;
import com.sf.release.domain.approval.ApprovalRole;
import com.sf.release.domain.audit.AuditAction;
import com.sf.release.domain.task.ItemEvent;
import com.sf.release.domain.task.ItemStatus;
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

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 提交生产 / 驳回后重提 / 准入视图 of one 发布单 (tech design 4.5, 4.8).
 *
 * <p><strong>One transaction: order + snapshot + item + chain.</strong> A submission writes the
 * order's admission fields, inserts an immutable admission snapshot, moves the 变更项
 * ({@code SUBMIT} then {@code NO_APPROVAL}/{@code NEED_APPROVAL}) and builds the approval chain of
 * that snapshot. Any failure rolls all four back, so no half-submitted release can exist.
 *
 * <p><strong>Admission never blocks on a freeze.</strong> A freeze only adds the second signature
 * (design 4.5 step 5); the submission is refused only by the two gates, the OA ticket or the planned
 * time. The gate and the first gate-related reason map to 40002, everything else to 40000, and the
 * payload always carries the failed steps with their reasons and the next action.
 *
 * <p><strong>Resubmission starts a new generation.</strong> A rejected release is submitted again by
 * {@link #resubmit}: the item takes {@code RESUBMIT}, a <em>new</em> snapshot is inserted and a
 * <em>new</em> chain is built for it (V22 keys the chain by snapshot). The earlier snapshot and its
 * decided steps stay untouched as history — that is what "旧审批单保留为历史" means. When the body
 * omits the OA ticket or the planned time, the values of the previous submission are reused (design
 * 4.3: "重跑复用，不重填").
 *
 * <p><strong>Reading.</strong> {@link #admission(String)} returns the latest snapshot, the chain of
 * that snapshot, the current item/order status and the pre-execution review preview of the stored
 * snapshot (P3: {@link ReviewService}). The review is a preview only; {@link ExecuteProdService}
 * re-runs it right before executing.
 */
@Service
public class SubmitProdService {

    private static final Logger log = LoggerFactory.getLogger(SubmitProdService.class);

    /** How long after the planned time the release counts as overdue ({@code deadline_at}). */
    static final Duration DEADLINE_AFTER_PLANNED = Duration.ofHours(2);

    private static final String ORDER_SUBMITTED = ItemStatus.SUBMITTED.name();

    private final ReleaseOrderMapper orderMapper;
    private final ReleaseItemMapper itemMapper;
    private final AdmissionSnapshotMapper snapshotMapper;
    private final AdmissionService admissionService;
    private final ApprovalService approvalService;
    private final ItemTransitions transitions;
    private final ReviewService reviewService;
    private final AuditService audit;

    public SubmitProdService(ReleaseOrderMapper orderMapper,
                             ReleaseItemMapper itemMapper,
                             AdmissionSnapshotMapper snapshotMapper,
                             AdmissionService admissionService,
                             ApprovalService approvalService,
                             ItemTransitions transitions,
                             ReviewService reviewService,
                             AuditService audit) {
        this.orderMapper = orderMapper;
        this.itemMapper = itemMapper;
        this.snapshotMapper = snapshotMapper;
        this.admissionService = admissionService;
        this.approvalService = approvalService;
        this.transitions = transitions;
        this.reviewService = reviewService;
        this.audit = audit;
    }

    /**
     * Submits one 发布单 for production.
     *
     * @param plannedReleaseTime the intended production time (required, after now, ≤ 30 days)
     * @param oaTicketNo         the OA ticket (required, see {@code AdmissionEvaluator#OA_TICKET})
     * @throws BizException 40000 unknown publish number, a bad OA ticket or planned time; 40001 the
     *                      item is not {@code GATE_PENDING} any more (repeat submit); 40002 a gate
     *                      or test finding; 40009 no permission or no scope on the unit
     */
    @Transactional
    public AdmissionView submit(String publishNo, Instant plannedReleaseTime, String oaTicketNo) {
        CurrentUser user = UserContext.current();
        requirePerm(user, Permissions.CONTROL_SUBMIT_PROD);
        String no = requirePublishNo(publishNo);
        ReleaseOrderPO order = requireOrder(no);
        ReleaseItemPO item = requireItem(order);
        ScopeGuard.assertUnitInScope(unitIdOf(item));
        ItemStatus status = AdmissionService.statusOf(item);
        if (status != ItemStatus.GATE_PENDING) {
            throw repeatOrWrongStatus(no, item, status);
        }
        AdmissionPlan plan = admissionService.plan(order.getId(), plannedReleaseTime, oaTicketNo);
        requireAdmitted(plan);
        writeOrder(order, plan);
        long snapshotId = writeSnapshot(order.getId(), item.getId(), plan, user.userId());
        applySubmit(item.getId(), plan.decision().requiredRoles());
        approvalService.ensureChain(order.getId(), snapshotId, plan.decision().requiredRoles());
        String finalStatus = finalStatusOf(plan);
        syncOrderStatus(order.getId(), rowVersionOf(order) + 1, finalStatus);
        log.info("release submitted for production: publishNo={}, orderId={}, itemId={},"
                + " snapshotId={}, window={}, frozen={}, roles={}, status={}, oa={}, plannedAt={},"
                + " by={}", no, order.getId(), item.getId(), snapshotId, plan.windowResult(),
            plan.frozen(), plan.decision().requiredRoles(), finalStatus, plan.oaTicketNo(),
            plan.plannedAt(), user.userId());
        audit.record(AuditCommand.of(AuditAction.SUBMIT_PROD, no, no,
                "提交生产：发布单 " + no + "（变更项 " + item.getCrid() + "）")
            .before(Map.of("itemStatus", status.name()))
            .after(Map.of(
                "itemStatus", finalStatus,
                "oaTicketNo", plan.oaTicketNo(),
                "plannedReleaseTime", plan.plannedAt().toString(),
                "snapshotId", snapshotId))
            .scopeUnitId(unitIdOf(item))
            .build());
        return admissionView(order, item, finalStatus, finalStatus, snapshotId, plan.decision());
    }

    /**
     * Resubmits a rejected 发布单: {@code REJECTED → SUBMITTED}, a fresh admission judgement, a new
     * snapshot and a new chain generation.
     *
     * @param plannedReleaseTime optional; {@code null} reuses the previous submission's value
     * @param oaTicketNo         optional; {@code null} reuses the previous submission's ticket
     * @throws BizException 40000/40002 as {@link #submit}; 40001 the item is not {@code REJECTED}
     */
    @Transactional
    public AdmissionView resubmit(String publishNo, Instant plannedReleaseTime, String oaTicketNo) {
        CurrentUser user = UserContext.current();
        requirePerm(user, Permissions.CONTROL_SUBMIT_PROD);
        String no = requirePublishNo(publishNo);
        ReleaseOrderPO order = requireOrder(no);
        ReleaseItemPO item = requireItem(order);
        ScopeGuard.assertUnitInScope(unitIdOf(item));
        ItemStatus status = AdmissionService.statusOf(item);
        if (status != ItemStatus.REJECTED) {
            throw new BizException(BizError.STATE_NOT_ALLOWED,
                "只有已驳回（REJECTED）的变更项可以重新提交生产，当前是 " + status,
                Map.of("publishNo", no, "itemId", item.getId(), "status", status.name()));
        }
        Instant planned = plannedReleaseTime == null
            ? order.getPlannedReleaseTime() : plannedReleaseTime;
        String oa = trimToNull(oaTicketNo) == null ? order.getOaTicketNo() : oaTicketNo;
        AdmissionPlan plan = admissionService.plan(order.getId(), planned, oa);
        requireAdmitted(plan);
        applyTransition(item.getId(), ItemEvent.RESUBMIT);
        writeOrder(order, plan);
        long snapshotId = writeSnapshot(order.getId(), item.getId(), plan, user.userId());
        approvalService.ensureChain(order.getId(), snapshotId, plan.decision().requiredRoles());
        applyAfterSubmit(item.getId(), plan.decision().requiredRoles());
        String finalStatus = finalStatusOf(plan);
        syncOrderStatus(order.getId(), rowVersionOf(order) + 1, finalStatus);
        log.info("release resubmitted for production: publishNo={}, orderId={}, itemId={},"
                + " snapshotId={}, window={}, frozen={}, roles={}, status={}, oa={}, plannedAt={},"
                + " by={}", no, order.getId(), item.getId(), snapshotId, plan.windowResult(),
            plan.frozen(), plan.decision().requiredRoles(), finalStatus, plan.oaTicketNo(),
            plan.plannedAt(), user.userId());
        return admissionView(order, item, finalStatus, finalStatus, snapshotId, plan.decision());
    }

    /**
     * The admission view of one 发布单: the latest snapshot, the chain of that snapshot, the current
     * item/order status and the pre-execution review preview of the stored snapshot.
     *
     * <p>{@code admitted}/{@code steps} are {@code null}/empty here: the snapshot stores the outcome,
     * not the six step sentences, and re-evaluating it would recompute the window the design says
     * must not be recomputed (4.6). Use {@link #check} for a fresh judgement. The {@code review}
     * section is filled (design 4.8: 快照、审批链、复核预览); the submit/resubmit/check answers keep it
     * {@code null} because those are admission answers, not execution ones.
     *
     * @throws BizException 40000 unknown publish number; 40009 no data scope on the owning unit
     */
    public AdmissionView admission(String publishNo) {
        String no = requirePublishNo(publishNo);
        ReleaseOrderPO order = requireOrder(no);
        ReleaseItemPO item = requireItem(order);
        ScopeGuard.assertUnitInScope(unitIdOf(item));
        AdmissionSnapshotPO snapshot = snapshotMapper.selectLatestByOrder(order.getId());
        if (snapshot == null) {
            return new AdmissionView(order.getPublishNo(), order.getId(), item.getId(),
                item.getStatus(), order.getStatus(), null, List.of(), "尚未提交生产", null,
                List.of(), null);
        }
        List<ApprovalStepView> chain = approvalService.stepViews(snapshot.getId());
        return new AdmissionView(order.getPublishNo(), order.getId(), item.getId(),
            item.getStatus(), order.getStatus(), AdmissionView.snapshotOf(snapshot), chain,
            nextActionOf(chain, item.getStatus()),
            null, List.of(),
            reviewService.reviewOf(order, item, snapshot));
    }

    /**
     * The draft admission judgement of one 发布单 — the submit dialog's "may this go to production,
     * and what is still missing" — <strong>without writing anything</strong>.
     *
     * <p>Open to any logged-in caller inside the deployment unit's scope (no
     * {@code control:submit-prod}): asking the question is not a release decision, and the developer
     * who fills the dialog needs the answers before the button exists.
     *
     * @param plannedReleaseTime the proposed production time; {@code null} answers "请填写"
     * @param oaTicketNo         the OA ticket the operator typed
     * @throws BizException 40000 unknown publish number; 40009 no data scope on the owning unit
     */
    public AdmissionView check(String publishNo, Instant plannedReleaseTime, String oaTicketNo) {
        String no = requirePublishNo(publishNo);
        ReleaseOrderPO order = requireOrder(no);
        ReleaseItemPO item = requireItem(order);
        ScopeGuard.assertUnitInScope(unitIdOf(item));
        AdmissionDecision decision = admissionService.evaluate(order.getId(), plannedReleaseTime,
            oaTicketNo);
        return new AdmissionView(order.getPublishNo(), order.getId(), item.getId(), item.getStatus(),
            order.getStatus(), null, List.of(), decision.nextAction(), decision.admitted(),
            AdmissionView.stepsOf(decision), null);
    }

    // ------------------------------------------------------------------ writes

    /**
     * Writes the admission fields of the order with the {@code row_version} CAS and the
     * {@code SUBMITTED} marker; the final {@code READY}/{@code PENDING_APPROVAL} status follows the
     * item a moment later (the item state machine is the authority, the order mirrors it).
     */
    private void writeOrder(ReleaseOrderPO order, AdmissionPlan plan) {
        int version = rowVersionOf(order);
        int rows = orderMapper.update(null, Wrappers.<ReleaseOrderPO>lambdaUpdate()
            .eq(ReleaseOrderPO::getId, order.getId())
            .eq(ReleaseOrderPO::getRowVersion, version)
            .set(ReleaseOrderPO::getOaTicketNo, plan.oaTicketNo())
            .set(ReleaseOrderPO::getPlannedReleaseTime, plan.plannedAt())
            .set(ReleaseOrderPO::getWindowResult, plan.windowResult())
            .set(ReleaseOrderPO::getDeadlineAt, plan.plannedAt().plus(DEADLINE_AFTER_PLANNED))
            .set(ReleaseOrderPO::getStatus, ORDER_SUBMITTED)
            .set(ReleaseOrderPO::getRowVersion, version + 1));
        if (rows != 1) {
            throw new BizException(BizError.STATE_NOT_ALLOWED, "发布单已被并发更新，请刷新后重试",
                Map.of("publishNo", order.getPublishNo(), "orderId", order.getId()));
        }
    }

    private void syncOrderStatus(long orderId, int currentVersion, String status) {
        int rows = orderMapper.update(null, Wrappers.<ReleaseOrderPO>lambdaUpdate()
            .eq(ReleaseOrderPO::getId, orderId)
            .eq(ReleaseOrderPO::getRowVersion, currentVersion)
            .set(ReleaseOrderPO::getStatus, status)
            .set(ReleaseOrderPO::getRowVersion, currentVersion + 1));
        if (rows != 1) {
            // The write above took the row lock in this very transaction, so 0 rows means the
            // assumption is broken: fail the submission instead of leaving the order saying
            // SUBMITTED while the item is already READY/PENDING_APPROVAL.
            log.warn("order status {} not applied to order {}: row_version {} no longer matches",
                status, orderId, currentVersion);
            throw new BizException(BizError.STATE_NOT_ALLOWED,
                "发布单状态已变化，提交未生效，请刷新后重试", Map.of("orderId", orderId));
        }
    }

    /**
     * Inserts the immutable admission snapshot (design 4.5) and returns its id, which is also the
     * chain's key. {@code submittedBy} is the caller of the submit/resubmit use case: it is the
     * evidence the approval flow uses to refuse self-approval (design 4.4, V23).
     */
    private long writeSnapshot(long orderId, long itemId, AdmissionPlan plan, String submittedBy) {
        Instant now = Instant.now();
        AdmissionSnapshotPO po = new AdmissionSnapshotPO();
        po.setOrderId(orderId);
        po.setItemId(itemId);
        po.setSubmittedBy(trimToNull(submittedBy));
        po.setPlannedReleaseTime(plan.plannedAt());
        po.setInWindow(plan.inWindow());
        po.setWindowId(plan.windowId());
        po.setFrozen(plan.frozen());
        po.setFreezeIds(SnapshotJson.ofLongs(plan.freezeIds()));
        po.setMaxSeverity(plan.maxSeverity());
        po.setCheckConclusion(plan.checkConclusion());
        po.setWaiverIds(SnapshotJson.ofLongs(plan.waiverIds()));
        po.setTestConclusion(plan.testConclusion());
        po.setTestPassBy(plan.testPassBy());
        po.setOaTicketNo(plan.oaTicketNo());
        po.setImageDigest(plan.imageDigest());
        po.setChainJson(SnapshotJson.ofStrings(
            plan.decision().requiredRoles().stream().map(role -> role.name()).toList()));
        po.setCreatedAt(now);
        snapshotMapper.insert(po);
        return po.getId() == null ? 0L : po.getId();
    }

    /** {@code GATE_PENDING → SUBMITTED}, then the admission's own {@code SUBMITTED → …} step. */
    private void applySubmit(long itemId, List<ApprovalRole> roles) {
        applyTransition(itemId, ItemEvent.SUBMIT);
        applyAfterSubmit(itemId, roles);
    }

    private void applyAfterSubmit(long itemId, List<ApprovalRole> roles) {
        applyTransition(itemId, roles.isEmpty() ? ItemEvent.NO_APPROVAL : ItemEvent.NEED_APPROVAL);
    }

    /**
     * Applies one main-state event and turns "the state changed under us" into a 40001, so the
     * caller retries instead of half-writing a submission.
     */
    private void applyTransition(long itemId, ItemEvent event) {
        ItemTransitions.Outcome outcome = transitions.apply(itemId, event);
        if (outcome == ItemTransitions.Outcome.APPLIED) {
            return;
        }
        throw new BizException(BizError.STATE_NOT_ALLOWED,
            "变更项状态已变化，提交未生效（" + event + "）",
            Map.of("itemId", itemId, "event", event.name(), "outcome", outcome.name()));
    }

    // ------------------------------------------------------------------ views and errors

    private AdmissionView admissionView(ReleaseOrderPO order, ReleaseItemPO item, String itemStatus,
                                        String orderStatus, long snapshotId,
                                        AdmissionDecision decision) {
        AdmissionSnapshotPO snapshot = snapshotMapper.selectById(snapshotId);
        return new AdmissionView(order.getPublishNo(), order.getId(), item.getId(), itemStatus,
            orderStatus, AdmissionView.snapshotOf(snapshot), approvalService.stepViews(snapshotId),
            decision.nextAction(), decision.admitted(), AdmissionView.stepsOf(decision), null);
    }

    private static String finalStatusOf(AdmissionPlan plan) {
        return plan.decision().requiredRoles().isEmpty()
            ? ItemStatus.READY.name() : ItemStatus.PENDING_APPROVAL.name();
    }

    /** The "next step" sentence of the view when the item is not being submitted right now. */
    private static String nextActionOf(List<ApprovalStepView> chain, String itemStatus) {
        for (ApprovalStepView step : chain) {
            if ("PENDING".equals(step.status())) {
                return "等待 " + AdmissionDecision.roleLabel(step.role()) + " 审批（第 " + step.stepNo()
                    + " 步）";
            }
        }
        if (ItemStatus.READY.name().equals(itemStatus)) {
            return "无需进一步审批，可以执行生产";
        }
        if (ItemStatus.PENDING_APPROVAL.name().equals(itemStatus)) {
            return "审批链已无待签步骤，请刷新状态";
        }
        if (ItemStatus.REJECTED.name().equals(itemStatus)) {
            return "审批已驳回，可修改后重新提交";
        }
        return "当前状态：" + itemStatus;
    }

    /**
     * Refuses a submission whose admission judgement failed, with the reasons and the next action.
     * A gate/test reason is 40002 (design 4.5 step 1/2), a bad OA ticket or planned time is 40000
     * (step 3/6) — the code the requirement names, not a message-text guess.
     */
    private static void requireAdmitted(AdmissionPlan plan) {
        AdmissionDecision decision = plan.decision();
        if (decision.admitted()) {
            return;
        }
        BizError error = decision.blockedByGateOrTest()
            ? BizError.GATE_BLOCKED : BizError.PARAM_INVALID;
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("publishNo", plan.publishNo());
        payload.put("itemId", plan.itemId());
        payload.put("reasons", decision.reasons());
        payload.put("failedSteps", decision.failedSteps().stream()
            .map(step -> step.key().name()).toList());
        payload.put("steps", AdmissionView.stepPayloads(decision));
        payload.put("nextAction", decision.nextAction());
        throw new BizException(error, String.join("；", decision.reasons()), payload);
    }

    private static BizException repeatOrWrongStatus(String publishNo, ReleaseItemPO item,
                                                    ItemStatus status) {
        Map<String, Object> payload = Map.of("publishNo", publishNo, "itemId", item.getId(),
            "status", status.name());
        if (status == ItemStatus.SUBMITTED || status == ItemStatus.PENDING_APPROVAL
                || status == ItemStatus.READY) {
            return new BizException(BizError.STATE_NOT_ALLOWED,
                "变更项已提交生产（当前：" + status + "），请勿重复提交", payload);
        }
        if (status == ItemStatus.REJECTED) {
            return new BizException(BizError.STATE_NOT_ALLOWED,
                "变更项已被驳回（REJECTED），请使用「重新提交」而不是再次提交生产", payload);
        }
        return new BizException(BizError.STATE_NOT_ALLOWED,
            "只有门禁通过（GATE_PENDING）的变更项可以提交生产，当前是 " + status, payload);
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

    private static long unitIdOf(ReleaseItemPO item) {
        return item.getUnitId() == null ? 0L : item.getUnitId();
    }

    private static int rowVersionOf(ReleaseOrderPO order) {
        return order.getRowVersion() == null ? 0 : order.getRowVersion();
    }

    private static String requirePublishNo(String raw) {
        String no = trimToNull(raw);
        if (no == null) {
            throw new BizException(BizError.PARAM_INVALID, "发布单号不能为空",
                Map.of("publishNo", "不能为空"));
        }
        return no;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}