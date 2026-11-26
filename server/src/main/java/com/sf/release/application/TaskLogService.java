package com.sf.release.application;

import com.sf.release.common.BizError;
import com.sf.release.common.BizException;
import com.sf.release.domain.engine.CicdEngine;
import com.sf.release.domain.engine.JobKind;
import com.sf.release.domain.pipeline.NodeTask;
import com.sf.release.domain.pipeline.TaskType;
import com.sf.release.domain.pipeline.port.PipelineRepository;
import com.sf.release.infra.repo.ReleaseQueryMapper;
import com.sf.release.infra.repo.po.PipelineInstancePO;
import com.sf.release.infra.repo.po.ReleaseHeadRow;
import com.sf.release.infra.security.ScopeGuard;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Objects;

/**
 * The 任务日志代理 use case (P6-G design §2 / requirements PART-04).
 *
 * <p><strong>Read-only proxy.</strong> The platform stores only the engine-side job id on a
 * {@code node_task} row; the raw log lives in the CI/CD engine. This service maps the task type to
 * the engine's job namespace ({@link JobKind}) and passes {@code offset}/{@code limit} through to
 * {@link CicdEngine#logs}. It never writes anything and never falls back to a different job kind:
 * build, deploy and publish jobs live in separate namespaces, so guessing one would serve the log
 * of a different job.
 *
 * <p><strong>Kinds.</strong> {@code CODE_CHECK}/{@code PKG_BUILD}/{@code IMG_BUILD} are build
 * jobs ({@code BUILD}); {@code DEPLOY} is a deploy job; {@code LIB_PUBLISH} is the artifact upload
 * job ({@code PUBLISH}). A {@code CHECK} task has no engine job at all — the gate check is polled
 * through its own endpoint — and every other task kind is refused with 40001; the page does not
 * render a log action for those rows.
 *
 * <p><strong>Only the latest instance.</strong> Historical executions are read-only evidence and
 * the engine may already have dropped their logs, so only a task of the newest instance of the
 * 发布单 is served. A task of an older instance is 40001; a task of another 发布单 is 40000. The
 * unit's data scope is enforced before the task's business status is examined (40009).
 *
 * <p><strong>Engine failures are passed through.</strong> {@code EngineException} already carries
 * the contract code (50001 unavailable / 50002 timeout) and the adapter's redacted message; the
 * web layer renders it, so wrapping it again would only lose the distinction.
 */
@Service
public class TaskLogService {

    /** Engine-side maximum page size (P6-G design §2). */
    public static final int MAX_LIMIT = 8000;

    /** Default page size when the caller omits {@code limit}. */
    public static final int DEFAULT_LIMIT = MAX_LIMIT;

    private final ReleaseQueryMapper queryMapper;
    private final PipelineRepository pipelineRepository;
    private final CicdEngine engine;

    public TaskLogService(ReleaseQueryMapper queryMapper,
                          PipelineRepository pipelineRepository,
                          CicdEngine engine) {
        this.queryMapper = Objects.requireNonNull(queryMapper, "queryMapper must not be null");
        this.pipelineRepository = Objects.requireNonNull(pipelineRepository,
            "pipelineRepository must not be null");
        this.engine = Objects.requireNonNull(engine, "engine must not be null");
    }

    /**
     * Reads one page of a task's engine log.
     *
     * @param offset zero-based character offset passed to the engine
     * @param limit  1..{@value #MAX_LIMIT}; the engine's own page size limit
     * @throws BizException 40000 blank/unknown publish number, unknown task or out-of-range
     *                      offset/limit; 40009 the unit is outside the caller's data scope;
     *                      40001 a historical-instance task, a task without an engine job
     *                      (CHECK), a missing {@code external_id}, or an unmapped task type;
     *                      {@code EngineException} (50001/50002) when the engine call itself fails
     */
    public LogView logs(String publishNo, long taskId, int offset, int limit) {
        if (offset < 0) {
            throw new BizException(BizError.PARAM_INVALID, "offset 不能小于 0",
                Map.of("offset", offset));
        }
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new BizException(BizError.PARAM_INVALID,
                "limit 必须在 1 到 " + MAX_LIMIT + " 之间", Map.of("limit", limit));
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
        ScopeGuard.assertUnitInScope(head.getUnitId());
        if (head.getInstanceId() == null
            || !head.getInstanceId().equals(task.instanceId())) {
            throw new BizException(BizError.STATE_NOT_ALLOWED,
                "历史实例只读：只提供最新实例任务的日志",
                Map.of("publishNo", no, "taskId", taskId, "instanceId", task.instanceId(),
                    "latestInstanceId", head.getInstanceId()));
        }
        JobKind kind = jobKindOf(task.taskType());
        if (kind == null) {
            throw new BizException(BizError.STATE_NOT_ALLOWED,
                "该任务没有引擎作业日志", Map.of("taskId", taskId,
                    "taskType", task.taskType().name()));
        }
        String externalId = task.externalId();
        if (externalId == null || externalId.isBlank()) {
            throw new BizException(BizError.STATE_NOT_ALLOWED,
                "任务没有记录引擎作业号，无法读取日志",
                Map.of("taskId", taskId, "taskType", task.taskType().name()));
        }
        String content = engine.logs(externalId, kind, offset, limit);
        if (content == null) {
            content = "";
        }
        // The engine returns the raw body and does not tell us whether another page exists; a full
        // page is treated as truncated so the page can offer 加载更多 without hiding lines.
        return new LogView(content, content.length() >= limit, offset, limit);
    }

    /**
     * The engine namespace of one task type, or {@code null} when the task has no engine job.
     * {@code CHECK} is deliberately unmapped: the gate check has its own polling endpoint and the
     * page does not show a log action for it.
     */
    static JobKind jobKindOf(TaskType taskType) {
        if (taskType == null) {
            return null;
        }
        return switch (taskType) {
            case CODE_CHECK, PKG_BUILD, IMG_BUILD -> JobKind.BUILD;
            case DEPLOY -> JobKind.DEPLOY;
            case LIB_PUBLISH -> JobKind.PUBLISH;
            case CHECK, AUTO_TEST, ROLLBACK, DAILY_DEPLOY -> null;
        };
    }

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

    /**
     * One page of raw log text. {@code truncated} means "a full page was returned, there may be
     * more"; the caller reads the next page with {@code offset + limit}.
     */
    public record LogView(String content, boolean truncated, int offset, int limit) {
    }
}