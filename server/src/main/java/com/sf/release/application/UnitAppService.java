package com.sf.release.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sf.release.common.BizError;
import com.sf.release.common.BizException;
import com.sf.release.domain.resource.BranchMode;
import com.sf.release.domain.resource.CodingTaskBinding;
import com.sf.release.domain.resource.DeployUnit;
import com.sf.release.domain.resource.EnvDe;
import com.sf.release.domain.resource.EnvKind;
import com.sf.release.domain.resource.TaskKind;
import com.sf.release.domain.resource.UnitStatus;
import com.sf.release.domain.resource.UnitType;
import com.sf.release.infra.repo.AppUserRepository;
import com.sf.release.infra.repo.CodingTaskBindingMapper;
import com.sf.release.infra.repo.DeployUnitMapper;
import com.sf.release.infra.repo.EnvDeMapper;
import com.sf.release.infra.repo.SubsystemMapper;
import com.sf.release.infra.repo.po.CodingTaskBindingPO;
import com.sf.release.infra.repo.po.DeployUnitPO;
import com.sf.release.infra.repo.po.EnvDePO;
import com.sf.release.infra.repo.po.SubsystemPO;
import com.sf.release.infra.security.ScopeGuard;
import com.sf.release.infra.security.UserContext;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Deployment unit management: units (and second-party libraries), their CODING task
 * bindings and their environment DEs.
 *
 * <p><strong>Data scope.</strong> The unit list is filtered explicitly with
 * {@code id IN (scopeUnitIds)} for a caller with {@link com.sf.release.infra.security.CurrentUser#allUnits()}
 * {@code = false}; a caller with an empty scope sees an empty page. Unit-scoped reads and
 * writes call {@link ScopeGuard} and fail closed with {@code FORBIDDEN}. There is no
 * generic SQL rewriter in phase 1.
 *
 * <p><strong>CODING task binding.</strong> Bindings are keyed by
 * {@code (unitId, taskKind)}. {@link #bindCodingTasks} upserts the given kinds and leaves
 * the other kinds untouched, so a unit can be configured incrementally. Three kinds are
 * required for publishing ({@code BUILD}, {@code TEST_DEPLOY}, {@code PROD_DEPLOY});
 * {@code DAILY_DEPLOY} is optional and daily releases fall back to the test deployment
 * task.
 *
 * <p><strong>Environment DE.</strong> {@code deploy_batch_no} orders DEs; the list is
 * always sorted by it so the orchestrator can deploy batches serially and stop on the
 * first failure (C14).
 */
@Service
public class UnitAppService {

    private static final int NAME_MAX_LENGTH = 128;
    private static final int DU_MAX_LENGTH = 128;
    private static final int FRAMEWORK_TEMPLATE_MAX_LENGTH = 64;
    private static final int DESCRIPTION_MAX_LENGTH = 512;
    private static final int MODULE_PATH_MAX_LENGTH = 255;
    private static final int DEPLOY_TYPE_MAX_LENGTH = 32;
    private static final int IMAGE_TYPE_MAX_LENGTH = 32;
    private static final int IMAGE_NAME_MAX_LENGTH = 255;
    private static final int MODULE_LABEL_MAX_LENGTH = 64;
    private static final int TAG_MAX_LENGTH = 64;
    private static final int TASK_ID_MAX_LENGTH = 64;
    private static final int ENV_TAG_MAX_LENGTH = 64;
    private static final int DE_NAME_MAX_LENGTH = 128;
    private static final int RESOURCE_NAME_MAX_LENGTH = 128;
    private static final int CODING_DEPLOY_TARGET_MAX_LENGTH = 128;
    private static final int MAX_PAGE_SIZE = 100;

    private static final List<TaskKind> PUBLISH_REQUIRED =
        List.of(TaskKind.BUILD, TaskKind.TEST_DEPLOY, TaskKind.PROD_DEPLOY);

    private final DeployUnitMapper unitMapper;
    private final EnvDeMapper envDeMapper;
    private final CodingTaskBindingMapper bindingMapper;
    private final SubsystemMapper subsystemMapper;
    private final AppUserRepository appUserRepository;
    private final ObjectMapper objectMapper;

    public UnitAppService(DeployUnitMapper unitMapper,
                          EnvDeMapper envDeMapper,
                          CodingTaskBindingMapper bindingMapper,
                          SubsystemMapper subsystemMapper,
                          AppUserRepository appUserRepository,
                          ObjectMapper objectMapper) {
        this.unitMapper = unitMapper;
        this.envDeMapper = envDeMapper;
        this.bindingMapper = bindingMapper;
        this.subsystemMapper = subsystemMapper;
        this.appUserRepository = appUserRepository;
        this.objectMapper = objectMapper;
    }

    /** Creates a unit under an existing subsystem and returns its id. */
    @Transactional
    public long create(CreateCmd cmd) {
        if (cmd == null) {
            throw new BizException(BizError.PARAM_INVALID, "创建参数不能为空");
        }
        if (cmd.subsystemId() == null) {
            throw new BizException(BizError.PARAM_INVALID, "所属系统不能为空",
                Map.of("subsystemId", "不能为空"));
        }
        SubsystemPO subsystem = subsystemMapper.selectById(cmd.subsystemId());
        if (subsystem == null) {
            throw new BizException(BizError.PARAM_INVALID, "所属系统不存在",
                Map.of("subsystemId", cmd.subsystemId()));
        }
        String name = requireName(cmd.name());
        requireNameAvailable(cmd.subsystemId(), name, null);
        UnitType type = parseEnum(UnitType.class, cmd.type(), UnitType.UNIT, "type", "类型");
        String du = optionalMaxLength(cmd.du(), DU_MAX_LENGTH, "du", "DU");
        String ownerId = requireActiveEmployee(cmd.ownerId(), "ownerId", "负责人");
        String frameworkTemplate = optionalMaxLength(
            cmd.frameworkTemplate(), FRAMEWORK_TEMPLATE_MAX_LENGTH, "frameworkTemplate", "框架模板");
        BranchMode branchMode = parseEnum(
            BranchMode.class, cmd.branchMode(), BranchMode.STANDARD, "branchMode", "分支模式");
        String description = optionalMaxLength(
            cmd.description(), DESCRIPTION_MAX_LENGTH, "description", "描述");
        String modulePath = optionalMaxLength(
            cmd.modulePath(), MODULE_PATH_MAX_LENGTH, "modulePath", "模块路径");
        String deployType = optionalMaxLength(
            cmd.deployType(), DEPLOY_TYPE_MAX_LENGTH, "deployType", "部署类型");
        String imageType = optionalMaxLength(
            cmd.imageType(), IMAGE_TYPE_MAX_LENGTH, "imageType", "镜像类型");
        String imageName = optionalMaxLength(
            cmd.imageName(), IMAGE_NAME_MAX_LENGTH, "imageName", "镜像名称");
        String module = optionalMaxLength(
            cmd.module(), MODULE_LABEL_MAX_LENGTH, "module", "模块");
        String tag = optionalMaxLength(cmd.tag(), TAG_MAX_LENGTH, "tag", "标签");

        Instant now = Instant.now();
        DeployUnit unit = new DeployUnit(null, cmd.subsystemId(), name, type, du, ownerId,
            frameworkTemplate, branchMode, UnitStatus.ACTIVE, description, modulePath,
            deployType, imageType, imageName, module, tag, now, now);
        DeployUnitPO po = DeployUnitPO.of(unit);
        try {
            unitMapper.insert(po);
        } catch (DuplicateKeyException ex) {
            // Concurrent create won the unique-key race after our pre-check.
            throw duplicateName(name);
        }
        return po.getId();
    }

    /**
     * Full replacement of the editable fields. The subsystem link is immutable; a blank
     * status keeps the current lifecycle value.
     */
    @Transactional
    public void update(long id, UpdateCmd cmd) {
        if (cmd == null) {
            throw new BizException(BizError.PARAM_INVALID, "更新参数不能为空");
        }
        DeployUnit existing = loadScoped(id);
        String name = requireName(cmd.name());
        requireNameAvailable(existing.subsystemId(), name, id);
        UnitType type = parseEnum(UnitType.class, cmd.type(), existing.type(), "type", "类型");
        String du = optionalMaxLength(cmd.du(), DU_MAX_LENGTH, "du", "DU");
        String ownerId = requireActiveEmployee(cmd.ownerId(), "ownerId", "负责人");
        String frameworkTemplate = optionalMaxLength(
            cmd.frameworkTemplate(), FRAMEWORK_TEMPLATE_MAX_LENGTH, "frameworkTemplate", "框架模板");
        BranchMode branchMode = parseEnum(
            BranchMode.class, cmd.branchMode(), existing.branchMode(), "branchMode", "分支模式");
        UnitStatus status = parseEnum(
            UnitStatus.class, cmd.status(), existing.status(), "status", "状态");
        String description = optionalMaxLength(
            cmd.description(), DESCRIPTION_MAX_LENGTH, "description", "描述");
        String modulePath = optionalMaxLength(
            cmd.modulePath(), MODULE_PATH_MAX_LENGTH, "modulePath", "模块路径");
        String deployType = optionalMaxLength(
            cmd.deployType(), DEPLOY_TYPE_MAX_LENGTH, "deployType", "部署类型");
        String imageType = optionalMaxLength(
            cmd.imageType(), IMAGE_TYPE_MAX_LENGTH, "imageType", "镜像类型");
        String imageName = optionalMaxLength(
            cmd.imageName(), IMAGE_NAME_MAX_LENGTH, "imageName", "镜像名称");
        String module = optionalMaxLength(
            cmd.module(), MODULE_LABEL_MAX_LENGTH, "module", "模块");
        String tag = optionalMaxLength(cmd.tag(), TAG_MAX_LENGTH, "tag", "标签");

        int rows;
        try {
            rows = unitMapper.update(null, Wrappers.<DeployUnitPO>lambdaUpdate()
                .eq(DeployUnitPO::getId, id)
                .set(DeployUnitPO::getName, name)
                .set(DeployUnitPO::getType, type.name())
                .set(DeployUnitPO::getDu, du)
                .set(DeployUnitPO::getOwnerId, ownerId)
                .set(DeployUnitPO::getFrameworkTemplate, frameworkTemplate)
                .set(DeployUnitPO::getBranchMode, branchMode.name())
                .set(DeployUnitPO::getStatus, status.name())
                .set(DeployUnitPO::getDescription, description)
                .set(DeployUnitPO::getModulePath, modulePath)
                .set(DeployUnitPO::getDeployType, deployType)
                .set(DeployUnitPO::getImageType, imageType)
                .set(DeployUnitPO::getImageName, imageName)
                .set(DeployUnitPO::getModule, module)
                .set(DeployUnitPO::getTag, tag)
                .set(DeployUnitPO::getUpdatedAt, Instant.now()));
        } catch (DuplicateKeyException ex) {
            throw duplicateName(name);
        }
        if (rows == 0) {
            throw notFound(id);
        }
    }

    public UnitView get(long id) {
        return toView(loadScoped(id));
    }

    /**
     * Unit list. The scope filter is applied as {@code id IN (scopeUnitIds)}; an empty
     * scope yields an empty page instead of an unrestricted query (fail-closed).
     */
    public PageResult<UnitView> page(PageQuery query) {
        requirePageQuery(query, MAX_PAGE_SIZE);
        String keyword = trimToNull(query.keyword());
        // module/tag are exact-match label filters, unlike the keyword which matches the name.
        String module = trimToNull(query.module());
        String tag = trimToNull(query.tag());
        UnitType type = query.type() == null || query.type().isBlank()
            ? null
            : parseEnum(UnitType.class, query.type(), null, "type", "类型");

        var user = UserContext.current();
        if (!user.allUnits() && user.scopeUnitIds().isEmpty()) {
            return new PageResult<>(0, query.pageNo(), query.pageSize(), List.of());
        }

        LambdaQueryWrapper<DeployUnitPO> wrapper = Wrappers.<DeployUnitPO>lambdaQuery()
            .in(!user.allUnits(), DeployUnitPO::getId, user.scopeUnitIds())
            .eq(query.subsystemId() != null, DeployUnitPO::getSubsystemId, query.subsystemId())
            .eq(type != null, DeployUnitPO::getType, type == null ? null : type.name())
            .eq(module != null, DeployUnitPO::getModule, module)
            .eq(tag != null, DeployUnitPO::getTag, tag)
            .like(keyword != null, DeployUnitPO::getName, keyword)
            .orderByAsc(DeployUnitPO::getName)
            .orderByAsc(DeployUnitPO::getId);

        Page<DeployUnitPO> page = unitMapper.selectPage(
            new Page<>(query.pageNo(), query.pageSize()), wrapper);
        List<UnitView> records = page.getRecords().stream()
            .map(po -> toView(po.toDomain()))
            .toList();
        return new PageResult<>(page.getTotal(), page.getCurrent(), page.getSize(), records);
    }

    /**
     * Upserts the given CODING task bindings. Kinds absent from {@code bindings} stay
     * untouched, so callers may configure a unit over several requests (as the publish
     * tests do). Repeating a kind inside one request is rejected as {@code PARAM_INVALID}.
     */
    @Transactional
    public void bindCodingTasks(long unitId, List<BindingCmd> bindings) {
        if (bindings == null || bindings.isEmpty()) {
            throw new BizException(BizError.PARAM_INVALID, "CODING 任务绑定不能为空",
                Map.of("bindings", "不能为空"));
        }
        loadScoped(unitId);

        Map<TaskKind, BindingCmd> byKind = new EnumMap<>(TaskKind.class);
        for (BindingCmd binding : bindings) {
            if (binding == null) {
                throw new BizException(BizError.PARAM_INVALID, "CODING 任务绑定不能为空");
            }
            TaskKind kind = parseEnum(TaskKind.class, binding.taskKind(), null, "taskKind", "任务类型");
            if (kind == null) {
                throw new BizException(BizError.PARAM_INVALID, "任务类型不能为空",
                    Map.of("taskKind", "不能为空"));
            }
            String taskId = requireMaxLength(
                binding.taskId(), TASK_ID_MAX_LENGTH, "taskId", "CODING 任务 ID");
            requireValidJson(binding.paramsJson(), "paramsJson");
            if (byKind.put(kind, binding) != null) {
                throw new BizException(BizError.PARAM_INVALID, "任务类型重复绑定：" + kind,
                    Map.of("taskKind", kind.name()));
            }
        }

        for (Map.Entry<TaskKind, BindingCmd> entry : byKind.entrySet()) {
            upsertBinding(unitId, entry.getKey(), entry.getValue());
        }
    }

    /** No-op when the three required task kinds are bound and enabled. */
    public void assertPublishable(long unitId) {
        loadScoped(unitId);
        Set<TaskKind> configured = enabledTaskKinds(unitId);
        List<String> missing = PUBLISH_REQUIRED.stream()
            .filter(kind -> !configured.contains(kind))
            .map(Enum::name)
            .toList();
        if (!missing.isEmpty()) {
            throw new BizException(BizError.UNIT_NOT_CONFIGURED,
                "部署单元未配置完整，缺少 CODING 任务：" + String.join("、", missing),
                Map.of("unitId", unitId, "missing", missing));
        }
    }

    /**
     * Plan id to use for a daily release: the {@code DAILY_DEPLOY} binding when present,
     * otherwise the {@code TEST_DEPLOY} binding (daily reuses the test deploy task and
     * only switches the target environment). Neither configured is a unit configuration
     * error.
     */
    public String resolveDailyDeployPlan(long unitId) {
        loadScoped(unitId);
        CodingTaskBindingPO daily = findBinding(unitId, TaskKind.DAILY_DEPLOY);
        if (daily != null) {
            return daily.getTaskId();
        }
        CodingTaskBindingPO test = findBinding(unitId, TaskKind.TEST_DEPLOY);
        if (test != null) {
            return test.getTaskId();
        }
        throw new BizException(BizError.UNIT_NOT_CONFIGURED,
            "未配置日常部署任务，且测试部署任务缺失", Map.of("unitId", unitId));
    }

    /** Adds a DE to a unit; the batch number defaults to {@code 0}. */
    @Transactional
    public long addEnvDe(long unitId, EnvDeCmd cmd) {
        DeployUnit unit = loadScoped(unitId);
        if (unit.type() == UnitType.LIB) {
            // FR-R-03: a second-party library has no deployment or environment config.
            throw new BizException(BizError.STATE_NOT_ALLOWED, "二方库不配置部署环境",
                Map.of("unitId", unitId, "type", unit.type().name()));
        }
        if (cmd == null) {
            throw new BizException(BizError.PARAM_INVALID, "DE 参数不能为空");
        }
        EnvKind envKind = parseEnum(EnvKind.class, cmd.envKind(), null, "envKind", "环境类型");
        if (envKind == null) {
            throw new BizException(BizError.PARAM_INVALID, "环境类型不能为空",
                Map.of("envKind", "不能为空"));
        }
        String deName = requireMaxLength(
            cmd.deName(), DE_NAME_MAX_LENGTH, "deName", "DE 名称");
        String envTag = optionalMaxLength(cmd.envTag(), ENV_TAG_MAX_LENGTH, "envTag", "环境标签");
        String resourceName = optionalMaxLength(
            cmd.resourceName(), RESOURCE_NAME_MAX_LENGTH, "resourceName", "资源名");
        String codingDeployTarget = optionalMaxLength(cmd.codingDeployTarget(),
            CODING_DEPLOY_TARGET_MAX_LENGTH, "codingDeployTarget", "CODING 部署目标");
        if (cmd.deployBatchNo() < 0) {
            throw new BizException(BizError.PARAM_INVALID, "部署批次号不能为负数",
                Map.of("deployBatchNo", cmd.deployBatchNo()));
        }

        Instant now = Instant.now();
        EnvDe de = new EnvDe(null, unitId, envKind, envTag, deName, resourceName,
            codingDeployTarget, cmd.deployBatchNo(), now, now);
        EnvDePO po = EnvDePO.of(de);
        try {
            envDeMapper.insert(po);
        } catch (DuplicateKeyException ex) {
            throw new BizException(BizError.DUPLICATE_NAME, "DE 名称已存在：" + deName,
                Map.of("deName", deName));
        }
        return po.getId();
    }

    /** Read model of a unit's CODING task bindings, sorted by task kind. */
    public record CodingTaskView(String taskKind, String taskId, String paramsJson, boolean enabled) {
    }

    /**
     * Lists the unit's CODING task bindings so the settings UI can show the current mapping
     * instead of blindly overwriting it. Read-only, but scoped like every other unit read.
     */
    public List<CodingTaskView> listCodingTasks(long unitId) {
        loadScoped(unitId);
        return bindingMapper.selectList(Wrappers.<CodingTaskBindingPO>lambdaQuery()
                .eq(CodingTaskBindingPO::getUnitId, unitId)
                .orderByAsc(CodingTaskBindingPO::getTaskKind))
            .stream()
            .map(b -> new CodingTaskView(b.getTaskKind(), b.getTaskId(), b.getParamsJson(),
                Boolean.TRUE.equals(b.getEnabled())))
            .toList();
    }

    /** DEs of a unit, ordered by {@code deploy_batch_no} (the C14 serial order). */
    public List<EnvDeView> listEnvDe(long unitId) {
        loadScoped(unitId);
        return envDeMapper.selectList(Wrappers.<EnvDePO>lambdaQuery()
                .eq(EnvDePO::getUnitId, unitId)
                .orderByAsc(EnvDePO::getDeployBatchNo)
                .orderByAsc(EnvDePO::getId))
            .stream()
            .map(po -> toView(po.toDomain()))
            .toList();
    }

    private void upsertBinding(long unitId, TaskKind kind, BindingCmd binding) {
        String taskId = requireMaxLength(
            binding.taskId(), TASK_ID_MAX_LENGTH, "taskId", "CODING 任务 ID");
        String paramsJson = trimToNull(binding.paramsJson());
        Instant now = Instant.now();
        int rows = bindingMapper.update(null, Wrappers.<CodingTaskBindingPO>lambdaUpdate()
            .eq(CodingTaskBindingPO::getUnitId, unitId)
            .eq(CodingTaskBindingPO::getTaskKind, kind.name())
            .set(CodingTaskBindingPO::getTaskId, taskId)
            .set(CodingTaskBindingPO::getParamsJson, paramsJson)
            .set(CodingTaskBindingPO::getEnabled, true)
            .set(CodingTaskBindingPO::getUpdatedAt, now));
        if (rows > 0) {
            return;
        }
        CodingTaskBinding entity = new CodingTaskBinding(
            null, unitId, kind, taskId, paramsJson, true, now, now);
        try {
            bindingMapper.insert(CodingTaskBindingPO.of(entity));
        } catch (DuplicateKeyException ex) {
            // Concurrent bind inserted the same kind first; fall back to the update.
            bindingMapper.update(null, Wrappers.<CodingTaskBindingPO>lambdaUpdate()
                .eq(CodingTaskBindingPO::getUnitId, unitId)
                .eq(CodingTaskBindingPO::getTaskKind, kind.name())
                .set(CodingTaskBindingPO::getTaskId, taskId)
                .set(CodingTaskBindingPO::getParamsJson, paramsJson)
                .set(CodingTaskBindingPO::getEnabled, true)
                .set(CodingTaskBindingPO::getUpdatedAt, Instant.now()));
        }
    }

    private Set<TaskKind> enabledTaskKinds(long unitId) {
        List<CodingTaskBindingPO> bindings = bindingMapper.selectList(
            Wrappers.<CodingTaskBindingPO>lambdaQuery()
                .eq(CodingTaskBindingPO::getUnitId, unitId)
                .eq(CodingTaskBindingPO::getEnabled, true));
        Set<TaskKind> kinds = new LinkedHashSet<>();
        for (CodingTaskBindingPO binding : bindings) {
            kinds.add(TaskKind.valueOf(binding.getTaskKind()));
        }
        return kinds;
    }

    private CodingTaskBindingPO findBinding(long unitId, TaskKind kind) {
        return bindingMapper.selectOne(Wrappers.<CodingTaskBindingPO>lambdaQuery()
            .eq(CodingTaskBindingPO::getUnitId, unitId)
            .eq(CodingTaskBindingPO::getTaskKind, kind.name())
            .eq(CodingTaskBindingPO::getEnabled, true));
    }

    private DeployUnit loadScoped(long id) {
        ScopeGuard.assertUnitInScope(id);
        DeployUnitPO po = unitMapper.selectById(id);
        if (po == null) {
            throw notFound(id);
        }
        return po.toDomain();
    }

    private void requireNameAvailable(long subsystemId, String name, Long excludingId) {
        LambdaQueryWrapper<DeployUnitPO> query = Wrappers.<DeployUnitPO>lambdaQuery()
            .eq(DeployUnitPO::getSubsystemId, subsystemId)
            .eq(DeployUnitPO::getName, name);
        if (excludingId != null) {
            query.ne(DeployUnitPO::getId, excludingId);
        }
        Long existing = unitMapper.selectCount(query);
        if (existing != null && existing > 0) {
            throw duplicateName(name);
        }
    }

    private void requireValidJson(String raw, String field) {
        String json = trimToNull(raw);
        if (json == null) {
            return;
        }
        try {
            objectMapper.readTree(json);
        } catch (JsonProcessingException ex) {
            throw new BizException(BizError.PARAM_INVALID, "参数模板不是合法 JSON",
                Map.of(field, json));
        }
    }

    private static void requirePageQuery(PageQuery query, int maxPageSize) {
        if (query == null) {
            throw new BizException(BizError.PARAM_INVALID, "分页参数不能为空");
        }
        if (query.pageNo() < 1) {
            throw new BizException(BizError.PARAM_INVALID, "页码必须大于 0",
                Map.of("pageNo", query.pageNo()));
        }
        if (query.pageSize() < 1 || query.pageSize() > maxPageSize) {
            throw new BizException(BizError.PARAM_INVALID,
                "每页条数必须在 1 到 " + maxPageSize + " 之间",
                Map.of("pageSize", query.pageSize()));
        }
    }

    private static String requireName(String raw) {
        return requireMaxLength(raw, NAME_MAX_LENGTH, "name", "部署单元名称");
    }

    private static String requireMaxLength(String raw, int maxLength, String field, String label) {
        String value = trimToNull(raw);
        if (value == null) {
            throw new BizException(BizError.PARAM_INVALID, label + "不能为空",
                Map.of(field, "不能为空"));
        }
        if (value.length() > maxLength) {
            throw new BizException(BizError.PARAM_INVALID,
                label + "长度不能超过 " + maxLength, Map.of(field, value));
        }
        return value;
    }

    private static String optionalMaxLength(String raw, int maxLength, String field, String label) {
        String value = trimToNull(raw);
        if (value != null && value.length() > maxLength) {
            throw new BizException(BizError.PARAM_INVALID,
                label + "长度不能超过 " + maxLength, Map.of(field, value));
        }
        return value;
    }

    private String requireActiveEmployee(String raw, String field, String label) {
        String userId = trimToNull(raw);
        if (userId == null) {
            throw new BizException(BizError.PARAM_INVALID, label + "不能为空",
                Map.of(field, "不能为空"));
        }
        if (!appUserRepository.existsActive(userId)) {
            throw new BizException(BizError.PARAM_INVALID, label + "不存在或已停用：" + userId,
                Map.of(field, userId));
        }
        return userId;
    }

    private static <E extends Enum<E>> E parseEnum(
            Class<E> type, String raw, E fallback, String field, String label) {
        String value = trimToNull(raw);
        if (value == null) {
            return fallback;
        }
        try {
            return Enum.valueOf(type, value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new BizException(BizError.PARAM_INVALID, label + "不合法：" + raw,
                Map.of(field, raw));
        }
    }

    private static BizException duplicateName(String name) {
        return new BizException(BizError.DUPLICATE_NAME, "部署单元名称已存在：" + name,
            Map.of("name", name));
    }

    private static BizException notFound(long id) {
        return new BizException(BizError.PARAM_INVALID, "部署单元不存在", Map.of("id", id));
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static UnitView toView(DeployUnit unit) {
        return new UnitView(
            unit.id(), unit.subsystemId(), unit.name(), unit.type().name(), unit.du(),
            unit.ownerId(), unit.frameworkTemplate(), unit.branchMode().name(),
            unit.status().name(), unit.description(), unit.modulePath(), unit.deployType(),
            unit.imageType(), unit.imageName(), unit.module(), unit.tag(),
            unit.createdAt(), unit.updatedAt());
    }

    private static EnvDeView toView(EnvDe de) {
        return new EnvDeView(
            de.id(), de.unitId(), de.envKind().name(), de.envTag(), de.deName(),
            de.resourceName(), de.codingDeployTarget(), de.deployBatchNo(),
            de.createdAt(), de.updatedAt());
    }

    /** Create command. The three-argument shorthand leaves the detail fields empty. */
    public record CreateCmd(
        Long subsystemId,
        String name,
        String type,
        String du,
        String ownerId,
        String frameworkTemplate,
        String branchMode,
        String description,
        String modulePath,
        String deployType,
        String imageType,
        String imageName,
        String module,
        String tag) {

        public CreateCmd(Long subsystemId, String name, String ownerId) {
            this(subsystemId, name, null, null, ownerId, null, null, null, null, null, null,
                null, null, null);
        }
    }

    /** Update command: full replacement of the editable fields, subsystem is immutable. */
    public record UpdateCmd(
        String name,
        String type,
        String du,
        String ownerId,
        String frameworkTemplate,
        String branchMode,
        String status,
        String description,
        String modulePath,
        String deployType,
        String imageType,
        String imageName,
        String module,
        String tag) {

        public UpdateCmd(String name, String ownerId) {
            this(name, null, null, ownerId, null, null, null, null, null, null, null, null,
                null, null);
        }
    }

    /** One CODING task reference; {@code paramsJson} is an optional JSON template. */
    public record BindingCmd(String taskKind, String taskId, String paramsJson) {

        public BindingCmd(String taskKind, String taskId) {
            this(taskKind, taskId, null);
        }
    }

    /** DE create command; {@code deployBatchNo} defaults to {@code 0}. */
    public record EnvDeCmd(
        String envKind,
        String envTag,
        String deName,
        String resourceName,
        String codingDeployTarget,
        int deployBatchNo) {

        public EnvDeCmd(String envKind, String deName) {
            this(envKind, null, deName, null, null, 0);
        }

        public EnvDeCmd(String envKind, String deName, int deployBatchNo) {
            this(envKind, null, deName, null, null, deployBatchNo);
        }
    }

    /**
     * Unit page request. {@code keyword} matches the name partially; {@code subsystemId},
     * {@code type}, {@code module} and {@code tag} are exact filters (the last two are
     * display labels, see {@link DeployUnit}).
     */
    public record PageQuery(int pageNo, int pageSize, String keyword, Long subsystemId,
                            String type, String module, String tag) {

        public PageQuery(int pageNo, int pageSize, String keyword, Long subsystemId, String type) {
            this(pageNo, pageSize, keyword, subsystemId, type, null, null);
        }

        public PageQuery(int pageNo, int pageSize) {
            this(pageNo, pageSize, null, null, null, null, null);
        }

        public PageQuery(int pageNo, int pageSize, String keyword) {
            this(pageNo, pageSize, keyword, null, null, null, null);
        }
    }

    /** Page response shape shared by the resource services. */
    public record PageResult<T>(long total, long pageNo, long pageSize, List<T> records) {

        public PageResult {
            records = records == null ? List.of() : List.copyOf(records);
        }
    }

    /** Read model exposed to the web layer. */
    public record UnitView(
        long id,
        long subsystemId,
        String name,
        String type,
        String du,
        String ownerId,
        String frameworkTemplate,
        String branchMode,
        String status,
        String description,
        String modulePath,
        String deployType,
        String imageType,
        String imageName,
        String module,
        String tag,
        Instant createdAt,
        Instant updatedAt) {
    }

    /** Read model of a DE exposed to the web layer. */
    public record EnvDeView(
        long id,
        long unitId,
        String envKind,
        String envTag,
        String deName,
        String resourceName,
        String codingDeployTarget,
        int deployBatchNo,
        Instant createdAt,
        Instant updatedAt) {
    }
}