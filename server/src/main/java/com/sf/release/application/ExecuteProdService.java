package com.sf.release.application;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sf.release.common.BizError;
import com.sf.release.common.BizException;
import com.sf.release.common.security.Permissions;
import com.sf.release.domain.approval.ApprovalRole;
import com.sf.release.domain.pipeline.InstanceStatus;
import com.sf.release.domain.pipeline.NodeHandler;
import com.sf.release.domain.pipeline.NodeHandlerRegistry;
import com.sf.release.domain.pipeline.NodeStatus;
import com.sf.release.domain.pipeline.NodeTask;
import com.sf.release.domain.pipeline.NodeType;
import com.sf.release.domain.pipeline.PipelineInstance;
import com.sf.release.domain.pipeline.PipelineNode;
import com.sf.release.domain.pipeline.TaskType;
import com.sf.release.domain.pipeline.port.PipelineAdvancer;
import com.sf.release.domain.pipeline.port.PipelineRepository;
import com.sf.release.domain.pipeline.port.TransactionRunner;
import com.sf.release.domain.task.ItemEvent;
import com.sf.release.domain.task.ItemStatus;
import com.sf.release.infra.repo.AdmissionSnapshotMapper;
import com.sf.release.infra.repo.ReleaseItemMapper;
import com.sf.release.infra.repo.ReleaseOrderMapper;
import com.sf.release.infra.repo.ReleaseQueryMapper;
import com.sf.release.infra.repo.po.AdmissionSnapshotPO;
import com.sf.release.infra.repo.po.ReleaseHeadRow;
import com.sf.release.infra.repo.po.ReleaseItemPO;
import com.sf.release.infra.repo.po.ReleaseOrderPO;
import com.sf.release.infra.security.CurrentUser;
import com.sf.release.infra.security.ScopeGuard;
import com.sf.release.infra.security.UserContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;

/**
 * 执行生产 / 恢复生产执行 of one 发布单 (tech design 4.6/4.7, {@code POST
 * /releases/{publishNo}/execute}): the human "go" of the production pipeline.
 *
 * <p><strong>One endpoint, two states.</strong>
 * <ul>
 *   <li><strong>First execution</strong>: the 变更项 is {@code READY}. The review must pass, the
 *       order becomes {@code EXECUTING}, {@code EXECUTE} moves the item {@code READY →
 *       PROD_DEPLOYING}, and the paused instance is resumed so the {@code PROD_DEPLOY} node starts
 *       its first batch.</li>
 *   <li><strong>Resume after a batch-boundary pause</strong>: the item is already
 *       {@code PROD_DEPLOYING} and the instance is {@code PAUSED} at {@code PROD_DEPLOY}. The review
 *       is re-run, but <strong>no second {@code EXECUTE} is applied</strong> (the state machine would
 *       reject it anyway); the instance is resumed and the handler is re-driven, which triggers the
 *       next batch.</li>
 * </ul>
 * In both cases the instance must be paused at {@code PROD_DEPLOY}: an instance that is running is
 * already executing, and one paused elsewhere is not a production stop.
 *
 * <p><strong>A freeze that appeared while the item was READY pulls it back.</strong> If a freeze is
 * in force now and the snapshot did not know about it, the execute attempt appends the missing
 * {@code DEPT_MANAGER} step to the latest snapshot's chain, applies {@code FREEZE_HIT}
 * ({@code READY → PENDING_APPROVAL}), mirrors the order and answers 40002 with the reason. That is
 * the same treatment a freeze declared at that moment gives the release (design 4.2/4.6), applied at
 * the last moment instead of at declaration. A broken waiver is a plain 40002 (nothing to pull back:
 * the item was admitted on an exemption that no longer exists), and a digest mismatch is the hard
 * 40007 (J27) that no waiver can lift.
 *
 * <p><strong>No long transaction.</strong> The review and the state writes read/write short
 * transactions; the engine calls happen in the handler after the instance was resumed, never while a
 * row lock is held.
 */
@Service
public class ExecuteProdService {

    private static final Logger log = LoggerFactory.getLogger(ExecuteProdService.class);

    /** {@code release_order.status} while the production pipeline runs (design 4.9). The 变更项
     * orthogonal to it is {@link ItemStatus#PROD_DEPLOYING}; the order value is not an item status,
     * so it is spelled here instead of borrowed from the item enum. */
    private static final String ORDER_EXECUTING = "EXECUTING";

