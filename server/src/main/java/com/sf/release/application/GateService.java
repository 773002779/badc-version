package com.sf.release.application;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sf.release.common.BizError;
import com.sf.release.common.BizException;
import com.sf.release.common.security.Permissions;
import com.sf.release.common.security.Roles;
import com.sf.release.domain.gate.GateCheck;
import com.sf.release.domain.gate.GateCheckStatus;
import com.sf.release.domain.gate.GateConclusion;
import com.sf.release.domain.gate.GateDecision;
import com.sf.release.domain.gate.GateEvaluator;
import com.sf.release.domain.gate.GateWaiver;
import com.sf.release.domain.gate.GateWaiverStatus;
import com.sf.release.domain.gate.Severity;
import com.sf.release.domain.pipeline.CheckType;
import com.sf.release.domain.pipeline.PipelineInstance;
import com.sf.release.domain.pipeline.port.PipelineAdvancer;
import com.sf.release.domain.pipeline.port.PipelineRepository;
import com.sf.release.domain.task.GateStatus;
import com.sf.release.domain.task.ItemEvent;
import com.sf.release.domain.task.ItemStatus;
import com.sf.release.domain.task.ReleaseItem;
import com.sf.release.domain.task.TestStatus;
import com.sf.release.infra.repo.GateWaiverMapper;
import com.sf.release.infra.repo.PipelineInstanceMapper;
import com.sf.release.infra.repo.ReleaseItemMapper;
import com.sf.release.infra.repo.ReleaseOrderMapper;
import com.sf.release.infra.repo.ReleaseTaskMemberMapper;
import com.sf.release.infra.security.CurrentUser;
import com.sf.release.infra.security.ScopeGuard;
import com.sf.release.infra.security.UserContext;
import com.sf.release.infra.repo.po.GateWaiverPO;
import com.sf.release.infra.repo.po.PipelineInstancePO;
import com.sf.release.infra.repo.po.ReleaseItemPO;
import com.sf.release.infra.repo.po.ReleaseOrderPO;
import com.sf.release.infra.repo.po.ReleaseTaskMemberPO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The gate decision of one 变更项 (requirements §4.3/§4.4/§4.6, tech design §7): combines the two
 * gates (checks and test), writes the orthogonal {@code release_item.gate_status}, drives the main
 * state machine, answers the production admission and backs the verification panel.
 *
 * <p><strong>The two gates are independent, the decision is one.</strong> The check gate is
 * computed by {@link GateEvaluator} from the recorded checks; the test gate lives on the item
 * ({@code gate_status} and {@code last_test_pass_by/at}). Production admission needs both — a
 * waiver only lifts the check gate (FR-Q-03) and never the test gate.
 *
 * <p><strong>The main state only moves on a final answer.</strong> While a check is still running,
 * {@code evaluate} writes {@code CHECK_RUNNING} and emits nothing: moving to {@code GATE_FAILED}
 * because a check is in flight would force a rerun for something that may pass in a minute. A pass
 * requires <em>both</em> gates; a <em>final</em> block (a 高危/严重 finding, an invalidated result, a
 * failed or revoked test) emits {@code GATE_FAIL}. A waivable 中危 block keeps the item in
 * {@code TESTING} and the {@code VERIFY} node waiting, so the waiver can be filed before the release
 * is discarded.
 *
 * <p><strong>Invalidation (C16).</strong> When a granted admission stops being true — a check
 * result becomes invalid, a waiver expires, the tester revokes the pass — the item falls back to
 * {@code GATE_FAILED} through {@code CHECK_INVALID}/{@code TEST_REVOKE}/{@code GATE_INVALID}. Only
 * what has not happened yet is revoked: this service never touches a succeeded production batch
 * (requirements §4.6).
 *
 * <p><strong>Test pass rules (C18/C19).</strong> Any member of the task holding the {@code TEST}
 * role may pass the test; only the person who passed it may revoke it, and revoking needs a
 * reason. A platform {@code ADMIN} may act without being a member (break-glass), everybody else
 * must be a member — the task's own member list is the authority, not a platform role.
 */
