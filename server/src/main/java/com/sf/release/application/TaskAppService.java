package com.sf.release.application;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sf.release.common.BizError;
import com.sf.release.common.BizException;
import com.sf.release.domain.task.ItemStatus;
import com.sf.release.domain.task.MemberRole;
import com.sf.release.domain.task.ReleaseTask;
import com.sf.release.domain.task.TaskMember;
import com.sf.release.domain.task.TaskProgress;
import com.sf.release.domain.task.TaskStatus;
import com.sf.release.infra.repo.AppUserRepository;
import com.sf.release.infra.repo.ReleaseTaskMapper;
import com.sf.release.infra.repo.ReleaseTaskMemberMapper;
import com.sf.release.infra.repo.SubsystemMapper;
import com.sf.release.infra.repo.po.ReleaseTaskMemberPO;
import com.sf.release.infra.repo.po.ReleaseTaskPO;
import com.sf.release.infra.security.UserContext;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

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
 * 「有阻塞」 flag (FR-T-04 / J9). The {@code release_item} table does not exist in Task 7,
 * so a task has no items yet and its progress is zero; the item statuses are supplied by
 * Task 8 and summarised by {@link TaskProgress} over {@link ItemStatus}.
 */
@Service
public class TaskAppService {

    private static final int NAME_MAX_LENGTH = 128;
    private static final int CREATOR_MAX_LENGTH = 32;
    private static final int USER_ID_MAX_LENGTH = 32;
    private static final int PURPOSE_MAX_LENGTH = 512;

    private final ReleaseTaskMapper taskMapper;
    private final ReleaseTaskMemberMapper memberMapper;
    private final SubsystemMapper subsystemMapper;
    private final AppUserRepository appUserRepository;

    public TaskAppService(ReleaseTaskMapper taskMapper,
                          ReleaseTaskMemberMapper memberMapper,
                          SubsystemMapper subsystemMapper,
                          AppUserRepository appUserRepository) {
        this.taskMapper = taskMapper;
        this.memberMapper = memberMapper;
        this.subsystemMapper = subsystemMapper;
        this.appUserRepository = appUserRepository;
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
        // release_item arrives with Task 8, so a task has no items yet. The rules below are
        // already defined over ItemStatus; Task 8 only has to pass the real statuses in.
        TaskProgress progress = TaskProgress.of(List.of());
        return new ProgressView(progress.done(), progress.total(), progress.rate(),
            progress.hasBlocking());
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

    /** A validated member awaiting the upsert, so the role is parsed only once. */
    private record MemberToAdd(String userId, MemberRole role) {
    }
}