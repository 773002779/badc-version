package com.sf.release.application.event;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sf.release.domain.pipeline.InstanceStatus;
import com.sf.release.domain.pipeline.NodeStatus;
import com.sf.release.domain.pipeline.NodeType;
import com.sf.release.domain.pipeline.event.InstanceFinishedEvent;
import com.sf.release.domain.pipeline.event.NodeCompletedEvent;
import com.sf.release.domain.pipeline.event.NodeTaskCompletedEvent;
import com.sf.release.domain.task.ItemEvent;
import com.sf.release.domain.task.ItemStateMachine;
import com.sf.release.domain.task.ItemStatus;
import com.sf.release.domain.task.ReleaseItem;
import com.sf.release.infra.repo.ReleaseItemMapper;
import com.sf.release.infra.repo.ReleaseOrderMapper;
import com.sf.release.infra.repo.po.ReleaseItemPO;
import com.sf.release.infra.repo.po.ReleaseOrderPO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Application-layer wiring of the pipeline events to the 变更项 main state machine (tech design
 * §3.2/§5.2). Node results map to the six engine-callback events:
 *
 * <ul>
 *   <li>{@code BUILD SUCCESS/FAILED} → {@code BUILD_OK}/{@code BUILD_FAIL};</li>
 *   <li>{@code TEST_DEPLOY SUCCESS/FAILED} → {@code TEST_DEPLOY_OK}/{@code TEST_DEPLOY_FAIL};</li>
 *   <li>{@code PROD_DEPLOY SUCCESS/FAILED} → {@code PROD_OK}/{@code PROD_FAIL}.</li>
 * </ul>
 *
 * <p>{@code VERIFY} has no item transition: the gate evaluator owns the {@code GATE_*} events
 * (Task 15+), so a check result must not move the main state here. An instance without a
 * release order (the daily pipeline, {@code orderId = 0}) never changes an item either
 * (design §5.1).
 *
 * <p>Idempotency: events are emitted once by the orchestrator, but this listener still checks
 * {@link ItemStateMachine#allowedEvents(ItemStatus)} before transitioning, so a duplicated or
 * out-of-date event is logged and skipped instead of aborting the pipeline transaction. The
 * status write is a {@code row_version} CAS, the same rule as every other item transition.
 */
@Component
public class PipelineEventListener {

    private static final Logger log = LoggerFactory.getLogger(PipelineEventListener.class);

    private final ReleaseOrderMapper orderMapper;
    private final ReleaseItemMapper itemMapper;

    public PipelineEventListener(ReleaseOrderMapper orderMapper, ReleaseItemMapper itemMapper) {
        this.orderMapper = orderMapper;
        this.itemMapper = itemMapper;
    }

    @EventListener
    public void onNodeTaskCompleted(NodeTaskCompletedEvent event) {
        log.debug("node task completed: instanceId={}, taskId={}, node={}, type={}, status={}",
            event.instanceId(), event.taskId(), event.nodeType(), event.taskType(),
            event.status());
    }

    @EventListener
    public void onNodeCompleted(NodeCompletedEvent event) {
        ItemEvent itemEvent = itemEventOf(event.nodeType(), event.status());
        if (itemEvent == null) {
            log.debug("node {} result does not change an item state (instanceId={})",
                event.nodeType(), event.instanceId());
            return;
        }
        applyItemEvent(event, itemEvent);
    }

    @EventListener
    public void onInstanceFinished(InstanceFinishedEvent event) {
        if (event.status() == InstanceStatus.FAILED) {
            log.warn("pipeline failed: instanceId={}, orderId={}, reason={}", event.instanceId(),
                event.orderId(), event.reason());
        } else {
            log.info("pipeline finished: instanceId={}, orderId={}, status={}, reason={}",
                event.instanceId(), event.orderId(), event.status(), event.reason());
        }
    }

    /** The item event a node result produces, or {@code null} when there is none. */
    static ItemEvent itemEventOf(NodeType nodeType, NodeStatus nodeStatus) {
        boolean success = nodeStatus == NodeStatus.SUCCESS;
        return switch (nodeType) {
            case BUILD -> success ? ItemEvent.BUILD_OK : ItemEvent.BUILD_FAIL;
            case TEST_DEPLOY -> success ? ItemEvent.TEST_DEPLOY_OK : ItemEvent.TEST_DEPLOY_FAIL;
            case PROD_DEPLOY -> success ? ItemEvent.PROD_OK : ItemEvent.PROD_FAIL;
            case VERIFY -> null;
        };
    }

    private void applyItemEvent(NodeCompletedEvent event, ItemEvent itemEvent) {
        if (event.orderId() <= 0) {
            log.debug("instance {} has no release order, item state unchanged", event.instanceId());
            return;
        }
        ReleaseOrderPO order = orderMapper.selectById(event.orderId());
        if (order == null || order.getItemId() == null) {
            log.warn("release order {} of instance {} is missing, item state unchanged",
                event.orderId(), event.instanceId());
            return;
        }
        ReleaseItemPO po = itemMapper.selectById(order.getItemId());
        if (po == null) {
            log.warn("release item {} of order {} is missing, item state unchanged",
                order.getItemId(), event.orderId());
            return;
        }
        ReleaseItem item = po.toDomain();
        if (!ItemStateMachine.allowedEvents(item.status()).contains(itemEvent)) {
            log.warn("item {} in status {} does not accept {} (instanceId={}, node={},"
                    + " nodeStatus={}); skipped",
                item.id(), item.status(), itemEvent, event.instanceId(), event.nodeType(),
                event.status());
            return;
        }
        ItemStatus next = ItemStateMachine.transit(item, itemEvent);
        int rows = itemMapper.update(null, Wrappers.<ReleaseItemPO>lambdaUpdate()
            .eq(ReleaseItemPO::getId, item.id())
            .eq(ReleaseItemPO::getRowVersion, item.rowVersion())
            .set(ReleaseItemPO::getStatus, next.name())
            .set(ReleaseItemPO::getRowVersion, item.rowVersion() + 1)
            .set(ReleaseItemPO::getUpdatedAt, Instant.now()));
        if (rows == 1) {
            log.info("item state advanced: itemId={}, {} -> {} by {} (instanceId={})",
                item.id(), item.status(), next, itemEvent, event.instanceId());
        } else {
            log.warn("item {} state CAS lost for {} (previous status {}, expected rowVersion {})",
                item.id(), itemEvent, item.status(), item.rowVersion());
        }
    }
}