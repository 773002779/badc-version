package com.sf.release.application;

import com.sf.release.application.audit.AuditCommand;
import com.sf.release.application.audit.AuditService;
import com.sf.release.common.BizError;
import com.sf.release.common.BizException;
import com.sf.release.common.security.Permissions;
import com.sf.release.domain.audit.AuditAction;
import com.sf.release.domain.pipeline.NodeTask;
import com.sf.release.domain.pipeline.NodeTaskStatus;
import com.sf.release.domain.pipeline.NodeType;
import com.sf.release.domain.pipeline.PipelineOrchestrator;
import com.sf.release.domain.pipeline.ReleaseOrder;
import com.sf.release.domain.pipeline.TaskType;
import com.sf.release.domain.pipeline.TriggerType;
import com.sf.release.domain.pipeline.port.PipelineRepository;
import com.sf.release.domain.resource.EnvKind;
import com.sf.release.domain.task.ItemEvent;
import com.sf.release.domain.task.ItemStatus;
import com.sf.release.domain.task.ReleaseItem;
import com.sf.release.infra.lock.UnitLockService;
import com.sf.release.infra.repo.EnvDeMapper;
import com.sf.release.infra.repo.ReleaseItemMapper;
import com.sf.release.infra.repo.ReleaseOrderMapper;
import com.sf.release.infra.repo.ReleaseQueryMapper;
import com.sf.release.infra.repo.po.EnvDePO;
import com.sf.release.infra.repo.po.NodeTaskPO;
import com.sf.release.infra.repo.po.PipelineInstancePO;
import com.sf.release.infra.repo.po.ReleaseHeadRow;
import com.sf.release.infra.repo.po.ReleaseItemPO;
import com.sf.release.infra.repo.po.ReleaseOrderPO;
import com.sf.release.infra.security.CurrentUser;
import com.sf.release.infra.security.ScopeGuard;
import com.sf.release.infra.security.UserContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The 单 DE 重新部署 use case (P6-G design §1 / requirements PART-04).
 *
 * <p><strong>What it does.</strong> A failed production deployment row is re-executed on a new
 * {@code RERUN_FROM_NODE} instance that resumes at {@code PROD_DEPLOY} with the previous
 * instance's image and commit. The whole <em>target batch</em> of the failed DE stays
 * {@code PENDING}: one failed DE usually shares its batch configuration and a partially
 * redeployed batch would mix two versions, so the batch is retried as a whole. Every other
 * production batch and every earlier node is pre-marked {@code SKIPPED}.
 *
 * <p><strong>Eligibility (all required).</strong>
 * <ol>
 *   <li>the caller has {@code control:execute-prod} and the unit is inside the data scope;</li>
 *   <li>the task belongs to the <em>latest</em> pipeline instance of the 发布单;</li>
 *   <li>it is a {@code PROD_DEPLOY}/{@code DEPLOY} task and its latest attempt is
 *       {@code FAILED}/{@code TIMEOUT}; a successful or non-deploy task is refused (the route
 *       must not become a way around the admission);</li>
 *   <li>the 变更项 and the 发布单 are in {@code FAILED} or {@code PROD_DEPLOYING} (a closed,
 *       terminated or done release is history);</li>
 *   <li>the latest instance has an image digest to reuse — a redeploy never rebuilds.</li>
 * </ol>
 *
 * <p><strong>Transaction and lock.</strong> The unit lock is taken before the transaction (40006
 * on conflict) and bound to the new instance inside it, exactly like publish/rerun. One
 * transaction moves the item {@code FAILED -RERUN-> TESTING -EXECUTE_RERUN-> PROD_DEPLOYING}
 * (only existing events and edges; an already {@code PROD_DEPLOYING} item is left as it is),
 * invalidates the unfinished gate checks (old results no longer describe the redeployed object),
 * and creates the new instance. The audit {@code RERUN} row is written after the commit, the
 * pipeline is started after that.
 *
 * <p><strong>History is read-only.</strong> Nothing on the previous instance or its tasks is
 * touched; the redeploy is a new execution under the same publish number.
 */
@Service
public class SingleDeRedeployService {

    private static final Logger log = LoggerFactory.getLogger(SingleDeRedeployService.class);

    private static final Set<ItemStatus> REDEPLOYABLE =
        EnumSet.of(ItemStatus.FAILED, ItemStatus.PROD_DEPLOYING);