    private final ReleaseOrderMapper orderMapper;
    private final ReleaseItemMapper itemMapper;
    private final AdmissionSnapshotMapper snapshotMapper;
    private final ReleaseQueryMapper queryMapper;
    private final PipelineRepository repository;
    private final NodeHandlerRegistry handlerRegistry;
    private final PipelineAdvancer advancer;
    private final ReviewService reviewService;
    private final ApprovalService approvalService;
    private final ItemTransitions transitions;
    private final TransactionRunner transactionRunner;

    public ExecuteProdService(ReleaseOrderMapper orderMapper,
                              ReleaseItemMapper itemMapper,
                              AdmissionSnapshotMapper snapshotMapper,
                              ReleaseQueryMapper queryMapper,
                              PipelineRepository repository,
                              NodeHandlerRegistry handlerRegistry,
                              PipelineAdvancer advancer,
                              ReviewService reviewService,
                              ApprovalService approvalService,
                              ItemTransitions transitions,
                              TransactionRunner transactionRunner) {
        this.orderMapper = Objects.requireNonNull(orderMapper, "orderMapper must not be null");
        this.itemMapper = Objects.requireNonNull(itemMapper, "itemMapper must not be null");
        this.snapshotMapper = Objects.requireNonNull(snapshotMapper,
            "snapshotMapper must not be null");
        this.queryMapper = Objects.requireNonNull(queryMapper, "queryMapper must not be null");
        this.repository = Objects.requireNonNull(repository, "repository must not be null");
        this.handlerRegistry = Objects.requireNonNull(handlerRegistry,
            "handlerRegistry must not be null");
        this.advancer = Objects.requireNonNull(advancer, "advancer must not be null");
        this.reviewService = Objects.requireNonNull(reviewService,
            "reviewService must not be null");
        this.approvalService = Objects.requireNonNull(approvalService,
            "approvalService must not be null");
        this.transitions = Objects.requireNonNull(transitions, "transitions must not be null");
        this.transactionRunner = Objects.requireNonNull(transactionRunner,
            "transactionRunner must not be null");
    }

    /**
     * What the execute endpoint answers: the 变更项 status after the call (always
     * {@code PROD_DEPLOYING} on success), the node the pipeline is executing, the number of
     * production batches the instance was generated with, and the review the call passed.
     */
    public record ExecuteResult(String publishNo, String status, String node, int batchCount,
                                ReviewService.ReviewView review) {
    }