@Service
public class GateService {

    private static final Logger log = LoggerFactory.getLogger(GateService.class);

    /** Max stored length of a test reason, matching the DDL. */
    static final int MAX_REASON = 512;

    private final ReleaseItemMapper itemMapper;
    private final ReleaseOrderMapper orderMapper;
    private final GateCheckService checkService;
    private final GateWaiverMapper waiverMapper;
    private final ReleaseTaskMemberMapper memberMapper;
    private final PipelineInstanceMapper instanceMapper;
    private final PipelineRepository repository;
    private final ItemTransitions transitions;
    private final PipelineAdvancer advancer;

    public GateService(ReleaseItemMapper itemMapper,
                       ReleaseOrderMapper orderMapper,
                       GateCheckService checkService,
                       GateWaiverMapper waiverMapper,
                       ReleaseTaskMemberMapper memberMapper,
                       PipelineInstanceMapper instanceMapper,
                       PipelineRepository repository,
                       ItemTransitions transitions,
                       PipelineAdvancer advancer) {
        this.itemMapper = Objects.requireNonNull(itemMapper, "itemMapper must not be null");
        this.orderMapper = Objects.requireNonNull(orderMapper, "orderMapper must not be null");
        this.checkService = Objects.requireNonNull(checkService, "checkService must not be null");
        this.waiverMapper = Objects.requireNonNull(waiverMapper, "waiverMapper must not be null");
        this.memberMapper = Objects.requireNonNull(memberMapper, "memberMapper must not be null");
        this.instanceMapper = Objects.requireNonNull(instanceMapper,
            "instanceMapper must not be null");
        this.repository = Objects.requireNonNull(repository, "repository must not be null");
        this.transitions = Objects.requireNonNull(transitions, "transitions must not be null");
        this.advancer = Objects.requireNonNull(advancer, "advancer must not be null");
    }

    // ------------------------------------------------------------------ decision

    /**
     * Evaluates the gate of {@code itemId}, persists {@code gate_status}, advances the main state
     * when the decision is final and re-drives the pipeline.
     *
     * @throws BizException 40000 the item does not exist
     */
    public GateDecision evaluate(long itemId) {
        ReleaseItemPO po = requireItem(itemId);
        ReleaseItem item = po.toDomain();
        Snapshot snapshot = snapshotOf(item);
        GateDecision decision = decide(item, snapshot);
        persistGateStatus(item, decision);

        if (item.status() == ItemStatus.TESTING) {
            if (decision.admits() && testPassed(po)) {
                apply(itemId, ItemEvent.GATE_PASS);
            } else if (finalBlock(decision) || testFailed(po)) {
                // Only a verdict the customer cannot turn around ends the item: a 高危/严重 finding,
                // an invalidated result, a failed or revoked test. A 中危 finding that could still
                // be waived keeps the item in TESTING and the VERIFY node waiting, so the waiver
                // decision can be made before the release is thrown away (FR-Q-02/FR-Q-03).
                apply(itemId, ItemEvent.GATE_FAIL);
            }
        } else if (revokesAdmission(item.status()) && !productionStillAdmitted(po, decision)) {
            apply(itemId, invalidationEvent(item.status()));
        }
        if (snapshot.instanceId() > 0) {
            advancer.advance(snapshot.instanceId());
        }
        return decision;
    }

    /**
     * The decision of one item without writing anything. Used by the {@code VERIFY} node handler to
     * answer "may this node complete now?" inside the orchestrator's transaction.
     */
    public GateDecision decisionFor(long itemId) {
        ReleaseItem item = requireItem(itemId).toDomain();
        return decide(item, snapshotOf(item));
    }

    /** Re-evaluates and returns the refreshed panel; used after an action that changed a check. */
    public GateView evaluateAndView(long itemId) {
        evaluate(itemId);
        return view(itemId);
    }

