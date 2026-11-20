package com.sf.release.application;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sf.release.common.BizError;
import com.sf.release.common.BizException;
import com.sf.release.domain.pipeline.NodeTask;
import com.sf.release.domain.pipeline.NodeType;
import com.sf.release.domain.pipeline.PipelineFactory;
import com.sf.release.domain.pipeline.PipelineInstance;
import com.sf.release.domain.pipeline.PipelineNode;
import com.sf.release.domain.pipeline.ReleaseOrder;
import com.sf.release.domain.pipeline.TriggerType;
import com.sf.release.domain.pipeline.port.GitCommitProbe;
import com.sf.release.domain.resource.EnvDe;
import com.sf.release.domain.resource.UnitType;
import com.sf.release.domain.task.ItemEvent;
import com.sf.release.domain.task.ItemStateMachine;
import com.sf.release.domain.task.ItemStatus;
import com.sf.release.domain.task.ReleaseItem;
import com.sf.release.infra.lock.UnitLockService;
import com.sf.release.infra.repo.EnvDeMapper;
import com.sf.release.infra.repo.NodeTaskMapper;
import com.sf.release.infra.repo.PipelineInstanceMapper;
import com.sf.release.infra.repo.PipelineNodeMapper;
import com.sf.release.infra.repo.ReleaseItemMapper;
import com.sf.release.infra.repo.ReleaseOrderMapper;
import com.sf.release.infra.repo.po.EnvDePO;
import com.sf.release.infra.repo.po.NodeTaskPO;
import com.sf.release.infra.repo.po.PipelineInstancePO;
import com.sf.release.infra.repo.po.PipelineNodePO;
import com.sf.release.infra.repo.po.ReleaseItemPO;
import com.sf.release.infra.repo.po.ReleaseOrderPO;
import com.sf.release.infra.security.ScopeGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * The publish use case of an online 变更项 (tech design §4, §5.1):
 * {@code ScopeGuard → assertPublishable → acquire the unit lock → one transaction that moves
 * the item PENDING -PUBLISH-> BUILDING and creates the release order, the pipeline instance
 * and every node/task}.
 *
 * <p><strong>Why the lock comes first.</strong> The unit lock is the mutual exclusion of the
 * whole publish: two clicks (or two nodes) must not create two orders. The winner holds the
 * lease and the {@code deploy_unit_lock} row until the instance reaches a terminal state; the
 * loser gets {@link BizError#UNIT_LOCKED} (40006). Because the item status is only checked
 * inside the locked transaction (through the state machine and a {@code row_version} CAS), a
 * concurrent double publish loses deterministically on the lock, while a later publish of an
 * already {@code BUILDING} item (lock gone) fails with 40001 and releases its own reservation.
 *
 * <p><strong>One transaction.</strong> The order, instance, nodes and tasks are inserted in a
 * single {@link TransactionTemplate} transaction together with the item transition, so a
 * failure leaves no half-published pipeline. The lock row is inserted <em>before</em> that
 * transaction (it must be visible to concurrent publishers) and is bound to the new instance
 * id inside it. Any other failure releases the sentinel reservation; a failure of the bind
 * itself means the lock was lost, so no release is attempted at all.
 *
 * <p><strong>Scope.</strong> Only online deployment-unit items are published here. The daily
 * pipeline and the second-party library pipeline belong to later tasks; the rerun use case
 * ({@link RerunAppService}) reuses {@link #createRerunInstance} for its instance creation.
 */
@Service
public class PublishAppService {

    private static final Logger log = LoggerFactory.getLogger(PublishAppService.class);

    private static final int PUBLISH_NO_RANDOM_LENGTH = 6;
    private static final DateTimeFormatter PUBLISH_DATE =
        DateTimeFormatter.ofPattern("yyyyMMdd", Locale.ROOT);

    private final ReleaseItemMapper itemMapper;
    private final EnvDeMapper envDeMapper;
    private final ReleaseOrderMapper orderMapper;
    private final PipelineInstanceMapper instanceMapper;
    private final PipelineNodeMapper nodeMapper;
    private final NodeTaskMapper taskMapper;
    private final UnitAppService unitAppService;
    private final UnitLockService unitLockService;
    private final GitCommitProbe commitProbe;
    private final TransactionTemplate transactionTemplate;

    public PublishAppService(ReleaseItemMapper itemMapper,
                             EnvDeMapper envDeMapper,
                             ReleaseOrderMapper orderMapper,
                             PipelineInstanceMapper instanceMapper,
                             PipelineNodeMapper nodeMapper,
                             NodeTaskMapper taskMapper,
                             UnitAppService unitAppService,
                             UnitLockService unitLockService,
                             GitCommitProbe commitProbe,
                             PlatformTransactionManager transactionManager) {
        this.itemMapper = itemMapper;
        this.envDeMapper = envDeMapper;
        this.orderMapper = orderMapper;
        this.instanceMapper = instanceMapper;
        this.nodeMapper = nodeMapper;
        this.taskMapper = taskMapper;
        this.unitAppService = unitAppService;
        this.unitLockService = unitLockService;
        this.commitProbe = commitProbe;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /**
     * Publishes {@code itemId}: creates its 发布单 and pre-generated online pipeline and moves
     * the item to {@code BUILDING}.
     *
     * @throws BizException 40000 unknown item; 40001 not publishable in its current state (or a
     *     lost status CAS); 40006 the deployment unit is locked; 40011 the unit configuration
     *     is incomplete; 40009 the caller has no data scope on the unit
     */
    public PublishResult publish(long itemId) {
        ReleaseItem item = loadItem(itemId);
        ScopeGuard.assertUnitInScope(item.unitId());
        if (item.type() != UnitType.UNIT) {
            throw new BizException(BizError.STATE_NOT_ALLOWED,
                "二方库发布流水线不在本期范围", Map.of("itemId", itemId, "type", item.type().name()));
        }
        unitAppService.assertPublishable(item.unitId());

        long unitId = item.unitId();
        if (!unitLockService.acquire(unitId, UnitLockService.UNBOUND_INSTANCE_ID)) {
            throw unitLocked(unitId, itemId);
        }
        try {
            PublishResult result = transactionTemplate.execute(
                tx -> publishInTransaction(itemId, unitId));
            return Objects.requireNonNull(result, "publish result");
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

    private PublishResult publishInTransaction(long itemId, long unitId) {
        ReleaseItem current = loadItem(itemId);
        ItemStatus next = ItemStateMachine.transit(current, ItemEvent.PUBLISH);
        String baseCommit = observeBaseCommit(current);
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
        ReleaseOrder persistedOrder = order.withId(orderPo.getId());

        List<EnvDe> envDes = loadEnvDes(unitId);
        PipelineInstance instance = PipelineFactory.createOnline(persistedOrder, current, envDes);
        persistInstance(instance, unitId, itemId);
        log.info("release published: itemId={}, unitId={}, publishNo={}, pipelineNo={}",
            itemId, unitId, persistedOrder.publishNo(), instance.pipelineNo());
        return new PublishResult(persistedOrder.publishNo(), instance.pipelineNo());
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
    PipelineInstance createRerunInstance(ReleaseOrder order, ReleaseItem item, long unitId,
                                                TriggerType triggerType, NodeType fromNode,
                                                String imageDigest, String commitSha,
                                                PipelineInstance previous) {
        List<EnvDe> envDes = loadEnvDes(unitId);
        PipelineInstance instance = PipelineFactory.createRerun(order, item, envDes,
            triggerType, fromNode, imageDigest, commitSha, previous);
        persistInstance(instance, unitId, item.id());
        log.info("rerun pipeline created: itemId={}, unitId={}, publishNo={}, pipelineNo={},"
                + " triggerType={}, fromNode={}, imageDigest={}",
            item.id(), unitId, order.publishNo(), instance.pipelineNo(),
            instance.triggerType(), instance.currentNode(), imageDigest == null ? "<none>"
                : imageDigest);
        return instance;
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

    /** Inserts the instance, binds the unit lock to it and pre-generates every node and task. */
    private void persistInstance(PipelineInstance instance, long unitId, long itemId) {
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

    /** What the caller needs after publishing: the order number and the instance number. */
    public record PublishResult(String publishNo, String pipelineNo) {
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