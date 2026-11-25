package com.sf.release.application;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sf.release.common.BizError;
import com.sf.release.common.BizException;
import com.sf.release.domain.resource.DeployUnit;
import com.sf.release.domain.resource.TaskKind;
import com.sf.release.domain.resource.UnitType;
import com.sf.release.infra.repo.CodingTaskBindingMapper;
import com.sf.release.infra.repo.DeployUnitMapper;
import com.sf.release.infra.repo.po.CodingTaskBindingPO;
import com.sf.release.infra.repo.po.DeployUnitPO;
import com.sf.release.infra.security.ScopeGuard;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The second-party library use cases (P6-A design §4): the documented dependents heuristic and
 * the publish-time configuration check.
 *
 * <p><strong>Dependents are a heuristic.</strong> {@code GET /api/v1/libs/{unitId}/dependents}
 * returns the deployment units of the <em>same subsystem</em> whose {@code module_path} equals the
 * library's {@code artifactId}. That is a documented stand-in for the artifact repository's
 * dependency API (Q4 is still open), which is why the response carries
 * {@code source: "MODULE_PATH"}: a future implementation can change the value without changing
 * the contract. Nothing is inferred beyond that equality.
 *
 * <p><strong>Publish configuration.</strong> A LIB publish needs the artifact coordinates, an
 * enabled {@code BUILD} binding and an enabled {@code LIB_PUBLISH} (artifact upload) binding. It
 * deliberately does <em>not</em> need {@code TEST_DEPLOY}/{@code PROD_DEPLOY} or any DE: a library
 * has no deployment or environment (FR-R-03). A missing piece is reported as 40001 with the
 * missing markers, before any order/lock is created (P6-A design §5 item 5).
 */
@Service
public class LibAppService {

    /** The only dependents source this slice implements; part of the API contract. */
    public static final String SOURCE_MODULE_PATH = "MODULE_PATH";

    /** Payload marker: the unit has no artifact coordinates (a legacy LIB row). */
    static final String MISSING_COORDINATES = "ARTIFACT_COORDINATES";

    /** Payload marker: the unit has no enabled BUILD binding. */
    static final String MISSING_BUILD = "BUILD";

    /** Payload marker: the unit has no enabled LIB_PUBLISH (artifact upload) binding. */
    static final String MISSING_LIB_PUBLISH = "LIB_PUBLISH";

    private final DeployUnitMapper unitMapper;
    private final CodingTaskBindingMapper bindingMapper;

    public LibAppService(DeployUnitMapper unitMapper, CodingTaskBindingMapper bindingMapper) {
        this.unitMapper = Objects.requireNonNull(unitMapper, "unitMapper must not be null");
        this.bindingMapper = Objects.requireNonNull(bindingMapper, "bindingMapper must not be null");
    }

    /**
     * The documented dependents heuristic of one library.
     *
     * @throws BizException 40000 unknown unit; 40001 the unit is not a LIB (or has no artifact
     *                      coordinates yet); 40009 the caller has no scope on the unit
     */
    public DependentsView dependents(long libUnitId) {
        DeployUnit lib = requireLib(libUnitId);
        String artifactId = lib.artifactArtifactId();
        if (artifactId == null) {
            throw new BizException(BizError.STATE_NOT_ALLOWED,
                "二方库未配置制品坐标，无法计算依赖单元",
                Map.of("unitId", libUnitId, "missing", List.of(MISSING_COORDINATES)));
        }
        List<DependentView> dependents = unitMapper.selectList(
                Wrappers.<DeployUnitPO>lambdaQuery()
                    .eq(DeployUnitPO::getSubsystemId, lib.subsystemId())
                    .eq(DeployUnitPO::getType, UnitType.UNIT.name())
                    .eq(DeployUnitPO::getModulePath, artifactId)
                    .orderByAsc(DeployUnitPO::getName)
                    .orderByAsc(DeployUnitPO::getId))
            .stream()
            .map(po -> new DependentView(po.getId() == null ? 0L : po.getId(), po.getName(),
                po.getModulePath()))
            .toList();
        return new DependentsView(libUnitId, artifactId, SOURCE_MODULE_PATH, dependents);
    }

    /**
     * Loads {@code unitId} as a LIB and enforces the caller's data scope.
     *
     * @throws BizException 40000 unknown unit; 40001 not a LIB; 40009 out of scope
     */
    public DeployUnit requireLib(long unitId) {
        ScopeGuard.assertUnitInScope(unitId);
        DeployUnitPO po = unitMapper.selectById(unitId);
        if (po == null) {
            throw new BizException(BizError.PARAM_INVALID, "部署单元不存在",
                Map.of("unitId", unitId));
        }
        DeployUnit unit = po.toDomain();
        if (unit.type() != UnitType.LIB) {
            throw new BizException(BizError.STATE_NOT_ALLOWED,
                "该部署单元不是二方库，接口只对 LIB 生效",
                Map.of("unitId", unitId, "type", unit.type().name()));
        }
        return unit;
    }

    /**
     * Asserts that a LIB unit can publish: artifact coordinates, {@code BUILD} binding and
     * {@code LIB_PUBLISH} binding. Runs before the unit lock, so a misconfigured library reserves
     * nothing.
     *
     * @throws BizException 40001 not a LIB or incomplete configuration; 40009 out of scope
     */
    public void assertPublishable(long unitId) {
        DeployUnit unit = requireLib(unitId);
        List<String> missing = new ArrayList<>(3);
        if (!unit.hasArtifactCoordinates()) {
            missing.add(MISSING_COORDINATES);
        }
        if (enabledTaskId(unitId, TaskKind.BUILD) == null) {
            missing.add(MISSING_BUILD);
        }
        if (enabledTaskId(unitId, TaskKind.LIB_PUBLISH) == null) {
            missing.add(MISSING_LIB_PUBLISH);
        }
        if (!missing.isEmpty()) {
            throw new BizException(BizError.STATE_NOT_ALLOWED,
                "二方库未配置完整，缺少：" + String.join("、", missing),
                Map.of("unitId", unitId, "missing", List.copyOf(missing)));
        }
    }

    private String enabledTaskId(long unitId, TaskKind kind) {
        CodingTaskBindingPO binding = bindingMapper.selectOne(
            Wrappers.<CodingTaskBindingPO>lambdaQuery()
                .eq(CodingTaskBindingPO::getUnitId, unitId)
                .eq(CodingTaskBindingPO::getTaskKind, kind.name())
                .eq(CodingTaskBindingPO::getEnabled, true));
        if (binding == null || binding.getTaskId() == null || binding.getTaskId().isBlank()) {
            return null;
        }
        return binding.getTaskId().trim();
    }

    /** One deployment unit that depends on the library by module-path equality. */
    public record DependentView(long unitId, String name, String modulePath) {
    }

    /** The dependents response; {@code source} is the documented heuristic identifier. */
    public record DependentsView(long libUnitId, String artifactId, String source,
                                 List<DependentView> dependents) {

        public DependentsView {
            dependents = dependents == null ? List.of() : List.copyOf(dependents);
        }
    }
}