    /**
     * Evaluates the item behind {@code instance} when there is one. Called right after the checks
     * were triggered so the recorded dimension becomes {@code CHECK_RUNNING} instead of staying at
     * "no result yet": the page must show that the gate is being evaluated, not that it never
     * started.
     */
    public void evaluateForInstance(PipelineInstance instance) {
        long itemId = itemIdOfInstance(instance);
        if (itemId > 0) {
            evaluate(itemId);
        }
    }

    /** The decision for an instance's item, or a "not started" decision for a daily instance. */
    public GateDecision decisionForInstance(PipelineInstance instance) {
        long itemId = itemIdOfInstance(instance);
        if (itemId <= 0) {
            return new GateDecision(GateConclusion.RUNNING, Severity.NONE, 0, 0, 0, false,
                List.of("实例没有关联的变更项，门禁不适用"));
        }
        return decisionFor(itemId);
    }

    /**
     * Whether the {@code VERIFY} node of {@code instance} may complete: both gates are satisfied
     * (checks passed or waived <em>and</em> the test passed). Read-only, because the orchestrator
     * calls it from inside its advance transaction.
     */
    public boolean verifySatisfied(PipelineInstance instance) {
        long itemId = itemIdOfInstance(instance);
        if (itemId <= 0) {
            return false;
        }
        ReleaseItemPO po = requireItem(itemId);
        return decide(po.toDomain(), snapshotOf(po.toDomain())).admits() && testPassed(po);
    }

    /**
     * Why the {@code VERIFY} node must fail now, or {@code null} while it may still turn around.
     * Only a <em>final</em> verdict fails the node: a block (checks finished and blocking) or a test
     * that failed or was revoked. A running check is not a failure.
     */
    public String verifyBlockedReason(PipelineInstance instance) {
        long itemId = itemIdOfInstance(instance);
        if (itemId <= 0) {
            return null;
        }
        ReleaseItemPO po = requireItem(itemId);
        ReleaseItem item = po.toDomain();
        GateDecision decision = decide(item, snapshotOf(item));
        if (finalBlock(decision)) {
            return "门禁未通过：" + decision.summary()
                + (decision.reasons().isEmpty() ? ""
                    : "（" + String.join("；", decision.reasons()) + "）");
        }
        String reason = po.getTestFailReason() == null ? "未填写原因" : po.getTestFailReason();
        TestStatus test = testStatusOf(po);
        if (test == TestStatus.TEST_FAIL) {
            return "测试不通过：" + reason;
        }
        if (test == TestStatus.TEST_REVOKED) {
            return "测试通过已被撤回：" + reason;
        }
        return null;
    }

    private GateDecision decide(ReleaseItem item, Snapshot snapshot) {
        return GateEvaluator.evaluate(checkService.checks(item.id()), snapshot.waiver(),
            Instant.now(), snapshot.commitSha(), snapshot.imageDigest());
    }

    /**
     * Writes the orthogonal dimension. Only a terminal item keeps its recorded value: a closed or
     * terminated item is history, not a gate to recompute.
     */
    private void persistGateStatus(ReleaseItem item, GateDecision decision) {
        if (!item.status().isBlocking() && item.status() != ItemStatus.DONE) {
            return;
        }
        GateStatus next = decision.gateStatus();
        if (item.gateStatus() == next) {
            return;
        }
        itemMapper.update(null, Wrappers.<ReleaseItemPO>lambdaUpdate()
            .eq(ReleaseItemPO::getId, item.id())
            .set(ReleaseItemPO::getGateStatus, next.name())
            .set(ReleaseItemPO::getUpdatedAt, Instant.now()));
    }

    private void apply(long itemId, ItemEvent event) {
        ItemTransitions.Outcome outcome = transitions.apply(itemId, event);
        if (outcome == ItemTransitions.Outcome.NOT_ALLOWED) {
            log.debug("item {} does not accept {} in its current state", itemId, event);
        }
    }