    /**
     * @throws BizException 40000 unknown publish number; 40001 the item is neither {@code READY} nor
     *                      {@code PROD_DEPLOYING}, the instance is not paused at
     *                      {@code PROD_DEPLOY}, or there is no admission snapshot; 40002 a freeze is
     *                      in force or a waiver of the snapshot expired; 40007 the instance image
     *                      differs from the admitted digest; 40009 no {@code control:execute-prod}
     *                      or no data scope on the unit
     */
    public ExecuteResult execute(String publishNo) {
        CurrentUser user = UserContext.current();
        requirePerm(user, Permissions.CONTROL_EXECUTE_PROD);
        String no = requirePublishNo(publishNo);
        ReleaseOrderPO order = requireOrder(no);
        ReleaseItemPO item = requireItem(order);
        ScopeGuard.assertUnitInScope(item.getUnitId() == null ? 0L : item.getUnitId());
        ItemStatus status = AdmissionService.statusOf(item);
        if (status != ItemStatus.READY && status != ItemStatus.PROD_DEPLOYING) {
            throw new BizException(BizError.STATE_NOT_ALLOWED,
                "只有待执行（READY）或生产部署中（PROD_DEPLOYING）的变更项可以执行生产，当前是 "
                    + status.name(),
                Map.of("publishNo", no, "itemId", item.getId(), "status", status.name()));
        }
        AdmissionSnapshotPO snapshot = snapshotMapper.selectLatestByOrder(order.getId());
        if (snapshot == null) {
            throw new BizException(BizError.STATE_NOT_ALLOWED,
                "发布单尚未提交生产，没有准入快照，不能执行", Map.of("publishNo", no));
        }
        ReleaseHeadRow head = queryMapper.selectHeadRow(no);
        if (head == null) {
            throw new BizException(BizError.PARAM_INVALID, "发布单不存在：" + no,
                Map.of("publishNo", no));
        }
        if (head.getInstanceId() == null) {
            throw new BizException(BizError.STATE_NOT_ALLOWED, "该发布单没有执行记录",
                Map.of("publishNo", no));
        }
        PipelineInstance instance = repository.loadInstance(head.getInstanceId());
        if (instance == null) {
            throw new BizException(BizError.STATE_NOT_ALLOWED, "流水线实例不存在",
                Map.of("publishNo", no, "instanceId", head.getInstanceId()));
        }
        if (instance.status() != InstanceStatus.PAUSED
                || instance.currentNode() != NodeType.PROD_DEPLOY) {
            throw new BizException(BizError.STATE_NOT_ALLOWED,
                "实例当前是 " + instance.status() + " / 节点 "
                    + (instance.currentNode() == null ? "无" : instance.currentNode())
                    + "，只有暂停（PAUSED）在生产部署（PROD_DEPLOY）时可以执行或恢复生产",
                Map.of("publishNo", no, "instanceId", instance.id(),
                    "instanceStatus", instance.status().name()));
        }

        ReviewService.ReviewView review = reviewService.reviewOf(order, item, snapshot);
        if (!review.digestMatched()) {
            throw new BizException(BizError.DIGEST_MISMATCH,
                String.join("；", review.reasons()), reviewPayload(no, item, review));
        }
        if (review.blocked()) {
            if (status == ItemStatus.READY && review.frozen()) {
                pullBackForFreeze(order, item, snapshot, no);
            }
            throw new BizException(BizError.GATE_BLOCKED, String.join("；", review.reasons()),
                reviewPayload(no, item, review));
        }

        if (status == ItemStatus.READY) {
            transactionRunner.inTransaction(() -> {
                syncOrderStatus(order.getId(), ORDER_EXECUTING);
                applyTransition(item.getId(), ItemEvent.EXECUTE, no);
                return null;
            });
        }
        if (!repository.resumeInstance(instance.id(), Instant.now())) {
            throw new BizException(BizError.STATE_NOT_ALLOWED,
                "实例状态已变化，执行未生效，请刷新后重试",
                Map.of("publishNo", no, "instanceId", instance.id()));
        }
        advancer.advance(instance.id());
        reDriveProductionNode(instance.id());
        int batches = batchCount(instance.id());
        log.info("production execution started: publishNo={}, instanceId={}, itemId={},"
                + " previousStatus={}, batches={}, by={}", no, instance.id(), item.getId(),
            status, batches, user.userId());
        return new ExecuteResult(no, ItemStatus.PROD_DEPLOYING.name(),
            NodeType.PROD_DEPLOY.name(), batches, review);
    }

    // ------------------------------------------------------------------ state changes

    /**
     * The freeze that appeared after admission: raise the second signature on the <em>latest</em>
     * snapshot's chain and pull the item back into approval, in one transaction so a release can
     * never be found {@code READY} with a chain that requires a signature nobody will be asked for.
     */
    private void pullBackForFreeze(ReleaseOrderPO order, ReleaseItemPO item,
                                   AdmissionSnapshotPO snapshot, String publishNo) {
        transactionRunner.inTransaction(() -> {
            approvalService.appendIfMissing(order.getId(), snapshot.getId(),
                ApprovalRole.DEPT_MANAGER);
            applyTransition(item.getId(), ItemEvent.FREEZE_HIT, publishNo);
            syncOrderStatus(order.getId(), ItemStatus.PENDING_APPROVAL.name());
            return null;
        });
        log.info("production execution blocked by a freeze: pulled back into approval:"
                + " publishNo={}, itemId={}, snapshotId={}", publishNo, item.getId(),
            snapshot.getId());
    }

    /** Applies one main-state event, turning "the state changed under us" into a 40001. */
    private void applyTransition(long itemId, ItemEvent event, String publishNo) {
        ItemTransitions.Outcome outcome = transitions.apply(itemId, event);
        if (outcome != ItemTransitions.Outcome.APPLIED) {
            throw new BizException(BizError.STATE_NOT_ALLOWED,
                "变更项状态已变化，执行未生效（" + event + "）",
                Map.of("publishNo", publishNo, "itemId", itemId, "event", event.name(),
                    "outcome", outcome.name()));
        }
    }

