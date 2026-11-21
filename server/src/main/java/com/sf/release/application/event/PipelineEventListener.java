package com.sf.release.application.event;

import com.sf.release.application.ItemFailureAppService;
import com.sf.release.application.ItemTransitions;
import com.sf.release.domain.pipeline.InstanceStatus;
import com.sf.release.domain.pipeline.NodeStatus;
import com.sf.release.domain.pipeline.NodeType;
import com.sf.release.domain.pipeline.event.InstanceFinishedEvent;
import com.sf.release.domain.pipeline.event.NodeCompletedEvent;
import com.sf.release.domain.pipeline.event.NodeTaskCompletedEvent;
import com.sf.release.domain.task.ItemEvent;
import com.sf.release.infra.repo.ReleaseOrderMapper;
import com.sf.release.infra.repo.po.ReleaseOrderPO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

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
 * <p><strong>Listener failures never break the pipeline; they are persisted.</strong> The
 * transition runs in the orchestrator's transaction, but this listener is a notification side
 * effect. A transition that throws (corrupt row, unexpected DB error) is caught, logged and
 * recorded in {@code pipeline_item_failure} through
 * {@link ItemFailureAppService#record}; the 30s reconcile replays it
 * ({@link com.sf.release.domain.pipeline.port.ItemFailureRepairer}). A lost {@code row_version}
 * CAS ({@code CAS_LOST}) is recorded the same way — the event was meant to apply and the current
 * row may accept it — so a transient concurrent write cannot silently drop a state change. A
 * deliberately rejected transition ({@code NOT_ALLOWED}: late/duplicate event) is a WARN and is
 * not recorded: retrying it forever would only produce noise. Idempotency is unchanged:
 * {@code allowedEvents} is checked before the transition and the write is a {@code row_version}
 * CAS.
 */
@Component
public class PipelineEventListener {

    private static final Logger log = LoggerFactory.getLogger(PipelineEventListener.class);

    private final ReleaseOrderMapper orderMapper;
    private final ItemTransitions transitions;
    private final ItemFailureAppService failureService;

    public PipelineEventListener(ReleaseOrderMapper orderMapper,
                                 ItemTransitions transitions,
                                 ItemFailureAppService failureService) {
        this.orderMapper = orderMapper;
        this.transitions = transitions;
        this.failureService = failureService;
    }

    @EventListener
    public void onNodeTaskCompleted(NodeTaskCompletedEvent event) {
        try {
            log.debug("node task completed: instanceId={}, taskId={}, node={}, type={}, status={}",
                event.instanceId(), event.taskId(), event.nodeType(), event.taskType(),
                event.status());
        } catch (RuntimeException ex) {
            log.error("node task listener failed: instanceId={}, taskId={}",
                event.instanceId(), event.taskId(), ex);
        }
    }

    @EventListener
    public void onNodeCompleted(NodeCompletedEvent event) {
        ItemEvent itemEvent = itemEventOf(event.nodeType(), event.status());
        if (itemEvent == null) {
            log.debug("node {} result does not change an item state (instanceId={})",
                event.nodeType(), event.instanceId());
            return;
        }
        long itemId = 0L;
        try {
            if (event.orderId() <= 0) {
                log.debug("instance {} has no release order, item state unchanged",
                    event.instanceId());
                return;
            }
            ReleaseOrderPO order = orderMapper.selectById(event.orderId());
            if (order == null || order.getItemId() == null) {
                log.warn("release order {} of instance {} is missing, item state unchanged",
                    event.orderId(), event.instanceId());
                return;
            }
            itemId = order.getItemId();
            ItemTransitions.Outcome outcome = transitions.apply(itemId, itemEvent);
            switch (outcome) {
                case APPLIED -> log.info("item state advanced: itemId={} by {} (instanceId={})",
                    itemId, itemEvent, event.instanceId());
                case NOT_ALLOWED -> log.warn("item {} does not accept {} (instanceId={},"
                        + " node={}, nodeStatus={}); skipped", itemId, itemEvent,
                    event.instanceId(), event.nodeType(), event.status());
                case CAS_LOST -> {
                    // A lost row_version CAS is not a rejected event: the transition was meant
                    // to happen and another writer got there first. Record it so the 30s drain
                    // replays it against the now-current row (it may be legal from the new
                    // state), exactly like a thrown transition. The record is a REQUIRES_NEW
                    // insert, so the pipeline transaction keeps committing.
                    log.warn("item state CAS lost, recorded for repair: instanceId={}, itemId={}, event={}",
                        event.instanceId(), itemId, itemEvent);
                    failureService.record(event.instanceId(), itemId, itemEvent.name(),
                        "state transition CAS lost: row_version changed concurrently");
                }
            }
        } catch (RuntimeException ex) {
            // Persist instead of only logging: a swallowed listener failure must still have a
            // durable repair path (V12). The record itself never throws.
            log.error("item transition failed: instanceId={}, itemId={}, event={}, reason={}",
                event.instanceId(), itemId, itemEvent, ex.getMessage(), ex);
            failureService.record(event.instanceId(), itemId, itemEvent.name(),
                messageOf(ex));
        }
    }

    @EventListener
    public void onInstanceFinished(InstanceFinishedEvent event) {
        try {
            if (event.status() == InstanceStatus.FAILED) {
                log.warn("pipeline failed: instanceId={}, orderId={}, reason={}",
                    event.instanceId(), event.orderId(), event.reason());
            } else {
                log.info("pipeline finished: instanceId={}, orderId={}, status={}, reason={}",
                    event.instanceId(), event.orderId(), event.status(), event.reason());
            }
        } catch (RuntimeException ex) {
            log.error("instance listener failed: instanceId={}", event.instanceId(), ex);
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

    private static String messageOf(RuntimeException ex) {
        String message = ex.getMessage();
        return message == null || message.isBlank()
            ? ex.getClass().getSimpleName() : message;
    }
}