    /** Statuses where a granted admission still exists and can be revoked. */
    private static boolean revokesAdmission(ItemStatus status) {
        return status == ItemStatus.GATE_PENDING || status == ItemStatus.SUBMITTED
            || status == ItemStatus.PENDING_APPROVAL || status == ItemStatus.READY;
    }

    /**
     * A verdict the release cannot turn around by itself: an invalidated result, or a blocking
     * finding that cannot be waived. A waivable 中危 block is <em>not</em> final — the item keeps
     * waiting while the waiver is requested and reviewed.
     */
    private static boolean finalBlock(GateDecision decision) {
        return decision.conclusion() == GateConclusion.INVALID
            || (decision.conclusion() == GateConclusion.BLOCK && !decision.waivable());
    }

    /**
     * The transition event that revokes an admission in {@code status}. The state matrix accepts
     * {@code CHECK_INVALID} everywhere except {@code PENDING_APPROVAL}, which uses
     * {@code GATE_INVALID} (both end in {@code GATE_FAILED}).
     */
    private static ItemEvent invalidationEvent(ItemStatus status) {
        return status == ItemStatus.PENDING_APPROVAL
            ? ItemEvent.GATE_INVALID : ItemEvent.CHECK_INVALID;
    }

    /** True when the recorded admission facts still hold. */
    private boolean productionStillAdmitted(ReleaseItemPO po, GateDecision decision) {
        return decision.admits() && testPassed(po);
    }

    // ------------------------------------------------------------------ test gate

    /**
     * Marks the test passed: any member of the task with the {@code TEST} role may do it (C18), and
     * a platform admin may do it without being a member.
     *
     * @throws BizException 40009 the caller may not pass this item's test; 40001 the item is not in
     *                      testing, or the test environment is not ready yet
     */
    public GateView passTest(long itemId) {
        CurrentUser user = UserContext.current();
        requirePerm(user, Permissions.GATE_TEST_PASS);
        ReleaseItemPO po = requireItem(itemId);
        ReleaseItem item = po.toDomain();
        ScopeGuard.assertUnitInScope(item.unitId());
        requireTester(user, item);
        if (item.status() != ItemStatus.TESTING) {
            throw new BizException(BizError.STATE_NOT_ALLOWED,
                "当前状态不能标记测试通过：" + item.status(), Map.of("itemId", itemId,
                    "currentStatus", item.status().name()));
        }
        Snapshot snapshot = snapshotOf(item);
        if (snapshot.testReadyAt() == null) {
            throw new BizException(BizError.STATE_NOT_ALLOWED,
                "测试环境尚未就绪，不能标记测试通过", Map.of("itemId", itemId));
        }
        Instant now = Instant.now();
        itemMapper.update(null, Wrappers.<ReleaseItemPO>lambdaUpdate()
            .eq(ReleaseItemPO::getId, itemId)
            .set(ReleaseItemPO::getTestStatus, TestStatus.TEST_PASS.name())
            .set(ReleaseItemPO::getLastTestPassBy, user.userId())
            .set(ReleaseItemPO::getLastTestPassAt, now)
            .set(ReleaseItemPO::getTestFailReason, null)
            .set(ReleaseItemPO::getUpdatedAt, now));
        log.info("test passed: itemId={}, by={}", itemId, user.userId());
        evaluate(itemId);
        return view(itemId);
    }

