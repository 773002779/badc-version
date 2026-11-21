package com.sf.release.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sf.release.application.SubsystemAppService.PageResult;
import com.sf.release.common.BizError;
import com.sf.release.common.BizException;
import com.sf.release.domain.task.ItemStatus;
import com.sf.release.domain.task.MemberRole;
import com.sf.release.domain.task.ReleaseTask;
import com.sf.release.domain.task.TaskMember;
import com.sf.release.domain.task.TaskProgress;
import com.sf.release.domain.task.TaskStatus;
import com.sf.release.infra.repo.AppUserRepository;
import com.sf.release.infra.repo.ReleaseItemMapper;
import com.sf.release.infra.repo.ReleaseTaskMapper;
import com.sf.release.infra.repo.ReleaseTaskMemberMapper;
import com.sf.release.infra.repo.SubsystemMapper;
import com.sf.release.infra.repo.TaskQueryMapper;
import com.sf.release.infra.repo.po.ReleaseItemPO;
import com.sf.release.infra.repo.po.ReleaseTaskMemberPO;
import com.sf.release.infra.repo.po.ReleaseTaskPO;
import com.sf.release.infra.repo.po.SubsystemPO;
import com.sf.release.infra.repo.po.TaskStatusCountRow;
import com.sf.release.infra.security.UserContext;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Release task management: create a task, attach its members and read its progress.
 *
 * <p><strong>Creator.</strong> The task is attributed to the authenticated caller. The
 * command can name a creator explicitly for seeding and back-office flows, but the HTTP
 * adapter never does, so a request cannot attribute a task to somebody else.
 *
 * <p><strong>Members.</strong> A user appears at most once per task: adding the same
 * {@code (taskId, userId)} again updates the role in place (the batch add screen relies on
 * this). Member ids must be existing {@code ACTIVE} users because notifications are
 * addressed to them (C22).
 *
 * <p><strong>Progress.</strong> {@code done/total} over the 变更项 of the task and the
 * 「有阻塞」 flag (FR-T-04 / J9), computed from the real {@code release_item.status} values
 * and never persisted. A task without items has rate {@code 0} and no blocking item.
 *
 * <p><strong>Reads.</strong> The P1 task page uses {@link #page(TaskQuery)},
 * {@link #detail(long)} and {@link #items(long)}. The list aggregates a whole page with one
 * grouped {@code release_item} query; the detail reuses that aggregate for its
 * {@code itemCount}; every read of an unknown task id raises
 * {@link BizError#PARAM_INVALID} with {@code {taskId}}.
 */
@Service
public class TaskAppService {

    private static final int NAME_MAX_LENGTH = 128;
    private static final int CREATOR_MAX_LENGTH = 32;
    private static final int USER_ID_MAX_LENGTH = 32;
    private static final int PURPOSE_MAX_LENGTH = 512;
    private static final int MAX_PAGE_SIZE = 100;

    private final ReleaseTaskMapper taskMapper;
    private final ReleaseTaskMemberMapper memberMapper;
    private final ReleaseItemMapper itemMapper;
    private final SubsystemMapper subsystemMapper;
    private final AppUserRepository appUserRepository;
    private final TaskQueryMapper taskQueryMapper;

    public TaskAppService(ReleaseTaskMapper taskMapper,
                          ReleaseTaskMemberMapper memberMapper,
                          ReleaseItemMapper itemMapper,
                          SubsystemMapper subsystemMapper,
                          AppUserRepository appUserRepository,
                          TaskQueryMapper taskQueryMapper) {
        this.taskMapper = taskMapper;
        this.memberMapper = memberMapper;
        this.itemMapper = itemMapper;
        this.subsystemMapper = subsystemMapper;
        this.appUserRepository = appUserRepository;
        this.taskQueryMapper = taskQueryMapper;
    }

    /**
     * Creates an {@code ACTIVE} task under an existing subsystem and returns its id. The
     * name is trimmed and unique inside the subsystem.
     */
    @Transactional
    public long create(CreateTaskCmd cmd) {
        if (cmd == null) {
            throw new BizException(BizError.PARAM_INVALID, "创建参数不能为空");
        }
        if (cmd.subsystemId() == null) {
            throw new BizException(BizError.PARAM_INVALID, "所属子系统不能为空",
                Map.of("subsystemId", "不能为空"));
        }
        String name = requireText(cmd.name(), NAME_MAX_LENGTH, "name", "任务名称");
        if (subsystemMapper.selectById(cmd.subsystemId()) == null) {
            throw new BizException(BizError.PARAM_INVALID, "所属子系统不存在",
                Map.of("subsystemId", cmd.subsystemId()));
        }
        requireNameAvailable(cmd.subsystemId(), name);
        String creator = resolveCreator(cmd.creator());
        String purpose = optionalText(cmd.purpose(), PURPOSE_MAX_LENGTH, "purpose", "目的");

        Instant now = Instant.now();
        ReleaseTask task = new ReleaseTask(null, cmd.subsystemId(), name, creator, purpose,
            cmd.planFinishDate(), TaskStatus.ACTIVE, now, now);
        ReleaseTaskPO po = ReleaseTaskPO.of(task);
        try {
            taskMapper.insert(po);
        } catch (DuplicateKeyException ex) {
            // Concurrent create won the unique-key race after our pre-check.
            throw duplicateName(name);
        }
        return po.getId();
    }

    /**
     * Adds or updates the given members. Repeating a user updates that user's role and
     * keeps a single row; the whole batch is validated before anything is written.
     */
    @Transactional
    public void addMembers(long taskId, List<MemberCmd> members) {
        loadTask(taskId);
        if (members == null || members.isEmpty()) {
            throw new BizException(BizError.PARAM_INVALID, "成员不能为空",
                Map.of("members", "不能为空"));
        }
        // Validate and normalise the whole batch before writing anything; a later member
        // must not leave the earlier ones behind when the request is rejected.
        List<MemberToAdd> pending = new ArrayList<>(members.size());
        for (MemberCmd member : members) {
            if (member == null) {
                throw new BizException(BizError.PARAM_INVALID, "成员不能为空");
            }
            String userId = requireActiveEmployee(member.userId(), "userId", "成员");
            pending.add(new MemberToAdd(userId, parseRole(member.role())));
        }
        for (MemberToAdd member : pending) {
            upsertMember(taskId, member.userId(), member.role());
        }
    }

    /**
     * Progress of a task: {@code done/total} over its 变更项 plus the 「有阻塞」 flag. A task
     * without items has rate {@code 0} and no blocking item.
     */
    public ProgressView progress(long taskId) {
        loadTask(taskId);
        // Task 8 owns release_item; the state machine writes its status, the progress rule
        // only reads it (FR-T-04 / J9).
        List<ItemStatus> statuses = itemMapper.selectList(Wrappers.<ReleaseItemPO>lambdaQuery()
                .eq(ReleaseItemPO::getTaskId, taskId)
                .orderByAsc(ReleaseItemPO::getId))
            .stream()
            .map(po -> po.toDomain().status())
            .toList();
        TaskProgress progress = TaskProgress.of(statuses);
        return new ProgressView(progress.done(), progress.total(), progress.rate(),
            progress.hasBlocking());
    }

    /**
     * Paged task list, newest first. {@code subsystemId}, {@code status} and the name
     * {@code keyword} are optional filters; the keyword is bound as a statement parameter.
     *
     * <p>The progress of the whole page is aggregated from one grouped
     * {@code release_item} query and the subsystem names from one batch lookup, so the
     * statement count does not grow with the number of tasks on the page.
     */
    public PageResult<TaskView> page(TaskQuery query) {
        if (query == null) {
            throw new BizException(BizError.PARAM_INVALID, "分页参数不能为空");
        }
        if (query.pageNo() < 1) {
            throw new BizException(BizError.PARAM_INVALID, "页码必须大于 0",
                Map.of("pageNo", query.pageNo()));
        }
        if (query.pageSize() < 1 || query.pageSize() > MAX_PAGE_SIZE) {
            throw new BizException(BizError.PARAM_INVALID,
                "每页条数必须在 1 到 " + MAX_PAGE_SIZE + " 之间",
                Map.of("pageSize", query.pageSize()));
        }
        TaskStatus status = query.status() == null || query.status().isBlank()
            ? null
            : parseStatus(query.status());
        String keyword = trimToNull(query.keyword());

        LambdaQueryWrapper<ReleaseTaskPO> wrapper = Wrappers.<ReleaseTaskPO>lambdaQuery()
            .eq(query.subsystemId() != null, ReleaseTaskPO::getSubsystemId, query.subsystemId())
            .eq(status != null, ReleaseTaskPO::getStatus, status == null ? null : status.name())
            .like(keyword != null, ReleaseTaskPO::getName, keyword)
            .orderByDesc(ReleaseTaskPO::getId);

        // The count and the page are issued as two explicit statements so the read budget of
        // the endpoint stays visible: count + page + subsystem names + grouped progress.
        long total = taskMapper.selectCount(wrapper);
        Page<ReleaseTaskPO> page = new Page<>(query.pageNo(), query.pageSize());
        page.setSearchCount(false);
        taskMapper.selectPage(page, wrapper);
        List<ReleaseTaskPO> rows = page.getRecords();
        if (rows.isEmpty()) {
            return new PageResult<>(total, page.getCurrent(), page.getSize(), List.of());
        }
        Map<Long, String> subsystemNames = subsystemNames(rows.stream()
            .map(ReleaseTaskPO::getSubsystemId)
            .filter(Objects::nonNull)
            .distinct()
            .toList());
        Map<Long, TaskProgress> progress = progressByTask(rows.stream()
            .map(ReleaseTaskPO::getId)
            .filter(Objects::nonNull)
            .toList());
        List<TaskView> records = rows.stream()
            .map(po -> toTaskView(po, subsystemNames, progressOf(progress, po.getId())))
            .toList();
        return new PageResult<>(total, page.getCurrent(), page.getSize(), records);
    }

    /**
     * Task detail: the task view, its members with display names and the number of its
     * 变更项. The item count reuses the grouped progress rows instead of a second count.
     */
    public TaskDetailView detail(long taskId) {
        ReleaseTaskPO po = loadTaskRow(taskId);
        Map<Long, String> subsystemNames = po.getSubsystemId() == null
            ? Map.of()
            : subsystemNames(List.of(po.getSubsystemId()));
        TaskProgress progress = progressOf(progressByTask(List.of(taskId)), taskId);
        List<MemberView> members = taskQueryMapper.selectMembers(taskId).stream()
            .map(row -> new MemberView(row.getUserId(), row.getName(), row.getRole()))
            .toList();
        return new TaskDetailView(toTaskView(po, subsystemNames, progress), members,
            progress.total());
    }

    /** The 变更项 of a task with the joined deployment unit name, newest first. */
    public List<ItemView> items(long taskId) {
        loadTaskRow(taskId);
        return taskQueryMapper.selectItems(taskId).stream()
            .map(row -> new ItemView(
                row.getId() == null ? 0L : row.getId(),
                row.getCrid(),
                row.getUnitId(),
                row.getUnitName(),
                row.getType(),
                row.getVersion(),
                row.getSourceBranch(),
                row.getPlanReleaseDate(),
                row.getStatus(),
                row.getGateStatus(),
                row.getCreatedAt(),
                row.getUpdatedAt()))
            .toList();
    }

    /**
     * Ids of the {@code PRODUCT} members of a task, used to address freeze notifications
     * (C22). Task 8's notification fan-out consumes this list, not the whole member list.
     */
    public List<String> productsOf(long taskId) {
        loadTask(taskId);
        return memberMapper.selectList(Wrappers.<ReleaseTaskMemberPO>lambdaQuery()
                .eq(ReleaseTaskMemberPO::getTaskId, taskId)
                .eq(ReleaseTaskMemberPO::getRole, MemberRole.PRODUCT.name())
                .orderByAsc(ReleaseTaskMemberPO::getUserId))
            .stream()
            .map(ReleaseTaskMemberPO::getUserId)
            .toList();
    }

    private Map<Long, String> subsystemNames(List<Long> subsystemIds) {
        if (subsystemIds == null || subsystemIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> names = new HashMap<>();
        for (SubsystemPO po : subsystemMapper.selectBatchIds(subsystemIds)) {
            names.put(po.getId(), po.getName());
        }
        return names;
    }

    /**
     * {@code task -> progress} for a whole page in one grouped query. The domain
     * {@link TaskProgress#ofCounts(Map)} applies the progress rule, so the read side keeps
     * no second definition of done/blocking.
     */
    private Map<Long, TaskProgress> progressByTask(List<Long> taskIds) {
        if (taskIds == null || taskIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, Map<ItemStatus, Long>> countsByTask = new HashMap<>();
        for (TaskStatusCountRow row : taskQueryMapper.selectStatusCounts(taskIds)) {
            if (row.getTaskId() == null || row.getStatus() == null) {
                continue;
            }
            ItemStatus status;
            try {
                status = ItemStatus.valueOf(row.getStatus());
            } catch (IllegalArgumentException unknownStatus) {
                // A status outside the state machine is not a progress bucket; the row is
                // ignored rather than failing the whole read page.
                continue;
            }
            long count = row.getCnt() == null ? 0L : row.getCnt();
            if (count == 0) {
                continue;
            }
            countsByTask
                .computeIfAbsent(row.getTaskId(), id -> new EnumMap<>(ItemStatus.class))
                .merge(status, count, Long::sum);
        }
        Map<Long, TaskProgress> progress = new HashMap<>();
        for (Map.Entry<Long, Map<ItemStatus, Long>> entry : countsByTask.entrySet()) {
            progress.put(entry.getKey(), TaskProgress.ofCounts(entry.getValue()));
        }
        return progress;
    }

    private static TaskProgress progressOf(Map<Long, TaskProgress> progress, Long taskId) {
        TaskProgress empty = TaskProgress.ofCounts(Map.of());
        return taskId == null ? empty : progress.getOrDefault(taskId, empty);
    }

    private static TaskView toTaskView(ReleaseTaskPO po, Map<Long, String> subsystemNames,
                                       TaskProgress progress) {
        String status = po.getStatus() == null || po.getStatus().isBlank()
            ? TaskStatus.ACTIVE.name()
            : po.getStatus();
        return new TaskView(
            po.getId() == null ? 0L : po.getId(),
            po.getName(),
            po.getSubsystemId(),
            po.getSubsystemId() == null ? null : subsystemNames.get(po.getSubsystemId()),
            po.getCreator(),
            po.getPlanFinishDate(),
            po.getPurpose(),
            status,
            po.getCreatedAt(),
            progress.done(),
            progress.total(),
            progress.rate(),
            progress.hasBlocking());
    }

    private ReleaseTaskPO loadTaskRow(long taskId) {
        ReleaseTaskPO po = taskMapper.selectById(taskId);
        if (po == null) {
            throw notFoundRead(taskId);
        }
        return po;
    }

    private static TaskStatus parseStatus(String raw) {
        String normalized = raw.trim().toUpperCase(Locale.ROOT);
        try {
            return TaskStatus.valueOf(normalized);
        } catch (IllegalArgumentException ex) {
            throw new BizException(BizError.PARAM_INVALID, "状态不合法：" + raw,
                Map.of("status", raw));
        }
    }

    private void upsertMember(long taskId, String userId, MemberRole role) {
        Instant now = Instant.now();
        int rows = memberMapper.update(null, Wrappers.<ReleaseTaskMemberPO>lambdaUpdate()
            .eq(ReleaseTaskMemberPO::getTaskId, taskId)
            .eq(ReleaseTaskMemberPO::getUserId, userId)
            .set(ReleaseTaskMemberPO::getRole, role.name())
            .set(ReleaseTaskMemberPO::getUpdatedAt, now));
        if (rows > 0) {
            return;
        }
        TaskMember member = new TaskMember(null, taskId, userId, role, now, now);
        try {
            memberMapper.insert(ReleaseTaskMemberPO.of(member));
        } catch (DuplicateKeyException ex) {
            // A concurrent add inserted the same (task_id, user_id) first; fall back to the
            // role update, which is the documented idempotent behaviour.
            memberMapper.update(null, Wrappers.<ReleaseTaskMemberPO>lambdaUpdate()
                .eq(ReleaseTaskMemberPO::getTaskId, taskId)
                .eq(ReleaseTaskMemberPO::getUserId, userId)
                .set(ReleaseTaskMemberPO::getRole, role.name())
                .set(ReleaseTaskMemberPO::getUpdatedAt, Instant.now()));
        }
    }

    private ReleaseTask loadTask(long taskId) {
        ReleaseTaskPO po = taskMapper.selectById(taskId);
        if (po == null) {
            throw notFound(taskId);
        }
        return po.toDomain();
    }

    private void requireNameAvailable(long subsystemId, String name) {
        Long existing = taskMapper.selectCount(Wrappers.<ReleaseTaskPO>lambdaQuery()
            .eq(ReleaseTaskPO::getSubsystemId, subsystemId)
            .eq(ReleaseTaskPO::getName, name));
        if (existing != null && existing > 0) {
            throw duplicateName(name);
        }
    }

    /**
     * The authenticated caller is the creator. An explicit command value wins so seeding
     * and back-office flows can attribute a task, and it must be an active employee.
     */
    private String resolveCreator(String requested) {
        String explicit = trimToNull(requested);
        if (explicit == null) {
            return UserContext.current().userId();
        }
        if (explicit.length() > CREATOR_MAX_LENGTH) {
            throw new BizException(BizError.PARAM_INVALID,
                "创建人长度不能超过 " + CREATOR_MAX_LENGTH, Map.of("creator", "长度超限"));
        }
        return requireActiveEmployee(explicit, "creator", "创建人");
    }

    private String requireActiveEmployee(String raw, String field, String label) {
        String userId = requireText(raw, USER_ID_MAX_LENGTH, field, label);
        if (!appUserRepository.existsActive(userId)) {
            throw new BizException(BizError.PARAM_INVALID, label + "不存在或已停用：" + userId,
                Map.of(field, userId));
        }
        return userId;
    }

    private static MemberRole parseRole(String raw) {
        String value = trimToNull(raw);
        if (value == null) {
            throw new BizException(BizError.PARAM_INVALID, "成员角色不能为空",
                Map.of("role", "不能为空"));
        }
        try {
            return MemberRole.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new BizException(BizError.PARAM_INVALID, "成员角色不合法：" + raw,
                Map.of("role", raw));
        }
    }

    private static String requireText(String raw, int maxLength, String field, String label) {
        String value = trimToNull(raw);
        if (value == null) {
            throw new BizException(BizError.PARAM_INVALID, label + "不能为空",
                Map.of(field, "不能为空"));
        }
        if (value.length() > maxLength) {
            throw new BizException(BizError.PARAM_INVALID,
                label + "长度不能超过 " + maxLength, Map.of(field, "长度超限"));
        }
        return value;
    }

    private static String optionalText(String raw, int maxLength, String field, String label) {
        String value = trimToNull(raw);
        if (value != null && value.length() > maxLength) {
            throw new BizException(BizError.PARAM_INVALID,
                label + "长度不能超过 " + maxLength, Map.of(field, "长度超限"));
        }
        return value;
    }

    private static BizException duplicateName(String name) {
        return new BizException(BizError.DUPLICATE_NAME, "发布任务名称已存在：" + name,
            Map.of("name", name));
    }

    private static BizException notFound(long taskId) {
        return new BizException(BizError.PARAM_INVALID, "发布任务不存在", Map.of("id", taskId));
    }

    /** Read endpoints name the unknown id as {@code taskId}, the field the frontend reads. */
    private static BizException notFoundRead(long taskId) {
        return new BizException(BizError.PARAM_INVALID, "发布任务不存在", Map.of("taskId", taskId));
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * Create command. {@code creator} is optional: a blank value records the authenticated
     * caller (the web adapter always leaves it blank).
     */
    public record CreateTaskCmd(
        String name,
        Long subsystemId,
        String creator,
        LocalDate planFinishDate,
        String purpose) {

        public CreateTaskCmd(String name, Long subsystemId, String creator) {
            this(name, subsystemId, creator, null, null);
        }
    }

    /** One member to add or update; {@code role} is a {@link MemberRole} name. */
    public record MemberCmd(String userId, String role) {
    }

    /** Progress read model: the completion rate and the 「有阻塞」 flag (FR-T-04 / J9). */
    public record ProgressView(long done, long total, double rate, boolean hasBlocking) {
    }

    /** Task list query: optional subsystem, status and name keyword filters. */
    public record TaskQuery(int pageNo, int pageSize, Long subsystemId, String status,
                            String keyword) {
    }

    /**
     * Task list/card row. {@code subsystemName} is the joined subsystem name, and the last
     * four fields are the progress aggregate of the task's 变更项.
     */
    public record TaskView(
        long id,
        String name,
        Long subsystemId,
        String subsystemName,
        String creator,
        LocalDate planFinishDate,
        String purpose,
        String status,
        Instant createdAt,
        long done,
        long total,
        double rate,
        boolean hasBlocking) {
    }

    /** Task detail: the task itself, its members and the number of its 变更项. */
    public record TaskDetailView(TaskView task, List<MemberView> members, long itemCount) {
    }

    /** A task member with the display name from {@code app_user}; the name may be null. */
    public record MemberView(String userId, String name, String role) {
    }

    /** One 变更项 with the joined deployment unit name. */
    public record ItemView(
        long id,
        String crid,
        Long unitId,
        String unitName,
        String type,
        String version,
        String sourceBranch,
        LocalDate planReleaseDate,
        String status,
        String gateStatus,
        Instant createdAt,
        Instant updatedAt) {
    }

    /** A validated member awaiting the upsert, so the role is parsed only once. */
    private record MemberToAdd(String userId, MemberRole role) {
    }
}