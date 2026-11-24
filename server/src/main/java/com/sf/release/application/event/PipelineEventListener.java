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
import com.sf.release.infra.repo.PipelineInstanceMapper;
import com.sf.release.infra.repo.ReleaseOrderMapper;
import com.sf.release.infra.repo.po.PipelineInstancePO;
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
    private final ItemTransitions transitions;
    private final ItemFailureAppService failureService;
    private final NotificationService notifications;

    public PipelineEventListener(ReleaseOrderMapper orderMapper,
                                 PipelineInstanceMapper instanceMapper,
                                 ItemTransitions transitions,
                                 ItemFailureAppService failureService,
                                 NotificationService notifications) {
        this.orderMapper = orderMapper;
        this.instanceMapper = instanceMapper;
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
        ItemEvent itemEvent = itemEventOf(event.nodeType(), event.status());
        if (itemEvent == null) {
            log.debug("node {} result does not change an item state (instanceId={})",
                event.nodeType(), event.instanceId());
            return;
        }
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
                case VERIFY, DAILY_DEPLOY -> null;
            };
            if (key == null || event.orderId() <= 0) {
                return;
            }
            if (isRollbackInstance(event.instanceId())) {
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
     * The item event a node result produces, or {@code null} when there is none. The daily deploy
     * has none by design: a daily release never moves the 变更项 state machine (P5-A design §5),
     * and its instance has no release order anyway (the order-id guard below would also skip it).
     */
    static ItemEvent itemEventOf(NodeType nodeType, NodeStatus nodeStatus) {
        boolean success = nodeStatus == NodeStatus.SUCCESS;
        return switch (nodeType) {
            case BUILD -> success ? ItemEvent.BUILD_OK : ItemEvent.BUILD_FAIL;
            case TEST_DEPLOY -> success ? ItemEvent.TEST_DEPLOY_OK : ItemEvent.TEST_DEPLOY_FAIL;
            case PROD_DEPLOY -> success ? ItemEvent.PROD_OK : ItemEvent.PROD_FAIL;
            case VERIFY, DAILY_DEPLOY -> null;
        };
    }

    private boolean isRollbackInstance(long instanceId) {
        PipelineInstancePO instance = instanceMapper.selectById(instanceId);
        return instance != null
            && TriggerType.ROLLBACK.name().equals(instance.getTriggerType());
    }

    private static String messageOf(RuntimeException ex) {
        String message = ex.getMessage();
        return message == null || message.isBlank()
            ? ex.getClass().getSimpleName() : message;
    }
}