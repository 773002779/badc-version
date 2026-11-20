package com.sf.release.application;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sf.release.common.BizError;
import com.sf.release.common.BizException;
import com.sf.release.domain.resource.DeployUnit;
import com.sf.release.domain.resource.TaskKind;
import com.sf.release.domain.task.GateStatus;
import com.sf.release.domain.task.ItemEvent;
import com.sf.release.domain.task.ItemStateMachine;
import com.sf.release.domain.task.ItemStatus;
import com.sf.release.domain.task.ReleaseItem;
import com.sf.release.infra.repo.CodingTaskBindingMapper;
import com.sf.release.infra.repo.DeployUnitMapper;
import com.sf.release.infra.repo.ReleaseItemMapper;
import com.sf.release.infra.repo.ReleaseTaskMapper;
import com.sf.release.infra.repo.po.CodingTaskBindingPO;
import com.sf.release.infra.repo.po.DeployUnitPO;
import com.sf.release.infra.repo.po.ReleaseItemPO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 变更项 use cases: create an item and advance it through the main state machine.
 *
 * <p><strong>Creation.</strong> The item is created {@code PENDING} with gate status
 * {@code TEST_PENDING}. Its type, {@code module_path} and CODING build task are snapshotted
 * from the deployment unit (the build task comes from the unit's enabled {@code BUILD}
 * binding, so an item can never point at an unconfigured build). The requested branch is
 * trimmed and combined with {@code (taskId, unitId)} in the unique key; a duplicate is
 * reported as 40010. Unknown task/unit and missing fields are 40000.
 *
 * <p><strong>Transitions.</strong> {@link #transit(long, String)} is the only writer of
 * {@code release_item.status}. Each attempt loads the row, asks
 * {@link ItemStateMachine} for the next status and writes it with
 * {@code UPDATE ... WHERE id = ? AND row_version = ?}; a lost update is re-read and retried
 * up to {@value #MAX_CAS_ATTEMPTS} times, then fails with 40001 (tech design §4.3). Every
 * attempt runs in its own {@link TransactionTemplate} transaction so a retry observes the
 * winner's committed row (MySQL REPEATABLE READ would hide it inside one transaction).
 * Callers must therefore not wrap this method in an outer transaction.
 */
@Service
public class ItemAppService {

    private static final Logger log = LoggerFactory.getLogger(ItemAppService.class);

    private static final int MAX_CAS_ATTEMPTS = 3;
    private static final int SOURCE_BRANCH_MAX_LENGTH = 128;
    private static final int VERSION_MAX_LENGTH = 64;
    private static final int CRID_RANDOM_LENGTH = 6;
    private static final DateTimeFormatter CRID_DATE =
        DateTimeFormatter.ofPattern("yyyyMMdd", Locale.ROOT);

    private final ReleaseItemMapper itemMapper;
    private final ReleaseTaskMapper taskMapper;
    private final DeployUnitMapper unitMapper;
    private final CodingTaskBindingMapper bindingMapper;
    private final TransactionTemplate transactionTemplate;

    public ItemAppService(ReleaseItemMapper itemMapper,
                          ReleaseTaskMapper taskMapper,
                          DeployUnitMapper unitMapper,
                          CodingTaskBindingMapper bindingMapper,
                          PlatformTransactionManager transactionManager) {
        this.itemMapper = itemMapper;
        this.taskMapper = taskMapper;
        this.unitMapper = unitMapper;
        this.bindingMapper = bindingMapper;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /**
     * Creates a {@code PENDING} item in an existing task and returns its id. The build task
     * and module path are snapshots of the unit.
     */
    @Transactional
    public long create(long taskId, CreateItemCmd cmd) {
        if (cmd == null) {
            throw new BizException(BizError.PARAM_INVALID, "创建参数不能为空");
        }
        if (taskMapper.selectById(taskId) == null) {
            throw new BizException(BizError.PARAM_INVALID, "发布任务不存在",
                Map.of("taskId", taskId));
        }
        if (cmd.unitId() == null) {
            throw new BizException(BizError.PARAM_INVALID, "部署单元不能为空",
                Map.of("unitId", "不能为空"));
        }
        DeployUnitPO unitPo = unitMapper.selectById(cmd.unitId());
        if (unitPo == null) {
            throw new BizException(BizError.PARAM_INVALID, "部署单元不存在",
                Map.of("unitId", cmd.unitId()));
        }
        DeployUnit unit = unitPo.toDomain();
        String sourceBranch = requireText(
            cmd.sourceBranch(), SOURCE_BRANCH_MAX_LENGTH, "sourceBranch", "分支");
        String version = requireText(cmd.version(), VERSION_MAX_LENGTH, "version", "变更原因");
        if (cmd.planReleaseDate() == null) {
            throw new BizException(BizError.PARAM_INVALID, "计划发布时间不能为空",
                Map.of("planReleaseDate", "不能为空"));
        }
        String buildPlanId = requireBuildPlan(unit);
        requireNotDuplicate(taskId, unit.id(), sourceBranch);

        Instant now = Instant.now();
        ReleaseItem item = new ReleaseItem(null, taskId, newCrid(), unit.type(), unit.id(),
            buildPlanId, sourceBranch, unit.modulePath(), version, cmd.planReleaseDate(),
            ItemStatus.PENDING, GateStatus.TEST_PENDING, null, null, null, 0, now, now);
        ReleaseItemPO po = ReleaseItemPO.of(item);
        try {
            itemMapper.insert(po);
        } catch (DuplicateKeyException ex) {
            // A concurrent create won the unique-key race after our pre-check.
            throw duplicate(taskId, unit.id(), sourceBranch);
        }
        log.info("release item created: id={}, taskId={}, unitId={}, branch={}",
            po.getId(), taskId, unit.id(), sourceBranch);
        return po.getId();
    }

    /** Reads one item; the web layer sees primitives and enum names only. */
    public ItemView view(long itemId) {
        return toView(load(itemId));
    }

    /**
     * Applies one state-machine event to the item and returns the new state. The event is
     * the API string form of {@link ItemEvent} (case-insensitive). See the class Javadoc for
     * the optimistic-lock retry.
     */
    public ItemView transit(long itemId, String event) {
        ItemEvent parsed = parseEvent(event);
        ItemStatus lastKnownStatus = null;
        int lastKnownVersion = -1;
        for (int attempt = 1; attempt <= MAX_CAS_ATTEMPTS; attempt++) {
            try {
                ItemView view = transactionTemplate.execute(tx -> applyTransition(itemId, parsed));
                return Objects.requireNonNull(view, "transition result");
            } catch (LostUpdateException ex) {
                lastKnownStatus = ex.currentStatus();
                lastKnownVersion = ex.rowVersion();
                log.warn("lost update on release item {} ({}), attempt {}/{}",
                    itemId, parsed, attempt, MAX_CAS_ATTEMPTS);
            }
        }
        String currentStatus = lastKnownStatus == null ? "UNKNOWN" : lastKnownStatus.name();
        log.error("release item {} transition {} aborted after {} CAS attempts;"
                + " currentStatus={}, rowVersion={}",
            itemId, parsed, MAX_CAS_ATTEMPTS, currentStatus, lastKnownVersion);
        throw new BizException(BizError.STATE_NOT_ALLOWED,
            "变更项并发更新冲突，已重试 " + MAX_CAS_ATTEMPTS + " 次",
            Map.of("itemId", itemId, "currentStatus", currentStatus));
    }

    private ItemView applyTransition(long itemId, ItemEvent event) {
        ReleaseItem current = load(itemId);
        ItemStatus next = ItemStateMachine.transit(current, event);
        int nextVersion = current.rowVersion() + 1;
        Instant now = Instant.now();
        int rows = itemMapper.update(null, Wrappers.<ReleaseItemPO>lambdaUpdate()
            .eq(ReleaseItemPO::getId, itemId)
            .eq(ReleaseItemPO::getRowVersion, current.rowVersion())
            .set(ReleaseItemPO::getStatus, next.name())
            .set(ReleaseItemPO::getRowVersion, nextVersion)
            .set(ReleaseItemPO::getUpdatedAt, now));
        if (rows != 1) {
            throw new LostUpdateException(current.status(), current.rowVersion());
        }
        return toView(current.withStatus(next), nextVersion, now);
    }

    private ReleaseItem load(long itemId) {
        ReleaseItemPO po = itemMapper.selectById(itemId);
        if (po == null) {
            throw notFound(itemId);
        }
        return po.toDomain();
    }

    private String requireBuildPlan(DeployUnit unit) {
        CodingTaskBindingPO binding = bindingMapper.selectOne(
            Wrappers.<CodingTaskBindingPO>lambdaQuery()
                .eq(CodingTaskBindingPO::getUnitId, unit.id())
                .eq(CodingTaskBindingPO::getTaskKind, TaskKind.BUILD.name())
                .eq(CodingTaskBindingPO::getEnabled, true));
        if (binding == null || binding.getTaskId() == null || binding.getTaskId().isBlank()) {
            throw new BizException(BizError.UNIT_NOT_CONFIGURED,
                "部署单元未配置构建任务，无法创建变更项",
                Map.of("unitId", unit.id()));
        }
        return binding.getTaskId().trim();
    }

    private void requireNotDuplicate(long taskId, long unitId, String sourceBranch) {
        Long existing = itemMapper.selectCount(Wrappers.<ReleaseItemPO>lambdaQuery()
            .eq(ReleaseItemPO::getTaskId, taskId)
            .eq(ReleaseItemPO::getUnitId, unitId)
            .eq(ReleaseItemPO::getSourceBranch, sourceBranch));
        if (existing != null && existing > 0) {
            throw duplicate(taskId, unitId, sourceBranch);
        }
    }

    /**
     * Display id of a 变更项, for example {@code CR-20261121-1A2B3C}. It is not a key: the
     * unique key is {@code (task_id, unit_id, source_branch)} and the database id is the
     * identity.
     */
    private static String newCrid() {
        String suffix = UUID.randomUUID().toString().replace("-", "")
            .substring(0, CRID_RANDOM_LENGTH).toUpperCase(Locale.ROOT);
        return "CR-" + CRID_DATE.format(LocalDate.now(ZoneOffset.UTC)) + "-" + suffix;
    }

    private static ItemEvent parseEvent(String raw) {
        String value = trimToNull(raw);
        if (value == null) {
            throw new BizException(BizError.PARAM_INVALID, "事件不能为空",
                Map.of("event", "不能为空"));
        }
        try {
            return ItemEvent.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new BizException(BizError.PARAM_INVALID, "事件不合法：" + value,
                Map.of("event", value));
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

    private static ItemView toView(ReleaseItem item) {
        return toView(item, item.rowVersion(), item.updatedAt());
    }

    private static ItemView toView(ReleaseItem item, int rowVersion, Instant updatedAt) {
        return new ItemView(
            item.id(),
            item.taskId(),
            item.crid(),
            item.type().name(),
            item.unitId(),
            item.buildPlanId(),
            item.sourceBranch(),
            item.modulePath(),
            item.version(),
            item.planReleaseDate(),
            item.status().name(),
            item.gateStatus().name(),
            item.baseCommit(),
            item.lastTestPassBy(),
            item.lastTestPassAt(),
            rowVersion,
            item.createdAt(),
            updatedAt);
    }

    private static BizException duplicate(long taskId, long unitId, String sourceBranch) {
        return new BizException(BizError.DUPLICATE_NAME,
            "同一任务下该单元与分支已存在变更项",
            Map.of("taskId", taskId, "unitId", unitId, "sourceBranch", sourceBranch));
    }

    private static BizException notFound(long itemId) {
        return new BizException(BizError.PARAM_INVALID, "变更项不存在", Map.of("id", itemId));
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** Create command; the branch, version and planned date are required. */
    public record CreateItemCmd(
        Long unitId,
        String sourceBranch,
        String version,
        LocalDate planReleaseDate) {
    }

    /** Read model: enum values are flattened to their names for the web layer. */
    public record ItemView(
        long id,
        long taskId,
        String crid,
        String type,
        long unitId,
        String buildPlanId,
        String sourceBranch,
        String modulePath,
        String version,
        LocalDate planReleaseDate,
        String status,
        String gateStatus,
        String baseCommit,
        String lastTestPassBy,
        Instant lastTestPassAt,
        int rowVersion,
        Instant createdAt,
        Instant updatedAt) {
    }

    /** Signals that the row_version CAS lost; the caller retries in a fresh transaction. */
    private static final class LostUpdateException extends RuntimeException {

        private final ItemStatus currentStatus;
        private final int rowVersion;

        private LostUpdateException(ItemStatus currentStatus, int rowVersion) {
            super("lost update on release item status " + currentStatus);
            this.currentStatus = currentStatus;
            this.rowVersion = rowVersion;
        }

        private ItemStatus currentStatus() {
            return currentStatus;
        }

        private int rowVersion() {
            return rowVersion;
        }
    }
}