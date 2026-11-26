package com.sf.release.application;

import com.sf.release.common.BizError;
import com.sf.release.common.BizException;
import com.sf.release.domain.pipeline.NodeStatus;
import com.sf.release.domain.pipeline.TriggerType;
import com.sf.release.domain.task.MemberRole;
import com.sf.release.infra.repo.ReleaseQueryMapper;
import com.sf.release.infra.repo.po.NodeTaskPO;
import com.sf.release.infra.repo.po.PipelineInstancePO;
import com.sf.release.infra.repo.po.PipelineNodePO;
import com.sf.release.infra.repo.po.ReleaseHeadRow;
import com.sf.release.infra.repo.po.ReleaseMemberRow;
import com.sf.release.infra.security.ScopeGuard;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The 发布内容 read use cases (Task 16): the aggregated detail of one 发布单 and its instance
 * history.
 *
 * <p><strong>One execution at a time.</strong> A 发布单 keeps its publish number across reruns;
 * every execution is a new {@link PipelineInstancePO} with a new pipeline number. The detail
 * therefore shows the <em>newest</em> execution (the page follows the current run), while
 * {@link #records(String)} lists all of them newest first.
 *
 * <p><strong>Query budget.</strong> The detail is assembled from at most four statements — one
 * header join (with the owning task id), one member read, one node read and one task read — and
 * the tasks are grouped by node in memory; there is no per-node or per-member query. The member
 * read feeds the P6-F 人员列 ({@code testers}/{@code deployers}). The history costs one ownership
 * lookup plus one instance list.
 *
 * <p><strong>Gate fields.</strong> {@code gateStatus} is the item's <em>check</em> dimension and
 * {@code testStatus} its <em>test</em> dimension (V19 split them: one column could not hold both
 * without one erasing the other). Both are returned raw; the page renders them in two columns and
 * the detailed reasoning lives in {@code GET /api/v1/items/{itemId}/gate}.
 *
 * <p><strong>Rerun hand-off (Task 15).</strong> {@code triggerType} tells why the execution
 * exists. For a rerun it is accompanied by {@code fullRerun} and {@code rerunFromNode}, so the
 * page can say 「代码未变，从测试部署重跑」 or 「代码有更新，需全量重来」: the start node is the
 * first node the execution did not skip ({@code BUILD} for a full rerun, the resume node for a
 * from-node rerun). Both fields stay {@code null} for a first run or a rollback, where no rerun
 * decision applies.
 *
 * <p><strong>Permission.</strong> Only an authenticated caller may read, and the owning
 * deployment unit must be inside the caller's data scope ({@code ScopeGuard} → 40009). An
 * unknown publish number is a business {@code PARAM_INVALID} (40000) naming the number, the same
 * convention as the publish and rerun use cases.
 */
@Service
public class ReleaseQueryService {

    private static final Set<TriggerType> RERUNS =
        EnumSet.of(TriggerType.RERUN_FROM_NODE, TriggerType.RERUN_FULL);

    private final ReleaseQueryMapper queryMapper;

    public ReleaseQueryService(ReleaseQueryMapper queryMapper) {
        this.queryMapper = Objects.requireNonNull(queryMapper, "queryMapper must not be null");
    }

    /**
     * The aggregated view of the newest execution of {@code publishNo}.
     *
     * @throws BizException 40000 blank or unknown publish number; 40009 the caller has no data
     *                      scope on the owning deployment unit
     */
    public ReleaseDetail detail(String publishNo) {
        String no = requirePublishNo(publishNo);
        ReleaseHeadRow head = queryMapper.selectHeadRow(no);
        if (head == null) {
            throw notFound(no);
        }
        ScopeGuard.assertUnitInScope(head.getUnitId());

        // P6-F 人员列 (design §2): read once for both columns, partition in memory, keep the SQL
        // order (user_id ascending). A task row is always present (release_item.task_id is NOT
        // NULL), the null check only keeps the read model total.
        List<ReleaseMemberRow> members = head.getTaskId() == null
            ? List.of()
            : queryMapper.selectMembers(head.getTaskId());
        List<MemberView> testers = membersWithRole(members, MemberRole.TEST);
        List<MemberView> deployers = membersWithRole(members, MemberRole.OPS);

        List<NodeView> nodes = head.getInstanceId() == null
            ? List.of()
            : nodes(queryMapper.selectNodes(head.getInstanceId()),
                queryMapper.selectTasks(head.getInstanceId()));

        TriggerType triggerType = parseTriggerType(head.getTriggerType());
        boolean rerun = triggerType != null && RERUNS.contains(triggerType);
        return new ReleaseDetail(
            head.getPublishNo(),
            head.getPipelineNo(),
            head.getStatus(),
            head.getStatusReason(),
            head.getGateStatus(),
            head.getTestStatus(),
            triggerType == null ? null : triggerType.name(),
            rerun ? firstExecutedNode(nodes) : null,
            rerun ? triggerType == TriggerType.RERUN_FULL : null,
            head.getCommitSha(),
            head.getImageDigest(),
            new OrderView(head.getOaTicketNo(), head.getPlannedReleaseTime()),
            nodes,
            head.getItemId(),
            head.getUnitId(),
            emergencyFlagOf(head.getEmergencyRelease()),
            head.getEmergencyReason(),
            head.getEmergencyBy(),
            instantOf(head.getEmergencyAt()),
            testers,
            deployers);
    }

    /**
     * Every execution of {@code publishNo}, newest first.
     *
     * @throws BizException 40000 blank or unknown publish number; 40009 the caller has no data
     *                      scope on the owning deployment unit
     */
    public List<ReleaseRecord> records(String publishNo) {
        String no = requirePublishNo(publishNo);
        Long unitId = queryMapper.selectUnitId(no);
        if (unitId == null) {
            throw notFound(no);
        }
        ScopeGuard.assertUnitInScope(unitId);

        List<ReleaseRecord> records = new ArrayList<>();
        for (PipelineInstancePO instance : queryMapper.selectInstances(no)) {
            // No operator column exists on the instance yet (creation is audited in the log), so
            // the field is null instead of being guessed from the item or the order.
            records.add(new ReleaseRecord(instance.getPipelineNo(), instance.getTriggerType(),
                instance.getStatus(), instance.getStartAt(), instance.getEndAt(), null));
        }
        return List.copyOf(records);
    }

    /** Nodes in pipeline order, each with the tasks pre-generated for it. */
    private static List<NodeView> nodes(List<PipelineNodePO> nodeRows, List<NodeTaskPO> taskRows) {
        Map<String, List<TaskView>> tasksByNode = new LinkedHashMap<>();
        for (NodeTaskPO task : taskRows) {
            tasksByNode.computeIfAbsent(task.getNodeType(), key -> new ArrayList<>())
                .add(taskView(task));
        }
        List<PipelineNodePO> ordered = new ArrayList<>(nodeRows);
        ordered.sort(Comparator.comparingInt(
            node -> node.getSeqNo() == null ? 0 : node.getSeqNo()));
        return ordered.stream()
            .map(node -> new NodeView(
                node.getNodeType(),
                node.getSeqNo() == null ? 0 : node.getSeqNo(),
                node.getStatus(),
                node.getStartedAt(),
                node.getFinishedAt(),
                tasksByNode.getOrDefault(node.getNodeType(), List.of())))
            .toList();
    }

    private static TaskView taskView(NodeTaskPO task) {
        return new TaskView(
            task.getId() == null ? 0L : task.getId(),
            task.getTaskType(),
            task.getBatchNo() == null ? 0 : task.getBatchNo(),
            task.getTargetId() == null ? 0L : task.getTargetId(),
            task.getStatus(),
            task.getDurationMs(),
            task.getResultSummary(),
            task.getExternalUrl());
    }

    /**
     * The node the execution started at: the first node it did not skip. A from-node rerun
     * pre-marks the earlier nodes {@code SKIPPED} (Task 15), and a full rerun starts at
     * {@code BUILD}, so this is stable while the pipeline advances.
     */
    private static String firstExecutedNode(List<NodeView> nodes) {
        for (NodeView node : nodes) {
            if (!NodeStatus.SKIPPED.name().equals(node.status())) {
                return node.nodeType();
            }
        }
        return null;
    }

    /**
     * The members holding {@code role}, preserving the SQL order ({@code user_id} ascending). One
     * row per user per task (the unique key), so a name appears in at most one of the two columns.
     * The role is the stored task-member vocabulary ({@link MemberRole}), not the platform role;
     * an unrecognised stored value is ignored rather than guessed.
     */
    private static List<MemberView> membersWithRole(List<ReleaseMemberRow> rows, MemberRole role) {
        String expected = role.name();
        List<MemberView> views = new ArrayList<>();
        for (ReleaseMemberRow row : rows) {
            if (expected.equals(roleName(row.getRole()))) {
                views.add(new MemberView(row.getUserId(), row.getName()));
            }
        }
        return List.copyOf(views);
    }

    private static String roleName(String raw) {
        return raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
    }

    /** The stored trigger vocabulary is controlled; an unrecognised value degrades to unknown. */
    private static TriggerType parseTriggerType(String raw) {
        String value = trimToNull(raw);
        if (value == null) {
            return null;
        }
        try {
            return TriggerType.valueOf(value);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    /**
     * The J11 read marker as the machine word the page compares (P6-D design §3: "four string
     * fields"). A never-released order answers {@code "false"}; the column default (0) and a
     * pre-V32 {@code null} both mean the same.
     */
    private static String emergencyFlagOf(Integer flag) {
        return flag != null && flag != 0 ? "true" : "false";
    }

    /** A stored instant as an ISO-8601 string (the emergency fields travel as strings). */
    private static String instantOf(Instant instant) {
        return instant == null ? null : instant.toString();
    }

    private static String requirePublishNo(String raw) {
        String no = trimToNull(raw);
        if (no == null) {
            throw new BizException(BizError.PARAM_INVALID, "发布单号不能为空",
                Map.of("publishNo", "不能为空"));
        }
        return no;
    }

    private static BizException notFound(String publishNo) {
        return new BizException(BizError.PARAM_INVALID, "发布单不存在：" + publishNo,
            Map.of("publishNo", publishNo));
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** Read model of one 发布单; the web layer mirrors it as the response body. */
    public record ReleaseDetail(
        String publishNo,
        String pipelineNo,
        String status,
        String statusReason,
        String gateStatus,
        String testStatus,
        String triggerType,
        String rerunFromNode,
        Boolean fullRerun,
        String commitSha,
        String imageDigest,
        OrderView order,
        List<NodeView> nodes,
        Long itemId,
        Long unitId,
        /**
         * J11 marker as {@code "true"}/{@code "false"}; the other three are null unless an
         * emergency release happened (P6-D design §2/§3).
         */
        String emergencyRelease,
        String emergencyReason,
        String emergencyBy,
        String emergencyAt,
        /**
         * P6-F (design §2): the task members with {@code role=TEST} / {@code role=OPS}, ordered by
         * {@code user_id}; empty — never null — when the task has no such member. Read-only display
         * data: it takes no part in permission checks.
         */
        List<MemberView> testers,
        List<MemberView> deployers) {

        public ReleaseDetail {
            nodes = nodes == null ? List.of() : List.copyOf(nodes);
            testers = testers == null ? List.of() : List.copyOf(testers);
            deployers = deployers == null ? List.of() : List.copyOf(deployers);
        }
    }

    /** One person of the 测试人员 / 部署人员 column: the task member and their user name. */
    public record MemberView(String userId, String name) {
    }

    /** Admission fields of the order the detail belongs to. */
    public record OrderView(String oaTicketNo, Instant plannedReleaseTime) {
    }

    /** One pipeline node with its tasks; {@code seqNo} is the 0-based pipeline order. */
    public record NodeView(
        String nodeType,
        int seqNo,
        String status,
        Instant startedAt,
        Instant finishedAt,
        List<TaskView> tasks) {

        public NodeView {
            tasks = tasks == null ? List.of() : List.copyOf(tasks);
        }
    }

    /**
     * One {@code node_task} row. {@code id} is the row id the P6-G task actions
     * ({@code redeploy}/{@code logs}) address. {@code batchNo} is 0 outside the production
     * deploy node.
     */
    public record TaskView(
        long id,
        String taskType,
        int batchNo,
        long targetId,
        String status,
        Long durationMs,
        String resultSummary,
        String externalUrl) {
    }

    /** One execution in the history of a 发布单. */
    public record ReleaseRecord(
        String pipelineNo,
        String triggerType,
        String status,
        Instant startAt,
        Instant endAt,
        String operator) {
    }
}