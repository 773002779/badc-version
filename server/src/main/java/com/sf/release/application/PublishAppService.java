package com.sf.release.application;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sf.release.common.BizError;
import com.sf.release.common.BizException;
import com.sf.release.domain.pipeline.NodeTask;
import com.sf.release.domain.pipeline.PipelineFactory;
import com.sf.release.domain.pipeline.PipelineInstance;
import com.sf.release.domain.pipeline.PipelineNode;
import com.sf.release.domain.pipeline.ReleaseOrder;
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
 * loser gets {@link BizError#UNIT_LOCKED} (40006). The item status is checked before the lock
 * so a second publish of an already {@code BUILDING} item fails with 40001 without touching
 * the lock, and the status transition itself is still re-applied inside the transaction with
 * a {@code row_version} CAS.
 *
 * <p><strong>One transaction.</strong> The order, instance, nodes and tasks are inserted in a
 * single {@link TransactionTemplate} transaction together with the item transition, so a
 * failure leaves no half-published pipeline. The lock row is inserted <em>before</em> that
 * transaction (it must be visible to concurrent publishers) and is bound to the new instance
 * id inside it; if the transaction fails the lock is released.
 *
 * <p><strong>Scope.</strong> Only online deployment-unit items are published here. The daily
 * pipeline, the second-party library pipeline and reruns belong to later tasks.
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
    private final TransactionTemplate transactionTemplate;

    public PublishAppService(ReleaseItemMapper itemMapper,
                             EnvDeMapper envDeMapper,
                             ReleaseOrderMapper orderMapper,
                             PipelineInstanceMapper instanceMapper,
                             PipelineNodeMapper nodeMapper,
                             NodeTaskMapper taskMapper,
                             UnitAppService unitAppService,
                             UnitLockService unitLockService,
                             PlatformTransactionManager transactionManager) {
        this.itemMapper = itemMapper;
        this.envDeMapper = envDeMapper;
        this.orderMapper = orderMapper;
        this.instanceMapper = instanceMapper;
        this.nodeMapper = nodeMapper;
        this.taskMapper = taskMapper;
        this.unitAppService = unitAppService;
        this.unitLockService = unitLockService;
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
        assertItemCanBePublished(item);

        long unitId = item.unitId();
        if (!unitLockService.acquire(unitId, UnitLockService.UNBOUND_INSTANCE_ID)) {
            throw new BizException(BizError.UNIT_LOCKED, "部署单元已被占用",
                Map.of("unitId", unitId, "itemId", itemId));
        }
        try {
            PublishResult result = transactionTemplate.execute(
                tx -> publishInTransaction(itemId, unitId));
            return Objects.requireNonNull(result, "publish result");
        } catch (RuntimeException ex) {
            unitLockService.release(unitId);
            throw ex;
        }
    }

    private PublishResult publishInTransaction(long itemId, long unitId) {
        ReleaseItem current = loadItem(itemId);
        ItemStatus next = ItemStateMachine.transit(current, ItemEvent.PUBLISH);
        Instant now = Instant.now();
        int rows = itemMapper.update(null, Wrappers.<ReleaseItemPO>lambdaUpdate()
            .eq(ReleaseItemPO::getId, itemId)
            .eq(ReleaseItemPO::getRowVersion, current.rowVersion())
            .set(ReleaseItemPO::getStatus, next.name())
            .set(ReleaseItemPO::getRowVersion, current.rowVersion() + 1)
            .set(ReleaseItemPO::getUpdatedAt, now));
        if (rows != 1) {
            throw new BizException(BizError.STATE_NOT_ALLOWED, "变更项已被并发发布",
                Map.of("itemId", itemId, "currentStatus", current.status().name()));
        }

        ReleaseOrder order = new ReleaseOrder(null, newPublishNo(now), itemId, null, null, null,
            null, ReleaseOrder.STATUS_INIT, 0, now);
        ReleaseOrderPO orderPo = ReleaseOrderPO.of(order);
        orderMapper.insert(orderPo);
        ReleaseOrder persistedOrder = order.withId(orderPo.getId());

        List<EnvDe> envDes = envDeMapper.selectList(Wrappers.<EnvDePO>lambdaQuery()
                .eq(EnvDePO::getUnitId, unitId)
                .orderByAsc(EnvDePO::getDeployBatchNo)
                .orderByAsc(EnvDePO::getId))
            .stream()
            .map(EnvDePO::toDomain)
            .toList();
        PipelineInstance instance = PipelineFactory.createOnline(persistedOrder, current, envDes);
        PipelineInstancePO instancePo = PipelineInstancePO.of(instance);
        instanceMapper.insert(instancePo);
        long instanceId = instancePo.getId();
        if (!unitLockService.bindInstance(unitId, instanceId)) {
            throw new BizException(BizError.UNIT_LOCKED, "发布过程中部署单元锁丢失",
                Map.of("unitId", unitId, "itemId", itemId));
        }

        for (PipelineNode node : instance.nodes()) {
            nodeMapper.insert(PipelineNodePO.of(node, instanceId));
            for (NodeTask task : node.tasks()) {
                taskMapper.insert(NodeTaskPO.of(task, instanceId));
            }
        }
        log.info("release published: itemId={}, unitId={}, publishNo={}, pipelineNo={},"
                + " instanceId={}, nodes={}",
            itemId, unitId, persistedOrder.publishNo(), instance.pipelineNo(), instanceId,
            instance.nodes().size());
        return new PublishResult(persistedOrder.publishNo(), instance.pipelineNo());
    }

    /** Applies the state machine to the loaded item; an illegal PUBLISH surfaces as 40001. */
    private static void assertItemCanBePublished(ReleaseItem item) {
        ItemStateMachine.transit(item, ItemEvent.PUBLISH);
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
}