    /**
     * Marks the test failed with a reason everybody can read.
     *
     * <p>The permission point is the same one that allows passing ({@code gate:test-pass}): the
     * platform has no separate "test fail" point, and a tester who may decide the test may decide
     * it either way.
     */
    public GateView failTest(long itemId, String reason) {
        CurrentUser user = UserContext.current();
        requirePerm(user, Permissions.GATE_TEST_PASS);
        ReleaseItem item = requireItem(itemId).toDomain();
        ScopeGuard.assertUnitInScope(item.unitId());
        requireTester(user, item);
        if (item.status() != ItemStatus.TESTING) {
            throw new BizException(BizError.STATE_NOT_ALLOWED,
                "当前状态不能标记测试不通过：" + item.status(),
                Map.of("itemId", itemId, "currentStatus", item.status().name()));
        }
        String text = requireReason(reason, "不通过原因");
        Instant now = Instant.now();
        itemMapper.update(null, Wrappers.<ReleaseItemPO>lambdaUpdate()
            .eq(ReleaseItemPO::getId, itemId)
            .set(ReleaseItemPO::getTestStatus, TestStatus.TEST_FAIL.name())
            .set(ReleaseItemPO::getTestFailReason, text)
            .set(ReleaseItemPO::getUpdatedAt, now));
        log.info("test failed: itemId={}, by={}, reason={}", itemId, user.userId(), text);
        evaluate(itemId);
        return view(itemId);
    }

    /**
     * Revokes a recorded test pass. Only the person who passed it may revoke it (C19); the reason
     * is required and kept.
     *
     * @throws BizException 40009 the caller did not pass this test; 40001 there is no pass to
     *                      revoke
     */
    public GateView revokeTest(long itemId, String reason) {
        CurrentUser user = UserContext.current();
        requirePerm(user, Permissions.GATE_TEST_REVOKE);
        ReleaseItemPO po = requireItem(itemId);
        ReleaseItem item = po.toDomain();
        ScopeGuard.assertUnitInScope(item.unitId());
        if (!user.hasRole(Roles.ADMIN) && !user.userId().equals(item.lastTestPassBy())) {
            throw new BizException(BizError.FORBIDDEN,
                "只有通过测试的人可以撤回", Map.of("itemId", itemId,
                    "lastTestPassBy", item.lastTestPassBy() == null ? "" : item.lastTestPassBy()));
        }
        TestStatus test = testStatusOf(po);
        if (test != TestStatus.TEST_PASS) {
            throw new BizException(BizError.STATE_NOT_ALLOWED,
                "当前没有可以撤回的测试通过：" + test,
                Map.of("itemId", itemId, "testStatus", test.name()));
        }
        String text = requireReason(reason, "撤回原因");
        Instant now = Instant.now();
        itemMapper.update(null, Wrappers.<ReleaseItemPO>lambdaUpdate()
            .eq(ReleaseItemPO::getId, itemId)
            .set(ReleaseItemPO::getTestStatus, TestStatus.TEST_REVOKED.name())
            .set(ReleaseItemPO::getTestFailReason, text)
            .set(ReleaseItemPO::getUpdatedAt, now));
        log.info("test pass revoked: itemId={}, by={}, reason={}", itemId, user.userId(), text);
        if (item.status() == ItemStatus.TESTING || item.status() == ItemStatus.GATE_PENDING) {
            apply(itemId, ItemEvent.TEST_REVOKE);
        } else if (revokesAdmission(item.status())) {
            apply(itemId, invalidationEvent(item.status()));
        }
        Snapshot snapshot = snapshotOf(item);
        if (snapshot.instanceId() > 0) {
            advancer.advance(snapshot.instanceId());
        }
        return view(itemId);
    }

    // ------------------------------------------------------------------ admission

    /**
     * Whether the item may be submitted for production, and what is missing when it may not.
     * Requirements FR-G-05: the answer must name the missing item, never just grey the button.
     */
    public Admission admission(long itemId) {
        ReleaseItemPO po = requireItem(itemId);
        ReleaseItem item = po.toDomain();
        Snapshot snapshot = snapshotOf(item);
        GateDecision decision = decide(item, snapshot);
        List<String> missing = new ArrayList<>(3);
        if (!decision.admits()) {
            missing.add(decision.summary());
        }
        if (!testPassed(po)) {
            missing.add("测试未通过（当前：" + testLabel(po) + "）");
        }
        if (item.status() == ItemStatus.GATE_FAILED || item.status() == ItemStatus.FAILED) {
            // The gate may be satisfied again (a waiver was just approved) while the item is still
            // closed out; submitting is not possible until it is republished (requirements §4.4).
            missing.add("变更项当前是 " + item.status() + "，需要重新发布后再提交");
        }
        if (snapshot.instanceId() == 0) {
            missing.add("尚未发布，没有流水线实例");
        } else if (snapshot.imageDigest() == null) {
            missing.add("尚未产出镜像，无法保证生产与测试同镜像");
        } else {
            missing.addAll(imageMismatches(itemId, snapshot.imageDigest()));
        }
        return new Admission(missing.isEmpty(), List.copyOf(missing),
            decision.conclusion().name(), decision.summary(), testLabel(po));
    }

