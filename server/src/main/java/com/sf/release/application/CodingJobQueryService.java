package com.sf.release.application;

import com.sf.release.application.UnitAppService.UnitView;
import com.sf.release.common.BizError;
import com.sf.release.common.BizException;
import com.sf.release.domain.engine.CicdEngine;
import com.sf.release.domain.engine.EngineJob;
import com.sf.release.domain.engine.JobKind;
import com.sf.release.domain.resource.TaskKind;
import com.sf.release.infra.repo.SubsystemMapper;
import com.sf.release.infra.repo.po.SubsystemPO;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Asks the engine which build/deploy jobs a unit can bind, so the settings form offers the
 * project's real tasks instead of a free-text id.
 *
 * <p><strong>Project.</strong> A unit belongs to a system, and the system owns the CODING
 * project; the project is therefore read from {@code subsystem.coding_project} and never from
 * the request. A system without a project fails with {@code PARAM_INVALID} naming the system,
 * because an empty picker would look like "this project has no jobs".
 *
 * <p><strong>Scope.</strong> The unit is loaded through {@link UnitAppService#get}, which is
 * fail-closed, so an unscoped caller cannot enumerate the tasks of a unit it cannot see.
 *
 * <p><strong>Failure.</strong> A missing or unreachable CODING connection propagates as
 * {@code ENGINE_UNAVAILABLE}; the caller (and the UI) can then fall back to typing an id. An
 * empty list always means the project really has no jobs.
 */
@Service
public class CodingJobQueryService {

    private final UnitAppService unitAppService;
    private final SubsystemMapper subsystemMapper;
    private final CicdEngine engine;

    public CodingJobQueryService(UnitAppService unitAppService,
                                 SubsystemMapper subsystemMapper,
                                 CicdEngine engine) {
        this.unitAppService = unitAppService;
        this.subsystemMapper = subsystemMapper;
        this.engine = engine;
    }

    /** One selectable job: the id a binding stores, the label and the engine's kind word. */
    public record CodingJobView(String taskId, String name, String type) {
    }

    /**
     * Lists the jobs matching {@code taskKind}: {@code BUILD} asks the build namespace, every
     * deploy kind asks the deploy namespace (a CD project can hold test, production and daily
     * deploy jobs alike).
     */
    public List<CodingJobView> list(long unitId, String taskKind) {
        TaskKind kind = parseTaskKind(taskKind);
        UnitView unit = unitAppService.get(unitId);
        String project = codingProjectOf(unit.subsystemId());
        List<EngineJob> jobs = engine.listJobs(project, jobKindOf(kind));
        return jobs.stream()
            .map(job -> new CodingJobView(job.taskId(), job.name(), job.type()))
            .toList();
    }

    private String codingProjectOf(long subsystemId) {
        SubsystemPO subsystem = subsystemMapper.selectById(subsystemId);
        String project = subsystem == null ? null : trimToNull(subsystem.getCodingProject());
        if (project == null) {
            throw new BizException(BizError.PARAM_INVALID,
                "所属系统未配置 CODING 项目，无法查询任务列表",
                Map.of("subsystemId", subsystemId));
        }
        return project;
    }

    private static TaskKind parseTaskKind(String raw) {
        String value = trimToNull(raw);
        if (value == null) {
            throw new BizException(BizError.PARAM_INVALID, "任务类型不能为空",
                Map.of("taskKind", "不能为空"));
        }
        try {
            return TaskKind.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new BizException(BizError.PARAM_INVALID, "任务类型不合法：" + raw,
                Map.of("taskKind", raw));
        }
    }

    private static JobKind jobKindOf(TaskKind kind) {
        return kind == TaskKind.BUILD ? JobKind.BUILD : JobKind.DEPLOY;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}