package com.sf.release.application;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sf.release.application.audit.AuditCommand;
import com.sf.release.application.audit.AuditService;
import com.sf.release.application.settings.SettingsService;
import com.sf.release.common.BizError;
import com.sf.release.common.BizException;
import com.sf.release.domain.audit.AuditAction;
import com.sf.release.domain.engine.CicdEngine;
import com.sf.release.domain.engine.EngineException;
import com.sf.release.domain.engine.GateCheckCmd;
import com.sf.release.domain.engine.GateCheckOutcome;
import com.sf.release.domain.engine.GateCheckReport;
import com.sf.release.domain.engine.TriggerResult;
import com.sf.release.domain.gate.GateCheck;
import com.sf.release.domain.gate.GateCheckStatus;
import com.sf.release.domain.gate.Severity;
import com.sf.release.domain.pipeline.CheckType;
import com.sf.release.domain.pipeline.NodeTask;
import com.sf.release.domain.pipeline.NodeTaskStatus;
import com.sf.release.domain.pipeline.NodeType;
import com.sf.release.domain.pipeline.PipelineInstance;
import com.sf.release.domain.pipeline.TaskType;
import com.sf.release.domain.pipeline.UnitPipelineConfig;
import com.sf.release.domain.pipeline.port.PipelineRepository;
import com.sf.release.infra.repo.GateCheckMapper;
import com.sf.release.infra.repo.NodeTaskMapper;
import com.sf.release.infra.repo.ReleaseItemMapper;
import com.sf.release.infra.repo.ReleaseOrderMapper;
import com.sf.release.infra.repo.po.GateCheckPO;
import com.sf.release.infra.repo.po.NodeTaskPO;
import com.sf.release.infra.repo.po.ReleaseItemPO;
import com.sf.release.infra.repo.po.ReleaseOrderPO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Triggers, polls and records the seven gate checks of a 变更项 (requirements §4.3/§7.1, tech
 * design §7.1).
 *
 * <p><strong>Who starts what.</strong> The {@code VERIFY} node handler calls
 * {@link #startChecks(PipelineInstance)} once, after the test deployment succeeded: image-level
 * checks and environment-level checks share that entry point in phase 1 (the design's earlier
 * image-level trigger is an optimisation, not a correctness requirement — the decision is only
 * made after the test deployment anyway).
 *
 * <p><strong>Checks are never inferred from an empty response.</strong> An engine outage, an
 * unmapped status word or a failed check without a defect level is recorded as
 * {@link GateCheckStatus#UNKNOWN}, which the gate reads as "not finished". The platform would
 * rather wait than admit a release nobody checked.
 *
 * <p><strong>One row per check.</strong> The table's unique key is
 * {@code (item_id, check_kind, stage)}, so starting a check upserts instead of inserting. A
 * finished image-level result survives a rerun that reuses the image (requirements §4.5); a
 * finished environment-level result does not survive a new deployment, because the environment it
 * measured is gone (see {@link #startChecks}).
 */
@Service
public class GateCheckService {

    private static final Logger log = LoggerFactory.getLogger(GateCheckService.class);

    /** Per-check deadline (decision D6): a check that cannot finish in 30 minutes times out. */
    public static final Duration CHECK_TIMEOUT = Duration.ofMinutes(30);

    /** A polled check is re-observed at most every 30s (the orchestrator round is 30s). */
    static final Duration POLL_INTERVAL = Duration.ofSeconds(30);

    private final CicdEngine engine;
    private final GateCheckMapper checkMapper;
    private final NodeTaskMapper taskMapper;
    private final ReleaseItemMapper itemMapper;
    private final ReleaseOrderMapper orderMapper;
    private final PipelineRepository repository;
    private final AuditService audit;
    /** Gate switches; {@code null} in the legacy constructor means "every check enabled". */
    private final SettingsService settings;
    private final TransactionTemplate transactionTemplate;

    /**
     * Production constructor: the gate switches are read on every trigger/evaluation, so disabling
     * a check stops new trigger rows on the very next round (P6-B design §2.1).
     */
    @Autowired
    public GateCheckService(CicdEngine engine,
                            GateCheckMapper checkMapper,
                            NodeTaskMapper taskMapper,
                            ReleaseItemMapper itemMapper,
                            ReleaseOrderMapper orderMapper,
                            PipelineRepository repository,
                            AuditService audit,
                            PlatformTransactionManager transactionManager,
                            SettingsService settings) {
        this.engine = Objects.requireNonNull(engine, "engine must not be null");
        this.checkMapper = Objects.requireNonNull(checkMapper, "checkMapper must not be null");
        this.taskMapper = Objects.requireNonNull(taskMapper, "taskMapper must not be null");
        this.itemMapper = Objects.requireNonNull(itemMapper, "itemMapper must not be null");
        this.orderMapper = Objects.requireNonNull(orderMapper, "orderMapper must not be null");
        this.repository = Objects.requireNonNull(repository, "repository must not be null");
        this.audit = Objects.requireNonNull(audit, "audit must not be null");
        this.settings = settings;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /**
     * Test/legacy constructor without the settings seam: every check is treated as enabled, which
     * is exactly the pre-P6-B behaviour the tests that build this service by hand expect.
     */
    public GateCheckService(CicdEngine engine,
                            GateCheckMapper checkMapper,
                            NodeTaskMapper taskMapper,
                            ReleaseItemMapper itemMapper,
                            ReleaseOrderMapper orderMapper,
                            PipelineRepository repository,
                            AuditService audit,
                            PlatformTransactionManager transactionManager) {
        this(engine, checkMapper, taskMapper, itemMapper, orderMapper, repository, audit,
            transactionManager, null);
    }

    // ------------------------------------------------------------------ gate switches

    /**
     * The enabled check types read from the platform settings on every call (no cache: a save must
     * be effective on the next trigger/evaluation). A read failure is handled by the settings
     * service, which falls back to "all checks enabled".
     */
    public Set<CheckType> enabledChecks() {
        if (settings == null) {
            return Collections.unmodifiableSet(EnumSet.allOf(CheckType.class));
        }
        return settings.enabledChecks();
    }

    /**
     * Starts every check that has no usable result yet, for {@code instance}.
     *
     * <p>A finished check is kept when it still applies: image-level results are bound to the image
     * (a from-node rerun reuses it), environment-level results are bound to the deployment, so a
     * result recorded by a <em>different</em> instance is re-run.
     *
     * <p><strong>Disabled checks are skipped.</strong> The platform's gate switches decide which
     * checks a new run should start (P6-B design §2.1); historical rows of a disabled check stay in
     * the table but are ignored by the gate, so no new row is created for them.
     *
     * @return the number of checks actually triggered
     */
    public int startChecks(PipelineInstance instance) {
        if (instance == null || instance.id() == null) {
            return 0;
        }
        // P6-C: the platform switches intersected with the unit's environment-check switch. A
        // disabled environment check is not triggered for a new run (its historical rows stay
        // readable and are ignored by the gate evaluation).
        UnitPipelineConfig config = repository.pipelineConfigOf(instance.id());
        Set<CheckType> enabled = UnitPipelineConfig.effectiveChecks(enabledChecks(), config);
        CheckContext context = contextOf(instance);
        if (context.missingObject() != null) {
            // Never start a check without the object it must scan: an empty scan would either fail
            // the release for the wrong reason or pass it for no reason.
            String reason = "STATE_NOT_ALLOWED: 检查缺少扫描对象（" + context.missingObject() + "）";
            log.warn("gate checks not started: instanceId={}, reason={}", instance.id(), reason);
            for (CheckType type : CheckType.values()) {
                if (enabled.contains(type)) {
                    upsertUnknown(instance, type, context, reason);
                }
            }
            return 0;
        }
        int started = 0;
        for (CheckType type : CheckType.values()) {
            if (!enabled.contains(type)) {
                continue;
            }
            GateCheck existing = find(context.itemId(), type);
            if (!needsStart(existing, instance, type)) {
                continue;
            }
            start(instance, type, context, existing);
            started++;
        }
        log.info("gate checks started: instanceId={}, itemId={}, started={}, enabled={}",
            instance.id(), context.itemId(), started, enabled);
        return started;
    }

    /**
     * Polls the checks the engine still owes us and records what it reports.
     *
     * @return the item ids whose result changed, so the caller re-evaluates those gates
     */
    public List<Long> pollDue(Instant now, int limit) {
        List<GateCheckPO> due = checkMapper.selectDue(now.minus(POLL_INTERVAL), now, limit);
        Set<Long> changedItems = new LinkedHashSet<>();
        for (GateCheckPO po : due) {
            GateCheck check = po.toDomain();
            if (check == null) {
                log.warn("gate check row has an unknown check_kind, skipped: id={}", po.getId());
                continue;
            }
            try {
                if (applyPolledResult(check, now)) {
                    changedItems.add(check.itemId());
                }
            } catch (RuntimeException ex) {
                // One bad check must not stop the round; it stays due and is retried.
                log.error("gate check poll failed: checkId={}, kind={}", check.id(),
                    check.checkType(), ex);
            }
        }
        return List.copyOf(changedItems);
    }

    /**
     * Re-runs one check on request ("检查项可单独刷新", requirements §4.3). The row is reset and
     * triggered again; the gate re-evaluates afterwards through the normal path.
     *
     * @param kind the check type name ({@code SONAR}, {@code AUTO_TEST}, …); an unknown name is a
     *             40000
     * @throws BizException 40000 unknown item or check kind; 40001 the item has no instance yet
     */
    public void refresh(long itemId, String kind, long instanceId) {
        CheckType type = CheckType.fromName(kind);
        if (type == null) {
            throw new BizException(BizError.PARAM_INVALID, "检查项不合法：" + kind,
                Map.of("kind", kind == null ? "" : kind));
        }
        ReleaseItemPO item = itemMapper.selectById(itemId);
        if (item == null) {
            throw new BizException(BizError.PARAM_INVALID, "变更项不存在", Map.of("itemId", itemId));
        }
        PipelineInstance instance = instanceId > 0 ? repository.loadInstance(instanceId)
            : repository.latestInstanceOf(itemId);
        if (instance == null) {
            throw new BizException(BizError.STATE_NOT_ALLOWED,
                "变更项还没有流水线实例，无法刷新检查", Map.of("itemId", itemId));
        }
        GateCheck existing = find(itemId, type);
        GateCheck reset = new GateCheck(existing == null ? null : existing.id(), itemId,
            contextOf(instance).orderId(), instance.id(), type, GateCheckStatus.PENDING,
            Severity.NONE, 0, null, null, null, null,
            existing == null ? 0 : existing.attempt(), null, null, null);
        save(reset);
        start(instance, type, contextOf(instance), existing);
        GateCheck refreshed = find(itemId, type);
        Map<String, Object> before = new LinkedHashMap<>();
        before.put("checkKind", type.name());
        before.put("status", existing == null ? null : existing.status().name());
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("checkKind", type.name());
        after.put("status", refreshed == null ? GateCheckStatus.UNKNOWN.name()
            : refreshed.status().name());
        after.put("attempt", refreshed == null ? 0 : refreshed.attempt());
        audit.record(AuditCommand.of(AuditAction.GATE_REFRESH, String.valueOf(itemId),
                item.getCrid(), "刷新检查：" + type.label() + "（变更项 " + item.getCrid() + "）")
            .before(before)
            .after(after)
            .scopeUnitId(item.getUnitId())
            .build());
    }

    /**
     * Marks every check of {@code itemId} invalid (commit/image changed, redeploy). The gate then
     * reads {@code INVALID} and blocks until the checks run again.
     *
     * @return the number of rows invalidated
     */
    public int invalidateAll(long itemId, String reason) {
        List<GateCheck> checks = checks(itemId);
        Instant now = Instant.now();
        int invalidated = 0;
        for (GateCheck check : checks) {
            if (check.status() == GateCheckStatus.INVALID) {
                continue;
            }
            GateCheck invalid = new GateCheck(check.id(), check.itemId(), check.orderId(),
                check.instanceId(), check.checkType(), GateCheckStatus.INVALID, check.severity(),
                check.issues(), check.scannedObject(), check.externalId(), check.reportUrl(),
                reason == null ? "检查结果已失效" : reason, check.attempt(), check.startedAt(),
                now, check.deadlineAt());
            save(invalid);
            syncTask(invalid);
            invalidated++;
        }
        log.info("gate checks invalidated: itemId={}, count={}, reason={}", itemId, invalidated,
            reason);
        return invalidated;
    }

    /**
     * Marks the checks that have not produced a result yet ({@code PENDING}/{@code RUNNING})
     * {@code INVALID} (P5-B design §3): the release they belonged to has been exited or closed, so a
     * result that arrives later must not be read as "this release passed".
     *
     * <p>Only unfinished rows are touched. A recorded {@code PASS}/{@code FAIL} is history — the same
     * rule {@link #invalidateAll} follows for a rebuild — and stays readable. The down-stream node task
     * mirror is left alone: no node runs any more, and the status of the check rows is what the gate
     * reads.
     *
     * @return the number of rows invalidated
     */
    public int invalidateUnfinished(long itemId, String reason) {
        Instant now = Instant.now();
        String summary = reason == null || reason.isBlank() ? "变更项已关闭，检查结果失效" : reason;
        int rows = checkMapper.update(null, Wrappers.<GateCheckPO>lambdaUpdate()
            .eq(GateCheckPO::getItemId, itemId)
            .in(GateCheckPO::getStatus, GateCheckStatus.PENDING.name(),
                GateCheckStatus.RUNNING.name())
            .set(GateCheckPO::getStatus, GateCheckStatus.INVALID.name())
            .set(GateCheckPO::getSummary, summary)
            .set(GateCheckPO::getFinishedAt, now)
            .set(GateCheckPO::getUpdatedAt, now));
        if (rows > 0) {
            log.info("unfinished gate checks invalidated: itemId={}, count={}, reason={}", itemId,
                rows, summary);
        }
        return rows;
    }

    /** Every check row of one item, ordered by stage then kind. */
    public List<GateCheck> checks(long itemId) {
        List<GateCheck> rows = new ArrayList<>();
        for (GateCheckPO po : checkMapper.selectByItem(itemId)) {
            GateCheck check = po.toDomain();
            if (check != null) {
                rows.add(check);
            }
        }
        return List.copyOf(rows);
    }

    // ------------------------------------------------------------------ start / poll internals

    private GateCheck find(long itemId, CheckType type) {
        for (GateCheck check : checks(itemId)) {
            if (check.checkType() == type) {
                return check;
            }
        }
        return null;
    }

    /**
     * Whether a check must be (re)started. A running check stays; a finished one stays too, unless
     * it is invalid, or it is an environment-level result recorded by another instance (that
     * deployment no longer exists).
     */
    private static boolean needsStart(GateCheck existing, PipelineInstance instance,
                                      CheckType type) {
        if (existing == null) {
            return true;
        }
        if (existing.status() == GateCheckStatus.RUNNING) {
            return false;
        }
        if (existing.status() == GateCheckStatus.INVALID || existing.status().pendingResult()) {
            return true;
        }
        boolean otherDeployment = type.stage() == CheckType.CheckStage.ENV
            && existing.instanceId() != null
            && !existing.instanceId().equals(instance.id());
        return otherDeployment;
    }

    private void start(PipelineInstance instance, CheckType type, CheckContext context,
                       GateCheck existing) {
        Instant now = Instant.now();
        GateCheckCmd cmd = new GateCheckCmd(context.unitId(),
            type.stage() == CheckType.CheckStage.IMAGE ? context.buildTaskId() : context.deployTaskId(),
            type, context.commitSha(), context.imageDigest(),
            type.stage() == CheckType.CheckStage.IMAGE ? null : context.deName(),
            context.pipelineNo(), existing == null ? 0 : existing.attempt() + 1);
        GateCheck starting = new GateCheck(existing == null ? null : existing.id(),
            context.itemId(), context.orderId(), instance.id(), type, GateCheckStatus.RUNNING,
            Severity.NONE, 0, context.scannedObjectFor(type), null, null, null,
            cmd.attempt(), now, null, now.plus(CHECK_TIMEOUT));
        try {
            TriggerResult trigger = engine.startCheck(cmd);
            GateCheck running = new GateCheck(starting.id(), starting.itemId(),
                starting.orderId(), starting.instanceId(), type, GateCheckStatus.RUNNING,
                Severity.NONE, 0, starting.scannedObject(), trigger.externalId(),
                trigger.externalUrl(), null, starting.attempt(), now, null,
                starting.deadlineAt());
            save(running);
            syncTask(running);
        } catch (EngineException ex) {
            // Not a failure of the code under test: the check simply did not run. Recording it as
            // UNKNOWN keeps the gate blocked without blaming the release.
            String reason = "检查未能启动（" + errorName(ex) + "）：" + messageOf(ex);
            recordUnknown(context.itemId(), type, instance.id(), context, starting.attempt(),
                reason);
            log.warn("gate check could not start: itemId={}, check={}, reason={}",
                context.itemId(), type, reason);
        }
    }

    private void upsertUnknown(PipelineInstance instance, CheckType type, CheckContext context,
                               String reason) {
        recordUnknown(context.itemId(), type, instance.id(), context, 0, reason);
    }

    private void recordUnknown(long itemId, CheckType type, long instanceId, CheckContext context,
                               int attempt, String reason) {
        if (itemId <= 0) {
            // No 变更项 behind the instance (daily pipeline): there is no gate to record against.
            log.warn("gate check not recorded, the instance has no 变更项: instanceId={}, check={},"
                + " reason={}", instanceId, type, reason);
            return;
        }
        GateCheck existing = find(itemId, type);
        Instant now = Instant.now();
        GateCheck unknown = new GateCheck(existing == null ? null : existing.id(), itemId,
            context.orderId(), instanceId == 0 ? (existing == null ? null : existing.instanceId())
                : instanceId,
            type, GateCheckStatus.UNKNOWN, existing == null ? Severity.NONE : existing.severity(),
            existing == null ? 0 : existing.issues(),
            existing == null ? context.scannedObjectFor(type) : existing.scannedObject(),
            null, null, reason, Math.max(attempt, existing == null ? 0 : existing.attempt()),
            existing == null ? now : existing.startedAt(), null, null);
        save(unknown);
        syncTask(unknown);
    }

    /**
     * Queries one check and records the answer.
     *
     * @return {@code true} when the recorded result changed (the gate must be re-evaluated)
     */
    private boolean applyPolledResult(GateCheck check, Instant now) {
        GateCheckReport report;
        try {
            report = engine.queryCheck(check.externalId(), check.checkType());
        } catch (EngineException ex) {
            return recordUnfinished(check, now,
                "查询检查结果失败（" + errorName(ex) + "）：" + messageOf(ex));
        } catch (RuntimeException ex) {
            return recordUnfinished(check, now, "查询检查结果失败：" + messageOf(ex));
        }
        boolean deadlinePassed = check.deadlineAt() != null && !check.deadlineAt().isAfter(now);
        GateCheckStatus next = switch (report.outcome()) {
            case RUNNING -> deadlinePassed ? GateCheckStatus.TIMEOUT : GateCheckStatus.RUNNING;
            case SUCCESS -> report.severity() == null ? GateCheckStatus.UNKNOWN
                : GateCheckStatus.PASS;
            case FAILED -> report.severity() == null ? GateCheckStatus.UNKNOWN
                : GateCheckStatus.FAIL;
            case UNKNOWN -> GateCheckStatus.UNKNOWN;
        };
        String summary = report.summary();
        if (next == GateCheckStatus.TIMEOUT) {
            summary = "检查超时（" + CHECK_TIMEOUT.toMinutes() + " 分钟未完成）";
        } else if (next == GateCheckStatus.UNKNOWN && report.outcome() == GateCheckOutcome.SUCCESS) {
            summary = "检查通过但引擎未报告缺陷等级，无法判定";
        } else if (next == GateCheckStatus.UNKNOWN && report.outcome() == GateCheckOutcome.FAILED) {
            summary = "检查未通过但引擎未报告缺陷等级，无法判定";
        }
        GateCheck updated = new GateCheck(check.id(), check.itemId(), check.orderId(),
            check.instanceId(), check.checkType(), next,
            report.severity() == null ? check.severity() : report.severity(),
            report.issues() == 0 ? check.issues() : report.issues(),
            check.scannedObject(), check.externalId(),
            report.reportUrl() == null ? check.reportUrl() : report.reportUrl(),
            summary == null ? check.summary() : summary, check.attempt(), check.startedAt(),
            next.terminal() && next != GateCheckStatus.INVALID ? now : check.finishedAt(),
            check.deadlineAt());
        save(updated);
        syncTask(updated);
        return check.status() != next;
    }

    /** Keeps a check open without inventing a verdict; returns whether anything changed. */
    private boolean recordUnfinished(GateCheck check, Instant now, String reason) {
        GateCheckStatus next = check.deadlineAt() != null && !check.deadlineAt().isAfter(now)
            ? GateCheckStatus.TIMEOUT : GateCheckStatus.UNKNOWN;
        String summary = next == GateCheckStatus.TIMEOUT
            ? "检查超时（" + CHECK_TIMEOUT.toMinutes() + " 分钟未完成）" : reason;
        GateCheck updated = new GateCheck(check.id(), check.itemId(), check.orderId(),
            check.instanceId(), check.checkType(), next, check.severity(), check.issues(),
            check.scannedObject(), check.externalId(), check.reportUrl(), summary,
            check.attempt(), check.startedAt(),
            next == GateCheckStatus.TIMEOUT ? now : check.finishedAt(), check.deadlineAt());
        save(updated);
        syncTask(updated);
        return check.status() != next;
    }

    // ------------------------------------------------------------------ persistence

    private void save(GateCheck check) {
        transactionTemplate.execute(tx -> {
            Instant now = Instant.now();
            GateCheckPO existing = findRow(check.itemId(), check.checkType());
            if (existing == null) {
                GateCheckPO po = GateCheckPO.of(check);
                po.setCreatedAt(check.startedAt() == null ? now : check.startedAt());
                po.setUpdatedAt(now);
                po.setDurationMs(durationOf(check));
                checkMapper.insert(po);
                return null;
            }
            // Explicit .set() calls: a lambda update skips null values by default, and a reset
            // (refresh, invalidation) has to CLEAR the previous engine id and timestamps.
            checkMapper.update(null, Wrappers.<GateCheckPO>lambdaUpdate()
                .eq(GateCheckPO::getId, existing.getId())
                .set(GateCheckPO::getItemId, check.itemId())
                .set(GateCheckPO::getOrderId, check.orderId())
                .set(GateCheckPO::getInstanceId, check.instanceId())
                .set(GateCheckPO::getCheckKind, check.checkType().name())
                .set(GateCheckPO::getStage, check.checkType().stage().name())
                .set(GateCheckPO::getStatus, check.status().name())
                .set(GateCheckPO::getSeverity, check.severity().name())
                .set(GateCheckPO::getIssues, check.issues())
                .set(GateCheckPO::getScannedObject, check.scannedObject())
                .set(GateCheckPO::getExternalId, check.externalId())
                .set(GateCheckPO::getReportUrl, check.reportUrl())
                .set(GateCheckPO::getSummary, check.summary())
                .set(GateCheckPO::getAttempt, check.attempt())
                .set(GateCheckPO::getStartedAt, check.startedAt())
                .set(GateCheckPO::getFinishedAt, check.finishedAt())
                .set(GateCheckPO::getDurationMs, durationOf(check))
                .set(GateCheckPO::getDeadlineAt, check.deadlineAt())
                .set(GateCheckPO::getUpdatedAt, now));
            return null;
        });
    }

    private static Long durationOf(GateCheck check) {
        if (check.startedAt() == null || check.finishedAt() == null) {
            return null;
        }
        return Math.max(0L, Duration.between(check.startedAt(), check.finishedAt()).toMillis());
    }

    private GateCheckPO findRow(long itemId, CheckType type) {
        return checkMapper.selectOne(Wrappers.<GateCheckPO>lambdaQuery()
            .eq(GateCheckPO::getItemId, itemId)
            .eq(GateCheckPO::getCheckKind, type.name())
            .eq(GateCheckPO::getStage, type.stage().name()));
    }

    /**
     * Mirrors the check onto the pre-generated {@code VERIFY/CHECK} task, so the release page shows
     * a running/finished check without a second query. {@code CHECK} tasks never gate the node
     * (the orchestrator ignores them), so this is presentation state, written without events.
     */
    private void syncTask(GateCheck check) {
        if (check.instanceId() == null) {
            return;
        }
        NodeTaskStatus status = switch (check.status()) {
            case PENDING, INVALID -> NodeTaskStatus.PENDING;
            case RUNNING, UNKNOWN -> NodeTaskStatus.WAITING_EXTERNAL;
            case PASS -> NodeTaskStatus.SUCCESS;
            case FAIL, TIMEOUT -> NodeTaskStatus.FAILED;
        };
        try {
            taskMapper.update(null, Wrappers.<NodeTaskPO>lambdaUpdate()
                .eq(NodeTaskPO::getInstanceId, check.instanceId())
                .eq(NodeTaskPO::getNodeType, NodeType.VERIFY.name())
                .eq(NodeTaskPO::getTaskType, TaskType.CHECK.name())
                .eq(NodeTaskPO::getTargetId, (long) check.checkType().code())
                .set(NodeTaskPO::getStatus, status.name())
                .set(NodeTaskPO::getExternalId, check.externalId())
                .set(NodeTaskPO::getExternalUrl, check.reportUrl())
                .set(NodeTaskPO::getResultSummary, check.summary())
                .set(NodeTaskPO::getDeadlineAt, check.deadlineAt())
                .set(NodeTaskPO::getStartedAt, check.startedAt())
                .set(NodeTaskPO::getFinishedAt,
                    status == NodeTaskStatus.SUCCESS || status == NodeTaskStatus.FAILED
                        ? check.finishedAt() : null)
                .set(NodeTaskPO::getUpdatedAt, Instant.now()));
        } catch (RuntimeException ex) {
            log.warn("could not mirror check {} onto its node task (instanceId={}): {}",
                check.checkType(), check.instanceId(), messageOf(ex));
        }
    }

    // ------------------------------------------------------------------ context

    /** The scan targets of one instance: what each check needs to run. */
    private CheckContext contextOf(PipelineInstance instance) {
        PipelineRepository.BuildContext build = repository.buildContextOf(instance.id());
        PipelineRepository.DeployContext deploy = repository.deployContextOf(instance.id());
        PipelineRepository.InstanceContext owner = repository.contextOf(instance.id());
        String deName = deploy.targets().isEmpty() ? null : deploy.targets().get(0).deName();
        long itemId = itemIdOf(owner.orderId());
        return new CheckContext(itemId, owner.orderId(), build.unitId(), build.planId(),
            deploy.planId(), deName, instance.commitSha(), instance.imageDigest(),
            instance.pipelineNo());
    }

    /** The item of a release order; {@code 0} when the instance has no order (daily pipeline). */
    private long itemIdOf(long orderId) {
        if (orderId <= 0) {
            return 0L;
        }
        ReleaseOrderPO order = orderMapper.selectById(orderId);
        return order == null || order.getItemId() == null ? 0L : order.getItemId();
    }

    /** {@code ENGINE_UNAVAILABLE}/{@code ENGINE_TIMEOUT} of an engine failure. */
    private static String errorName(EngineException ex) {
        return ex.getError() == null ? "ENGINE_UNAVAILABLE" : ex.getError().name();
    }

    private static String messageOf(Throwable ex) {
        String message = ex.getMessage();
        return message == null || message.isBlank() ? ex.getClass().getSimpleName() : message;
    }

    /** What a check scans; see {@link #contextOf(PipelineInstance)}. */
    record CheckContext(long itemId, long orderId, long unitId, String buildTaskId,
                        String deployTaskId, String deName, String commitSha, String imageDigest,
                        String pipelineNo) {

        String scannedObjectFor(CheckType type) {
            if (type.stage() == CheckType.CheckStage.ENV) {
                return deName;
            }
            return imageDigest != null ? imageDigest : commitSha;
        }

        /** The first missing scan coordinate, or {@code null} when everything is known. */
        String missingObject() {
            if (imageDigest == null && commitSha == null && deName == null) {
                return "没有镜像、commit 或测试环境";
            }
            if (buildTaskId == null) {
                return "部署单元未绑定构建任务";
            }
            if (deployTaskId == null) {
                return "部署单元未绑定测试部署任务";
            }
            return null;
        }
    }
}