    /** Image-level checks whose scanned object is not the image the item will deploy (C23). */
    private List<String> imageMismatches(long itemId, String digest) {
        List<String> issues = new ArrayList<>();
        for (GateCheck check : checkService.checks(itemId)) {
            if (check.checkType().stage() != CheckType.CheckStage.IMAGE) {
                continue;
            }
            if (check.scannedObject() != null && !check.scannedObject().equals(digest)) {
                issues.add(check.checkType().label() + " 的检查对象与当前镜像不一致（需重跑检查）");
            }
        }
        return issues;
    }

    // ------------------------------------------------------------------ read model

    /**
     * The verification panel of one item: the two gates, every check grouped by stage, the waiver
     * and the admission conclusion with its reasoning.
     */
    public GateView view(long itemId) {
        ReleaseItemPO po = requireItem(itemId);
        ReleaseItem item = po.toDomain();
        Snapshot snapshot = snapshotOf(item);
        GateDecision decision = decide(item, snapshot);
        Admission admission = admission(itemId);
        List<GateCheck> checks = checkService.checks(itemId);
        List<CheckGroup> groups = new ArrayList<>(2);
        for (CheckType.CheckStage stage : CheckType.CheckStage.values()) {
            List<CheckView> views = new ArrayList<>();
            for (CheckType type : CheckType.values()) {
                if (type.stage() != stage) {
                    continue;
                }
                GateCheck check = checks.stream()
                    .filter(candidate -> candidate.checkType() == type)
                    .findFirst().orElse(null);
                views.add(checkView(type, check, decision));
            }
            int passed = (int) views.stream().filter(CheckView::passed).count();
            groups.add(new CheckGroup(stage.name(), stageLabel(stage), passed, views.size(), views));
        }
        TestStatus test = testStatusOf(po);
        return new GateView(
            item.id(),
            item.crid(),
            item.status().name(),
            item.gateStatus().name(),
            decision.conclusion().name(),
            decision.conclusion().label(),
            decision.maxSeverity().name(),
            decision.maxSeverity().label(),
            decision.passed(),
            decision.total(),
            decision.blocking(),
            decision.waivable(),
            decision.summary(),
            decision.reasons(),
            test.name(),
            test.label(),
            item.lastTestPassBy(),
            item.lastTestPassAt(),
            po.getTestFailReason(),
            groups,
            waiverView(itemId),
            admission,
            snapshot.instanceId(),
            snapshot.imageDigest(),
            snapshot.commitSha());
    }

    private CheckView checkView(CheckType type, GateCheck check, GateDecision decision) {
        GateCheckStatus status = check == null ? GateCheckStatus.PENDING : check.status();
        Severity severity = check == null ? Severity.NONE : check.severity();
        boolean blocking = check != null && status != GateCheckStatus.INVALID
            && severity.blocks();
        return new CheckView(
            type.name(),
            type.label(),
            type.stage().name(),
            status.name(),
            checkStatusLabel(status),
            severity.name(),
            severity.label(),
            check == null ? 0 : check.issues(),
            check == null ? null : check.scannedObject(),
            check == null ? null : check.summary(),
            check == null ? null : check.reportUrl(),
            check == null ? null : check.startedAt(),
            check == null ? null : check.finishedAt(),
            check == null ? null : check.deadlineAt(),
            check != null && status == GateCheckStatus.PASS,
            blocking,
            blocking && severity.waivable() && decision.conclusion() == GateConclusion.BLOCK
                ? "可申请豁免" : (blocking ? "不可豁免" : null));
    }

