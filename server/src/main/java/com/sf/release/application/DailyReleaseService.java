package com.sf.release.application;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sf.release.application.audit.AuditCommand;
import com.sf.release.application.audit.AuditService;
import com.sf.release.common.BizError;
import com.sf.release.common.BizException;
import com.sf.release.common.security.Permissions;
import com.sf.release.domain.audit.AuditAction;
import com.sf.release.domain.audit.AuditObjectType;
import com.sf.release.domain.pipeline.NodeTask;
import com.sf.release.domain.pipeline.PipelineFactory;
import com.sf.release.domain.pipeline.PipelineInstance;
import com.sf.release.domain.pipeline.PipelineNode;
import com.sf.release.domain.pipeline.port.PipelineAdvancer;
import com.sf.release.domain.resource.EnvDe;
import com.sf.release.domain.resource.EnvKind;
import com.sf.release.domain.resource.TaskKind;
import com.sf.release.domain.resource.UnitType;
import com.sf.release.domain.task.ReleaseItem;
import com.sf.release.domain.task.TaskStatus;
import com.sf.release.infra.repo.CodingTaskBindingMapper;
import com.sf.release.infra.repo.DailyQueryMapper;
import com.sf.release.infra.repo.EnvDeMapper;
import com.sf.release.infra.repo.NodeTaskMapper;
import com.sf.release.infra.repo.PipelineInstanceMapper;
import com.sf.release.infra.repo.PipelineNodeMapper;
import com.sf.release.infra.repo.ReleaseItemMapper;
import com.sf.release.infra.repo.ReleaseQueryMapper;
import com.sf.release.infra.repo.ReleaseTaskMapper;
import com.sf.release.infra.repo.po.CodingTaskBindingPO;
import com.sf.release.infra.repo.po.DailyInstanceRow;
import com.sf.release.infra.repo.po.EnvDePO;
import com.sf.release.infra.repo.po.NodeTaskPO;
import com.sf.release.infra.repo.po.PipelineInstancePO;
import com.sf.release.infra.repo.po.PipelineNodePO;
import com.sf.release.infra.repo.po.ReleaseItemPO;
import com.sf.release.infra.repo.po.ReleaseTaskPO;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The 日常发布 use cases (GAP-04, P5-A design §5): start, records and detail.
 *
 * <p><strong>What a daily release is not.</strong> It creates no 发布单, takes no 部署单元 lock,
 * never writes {@code release_item.status} and never becomes a rollback candidate. It is a
 * simplified {@code BUILD → DAILY_DEPLOY} pipeline owned by its 变更项; the instance's
 * {@code item_id/unit_id/requested_by} columns (V28) are how its pages find it, because
 * {@code orderId} is {@code 0}.
 *
 * <p><strong>Validation order (design §5).</strong> permission {@code release:daily} → the item
 * exists and is a UNIT (a LIB item is refused) → the unit is inside the caller's data scope (40009)
 * → the task is not archived → the daily target DEs and the CODING bindings are ready (40001) →
 * create the instance in one transaction → audit {@code DAILY_START} → advance the pipeline.
 *
 * <p><strong>Parallel by design.</strong> FR-D-01: the same 变更项 may start several daily
 * releases at once. Nothing is reserved, no "already running" check is made, and two instances
 * never share a lock or a row besides the item's read-only snapshot.
 *
 * <p><strong>Binding fallbacks (C24).</strong> The deploy plan is the unit's enabled
 * {@code DAILY_DEPLOY} binding, falling back to its {@code TEST_DEPLOY} binding; the target DEs are
 * the unit's {@code DAILY} DEs, falling back to its {@code TEST} DEs. The build plan is the online
 * rule (enabled {@code BUILD} binding, the item's {@code build_plan_id} snapshot as fallback) so a
 * daily release builds with the same task as an online release.
 *
 * <p><strong>Failure isolation.</strong> The audit row is written through {@link AuditService},
 * which never throws. The pipeline is advanced after the instance committed; a failure there is
 * logged and left to the reconcile pass, exactly like a publish, so it cannot undo the start.
 */
@Service
public class DailyReleaseService {

    private static final Logger log = LoggerFactory.getLogger(DailyReleaseService.class);

    /** Page size ceiling, shared with the other paged read services. */
    static final int MAX_PAGE_SIZE = 100;

    private final ReleaseItemMapper itemMapper;
    private final ReleaseTaskMapper releaseTaskMapper;
    private final EnvDeMapper envDeMapper;
    private final CodingTaskBindingMapper bindingMapper;
    private final PipelineInstanceMapper instanceMapper;
    private final PipelineNodeMapper nodeMapper;
    private final NodeTaskMapper taskMapper;
    private final ReleaseQueryMapper queryMapper;
    private final DailyQueryMapper dailyQueryMapper;
    private final PipelineAdvancer advancer;
    private final AuditService audit;
    private final TransactionTemplate transactionTemplate;

    /**
     * Whether starting a daily release enters its first node immediately. On in every real
     * deployment; the test profile turns it off so a handler test drives the nodes itself.
     */
    private final boolean autoStart;

    public DailyReleaseService(ReleaseItemMapper itemMapper,
                               ReleaseTaskMapper releaseTaskMapper,
                               EnvDeMapper envDeMapper,
                               CodingTaskBindingMapper bindingMapper,
                               PipelineInstanceMapper instanceMapper,
                               PipelineNodeMapper nodeMapper,
                               NodeTaskMapper taskMapper,
                               ReleaseQueryMapper queryMapper,
                               DailyQueryMapper dailyQueryMapper,
                               PipelineAdvancer advancer,
                               AuditService audit,
                               @Value("${release.pipeline.auto-start:true}") boolean autoStart,
                               PlatformTransactionManager transactionManager) {
        this.itemMapper = Objects.requireNonNull(itemMapper, "itemMapper must not be null");
        this.releaseTaskMapper = Objects.requireNonNull(releaseTaskMapper,
            "releaseTaskMapper must not be null");
        this.envDeMapper = Objects.requireNonNull(envDeMapper, "envDeMapper must not be null");
        this.bindingMapper = Objects.requireNonNull(bindingMapper, "bindingMapper must not be null");
        this.instanceMapper = Objects.requireNonNull(instanceMapper,
            "instanceMapper must not be null");
        this.nodeMapper = Objects.requireNonNull(nodeMapper, "nodeMapper must not be null");
        this.taskMapper = Objects.requireNonNull(taskMapper, "taskMapper must not be null");
        this.queryMapper = Objects.requireNonNull(queryMapper, "queryMapper must not be null");
        this.dailyQueryMapper = Objects.requireNonNull(dailyQueryMapper,
            "dailyQueryMapper must not be null");
        this.advancer = Objects.requireNonNull(advancer, "advancer must not be null");
        this.audit = Objects.requireNonNull(audit, "audit must not be null");
        this.autoStart = autoStart;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /**
     * Starts one daily release of {@code itemId} and answers its pipeline number and instance id.
     *
     * @throws BizException 40000 the item does not exist (or has no task); 40001 the item is a LIB,
     *     its task is archived, the unit has no DAILY/TEST DE, or a CODING binding is missing;
     *     40009 the caller has no {@code release:daily} or no data scope on the unit
     */
    public StartResult start(long itemId) {
        CurrentUser user = UserContext.current();
        requirePerm(user, Permissions.RELEASE_DAILY);
        ReleaseItem item = loadItem(itemId);
        if (item.type() != UnitType.UNIT) {
            throw new BizException(BizError.STATE_NOT_ALLOWED,
                "二方库变更项不支持日常发布", Map.of("itemId", itemId, "type", item.type().name()));
        }
        long unitId = item.unitId() == null ? 0L : item.unitId();
        if (unitId <= 0) {
            throw new BizException(BizError.STATE_NOT_ALLOWED, "变更项没有部署单元，无法日常发布",
                Map.of("itemId", itemId));
        }
        ScopeGuard.assertUnitInScope(unitId);
        requireTaskNotArchived(item);
        List<EnvDe> envDes = loadEnvDes(unitId);
        requireDailyConfiguration(item, unitId, envDes);

        Instant now = Instant.now();
        Started started = transactionTemplate.execute(
            tx -> createInTransaction(item, unitId, envDes, user.userId(), now));
        Started result = Objects.requireNonNull(started, "daily start result");
        log.info("daily release created: itemId={}, unitId={}, pipelineNo={}, instanceId={},"
                + " requestedBy={}", itemId, unitId, result.pipelineNo(), result.instanceId(),
            user.userId());

        // Audited before the first node is entered (design §5): the row names the ITEM object,
        // not the ORDER default of DAILY_START, and is scoped to the unit. AuditService never
        // throws, so a failed audit row cannot block (or undo) the start.
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("pipelineNo", result.pipelineNo());
        after.put("instanceId", result.instanceId());
        after.put("unitId", unitId);
        audit.record(AuditCommand.of(AuditAction.DAILY_START, String.valueOf(itemId), item.crid(),
                "日常发布：变更项 " + item.crid() + "（" + branchOf(item) + "）")
            .objectType(AuditObjectType.ITEM)
            .after(after)
            .scopeUnitId(unitId)
            .build());

        if (autoStart) {
            startPipeline(result.instanceId());
        }
        return new StartResult(result.pipelineNo(), result.instanceId());
    }

    /**
     * One page of the daily instances of {@code itemId}, newest first (design §5).
     *
     * @throws BizException 40000 unknown item or an invalid page request; 40009 no data scope
     */
    public DailyPage page(long itemId, int pageNo, int pageSize) {
        ReleaseItem item = loadItem(itemId);
        long unitId = item.unitId() == null ? 0L : item.unitId();
        ScopeGuard.assertUnitInScope(unitId);
        requirePage(pageNo, pageSize);

        long total = dailyQueryMapper.countByItem(itemId);
        if (total == 0) {
            return new DailyPage(0, pageNo, pageSize, List.of());
        }
        long offset = (long) (pageNo - 1) * pageSize;
        List<DailyRecord> records = dailyQueryMapper.selectPage(itemId, offset, pageSize)
            .stream()
            .map(DailyReleaseService::recordOf)
            .toList();
        return new DailyPage(total, pageNo, pageSize, records);
    }

    /**
     * The daily instance {@code pipelineNo} with its nodes and tasks (design §5).
     *
     * @throws BizException 40000 blank or unknown daily pipeline number; 40009 no data scope
     */
    public DailyDetail detail(String pipelineNo) {
        String no = requirePipelineNo(pipelineNo);
        DailyInstanceRow row = dailyQueryMapper.selectByPipelineNo(no);
        if (row == null) {
            throw new BizException(BizError.PARAM_INVALID, "日常发布记录不存在：" + no,
                Map.of("pipelineNo", no));
        }
        ScopeGuard.assertUnitInScope(row.getUnitId() == null ? 0L : row.getUnitId());
        long instanceId = row.getInstanceId() == null ? 0L : row.getInstanceId();
        List<DailyNode> nodes = nodes(queryMapper.selectNodes(instanceId),
            queryMapper.selectTasks(instanceId));
        return new DailyDetail(row.getPipelineNo(), row.getStatus(), row.getRequestedBy(),
            row.getBranch(), row.getCommitSha(), row.getCreatedAt(), row.getStartAt(),
            row.getEndAt(), row.getImageDigest(), nodes);
    }

    // ------------------------------------------------------------------ creation

    /**
     * Creates the instance, its nodes and its tasks in <strong>one</strong> transaction. Unlike
     * {@code PublishAppService#persistInstance} it binds no unit lock and writes no release order:
     * a daily release is parallel by design and must never block (or be blocked by) an online
     * release of the same unit.
     */
    private Started createInTransaction(ReleaseItem item, long unitId, List<EnvDe> envDes,
                                        String requestedBy, Instant now) {
        PipelineInstance instance = PipelineFactory.createDaily(item, unitId, envDes,
            requestedBy, now);
        PipelineInstancePO instancePo = PipelineInstancePO.of(instance);
        instanceMapper.insert(instancePo);
        long instanceId = instancePo.getId();
        for (PipelineNode node : instance.nodes()) {
            nodeMapper.insert(PipelineNodePO.of(node, instanceId));
            for (NodeTask task : node.tasks()) {
                taskMapper.insert(NodeTaskPO.of(task, instanceId));
            }
        }
        return new Started(instance.pipelineNo(), instanceId);
    }

    /**
     * Enters the first node. Runs outside the creation transaction because node handlers call the
     * CI engine; a failure here is logged and the reconcile pass enters the still-{@code PENDING}
     * instance later, exactly like a publish.
     */
    private void startPipeline(long instanceId) {
        try {
            advancer.advance(instanceId);
        } catch (RuntimeException ex) {
            log.error("daily pipeline did not start; reconcile will enter its first node:"
                + " instanceId={}", instanceId, ex);
        }
    }

    // ------------------------------------------------------------------ validation

    /** 40001 when the 变更项's owning task is archived (FR-T-05: archived tasks are read-only). */
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
            throw new BizException(BizError.STATE_NOT_ALLOWED, "任务已归档，不能发起日常发布",
                Map.of("itemId", item.id(), "taskId", item.taskId()));
        }
    }

    /**
     * 40001 when the unit cannot run a daily release: no {@code DAILY}/{@code TEST} DE (C24), no
     * deploy plan (enabled {@code DAILY_DEPLOY}, else {@code TEST_DEPLOY} binding) or no build plan
     * (enabled {@code BUILD} binding, else the item's snapshot). Checked <em>before</em> anything
     * is written, so a malformed start leaves no half-created instance.
     */
    private void requireDailyConfiguration(ReleaseItem item, long unitId, List<EnvDe> envDes) {
        if (dailyTargets(envDes).isEmpty()) {
            throw new BizException(BizError.STATE_NOT_ALLOWED,
                "单元没有日常/测试环境 DE，无法日常发布", Map.of("unitId", unitId));
        }
        if (deployPlan(unitId) == null) {
            throw new BizException(BizError.STATE_NOT_ALLOWED,
                "部署单元未配置日常部署（DAILY_DEPLOY/TEST_DEPLOY）CODING 任务，无法日常发布",
                Map.of("unitId", unitId));
        }
        if (buildPlan(item, unitId) == null) {
            throw new BizException(BizError.STATE_NOT_ALLOWED,
                "部署单元未配置构建（BUILD）CODING 任务，无法日常发布",
                Map.of("unitId", unitId, "itemId", item.id()));
        }
    }

    /** The unit's DAILY DEs, or its TEST DEs when it has none (C24); never {@code null}. */
    private static List<EnvDe> dailyTargets(List<EnvDe> envDes) {
        List<EnvDe> daily = envDes.stream()
            .filter(de -> de.envKind() == EnvKind.DAILY)
            .toList();
        return daily.isEmpty()
            ? envDes.stream().filter(de -> de.envKind() == EnvKind.TEST).toList()
            : daily;
    }

    /** The deploy plan id: enabled {@code DAILY_DEPLOY} binding, else {@code TEST_DEPLOY}. */
    private String deployPlan(long unitId) {
        String daily = enabledBinding(unitId, TaskKind.DAILY_DEPLOY);
        return daily != null ? daily : enabledBinding(unitId, TaskKind.TEST_DEPLOY);
    }

    /** The build plan id: enabled {@code BUILD} binding, else the item's snapshot. */
    private String buildPlan(ReleaseItem item, long unitId) {
        String build = enabledBinding(unitId, TaskKind.BUILD);
        return build != null ? build : trimToNull(item.buildPlanId());
    }

    /** The CODING task id of one enabled binding kind, or {@code null} when there is none. */
    private String enabledBinding(long unitId, TaskKind kind) {
        CodingTaskBindingPO binding = bindingMapper.selectOne(
            Wrappers.<CodingTaskBindingPO>lambdaQuery()
                .eq(CodingTaskBindingPO::getUnitId, unitId)
                .eq(CodingTaskBindingPO::getTaskKind, kind.name())
                .eq(CodingTaskBindingPO::getEnabled, true));
        return binding == null ? null : trimToNull(binding.getTaskId());
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

    private static void requirePerm(CurrentUser user, String perm) {
        if (!user.hasPerm(perm)) {
            throw new BizException(BizError.FORBIDDEN, "无权执行该操作：" + perm,
                Map.of("perm", perm));
        }
    }

    // ------------------------------------------------------------------ mapping

    private static DailyRecord recordOf(DailyInstanceRow row) {
        return new DailyRecord(row.getPipelineNo(), row.getStatus(), row.getRequestedBy(),
            row.getBranch(), row.getCommitSha(), row.getCreatedAt(), row.getStartAt(),
            row.getEndAt(), row.getImageDigest());
    }

    /** Nodes in pipeline order (the shared query already orders by {@code seq_no}). */
    private static List<DailyNode> nodes(List<PipelineNodePO> nodeRows,
                                         List<NodeTaskPO> taskRows) {
        Map<String, List<DailyTask>> tasksByNode = new LinkedHashMap<>();
        for (NodeTaskPO task : taskRows) {
            tasksByNode.computeIfAbsent(task.getNodeType(), key -> new ArrayList<>())
                .add(new DailyTask(task.getTaskType(), task.getStatus(), task.getExternalUrl(),
                    task.getResultSummary()));
        }
        List<DailyNode> nodes = new ArrayList<>(nodeRows.size());
        for (PipelineNodePO node : nodeRows) {
            nodes.add(new DailyNode(node.getNodeType(), node.getStatus(),
                tasksByNode.getOrDefault(node.getNodeType(), List.of())));
        }
        return List.copyOf(nodes);
    }

    // ------------------------------------------------------------------ helpers

    private ReleaseItem loadItem(long itemId) {
        ReleaseItemPO po = itemMapper.selectById(itemId);
        if (po == null) {
            throw new BizException(BizError.PARAM_INVALID, "变更项不存在", Map.of("itemId", itemId));
        }
        return po.toDomain();
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

    private static String branchOf(ReleaseItem item) {
        String branch = trimToNull(item.sourceBranch());
        return branch == null ? "无分支" : branch;
    }

    private static String requirePipelineNo(String raw) {
        String no = trimToNull(raw);
        if (no == null) {
            throw new BizException(BizError.PARAM_INVALID, "流水线编号不能为空",
                Map.of("pipelineNo", "不能为空"));
        }
        return no;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** What the start endpoint answers (design §5): the pipeline number and the instance id. */
    public record StartResult(String pipelineNo, long instanceId) {
    }

    /** Internal carrier of the committed instance before it is started. */
    private record Started(String pipelineNo, long instanceId) {
    }

    /** One daily execution row of the records page. */
    public record DailyRecord(String pipelineNo, String status, String requestedBy, String branch,
                              String commitSha, Instant createdAt, Instant startAt, Instant endAt,
                              String imageDigest) {
    }

    /** One page of daily executions; {@code total} is the full match count. */
    public record DailyPage(long total, int pageNo, int pageSize, List<DailyRecord> records) {

        public DailyPage {
            records = records == null ? List.of() : List.copyOf(records);
        }
    }

    /** The daily detail: the instance row plus every node with its tasks. */
    public record DailyDetail(String pipelineNo, String status, String requestedBy, String branch,
                              String commitSha, Instant createdAt, Instant startAt, Instant endAt,
                              String imageDigest, List<DailyNode> nodes) {

        public DailyDetail {
            nodes = nodes == null ? List.of() : List.copyOf(nodes);
        }
    }

    /** One node of the daily pipeline; {@code nodeType}/{@code status} are the machine values. */
    public record DailyNode(String nodeType, String status, List<DailyTask> tasks) {

        public DailyNode {
            tasks = tasks == null ? List.of() : List.copyOf(tasks);
        }
    }

    /** One task: its kind, status, engine log link ({@code externalUrl}) and result summary. */
    public record DailyTask(String taskType, String status, String externalUrl, String summary) {
    }
}