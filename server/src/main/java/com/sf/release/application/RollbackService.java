package com.sf.release.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sf.release.application.audit.AuditCommand;
import com.sf.release.application.audit.AuditService;
import com.sf.release.common.BizError;
import com.sf.release.common.BizException;
import com.sf.release.common.security.Permissions;
import com.sf.release.domain.audit.AuditAction;
import com.sf.release.domain.pipeline.PipelineFactory;
import com.sf.release.domain.pipeline.PipelineInstance;
import com.sf.release.domain.pipeline.PipelineOrchestrator;
import com.sf.release.domain.resource.EnvDe;
import com.sf.release.domain.resource.EnvKind;
import com.sf.release.domain.resource.TaskKind;
import com.sf.release.domain.resource.UnitType;
import com.sf.release.domain.rollback.RollbackRecord;
import com.sf.release.infra.lock.UnitLockService;
import com.sf.release.infra.repo.CodingTaskBindingMapper;
import com.sf.release.infra.repo.DeployUnitMapper;
import com.sf.release.infra.repo.EnvDeMapper;
import com.sf.release.infra.repo.ReleaseItemMapper;
import com.sf.release.infra.repo.ReleaseOrderMapper;
import com.sf.release.infra.repo.RollbackRecordMapper;
import com.sf.release.infra.repo.po.CodingTaskBindingPO;
import com.sf.release.infra.repo.po.DeployUnitPO;
import com.sf.release.infra.repo.po.EnvDePO;
import com.sf.release.infra.repo.po.ReleaseItemPO;
import com.sf.release.infra.repo.po.ReleaseOrderPO;
import com.sf.release.infra.repo.po.RollbackEnvKindRow;
import com.sf.release.infra.repo.po.RollbackRecordPO;
import com.sf.release.infra.repo.po.RollbackVersionRow;
import com.sf.release.infra.security.CurrentUser;
import com.sf.release.infra.security.ScopeGuard;
import com.sf.release.infra.security.UserContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The 生产回滚 use cases (rollback design section 2-5, P4-A): the version list derived from
 * successful production deployments, the single-step execution, and the record page/detail.
 *
 * <p><strong>Version list.</strong> There is no artifact index (Q4 is still open), so a
 * <em>rollback target</em> is a <em>fully deployed</em> forward release: an image digest whose
 * {@code PROD_DEPLOY} node reached {@code SUCCESS}. The <em>current online image</em> is the newest
 * successful production deploy of any trigger, including a rollback: after rolling back, the target
 * image is what production runs, so it is shown as current and repeating that rollback answers
 * 40000. The next ten rows are the fully deployed forward releases, newest first. A release that
 * failed mid-batch is deliberately not a target (only part of its image is online), while it can
 * still be what production currently runs — the page then offers the last full release as the
 * rollback target, which repairs the partial rollout. A unit without any successful production
 * deployment answers {@link BizError#STATE_NOT_ALLOWED} (40001): there is nothing to roll back to.
 *
 * <p><strong>Execution is one step (J13).</strong> {@code POST /rollbacks} creates the record and
 * the instance and starts them; there is no "request then execute" pair, because a rollback is a
 * stop-loss action. The caller needs {@code rollback:execute} (operations/administrator); a release
 * owner without it asks operations to press the button instead.
 *
 * <p><strong>Validation order</strong> (deliberately the design's): permission → unit exists and is
 * in data scope → a mandatory reason (1..200 chars) → the version list is non-empty (40001) and the
 * target is a historical version different from the current one (40000) → the unit has an enabled
 * {@code PROD_DEPLOY} binding and a production-family DE (40011) → a freeze only produces a warning
 * (never blocks) → the unit lock (40006).
 *
 * <p><strong>Transaction and lock.</strong> The unit lock is acquired before the transaction with
 * the unbound sentinel; the transaction inserts the record, creates the single-node reversed-batch
 * instance through {@link PipelineFactory#createRollback} and binds the instance to the lock. The
 * persist/bind step is shared with the publish use case ({@link PublishAppService#persistInstance}),
 * so the "lock lost" path is identical. After the commit the instance is started
 * ({@code pipeline.auto-start}; tests disable it to drive the instance themselves).
 *
 * <p><strong>Freeze.</strong> {@link FreezeService#active(long, Instant)} is asked only for the
 * warning text; the response carries it and the rollback runs anyway ("封板期提示不阻断", design
 * section 3). The production handler skips its boundary probe for a {@code ROLLBACK} instance.
 */
@Service
public class RollbackService {

    private static final Logger log = LoggerFactory.getLogger(RollbackService.class);

    /** The reason column is {@code VARCHAR(200)} (V25). */
    static final int MAX_REASON_LENGTH = 200;

    /** Page size ceiling, shared with the other paged read services. */
    static final int MAX_PAGE_SIZE = 100;

    /** Payload marker: the unit has no enabled {@code PROD_DEPLOY} binding. */
    static final String MISSING_PROD_DEPLOY_BINDING = "PROD_DEPLOY";

    /** Payload marker: the unit has no PROD/DR/BLUE DE. */
    static final String MISSING_PROD_DE = "PROD_DE";

    /** The must-read notes of the rollback drawer (mirror-only, manual code revert, shared DB). */
    static final List<String> WARNINGS = List.of(
        "回滚仅切换生产镜像，代码不会回退；如需回退代码，请到 CODING 手工处理",
        "同一数据库/中间件被多个单元共用时，回滚镜像可能造成版本不兼容，请先评估影响",
        "回滚立即执行，不走审批；请确认目标版本与影响范围");

    private final RollbackRecordMapper recordMapper;
    private final DeployUnitMapper unitMapper;
    private final ReleaseOrderMapper orderMapper;
    private final ReleaseItemMapper itemMapper;
    private final EnvDeMapper envDeMapper;
    private final CodingTaskBindingMapper bindingMapper;
    private final UnitLockService unitLockService;
    private final FreezeService freezeService;
    private final PipelineOrchestrator orchestrator;
    private final PublishAppService publishAppService;
    private final AuditService audit;
    private final TransactionTemplate transactionTemplate;

    /**
     * Whether executing a rollback starts the instance immediately. Enabled in every real
     * deployment; the test profile turns it off so tests can observe the created instance and drive
     * it with a stub engine. With it off the committed instance stays {@code PENDING} until the
     * reconcile pass enters its first node.
     */
    private final boolean autoStart;

    public RollbackService(RollbackRecordMapper recordMapper,
                           DeployUnitMapper unitMapper,
                           ReleaseOrderMapper orderMapper,
                           ReleaseItemMapper itemMapper,
                           EnvDeMapper envDeMapper,
                           CodingTaskBindingMapper bindingMapper,
                           UnitLockService unitLockService,
                           FreezeService freezeService,
                           PipelineOrchestrator orchestrator,
                           PublishAppService publishAppService,
                           AuditService audit,
                           @Value("${release.pipeline.auto-start:true}") boolean autoStart,
                           PlatformTransactionManager transactionManager) {
        this.recordMapper = recordMapper;
        this.unitMapper = unitMapper;
        this.orderMapper = orderMapper;
        this.itemMapper = itemMapper;
        this.envDeMapper = envDeMapper;
        this.bindingMapper = bindingMapper;
        this.unitLockService = unitLockService;
        this.freezeService = freezeService;
        this.orchestrator = orchestrator;
        this.publishAppService = publishAppService;
        this.audit = audit;
        this.autoStart = autoStart;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /**
     * The rollback drawer data of {@code unitId}: the current online version, up to ten historical
     * ones and the must-read notes.
     *
     * @throws BizException 40000 the unit does not exist; 40001 the unit has no successful
     *                      production deployment yet; 40009 the caller has no data scope on the unit
     */
    public VersionList versions(long unitId) {
        requireUnit(unitId);
        ScopeGuard.assertUnitInScope(unitId);
        List<RollbackVersionRow> rows = loadVersionRows(unitId);
        List<RollbackVersionView> views = versionViews(unitId, rows);
        return new VersionList(views.get(0), views.subList(1, views.size()), WARNINGS);
    }

    /**
     * Executes a rollback of {@code unitId} to {@code targetDigest}: reads the version list, takes
     * the unit lock, creates the record and the reversed-batch instance in one transaction, binds
     * the instance to the lock and starts it after the commit.
     *
     * @throws BizException 40000 the unit/target/reason is invalid (unknown unit, target not in the
     *                      history, target equals the current version, blank or oversized reason);
     *                      40001 no successful production deployment; 40006 the unit is locked;
     *                      40009 no {@code rollback:execute} or no data scope; 40011 the unit has no
     *                      production binding/DE
     */
    public ExecuteResult execute(Long unitId, String targetDigest, String reason) {
        CurrentUser user = UserContext.current();
        requirePerm(user, Permissions.ROLLBACK_EXECUTE);
        long id = requireUnitId(unitId);
        DeployUnitPO unit = requireUnit(id);
        ScopeGuard.assertUnitInScope(id);
        String why = requireReason(reason);

        List<RollbackVersionRow> rows = loadVersionRows(id);
        RollbackVersionRow current = rows.get(0);
        String target = requireTargetDigest(targetDigest);
        RollbackVersionRow targetVersion = rows.stream()
            .filter(row -> target.equals(row.getDigest()))
            .findFirst()
            .orElse(null);
        if (targetVersion == null) {
            throw new BizException(BizError.PARAM_INVALID,
                "目标版本不在该单元可回滚的历史版本中：" + target,
                Map.of("unitId", id, "targetDigest", target));
        }
        if (target.equals(current.getDigest())) {
            throw new BizException(BizError.PARAM_INVALID, "目标版本已是当前线上版本，无需回滚",
                Map.of("unitId", id, "targetDigest", target,
                    "currentDigest", current.getDigest()));
        }

        List<EnvDe> envDes = loadEnvDes(id);
        requireProductionConfiguration(id, envDes);
        ReleaseOrderPO sourceOrder = requireSourceOrder(current);
        ReleaseItemPO sourceItem = requireSourceItem(sourceOrder);
        String freezeWarning = freezeWarning(unit.getSubsystemId());

        if (!unitLockService.acquire(id, UnitLockService.UNBOUND_INSTANCE_ID)) {
            throw unitLocked(id, sourceItem.getId());
        }
        RollbackRecord record = RollbackRecord.running(id, sourceOrder.getId(),
            sourceItem.getId(), current.getPublishNo(), current.getDigest(), current.getCommitSha(),
            targetVersion.getDigest(), targetVersion.getPublishNo(), why, user.userId(),
            Instant.now());
        try {
            Executed executed = transactionTemplate.execute(
                tx -> executeInTransaction(sourceOrder, sourceItem, envDes, record));
            Executed result = Objects.requireNonNull(executed, "rollback result");
            if (autoStart) {
                startPipeline(result.instanceId());
            }
            log.info("production rollback started: rollbackId={}, unitId={}, from={}, to={},"
                    + " by={}, freezeWarning={}", result.recordId(), id, current.getDigest(),
                targetVersion.getDigest(), user.userId(), freezeWarning);
            Map<String, Object> before = new LinkedHashMap<>();
            before.put("digest", current.getDigest());
            before.put("publishNo", current.getPublishNo());
            Map<String, Object> after = new LinkedHashMap<>();
            after.put("digest", targetVersion.getDigest());
            after.put("publishNo", targetVersion.getPublishNo());
            after.put("rollbackId", result.recordId());
            after.put("pipelineNo", result.pipelineNo());
            audit.record(AuditCommand.of(AuditAction.ROLLBACK_EXECUTE,
                    String.valueOf(result.recordId()), targetVersion.getPublishNo(),
                    "执行回滚：部署单元 " + id + " 从 " + current.getDigest() + " 到 "
                        + targetVersion.getDigest())
                .before(before)
                .after(after)
                .reason(why)
                .scopeUnitId(id)
                .build());
            return new ExecuteResult(result.recordId(), result.pipelineNo(), id,
                targetVersion.getDigest(), freezeWarning);
        } catch (PublishAppService.LockLostException ex) {
            // The lock row is no longer ours (bindInstance reported it lost); releasing could
            // delete a new holder's row/key, so the release call is skipped entirely.
            throw ex.failure();
        } catch (RuntimeException ex) {
            unitLockService.release(id, UnitLockService.UNBOUND_INSTANCE_ID);
            throw ex;
        }
    }

    /**
     * One page of the rollback records, newest first. With {@code unitId} the page is restricted to
     * that unit (data scope enforced); without it the caller's data scope filters the page, and a
     * caller with no unit scope gets an empty page (fail-closed, like the other paged reads).
     *
     * @throws BizException 40000 an invalid page request; 40009 the unit is outside the data scope
     */
    public PageResult page(Long unitId, int pageNo, int pageSize) {
        requirePage(pageNo, pageSize);
        CurrentUser user = UserContext.current();
        if (unitId != null) {
            ScopeGuard.assertUnitInScope(unitId);
        }
        if (unitId == null && !user.allUnits() && user.scopeUnitIds().isEmpty()) {
            return new PageResult(0, pageNo, pageSize, List.of());
        }
        LambdaQueryWrapper<RollbackRecordPO> query = Wrappers.<RollbackRecordPO>lambdaQuery()
            .eq(unitId != null, RollbackRecordPO::getUnitId, unitId)
            .in(unitId == null && !user.allUnits(), RollbackRecordPO::getUnitId,
                user.scopeUnitIds())
            .orderByDesc(RollbackRecordPO::getCreatedAt)
            .orderByDesc(RollbackRecordPO::getId);
        Page<RollbackRecordPO> page = recordMapper.selectPage(new Page<>(pageNo, pageSize), query);
        List<RollbackRecordView> records = page.getRecords().stream()
            .map(po -> toView(po.toDomain()))
            .toList();
        return new PageResult(page.getTotal(), page.getCurrent(), page.getSize(), records);
    }

    /**
     * One rollback record.
     *
     * @throws BizException 40000 the record does not exist; 40009 no data scope on its unit
     */
    public RollbackRecordView detail(long id) {
        RollbackRecordPO po = recordMapper.selectById(id);
        if (po == null) {
            throw new BizException(BizError.PARAM_INVALID, "回滚记录不存在", Map.of("id", id));
        }
        ScopeGuard.assertUnitInScope(po.getUnitId() == null ? 0L : po.getUnitId());
        return toView(po.toDomain());
    }

    // ------------------------------------------------------------------ transaction

    private Executed executeInTransaction(ReleaseOrderPO sourceOrder, ReleaseItemPO sourceItem,
                                          List<EnvDe> envDes, RollbackRecord record) {
        RollbackRecordPO recordPo = RollbackRecordPO.of(record);
        recordMapper.insert(recordPo);
        PipelineInstance instance = PipelineFactory.createRollback(sourceOrder.toDomain(),
            sourceItem.toDomain(), envDes, record.targetDigest(), record.createdAt());
        long instanceId = publishAppService.persistInstance(instance, record.unitId(),
            sourceItem.getId());
        int rows = recordMapper.update(null, Wrappers.<RollbackRecordPO>lambdaUpdate()
            .eq(RollbackRecordPO::getId, recordPo.getId())
            .set(RollbackRecordPO::getInstanceId, instanceId)
            .set(RollbackRecordPO::getPipelineNo, instance.pipelineNo()));
        if (rows != 1) {
            throw new IllegalStateException(
                "rollback record " + recordPo.getId() + " disappeared during creation");
        }
        return new Executed(recordPo.getId(), instance.pipelineNo(), instanceId);
    }

    /**
     * Starts the freshly committed rollback by entering its first node. Runs outside the transaction
     * because the node handler calls the CI engine; a failure here must not undo the rollback: the
     * instance and record are committed, the reconcile pass enters an unstarted node later, and the
     * unit lock is released by the orchestrator once the instance is terminal.
     */
    private void startPipeline(long instanceId) {
        try {
            orchestrator.advance(instanceId);
        } catch (RuntimeException ex) {
            log.error("rollback pipeline did not start; reconcile will enter its first node:"
                + " instanceId={}", instanceId, ex);
        }
    }

    /** Internal carrier: the record id, its pipeline number and the instance to start. */
    private record Executed(long recordId, String pipelineNo, long instanceId) {
    }

    // ------------------------------------------------------------------ version reading

    /**
     * The current online version followed by up to ten rollback targets, with the batch count merged
     * from the companion aggregate query.
     *
     * <p>The current row is the newest successful production deploy of <em>any</em> trigger
     * (including a rollback: after rolling back, the target image is what production runs, so it is
     * the current version and repeating that rollback is refused). The history rows are fully
     * deployed forward releases only; a release that failed mid-batch is not a rollback target.
     *
     * @throws BizException 40001 the unit has no successful production deployment
     */
    private List<RollbackVersionRow> loadVersionRows(long unitId) {
        RollbackVersionRow current = recordMapper.selectCurrent(unitId);
        if (current == null) {
            throw new BizException(BizError.STATE_NOT_ALLOWED,
                "该部署单元没有成功的生产部署记录，暂不可回滚",
                Map.of("unitId", unitId, "hint", "先完成一次生产发布再回滚"));
        }
        List<RollbackVersionRow> rows = new ArrayList<>(11);
        rows.add(current);
        rows.addAll(recordMapper.selectHistory(unitId, current.getDigest()));
        Map<String, Integer> counts = new HashMap<>();
        for (RollbackVersionRow row : recordMapper.selectVersionBatchCounts(unitId)) {
            counts.put(row.getDigest(), row.getBatchCount());
        }
        for (RollbackVersionRow row : rows) {
            Integer count = counts.get(row.getDigest());
            row.setBatchCount(count == null ? 0 : count);
        }
        return rows;
    }

    /** Turns the rows into page views, attaching the environment chips of each digest. */
    private List<RollbackVersionView> versionViews(long unitId, List<RollbackVersionRow> rows) {
        Map<String, List<RollbackVersionView.Environment>> environments = new HashMap<>();
        for (RollbackEnvKindRow row : recordMapper.selectVersionEnvKinds(unitId)) {
            environments.computeIfAbsent(row.getDigest(), key -> new ArrayList<>())
                .add(environment(row.getEnvKind()));
        }
        List<RollbackVersionView> views = new ArrayList<>(rows.size());
        for (RollbackVersionRow row : rows) {
            views.add(new RollbackVersionView(row.getDigest(), row.getFinishedAt(),
                row.getPublishNo(), row.getExecutedBy(),
                row.getBatchCount() == null ? 0 : row.getBatchCount(),
                environments.getOrDefault(row.getDigest(), List.of())));
        }
        return views;
    }

    private static RollbackVersionView.Environment environment(String envKind) {
        EnvKind kind;
        try {
            kind = EnvKind.valueOf(envKind);
        } catch (RuntimeException ex) {
            return new RollbackVersionView.Environment(envKind, envKind);
        }
        return new RollbackVersionView.Environment(kind.name(), kind.label());
    }

    // ------------------------------------------------------------------ validation and helpers

    private static void requirePerm(CurrentUser user, String perm) {
        if (!user.hasPerm(perm)) {
            throw new BizException(BizError.FORBIDDEN, "无权执行该操作：" + perm,
                Map.of("perm", perm));
        }
    }

    private static long requireUnitId(Long unitId) {
        if (unitId == null || unitId <= 0) {
            throw new BizException(BizError.PARAM_INVALID, "部署单元不能为空",
                Map.of("unitId", String.valueOf(unitId)));
        }
        return unitId;
    }

    private DeployUnitPO requireUnit(long unitId) {
        DeployUnitPO unit = unitMapper.selectById(unitId);
        if (unit == null) {
            throw new BizException(BizError.PARAM_INVALID, "部署单元不存在",
                Map.of("unitId", unitId));
        }
        return unit;
    }

    private static String requireReason(String raw) {
        String reason = trimToNull(raw);
        if (reason == null) {
            throw new BizException(BizError.PARAM_INVALID, "回滚原因不能为空",
                Map.of("reason", "不能为空"));
        }
        if (reason.length() > MAX_REASON_LENGTH) {
            throw new BizException(BizError.PARAM_INVALID,
                "回滚原因长度不能超过 " + MAX_REASON_LENGTH, Map.of("reason", "长度超限"));
        }
        return reason;
    }

    private static String requireTargetDigest(String raw) {
        String digest = trimToNull(raw);
        if (digest == null) {
            throw new BizException(BizError.PARAM_INVALID, "目标版本不能为空",
                Map.of("targetDigest", "不能为空"));
        }
        return digest;
    }

    private List<EnvDe> loadEnvDes(long unitId) {
        return envDeMapper.selectList(Wrappers.<EnvDePO>lambdaQuery()
                .eq(EnvDePO::getUnitId, unitId)
                .orderByAsc(EnvDePO::getDeployBatchNo)
                .orderByAsc(EnvDePO::getId))
            .stream()
            .map(EnvDePO::toDomain)
            .toList();
    }

    /**
     * Fails with 40011 when the unit cannot deploy to production: no enabled {@code PROD_DEPLOY}
     * binding or no {@code PROD}/{@code DR}/{@code BLUE} DE. The rollback instance would otherwise
     * carry an empty {@code PROD_DEPLOY} node that can never complete.
     */
    private void requireProductionConfiguration(long unitId, List<EnvDe> envDes) {
        CodingTaskBindingPO binding = bindingMapper.selectOne(
            Wrappers.<CodingTaskBindingPO>lambdaQuery()
                .eq(CodingTaskBindingPO::getUnitId, unitId)
                .eq(CodingTaskBindingPO::getTaskKind, TaskKind.PROD_DEPLOY.name())
                .eq(CodingTaskBindingPO::getEnabled, true));
        boolean hasProductionDe = envDes.stream()
            .anyMatch(de -> de.envKind() == EnvKind.PROD
                || de.envKind() == EnvKind.DR
                || de.envKind() == EnvKind.BLUE);
        List<String> missing = new ArrayList<>(2);
        if (binding == null) {
            missing.add(MISSING_PROD_DEPLOY_BINDING);
        }
        if (!hasProductionDe) {
            missing.add(MISSING_PROD_DE);
        }
        if (!missing.isEmpty()) {
            throw new BizException(BizError.UNIT_NOT_CONFIGURED,
                "部署单元缺少生产回滚所需配置，缺少：" + String.join("、", missing),
                Map.of("unitId", unitId, "missing", List.copyOf(missing)));
        }
    }

    private ReleaseOrderPO requireSourceOrder(RollbackVersionRow current) {
        String publishNo = current.getPublishNo();
        if (publishNo == null || publishNo.isBlank()) {
            throw new BizException(BizError.STATE_NOT_ALLOWED,
                "当前线上版本没有对应的发布单，无法创建回滚实例",
                Map.of("digest", current.getDigest()));
        }
        ReleaseOrderPO order = orderMapper.selectOne(Wrappers.<ReleaseOrderPO>lambdaQuery()
            .eq(ReleaseOrderPO::getPublishNo, publishNo)
            .last("LIMIT 1"));
        if (order == null || order.getItemId() == null) {
            throw new BizException(BizError.STATE_NOT_ALLOWED,
                "当前线上版本的发布单不存在，无法创建回滚实例",
                Map.of("publishNo", publishNo));
        }
        return order;
    }

    private ReleaseItemPO requireSourceItem(ReleaseOrderPO sourceOrder) {
        ReleaseItemPO item = itemMapper.selectById(sourceOrder.getItemId());
        if (item == null) {
            throw new BizException(BizError.STATE_NOT_ALLOWED,
                "当前线上版本的变更项不存在，无法创建回滚实例",
                Map.of("itemId", sourceOrder.getItemId()));
        }
        if (!UnitType.UNIT.name().equals(item.getType())) {
            throw new BizException(BizError.STATE_NOT_ALLOWED,
                "回滚仅支持部署单元变更项", Map.of("itemId", item.getId(), "type", item.getType()));
        }
        return item;
    }

    /**
     * The freeze warning of the response, or {@code null}. A freeze never blocks a rollback
     * (rollback design section 3: 封板期提示不阻断), so this only explains the risk.
     */
    private String freezeWarning(Long subsystemId) {
        List<FreezeView> freezes = freezeService.active(subsystemId == null ? 0L : subsystemId,
            Instant.now());
        if (freezes.isEmpty()) {
            return null;
        }
        StringBuilder reasons = new StringBuilder();
        for (FreezeView freeze : freezes) {
            if (reasons.length() > 0) {
                reasons.append("；");
            }
            reasons.append(freeze.reason() == null ? "封板中" : freeze.reason());
        }
        return "生产封板生效：" + reasons + "；回滚仍将立即执行（不阻断）";
    }

    /**
     * Builds the 40006 business error with the observed holder when there is one. Mirrors the
     * publish error so the page explains the same way.
     */
    private BizException unitLocked(long unitId, long itemId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("unitId", unitId);
        payload.put("itemId", itemId);
        UnitLockService.LockHolder holder = unitLockService.holder(unitId);
        if (holder != null && holder.instanceId() > 0) {
            payload.put("instanceId", holder.instanceId());
        }
        if (holder != null && holder.acquiredAt() != null) {
            payload.put("acquiredAt", holder.acquiredAt());
        }
        return new BizException(BizError.UNIT_LOCKED, "部署单元已被占用", payload);
    }

    private static void requirePage(int pageNo, int pageSize) {
        if (pageNo < 1) {
            throw new BizException(BizError.PARAM_INVALID, "页码必须从 1 开始",
                Map.of("pageNo", pageNo));
        }
        if (pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
            throw new BizException(BizError.PARAM_INVALID,
                "每页条数必须在 1 到 " + MAX_PAGE_SIZE + " 之间", Map.of("pageSize", pageSize));
        }
    }

    private static RollbackRecordView toView(RollbackRecord record) {
        return new RollbackRecordView(record.id(), record.unitId(), record.instanceId(),
            record.pipelineNo(), record.sourceOrderId(), record.sourceItemId(),
            record.sourcePublishNo(), record.sourceDigest(), record.sourceCommit(),
            record.targetDigest(), record.targetPublishNo(), record.reason(), record.requestedBy(),
            record.status().name(), record.status().label(), record.failureReason(),
            record.createdAt(), record.finishedAt());
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** The drawer data: the current version, up to ten historical ones and the must-read notes. */
    public record VersionList(RollbackVersionView current, List<RollbackVersionView> history,
                              List<String> warnings) {

        public VersionList {
            history = history == null ? List.of() : List.copyOf(history);
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
        }
    }

    /** What {@code POST /rollbacks} answers: the record, its pipeline and the freeze warning. */
    public record ExecuteResult(long rollbackId, String pipelineNo, long unitId,
                                String targetDigest, String freezeWarning) {
    }

    /** Page response shape shared by the paged read services. */
    public record PageResult(long total, long pageNo, long pageSize,
                             List<RollbackRecordView> records) {

        public PageResult {
            records = records == null ? List.of() : List.copyOf(records);
        }
    }
}