    private WaiverView waiverView(long itemId) {
        GateWaiverPO po = waiverMapper.selectLatest(itemId);
        if (po == null) {
            return null;
        }
        GateWaiver waiver = po.toDomain();
        Instant now = Instant.now();
        return new WaiverView(waiver.id(), waiver.status().name(),
            waiverStatusLabel(waiver.status()), waiver.reason(), waiver.impact(),
            waiver.requestedBy(), waiver.requestedAt(), waiver.approvedBy(),
            waiver.approvedAt(), waiver.decideReason(), waiver.expireAt(),
            waiver.status() == GateWaiverStatus.APPROVED
                && waiver.expireAt() != null && waiver.expireAt().isAfter(now));
    }

    // ------------------------------------------------------------------ helpers

    private Snapshot snapshotOf(ReleaseItem item) {
        PipelineInstance instance = repository.latestInstanceOf(item.id());
        GateWaiverPO waiverPo = waiverMapper.selectApproved(item.id());
        GateWaiver waiver = waiverPo == null ? null : waiverPo.toDomain();
        Instant testReadyAt = null;
        if (instance != null && instance.id() != null) {
            PipelineInstancePO po = instanceMapper.selectById(instance.id());
            if (po != null) {
                testReadyAt = po.getTestReadyAt();
            }
        }
        return new Snapshot(instance == null || instance.id() == null ? 0L : instance.id(),
            instance == null ? null : instance.imageDigest(),
            instance == null ? null : instance.commitSha(), testReadyAt, waiver);
    }

    private long itemIdOfInstance(PipelineInstance instance) {
        if (instance == null || instance.id() == null) {
            return 0L;
        }
        var context = repository.contextOf(instance.id());
        if (context.orderId() <= 0) {
            return 0L;
        }
        ReleaseOrderPO order = orderMapper.selectById(context.orderId());
        return order == null || order.getItemId() == null ? 0L : order.getItemId();
    }

    private ReleaseItemPO requireItem(long itemId) {
        ReleaseItemPO po = itemMapper.selectById(itemId);
        if (po == null) {
            throw new BizException(BizError.PARAM_INVALID, "变更项不存在", Map.of("itemId", itemId));
        }
        return po;
    }

    private static void requirePerm(CurrentUser user, String perm) {
        if (!user.hasPerm(perm)) {
            throw new BizException(BizError.FORBIDDEN, "无权执行该操作：" + perm,
                Map.of("perm", perm));
        }
    }

    /** The caller must be a {@code TEST} member of the item's task, or a platform admin. */
    private void requireTester(CurrentUser user, ReleaseItem item) {
        if (user.hasRole(Roles.ADMIN)) {
            return;
        }
        Long members = memberMapper.selectCount(Wrappers.<ReleaseTaskMemberPO>lambdaQuery()
            .eq(ReleaseTaskMemberPO::getTaskId, item.taskId())
            .eq(ReleaseTaskMemberPO::getUserId, user.userId())
            .eq(ReleaseTaskMemberPO::getRole, "TEST"));
        if (members == null || members == 0) {
            throw new BizException(BizError.FORBIDDEN,
                "只有该发布任务的测试人员可以通过或驳回测试",
                Map.of("taskId", item.taskId(), "userId", user.userId()));
        }
    }

    private static String requireReason(String reason, String label) {
        String text = reason == null ? "" : reason.trim();
        if (text.isEmpty()) {
            throw new BizException(BizError.PARAM_INVALID, label + "不能为空",
                Map.of("reason", "不能为空"));
        }
        if (text.length() > MAX_REASON) {
            throw new BizException(BizError.PARAM_INVALID,
                label + "长度不能超过 " + MAX_REASON, Map.of("reason", "长度超限"));
        }
        return text;
    }

