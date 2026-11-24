package com.sf.release.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sf.release.application.audit.AuditCommand;
import com.sf.release.application.audit.AuditService;
import com.sf.release.common.BizError;
import com.sf.release.common.BizException;
import com.sf.release.domain.audit.AuditAction;
import com.sf.release.domain.resource.Subsystem;
import com.sf.release.domain.resource.SubsystemStatus;
import com.sf.release.infra.repo.AppUserRepository;
import com.sf.release.infra.repo.SubsystemMapper;
import com.sf.release.infra.repo.po.SubsystemPO;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Subsystem management use cases: create, update, detail and keyword paging.
 *
 * <p>Employee lookup only accepts existing {@code ACTIVE} users (owner, optional tool
 * authorisation agent). Name uniqueness is checked before the employee lookup: a
 * resubmitted existing name is reported as {@link BizError#DUPLICATE_NAME} even when the
 * owner 工号 is stale, which is the more actionable error. The database unique key
 * {@code uk_name} remains the final guard for concurrent creates.
 *
 * <p>Commands and views are nested here so the web layer depends on {@code application}
 * only (ArchUnit forbids {@code web -> domain}).
 */
@Service
public class SubsystemAppService {

    private static final int NAME_MAX_LENGTH = 128;
    private static final int DESCRIPTION_MAX_LENGTH = 512;
    private static final int GIT_URL_MAX_LENGTH = 512;
    private static final int CODING_PROJECT_MAX_LENGTH = 128;
    private static final int MAX_PAGE_SIZE = 100;

    private final SubsystemMapper subsystemMapper;
    private final AppUserRepository appUserRepository;
    private final AuditService audit;

    public SubsystemAppService(SubsystemMapper subsystemMapper, AppUserRepository appUserRepository,
                               AuditService audit) {
        this.subsystemMapper = subsystemMapper;
        this.appUserRepository = appUserRepository;
        this.audit = audit;
    }

    /** Creates an {@code ACTIVE} subsystem and returns its id. */
    @Transactional
    public long create(CreateCmd cmd) {
        if (cmd == null) {
            throw new BizException(BizError.PARAM_INVALID, "创建参数不能为空");
        }
        String name = requireName(cmd.name());
        requireNameAvailable(name, null);
        String ownerId = requireActiveEmployee(cmd.ownerId(), "ownerId", "负责人");
        String agentId = optionalActiveEmployee(cmd.agentId(), "agentId", "工具授权代理");

        String description = requireMaxLength(cmd.description(), DESCRIPTION_MAX_LENGTH, "description", "描述");
        String gitUrl = requireMaxLength(cmd.gitUrl(), GIT_URL_MAX_LENGTH, "gitUrl", "Git 仓库地址");
        String codingProject = requireMaxLength(
            cmd.codingProject(), CODING_PROJECT_MAX_LENGTH, "codingProject", "CODING 项目");

        Instant now = Instant.now();
        Subsystem subsystem = new Subsystem(null, name, ownerId, agentId, SubsystemStatus.ACTIVE,
            description, gitUrl, codingProject, now, now);
        SubsystemPO po = SubsystemPO.of(subsystem);
        try {
            subsystemMapper.insert(po);
        } catch (DuplicateKeyException ex) {
            // Concurrent create won the unique-key race after our pre-check.
            throw duplicateName(name);
        }
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("name", name);
        after.put("ownerId", ownerId);
        after.put("agentId", agentId);
        after.put("status", SubsystemStatus.ACTIVE.name());
        audit.record(AuditCommand.of(AuditAction.RESOURCE_UPDATE, String.valueOf(po.getId()), name,
                "新增系统：" + name)
            .after(after)
            .scopeSubsystemId(po.getId())
            .build());
        return po.getId();
    }

    /**
     * Full replacement of the editable fields. A blank {@code status} keeps the current
     * lifecycle value; the nullable text fields are replaced, so a null clears them.
     */
    @Transactional
    public void update(long id, UpdateCmd cmd) {
        if (cmd == null) {
            throw new BizException(BizError.PARAM_INVALID, "更新参数不能为空");
        }
        Subsystem existing = load(id);
        String name = requireName(cmd.name());
        requireNameAvailable(name, id);
        String ownerId = requireActiveEmployee(cmd.ownerId(), "ownerId", "负责人");
        String agentId = optionalActiveEmployee(cmd.agentId(), "agentId", "工具授权代理");
        SubsystemStatus status = cmd.status() == null || cmd.status().isBlank()
            ? existing.status()
            : parseStatus(cmd.status());

        String description = requireMaxLength(cmd.description(), DESCRIPTION_MAX_LENGTH, "description", "描述");
        String gitUrl = requireMaxLength(cmd.gitUrl(), GIT_URL_MAX_LENGTH, "gitUrl", "Git 仓库地址");
        String codingProject = requireMaxLength(
            cmd.codingProject(), CODING_PROJECT_MAX_LENGTH, "codingProject", "CODING 项目");

        int rows;
        try {
            rows = subsystemMapper.update(null, Wrappers.<SubsystemPO>lambdaUpdate()
                .eq(SubsystemPO::getId, id)
                .set(SubsystemPO::getName, name)
                .set(SubsystemPO::getOwnerId, ownerId)
                .set(SubsystemPO::getAgentId, agentId)
                .set(SubsystemPO::getStatus, status.name())
                .set(SubsystemPO::getDescription, description)
                .set(SubsystemPO::getGitUrl, gitUrl)
                .set(SubsystemPO::getCodingProject, codingProject)
                .set(SubsystemPO::getUpdatedAt, Instant.now()));
        } catch (DuplicateKeyException ex) {
            // Concurrent rename won the unique-key race after our pre-check.
            throw duplicateName(name);
        }
        if (rows == 0) {
            throw notFound(id);
        }
        Map<String, Object> before = new LinkedHashMap<>();
        before.put("name", existing.name());
        before.put("ownerId", existing.ownerId());
        before.put("agentId", existing.agentId());
        before.put("status", existing.status().name());
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("name", name);
        after.put("ownerId", ownerId);
        after.put("agentId", agentId);
        after.put("status", status.name());
        audit.record(AuditCommand.of(AuditAction.RESOURCE_UPDATE, String.valueOf(id), name,
                "修改系统：" + name)
            .before(before)
            .after(after)
            .scopeSubsystemId(id)
            .build());
    }

    public SubsystemView get(long id) {
        return toView(load(id));
    }

    /** Keyword search over the name, ordered by name; blank keyword means no filter. */
    public PageResult<SubsystemView> page(PageQuery query) {
        if (query == null) {
            throw new BizException(BizError.PARAM_INVALID, "分页参数不能为空");
        }
        if (query.pageNo() < 1) {
            throw new BizException(BizError.PARAM_INVALID, "页码必须大于 0",
                Map.of("pageNo", query.pageNo()));
        }
        if (query.pageSize() < 1 || query.pageSize() > MAX_PAGE_SIZE) {
            throw new BizException(BizError.PARAM_INVALID,
                "每页条数必须在 1 到 " + MAX_PAGE_SIZE + " 之间", Map.of("pageSize", query.pageSize()));
        }
        String keyword = trimToNull(query.keyword());
        LambdaQueryWrapper<SubsystemPO> wrapper = Wrappers.<SubsystemPO>lambdaQuery()
            .like(keyword != null, SubsystemPO::getName, keyword)
            .orderByAsc(SubsystemPO::getName)
            .orderByAsc(SubsystemPO::getId);

        Page<SubsystemPO> page = subsystemMapper.selectPage(
            new Page<>(query.pageNo(), query.pageSize()), wrapper);
        List<SubsystemView> records = page.getRecords().stream()
            .map(po -> toView(po.toDomain()))
            .toList();
        return new PageResult<>(page.getTotal(), page.getCurrent(), page.getSize(), records);
    }

    private Subsystem load(long id) {
        SubsystemPO po = subsystemMapper.selectById(id);
        if (po == null) {
            throw notFound(id);
        }
        return po.toDomain();
    }

    private static BizException notFound(long id) {
        return new BizException(BizError.PARAM_INVALID, "系统不存在", Map.of("id", id));
    }

    private void requireNameAvailable(String name, Long excludingId) {
        LambdaQueryWrapper<SubsystemPO> query = Wrappers.<SubsystemPO>lambdaQuery()
            .eq(SubsystemPO::getName, name);
        if (excludingId != null) {
            query.ne(SubsystemPO::getId, excludingId);
        }
        Long existing = subsystemMapper.selectCount(query);
        if (existing != null && existing > 0) {
            throw duplicateName(name);
        }
    }

    private static BizException duplicateName(String name) {
        return new BizException(BizError.DUPLICATE_NAME, "系统名称已存在：" + name,
            Map.of("name", name));
    }

    private static String requireName(String raw) {
        String name = trimToNull(raw);
        if (name == null) {
            throw new BizException(BizError.PARAM_INVALID, "系统名称不能为空",
                Map.of("name", "名称不能为空"));
        }
        if (name.length() > NAME_MAX_LENGTH) {
            throw new BizException(BizError.PARAM_INVALID,
                "系统名称长度不能超过 " + NAME_MAX_LENGTH, Map.of("name", name));
        }
        return name;
    }

    private String requireActiveEmployee(String raw, String field, String label) {
        String userId = trimToNull(raw);
        if (userId == null) {
            throw new BizException(BizError.PARAM_INVALID, label + "不能为空",
                Map.of(field, label + "不能为空"));
        }
        if (!appUserRepository.existsActive(userId)) {
            throw new BizException(BizError.PARAM_INVALID, label + "不存在或已停用：" + userId,
                Map.of(field, userId));
        }
        return userId;
    }

    private String optionalActiveEmployee(String raw, String field, String label) {
        String userId = trimToNull(raw);
        if (userId == null) {
            return null;
        }
        if (!appUserRepository.existsActive(userId)) {
            throw new BizException(BizError.PARAM_INVALID, label + "不存在或已停用：" + userId,
                Map.of(field, userId));
        }
        return userId;
    }

    private static String requireMaxLength(String raw, int maxLength, String field, String label) {
        String value = trimToNull(raw);
        if (value != null && value.length() > maxLength) {
            throw new BizException(BizError.PARAM_INVALID,
                label + "长度不能超过 " + maxLength, Map.of(field, value));
        }
        return value;
    }

    private static SubsystemStatus parseStatus(String raw) {
        String normalized = raw.trim().toUpperCase(Locale.ROOT);
        try {
            return SubsystemStatus.valueOf(normalized);
        } catch (IllegalArgumentException ex) {
            throw new BizException(BizError.PARAM_INVALID, "状态不合法：" + raw,
                Map.of("status", raw));
        }
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static SubsystemView toView(Subsystem subsystem) {
        return new SubsystemView(
            subsystem.id(),
            subsystem.name(),
            subsystem.ownerId(),
            subsystem.agentId(),
            subsystem.status().name(),
            subsystem.description(),
            subsystem.gitUrl(),
            subsystem.codingProject(),
            subsystem.createdAt(),
            subsystem.updatedAt());
    }

    /** Create command. The three-argument shorthand leaves the detail fields empty. */
    public record CreateCmd(
        String name,
        String ownerId,
        String agentId,
        String description,
        String gitUrl,
        String codingProject) {

        public CreateCmd(String name, String ownerId, String agentId) {
            this(name, ownerId, agentId, null, null, null);
        }
    }

    /**
     * Update command: full replacement of the editable fields. A null or blank
     * {@code status} keeps the current status; a null nullable text field clears it.
     */
    public record UpdateCmd(
        String name,
        String ownerId,
        String agentId,
        String status,
        String description,
        String gitUrl,
        String codingProject) {

        public UpdateCmd(String name, String ownerId, String agentId) {
            this(name, ownerId, agentId, null, null, null, null);
        }

        public UpdateCmd(String name, String ownerId, String agentId, String status) {
            this(name, ownerId, agentId, status, null, null, null);
        }
    }

    /** Page request; {@code keyword} filters on the subsystem name. */
    public record PageQuery(int pageNo, int pageSize, String keyword) {
    }

    /** Page response shared shape: total + the requested page. */
    public record PageResult<T>(long total, long pageNo, long pageSize, List<T> records) {

        public PageResult {
            records = records == null ? List.of() : List.copyOf(records);
        }
    }

    /** Read model exposed to the web layer. */
    public record SubsystemView(
        long id,
        String name,
        String ownerId,
        String agentId,
        String status,
        String description,
        String gitUrl,
        String codingProject,
        Instant createdAt,
        Instant updatedAt) {
    }
}