package com.sf.release.application.event;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sf.release.application.ItemFailureAppService;
import com.sf.release.application.ItemTransitions;
import com.sf.release.application.notification.NotificationAudiences;
import com.sf.release.application.notification.NotificationCommand;
import com.sf.release.application.notification.NotificationService;
import com.sf.release.domain.notification.NotificationEventKey;
import com.sf.release.domain.pipeline.InstanceStatus;
import com.sf.release.domain.pipeline.NodeStatus;
import com.sf.release.domain.pipeline.NodeType;
import com.sf.release.domain.pipeline.TriggerType;
import com.sf.release.domain.pipeline.event.InstanceFinishedEvent;
import com.sf.release.domain.pipeline.event.NodeCompletedEvent;
import com.sf.release.domain.pipeline.event.NodeTaskCompletedEvent;
import com.sf.release.domain.pipeline.event.TestEnvReadyEvent;
import com.sf.release.domain.task.ItemEvent;
import com.sf.release.domain.task.MemberRole;
import com.sf.release.domain.resource.UnitType;
import com.sf.release.infra.repo.PipelineInstanceMapper;
import com.sf.release.infra.repo.ReleaseItemMapper;
import com.sf.release.infra.repo.ReleaseOrderMapper;
import com.sf.release.infra.repo.po.PipelineInstancePO;
import com.sf.release.infra.repo.po.ReleaseItemPO;
import com.sf.release.infra.repo.po.ReleaseOrderPO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.Set;

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
 * <p><strong>Rollback instances are excluded.</strong> A {@link TriggerType#ROLLBACK} instance
 * deploys an already released image to stop an incident; its completion is not a new production
 * result for the 变更项. {@code RollbackCompletionService} owns that convergence (record
 * SUCCESS/FAILED and, only when the source item is still {@code DONE}, {@code ROLLED_BACK}), so this
 * listener returns before mapping any item event for such an instance.
 *
 * <p>{@code VERIFY} has no item transition: the gate evaluator owns the {@code GATE_*} events
 * (Task 15+), so a check result must not move the main state here. An instance without a
 * release order (the daily pipeline, {@code orderId = 0}) never changes an item either
 * (design §5.1).
 *
 * <p><strong>The item type selects the vocabulary (P6-A).</strong> A {@code LIB} item keeps
 * {@code BUILDING} through its {@code BUILD} and {@code LIB_SECURITY} nodes and only moves on the
 * artifact upload ({@code LIB_PUBLISH} success → {@code LIB_OK} → {@code DONE}, any LIB node
 * failure → {@code LIB_FAIL} → {@code FAILED}). It never receives {@code BUILD_OK → TESTING} or a
 * production event, and it emits no notification: a second-party library sends none (FR-N-01 does
 * not list it), which is the recorded trade-off of the slice.
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
    private final PipelineInstanceMapper instanceMapper;
    private final ReleaseItemMapper itemMapper;
    private final ItemTransitions transitions;
    private final ItemFailureAppService failureService;
    private final NotificationService notifications;

    public PipelineEventListener(ReleaseOrderMapper orderMapper,
                                 PipelineInstanceMapper instanceMapper,
                                 ReleaseItemMapper itemMapper,
                                 ItemTransitions transitions,
                                 ItemFailureAppService failureService,
                                 NotificationService notifications) {
        this.orderMapper = orderMapper;
        this.instanceMapper = instanceMapper;
        this.itemMapper = itemMapper;
        this.transitions = transitions;
        this.failureService = failureService;
        this.notifications = notifications;
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
        if (isRollbackInstance(event.instanceId())) {
            // A rollback instance deploys an already released image; its PROD_DEPLOY result is
            // handled by RollbackCompletionService (record SUCCESS/FAILED, item ROLLED_BACK only
            // when the source item is still DONE). Mapping it to PROD_OK here would move the
            // source item to DONE from a non-DONE state and then be rolled back by the
            // completion, which is not what the rollback means.
            log.debug("node {} result of a rollback instance does not change an item state"
                + " (instanceId={})", event.nodeType(), event.instanceId());
            return;
        }
        if (event.orderId() <= 0) {
            log.debug("instance {} has no release order, item state unchanged",
                event.instanceId());
            return;
        }
        long itemId = 0L;
        ItemEvent itemEvent = null;
        try {
            ReleaseOrderPO order = orderMapper.selectById(event.orderId());
            if (order == null || order.getItemId() == null) {
                log.warn("release order {} of instance {} is missing, item state unchanged",
                    event.orderId(), event.instanceId());
                return;
            }
            itemId = order.getItemId();
            ReleaseItemPO item = itemMapper.selectById(itemId);
            if (item == null) {
                log.warn("变更项 {} of release order {} is missing, item state unchanged",
                    itemId, event.orderId());
                return;
            }
            // The item type decides the mapping: a LIB item must never receive BUILD_OK→TESTING
            // or any production event (P6-A design §2), so the branch is required before the
            // static node-type mapping can be applied.
            itemEvent = itemEventOf(unitTypeOf(item), event.nodeType(), event.status());
            if (itemEvent == null) {
                log.debug("node {} result does not change an item state (instanceId={}, type={})",
                    event.nodeType(), event.instanceId(), item.getType());
                return;
            }
            ItemTransitions.Outcome outcome = transitions.apply(itemId, itemEvent);
            switch (outcome) {
                case APPLIED -> {
                    log.info("item state advanced: itemId={} by {} (instanceId={})",
                        itemId, itemEvent, event.instanceId());
                    // The 发布记录 page reads release_order.status, so the order mirrors the item
                    // status as it advances. Without this mirror a release that failed at BUILD
                    // (or a library that ended DONE) kept showing INIT (P6-A review).
                    mirrorOrderStatus(event.orderId(), itemId);
                }
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
            if (itemEvent != null) {
                failureService.record(event.instanceId(), itemId, itemEvent.name(),
                    messageOf(ex));
            }
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

    /**
     * The failed-node notifications of design section 3: {@code BUILD_FAILED},
     * {@code TEST_DEPLOY_FAILED} and {@code PROD_DEPLOY_FAILED}. It is a separate listener so a
     * notification can never change the item transition above; a rollback instance and a daily
     * instance (no release order) are excluded, exactly like the item mapping.
     */
    @EventListener
    public void onNodeCompletedNotification(NodeCompletedEvent event) {
        try {
            if (event.status() != NodeStatus.FAILED) {
                return;
            }
            NotificationEventKey key = switch (event.nodeType()) {
                case BUILD -> NotificationEventKey.BUILD_FAILED;
                case TEST_DEPLOY -> NotificationEventKey.TEST_DEPLOY_FAILED;
                case PROD_DEPLOY -> NotificationEventKey.PROD_DEPLOY_FAILED;
                // A daily release sends no notification (P5-A design §1 non-goals: FR-N-01 does
                // not list it); its history is the daily page plus the DAILY_START audit row.
                // A second-party library sends none either (P6-A design §5 item 7: FR-N-01 does
                // not list it). The rows above are reached for a LIB item too — the "build"
                // node of a library is still a BUILD node — which is why the item-type guard
                // below comes before the order lookup.
                case VERIFY, DAILY_DEPLOY, LIB_SECURITY, LIB_PUBLISH -> null;
            };
            if (key == null || event.orderId() <= 0) {
                return;
            }
            if (isRollbackInstance(event.instanceId()) || isLibOrder(event.orderId())) {
                return;
            }
            ReleaseOrderPO order = orderMapper.selectById(event.orderId());
            if (order == null || order.getPublishNo() == null) {
                return;
            }
            Set<MemberRole> roles = event.nodeType() == NodeType.PROD_DEPLOY
                ? EnumSet.of(MemberRole.OPS) : EnumSet.of(MemberRole.DEV, MemberRole.TEST);
            String node = switch (event.nodeType()) {
                case BUILD -> "构建";
                case TEST_DEPLOY -> "测试部署";
                case PROD_DEPLOY -> "生产部署";
                case DAILY_DEPLOY -> "日常部署";
                case VERIFY -> "验证";
                // Reached only for an unknown/LIB node that slipped past the guard; the labels
                // exist so the switch stays exhaustive when the enum grows.
                case LIB_SECURITY -> "二方库安全检查";
                case LIB_PUBLISH -> "二方库入库";
            };
            StringBuilder body = new StringBuilder("发布单 ")
                .append(order.getPublishNo()).append(" 的").append(node).append("节点执行失败");
            if (event.message() != null && !event.message().isBlank()) {
                body.append("：").append(event.message());
            }
            body.append("。请到发布记录查看详情。");
            notifications.notify(new NotificationCommand(key, order.getPublishNo(),
                order.getPublishNo(), key.label(), body.toString(),
                String.valueOf(event.instanceId()),
                NotificationAudiences.taskMembers(order.getId(), roles)));
        } catch (RuntimeException ex) {
            log.error("node failure notification failed: instanceId={}, node={}",
                event.instanceId(), event.nodeType(), ex);
        }
    }

    /** {@code TEST_ENV_READY}: the test environment of the release is ready (design section 3). */
    @EventListener
    public void onTestEnvReady(TestEnvReadyEvent event) {
        try {
            ReleaseOrderPO order = orderMapper.selectOne(Wrappers.<ReleaseOrderPO>lambdaQuery()
                .eq(ReleaseOrderPO::getPublishNo, event.publishNo())
                .last("LIMIT 1"));
            if (order == null) {
                return;
            }
            notifications.notify(new NotificationCommand(
                NotificationEventKey.TEST_ENV_READY, event.publishNo(), event.publishNo(),
                NotificationEventKey.TEST_ENV_READY.label(),
                "发布单 " + event.publishNo() + " 的测试环境已就绪，可以开始验证。",
                String.valueOf(event.instanceId()),
                NotificationAudiences.taskMembers(order.getId(),
                    EnumSet.of(MemberRole.TEST, MemberRole.DEV))));
        } catch (RuntimeException ex) {
            log.error("test-ready notification failed: instanceId={}", event.instanceId(), ex);
        }
    }

    /**
     * {@code PROD_DONE}: a non-rollback instance finished {@code DONE}. The instance only reaches
     * {@code DONE} after its {@code PROD_DEPLOY} node succeeded, so the instance status is the
     * production result the design names.
     */
    @EventListener
    public void onInstanceFinishedNotification(InstanceFinishedEvent event) {
        try {
            if (event.status() != InstanceStatus.DONE || event.orderId() <= 0) {
                return;
            }
            if (isRollbackInstance(event.instanceId())) {
                return;
            }
            // P6-A design §5 item 7: a second-party library emits no notification (FR-N-01 does
            // not list it). Its history is the publish record's LIB nodes plus the PUBLISH audit
            // row. The guard is here, not only on the node listener, because a LIB instance also
            // finishes DONE after its LIB_PUBLISH node.
            if (isLibOrder(event.orderId())) {
                return;
            }
            ReleaseOrderPO order = orderMapper.selectById(event.orderId());
            if (order == null || order.getPublishNo() == null) {
                return;
            }
            notifications.notify(new NotificationCommand(
                NotificationEventKey.PROD_DONE, order.getPublishNo(), order.getPublishNo(),
                NotificationEventKey.PROD_DONE.label(),
                "发布单 " + order.getPublishNo() + " 已生产完成。",
                String.valueOf(event.instanceId()),
                NotificationAudiences.taskMembers(order.getId(),
                    EnumSet.of(MemberRole.OPS, MemberRole.DEV))));
        } catch (RuntimeException ex) {
            log.error("production-done notification failed: instanceId={}", event.instanceId(), ex);
        }
    }

    /**
     * The item event a node result produces, or {@code null} when there is none. The item type
     * selects the vocabulary: a {@code UNIT} (and the daily pipeline, which never moves an item)
     * keeps the deployment mapping, while a {@code LIB} item only ever moves on its own three
     * nodes (P6-A design §2).
     *
     * <p>For a LIB item, a successful {@code BUILD} or {@code LIB_SECURITY} deliberately maps to
     * <em>nothing</em>: the item stays {@code BUILDING} through both, and only the artifact
     * upload ends it ({@link ItemEvent#LIB_OK}). This is the branch that keeps a library out of
     * {@code TESTING}.
     */
    static ItemEvent itemEventOf(UnitType itemType, NodeType nodeType, NodeStatus nodeStatus) {
        if (itemType == UnitType.LIB) {
            return libItemEventOf(nodeType, nodeStatus);
        }
        boolean success = nodeStatus == NodeStatus.SUCCESS;
        return switch (nodeType) {
            case BUILD -> success ? ItemEvent.BUILD_OK : ItemEvent.BUILD_FAIL;
            case TEST_DEPLOY -> success ? ItemEvent.TEST_DEPLOY_OK : ItemEvent.TEST_DEPLOY_FAIL;
            case PROD_DEPLOY -> success ? ItemEvent.PROD_OK : ItemEvent.PROD_FAIL;
            // A LIB node never appears in a UNIT instance; VERIFY/DAILY_DEPLOY have no event.
            case VERIFY, DAILY_DEPLOY, LIB_SECURITY, LIB_PUBLISH -> null;
        };
    }

    /**
     * The item event of a LIB node result: failure always ends the item, success only of the
     * artifact upload. The other node types are unreachable for a LIB instance and map to
     * {@code null} instead of inventing a deployment state.
     */
    private static ItemEvent libItemEventOf(NodeType nodeType, NodeStatus nodeStatus) {
        boolean success = nodeStatus == NodeStatus.SUCCESS;
        return switch (nodeType) {
            case BUILD, LIB_SECURITY -> success ? null : ItemEvent.LIB_FAIL;
            case LIB_PUBLISH -> success ? ItemEvent.LIB_OK : ItemEvent.LIB_FAIL;
            case TEST_DEPLOY, VERIFY, PROD_DEPLOY, DAILY_DEPLOY -> null;
        };
    }

    /** The item type stored on the row; a blank/unknown value degrades to {@code UNIT}. */
    private static UnitType unitTypeOf(ReleaseItemPO item) {
        String raw = item.getType();
        if (raw == null || raw.isBlank()) {
            return UnitType.UNIT;
        }
        try {
            return UnitType.valueOf(raw.trim());
        } catch (IllegalArgumentException ex) {
            log.warn("变更项 {} has an unknown type '{}', treated as UNIT", item.getId(), raw);
            return UnitType.UNIT;
        }
    }

    /**
     * Whether the release order belongs to a second-party library. Read-only and cheap: one
     * order lookup plus one item lookup, only on the notification paths (never inside a handler).
     */
    private boolean isLibOrder(long orderId) {
        ReleaseOrderPO order = orderMapper.selectById(orderId);
        if (order == null || order.getItemId() == null) {
            return false;
        }
        return unitTypeOfItem(order.getItemId()) == UnitType.LIB;
    }

    private UnitType unitTypeOfItem(long itemId) {
        ReleaseItemPO item = itemMapper.selectById(itemId);
        return item == null ? UnitType.UNIT : unitTypeOf(item);
    }

    private boolean isRollbackInstance(long instanceId) {
        PipelineInstancePO instance = instanceMapper.selectById(instanceId);
        return instance != null
            && TriggerType.ROLLBACK.name().equals(instance.getTriggerType());
    }

    /**
     * Mirrors the item's new status onto its 发布单 row. The global 发布记录 page shows
     * {@code release_order.status}, so an order must not keep {@code INIT} after the pipeline moved
     * the item to DONE/FAILED. The update is deliberately not a {@code row_version} CAS: the order
     * status is a read model of the item and the explicit sync points (execute, approval, freeze,
     * stop/exit) write it the same way.
     */
    private void mirrorOrderStatus(long orderId, long itemId) {
        if (orderId <= 0) {
            return;
        }
        ReleaseItemPO advanced = itemMapper.selectById(itemId);
        if (advanced == null || advanced.getStatus() == null) {
            return;
        }
        String status = advanced.getStatus();
        ReleaseOrderPO order = orderMapper.selectById(orderId);
        if (order == null || status.equals(order.getStatus())) {
            return;
        }
        orderMapper.update(null, Wrappers.<ReleaseOrderPO>lambdaUpdate()
            .eq(ReleaseOrderPO::getId, orderId)
            .set(ReleaseOrderPO::getStatus, status));
    }

    private static String messageOf(RuntimeException ex) {
        String message = ex.getMessage();
        return message == null || message.isBlank()
            ? ex.getClass().getSimpleName() : message;
    }
}