    /** The test dimension recorded on the item; a missing value degrades to {@code TEST_PENDING}. */
    private static TestStatus testStatusOf(ReleaseItemPO po) {
        return TestStatus.parse(po.getTestStatus());
    }

    private static boolean testPassed(ReleaseItemPO po) {
        return testStatusOf(po) == TestStatus.TEST_PASS;
    }

    private static boolean testFailed(ReleaseItemPO po) {
        TestStatus test = testStatusOf(po);
        return test == TestStatus.TEST_FAIL || test == TestStatus.TEST_REVOKED;
    }

    private static String testLabel(ReleaseItemPO po) {
        return testStatusOf(po).label();
    }

    private static String checkStatusLabel(GateCheckStatus status) {
        return switch (status) {
            case PENDING -> "待执行";
            case RUNNING -> "执行中";
            case PASS -> "通过";
            case FAIL -> "未通过";
            case TIMEOUT -> "超时";
            case INVALID -> "已失效";
            case UNKNOWN -> "未完成";
        };
    }

    private static String stageLabel(CheckType.CheckStage stage) {
        return stage == CheckType.CheckStage.IMAGE ? "镜像级检查" : "环境级检查";
    }

    private static String waiverStatusLabel(GateWaiverStatus status) {
        return switch (status) {
            case PENDING -> "待审批";
            case APPROVED -> "已批准";
            case REJECTED -> "已驳回";
            case EXPIRED -> "已到期";
            case CANCELED -> "已作废";
        };
    }

    /** The item's current snapshot plus the waiver that may cover it. */
    private record Snapshot(long instanceId, String imageDigest, String commitSha,
                            Instant testReadyAt, GateWaiver waiver) {
    }

    /** Production admission: the verdict plus every reason it is not yet true. */
    public record Admission(boolean eligible, List<String> missing, String checkConclusion,
                            String checkSummary, String test) {

        public Admission {
            missing = missing == null ? List.of() : List.copyOf(missing);
        }
    }

    /** The verification panel read model. */
    public record GateView(
        long itemId,
        String crid,
        String itemStatus,
        String gateStatus,
        String conclusion,
        String conclusionLabel,
        String maxSeverity,
        String maxSeverityLabel,
        int passed,
        int total,
        int blocking,
        boolean waivable,
        String summary,
        List<String> reasons,
        String testStatus,
        String test,
        String testPassBy,
        Instant testPassAt,
        String testFailReason,
        List<CheckGroup> groups,
        WaiverView waiver,
        Admission admission,
        long instanceId,
        String imageDigest,
        String commitSha) {

        public GateView {
            reasons = reasons == null ? List.of() : List.copyOf(reasons);
            groups = groups == null ? List.of() : List.copyOf(groups);
        }
    }

    /** The checks of one stage, with their pass count for the panel header. */
    public record CheckGroup(String stage, String stageLabel, int passed, int total,
                             List<CheckView> checks) {

        public CheckGroup {
            checks = checks == null ? List.of() : List.copyOf(checks);
        }
    }

    /** One check row as the page shows it; {@code checkType} is the stable kind. */
    public record CheckView(
        String kind,
        String label,
        String stage,
        String status,
        String statusLabel,
        String severity,
        String severityLabel,
        int issues,
        String scannedObject,
        String summary,
        String reportUrl,
        Instant startedAt,
        Instant finishedAt,
        Instant deadlineAt,
        boolean passed,
        boolean blocking,
        String waiverHint) {
    }

    /** The item's waiver as the page shows it. */
    public record WaiverView(
        long id,
        String status,
        String statusLabel,
        String reason,
        String impact,
        String requestedBy,
        Instant requestedAt,
        String approvedBy,
        Instant approvedAt,
        String decideReason,
        Instant expireAt,
        boolean active) {
    }
}