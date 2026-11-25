package com.sf.release.application;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sf.release.application.audit.AuditCommand;
import com.sf.release.application.audit.AuditService;
import com.sf.release.application.settings.SettingsService;
import com.sf.release.common.BizError;
import com.sf.release.common.BizException;
import com.sf.release.domain.audit.AuditAction;
import com.sf.release.domain.pipeline.NodeTask;
import com.sf.release.domain.pipeline.NodeType;
import com.sf.release.domain.pipeline.PipelineFactory;
import com.sf.release.domain.pipeline.PipelineInstance;
import com.sf.release.domain.pipeline.PipelineNode;
import com.sf.release.domain.pipeline.PipelineOrchestrator;
import com.sf.release.domain.pipeline.ReleaseOrder;
import com.sf.release.domain.pipeline.TriggerType;
import com.sf.release.domain.pipeline.port.GitCommitProbe;
import com.sf.release.domain.resource.EnvDe;
import com.sf.release.domain.resource.EnvKind;
import com.sf.release.domain.resource.UnitType;
import com.sf.release.domain.task.ItemEvent;
import com.sf.release.domain.task.ItemStateMachine;
import com.sf.release.domain.task.ItemStatus;
import com.sf.release.domain.task.ReleaseItem;
import com.sf.release.domain.task.TaskStatus;
import com.sf.release.infra.lock.UnitLockService;
import com.sf.release.infra.repo.EnvDeMapper;
import com.sf.release.infra.repo.NodeTaskMapper;
import com.sf.release.infra.repo.PipelineInstanceMapper;
import com.sf.release.infra.repo.PipelineNodeMapper;
import com.sf.release.infra.repo.ReleaseItemMapper;
import com.sf.release.infra.repo.ReleaseOrderMapper;
import com.sf.release.infra.repo.ReleaseTaskMapper;
import com.sf.release.infra.repo.po.EnvDePO;
import com.sf.release.infra.repo.po.NodeTaskPO;
import com.sf.release.infra.repo.po.PipelineInstancePO;
import com.sf.release.infra.repo.po.PipelineNodePO;
import com.sf.release.infra.repo.po.ReleaseItemPO;
import com.sf.release.infra.repo.po.ReleaseOrderPO;
import com.sf.release.infra.repo.po.ReleaseTaskPO;
import com.sf.release.infra.security.ScopeGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * The publish use case of an online 变更项 (tech design §4, §5.1):
 * {@code ScopeGuard → assertPublishable → require DE configuration → observe the base commit →
 * acquire the unit lock → one transaction that moves the item PENDING -PUBLISH-> BUILDING and
 * creates the release order, the pipeline instance and every node/task}.
 *
 * <p><strong>Why the lock comes first.</strong> The unit lock is the mutual exclusion of the
 * whole publish: two clicks (or two nodes) must not create two orders. The winner holds the
 * lease and the {@code deploy_unit_lock} row until the instance reaches a terminal state; the
 * loser gets {@link BizError#UNIT_LOCKED} (40006). Because the item status is only checked
 * inside the locked transaction (through the state machine and a {@code row_version} CAS), a
 * concurrent double publish loses deterministically on the lock, while a later publish of an
 * already {@code BUILDING} item (lock gone) fails with 40001 and releases its own reservation.
 *
 * <p><strong>DE configuration.</strong> A unit must have at least one {@code TEST} DE and at
 * least one {@code PROD}/{@code DR}/{@code BLUE} DE, otherwise the pre-generated pipeline would
 * contain a {@code TEST_DEPLOY} or {@code PROD_DEPLOY} node without any deploy task (an empty
 * node that can never be completed). The validation fails with 40011 and names the missing
 * families; it runs before the lock, so a misconfigured unit reserves nothing.
 *
 * <p><strong>Base-commit probe.</strong> The branch-head probe is an outbound git call; it is
 * hoisted <em>before</em> the publish transaction (and before the unit lock) and its observed
 * value is passed in. Failure keeps the best-effort semantics: {@code base_commit} stays NULL
 * with a WARN and the publish continues.
 *
 * <p><strong>One transaction.</strong> The order, instance, nodes and tasks are inserted in a
 * single {@link TransactionTemplate} transaction together with the item transition, so a
 * failure leaves no half-published pipeline. The lock row is inserted <em>before</em> that
 * transaction (it must be visible to concurrent publishers) and is bound to the new instance
 * id inside it. Any other failure releases the sentinel reservation; a failure of the bind
 * itself means the lock was lost, so no release is attempted at all.
 *
 * <p><strong>Scope.</strong> Online deployment-unit items and second-party library items are
 * published here. A LIB item takes its own branch (coordinates + BUILD/LIB_PUBLISH binding, no
 * DE configuration) and commits a LIB pipeline instead of the four-node online graph; the UNIT
 * path below is unchanged (P6-A design §3/§5 item 5). The daily pipeline belongs to P5-A and the
 * rerun use case ({@link RerunAppService}) reuses {@link #createRerunInstance}.
 */
@Service
public class PublishAppService {

    private static final Logger log = LoggerFactory.getLogger(PublishAppService.class);

    private static final int PUBLISH_NO_RANDOM_LENGTH = 6;

    /**
     * Git reports 40 hex (SHA-1) or 64 hex (SHA-256); the column is {@code VARCHAR(64)} since
     * migration V15. A longer value is a contract violation, never something to truncate.
     */
    private static final int MAX_BASE_COMMIT_LENGTH = 64;
    private static final DateTimeFormatter PUBLISH_DATE =
        DateTimeFormatter.ofPattern("yyyyMMdd", Locale.ROOT);

    /** Payload marker: the unit has no TEST DE, so the pipeline could not test-deploy. */
    static final String MISSING_TEST_DE = "TEST_DE";

    /** Payload marker: the unit has no PROD/DR/BLUE DE, so production could not deploy. */
    static final String MISSING_PROD_DE = "PROD_DE";

    private final ReleaseItemMapper itemMapper;
    private final EnvDeMapper envDeMapper;
    private final ReleaseOrderMapper orderMapper;
    private final ReleaseTaskMapper releaseTaskMapper;
    private final PipelineInstanceMapper instanceMapper;
    private final PipelineNodeMapper nodeMapper;
    private final NodeTaskMapper taskMapper;
    private final UnitAppService unitAppService;
    private final LibAppService libAppService;
    private final UnitLockService unitLockService;
    private final GitCommitProbe commitProbe;
    private final PipelineOrchestrator orchestrator;
    private final AuditService audit;
    private final SettingsService settings;
    private final TransactionTemplate transactionTemplate;

    /**
     * Whether publishing starts the pipeline immediately. Enabled in every real deployment; the
     * test profile turns it off so tests can drive node handlers deterministically. With it off a
     * committed instance stays PENDING until the reconcile pass enters its first node.
     */
    private final boolean autoStart;

    public PublishAppService(ReleaseItemMapper itemMapper,
                             EnvDeMapper envDeMapper,
                             ReleaseOrderMapper orderMapper,
                             ReleaseTaskMapper releaseTaskMapper,
                             PipelineInstanceMapper instanceMapper,
                             PipelineNodeMapper nodeMapper,
                             NodeTaskMapper taskMapper,
                             UnitAppService unitAppService,
                             LibAppService libAppService,
                             UnitLockService unitLockService,
                             GitCommitProbe commitProbe,
                             PipelineOrchestrator orchestrator,
                             AuditService audit,
                             SettingsService settings,
                             @Value("${release.pipeline.auto-start:true}") boolean autoStart,
                             PlatformTransactionManager transactionManager) {
        this.itemMapper = itemMapper;
        this.envDeMapper = envDeMapper;
        this.orderMapper = orderMapper;
        this.releaseTaskMapper = releaseTaskMapper;
        this.instanceMapper = instanceMapper;
        this.nodeMapper = nodeMapper;
        this.taskMapper = taskMapper;
        this.unitAppService = unitAppService;
        this.libAppService = libAppService;
        this.unitLockService = unitLockService;
        this.commitProbe = commitProbe;
        this.orchestrator = orchestrator;
        this.audit = audit;
        this.settings = settings;
        this.autoStart = autoStart;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /**
     * Publishes {@code itemId}: creates its 发布单 and pre-generated online pipeline and moves
     * the item to {@code BUILDING}.
     *
     * @throws BizException 40000 unknown item; 40001 not publishable in its current state (or a
     *     lost status CAS); 40006 the deployment unit is locked; 40011 the unit configuration
     *     is incomplete (CODING tasks or the TEST / PROD|DR|BLUE DEs); 40009 the caller has no
     *     data scope on the unit
     */
    public PublishResult publish(long itemId) {
        ReleaseItem item = loadItem(itemId);
        ScopeGuard.assertUnitInScope(item.unitId());
        requireTaskNotArchived(item);
        if (item.type() == UnitType.LIB) {
            return publishLib(item);
        }
        unitAppService.assertPublishable(item.unitId());

        long unitId = item.unitId();
        // The DE snapshot is read and validated before the transaction: the pipeline cannot be
        // meaningful without a TEST and a production-family environment, and a failing
        // validation must not take the unit lock (or open a transaction).
        List<EnvDe> envDes = loadEnvDes(unitId);
        requireDeConfiguration(unitId, envDes);
        // Best-effort probe of the branch head, deliberately *outside* the publish transaction
        // and before the unit lock: it is an outbound git call and must not extend the critical
        // section that another publisher is waiting on. Its degradation semantics are unchanged
        // (a failure leaves base_commit NULL with a WARN and the publish continues).
        String baseCommit = observeBaseCommit(item);
        if (!unitLockService.acquire(unitId, UnitLockService.UNBOUND_INSTANCE_ID)) {
            throw unitLocked(unitId, itemId);
        }
        try {
            Published published = transactionTemplate.execute(
                tx -> publishInTransaction(itemId, unitId, envDes, baseCommit));
            Published result = Objects.requireNonNull(published, "publish result");
            // The instance is committed; now start it. Starting is outside the transaction on
            // purpose (node handlers make outbound engine calls) and must never undo the publish:
            // a failure here is logged, and the reconcile pass enters an unstarted node later.
            if (autoStart) {
                startPipeline(result.instanceId());
            }
            audit.record(AuditCommand.of(AuditAction.PUBLISH, result.result().publishNo(),
                    result.result().publishNo(),
                    "发起发布：发布单 " + result.result().publishNo() + "（变更项 " + item.crid() + "）")
                .before(Map.of("itemStatus", item.status().name()))
                .after(Map.of("itemStatus", ItemStatus.BUILDING.name(),
                    "publishNo", result.result().publishNo(),
                    "pipelineNo", result.result().pipelineNo()))
                .scopeUnitId(unitId)
                .build());
            return result.result();
        } catch (LockLostException ex) {
            // The lock row is no longer ours (bindInstance reported it lost). Releasing here
            // could delete a new holder's row/key, so the release call is skipped entirely.
            throw ex.failure();
        } catch (RuntimeException ex) {
            // On any other failure the transaction rolled back, including the bindInstance
            // update, so the row still belongs to the reserved sentinel.
            unitLockService.release(unitId, UnitLockService.UNBOUND_INSTANCE_ID);
            throw ex;
        }
    }

    /**
     * Publishes a second-party library item (P6-A design §3): validates the artifact
     * coordinates plus the {@code BUILD}/{@code LIB_PUBLISH} bindings, takes the library's own
     * unit lock, and commits one release order with the three-node LIB pipeline.
     *
     * <p>The steps are deliberately the same as the UNIT path: the base-commit probe is
     * best-effort and hoisted before the lock, the item moves {@code PENDING -PUBLISH->
     * BUILDING} with a {@code row_version} CAS, the order/instance/nodes/tasks and the lock bind
     * are one transaction, and the audit row is written after it commits. What differs is only
     * what is validated and what graph is pre-generated — a library has no DE/tests/production,
     * so neither {@code assertPublishable} nor the DE configuration check runs.
     */
    private PublishResult publishLib(ReleaseItem item) {
        long itemId = item.id();
        long unitId = item.unitId();
        libAppService.assertPublishable(unitId);
        String baseCommit = observeBaseCommit(item);
        if (!unitLockService.acquire(unitId, UnitLockService.UNBOUND_INSTANCE_ID)) {
            throw unitLocked(unitId, itemId);
        }
        try {
            Published published = transactionTemplate.execute(
                tx -> publishLibInTransaction(itemId, unitId, baseCommit));
            Published result = Objects.requireNonNull(published, "library publish result");
            if (autoStart) {
                startPipeline(result.instanceId());
            }
            audit.record(AuditCommand.of(AuditAction.PUBLISH, result.result().publishNo(),
                    result.result().publishNo(),
                    "发起二方库发布：发布单 " + result.result().publishNo()
                        + "（变更项 " + item.crid() + "）")
                .before(Map.of("itemStatus", item.status().name()))
                .after(Map.of("itemStatus", ItemStatus.BUILDING.name(),
                    "publishNo", result.result().publishNo(),
                    "pipelineNo", result.result().pipelineNo(),
                    "type", UnitType.LIB.name()))
                .scopeUnitId(unitId)
                .build());
            return result.result();
        } catch (LockLostException ex) {
            throw ex.failure();
        } catch (RuntimeException ex) {
            unitLockService.release(unitId, UnitLockService.UNBOUND_INSTANCE_ID);
            throw ex;
        }
    }

    /**
     * 40001 when the owning task is archived (FR-T-05, P5-B design §1: an archived task is
     * read-only). Checked before the unit lock and before any write, so a refused publish reserves
     * nothing. Staging/rollback are deliberately not guarded: a rollback of an archived task's unit
     * stays the operations recovery path (design §8).
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
            throw new BizException(BizError.STATE_NOT_ALLOWED, "任务已归档，不能发起发布",
                Map.of("itemId", item.id(), "taskId", item.taskId()));
        }
    }

    /**
     * Fails with {@link BizError#UNIT_NOT_CONFIGURED} (40011) when the unit has no TEST DE or no
     * production-family ({@code PROD}/{@code DR}/{@code BLUE}) DE. Publishing such a unit would
     * create a pipeline whose TEST_DEPLOY or PROD_DEPLOY node has no deploy task and therefore
     * can never be completed; the missing families are returned in the payload so the caller can
     * name them.
     */
    private static void requireDeConfiguration(long unitId, List<EnvDe> envDes) {
        boolean hasTest = envDes.stream().anyMatch(de -> de.envKind() == EnvKind.TEST);
        boolean hasProduction = envDes.stream()
            .anyMatch(de -> de.envKind() == EnvKind.PROD
                || de.envKind() == EnvKind.DR
                || de.envKind() == EnvKind.BLUE);
        List<String> missing = new ArrayList<>(2);
        if (!hasTest) {
            missing.add(MISSING_TEST_DE);
        }
        if (!hasProduction) {
            missing.add(MISSING_PROD_DE);
        }
        if (!missing.isEmpty()) {
            throw new BizException(BizError.UNIT_NOT_CONFIGURED,
                "部署单元未配置部署环境（DE），缺少：" + String.join("、", missing),
                Map.of("unitId", unitId, "missing", List.copyOf(missing)));
        }
    }

    private Published publishInTransaction(long itemId, long unitId, List<EnvDe> envDes,
                                               String baseCommit) {
        PublishStart start = beginPublish(itemId, baseCommit);
        PipelineInstance instance = PipelineFactory.createOnline(start.order(), start.item(),
            envDes, settings.enabledChecks());
        long instanceId = persistInstance(instance, unitId, itemId);
        log.info("release published: itemId={}, unitId={}, publishNo={}, pipelineNo={}",
            itemId, unitId, start.order().publishNo(), instance.pipelineNo());
        return new Published(new PublishResult(start.order().publishNo(), instance.pipelineNo(),
            UnitType.UNIT.name()), instanceId);
    }

    /**
     * Commits one LIB publish: the same item CAS and order insert as a deployment, then the
     * three-node library graph instead of the four-node online one (P6-A design §3). The order is
     * a real 发布单, so the unit lock, the audit row and the records page work unchanged.
     */
    private Published publishLibInTransaction(long itemId, long unitId, String baseCommit) {
        PublishStart start = beginPublish(itemId, baseCommit);
        PipelineInstance instance = PipelineFactory.createLib(start.order(), start.item(),
            start.now());
        long instanceId = persistInstance(instance, unitId, itemId);
        log.info("library release published: itemId={}, unitId={}, publishNo={}, pipelineNo={}",
            itemId, unitId, start.order().publishNo(), instance.pipelineNo());
        return new Published(new PublishResult(start.order().publishNo(), instance.pipelineNo(),
            UnitType.LIB.name()), instanceId);
    }

    /**
     * The shared first half of a publish transaction: move the item {@code PENDING →
     * BUILDING} with a {@code row_version} CAS and insert its {@code INIT} release order. The
     * caller then pre-generates the pipeline of the item type, so the two paths cannot disagree
     * on the item transition or on the order identity.
     */
    private PublishStart beginPublish(long itemId, String baseCommit) {
        ReleaseItem current = loadItem(itemId);
        ItemStatus next = ItemStateMachine.transit(current, ItemEvent.PUBLISH);
        Instant now = Instant.now();
        LambdaUpdateWrapper<ReleaseItemPO> update = Wrappers.<ReleaseItemPO>lambdaUpdate()
            .eq(ReleaseItemPO::getId, itemId)
            .eq(ReleaseItemPO::getRowVersion, current.rowVersion())
            .set(ReleaseItemPO::getStatus, next.name())
            .set(ReleaseItemPO::getRowVersion, current.rowVersion() + 1)
            .set(ReleaseItemPO::getUpdatedAt, now);
        if (baseCommit != null) {
            // The baseline the next rerun compares against. Best-effort: an unobservable head is
            // left NULL and only degrades that rerun (see observeBaseCommit).
            update.set(ReleaseItemPO::getBaseCommit, baseCommit);
        }
        int rows = itemMapper.update(null, update);
        if (rows != 1) {
            throw new BizException(BizError.STATE_NOT_ALLOWED, "变更项已被并发发布",
                Map.of("itemId", itemId, "currentStatus", current.status().name()));
        }

        ReleaseOrder order = new ReleaseOrder(null, newPublishNo(now), itemId, null, null, null,
            null, ReleaseOrder.STATUS_INIT, 0, now);
        ReleaseOrderPO orderPo = ReleaseOrderPO.of(order);
        orderMapper.insert(orderPo);
        return new PublishStart(current, order.withId(orderPo.getId()), now);
    }

    /** One committed publish start: the item as it was read and the persisted order. */
    private record PublishStart(ReleaseItem item, ReleaseOrder order, Instant now) {
    }

    /**
     * Starts the freshly committed pipeline by entering its first node. Runs outside the publish
     * transaction because node handlers call the CI engine.
     *
     * <p>A failure here must not invalidate the publish: the instance and its nodes are committed,
     * the reconcile pass enters any {@code PENDING} instance whose node was never entered, and the
     * unit lock is released by the orchestrator once the instance is terminal.
     */
    private void startPipeline(long instanceId) {
        try {
            orchestrator.advance(instanceId);
        } catch (RuntimeException ex) {
            log.error("pipeline did not start; reconcile will enter its first node: instanceId={}",
                instanceId, ex);
        }
    }

    /** Internal carrier: the business result plus the instance the caller must start. */
    private record Published(PublishResult result, long instanceId) {
    }

    /** A persisted rerun instance together with the row id needed to start it. */
    public record CreatedInstance(PipelineInstance instance, long instanceId) {
    }

    /**
     * Creates and persists the pipeline instance of a rerun under an existing 发布单. The caller
     * owns the transaction and must already hold the unit lock (the sentinel reservation):
     * this method binds the new instance id to it, exactly as the initial publish does.
     *
     * <p>Shared by {@link RerunAppService} so instance creation, node/task pre-generation and the
     * lock bind have a single implementation. A lost lock surfaces as the internal
     * {@link LockLostException}, whose business failure the caller rethrows.
     */
    CreatedInstance createRerunInstance(ReleaseOrder order, ReleaseItem item, long unitId,
                                                TriggerType triggerType, NodeType fromNode,
                                                String imageDigest, String commitSha,
                                                PipelineInstance previous) {
        List<EnvDe> envDes = loadEnvDes(unitId);
        PipelineInstance instance = PipelineFactory.createRerun(order, item, envDes,
            triggerType, fromNode, imageDigest, commitSha, previous, settings.enabledChecks());
        long instanceId = persistInstance(instance, unitId, item.id());
        log.info("rerun pipeline created: itemId={}, unitId={}, publishNo={}, pipelineNo={},"
                + " triggerType={}, fromNode={}, imageDigest={}",
            item.id(), unitId, order.publishNo(), instance.pipelineNo(),
            instance.triggerType(), instance.currentNode(), imageDigest == null ? "<none>"
                : imageDigest);
        return new CreatedInstance(instance, instanceId);
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
     * Inserts the instance, binds the unit lock to it and pre-generates every node and task.
     *
     * <p>Package-visible so {@link RollbackService} reuses the same persist/bind step instead of
     * duplicating the lock-lost path of a publish.
     */
    long persistInstance(PipelineInstance instance, long unitId, long itemId) {
        PipelineInstancePO instancePo = PipelineInstancePO.of(instance);
        instanceMapper.insert(instancePo);
        long instanceId = instancePo.getId();
        if (!unitLockService.bindInstance(unitId, instanceId)) {
            throw new LockLostException(new BizException(BizError.UNIT_LOCKED,
                "发布过程中部署单元锁丢失", Map.of("unitId", unitId, "itemId", itemId)));
        }

        for (PipelineNode node : instance.nodes()) {
            nodeMapper.insert(PipelineNodePO.of(node, instanceId));
            for (NodeTask task : node.tasks()) {
                taskMapper.insert(NodeTaskPO.of(task, instanceId));
            }
        }
        return instanceId;
    }

    /**
     * Builds the 40006 business error with the observed holder when there is one. A holder is
     * "known" by its instance id only after {@code bindInstance}; the acquisition time is
     * always part of the row.
     */
    BizException unitLocked(long unitId, long itemId) {
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

    /**
     * Observes the branch head for the item's {@code base_commit} baseline. Best-effort on
     * purpose: a missing CODING connection, a probe failure or an unreported head must not block
     * a release. The item stays with a NULL baseline, the publish continues, and the next rerun
     * is conservatively full+degraded (requirements §4.5).
     */
    private String observeBaseCommit(ReleaseItem item) {
        try {
            String head = trimToNull(commitProbe.headSha(item.unitId(), item.sourceBranch()));
            if (head == null) {
                log.warn("could not observe the branch head commit; base_commit stays NULL:"
                        + " itemId={}, unitId={}, branch={}",
                    item.id(), item.unitId(), item.sourceBranch());
                return null;
            }
            if (head.length() > MAX_BASE_COMMIT_LENGTH) {
                // Never truncate a commit id: a shortened value silently breaks the rerun
                // comparison. Degrade the baseline instead, exactly like a probe failure.
                log.warn("observed branch head is longer than {} chars; base_commit stays NULL:"
                        + " itemId={}, unitId={}, branch={}, length={}",
                    MAX_BASE_COMMIT_LENGTH, item.id(), item.unitId(), item.sourceBranch(),
                    head.length());
                return null;
            }
            return head;
        } catch (RuntimeException ex) {
            log.warn("could not observe the branch head commit; base_commit stays NULL:"
                    + " itemId={}, unitId={}, branch={}, reason={}",
                item.id(), item.unitId(), item.sourceBranch(), messageOf(ex));
            return null;
        }
    }

    private static String messageOf(RuntimeException ex) {
        String message = ex.getMessage();
        return message == null || message.isBlank()
            ? ex.getClass().getSimpleName() : message;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private ReleaseItem loadItem(long itemId) {
        ReleaseItemPO po = itemMapper.selectById(itemId);
        if (po == null) {
            throw new BizException(BizError.PARAM_INVALID, "变更项不存在", Map.of("id", itemId));
        }
        return po.toDomain();
    }

    /** Display number of a 发布单, for example {@code PO-20261121-1A2B3C}. */
    private static String newPublishNo(Instant now) {
        String suffix = UUID.randomUUID().toString().replace("-", "")
            .substring(0, PUBLISH_NO_RANDOM_LENGTH).toUpperCase(Locale.ROOT);
        return "PO-" + PUBLISH_DATE.format(LocalDate.ofInstant(now, ZoneOffset.UTC))
            + "-" + suffix;
    }

    /**
     * What the caller needs after publishing: the order number, the instance number and the
     * item type ({@code UNIT} or {@code LIB}, P6-A design §4: the LIB response carries its type
     * so the page can render the library view).
     */
    public record PublishResult(String publishNo, String pipelineNo, String type) {
    }

    /**
     * Internal marker: {@code bindInstance} found the lock row gone, so the "lock lost" path
     * must not release anything (the row now belongs to somebody else, or to nobody).
     */
    static final class LockLostException extends RuntimeException {

        private final BizException failure;

        LockLostException(BizException failure) {
            super(failure.getMessage(), failure);
            this.failure = failure;
        }

        BizException failure() {
            return failure;
        }
    }
}