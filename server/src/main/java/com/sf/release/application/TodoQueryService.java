package com.sf.release.application;

import com.sf.release.common.BizError;
import com.sf.release.common.BizException;
import com.sf.release.common.security.Permissions;
import com.sf.release.domain.task.ItemStatus;
import com.sf.release.domain.task.TaskStatus;
import com.sf.release.infra.repo.TodoQueryMapper;
import com.sf.release.infra.repo.po.TodoRow;
import com.sf.release.infra.security.CurrentUser;
import com.sf.release.infra.security.UserContext;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The 待办中心 read model (P5-C design section 1): the four groups of work the caller has to handle,
 * assembled from the eight statements of {@link TodoQueryMapper}.
 *
 * <p><strong>Server decides "is it my turn".</strong> The caller does not filter: the SQL already
 * applied the membership, the role of the <em>first unsigned</em> approval step, the self-submission
 * exclusion and the unit scope. This service adds the two rules that need the resolved
 * {@link CurrentUser}: a caller without {@code control:approve} has no 待审批 rows, and a caller
 * without {@code control:execute-prod} only sees 待执行 orders of tasks they are an {@code OPS}
 * member of. {@code mine} is about tasks the caller <em>created</em>, never about tasks they merely
 * joined (those are covered by the other three groups).
 *
 * <p><strong>Fail-closed.</strong> A caller with neither {@code allUnits} nor a single unit scope gets
 * four empty groups without a statement running. The mapper repeats the rule as {@code 1 = 0} so a
 * caller that bypassed this short-circuit still reads nothing.
 *
 * <p><strong>Labels.</strong> {@code statusLabel} comes from the domain enums ({@link ItemStatus},
 * {@link TaskStatus}); the web DTO carries the string and never imports a domain enum. An unknown
 * stored word degrades to the raw value instead of being invented.
 *
 * <p><strong>Waiting time.</strong> {@code waitingSince} is the moment the row entered its waiting
 * state and {@code waitingHours} the whole hours from then to the injected {@link Clock}; the clock is
 * a constructor dependency so the tests can freeze it. A negative difference (a clock behind the
 * stored timestamp) is clamped to zero hours.
 *
 * <p><strong>Links.</strong> The click-through paths are built here, not in the page:
 * {@code /tasks/{taskId}?item={itemId}} for a 变更项, {@code /releases/{publishNo}} for a 发布单 and
 * {@code /tasks/{taskId}} for a 任务.
 *
 * <p><strong>Query budget.</strong> Four counts and at most four detail statements; a group whose
 * count is zero skips its detail statement. The check for 待测试 deliberately uses the <em>newest</em>
 * instance of the item's newest order: a rerun that has not reached the test environment yet is not
 * "ready" again, so it must not reappear in the queue on the strength of an older instance.
 */
@Service
public class TodoQueryService {

    /** Page size default of the endpoint. */
    static final int DEFAULT_LIMIT = 5;

    /** Page size ceiling of the endpoint (design section 1: 1–20). */
    static final int MAX_LIMIT = 20;

    /** Object types the page uses to decide what the row is; stable API words. */
    static final String OBJECT_ITEM = "ITEM";
    static final String OBJECT_ORDER = "ORDER";
    static final String OBJECT_TASK = "TASK";

    private final TodoQueryMapper todoQueryMapper;
    private final Clock clock;