    private static final Set<NodeTaskStatus> RETRYABLE =
        EnumSet.of(NodeTaskStatus.FAILED, NodeTaskStatus.TIMEOUT);

    private static final Set<EnvKind> PRODUCTION_KINDS =
        EnumSet.of(EnvKind.PROD, EnvKind.DR, EnvKind.BLUE);

    private final ReleaseQueryMapper queryMapper;
    private final ReleaseOrderMapper orderMapper;
    private final ReleaseItemMapper itemMapper;
    private final EnvDeMapper envDeMapper;
    private final PipelineRepository pipelineRepository;
    private final PublishAppService publishAppService;
    private final GateService gateService;
    private final UnitLockService unitLockService;
    private final PipelineOrchestrator orchestrator;
    private final ItemTransitions transitions;
    private final AuditService audit;
    /** Mirrors publish/rerun: tests turn the immediate start off and drive the nodes themselves. */
    private final boolean autoStart;
    private final TransactionTemplate transactionTemplate;

    public SingleDeRedeployService(ReleaseQueryMapper queryMapper,
                                   ReleaseOrderMapper orderMapper,
                                   ReleaseItemMapper itemMapper,
                                   EnvDeMapper envDeMapper,
                                   PipelineRepository pipelineRepository,
                                   PublishAppService publishAppService,
                                   GateService gateService,
                                   UnitLockService unitLockService,
                                   PipelineOrchestrator orchestrator,
                                   ItemTransitions transitions,
                                   AuditService audit,
                                   @Value("${release.pipeline.auto-start:true}") boolean autoStart,
                                   PlatformTransactionManager transactionManager) {
        this.queryMapper = Objects.requireNonNull(queryMapper, "queryMapper must not be null");
        this.orderMapper = Objects.requireNonNull(orderMapper, "orderMapper must not be null");
        this.itemMapper = Objects.requireNonNull(itemMapper, "itemMapper must not be null");
        this.envDeMapper = Objects.requireNonNull(envDeMapper, "envDeMapper must not be null");
        this.pipelineRepository = Objects.requireNonNull(pipelineRepository,
            "pipelineRepository must not be null");
        this.publishAppService = Objects.requireNonNull(publishAppService,
            "publishAppService must not be null");
        this.gateService = Objects.requireNonNull(gateService, "gateService must not be null");
        this.unitLockService = Objects.requireNonNull(unitLockService,
            "unitLockService must not be null");
        this.orchestrator = Objects.requireNonNull(orchestrator,
            "orchestrator must not be null");
        this.transitions = Objects.requireNonNull(transitions, "transitions must not be null");
        this.audit = Objects.requireNonNull(audit, "audit must not be null");
        this.autoStart = autoStart;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /**
     * Redeploys the failed production DE of {@code taskId} under {@code publishNo}.
     *
     * @throws BizException 40000 unknown publish number or task (or a task of another 发布单);
     *                      40009 missing {@code control:execute-prod} or no unit scope;
     *                      40001 the task/release is not eligible; 40006 the unit is locked;
     *                      40011 the unit's check configuration is not executable
     */
    public RedeployResult redeploy(String publishNo, long taskId) {
        CurrentUser user = UserContext.current();
        if (!user.hasPerm(Permissions.CONTROL_EXECUTE_PROD)) {
            throw new BizException(BizError.FORBIDDEN,
                "无权执行该操作：" + Permissions.CONTROL_EXECUTE_PROD,
                Map.of("perm", Permissions.CONTROL_EXECUTE_PROD));
        }
        String no = requirePublishNo(publishNo);
        ReleaseHeadRow head = queryMapper.selectHeadRow(no);
        if (head == null) {
            throw notFound(no);
        }
        NodeTask task = pipelineRepository.loadTask(taskId);
        if (task == null || !belongsTo(no, task)) {
            throw taskNotFound(no, taskId);
        }
        if (head.getUnitId() == null) {
            throw notFound(no);
        }
        long unitId = head.getUnitId();
        ScopeGuard.assertUnitInScope(unitId);

        Eligibility eligibility = requireEligible(no, head, task, unitId);

        if (!unitLockService.acquire(unitId, UnitLockService.UNBOUND_INSTANCE_ID)) {
            throw publishAppService.unitLocked(unitId, eligibility.itemId());
        }
        try {
            Created created = transactionTemplate.execute(tx -> redeployInTransaction(
                eligibility, task, unitId));
            Created result = Objects.requireNonNull(created, "redeploy result");
            audit.record(AuditCommand.of(AuditAction.RERUN, eligibility.order().publishNo(),
                    eligibility.order().publishNo(),
                    "单 DE 重部署（目标 " + eligibility.deName() + "）")
                .before(before(task, eligibility.itemStatus()))
                .after(after(result, eligibility, task))
                .scopeUnitId(unitId)
                .build());
            if (autoStart) {
                try {
                    orchestrator.advance(result.instanceId());
                } catch (RuntimeException ex) {
                    log.error("single-DE redeploy pipeline did not start; reconcile enters its"
                        + " node later: instanceId={}", result.instanceId(), ex);
                }
            }
            return new RedeployResult(eligibility.order().publishNo(),
                result.pipelineNo(), result.instanceId(), NodeType.PROD_DEPLOY.name(),
                task.batchNo(), eligibility.targetDeIds(), eligibility.deName());
        } catch (PublishAppService.LockLostException ex) {
            // The lock row belongs to somebody else now; releasing could steal the new holder's
            // lock, so — exactly like publish/rerun — nothing is released on this path.
            throw ex.failure();
        } catch (RuntimeException ex) {
            // The transaction rolled back, including the bind, so the sentinel reservation is
            // still ours and an ownership-scoped release is safe.
            unitLockService.release(unitId, UnitLockService.UNBOUND_INSTANCE_ID);
            throw ex;
        }
    }

    // ------------------------------------------------------------------ eligibility

    /**
     * Validates the task, its instance, the release status and the target DE. Everything is read
     * before the lock so an ineligible call reserves nothing.
     */
    private Eligibility requireEligible(String publishNo, ReleaseHeadRow head, NodeTask task,
                                        long unitId) {
        if (head.getInstanceId() == null) {
            throw notEligible("发布单没有流水线实例", Map.of("publishNo", publishNo));
        }
        if (!head.getInstanceId().equals(task.instanceId())) {
            throw notEligible("历史实例只读：只能重部署最新实例的任务",
                Map.of("publishNo", publishNo, "taskId", task.id(),
                    "instanceId", task.instanceId(), "latestInstanceId", head.getInstanceId()));
        }
        if (task.nodeType() != NodeType.PROD_DEPLOY || task.taskType() != TaskType.DEPLOY) {
            throw notEligible("只有生产部署任务可以重新部署",
                Map.of("taskId", task.id(), "nodeType", task.nodeType().name(),
                    "taskType", task.taskType().name()));
        }
        if (!RETRYABLE.contains(task.status())) {
            throw notEligible("任务不是失败或超时状态，不能重新部署",
                Map.of("taskId", task.id(), "status", task.status().name(),
                    "allowed", "FAILED/TIMEOUT"));
        }
        List<NodeTaskPO> rows = queryMapper.selectTasks(task.instanceId());
        if (!isLatestAttempt(rows, task)) {
            throw notEligible("只能重部署该 DE 的最新一次尝试",
                Map.of("taskId", task.id(), "batchNo", task.batchNo(),
                    "targetId", task.targetId()));
        }

        ReleaseOrderPO orderPo = orderMapper.selectOne(
            com.baomidou.mybatisplus.core.toolkit.Wrappers.<ReleaseOrderPO>lambdaQuery()
                .eq(ReleaseOrderPO::getPublishNo, publishNo)
                .last("LIMIT 1"));
        if (orderPo == null) {
            throw notFound(publishNo);
        }
        ReleaseOrder order = orderPo.toDomain();
        ReleaseItemPO itemPo = itemMapper.selectById(order.itemId());
        if (itemPo == null) {
            throw notFound(publishNo);
        }
        ReleaseItem item = itemPo.toDomain();
        if (!REDEPLOYABLE.contains(item.status())) {
            throw notEligible("变更项当前状态不允许单 DE 重新部署",
                Map.of("publishNo", publishNo, "itemId", item.id(),
                    "status", item.status().name(), "allowed", "FAILED/PROD_DEPLOYING"));
        }
        String orderStatus = order.status() == null ? "" : order.status();
        if (!REDEPLOYABLE.stream().anyMatch(status -> status.name().equals(orderStatus))) {
            throw notEligible("发布单当前状态不允许单 DE 重新部署",
                Map.of("publishNo", publishNo, "orderStatus", orderStatus,
                    "allowed", "FAILED/PROD_DEPLOYING"));
        }
        if (head.getImageDigest() == null || head.getImageDigest().isBlank()) {
            throw notEligible("最新实例没有可复用的镜像，无法单 DE 重新部署",
                Map.of("publishNo", publishNo, "instanceId", head.getInstanceId()));
        }
        if (task.targetId() <= 0) {
            throw notEligible("生产部署任务没有目标 DE",
                Map.of("taskId", task.id(), "targetId", task.targetId()));
        }
        EnvDePO de = envDeMapper.selectById(task.targetId());
        if (de == null || de.getUnitId() == null || de.getUnitId() != unitId
            || !PRODUCTION_KINDS.contains(envKindOf(de))) {
            throw notEligible("目标 DE 已不在该部署单元的生产环境中",
                Map.of("taskId", task.id(), "targetId", task.targetId(), "unitId", unitId));
        }
        Set<Long> targetDeIds = batchTargetIds(rows, task.batchNo());
        if (!targetDeIds.contains(task.targetId())) {
            throw notEligible("目标批次的任务集合为空",
                Map.of("taskId", task.id(), "batchNo", task.batchNo()));
        }
        return new Eligibility(order, item.id(), item.status(), head.getImageDigest(),
            head.getCommitSha(), task.batchNo(), targetDeIds,
            de.getDeName() == null ? ("DE#" + task.targetId()) : de.getDeName());
    }

    /** Whether the given task is the highest-attempt row of its {@code (batch,target)} key. */
    private static boolean isLatestAttempt(List<NodeTaskPO> rows, NodeTask task) {
        NodeTaskPO latest = null;
        for (NodeTaskPO row : rows) {
            if (row.getNodeType() == null || !NodeType.PROD_DEPLOY.name().equals(row.getNodeType())
                || row.getTaskType() == null || !TaskType.DEPLOY.name().equals(row.getTaskType())
                || row.getBatchNo() == null || row.getBatchNo() != task.batchNo()
                || row.getTargetId() == null || row.getTargetId() != task.targetId()) {
                continue;
            }
            if (latest == null || attemptOf(row) > attemptOf(latest)) {
                latest = row;
            }
        }
        return latest != null && latest.getId() != null && latest.getId().equals(task.id());
    }

    /** The DE ids of one production batch, from the latest instance's own rows. */
    private static Set<Long> batchTargetIds(List<NodeTaskPO> rows, int batchNo) {
        Set<Long> targets = new LinkedHashSet<>();
        for (NodeTaskPO row : rows) {
            if (NodeType.PROD_DEPLOY.name().equals(row.getNodeType())
                && TaskType.DEPLOY.name().equals(row.getTaskType())
                && row.getBatchNo() != null && row.getBatchNo() == batchNo
                && row.getTargetId() != null && row.getTargetId() > 0) {
                targets.add(row.getTargetId());
            }
        }
        return Set.copyOf(targets);
    }

    private static int attemptOf(NodeTaskPO row) {
        return row.getAttempt() == null ? 0 : row.getAttempt();
    }

    // ------------------------------------------------------------------ transaction

    /**
     * One transaction: the item transition(s), the invalidation of the unfinished checks and the
     * new instance. The previous instance is never written.
     */
    private Created redeployInTransaction(Eligibility eligibility, NodeTask task, long unitId) {
        ReleaseItem current = loadItem(eligibility.itemId());
        if (!REDEPLOYABLE.contains(current.status())) {
            throw notEligible("变更项状态已变化，请刷新后重试",
                Map.of("itemId", current.id(), "status", current.status().name()));
        }
        if (current.status() == ItemStatus.FAILED) {
            // FAILED is the pipeline's side end; re-entering production from it is the same
            // two existing edges a production rerun uses (FAILED -RERUN-> TESTING ->
            // -EXECUTE_RERUN-> PROD_DEPLOYING). No new state or event is introduced.
            requireTransition(current.id(), ItemEvent.RERUN, "FAILED");
            requireTransition(current.id(), ItemEvent.EXECUTE_RERUN, "TESTING");
        }
        // An already PROD_DEPLOYING item is the target state; it is left as it is.
        gateService.invalidateUnfinished(eligibility.itemId(),
            "单 DE 重新部署：检查结果失效，需要重新检查");
        PublishAppService.CreatedInstance created =
            publishAppService.createDeRedeployInstance(eligibility.order(), current, unitId,
                NodeType.PROD_DEPLOY, eligibility.imageDigest(), eligibility.commitSha(),
                eligibility.targetDeIds());
        log.info("single-DE redeploy started: publishNo={}, itemId={}, taskId={}, batchNo={},"
                + " targets={}, pipelineNo={}, actor={}",
            eligibility.order().publishNo(), eligibility.itemId(), task.id(), task.batchNo(),
            eligibility.targetDeIds(), created.instance().pipelineNo(),
            UserContext.current().userId());
        return new Created(created.instance().pipelineNo(), created.instanceId());
    }

    private void requireTransition(long itemId, ItemEvent event, String expectedStatus) {
        ItemTransitions.Outcome outcome = transitions.apply(itemId, event);
        if (outcome != ItemTransitions.Outcome.APPLIED) {
            throw notEligible("变更项状态已变化，重部署未生效",
                Map.of("itemId", itemId, "event", event.name(), "expectedStatus", expectedStatus,
                    "outcome", outcome.name()));
        }
    }

    private ReleaseItem loadItem(long itemId) {
        ReleaseItemPO po = itemMapper.selectById(itemId);
        if (po == null) {
            throw new BizException(BizError.PARAM_INVALID, "变更项不存在", Map.of("itemId", itemId));
        }
        return po.toDomain();
    }

    /** Whether {@code task} belongs to any instance of the publish number (else it is another order). */
    private boolean belongsTo(String publishNo, NodeTask task) {
        if (task.instanceId() == null) {
            return false;
        }
        for (PipelineInstancePO instance : queryMapper.selectInstances(publishNo)) {
            if (task.instanceId().equals(instance.getId())) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ audit payloads

    private static Map<String, Object> before(NodeTask task, ItemStatus itemStatus) {
        Map<String, Object> before = new LinkedHashMap<>();
        before.put("itemStatus", itemStatus.name());
        before.put("taskStatus", task.status().name());
        before.put("batchNo", task.batchNo());
        before.put("targetId", task.targetId());
        return before;
    }

    private static Map<String, Object> after(Created created, Eligibility eligibility,
                                             NodeTask task) {
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("itemStatus", ItemStatus.PROD_DEPLOYING.name());
        after.put("triggerType", TriggerType.RERUN_FROM_NODE.name());
        after.put("pipelineNo", created.pipelineNo());
        after.put("fromNode", NodeType.PROD_DEPLOY.name());
        after.put("batchNo", task.batchNo());
        after.put("targetDeIds", new ArrayList<>(eligibility.targetDeIds()));
        after.put("imageDigest", eligibility.imageDigest());
        after.put("commitSha", eligibility.commitSha());
        return after;
    }

    // ------------------------------------------------------------------ helpers

    private static EnvKind envKindOf(EnvDePO de) {
        try {
            return de.getEnvKind() == null ? null : EnvKind.valueOf(de.getEnvKind());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static String requirePublishNo(String raw) {
        String no = raw == null ? "" : raw.trim();
        if (no.isEmpty()) {
            throw new BizException(BizError.PARAM_INVALID, "发布单号不能为空",
                Map.of("publishNo", "不能为空"));
        }
        return no;
    }

    private static BizException notFound(String publishNo) {
        return new BizException(BizError.PARAM_INVALID, "发布单不存在：" + publishNo,
            Map.of("publishNo", publishNo));
    }

    private static BizException taskNotFound(String publishNo, long taskId) {
        return new BizException(BizError.PARAM_INVALID, "任务不存在或不属于该发布单：" + taskId,
            Map.of("publishNo", publishNo, "taskId", taskId));
    }

    private static BizException notEligible(String message, Map<String, Object> payload) {
        return new BizException(BizError.STATE_NOT_ALLOWED, message, payload);
    }

    /** Everything the transaction/audit needs, validated before the unit lock is taken. */
    private record Eligibility(ReleaseOrder order, long itemId, ItemStatus itemStatus,
                               String imageDigest, String commitSha, int batchNo,
                               Set<Long> targetDeIds, String deName) {
    }

    /** The committed pipeline identity plus the id the caller must start. */
    private record Created(String pipelineNo, long instanceId) {
    }

    /** What the caller needs after a single-DE redeploy. */
    public record RedeployResult(String publishNo, String pipelineNo, long instanceId,
                                 String fromNode, int batchNo, Set<Long> targetDeIds,
                                 String targetDeName) {
        public RedeployResult {
            targetDeIds = targetDeIds == null ? Set.of() : Set.copyOf(targetDeIds);
        }
    }
}