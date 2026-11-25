package com.sf.release.application;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sf.release.application.audit.AuditCommand;
import com.sf.release.application.audit.AuditService;
import com.sf.release.common.BizError;
import com.sf.release.common.BizException;
import com.sf.release.domain.audit.AuditAction;
import com.sf.release.domain.pipeline.NodeType;
import com.sf.release.domain.pipeline.PipelineInstance;
import com.sf.release.domain.pipeline.PipelineOrchestrator;
import com.sf.release.domain.pipeline.ReleaseOrder;
import com.sf.release.domain.pipeline.RerunPlan;
import com.sf.release.domain.pipeline.RerunPlanner;
import com.sf.release.domain.pipeline.TriggerType;
import com.sf.release.domain.pipeline.port.GitCommitProbe;
import com.sf.release.domain.pipeline.port.PipelineRepository;
import com.sf.release.domain.resource.UnitType;
import com.sf.release.domain.task.GateStatus;
import com.sf.release.domain.task.ItemEvent;
import com.sf.release.domain.task.ItemStateMachine;
import com.sf.release.domain.task.ItemStatus;
import com.sf.release.domain.task.ReleaseItem;
import com.sf.release.domain.task.TaskStatus;
import com.sf.release.domain.task.TestStatus;
import com.sf.release.infra.lock.UnitLockService;
import com.sf.release.infra.repo.ReleaseItemMapper;
import com.sf.release.infra.repo.ReleaseOrderMapper;
import com.sf.release.infra.repo.ReleaseTaskMapper;
import com.sf.release.infra.repo.po.ReleaseItemPO;
import com.sf.release.infra.repo.po.ReleaseOrderPO;
import com.sf.release.infra.repo.po.ReleaseTaskPO;
import com.sf.release.infra.security.ScopeGuard;
import com.sf.release.infra.security.UserContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The 重新发布 use case (tech design §5.3, requirements §4.5 C15):
 * {@code ScopeGuard → validate state → observe head commit → decide scope → acquire the unit
 * lock → one transaction that moves the item through RERUN/RERUN_FULL and creates a new pipeline
 * instance under the same 发布单}.
 *
 * <p><strong>Scope decision.</strong> {@link RerunPlanner} compares the item's
 * {@code base_commit} with the head the {@link GitCommitProbe} reports and filters the change by
 * the item's {@code module_path}. This service only turns the plan into an {@link ItemEvent}:
 * unchanged → {@code RERUN} ({@code TESTING}, resume at the failed node, image reused); changed
 * → {@code RERUN_FULL} ({@code BUILDING}, rebuild, test pass revoked).
 *
 * <p><strong>Resume is impossible without an image.</strong> If the plan says "resume from a
 * node" but the previous instance has no recorded {@code image_digest}, the platform cannot
 * deploy that image. The service upgrades the execution to a full rerun instead of starting a
 * pipeline that is guaranteed to fail. A resume starting at {@code BUILD} is handled the same
 * way: rebuilding is the only interpretation of that resume point, so it goes through
 * {@code RERUN_FULL} / {@code BUILDING} rather than entering {@code TESTING}.
 *
 * <p><strong>Confirmation and revocation.</strong> Revoking the previous test pass is a
 * destructive action (requirements §4.5: 需二次确认 + 留痕). {@link #rerun(long, boolean)}
 * rejects the call with a business error unless {@code confirmRevokeTestPass} is true; when it
 * proceeds, {@code last_test_pass_by/at} are cleared in the same {@code row_version} CAS that
 * applies the state transition, and the acting user is written to the log.
 *
 * <p><strong>One instance per execution, one order per item.</strong> The publish number of the
 * release order is reused; only a new {@link PipelineInstance} (new pipeline number) is created.
 * Instance creation lives in {@link PublishAppService#createRerunInstance} so the node/task
 * pre-generation and the unit-lock bind are not duplicated.
 *
 * <p><strong>Lock.</strong> The unit lock is acquired before the transaction (the same
 * immediate-visibility rule as the initial publish) and is bound to the new instance inside it.
 * A failed transaction releases the sentinel reservation; a lost bind is reported as 40006 and
 * releases nothing (the row now belongs to another holder, or to nobody, so a release could
 * steal the new holder's lock).
 */
@Service
public class RerunAppService {

    private static final Logger log = LoggerFactory.getLogger(RerunAppService.class);

    /** Git reports 40 hex (SHA-1) or 64 hex (SHA-256); the baseline column is VARCHAR(64). */
    private static final int MAX_COMMIT_LENGTH = 64;

    private static final Set<ItemStatus> RERUNNABLE =
        EnumSet.of(ItemStatus.FAILED, ItemStatus.GATE_FAILED);

    private final ReleaseItemMapper itemMapper;
    private final ReleaseOrderMapper orderMapper;
    private final ReleaseTaskMapper releaseTaskMapper;
    private final PipelineRepository pipelineRepository;
    private final GitCommitProbe commitProbe;
    private final RerunPlanner rerunPlanner;
    private final PublishAppService publishAppService;
    private final UnitLockService unitLockService;
    private final PipelineOrchestrator orchestrator;
    private final GateCheckService gateCheckService;
    private final AuditService audit;
    /** Mirrors publish: tests turn the immediate start off and drive the nodes themselves. */
    private final boolean autoStart;
    private final TransactionTemplate transactionTemplate;

    public RerunAppService(ReleaseItemMapper itemMapper,
                           ReleaseOrderMapper orderMapper,
                           ReleaseTaskMapper releaseTaskMapper,
                           PipelineRepository pipelineRepository,
                           GitCommitProbe commitProbe,
                           RerunPlanner rerunPlanner,
                           PublishAppService publishAppService,
                           UnitLockService unitLockService,
                           PipelineOrchestrator orchestrator,
                           GateCheckService gateCheckService,
                           AuditService audit,
                           @Value("${release.pipeline.auto-start:true}") boolean autoStart,
                           PlatformTransactionManager transactionManager) {
        this.itemMapper = itemMapper;
        this.orderMapper = orderMapper;
        this.releaseTaskMapper = releaseTaskMapper;
        this.pipelineRepository = pipelineRepository;
        this.commitProbe = commitProbe;
        this.rerunPlanner = rerunPlanner;
        this.publishAppService = publishAppService;
        this.unitLockService = unitLockService;
        this.orchestrator = orchestrator;
        this.gateCheckService = gateCheckService;
        this.audit = audit;
        this.autoStart = autoStart;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /** A rerun without permission to revoke the test pass; a changed commit is rejected. */
    public RerunResult rerun(long itemId) {
        return rerun(itemId, false);
    }

    /**
     * Republished {@code itemId}.
     *
     * @param confirmRevokeTestPass explicit confirmation required when the previous test pass has
     *                              to be revoked; ignored when no revocation is needed
     * @throws BizException 40000 unknown item / item never published / missing confirmation;
     *                      40001 the item is not {@code FAILED}/{@code GATE_FAILED} (or a lost
     *                      status CAS); 40006 the deployment unit is locked; 40009 the caller has
     *                      no data scope on the unit
     */
    public RerunResult rerun(long itemId, boolean confirmRevokeTestPass) {
        ReleaseItem item = loadItem(itemId);
        ScopeGuard.assertUnitInScope(item.unitId());
        requireTaskNotArchived(item);
        if (item.type() != UnitType.UNIT) {
            // The rerun decision is commit/module based and phase 1 only publishes online units;
            // a LIB rerun has a different pipeline shape and must not silently become ONLINE.
            throw new BizException(BizError.STATE_NOT_ALLOWED,
                "二方库发布流水线不在本期范围",
                Map.of("itemId", itemId, "type", item.type().name()));
        }
        requireRerunnable(item);
        ReleaseOrder order = loadLatestOrder(itemId);
        PipelineInstance previous = pipelineRepository.latestInstanceOf(itemId);

        // Without a baseline commit the decision is always a conservative full rerun (the planner
        // reports degraded), so there is no reason to call the engine first.
        String observedHead = item.baseCommit() == null
            ? null : validHeadOrNull(commitProbe.headSha(item.unitId(), item.sourceBranch()));
        RerunPlan plan = rerunPlanner.decide(item, observedHead, previous);

        boolean reusableImage = previous != null && previous.imageDigest() != null
            && !previous.imageDigest().isBlank();
        boolean full = plan.full() || plan.fromNode() == NodeType.BUILD || !reusableImage;
        boolean hasTestPass = item.lastTestPassBy() != null || item.lastTestPassAt() != null;
        boolean revoke = hasTestPass && (plan.revokeTestPass() || !reusableImage);
        // A full rebuild always starts at BUILD; a resume starts at the planned node.
        NodeType fromNode = full ? NodeType.BUILD : plan.fromNode();
        if (revoke && !confirmRevokeTestPass) {
            throw new BizException(BizError.PARAM_INVALID,
                "commit 已变化或产物不可复用，作废「测试通过」需二次确认"
                    + "（confirmRevokeTestPass=true）",
                Map.of("itemId", itemId, "requireConfirm", "REVOKE_TEST_PASS",
                    "fromNode", fromNode.name(), "full", full));
        }

        long unitId = item.unitId();
        if (!unitLockService.acquire(unitId, UnitLockService.UNBOUND_INSTANCE_ID)) {
            throw publishAppService.unitLocked(unitId, itemId);
        }
        try {
            Started started = transactionTemplate.execute(tx -> rerunInTransaction(
                itemId, order, previous, plan, full, revoke, fromNode, observedHead));
            Started startedResult = Objects.requireNonNull(started, "rerun result");
            // The instance is committed; start it outside the transaction, mirroring publish.
            try {
                if (autoStart) {
                    orchestrator.advance(startedResult.instanceId());
                }
            } catch (RuntimeException ex) {
                log.error("rerun pipeline did not start; reconcile enters its node later:"
                    + " instanceId={}", startedResult.instanceId(), ex);
            }
            RerunResult result = startedResult.result();
            Map<String, Object> after = new LinkedHashMap<>();
            after.put("itemStatus", full ? ItemStatus.BUILDING.name() : ItemStatus.TESTING.name());
            after.put("triggerType", full ? TriggerType.RERUN_FULL.name()
                : TriggerType.RERUN_FROM_NODE.name());
            after.put("pipelineNo", result.pipelineNo());
            after.put("full", full);
            after.put("revokedTestPass", revoke);
            after.put("degraded", plan.degraded());
            audit.record(AuditCommand.of(AuditAction.RERUN, order.publishNo(), order.publishNo(),
                    "重新发布：发布单 " + order.publishNo() + "（" + (full ? "全量重建" : "从节点 "
                        + fromNode.name() + " 恢复") + "）")
                .before(Map.of("itemStatus", item.status().name()))
                .after(after)
                .scopeUnitId(unitId)
                .build());
            return result;
        } catch (PublishAppService.LockLostException ex) {
            // The lock row is no longer ours (bindInstance reported it lost). Mirroring the
            // publish path, releasing here could delete a new holder's row/key, so the release
            // is skipped entirely.
            throw ex.failure();
        } catch (RuntimeException ex) {
            // The transaction rolled back, including the lock bind, so the reservation is still
            // the sentinel and this release is ownership-scoped and safe.
            unitLockService.release(unitId, UnitLockService.UNBOUND_INSTANCE_ID);
            throw ex;
        }
    }

    private Started rerunInTransaction(long itemId, ReleaseOrder order,
                                           PipelineInstance previous, RerunPlan plan,
                                           boolean full, boolean revoke, NodeType fromNode,
                                           String observedHead) {
        ReleaseItem current = loadItem(itemId);
        requireRerunnable(current);
        ItemEvent event = full ? ItemEvent.RERUN_FULL : ItemEvent.RERUN;
        ItemStatus next = ItemStateMachine.transit(current, event);
        Instant now = Instant.now();

        LambdaUpdateWrapper<ReleaseItemPO> update = Wrappers.<ReleaseItemPO>lambdaUpdate()
            .eq(ReleaseItemPO::getId, itemId)
            .eq(ReleaseItemPO::getRowVersion, current.rowVersion())
            .set(ReleaseItemPO::getStatus, next.name())
            .set(ReleaseItemPO::getRowVersion, current.rowVersion() + 1)
            .set(ReleaseItemPO::getUpdatedAt, now);
        if (revoke) {
            update.set(ReleaseItemPO::getLastTestPassBy, null)
                .set(ReleaseItemPO::getLastTestPassAt, null);
        }
        if (full) {
            // A full rebuild invalidates BOTH gates for the previous image: the test pass is gone
            // (C19: revoked with the user recorded) and the recorded check results no longer apply
            // to the new image (requirements §4.5, C16). gate_status is the check dimension (V19)
            // and starts as CHECK_INVALID until the checks run again; the test dimension restarts
            // at TEST_PENDING.
            update.set(ReleaseItemPO::getGateStatus, GateStatus.CHECK_INVALID.name())
                .set(ReleaseItemPO::getTestStatus, TestStatus.TEST_PENDING.name())
                .set(ReleaseItemPO::getTestFailReason, null);
        }
        if (full && observedHead != null) {
            // A full rebuild starts from the observed head; without this the old baseline would
            // keep every later rerun full forever.
            update.set(ReleaseItemPO::getBaseCommit, observedHead);
        }
        int rows = itemMapper.update(null, update);
        if (rows != 1) {
            throw new BizException(BizError.STATE_NOT_ALLOWED, "变更项已被并发重新发布",
                Map.of("itemId", itemId, "currentStatus", current.status().name()));
        }
        if (full) {
            // The previous image's check results cannot travel to the rebuilt image: mark them
            // invalid so the gate shows "结果失效，需要重新检查" instead of an old pass, and the
            // VERIFY node re-runs them. This never touches a production batch: a full rerun starts
            // at BUILD (C16).
            gateCheckService.invalidateAll(itemId, "代码有更新，全量重来：检查结果失效");
        }

        String imageDigest = full || previous == null ? null : previous.imageDigest();
        String commitSha = full || previous == null ? null : previous.commitSha();
        TriggerType triggerType = full ? TriggerType.RERUN_FULL : TriggerType.RERUN_FROM_NODE;
        PublishAppService.CreatedInstance created = publishAppService.createRerunInstance(order,
            current, current.unitId(), triggerType, fromNode, imageDigest, commitSha, previous);
        PipelineInstance instance = created.instance();

        if (revoke) {
            // The item update above cleared the pass; record the acting user for the audit trail.
            log.info("test pass revoked on rerun: itemId={}, actor={}, full={},"
                    + " pipelineNo={}, baseCommit={}",
                itemId, UserContext.current().userId(), full, instance.pipelineNo(),
                current.baseCommit());
        }
        log.info("release rerun started: itemId={}, publishNo={}, pipelineNo={}, full={},"
                + " fromNode={}, degraded={}, transactionRevokedTestPass={}",
            itemId, order.publishNo(), instance.pipelineNo(), full, fromNode,
            plan.degraded(), revoke);
        return new Started(new RerunResult(instance.pipelineNo(), full, revoke, plan.degraded()),
            created.instanceId());
    }

    /** Internal carrier: the business result plus the instance the caller must start. */
    private record Started(RerunResult result, long instanceId) {
    }

    /**
     * 40001 when the owning task is archived (FR-T-05, P5-B design §1: an archived task is
     * read-only). A rerun creates a new execution and moves the item, so it is refused before the
     * unit lock is taken; production rollback of the same unit stays allowed.
     */
    private void requireTaskNotArchived(ReleaseItem item) {
        if (item.taskId() == null) {
            throw new BizException(BizError.PARAM_INVALID, "变更项所属任务不存在",
                Map.of("itemId", item.id()));
        }
        ReleaseTaskPO task = releaseTaskMapper.selectById(item.taskId());
        if (task == null) {
            throw new BizException(BizError.PARAM_INVALID, "变更项所属任务不存在",
                Map.of("itemId", item.id(), "taskId", item.taskId()));
        }
        if (TaskStatus.ARCHIVED.name().equals(task.getStatus())) {
            throw new BizException(BizError.STATE_NOT_ALLOWED, "任务已归档，不能重新发布",
                Map.of("itemId", item.id(), "taskId", item.taskId()));
        }
    }

    private void requireRerunnable(ReleaseItem item) {
        if (!RERUNNABLE.contains(item.status())) {
            throw new BizException(BizError.STATE_NOT_ALLOWED,
                "变更项当前状态不允许重新发布",
                Map.of("itemId", item.id(), "status", item.status().name(),
                    "allowed", "FAILED/GATE_FAILED"));
        }
    }

    private ReleaseItem loadItem(long itemId) {
        ReleaseItemPO po = itemMapper.selectById(itemId);
        if (po == null) {
            throw new BizException(BizError.PARAM_INVALID, "变更项不存在", Map.of("id", itemId));
        }
        return po.toDomain();
    }

    private ReleaseOrder loadLatestOrder(long itemId) {
        ReleaseOrderPO po = orderMapper.selectOne(Wrappers.<ReleaseOrderPO>lambdaQuery()
            .eq(ReleaseOrderPO::getItemId, itemId)
            .orderByDesc(ReleaseOrderPO::getId)
            .last("LIMIT 1"));
        if (po == null) {
            throw new BizException(BizError.PARAM_INVALID, "变更项尚未发布，无法重新发布",
                Map.of("itemId", itemId));
        }
        return po.toDomain();
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * The observed branch head, or {@code null} when it is absent or longer than the
     * {@code VARCHAR(64)} baseline column. A commit id is never truncated: an over-long value
     * degrades the decision to a conservative full rerun instead of corrupting the baseline.
     */
    private static String validHeadOrNull(String value) {
        String trimmed = trimToNull(value);
        if (trimmed == null) {
            return null;
        }
        if (trimmed.length() > MAX_COMMIT_LENGTH) {
            log.warn("observed branch head is longer than {} chars; baseline not refreshed: length={}",
                MAX_COMMIT_LENGTH, trimmed.length());
            return null;
        }
        return trimmed;
    }

    /**
     * What the caller needs after a rerun: the new pipeline number, whether it is a full rebuild,
     * whether the previous test pass was revoked, and whether the decision used the conservative
     * whole-repository comparison (no {@code module_path}).
     */
    public record RerunResult(String pipelineNo, boolean full, boolean revokedTestPass,
                              boolean degraded) {
    }
}