    public TodoQueryService(TodoQueryMapper todoQueryMapper, Clock clock) {
        this.todoQueryMapper =
            Objects.requireNonNull(todoQueryMapper, "todoQueryMapper must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /**
     * The four groups for the caller of the current request.
     *
     * @param limit items per group; {@code null} means the default 5, otherwise 1–20
     * @throws BizException 40000 a limit outside 1–20
     */
    public TodoBoard board(Integer limit) {
        int size = limitOf(limit);
        CurrentUser user = UserContext.current();
        boolean allUnits = user.allUnits();
        List<Long> scopeUnitIds = user.scopeUnitIds().stream().sorted().toList();
        if (!allUnits && scopeUnitIds.isEmpty()) {
            return TodoBoard.empty();
        }
        String me = user.userId();
        return new TodoBoard(
            testGroup(me, allUnits, scopeUnitIds, size),
            approvalGroup(user, me, allUnits, scopeUnitIds, size),
            executeGroup(user, me, allUnits, scopeUnitIds, size),
            mineGroup(me, allUnits, scopeUnitIds, size));
    }

    // ------------------------------------------------------------------ groups

    private TodoGroup testGroup(String me, boolean allUnits, List<Long> scopeUnitIds, int size) {
        long count = todoQueryMapper.countTestItems(me, allUnits, scopeUnitIds);
        if (count == 0) {
            return TodoGroup.empty();
        }
        List<TodoItem> items = todoQueryMapper
            .selectTestItems(me, allUnits, scopeUnitIds, size)
            .stream()
            .map(this::testItem)
            .toList();
        return new TodoGroup(count, items);
    }

    private TodoGroup approvalGroup(CurrentUser user, String me, boolean allUnits,
                                    List<Long> scopeUnitIds, int size) {
        if (!user.hasPerm(Permissions.CONTROL_APPROVE)) {
            return TodoGroup.empty();
        }
        List<String> roles = user.roles().stream().sorted().toList();
        if (roles.isEmpty()) {
            return TodoGroup.empty();
        }
        long count = todoQueryMapper.countApprovalItems(me, roles, allUnits, scopeUnitIds);
        if (count == 0) {
            return TodoGroup.empty();
        }
        List<TodoItem> items = todoQueryMapper
            .selectApprovalItems(me, roles, allUnits, scopeUnitIds, size)
            .stream()
            .map(this::orderItem)
            .toList();
        return new TodoGroup(count, items);
    }

    private TodoGroup executeGroup(CurrentUser user, String me, boolean allUnits,
                                   List<Long> scopeUnitIds, int size) {
        boolean executePerm = user.hasPerm(Permissions.CONTROL_EXECUTE_PROD);
        long count = todoQueryMapper.countExecuteItems(me, executePerm, allUnits, scopeUnitIds);
        if (count == 0) {
            return TodoGroup.empty();
        }
        List<TodoItem> items = todoQueryMapper
            .selectExecuteItems(me, executePerm, allUnits, scopeUnitIds, size)
            .stream()
            .map(this::orderItem)
            .toList();
        return new TodoGroup(count, items);
    }

    private TodoGroup mineGroup(String me, boolean allUnits, List<Long> scopeUnitIds, int size) {
        long count = todoQueryMapper.countMineItems(me, allUnits, scopeUnitIds);
        if (count == 0) {
            return TodoGroup.empty();
        }
        List<TodoItem> items = todoQueryMapper
            .selectMineTasks(me, allUnits, scopeUnitIds, size)
            .stream()
            .map(this::mineItem)
            .toList();
        return new TodoGroup(count, items);
    }

    // ------------------------------------------------------------------ rows to view

    private TodoItem testItem(TodoRow row) {
        return new TodoItem(
            OBJECT_ITEM,
            idOf(row.getObjectId()),
            row.getLabel(),
            row.getStatus(),
            itemStatusLabel(row.getStatus()),
            row.getUnitId(),
            row.getWaitingSince(),
            taskItemLink(row),
            hours(row.getWaitingSince()));
    }

    private TodoItem orderItem(TodoRow row) {
        return new TodoItem(
            OBJECT_ORDER,
            idOf(row.getObjectId()),
            row.getLabel(),
            row.getStatus(),
            itemStatusLabel(row.getStatus()),
            row.getUnitId(),
            row.getWaitingSince(),
            row.getPublishNo() == null ? null : "/releases/" + row.getPublishNo(),
            hours(row.getWaitingSince()));
    }

    private TodoItem mineItem(TodoRow row) {
        return new TodoItem(
            OBJECT_TASK,
            idOf(row.getObjectId()),
            row.getLabel(),
            row.getStatus(),
            taskStatusLabel(row.getStatus()),
            row.getUnitId(),
            row.getWaitingSince(),
            row.getObjectId() == null ? null : "/tasks/" + row.getObjectId(),
            hours(row.getWaitingSince()));
    }

    // ------------------------------------------------------------------ helpers

    /** {@code /tasks/{taskId}?item={itemId}}, the 变更项 detail of the task page. */
    private static String taskItemLink(TodoRow row) {
        if (row.getTaskId() == null || row.getItemId() == null) {
            return null;
        }
        return "/tasks/" + row.getTaskId() + "?item=" + row.getItemId();
    }

    /** The whole hours since {@code since}; a missing timestamp means zero. */
    private long hours(Instant since) {
        if (since == null) {
            return 0L;
        }
        long wholeHours = Duration.between(since, clock.instant()).toHours();
        return Math.max(wholeHours, 0L);
    }

    private static String itemStatusLabel(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return ItemStatus.valueOf(raw).label();
        } catch (IllegalArgumentException ex) {
            return raw;
        }
    }

    private static String taskStatusLabel(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return TaskStatus.valueOf(raw).label();
        } catch (IllegalArgumentException ex) {
            return raw;
        }
    }

    private static String idOf(Long id) {
        return id == null ? null : String.valueOf(id);
    }

    static int limitOf(Integer limit) {
        if (limit == null) {
            return DEFAULT_LIMIT;
        }
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new BizException(BizError.PARAM_INVALID,
                "limit 必须在 1 到 " + MAX_LIMIT + " 之间", Map.of("limit", limit));
        }
        return limit;
    }

    // ------------------------------------------------------------------ read model

    /** The four groups of the response, in the order the page renders them. */
    public record TodoBoard(TodoGroup test, TodoGroup approval, TodoGroup execute, TodoGroup mine) {

        static TodoBoard empty() {
            return new TodoBoard(TodoGroup.empty(), TodoGroup.empty(), TodoGroup.empty(),
                TodoGroup.empty());
        }
    }

    /** One group: the full count plus at most {@code limit} items. */
    public record TodoGroup(long count, List<TodoItem> items) {

        public TodoGroup {
            items = items == null ? List.of() : List.copyOf(items);
        }

        static TodoGroup empty() {
            return new TodoGroup(0L, List.of());
        }
    }

    /**
     * One row. {@code objectId} and {@code unitId} stay numeric here and become strings in the web
     * DTO; {@code waitingSince} is an instant and {@code waitingHours} the whole hours since then.
     */
    public record TodoItem(
        String objectType,
        String objectId,
        String label,
        String status,
        String statusLabel,
        Long unitId,
        Instant waitingSince,
        String link,
        long waitingHours) {
    }
}