    /**
     * Mirrors the item's state on the order row with the {@code row_version} CAS. The row is read
     * with a locking read first, so the version used is the committed one (another writer cannot
     * slip between the read and the update without blocking on the same row).
     */
    private void syncOrderStatus(long orderId, String status) {
        ReleaseOrderPO current = orderMapper.selectByIdForUpdate(orderId);
        if (current == null) {
            throw new IllegalStateException("release_order disappeared during execution: " + orderId);
        }
        if (status.equals(current.getStatus())) {
            return;
        }
        int version = current.getRowVersion() == null ? 0 : current.getRowVersion();
        int rows = orderMapper.update(null, Wrappers.<ReleaseOrderPO>lambdaUpdate()
            .eq(ReleaseOrderPO::getId, orderId)
            .eq(ReleaseOrderPO::getRowVersion, version)
            .set(ReleaseOrderPO::getStatus, status)
            .set(ReleaseOrderPO::getRowVersion, version + 1));
        if (rows != 1) {
            throw new BizException(BizError.STATE_NOT_ALLOWED,
                "发布单状态已变化，执行未生效，请刷新后重试", Map.of("orderId", orderId));
        }
    }

    // ------------------------------------------------------------------ pipeline driving

    /**
     * Re-drives the {@code PROD_DEPLOY} handler after the resume. {@code advance} enters the node
     * when it is still {@code PENDING}; when the instance was paused <em>inside</em> the node (the
     * batch-boundary pause) the node is already {@code RUNNING} and the handler must be asked
     * directly. {@link NodeHandler#start(PipelineInstance)} is idempotent (an in-flight batch is not
     * triggered twice), so calling it after either path is safe.
     */
    private void reDriveProductionNode(long instanceId) {
        PipelineInstance resumed = repository.loadInstance(instanceId);
        if (resumed == null || resumed.status() != InstanceStatus.RUNNING) {
            return;
        }
        PipelineNode node = resumed.node(NodeType.PROD_DEPLOY);
        if (node == null || node.status() != NodeStatus.RUNNING) {
            return;
        }
        NodeHandler handler = handlerRegistry.handler(NodeType.PROD_DEPLOY);
        if (handler == null) {
            return;
        }
        try {
            handler.start(resumed);
        } catch (RuntimeException ex) {
            // The handler already persisted the failed task; run the orchestrator's failure path in
            // its own transaction before reporting the engine problem to the caller.
            advancer.advance(instanceId);
            throw ex;
        }
    }

    /** The number of production batches the instance was generated with (distinct batch numbers). */
    private int batchCount(long instanceId) {
        PipelineInstance instance = repository.loadInstance(instanceId);
        if (instance == null) {
            return 0;
        }
        PipelineNode node = instance.node(NodeType.PROD_DEPLOY);
        if (node == null) {
            return 0;
        }
        TreeSet<Integer> batches = new TreeSet<>();
        for (NodeTask task : node.tasks()) {
            if (task.taskType() == TaskType.DEPLOY) {
                batches.add(task.batchNo());
            }
        }
        return batches.size();
    }

    // ------------------------------------------------------------------ helpers

    private static Map<String, Object> reviewPayload(String publishNo, ReleaseItemPO item,
                                                     ReviewService.ReviewView review) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("publishNo", publishNo);
        payload.put("itemId", item.getId());
        payload.put("reasons", review.reasons());
        payload.put("frozen", review.frozen());
        payload.put("freezeIds", review.freezeIds());
        payload.put("waiverInvalid", review.waiverInvalid());
        payload.put("digestMatched", review.digestMatched());
        payload.put("review", review);
        return payload;
    }

    private static void requirePerm(CurrentUser user, String perm) {
        if (!user.hasPerm(perm)) {
            throw new BizException(BizError.FORBIDDEN, "无权执行该操作：" + perm,
                Map.of("perm", perm));
        }
    }

    private ReleaseOrderPO requireOrder(String publishNo) {
        ReleaseOrderPO order = orderMapper.selectOne(Wrappers.<ReleaseOrderPO>lambdaQuery()
            .eq(ReleaseOrderPO::getPublishNo, publishNo));
        if (order == null) {
            throw new BizException(BizError.PARAM_INVALID, "发布单不存在：" + publishNo,
                Map.of("publishNo", publishNo));
        }
        return order;
    }

    private ReleaseItemPO requireItem(ReleaseOrderPO order) {
        ReleaseItemPO item = itemMapper.selectById(order.getItemId());
        if (item == null) {
            throw new BizException(BizError.PARAM_INVALID, "变更项不存在",
                Map.of("publishNo", order.getPublishNo(), "itemId", order.getItemId()));
        }
        return item;
    }

    private static String requirePublishNo(String raw) {
        String no = raw == null ? "" : raw.trim();
        if (no.isEmpty()) {
            throw new BizException(BizError.PARAM_INVALID, "发布单号不能为空",
                Map.of("publishNo", "不能为空"));
        }
        return no;
    }
}